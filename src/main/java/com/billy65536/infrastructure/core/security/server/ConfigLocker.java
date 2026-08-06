package com.billy65536.infrastructure.core.security.server;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.billy65536.infrastructure.core.config.ConfigDescriptor;
import com.billy65536.infrastructure.core.config.ConfigManager;
import com.billy65536.infrastructure.core.config.ConfigPath;
import com.billy65536.infrastructure.core.module.ModuleConfigReflectionAccessor;

/**
 * 纯客户端「服务端 opt-in」配置锁定状态机（billy-inf 核心安全层）。
 *
 * <p>原位于 chunkscanner 的 {@code security.server_optin.ConfigurationLocker}，现上移为
 * billy-inf 的<b>通用</b>配置锁定能力，供任意注册为模块的 mod 复用。逻辑基本继承原实现：
 * 锁定登记、强制值重放、以及针对命令 / GUI / 手动编辑磁盘文件三条修改通道的防绕过
 * （经配置对象的 {@code validatePostLoad()} → {@link #applyAll}）均已闭环。</p>
 *
 * <p><b>多模块隔离</b>：锁定表 key 使用<b>完整配置路径</b>
 * （{@code <module>:<id>/<dot.path>}，见 {@link ConfigPath}），不同模块的锁定项互不干扰。
 * 模块在登记时通过 {@link #registerDefaultLocks(String, Map)} 注入其默认受保护项
 * （进入多人服务器时默认锁定的强制值），随后 {@link #enterServerLock()} 锁定全部已注册项。</p>
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
 */
public final class ConfigLocker {

    private static final Logger LOGGER = LoggerFactory.getLogger("billy-inf.security.optin");

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
     */
    private static final Map<String, String> defaultLocks = new HashMap<>();

    private ConfigLocker() {}

    /** 单例访问（供外部调试模组 cs-dbg 的 Mixin / Action 调用，保持与原无参工具类一致）。 */
    public static ConfigLocker getInstance() {
        return Holder.INSTANCE;
    }

    private static final class Holder {
        private static final ConfigLocker INSTANCE = new ConfigLocker();
    }

    /**
     * 模块登记其默认受保护配置项（进入多人服务器时默认锁定的强制值）。
     *
     * <p>传入的 Map 中 key 为<b>纯字段点分路径</b>（如 {@code components.qshop.highlightEnabled}），
     * value 为强制写入的字符串值；本方法自动补全为完整路径
     * {@code <moduleId>:config/<dot.path>}（段名默认 {@code config}）。
     * 模块若有自定义段名，应使用 {@link #registerDefaultLocks(String, String, Map)}。</p>
     *
     * @param moduleId 模块 id（无命名空间纯名）
     * @param locks    纯字段点分路径 → 强制值（空串 / null 表示仅锁定）
     */
    public static void registerDefaultLocks(String moduleId, Map<String, String> locks) {
        registerDefaultLocks(moduleId, "config", locks);
    }

    /**
     * 模块登记默认受保护配置项，显式指定段名（自定义段 id）。
     *
     * @param moduleId 模块 id
     * @param segment  配置段名（如 {@code config}）
     * @param locks    纯字段点分路径 → 强制值
     */
    public static void registerDefaultLocks(String moduleId, String segment, Map<String, String> locks) {
        for (Entry<String, String> e : locks.entrySet()) {
            String full = ConfigPath.of(moduleId, segment, e.getKey()).toString();
            defaultLocks.put(full, e.getValue());
        }
        LOGGER.info("Registered {} default server-locks for module '{}'.", locks.size(), moduleId);
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
     * 进入多人服务器：按已登记默认锁锁定全部受保护配置（等待服务器授权信号）。
     * 无参版锁定所有模块的全部默认项。
     */
    public static void enterServerLock() {
        lockStatus.putAll(defaultLocks);
        LOGGER.info("Entered multiplayer server: locked {} config items, awaiting server authorization.",
                defaultLocks.size());
    }

    /**
     * 进入多人服务器：仅锁定指定模块的默认受保护项。
     *
     * @param moduleId 模块 id
     */
    public static void enterServerLock(String moduleId) {
        int n = 0;
        for (Entry<String, String> e : defaultLocks.entrySet()) {
            if (ConfigPath.parse(e.getKey()).module().equals(moduleId)) {
                lockStatus.put(e.getKey(), e.getValue());
                n++;
            }
        }
        LOGGER.info("Entered multiplayer server: locked {} config items for module '{}'.", n, moduleId);
    }

    /**
     * 退出服务器：清空全部锁定状态，恢复玩家自由配置。
     * 仅释放内存中的锁定登记，不修改磁盘配置文件。
     */
    public static void leaveServerLock() {
        lockStatus.clear();
        LOGGER.info("Left server: lock released.");
    }

    /**
     * 由服务端授权信号处理逻辑调用，解锁指定完整配置路径。
     *
     * @param fullPaths 要解锁的完整配置路径数组
     */
    public static void setAuthorized(String[] fullPaths) {
        for (String p : fullPaths) {
            lockStatus.remove(p);
        }
        LOGGER.info("Server authorized config editing: {}.", java.util.Arrays.asList(fullPaths));
    }

    /**
     * 由服务端信号处理逻辑调用，锁定并可选地强制指定完整配置路径。
     *
     * <p>传入的 Map 中：value 为非空串表示锁定且强制为该值；value 为空串或
     * {@code null} 表示仅锁定无强制值（仅禁止玩家修改）。锁定后若配置已注册，
     * 立即把活动配置重置为该强制值。</p>
     *
     * @param locks 完整配置路径 → 强制值（空串 / null 表示仅锁定）
     */
    public static void setLocked(Map<String, String> locks) {
        lockStatus.putAll(locks);
        LOGGER.info("Server locked {} config items.", locks.size());
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
}
