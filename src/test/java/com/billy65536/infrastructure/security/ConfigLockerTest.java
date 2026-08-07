package com.billy65536.infrastructure.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.billy65536.infrastructure.core.config.ConfigDescriptor;
import com.billy65536.infrastructure.core.config.ConfigPath;
import com.billy65536.infrastructure.core.module.IModule;
import com.billy65536.infrastructure.core.module.ModuleRegistry;
import com.billy65536.infrastructure.security.builtin.ConfigLocker;
import com.billy65536.infrastructure.security.builtin.ConfigLockerPolicyConfig;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConfigLocker 单元测试（由原 chunkscanner ConfigurationLockerTest 迁移）。
 *
 * <p>锁定的核心语义是 <b>key 存在即锁定</b>，与 value 是否为 {@code null} 无关：</p>
 * <ul>
 *   <li>key 不存在 —— 未锁定，玩家可自由修改</li>
 *   <li>key 存在 + value 非 null —— 锁定且强制为该值（空串是合法强制值）</li>
 *   <li>key 存在 + value 为 null —— 仅禁止修改，不强制任何值</li>
 * </ul>
 *
 * <p>新设计锁定表 key 为<b>完整配置路径</b>（{@code <module>:<id>/<dot.path>}）。本测试以
 * 模块 {@code chunkscanner} + 段 {@code config} 为例，字段路径沿用原 QShop 高亮项。
 * 配置对象用本地简单 POJO {@link TestConfig}（无需引入 chunkscanner）。</p>
 *
 * <p><b>状态隔离</b>：{@code activeConstraints} 是静态 Map，用例间会互相污染，
 * 每个用例前后都通过 {@link #clearLocks()} 显式清空；并通过注册 {@link TestModule}
 * 使 {@code readCurrent / restoreOriginals / applyAllRegistered} 能解析到描述符，
 * 从而真实验证「原值快照」与「还原」行为。</p>
 */
@DisplayName("ConfigLocker")
class ConfigLockerTest {

    /** 测试用配置对象：嵌套结构与 chunkscanner 的 components.qshop.highlightEnabled 对齐。 */
    public static class Components {
        public QShop qshop = new QShop();
    }

    public static class QShop {
        public boolean highlightEnabled = false;
    }

    public static class TestConfig {
        public Components components = new Components();
    }

    /** 模块 / 段 常量（对应 chunkscanner 的 config 段）。 */
    private static final String MODULE = "chunkscanner";
    private static final String SEGMENT = "config";

    /** 纯字段点分路径。 */
    private static final String QSHOP_HIGHLIGHT = "components.qshop.highlightEnabled";
    /** 完整路径（chunkscanner:config/components.qshop.highlightEnabled）。 */
    private static final String QSHOP_HIGHLIGHT_FULL =
            ConfigPath.of(MODULE, SEGMENT, QSHOP_HIGHLIGHT).toString();

    /** 持有当前描述符的测试模块（注册到 ModuleRegistry 以便路径解析）。 */
    private static final TestModule MODULE_INSTANCE = new TestModule();

    /** 当前测试配置实例（每个用例新建，避免污染）。 */
    private TestConfig config;

    /** 持有配置实例的描述符（供 applyAll）。 */
    private ConfigDescriptor descriptor;

    @BeforeAll
    static void registerModule() {
        ModuleRegistry.register(MODULE_INSTANCE);
    }

    @BeforeEach
    void reset() {
        clearLocks();
        config = new TestConfig();
        descriptor = ConfigDescriptor.of(
                ConfigPath.of(MODULE, SEGMENT, ""),
                () -> config,
                new TestConfig());
        MODULE_INSTANCE.setDescriptor(descriptor);
        assertFalse(ConfigLocker.isLocked(QSHOP_HIGHLIGHT_FULL), "静态锁定表未清理干净");
    }

    @AfterEach
    void cleanUp() {
        clearLocks();
    }

    // ==================== 构造辅助 ====================

    /** 用纯字段路径构造完整路径的锁定映射。 */
    private static Map<String, String> locksFull(String dotPath, String value) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(ConfigPath.of(MODULE, SEGMENT, dotPath).toString(), value);
        return m;
    }

    /** 构造一个可变锁定映射（Map.of 不允许 null value）。 */
    private static Map<String, String> locks(String key, String value) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(key, value);
        return m;
    }

    /** 把「完整路径 → 强制值」映射转为 ConfigLockerPolicyConfig 并交给执行器物化。 */
    private static void applyLocks(Map<String, String> fullPathLocks) {
        ConfigLockerPolicyConfig.Builder b = ConfigLockerPolicyConfig.builder(ConfigLocker.EXECUTOR_ID);
        for (Map.Entry<String, String> e : fullPathLocks.entrySet()) {
            ConfigPath cp = ConfigPath.parse(e.getKey());
            b.lock(cp.module(), cp.id(), cp.dotPath(), e.getValue());
        }
        ConfigLocker.getInstance().onPolicyChanged(b.build());
    }

    /** 模拟 setAuthorized：移除给定完整路径后重新物化（还原其余）。 */
    private static void unlock(String... fullPaths) {
        Map<String, String> current = new LinkedHashMap<>(ConfigLocker.getLockStatusSnapshot());
        for (String p : fullPaths) {
            current.remove(p);
        }
        applyLocks(current);
    }

    /** 清空全部约束（模拟 leaveServerLock）。 */
    private static void clearLocks() {
        ConfigLocker.getInstance().onPolicyChanged(ConfigLockerPolicyConfig.empty());
    }

    // ==================== isLocked / getValueLocked 语义 ====================

    @Nested
    @DisplayName("锁定语义")
    class LockSemantics {

        @Test
        @DisplayName("未登记的路径未锁定")
        void unregisteredPath_shouldNotBeLocked() {
            String full = ConfigPath.of(MODULE, SEGMENT, "scanner.maxTasksPerTick").toString();
            assertFalse(ConfigLocker.isLocked(full));
            assertNull(ConfigLocker.getValueLocked(full));
        }

        @Test
        @DisplayName("key 存在 + 非 null value = 锁定且有强制值")
        void keyWithValue_shouldLockAndForce() {
            applyLocks(locksFull(QSHOP_HIGHLIGHT, "false"));

            assertTrue(ConfigLocker.isLocked(QSHOP_HIGHLIGHT_FULL));
            assertEquals("false", ConfigLocker.getValueLocked(QSHOP_HIGHLIGHT_FULL));
        }

        @Test
        @DisplayName("key 存在 + null value = 仅锁定无强制值")
        void keyWithNullValue_shouldLockWithoutForcing() {
            applyLocks(locksFull(QSHOP_HIGHLIGHT, null));

            assertTrue(ConfigLocker.isLocked(QSHOP_HIGHLIGHT_FULL),
                    "value 为 null 时仍应视为锁定，判定依据是 key 是否存在");
            assertNull(ConfigLocker.getValueLocked(QSHOP_HIGHLIGHT_FULL));
        }

        @Test
        @DisplayName("空串是合法强制值，不等同于 null")
        void emptyStringValue_shouldBeValidForcedValue() {
            String full = ConfigPath.of(MODULE, SEGMENT, "components.qshop.sellKeyword").toString();
            applyLocks(locks(full, ""));

            assertTrue(ConfigLocker.isLocked(full));
            assertEquals("", ConfigLocker.getValueLocked(full),
                    "空串必须与 null 区分，前者是强制为空值，后者是不强制");
        }

        @Test
        @DisplayName("未预定义的任意路径也可被锁定")
        void arbitraryPath_shouldBeLockable() {
            String full = ConfigPath.of(MODULE, SEGMENT, "some.future.path").toString();
            applyLocks(locks(full, "x"));
            assertTrue(ConfigLocker.isLocked(full));
        }

        @Test
        @DisplayName("重复锁定同一 key 覆盖强制值")
        void repeatedSetLocked_shouldOverrideValue() {
            applyLocks(locksFull(QSHOP_HIGHLIGHT, "false"));
            applyLocks(locksFull(QSHOP_HIGHLIGHT, "true"));

            assertEquals("true", ConfigLocker.getValueLocked(QSHOP_HIGHLIGHT_FULL));
        }

        @Test
        @DisplayName("重新物化相同锁是幂等的")
        void reapplySameLocks_shouldBeIdempotent() {
            applyLocks(locksFull(QSHOP_HIGHLIGHT, "false"));
            applyLocks(locksFull(QSHOP_HIGHLIGHT, "false"));

            assertTrue(ConfigLocker.isLocked(QSHOP_HIGHLIGHT_FULL));
            assertEquals("false", ConfigLocker.getValueLocked(QSHOP_HIGHLIGHT_FULL));
        }

        @Test
        @DisplayName("锁定多个路径同时生效")
        void setLocked_multiplePaths_shouldAllApply() {
            Map<String, String> m = new LinkedHashMap<>();
            m.put(QSHOP_HIGHLIGHT_FULL, "false");
            m.put(ConfigPath.of(MODULE, SEGMENT, "scanner.maxTasksPerTick").toString(), "8");
            applyLocks(m);

            assertTrue(ConfigLocker.isLocked(QSHOP_HIGHLIGHT_FULL));
            assertTrue(ConfigLocker.isLocked(
                    ConfigPath.of(MODULE, SEGMENT, "scanner.maxTasksPerTick").toString()));
        }
    }

    // ==================== 进入 / 退出服务器 ====================

    @Nested
    @DisplayName("enterServer / leaveServer（经物化）")
    class ServerLifecycle {

        @Test
        @DisplayName("进入服务器锁定 QShop 高亮（已登记默认锁）")
        void enterServerLock_shouldApplyDefaultLocks() {
            applyLocks(locksFull(QSHOP_HIGHLIGHT, "false"));

            assertTrue(ConfigLocker.isLocked(QSHOP_HIGHLIGHT_FULL),
                    "进入多人服务器必须默认锁定 QShop 高亮，等待服务端授权");
            assertEquals("false", ConfigLocker.getValueLocked(QSHOP_HIGHLIGHT_FULL),
                    "默认强制值应为 false");
        }

        @Test
        @DisplayName("退出服务器清空所有锁定")
        void leaveServerLock_shouldClearAll() {
            applyLocks(locksFull(QSHOP_HIGHLIGHT, "false"));
            applyLocks(locksFull("scanner.maxTasksPerTick", "4"));

            clearLocks();

            assertFalse(ConfigLocker.isLocked(QSHOP_HIGHLIGHT_FULL));
            assertFalse(ConfigLocker.isLocked(
                    ConfigPath.of(MODULE, SEGMENT, "scanner.maxTasksPerTick").toString()));
        }

        @Test
        @DisplayName("重复清空是幂等的")
        void leaveServerLock_shouldBeIdempotent() {
            applyLocks(locksFull(QSHOP_HIGHLIGHT, "false"));
            clearLocks();
            assertDoesNotThrow(ConfigLockerTest::clearLocks);
            assertFalse(ConfigLocker.isLocked(QSHOP_HIGHLIGHT_FULL));
        }

        @Test
        @DisplayName("重复进入是幂等的")
        void enterServerLock_shouldBeIdempotent() {
            applyLocks(locksFull(QSHOP_HIGHLIGHT, "false"));
            applyLocks(locksFull(QSHOP_HIGHLIGHT, "false"));

            assertTrue(ConfigLocker.isLocked(QSHOP_HIGHLIGHT_FULL));
            assertEquals("false", ConfigLocker.getValueLocked(QSHOP_HIGHLIGHT_FULL));
        }
    }

    // ==================== 授权解锁 ====================

    @Nested
    @DisplayName("unlock（模拟 setAuthorized）")
    class Authorization {

        @Test
        @DisplayName("解锁后对应路径解锁")
        void setAuthorized_shouldUnlockPath() {
            applyLocks(locksFull(QSHOP_HIGHLIGHT, "false"));
            assertTrue(ConfigLocker.isLocked(QSHOP_HIGHLIGHT_FULL));

            unlock(QSHOP_HIGHLIGHT_FULL);

            assertFalse(ConfigLocker.isLocked(QSHOP_HIGHLIGHT_FULL));
            assertNull(ConfigLocker.getValueLocked(QSHOP_HIGHLIGHT_FULL));
        }

        @Test
        @DisplayName("解锁只影响列出的路径")
        void setAuthorized_shouldOnlyAffectListedPaths() {
            Map<String, String> m = new LinkedHashMap<>();
            m.put(QSHOP_HIGHLIGHT_FULL, "false");
            m.put(ConfigPath.of(MODULE, SEGMENT, "scanner.maxTasksPerTick").toString(), "8");
            applyLocks(m);

            unlock(QSHOP_HIGHLIGHT_FULL);

            assertFalse(ConfigLocker.isLocked(QSHOP_HIGHLIGHT_FULL));
            assertTrue(ConfigLocker.isLocked(
                    ConfigPath.of(MODULE, SEGMENT, "scanner.maxTasksPerTick").toString()),
                    "未被授权的路径应保持锁定");
        }

        @Test
        @DisplayName("解锁未锁定的路径不抛异常")
        void setAuthorized_unlockedPath_shouldNotThrow() {
            assertDoesNotThrow(() ->
                    unlock(ConfigPath.of(MODULE, SEGMENT, "never.locked.path").toString()));
        }

        @Test
        @DisplayName("解锁空数组不改变状态")
        void setAuthorized_emptyArray_shouldBeNoOp() {
            applyLocks(locksFull(QSHOP_HIGHLIGHT, "false"));
            unlock();
            assertTrue(ConfigLocker.isLocked(QSHOP_HIGHLIGHT_FULL));
        }

        @Test
        @DisplayName("可解锁任意路径，包括未预定义在默认锁中的")
        void setAuthorized_arbitraryPath_shouldWork() {
            applyLocks(locks(
                    ConfigPath.of(MODULE, SEGMENT, "custom.future.option").toString(), "v"));
            unlock(ConfigPath.of(MODULE, SEGMENT, "custom.future.option").toString());
            assertFalse(ConfigLocker.isLocked(
                    ConfigPath.of(MODULE, SEGMENT, "custom.future.option").toString()));
        }

        @Test
        @DisplayName("解锁后再次进入服务器会重新锁定默认项")
        void reenterAfterAuthorized_shouldRelock() {
            applyLocks(locksFull(QSHOP_HIGHLIGHT, "false"));
            unlock(QSHOP_HIGHLIGHT_FULL);
            assertFalse(ConfigLocker.isLocked(QSHOP_HIGHLIGHT_FULL));

            clearLocks();
            applyLocks(locksFull(QSHOP_HIGHLIGHT, "false"));

            assertTrue(ConfigLocker.isLocked(QSHOP_HIGHLIGHT_FULL),
                    "换服务器后授权必须失效，重新回到等待授权状态");
        }

        @Test
        @DisplayName("重复加锁不刷新原值快照（解锁回到玩家真实设置）")
        void repeatedLock_shouldNotRefreshOriginalSnapshot() {
            config.components.qshop.highlightEnabled = true; // 玩家本地值
            applyLocks(locksFull(QSHOP_HIGHLIGHT, "false")); // 捕获原值快照 = true
            config.components.qshop.highlightEnabled = true; // 玩家在锁定期间又改回 true
            applyLocks(locksFull(QSHOP_HIGHLIGHT, "false")); // 再次锁定：原值快照不得刷新

            unlock(QSHOP_HIGHLIGHT_FULL); // 还原必须回到玩家原值 true，而非被强制值 false 覆盖

            assertTrue(config.components.qshop.highlightEnabled,
                    "解锁必须回到玩家原值 true，而非被强制值 false 覆盖");
        }
    }

    // ==================== applyAll 强制值重放 ====================

    @Nested
    @DisplayName("applyAll")
    class ApplyAll {

        @Test
        @DisplayName("把锁定的强制值重放到配置对象（防手改磁盘文件绕过）")
        void applyAll_shouldOverwriteConfigValue() {
            config.components.qshop.highlightEnabled = true; // 模拟玩家手改配置文件

            applyLocks(locksFull(QSHOP_HIGHLIGHT, "false"));
            ConfigLocker.applyAll(List.of(descriptor));

            assertFalse(config.components.qshop.highlightEnabled,
                    "锁定的强制值必须在每次配置重载后被重放，否则玩家可手改磁盘文件绕过");
        }

        @Test
        @DisplayName("仅锁定无强制值时不修改配置")
        void applyAll_nullValue_shouldNotModifyConfig() {
            config.components.qshop.highlightEnabled = true;

            applyLocks(locksFull(QSHOP_HIGHLIGHT, null));
            ConfigLocker.applyAll(List.of(descriptor));

            assertTrue(config.components.qshop.highlightEnabled,
                    "value 为 null 表示仅锁定无强制值，不应强制覆盖当前值");
        }

        @Test
        @DisplayName("无锁定时 applyAll 不改变配置")
        void applyAll_noLocks_shouldBeNoOp() {
            config.components.qshop.highlightEnabled = true;

            ConfigLocker.applyAll(List.of(descriptor));

            assertTrue(config.components.qshop.highlightEnabled);
        }

        @Test
        @DisplayName("未知路径被静默跳过，不影响其他锁定项")
        void applyAll_unknownPath_shouldNotBreakOthers() {
            config.components.qshop.highlightEnabled = true;

            Map<String, String> m = new LinkedHashMap<>();
            m.put(ConfigPath.of(MODULE, SEGMENT, "totally.bogus.path").toString(), "1");
            m.put(QSHOP_HIGHLIGHT_FULL, "false");
            applyLocks(m);

            assertDoesNotThrow(() -> ConfigLocker.applyAll(List.of(descriptor)));
            assertFalse(config.components.qshop.highlightEnabled,
                    "单个非法路径不应阻断其余锁定项的重放");
        }

        @Test
        @DisplayName("applyAll 空描述符列表不抛异常")
        void applyAll_emptyDescriptors_shouldNotThrow() {
            applyLocks(locksFull(QSHOP_HIGHLIGHT, "false"));
            assertDoesNotThrow(() -> ConfigLocker.applyAll(List.of()));
        }

        @Test
        @DisplayName("多次 applyAll 结果一致（幂等）")
        void applyAll_shouldBeIdempotent() {
            applyLocks(locksFull(QSHOP_HIGHLIGHT, "false"));

            ConfigLocker.applyAll(List.of(descriptor));
            config.components.qshop.highlightEnabled = true;
            ConfigLocker.applyAll(List.of(descriptor));

            assertFalse(config.components.qshop.highlightEnabled);
        }
    }

    /** 持有当前描述符的测试模块，注册到 ModuleRegistry 以便路径解析与还原。 */
    static final class TestModule implements IModule {
        private volatile ConfigDescriptor descriptor;

        void setDescriptor(ConfigDescriptor d) {
            this.descriptor = d;
        }

        @Override
        public String getId() {
            return MODULE;
        }

        @Override
        public String getVersion() {
            return "test";
        }

        @Override
        public Text getName() {
            return Text.literal("test");
        }

        @Override
        public Text getDescription() {
            return Text.literal("test");
        }

        @Override
        public void onInitializeModule() {}

        @Override
        public List<ConfigDescriptor> getConfigDescriptors() {
            return descriptor == null ? List.of() : List.of(descriptor);
        }

        @Override
        public List<String> getCommandLiterals() {
            return List.of();
        }

        @Override
        public LiteralArgumentBuilder<FabricClientCommandSource> buildCommands() {
            return null;
        }
    }
}
