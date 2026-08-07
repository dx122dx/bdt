package com.billy65536.infrastructure.security.policy.server_optin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.billy65536.infrastructure.core.config.ConfigDescriptor;
import com.billy65536.infrastructure.core.config.ConfigManager;
import com.billy65536.infrastructure.core.config.ConfigPath;
import com.billy65536.infrastructure.core.module.IModule;
import com.billy65536.infrastructure.core.module.ModuleConfigReflectionAccessor;
import com.billy65536.infrastructure.core.module.ModuleRegistry;
import com.billy65536.infrastructure.security.core.policy.ISecurityExecutor;

import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 纯客户端「服务端 opt-in」配置锁定状态机（infrastructure 核心安全层）。
 *
 * <p>原位于 chunkscanner 的 {@code security.server_optin.ConfigurationLocker}，现上移为
 * infrastructure 的<b>通用</b>配置锁定能力，供任意注册为模块的 mod 复用。逻辑基本继承原实现：
 * 锁定登记、强制值重放、以及针对命令 / GUI / 手动编辑磁盘文件三条修改通道的防绕过
 * （经配置对象的 {@code validatePostLoad()} → {@link #applyAll}）均已闭环。</p>
 *
 * <p><b>多模块隔离</b>：锁定表 key 使用<b>完整配置路径</b>
 * （{@code <module>:<id>/<dot.path>}，见 {@link ConfigPath}），不同模块的锁定项互不干扰。
 * 模块在登记时直接调用 {@link #registerDefaultLocks(String, Map)} 注入其
 * 默认受保护项（进入多人服务器时默认锁定的强制值）。</p>
 *
 * <h2>本类即执行器</h2>
 *
 * <p>本类<b>自身实现</b> {@link ISecurityExecutor}，以 {@link #EXECUTOR_ID}
 * （{@code security:server-optin/config-locker}）登记在
 * {@code security:server-optin} 策略之下：策略激活时 {@link #onEnable()} 调用
 * {@link #enterServerLock()}，停用时 {@link #onDisable()} 调用
 * {@link #leaveServerLock()}。</p>
 *
 * <p>此处刻意<b>不</b>再单设一个转发用的执行器类。锁定表本身就是「被执行的安全约束」，
 * 拆成「引擎 + 一行转发的适配器」只会制造两个必须同步演进的类，以及一个可以绕过
 * 策略框架直接调用引擎的后门。</p>
 *
 * <p><b>生命周期归属</b>：{@link #enterServerLock()} / {@link #leaveServerLock()} 只应经
 * 策略框架触发，其他任何地方都不得直接调用，否则锁定状态会与策略激活态脱节。</p>
 *
 * <p>锁定状态统一存放在 {@link #lockStatus} Map 中，value 的语义为：</p>
 * <ul>
 *   <li>{@code key 不存在} = 未锁定（玩家可自由修改）；</li>
 *   <li>{@code key 存在} = 锁定，且强制为该 key 对应的 value。每次配置重载后由
 *       {@link ModuleConfigReflectionAccessor#applyLockedValue} 重放覆盖，
 *       防止玩家通过手动编辑磁盘配置文件绕过。</li>
 * </ul>
 *
 * <p>value 允许为 {@code null}（「仅锁定无强制值」语义，CHM 禁止 null value，故必须用
 * {@code synchronizedMap(HashMap)}）。</p>
 *
 * <h2>覆盖与还原</h2>
 *
 * <p>登记锁定<b>不等于</b>约束已生效：玩家本地已经设成别的值时，必须立刻把强制值写进
 * 活动配置，否则「已锁定」只是禁止再改，当前生效的仍是玩家的本地值。因此
 * {@link #enterServerLock()} / {@link #setLocked(Map)} 在更新锁定表后会立即
 * {@link #applyAllRegistered()} 覆盖全部已注册模块的活动配置。</p>
 *
 * <p>覆盖前的本地原值记录在 {@link #preLockValues} 中，并在
 * {@link #leaveServerLock()} / {@link #setAuthorized(String[])} 时写回——否则强制值会在
 * 断开连接后继续留在玩家配置里，等同于服务器永久篡改了客户端设置。</p>
 */
public final class ConfigLocker implements ISecurityExecutor {

    /**
     * 执行器 id：命名空间为所属模块，path 为「所属策略 / 执行器」。
     *
     * <p>直接用字面量而非引用 {@code ServerOptinPolicy.POLICY_NAME}，避免核心锁定层
     * 反向依赖具体策略实现。</p>
     */
    public static final Identifier EXECUTOR_ID =
            new Identifier("security", "server-optin/config-locker");

    private static final Logger LOGGER = LoggerFactory.getLogger("infrastructure.security.optin");

    /**
     * 配置锁定状态表：完整路径 → 强制值。
     * key 不存在=未锁定；key 存在=锁定且强制为该值，value 为 null 则仅禁止玩家修改。
     *
     * <p>由连接事件线程（{@link #enterServerLock} / {@link #leaveServerLock}）与配置线程
     * （{@link #applyAll}）并发访问，必须同步。不能用 {@code ConcurrentHashMap}
     * —— value 允许为 {@code null}（「仅锁定无强制值」语义），CHM 禁止 null value。</p>
     */
    private static final Map<String, String> lockStatus =
            Collections.synchronizedMap(new HashMap<>());

    /**
     * 各模块默认受保护配置项 → 强制值（进入服务器时默认锁定）。
     * key 为完整路径，与 {@link #lockStatus} 同构。由各模块登记时填充。
     *
     * <p>登记发生在模块发现 / 策略包注册线程，而读取发生在连接事件线程
     * （{@link #enterServerLock}），故必须同步。与 {@link #lockStatus} 同理，
     * value 允许为 {@code null}，不能用 {@code ConcurrentHashMap}。</p>
     */
    private static final Map<String, String> defaultLocks =
            Collections.synchronizedMap(new HashMap<>());

    /**
     * 被锁定项在<b>覆盖之前</b>的玩家本地值：完整路径 → 原值字符串。
     *
     * <p>仅记录本次锁定真正覆盖过的项，用于解锁时写回。value 为 {@code null} 表示原值
     * 读不出来（路径已不存在等），届时跳过还原而非写入 null。</p>
     *
     * <p>同一路径重复加锁时<b>不覆盖</b>已有快照，否则第二次加锁会把「上一次的强制值」
     * 误记成玩家原值，解锁后就再也回不到真正的本地设置。</p>
     */
    private static final Map<String, String> preLockValues =
            Collections.synchronizedMap(new HashMap<>());

    private ConfigLocker() {}

    /**
     * 单例访问。既供外部调试模组的 Mixin / Action 使用，也是本类作为
     * {@link ISecurityExecutor} 登记到策略之下时的实例。
     */
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
     * 所属策略激活：锁定全部已登记的默认受保护项，并立即覆盖活动配置。
     *
     * <p>幂等：锁定表用 {@code putAll} 覆盖同样的值，原值快照不重复记录，
     * 重放强制值本身也是幂等的。</p>
     */
    @Override
    public void onEnable() {
        enterServerLock();
    }

    /**
     * 所属策略停用：释放全部锁定并把玩家原值写回。
     *
     * <p>幂等：锁定表与原值快照均已清空时为空操作。</p>
     */
    @Override
    public void onDisable() {
        leaveServerLock();
    }

    /**
     * 模块登记其默认受保护配置项（进入多人服务器时默认锁定的强制值）。
     *
     * <p>传入的 Map 中 key 为<b>纯字段点分路径</b>（如 {@code components.qshop.highlightEnabled}），
     * value 为强制写入的字符串值；本方法自动补全为完整路径
     * {@code <moduleId>:config/<dot.path>}（段名默认 {@code config}）。
     * 模块若有自定义段名，应使用 {@link #registerDefaultLocks(String, String, Map)}。</p>
     *
     * <p><b>公共登记入口</b>：外部模组直接调用本方法登记默认锁，注册值进入
     * {@code defaultLocks} 表后可由 {@code /inf security status} 追溯。</p>
     *
     * @param moduleId 模块 id（无命名空间纯名）
     * @param locks    纯字段点分路径 → 强制值；{@code null} 表示仅锁定无强制值，
     *                 空串 {@code ""} 是会被真正写入的合法强制值
     */
    public static void registerDefaultLocks(String moduleId, Map<String, String> locks) {
        registerDefaultLocks(moduleId, "config", locks);
    }

    /**
     * 模块登记默认受保护配置项，显式指定段名（自定义段 id）。
     *
     * <p><b>公共登记入口</b>，理由同 {@link #registerDefaultLocks(String, Map)}。</p>
     *
     * @param moduleId 模块 id
     * @param segment  配置段名（如 {@code config}）
     * @param locks    纯字段点分路径 → 强制值，取值语义同
     *                 {@link #registerDefaultLocks(String, Map)}
     */
    public static void registerDefaultLocks(String moduleId, String segment, Map<String, String> locks) {
        for (Entry<String, String> e : locks.entrySet()) {
            String full = ConfigPath.of(moduleId, segment, e.getKey()).toString();
            defaultLocks.put(full, e.getValue());
        }
        LOGGER.info("Registered {} default server-locks for module '{}'.", locks.size(), moduleId);
    }

    /**
     * 返回当前默认锁登记表的快照（完整路径 → 强制值）。
     *
     * <p>反映各模块<b>登记</b>的保护范围，与实际锁定态无关（未进服务器时锁定表为空，
     * 本表依然非空）。供诊断命令与外部工具安全读取，避免持有内部 Map 引用。</p>
     *
     * @return 独立的副本，调用方可自由修改，不影响内部状态
     */
    public static Map<String, String> getDefaultLocksSnapshot() {
        synchronized (defaultLocks) {
            return new LinkedHashMap<>(defaultLocks);
        }
    }

    /** 判断某完整配置路径是否属于配置锁定保护范围。 */
    public static boolean isLocked(String fullPath) {
        return lockStatus.containsKey(fullPath);
    }

    /** 获取某完整配置路径被服务器强制的值；未锁定返回 null。 */
    public static String getValueLocked(String fullPath) {
        return lockStatus.get(fullPath);
    }

    /**
     * 返回当前锁定表的快照（完整路径 → 强制值）。
     * 供 {@code /inf security status} 等诊断命令安全读取，避免持有内部 Map 引用。
     *
     * @return 独立的副本，调用方可自由修改，不影响内部状态
     */
    public static Map<String, String> getLockStatusSnapshot() {
        synchronized (lockStatus) {
            return new LinkedHashMap<>(lockStatus);
        }
    }

    /** 当前是否处于任何服务器锁定之下（锁定表非空）。 */
    public static boolean isAnyLocked() {
        return !lockStatus.isEmpty();
    }

    /**
     * 进入多人服务器：按已登记默认锁锁定全部受保护配置（等待服务器授权信号），
     * 并<b>立即把强制值覆盖到活动配置</b>。
     *
     * <p>覆盖是必需的：玩家本地很可能已经把受保护项设成了别的值，若只登记锁定，
     * 当前生效的仍是玩家的本地值，约束要等到下一次配置重载才落地。</p>
     */
    public static void enterServerLock() {
        Map<String, String> locks = getDefaultLocksSnapshot();
        captureOriginals(locks.keySet());
        lockStatus.putAll(locks);
        LOGGER.info("Entered multiplayer server: locked {} config items, awaiting server authorization.",
                locks.size());
        applyAllRegistered();
    }

    /**
     * 进入多人服务器：仅锁定指定模块的默认受保护项，行为与
     * {@link #enterServerLock()} 一致（含立即覆盖活动配置）。
     *
     * <p>细粒度变体，供需要按模块分批锁定的场景使用；内置的
     * {@code security:server-optin} 策略走的是无参的 {@link #enterServerLock()}。</p>
     *
     * @param moduleId 模块 id（无命名空间纯名），不匹配任何登记项时为空操作
     */
    public static void enterServerLock(String moduleId) {
        Map<String, String> mine = new LinkedHashMap<>();
        for (Entry<String, String> e : getDefaultLocksSnapshot().entrySet()) {
            if (ConfigPath.parse(e.getKey()).module().equals(moduleId)) {
                mine.put(e.getKey(), e.getValue());
            }
        }
        if (mine.isEmpty()) {
            LOGGER.info("Entered multiplayer server: no default locks registered for module '{}'.", moduleId);
            return;
        }
        captureOriginals(mine.keySet());
        lockStatus.putAll(mine);
        LOGGER.info("Entered multiplayer server: locked {} config items for module '{}'.",
                mine.size(), moduleId);
        applyAllRegistered();
    }

    /**
     * 退出服务器：清空全部锁定状态，并把被强制值覆盖过的项<b>写回玩家原值</b>。
     *
     * <p>还原后会持久化受影响的模块。锁定期间本身不写盘，但玩家若在锁定期间从 GUI
     * 保存过配置，强制值就已经落到磁盘上了；不重新落盘的话，玩家的本地设置会在下次
     * 启动时永久丢失。</p>
     */
    public static void leaveServerLock() {
        Map<String, String> originals;
        synchronized (preLockValues) {
            originals = new LinkedHashMap<>(preLockValues);
            preLockValues.clear();
        }
        // 先清锁再还原：还原走反射写入，不受锁定表约束，但清锁在前可保证
        // 还原过程中任何被触发的 applyAll 都不会又把强制值写回去
        lockStatus.clear();
        int restored = restoreOriginals(originals);
        LOGGER.info("Left server: lock released, {} local config values restored.", restored);
    }

    /**
     * 由服务端授权信号处理逻辑调用，解锁指定完整配置路径，并把这些项写回玩家原值。
     *
     * @param fullPaths 要解锁的完整配置路径数组
     */
    public static void setAuthorized(String[] fullPaths) {
        Map<String, String> originals = new LinkedHashMap<>();
        for (String p : fullPaths) {
            lockStatus.remove(p);
            synchronized (preLockValues) {
                if (preLockValues.containsKey(p)) {
                    originals.put(p, preLockValues.remove(p));
                }
            }
        }
        LOGGER.info("Server authorized config editing: {}.", java.util.Arrays.asList(fullPaths));
        restoreOriginals(originals);
    }

    /**
     * 由服务端信号处理逻辑调用，锁定并可选地强制指定完整配置路径。
     *
     * <p>传入的 Map 中：value 为非空串表示锁定且强制为该值；value 为空串或
     * {@code null} 表示仅锁定无强制值（仅禁止玩家修改）。锁定后立即把活动配置
     * 覆盖为强制值，语义与 {@link #enterServerLock()} 一致。</p>
     *
     * @param locks 完整配置路径 → 强制值（空串 / null 表示仅锁定）
     */
    public static void setLocked(Map<String, String> locks) {
        captureOriginals(locks.keySet());
        lockStatus.putAll(locks);
        LOGGER.info("Server locked {} config items.", locks.size());
        applyAllRegistered();
    }

    /**
     * 由服务端信号处理逻辑调用，按模块 + 纯字段路径锁定。
     *
     * @param moduleId 模块 id
     * @param segment  配置段名（如 {@code config}）
     * @param locks    纯字段点分路径 → 强制值
     */
    public static void setLocked(String moduleId, String segment, Map<String, String> locks) {
        Map<String, String> full = new HashMap<>();
        for (Entry<String, String> e : locks.entrySet()) {
            full.put(ConfigPath.of(moduleId, segment, e.getKey()).toString(), e.getValue());
        }
        setLocked(full);
    }

    /**
     * 立即强制重置该模块描述符集合中全部锁定值（防 GUI / 磁盘绕过）。
     *
     * <p>遍历每个描述符的全部字段路径，若其完整路径在锁定表中，则重放强制值。
     * 在 {@code synchronized} 外取快照遍历，避免长期持锁。</p>
     *
     * @param descriptors 模块暴露的配置描述符集合（可为空）
     */
    public static void applyAll(List<ConfigDescriptor> descriptors) {
        Map<String, String> snapshot;
        synchronized (lockStatus) {
            snapshot = new HashMap<>(lockStatus);
        }
        for (ConfigDescriptor d : descriptors) {
            if (d == null) continue;
            ConfigPath base = d.path();
            for (String dotPath : ConfigManager.listPaths(d)) {
                String full = ConfigPath.of(base.module(), base.id(), dotPath).toString();
                if (!snapshot.containsKey(full)) continue;
                try {
                    applyLockedValue(d, dotPath);
                } catch (Exception e) {
                    LOGGER.warn("Failed to apply value to locked config item '{}': {}", full, e.getMessage());
                }
            }
        }
    }

    /**
     * 对单个描述符的字段路径重放锁定强制值（不检查锁定状态）。
     * 从 {@link #lockStatus} 取该完整路径的强制值，经
     * {@link ModuleConfigReflectionAccessor#applyLockedValue} 写入；
     * 锁定值为 null 表示仅锁定无强制值，不写入。
     *
     * @return 实际写入的值；仅锁定无强制值时返回 null
     */
    public static Object applyLockedValue(ConfigDescriptor descriptor, String dotPath) {
        ConfigPath base = descriptor.path();
        String full = ConfigPath.of(base.module(), base.id(), dotPath).toString();
        String forced = lockStatus.get(full);
        try {
            return ModuleConfigReflectionAccessor.applyLockedValue(descriptor, dotPath, forced);
        } catch (ModuleConfigReflectionAccessor.ConfigAccessException e) {
            LOGGER.warn("Failed to apply locked value to '{}': {}", full, e.getMessage());
            return null;
        }
    }

    /**
     * 便捷：按模块 id 取出其描述符并 applyAll。模块未找到时静默跳过。
     */
    public static void applyAll(String moduleId,
            java.util.function.Function<String, List<ConfigDescriptor>> descriptorSupplier) {
        List<ConfigDescriptor> ds = descriptorSupplier.apply(moduleId);
        if (ds != null) applyAll(ds);
    }

    /**
     * 对<b>全部已注册模块</b>重放锁定强制值，使锁定立刻覆盖当前生效的配置。
     *
     * <p>刻意不落盘：强制值属于本次连接的临时约束，写进磁盘等于让服务器永久改掉
     * 玩家的配置文件。原值的还原见 {@link #leaveServerLock()}。</p>
     *
     * <p>方法名刻意避开 {@code applyAll} 重载：调试模组以方法名匹配的方式向
     * {@code applyAll} 注入 Mixin，再加一个同名重载会引入选择歧义。</p>
     */
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

    /**
     * 在覆盖之前记下这些路径当前生效的本地值。
     *
     * <p>已有快照的路径<b>不覆盖</b>：重复加锁时若刷新快照，记下的将是上一次的强制值，
     * 解锁后就再也回不到玩家真正的本地设置。</p>
     */
    private static void captureOriginals(Collection<String> fullPaths) {
        for (String full : fullPaths) {
            synchronized (preLockValues) {
                if (preLockValues.containsKey(full)) continue;
                preLockValues.put(full, readCurrent(full));
            }
        }
    }

    /** 读取某完整路径当前生效的值并转为字符串；路径无法解析时返回 null。 */
    private static String readCurrent(String fullPath) {
        ConfigDescriptor d = ConfigManager.findDescriptorByPath(fullPath);
        if (d == null) return null;
        return stringify(ConfigManager.getValue(d, ConfigManager.dotPathOf(fullPath)));
    }

    /**
     * 值转字符串，用于原值快照。
     *
     * <p>枚举取 {@code name()} 而非 {@code toString()}：写回时按 {@code name()} 匹配，
     * 而 {@code toString()} 可能被重写，用 {@code String.valueOf} 会导致还原失败。</p>
     */
    private static String stringify(Object value) {
        if (value == null) return null;
        if (value instanceof Enum<?> e) return e.name();
        return String.valueOf(value);
    }

    /**
     * 把记录的本地原值写回活动配置，并持久化受影响的模块。
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
                ModuleConfigReflectionAccessor.applyLockedValue(
                        d, ConfigManager.dotPathOf(full), e.getValue());
                restored++;
                IModule m = ConfigManager.findModuleOfPath(full);
                if (m != null) dirty.add(m);
            } catch (ModuleConfigReflectionAccessor.ConfigAccessException ex) {
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
