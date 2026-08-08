package com.billy65536.infrastructure.security.builtin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.billy65536.infrastructure.core.config.ConfigAccessor;
import com.billy65536.infrastructure.core.config.ConfigDescriptor;
import com.billy65536.infrastructure.core.config.ConfigAccessException;
import com.billy65536.infrastructure.core.config.ConfigLockedException;
import com.billy65536.infrastructure.core.config.ConfigManager;
import com.billy65536.infrastructure.core.config.ConfigPath;
import com.billy65536.infrastructure.core.module.IModule;
import com.billy65536.infrastructure.core.module.ModuleRegistry;
import com.billy65536.infrastructure.security.core.internal.AttributedValue;
import com.billy65536.infrastructure.security.core.internal.Origin;
import com.billy65536.infrastructure.security.core.policy.ISecurityExecutor;
import com.billy65536.infrastructure.security.core.policy.SecurityManager;
import com.billy65536.infrastructure.security.core.policy.SecurityPolicyConfig;

import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 纯客户端「服务端 opt-in」配置锁定执行器（infrastructure 核心安全层）。
 *
 * <p>原位于 chunkscanner 的 {@code security.server_optin.ConfigurationLocker}，现上移为
 * infrastructure 的<b>通用</b>配置锁定能力，供任意注册为模块的 mod 复用。逻辑基本继承原实现：
 * 锁定登记、强制值重放、以及针对命令 / GUI / 手动编辑磁盘文件三条修改通道的防绕过
 * （经配置对象的 {@code validatePostLoad()} → {@link #applyAll}）均已闭环。</p>
 *
 * <h2>本类即执行器</h2>
 *
 * <p>本类<b>自身实现</b> {@link ISecurityExecutor}，以 {@link #EXECUTOR_ID}
 * （{@code security:config-locker}）登记在 {@code security:server-optin} 策略之下。
 * 策略激活时 {@link #onPolicyChanged(SecurityPolicyConfig)} 收到合并后的全量锁配置，
 * 停用时收到 {@code null}，均幂等。</p>
 *
 * <p>此处刻意<b>不</b>再单设一个转发用的执行器类：锁定表本身就是「被执行的安全约束」，
 * 拆成「引擎 + 一行转发的适配器」只会制造两个必须同步演进的类。</p>
 *
 * <h2>三表合一</h2>
 *
 * <p>原 {@code lockStatus / defaultLocks / preLockValues} 三张平行表合并为单张
 * {@link #activeConstraints}（{@code Map<String, LockConstraint>}）。{@code LockConstraint}
 * 聚合完整路径 / 强制值 / 原值快照 / 来源身份，在 {@link #onPolicyChanged} 内一次性 diff：</p>
 * <ul>
 *   <li>目标有、当前无 → 抓原值快照，建立约束；</li>
 *   <li>目标无、当前有 → 用快照还原并 {@code saveConfig} 持久化，移除约束；</li>
 *   <li>两者都有但强制值 / 来源变化 → 保留原快照（绝不刷新），更新强制值与来源；</li>
 *   <li>最后统一 {@link #applyAllRegistered()} 重放。</li>
 * </ul>
 *
 * <h2>来源溯源</h2>
 *
 * <p>每条约束携带框架回填的来源身份，{@link #getSource(String)} 据此让配置门禁在拦截时
 * 精确报出「这条路径是被哪个策略锁的」。来源全程由框架自动附加，锁定策略的编写者无需感知。</p>
 *
 * <p><b>次序硬约束</b>：还原消失的锁时，必须<b>先</b>把约束从 {@link #activeConstraints}
 * 移除，<b>再</b>写回原值。次序颠倒的话，写回过程中触发的 {@code applyAll}（及写入门禁）
 * 会把强制值又盖回去，原值永远还不回来。</p>
 *
 * <p>默认锁不再由本类自持，而是作为 {@link ServerOptinPolicy} 的静态配置片段，
 * 经 {@link com.billy65536.infrastructure.security.SecurityPortal} 注入。</p>
 */
public final class ConfigLocker implements ISecurityExecutor {

    /**
     * 执行器 id：命名空间为所属模块，path 为「所属策略 / 执行器」。
     *
     * <p>直接用字面量而非引用 {@code ServerOptinPolicy.POLICY_NAME}，避免核心锁定层
     * 反向依赖具体策略实现。</p>
     */
    public static final Identifier EXECUTOR_ID =
            new Identifier("security", "config-locker");

    private static final Logger LOGGER = LoggerFactory.getLogger("infrastructure.security.ConfigLocker");

    /**
     * 当前生效的约束表：完整路径 → 约束。
     *
     * <p>value 为 {@link LockConstraint}，<b>永不为 {@code null}</b>；「仅锁定无强制值」
     * 表达为约束内部的 {@code forcedValue == null}。用 {@code LinkedHashMap} 保序，
     * 使快照与命令输出的顺序稳定。</p>
     *
     * <p>本类假定只在客户端主线程访问（策略变更与配置读写均在主线程），
     * 故集合为普通非同步实现。</p>
     */
    private static final Map<String, LockConstraint> activeConstraints = new LinkedHashMap<>();

    private ConfigLocker() {}

    /** 单例访问：既是调试 / 诊断入口，也是作为执行器登记到策略下的实例。 */
    public static ConfigLocker getInstance() {
        return Holder.INSTANCE;
    }

    private static final class Holder {
        private static final ConfigLocker INSTANCE = new ConfigLocker();
    }

    // ==================== 安全执行器实现 ====================

    @Override
    public Identifier getId() {
        return EXECUTOR_ID;
    }

    @Override
    public Text getName() {
        return Text.translatable("infrastructure.security.executor.config_locker.name");
    }

    @Override
    public Text getDescription() {
        return Text.translatable("infrastructure.security.executor.config_locker.description");
    }

    /**
     * 收到合并后的全量锁配置，做 diff 物化。
     *
     * <p>{@code null} 表示无配置（策略停用 / 未被任何激活策略覆盖）：等价于清空全部约束，
     * 还原玩家原值并释放锁定。</p>
     */
    @Override
    public void onPolicyChanged(SecurityPolicyConfig raw) {
        Map<String, AttributedValue<String>> target = (raw == null)
                ? Map.of()
                : ((ConfigLockerPolicyConfig) raw).getEntries();
        diffAndApply(target);
    }

    // ==================== diff 物化 ====================

    /**
     * 按目标锁表 diff 当前 {@link #activeConstraints}：新增即抓原值快照并建立约束、
     * 消失即还原并移除、已有项则更新强制值与来源（<b>原值快照始终沿用</b>）；
     * 末尾重放全部当前强制值，并在策略仍激活时补发锁定事件。
     *
     * @param target 目标锁表（路径 → 带来源的强制值）；空表等价于释放全部约束
     */
    private static void diffAndApply(Map<String, AttributedValue<String>> target) {
        // 1) 先更新 activeConstraints：新增抓原值快照，已有则只更新强制值与来源
        //    （保留原快照——铁律，绝不刷新，否则解锁后回不到玩家真实设置）
        for (Entry<String, AttributedValue<String>> e : target.entrySet()) {
            String path = e.getKey();
            AttributedValue<String> entry = e.getValue();
            String forced = entry.getValue();
            Origin origin = entry.getOrigin();
            LockConstraint existing = activeConstraints.get(path);
            if (existing == null) {
                activeConstraints.put(path, new LockConstraint(path, forced, readCurrent(path), origin));
            } else {
                activeConstraints.put(path, new LockConstraint(path, forced, existing.originalValue(), origin));
            }
        }

        // 2) 还原消失的约束：先移除（保证还原中任何 applyAll 不会重写强制值），再写回原值
        List<String> removed = new ArrayList<>();
        for (String path : activeConstraints.keySet()) {
            if (!target.containsKey(path)) {
                removed.add(path);
            }
        }
        Map<String, String> originals = new LinkedHashMap<>();
        for (String path : removed) {
            LockConstraint c = activeConstraints.get(path);
            if (c != null && c.originalValue() != null) {
                originals.put(path, c.originalValue());
            }
        }
        activeConstraints.keySet().removeAll(removed);
        restoreOriginals(originals);

        // 3) 重放全部当前强制值（幂等）
        applyAllRegistered();

        // 4) 策略仍激活时补发锁定事件（晚于实际动作）
        if (SecurityManager.isActive(ServerOptinPolicy.ID)) {
            ServerOptinPolicy.fireLocksApplied();
        }
    }

    // ==================== 查询 API（供 ConfigManager 防绕过 / 诊断） ====================

    /** 判断某完整配置路径是否被锁定（key 存在即锁定，与 value 是否为 null 无关）。 */
    public static boolean isLocked(String fullPath) {
        return activeConstraints.containsKey(fullPath);
    }

    /**
     * 获取某完整配置路径被强制的值；未锁定或「仅锁定无强制值」均返回 null。
     *
     * <p>写入侧（{@link ConfigAccessor#applyLockedValue}）据此自行取值，
     * 而不由调用方传入——强制值的唯一真相源是本约束表。</p>
     */
    public static String getForcedValue(String fullPath) {
        LockConstraint c = activeConstraints.get(fullPath);
        return c == null ? null : c.forcedValue();
    }

    /** 当前锁定表快照（完整路径 → 强制值）。独立副本，调用方可自由修改。 */
    public static Map<String, String> getLockStatusSnapshot() {
        Map<String, String> snap = new LinkedHashMap<>();
        for (LockConstraint c : activeConstraints.values()) {
            snap.put(c.fullPath(), c.forcedValue());
        }
        return snap;
    }

    /**
     * 查询某完整配置路径的锁定来源。
     *
     * <p>门禁拦截时据此把「被谁锁的」写进异常与审计流水，替代过去只能报
     * 「来源未知」的窘境。</p>
     *
     * @param fullPath 完整配置路径
     * @return 来源身份；路径未被锁定时返回 {@code null}
     * @apiNote 返回框架内部类型 {@link Origin}，下游模组不得引用。
     */
    public static Origin getSource(String fullPath) {
        LockConstraint c = activeConstraints.get(fullPath);
        return c == null ? null : c.origin();
    }

    /**
     * 当前锁定来源快照（完整路径 → 来源）。独立副本，调用方可自由修改。
     *
     * @apiNote 暴露框架内部类型 {@link Origin}，下游模组不得引用。
     */
    public static Map<String, Origin> getLockSourceSnapshot() {
        Map<String, Origin> snap = new LinkedHashMap<>();
        for (LockConstraint c : activeConstraints.values()) {
            snap.put(c.fullPath(), c.origin());
        }
        return snap;
    }

    // ==================== 强制值重放 ====================

    /**
     * 由配置重载（{@code validatePostLoad}）调用：对给定描述符集合立即强制重放锁定值，
     * 防 GUI / 磁盘绕过。
     *
     * <p>只遍历路径并筛出被锁项，具体写什么值由
     * {@link ConfigAccessor#applyLockedValue} 回查本类的约束表决定。</p>
     *
     * @param descriptors 模块暴露的配置描述符集合（可为空）
     */
    public static void applyAll(List<ConfigDescriptor> descriptors) {
        for (ConfigDescriptor d : descriptors) {
            if (d == null) continue;
            ConfigPath base = d.path();
            for (String dotPath : ConfigAccessor.listPaths(d)) {
                String full = ConfigPath.of(base.module(), base.id(), dotPath).toString();
                if (!activeConstraints.containsKey(full)) continue;
                try {
                    ConfigAccessor.applyLockedValue(d, dotPath);
                } catch (ConfigAccessException e) {
                    LOGGER.warn("Failed to apply value to locked config item '{}': {}", full, e.getMessage());
                }
            }
        }
    }

    /** 对<b>全部已注册模块</b>重放锁定强制值，使锁定立刻覆盖当前生效的配置（不落盘）。 */
    public static void applyAllRegistered() {
        applyAll(allRegisteredDescriptors());
    }

    /** 汇总全部已注册模块暴露的配置描述符。 */
    private static List<ConfigDescriptor> allRegisteredDescriptors() {
        List<ConfigDescriptor> all = new ArrayList<>();
        for (IModule m : ModuleRegistry.getAll()) {
            List<ConfigDescriptor> ds = m.getConfigDescriptors();
            if (ds != null) all.addAll(ds);
        }
        return all;
    }

    // ==================== 本地原值的记录与还原 ====================

    /** 读取某完整路径当前生效的值并转为字符串；路径无法解析时返回 null。 */
    private static String readCurrent(String fullPath) {
        ConfigDescriptor d = ConfigManager.findDescriptorByPath(fullPath);
        if (d == null) return null;
        return stringify(ConfigAccessor.getValue(d, ConfigManager.dotPathOf(fullPath)));
    }

    /**
     * 值转字符串，用于原值快照。
     *
     * <p>枚举取 {@code name()} 而非 {@code toString()}：写回时按 {@code name()} 匹配。</p>
     */
    private static String stringify(Object value) {
        if (value == null) return null;
        if (value instanceof Enum<?> e) return e.name();
        return String.valueOf(value);
    }

    /**
     * 把记录的本地原值写回活动配置，并持久化受影响的模块。
     *
     * <p>调用前相关约束<b>必须已从 {@link #activeConstraints} 移除</b>，否则
     * {@link ConfigAccessor#setValue} 的锁定门禁会拒绝写回。</p>
     *
     * @param originals 完整路径 → 原值字符串；value 为 null 表示当初未能读取，跳过
     * @return 实际还原成功的条目数
     */
    private static int restoreOriginals(Map<String, String> originals) {
        if (originals.isEmpty()) return 0;
        Set<IModule> dirty = new LinkedHashSet<>();
        int restored = 0;
        for (Entry<String, String> e : originals.entrySet()) {
            String full = e.getKey();
            if (e.getValue() == null) continue;
            ConfigDescriptor d = ConfigManager.findDescriptorByPath(full);
            if (d == null) continue;
            try {
                ConfigAccessor.setValue(
                        d, ConfigManager.dotPathOf(full), e.getValue());
                restored++;
                IModule m = ConfigManager.findModuleOfPath(full);
                if (m != null) dirty.add(m);
            } catch (ConfigAccessException ex) {
                LOGGER.warn("Failed to restore local config value for '{}': {}", full, ex.getMessage());
            } catch (ConfigLockedException ex) {
                LOGGER.warn("Failed to restore local config value for '{}': {}", full, ex.getMessage());
            }
        }
        // 锁定期间玩家若从 GUI 保存过配置，强制值已落到磁盘，必须重新写盘覆盖回原值
        for (IModule m : dirty) {
            try {
                m.saveConfig();
            } catch (Throwable t) {
                LOGGER.warn("Failed to persist restored config of module '{}': {}", m.getId(), t.toString());
            }
        }
        return restored;
    }
}
