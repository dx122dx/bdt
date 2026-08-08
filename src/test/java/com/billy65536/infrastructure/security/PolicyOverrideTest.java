package com.billy65536.infrastructure.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.billy65536.infrastructure.security.builtin.ConfigLockPatch;
import com.billy65536.infrastructure.security.builtin.ConfigLockerPolicyConfig;
import com.billy65536.infrastructure.security.core.internal.Origin;
import com.billy65536.infrastructure.security.core.policy.ActivationTrigger;
import com.billy65536.infrastructure.security.core.policy.ISecurityExecutor;
import com.billy65536.infrastructure.security.core.policy.ISecurityPolicy;
import com.billy65536.infrastructure.security.core.policy.SecurityConfigPatch;
import com.billy65536.infrastructure.security.core.policy.SecurityManager;
import com.billy65536.infrastructure.security.core.policy.SecurityPolicyConfig;

import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 三层合并链路单测：验证 {@link SecurityManager#recompute(Collection)} 的
 * 「静态策略 → Policy Override 补丁 → 安全门控」三层语义。
 *
 * <p>本测试<b>不</b>使用内置的 {@code ConfigLocker}，而是自带一组极简的
 * {@link MapConfig} / {@link MapPatch} / {@link RecordingExecutor} 实现。理由：</p>
 * <ul>
 *   <li>合并链路本身是 Manager 的职责，与执行器如何物化配置无关，用假实现能把断言收敛到
 *       合并结果本身；</li>
 *   <li>{@code ConfigLocker} 持有静态约束表并会回写真实配置对象，混入会引入跨用例污染。</li>
 * </ul>
 *
 * <p><b>状态隔离</b>：{@link SecurityManager} 的注册表是静态的且不提供注销接口，故每个用例
 * 使用 {@link #nextId(String)} 生成<b>唯一</b>的策略 / 执行器 id，互不干扰；用例结束时统一
 * 停用本次注册的策略、清空补丁并复位门控。</p>
 */
@DisplayName("Policy Override 三层合并")
class PolicyOverrideTest {

    /** 全局自增，保证每个用例的策略 / 执行器 id 唯一。 */
    private static final AtomicInteger SEQ = new AtomicInteger();

    /** 本用例注册并激活过的策略，用于收尾停用。 */
    private final List<Identifier> activated = new ArrayList<>();

    private static Identifier nextId(String prefix) {
        return new Identifier("test", prefix + "-" + SEQ.incrementAndGet());
    }

    @AfterEach
    void tearDown() {
        for (Identifier id : activated) {
            SecurityPortal.activatePolicy(id, false);
        }
        activated.clear();
        SecurityPortal.setGate(null);
        SecurityPortal.clearOverrides();
    }

    // ==================== 构造辅助 ====================

    /** 注册一个 MANUAL 触发的策略并立即激活。 */
    private MapPolicy registerActive(Identifier policyId, SecurityPolicyConfig... configs) {
        MapPolicy policy = new MapPolicy(policyId, List.of(configs));
        SecurityPortal.registerPolicy(reg -> reg.register(policy));
        SecurityPortal.activatePolicy(policyId, true);
        activated.add(policyId);
        return policy;
    }

    /** 注册一个记录型执行器。 */
    private static RecordingExecutor registerExecutor(Identifier executorId) {
        RecordingExecutor ex = new RecordingExecutor(executorId, false);
        SecurityPortal.registerExecutor(reg -> reg.register(ex));
        return ex;
    }

    /** 读取某执行器最近收到的配置内容（null 配置返回 null）。 */
    private static Map<String, String> lastOf(RecordingExecutor ex) {
        MapConfig c = (MapConfig) ex.last;
        return c == null ? null : c.values();
    }

    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    // ==================== 第 1 层：静态策略合并 ====================

    @Nested
    @DisplayName("第 1 层 静态策略")
    class StaticLayer {

        @Test
        @DisplayName("单策略激活后执行器收到其配置")
        void singlePolicy_shouldPushConfig() {
            Identifier exId = nextId("executor");
            RecordingExecutor ex = registerExecutor(exId);

            registerActive(nextId("policy"), new MapConfig(exId, map("a", "1")));

            assertEquals(map("a", "1"), lastOf(ex));
            assertTrue(SecurityManager.isExecutorEnabled(exId), "收到非空配置即视为启用");
        }

        @Test
        @DisplayName("多策略同 key 由后注册者覆盖，异 key 取并集")
        void multiplePolicies_shouldCombine() {
            Identifier exId = nextId("executor");
            RecordingExecutor ex = registerExecutor(exId);

            registerActive(nextId("policy"), new MapConfig(exId, map("a", "1", "shared", "first")));
            registerActive(nextId("policy"), new MapConfig(exId, map("b", "2", "shared", "second")));

            assertEquals(map("a", "1", "shared", "second", "b", "2"), lastOf(ex));
        }

        @Test
        @DisplayName("只推送目标 executorId 的配置，不串台")
        void configs_shouldRouteByExecutorId() {
            Identifier exA = nextId("executor");
            Identifier exB = nextId("executor");
            RecordingExecutor a = registerExecutor(exA);
            RecordingExecutor b = registerExecutor(exB);

            registerActive(nextId("policy"),
                    new MapConfig(exA, map("only", "a")),
                    new MapConfig(exB, map("only", "b")));

            assertEquals(map("only", "a"), lastOf(a));
            assertEquals(map("only", "b"), lastOf(b));
        }

        @Test
        @DisplayName("策略停用后执行器收到 null 并被视为未启用")
        void deactivate_shouldPushNull() {
            Identifier exId = nextId("executor");
            Identifier policyId = nextId("policy");
            RecordingExecutor ex = registerExecutor(exId);
            registerActive(policyId, new MapConfig(exId, map("a", "1")));

            SecurityPortal.activatePolicy(policyId, false);

            assertNull(lastOf(ex), "无激活策略贡献时执行器应收到 null（释放全部约束）");
            assertFalse(SecurityManager.isExecutorEnabled(exId));
        }
    }

    // ==================== 第 2 层：Policy Override 补丁 ====================

    @Nested
    @DisplayName("第 2 层 Policy Override")
    class OverrideLayer {

        @Test
        @DisplayName("补丁的 add 与 remove 叠加在静态结果之上")
        void patch_shouldAddAndRemove() {
            Identifier exId = nextId("executor");
            Identifier policyId = nextId("policy");
            RecordingExecutor ex = registerExecutor(exId);
            registerActive(policyId, new MapConfig(exId, map("keep", "0", "drop", "1")));

            SecurityPortal.submitPolicyPatch(
                    new MapPatch(policyId, exId, map("added", "9"), Set.of("drop")));

            assertEquals(map("keep", "0", "added", "9"), lastOf(ex));
        }

        @Test
        @DisplayName("同一补丁内 remove 优先于 add")
        void singlePatch_removeShouldBeatAdd() {
            Identifier exId = nextId("executor");
            Identifier policyId = nextId("policy");
            RecordingExecutor ex = registerExecutor(exId);
            registerActive(policyId, new MapConfig(exId, map("k", "base")));

            SecurityPortal.submitPolicyPatch(
                    new MapPatch(policyId, exId, map("k", "patched"), Set.of("k")));

            assertEquals(Map.of(), lastOf(ex),
                    "同一补丁内先 add 后 remove，冲突时 remove 胜出");
        }

        @Test
        @DisplayName("跨补丁按登记顺序应用，后者覆盖前者")
        void multiplePatches_laterShouldWin() {
            Identifier exId = nextId("executor");
            Identifier policyId = nextId("policy");
            RecordingExecutor ex = registerExecutor(exId);
            registerActive(policyId, new MapConfig(exId, map("k", "base")));

            SecurityPortal.submitPolicyPatch(new MapPatch(policyId, exId, map("k", "first"), Set.of()));
            SecurityPortal.submitPolicyPatch(new MapPatch(policyId, exId, map("k", "second"), Set.of()));

            assertEquals(map("k", "second"), lastOf(ex));
        }

        @Test
        @DisplayName("先 remove 后 add 可把条目加回来（顺序敏感）")
        void removeThenAdd_shouldRestoreEntry() {
            Identifier exId = nextId("executor");
            Identifier policyId = nextId("policy");
            RecordingExecutor ex = registerExecutor(exId);
            registerActive(policyId, new MapConfig(exId, map("k", "base")));

            SecurityPortal.submitPolicyPatch(new MapPatch(policyId, exId, map(), Set.of("k")));
            assertEquals(Map.of(), lastOf(ex));

            SecurityPortal.submitPolicyPatch(new MapPatch(policyId, exId, map("k", "again"), Set.of()));
            assertEquals(map("k", "again"), lastOf(ex));
        }

        @Test
        @DisplayName("补丁只作用于目标 executorId")
        void patch_shouldRouteByExecutorId() {
            Identifier exA = nextId("executor");
            Identifier exB = nextId("executor");
            Identifier policyId = nextId("policy");
            RecordingExecutor a = registerExecutor(exA);
            RecordingExecutor b = registerExecutor(exB);
            registerActive(policyId,
                    new MapConfig(exA, map("k", "a")),
                    new MapConfig(exB, map("k", "b")));

            SecurityPortal.submitPolicyPatch(new MapPatch(policyId, exA, map("k", "patched"), Set.of()));

            assertEquals(map("k", "patched"), lastOf(a));
            assertEquals(map("k", "b"), lastOf(b), "其他执行器不应被波及");
        }

        @Test
        @DisplayName("clearOverrides 后回落静态结果")
        void clearOverrides_shouldFallBackToBase() {
            Identifier exId = nextId("executor");
            Identifier policyId = nextId("policy");
            RecordingExecutor ex = registerExecutor(exId);
            registerActive(policyId, new MapConfig(exId, map("k", "base")));

            SecurityPortal.submitPolicyPatch(new MapPatch(policyId, exId, map("k", "patched"), Set.of()));
            assertEquals(map("k", "patched"), lastOf(ex));

            SecurityPortal.clearOverrides();

            assertEquals(map("k", "base"), lastOf(ex));
            assertEquals(0, SecurityPortal.getContext().patchCount());
        }

        @Test
        @DisplayName("无激活策略时补丁不会凭空造出配置")
        void patchWithoutBase_shouldStayNull() {
            Identifier exId = nextId("executor");
            RecordingExecutor ex = registerExecutor(exId);

            SecurityPortal.submitPolicyPatch(
                    new MapPatch(nextId("policy"), exId, map("k", "v"), Set.of()));

            assertNull(lastOf(ex), "base 为空时补丁无处叠加，执行器仍应收到 null");
            assertFalse(SecurityManager.isExecutorEnabled(exId));
        }
    }

    // ==================== 第 3 层：安全门控（熔断） ====================

    @Nested
    @DisplayName("第 3 层 安全门控")
    class GateLayer {

        @Test
        @DisplayName("门控关闭时回落 base，忽略全部补丁")
        void gateClosed_shouldFallBackToBase() {
            Identifier exId = nextId("executor");
            Identifier policyId = nextId("policy");
            RecordingExecutor ex = registerExecutor(exId);
            registerActive(policyId, new MapConfig(exId, map("k", "base")));

            SecurityPortal.submitPolicyPatch(new MapPatch(policyId, exId, map("k", "patched"), Set.of()));
            assertEquals(map("k", "patched"), lastOf(ex));

            SecurityPortal.setGate(() -> false);
            SecurityPortal.recomputePolicies(Set.of(exId));

            assertEquals(map("k", "base"), lastOf(ex), "熔断后必须无视补丁");
            assertFalse(SecurityManager.isOverrideAllowed());
        }

        @Test
        @DisplayName("门控关闭期间提交的补丁在重新放行后生效")
        void gateReopened_shouldReapplyPatches() {
            Identifier exId = nextId("executor");
            Identifier policyId = nextId("policy");
            RecordingExecutor ex = registerExecutor(exId);
            registerActive(policyId, new MapConfig(exId, map("k", "base")));

            SecurityPortal.setGate(() -> false);
            SecurityPortal.submitPolicyPatch(new MapPatch(policyId, exId, map("k", "patched"), Set.of()));
            assertEquals(map("k", "base"), lastOf(ex));

            SecurityPortal.setGate(() -> true);
            SecurityPortal.recomputePolicies(Set.of(exId));

            assertEquals(map("k", "patched"), lastOf(ex), "补丁未被丢弃，只是暂时不参与合并");
        }

        @Test
        @DisplayName("门控为 null 时视为放行")
        void nullGate_shouldDefaultToAllow() {
            SecurityPortal.setGate(() -> false);
            SecurityPortal.setGate(null);
            assertTrue(SecurityManager.isOverrideAllowed());
        }
    }

    // ==================== 异常隔离 ====================

    @Nested
    @DisplayName("异常隔离")
    class FaultIsolation {

        @Test
        @DisplayName("单个执行器抛异常不影响其余执行器")
        void throwingExecutor_shouldNotBlockOthers() {
            Identifier badId = nextId("executor");
            Identifier goodId = nextId("executor");
            RecordingExecutor bad = new RecordingExecutor(badId, true);
            SecurityPortal.registerExecutor(reg -> reg.register(bad));
            RecordingExecutor good = registerExecutor(goodId);

            Identifier policyId = nextId("policy");
            assertDoesNotThrow(() -> registerActive(policyId,
                    new MapConfig(badId, map("k", "x")),
                    new MapConfig(goodId, map("k", "y"))));

            assertEquals(1, bad.calls, "异常执行器仍被调用过一次");
            assertEquals(map("k", "y"), lastOf(good), "后续执行器必须照常收到配置");
            assertTrue(SecurityManager.isActive(policyId), "执行器异常不得回滚策略激活态");
        }
    }

    // ==================== 来源自治 ====================

    /**
     * 来源回填的框架自治行为。
     *
     * <p>核心契约：策略侧<b>只写四参 lock、全程不出现 Origin / AttributedValue</b>，
     * 却能在执行层拿到正确来源。来源注入点唯一且位于框架内部，策略无从感知、无从篡改。</p>
     */
    @Nested
    @DisplayName("来源自治")
    class OriginBackfill {

        /** 注册一个只用公开四参 API 贡献锁定的策略并激活。 */
        private LockRecorder registerLockPolicy(Identifier policyId, Identifier exId,
                                                String dotPath, String value) {
            LockRecorder ex = new LockRecorder(exId);
            SecurityPortal.registerExecutor(reg -> reg.register(ex));
            registerActive(policyId, rebindExecutor(ConfigLockerPolicyConfig
                    .builder(exId)
                    .lock("mod", "config", dotPath, value)
                    .build(), exId));
            return ex;
        }

        /** ConfigLockerPolicyConfig 的 executorId 由 builder 指定，此处直接透传。 */
        private SecurityPolicyConfig rebindExecutor(ConfigLockerPolicyConfig cfg, Identifier exId) {
            assertEquals(exId, cfg.getExecutorId());
            return cfg;
        }

        @Test
        @DisplayName("策略只写四参 lock，框架自动回填来源")
        void policyWritesPlainLocks_frameworkBackfillsOrigin() {
            Identifier exId = nextId("executor");
            Identifier policyId = nextId("policy");
            LockRecorder ex = registerLockPolicy(policyId, exId, "a.b", "false");

            Origin origin = ex.originOf("mod:config/a.b");
            assertNotNull(origin, "策略未感知来源，但框架必须已完成回填");
            assertEquals(policyId, origin.getPrimary());
        }

        @Test
        @DisplayName("多策略锁同一路径：primary 取后注册者，contributors 含全部")
        void multiplePolicies_shouldMergeOrigins() {
            Identifier exId = nextId("executor");
            LockRecorder ex = new LockRecorder(exId);
            SecurityPortal.registerExecutor(reg -> reg.register(ex));

            Identifier first = nextId("policy");
            Identifier second = nextId("policy");
            registerActive(first, ConfigLockerPolicyConfig.builder(exId)
                    .lock("mod", "config", "a.b", "false").build());
            registerActive(second, ConfigLockerPolicyConfig.builder(exId)
                    .lock("mod", "config", "a.b", "true").build());

            Origin origin = ex.originOf("mod:config/a.b");
            assertEquals(second, origin.getPrimary(), "来源须与值覆盖语义一致，取后者");
            assertEquals(Set.of(first, second), origin.getContributors());
            assertEquals("true", ex.valueOf("mod:config/a.b"));
        }

        @Test
        @DisplayName("补丁层来源取补丁自带的 policyId")
        void patchLayer_shouldUsePatchOwnPolicyId() {
            Identifier exId = nextId("executor");
            Identifier staticPolicy = nextId("policy");
            Identifier patchPolicy = nextId("policy");
            LockRecorder ex = registerLockPolicy(staticPolicy, exId, "a.b", "false");

            ConfigLockPatch.builder(patchPolicy, exId).add("mod:config/a.b", "true").apply();

            assertEquals(patchPolicy, ex.originOf("mod:config/a.b").getPrimary());
            assertEquals("true", ex.valueOf("mod:config/a.b"));
        }

        @Test
        @DisplayName("门控熔断回落 base 时来源同步回落为静态层来源")
        void gateFallback_shouldRestoreStaticOrigin() {
            Identifier exId = nextId("executor");
            Identifier staticPolicy = nextId("policy");
            Identifier patchPolicy = nextId("policy");
            LockRecorder ex = registerLockPolicy(staticPolicy, exId, "a.b", "false");

            ConfigLockPatch.builder(patchPolicy, exId).add("mod:config/a.b", "true").apply();
            assertEquals(patchPolicy, ex.originOf("mod:config/a.b").getPrimary());

            SecurityPortal.setGate(() -> false);
            SecurityPortal.recomputePolicies(Set.of(exId));

            assertEquals(staticPolicy, ex.originOf("mod:config/a.b").getPrimary(),
                    "熔断丢弃补丁后，来源不得残留补丁来源");
            assertEquals("false", ex.valueOf("mod:config/a.b"));
        }

        @Test
        @DisplayName("同一补丁内 remove 优先于 add 的既有语义不受来源改造影响")
        void patch_removeShouldStillWinOverAdd() {
            Identifier exId = nextId("executor");
            Identifier staticPolicy = nextId("policy");
            Identifier patchPolicy = nextId("policy");
            LockRecorder ex = registerLockPolicy(staticPolicy, exId, "a.b", "false");

            ConfigLockPatch.builder(patchPolicy, exId)
                    .add("mod:config/a.b", "true")
                    .remove("mod:config/a.b")
                    .apply();

            assertNull(ex.originOf("mod:config/a.b"), "被 remove 的条目应彻底消失");
        }
    }

    /** 记录 ConfigLocker 型配置的执行器，用于观测框架回填的来源。 */
    static final class LockRecorder implements ISecurityExecutor {
        private final Identifier id;
        private ConfigLockerPolicyConfig last;

        LockRecorder(Identifier id) {
            this.id = id;
        }

        @Override
        public Identifier getId() {
            return id;
        }

        @Override
        public Text getName() {
            return Text.literal(id.toString());
        }

        @Override
        public Text getDescription() {
            return Text.literal("recorder");
        }

        @Override
        public void onPolicyChanged(SecurityPolicyConfig config) {
            last = (ConfigLockerPolicyConfig) config;
        }

        Origin originOf(String fullPath) {
            if (last == null) return null;
            var entry = last.getEntries().get(fullPath);
            return entry == null ? null : entry.getOrigin();
        }

        String valueOf(String fullPath) {
            return last == null ? null : last.getLocks().get(fullPath);
        }
    }

    // ==================== 测试替身 ====================

    /** 极简 Map 型配置：合并即「后者覆盖前者」。 */
    record MapConfig(Identifier executorId, Map<String, String> values) implements SecurityPolicyConfig {

        @Override
        public Identifier getExecutorId() {
            return executorId;
        }

        @Override
        public SecurityPolicyConfig combine(SecurityPolicyConfig other) {
            if (!(other instanceof MapConfig o)) {
                return this;
            }
            Map<String, String> merged = new LinkedHashMap<>(values);
            merged.putAll(o.values);
            return new MapConfig(executorId, merged);
        }

        @Override
        public SecurityPolicyConfig applyPatch(SecurityConfigPatch patch) {
            if (!(patch instanceof MapPatch p)) {
                return this;
            }
            Map<String, String> result = new LinkedHashMap<>(values);
            result.putAll(p.adds());
            for (String r : p.removes()) {
                result.remove(r);
            }
            return new MapConfig(executorId, result);
        }
    }

    /** 极简补丁：先 add 后 remove，与 {@code ConfigLockPatch} 语义一致。 */
    record MapPatch(Identifier policyId, Identifier executorId,
            Map<String, String> adds, Set<String> removes) implements SecurityConfigPatch {

        @Override
        public Identifier getPolicyId() {
            return policyId;
        }

        @Override
        public Identifier getExecutorId() {
            return executorId;
        }
    }

    /** MANUAL 触发的测试策略，配置片段固定。 */
    record MapPolicy(Identifier id, List<SecurityPolicyConfig> configs) implements ISecurityPolicy {

        @Override
        public Identifier getId() {
            return id;
        }

        @Override
        public Text getName() {
            return Text.literal(id.toString());
        }

        @Override
        public Text getDescription() {
            return Text.literal("test policy");
        }

        @Override
        public ActivationTrigger getTrigger() {
            return ActivationTrigger.MANUAL;
        }

        @Override
        public boolean isManuallyToggleable() {
            return true;
        }

        @Override
        public Collection<SecurityPolicyConfig> getConfigs() {
            return configs;
        }
    }

    /** 记录最近一次收到的配置；{@code faulty} 为真时每次都抛异常。 */
    static final class RecordingExecutor implements ISecurityExecutor {
        private final Identifier id;
        private final boolean faulty;
        private SecurityPolicyConfig last;
        private int calls;

        RecordingExecutor(Identifier id, boolean faulty) {
            this.id = id;
            this.faulty = faulty;
        }

        @Override
        public Identifier getId() {
            return id;
        }

        @Override
        public Text getName() {
            return Text.literal(id.toString());
        }

        @Override
        public Text getDescription() {
            return Text.literal("test executor");
        }

        @Override
        public void onPolicyChanged(SecurityPolicyConfig config) {
            calls++;
            if (faulty) {
                throw new IllegalStateException("boom");
            }
            last = config;
        }
    }
}
