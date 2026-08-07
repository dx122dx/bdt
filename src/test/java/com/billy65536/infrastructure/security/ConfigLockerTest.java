package com.billy65536.infrastructure.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.billy65536.infrastructure.core.config.ConfigDescriptor;
import com.billy65536.infrastructure.core.config.ConfigPath;
import com.billy65536.infrastructure.security.policy.server_optin.ConfigLocker;

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
 * <p><b>静态状态隔离</b>：{@code lockStatus} 是静态 Map，用例间会互相污染，
 * 每个用例前后都通过 {@link ConfigLocker#leaveServerLock()} 显式清空。</p>
 */
@DisplayName("ConfigLocker")
class ConfigLockerTest {

    /** 测试用配置对象：嵌套结构与 chunkscanner 的 components.qshop.highlightEnabled 对齐，
     *  使反射索引返回的点分路径与登记锁定时使用的逻辑路径一致（否则 applyAll 路径不匹配）。 */
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

    /** 构造一个可变锁定映射（Map.of 不允许 null value）。 */
    private static Map<String, String> locks(String key, String value) {
        Map<String, String> m = new HashMap<>();
        m.put(key, value);
        return m;
    }

    /** 用纯字段路径构造完整路径的锁定映射。 */
    private static Map<String, String> locksFull(String dotPath, String value) {
        return locks(ConfigPath.of(MODULE, SEGMENT, dotPath).toString(), value);
    }

    /** 当前测试配置实例（每个用例新建，避免污染）。 */
    private TestConfig config;

    /** 持有配置实例的描述符（供 applyAll）。 */
    private ConfigDescriptor descriptor;

    @BeforeEach
    void reset() {
        ConfigLocker.leaveServerLock();
        config = new TestConfig();
        descriptor = ConfigDescriptor.of(
                ConfigPath.of(MODULE, SEGMENT, ""),
                () -> config,
                new TestConfig());
        assertFalse(ConfigLocker.isLocked(QSHOP_HIGHLIGHT_FULL), "静态锁定表未清理干净");
    }

    @AfterEach
    void cleanUp() {
        ConfigLocker.leaveServerLock();
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
            ConfigLocker.setLocked(locksFull(QSHOP_HIGHLIGHT, "false"));

            assertTrue(ConfigLocker.isLocked(QSHOP_HIGHLIGHT_FULL));
            assertEquals("false", ConfigLocker.getValueLocked(QSHOP_HIGHLIGHT_FULL));
        }

        @Test
        @DisplayName("key 存在 + null value = 仅锁定无强制值")
        void keyWithNullValue_shouldLockWithoutForcing() {
            ConfigLocker.setLocked(locksFull(QSHOP_HIGHLIGHT, null));

            assertTrue(ConfigLocker.isLocked(QSHOP_HIGHLIGHT_FULL),
                    "value 为 null 时仍应视为锁定，判定依据是 key 是否存在");
            assertNull(ConfigLocker.getValueLocked(QSHOP_HIGHLIGHT_FULL));
        }

        @Test
        @DisplayName("空串是合法强制值，不等同于 null")
        void emptyStringValue_shouldBeValidForcedValue() {
            String full = ConfigPath.of(MODULE, SEGMENT, "components.qshop.sellKeyword").toString();
            ConfigLocker.setLocked(locks(full, ""));

            assertTrue(ConfigLocker.isLocked(full));
            assertEquals("", ConfigLocker.getValueLocked(full),
                    "空串必须与 null 区分，前者是强制为空值，后者是不强制");
        }

        @Test
        @DisplayName("未预定义的任意路径也可被锁定")
        void arbitraryPath_shouldBeLockable() {
            String full = ConfigPath.of(MODULE, SEGMENT, "some.future.path").toString();
            ConfigLocker.setLocked(locks(full, "x"));
            assertTrue(ConfigLocker.isLocked(full));
        }

        @Test
        @DisplayName("重复 setLocked 同一 key 覆盖强制值")
        void repeatedSetLocked_shouldOverrideValue() {
            ConfigLocker.setLocked(locksFull(QSHOP_HIGHLIGHT, "false"));
            ConfigLocker.setLocked(locksFull(QSHOP_HIGHLIGHT, "true"));

            assertEquals("true", ConfigLocker.getValueLocked(QSHOP_HIGHLIGHT_FULL));
        }

        @Test
        @DisplayName("setLocked 多个路径同时生效")
        void setLocked_multiplePaths_shouldAllApply() {
            Map<String, String> m = new HashMap<>();
            m.put(QSHOP_HIGHLIGHT_FULL, "false");
            m.put(ConfigPath.of(MODULE, SEGMENT, "scanner.maxTasksPerTick").toString(), "8");
            ConfigLocker.setLocked(m);

            assertTrue(ConfigLocker.isLocked(QSHOP_HIGHLIGHT_FULL));
            assertTrue(ConfigLocker.isLocked(
                    ConfigPath.of(MODULE, SEGMENT, "scanner.maxTasksPerTick").toString()));
        }

        @Test
        @DisplayName("setLocked 空 Map 不改变现有状态")
        void setLocked_emptyMap_shouldBeNoOp() {
            ConfigLocker.setLocked(locksFull(QSHOP_HIGHLIGHT, "false"));
            ConfigLocker.setLocked(new HashMap<>());

            assertTrue(ConfigLocker.isLocked(QSHOP_HIGHLIGHT_FULL));
            assertEquals("false", ConfigLocker.getValueLocked(QSHOP_HIGHLIGHT_FULL));
        }
    }

    // ==================== 进入 / 退出服务器 ====================

    @Nested
    @DisplayName("enterServerLock / leaveServerLock")
    class ServerLifecycle {

        @Test
        @DisplayName("进入服务器锁定 QShop 高亮（已注册默认锁）")
        void enterServerLock_shouldApplyDefaultLocks() {
            // 注册默认锁（模拟 chunkscanner 模块初始化时登记）
            ConfigLocker.registerDefaultLocks(MODULE, SEGMENT,
                    Map.of(QSHOP_HIGHLIGHT, "false"));
            ConfigLocker.enterServerLock();

            assertTrue(ConfigLocker.isLocked(QSHOP_HIGHLIGHT_FULL),
                    "进入多人服务器必须默认锁定 QShop 高亮，等待服务端授权");
            assertEquals("false", ConfigLocker.getValueLocked(QSHOP_HIGHLIGHT_FULL),
                    "默认强制值应为 false");
        }

        @Test
        @DisplayName("退出服务器清空所有锁定")
        void leaveServerLock_shouldClearAll() {
            ConfigLocker.registerDefaultLocks(MODULE, SEGMENT,
                    Map.of(QSHOP_HIGHLIGHT, "false"));
            ConfigLocker.enterServerLock();
            ConfigLocker.setLocked(locksFull("scanner.maxTasksPerTick", "4"));

            ConfigLocker.leaveServerLock();

            assertFalse(ConfigLocker.isLocked(QSHOP_HIGHLIGHT_FULL));
            assertFalse(ConfigLocker.isLocked(
                    ConfigPath.of(MODULE, SEGMENT, "scanner.maxTasksPerTick").toString()));
        }

        @Test
        @DisplayName("重复 leaveServerLock 是幂等的")
        void leaveServerLock_shouldBeIdempotent() {
            ConfigLocker.registerDefaultLocks(MODULE, SEGMENT,
                    Map.of(QSHOP_HIGHLIGHT, "false"));
            ConfigLocker.enterServerLock();
            ConfigLocker.leaveServerLock();
            assertDoesNotThrow(ConfigLocker::leaveServerLock);
            assertFalse(ConfigLocker.isLocked(QSHOP_HIGHLIGHT_FULL));
        }

        @Test
        @DisplayName("重复 enterServerLock 是幂等的")
        void enterServerLock_shouldBeIdempotent() {
            ConfigLocker.registerDefaultLocks(MODULE, SEGMENT,
                    Map.of(QSHOP_HIGHLIGHT, "false"));
            ConfigLocker.enterServerLock();
            ConfigLocker.enterServerLock();

            assertTrue(ConfigLocker.isLocked(QSHOP_HIGHLIGHT_FULL));
            assertEquals("false", ConfigLocker.getValueLocked(QSHOP_HIGHLIGHT_FULL));
        }
    }

    // ==================== 授权解锁 ====================

    @Nested
    @DisplayName("setAuthorized")
    class Authorization {

        @Test
        @DisplayName("授权后对应路径解锁")
        void setAuthorized_shouldUnlockPath() {
            ConfigLocker.registerDefaultLocks(MODULE, SEGMENT,
                    Map.of(QSHOP_HIGHLIGHT, "false"));
            ConfigLocker.enterServerLock();
            assertTrue(ConfigLocker.isLocked(QSHOP_HIGHLIGHT_FULL));

            ConfigLocker.setAuthorized(new String[]{QSHOP_HIGHLIGHT_FULL});

            assertFalse(ConfigLocker.isLocked(QSHOP_HIGHLIGHT_FULL));
            assertNull(ConfigLocker.getValueLocked(QSHOP_HIGHLIGHT_FULL));
        }

        @Test
        @DisplayName("授权只影响列出的路径")
        void setAuthorized_shouldOnlyAffectListedPaths() {
            Map<String, String> m = new HashMap<>();
            m.put(QSHOP_HIGHLIGHT_FULL, "false");
            m.put(ConfigPath.of(MODULE, SEGMENT, "scanner.maxTasksPerTick").toString(), "8");
            ConfigLocker.setLocked(m);

            ConfigLocker.setAuthorized(new String[]{QSHOP_HIGHLIGHT_FULL});

            assertFalse(ConfigLocker.isLocked(QSHOP_HIGHLIGHT_FULL));
            assertTrue(ConfigLocker.isLocked(
                    ConfigPath.of(MODULE, SEGMENT, "scanner.maxTasksPerTick").toString()),
                    "未被授权的路径应保持锁定");
        }

        @Test
        @DisplayName("授权未锁定的路径不抛异常")
        void setAuthorized_unlockedPath_shouldNotThrow() {
            assertDoesNotThrow(() ->
                    ConfigLocker.setAuthorized(new String[]{
                            ConfigPath.of(MODULE, SEGMENT, "never.locked.path").toString()}));
        }

        @Test
        @DisplayName("授权空数组不改变状态")
        void setAuthorized_emptyArray_shouldBeNoOp() {
            ConfigLocker.registerDefaultLocks(MODULE, SEGMENT,
                    Map.of(QSHOP_HIGHLIGHT, "false"));
            ConfigLocker.enterServerLock();
            ConfigLocker.setAuthorized(new String[0]);

            assertTrue(ConfigLocker.isLocked(QSHOP_HIGHLIGHT_FULL));
        }

        @Test
        @DisplayName("可授权任意路径，包括未预定义在默认锁中的")
        void setAuthorized_arbitraryPath_shouldWork() {
            ConfigLocker.setLocked(locks(
                    ConfigPath.of(MODULE, SEGMENT, "custom.future.option").toString(), "v"));
            ConfigLocker.setAuthorized(new String[]{
                    ConfigPath.of(MODULE, SEGMENT, "custom.future.option").toString()});

            assertFalse(ConfigLocker.isLocked(
                    ConfigPath.of(MODULE, SEGMENT, "custom.future.option").toString()));
        }

        @Test
        @DisplayName("授权后再次进入服务器会重新锁定默认项")
        void reenterAfterAuthorized_shouldRelock() {
            ConfigLocker.registerDefaultLocks(MODULE, SEGMENT,
                    Map.of(QSHOP_HIGHLIGHT, "false"));
            ConfigLocker.enterServerLock();
            ConfigLocker.setAuthorized(new String[]{QSHOP_HIGHLIGHT_FULL});
            assertFalse(ConfigLocker.isLocked(QSHOP_HIGHLIGHT_FULL));

            ConfigLocker.leaveServerLock();
            ConfigLocker.enterServerLock();

            assertTrue(ConfigLocker.isLocked(QSHOP_HIGHLIGHT_FULL),
                    "换服务器后授权必须失效，重新回到等待授权状态");
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

            ConfigLocker.setLocked(locksFull(QSHOP_HIGHLIGHT, "false"));
            ConfigLocker.applyAll(List.of(descriptor));

            assertFalse(config.components.qshop.highlightEnabled,
                    "锁定的强制值必须在每次配置重载后被重放，否则玩家可手改磁盘文件绕过");
        }

        @Test
        @DisplayName("仅锁定无强制值时不修改配置")
        void applyAll_nullValue_shouldNotModifyConfig() {
            config.components.qshop.highlightEnabled = true;

            ConfigLocker.setLocked(locksFull(QSHOP_HIGHLIGHT, null));
            ConfigLocker.applyAll(List.of(descriptor));

            assertTrue(config.components.qshop.highlightEnabled,
                    "value 为 null 表示仅禁止修改，不应强制覆盖当前值");
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

            Map<String, String> m = new HashMap<>();
            m.put(ConfigPath.of(MODULE, SEGMENT, "totally.bogus.path").toString(), "1");
            m.put(QSHOP_HIGHLIGHT_FULL, "false");
            ConfigLocker.setLocked(m);

            assertDoesNotThrow(() -> ConfigLocker.applyAll(List.of(descriptor)));
            assertFalse(config.components.qshop.highlightEnabled,
                    "单个非法路径不应阻断其余锁定项的重放");
        }

        @Test
        @DisplayName("applyAll 空描述符列表不抛异常")
        void applyAll_emptyDescriptors_shouldNotThrow() {
            ConfigLocker.setLocked(locksFull(QSHOP_HIGHLIGHT, "false"));
            assertDoesNotThrow(() -> ConfigLocker.applyAll(List.of()));
        }

        @Test
        @DisplayName("多次 applyAll 结果一致（幂等）")
        void applyAll_shouldBeIdempotent() {
            ConfigLocker.setLocked(locksFull(QSHOP_HIGHLIGHT, "false"));

            ConfigLocker.applyAll(List.of(descriptor));
            config.components.qshop.highlightEnabled = true;
            ConfigLocker.applyAll(List.of(descriptor));

            assertFalse(config.components.qshop.highlightEnabled);
        }
    }
}
