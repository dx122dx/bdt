package com.billy65536.infrastructure.security.core.policy;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import com.billy65536.infrastructure.InfrastructureMod;
import com.billy65536.infrastructure.security.core.event.SecurityEvents;

import net.minecraft.util.Identifier;

/**
 * 安全框架的唯一中枢（原 {@code PolicyRegistry} 升级）。
 *
 * <p>职责：注册 {@link ISecurityPolicy} 与 {@link ISecurityExecutor}、持有激活态、
 * 持有 {@link SecurityContext} 补丁表、执行三层合并 {@link #recompute(Collection)} 并
 * 全量推送给执行器、对单个执行器异常做隔离。</p>
 *
 * <h2>状态模型</h2>
 *
 * <p>只有<b>策略</b>持有激活态（{@link #active} 集合）。执行器<b>不</b>持有启用态，其启用与否
 * 派生自「合并缓存中是否收到非空配置」——见 {@link #isExecutorEnabled(Identifier)}。</p>
 *
 * <h2>三层合并链路</h2>
 *
 * <pre>
 * 第1层 静态策略：激活 Policy 的 getConfigs() 依次 combine        → baseMerged
 * 第2层 Policy Override：Context 补丁表按登记顺序 applyPatch       → patched
 * 第3层 安全门控：overrideAllowed ? patched : base                 → final
 * 最终 final 全量推送 executor.onPolicyChanged(final)
 * </pre>
 *
 * <h2>异常隔离</h2>
 *
 * <p>执行器的 {@code onPolicyChanged} 逐个用 {@code try/catch(Throwable)} 包住：单个执行器
 * 抛异常只记日志，不阻断其余执行器、不回滚激活态——安全回调失败绝不允许阻断连接流程或
 * 使游戏崩溃。</p>
 *
 * <p><b>线程约束</b>：内部集合均为普通非同步实现，假定注册与状态变更都发生在客户端主线程。</p>
 */
public final class SecurityManager {

    /** 已注册策略，按注册顺序保序。 */
    private static final Map<Identifier, ISecurityPolicy> policies = new LinkedHashMap<>();

    /** 执行器索引：执行器 id → 执行器，便于命令按 id 直接反查。 */
    private static final Map<Identifier, ISecurityExecutor> executors = new LinkedHashMap<>();

    /** 当前处于激活状态的策略 id 集合。 */
    private static final Set<Identifier> active = new LinkedHashSet<>();

    /** 每个执行器最近一次推送的合并配置缓存；非空即代表该执行器「启用」。 */
    private static final Map<Identifier, SecurityPolicyConfig> mergedCache = new LinkedHashMap<>();

    /** Override 补丁持有者（全局单例）。 */
    private static final SecurityContext context = new SecurityContext();

    /** 覆盖门控：返回 {@code true} 时允许叠加第 2 层补丁，{@code false} 时熔断回落 base。 */
    private static Supplier<Boolean> overrideGate = () -> true;

    private SecurityManager() {}

    /**
     * 注册一个安全策略。重复注册同 ID 会覆盖。
     *
     * <p>注册时按 {@link com.billy65536.infrastructure.security.core.policy.ActivationTrigger#ALWAYS}
     * 决定是否立即激活；其余触发条件的策略初始为未激活，等待框架判定或手动开关。</p>
     *
     * <p><b>包级私有</b>：外部模组不得直接调用本方法，统一经
     * {@link com.billy65536.infrastructure.security.SecurityPortal#registerPolicy} 登记，
     * 由框架保证时序。</p>
     *
     * @param policy 策略实例，null 或 id 为 null 时忽略并告警
     */
    static void register(ISecurityPolicy policy) {
        if (policy == null || policy.getId() == null) {
            InfrastructureMod.LOGGER.warn(
                    "Attempted to register null security policy or policy with null ID, ignored");
            return;
        }
        Identifier id = policy.getId();
        if (policies.containsKey(id)) {
            InfrastructureMod.LOGGER.warn("Security policy {} is already registered, overwriting", id);
        }
        policies.put(id, policy);

        InfrastructureMod.LOGGER.info(
                "Registered security policy: {} (trigger: {}, configs: {})",
                id, policy.getTrigger(), policy.getConfigs().size());

        if (policy.getTrigger() == ActivationTrigger.ALWAYS) {
            setActive(id, true);
        }
    }

    /**
     * 注册一个安全执行器（独立于策略，供重算时按 id 推送配置）。
     *
     * <p><b>包级私有</b>：外部模组不得直接调用本方法，统一经
     * {@link com.billy65536.infrastructure.security.SecurityPortal#registerExecutor} 登记。</p>
     */
    static void registerExecutor(ISecurityExecutor executor) {
        if (executor == null || executor.getId() == null) {
            InfrastructureMod.LOGGER.warn(
                    "Attempted to register null security executor or executor with null ID, ignored");
            return;
        }
        executors.put(executor.getId(), executor);
    }

    /** 设置覆盖门控（熔断定）。由宿主模块注入其配置开关的活引用。 */
    public static void setOverrideGate(Supplier<Boolean> gate) {
        overrideGate = (gate == null) ? () -> true : gate;
    }

    /** 当前覆盖门控是否放行（诊断用）。 */
    public static boolean isOverrideAllowed() {
        return overrideGate.get();
    }

    /** 取得全局 Override 上下文（修改器入口）。 */
    public static SecurityContext getContext() {
        return context;
    }

    /** 登记一条补丁（由 {@link SecurityContext} / 补丁修改器的 {@code apply()} 调用）。 */
    public static void submitPatch(SecurityConfigPatch patch) {
        context.submitPatch(patch);
    }

    /** 清空全部 Override 补丁，并使各执行器回落到静态合并结果。 */
    public static void clearOverrides() {
        context.clearPatches();
        recompute(new LinkedHashSet<>(executors.keySet()));
    }

    /** 通过 ID 获取策略。 */
    public static ISecurityPolicy get(Identifier id) {
        return policies.get(id);
    }

    /** 所有已注册策略（按注册顺序）。 */
    public static Collection<ISecurityPolicy> getAll() {
        return Collections.unmodifiableCollection(policies.values());
    }

    /** 已注册策略数量。 */
    public static int size() {
        return policies.size();
    }

    /** 通过 ID 获取执行器。 */
    public static ISecurityExecutor getExecutor(Identifier id) {
        return executors.get(id);
    }

    /** 所有已注册执行器（跨策略汇总）。 */
    public static Collection<ISecurityExecutor> getAllExecutors() {
        return Collections.unmodifiableCollection(executors.values());
    }

    /** 反查某配置所属的执行器（按 executorId）。 */
    public static ISecurityExecutor executorOf(Identifier executorId) {
        return executors.get(executorId);
    }

    /** 策略是否激活（未注册 id 视为未激活）。 */
    public static boolean isActive(Identifier policyId) {
        return active.contains(policyId);
    }

    /**
     * 执行器是否启用。
     *
     * <p>派生自「合并缓存中是否收到非空配置」：策略激活且产出配置即启用；策略停用或
     * 无静态配置时缓存为 null，视为未启用。</p>
     */
    public static boolean isExecutorEnabled(Identifier executorId) {
        return mergedCache.get(executorId) != null;
    }

    /**
     * 设置策略激活态，并联动重算其下辖执行器配置。
     *
     * <p>状态未变化直接返回（幂等）。变化后先更新激活集，再 {@link #recompute(Collection)}
     * 推送新配置（此时锁定已落地），最后触发框架级事件——保证子事件晚于实际动作。</p>
     *
     * @return true 表示状态确实变更，false 表示策略未注册或状态未变
     */
    public static boolean setActive(Identifier policyId, boolean value) {
        ISecurityPolicy policy = policies.get(policyId);
        if (policy == null) {
            InfrastructureMod.LOGGER.warn(
                    "Attempted to set state of unregistered security policy: {}", policyId);
            return false;
        }
        if (active.contains(policyId) == value) {
            return false;
        }

        if (value) {
            active.add(policyId);
        } else {
            active.remove(policyId);
        }

        // 先重算推送（锁定落地），再触发事件，保证子事件晚于实际动作
        Set<Identifier> affected = new LinkedHashSet<>();
        for (SecurityPolicyConfig cfg : policy.getConfigs()) {
            affected.add(cfg.getExecutorId());
        }
        recompute(affected);

        if (value) {
            SecurityEvents.ACTIVATE.invoker().onActivated(policy);
        } else {
            SecurityEvents.DEACTIVATE.invoker().onDeactivated(policy);
        }
        InfrastructureMod.LOGGER.info("Security policy {} is now {}",
                policyId, value ? "active" : "inactive");
        return true;
    }

    /**
     * 三层合并 + 全量推送。所有输入变化（setActive / submitPatch / injectConfig）统一收敛于此。
     *
     * <p>对每个受影响的执行器：</p>
     * <ol>
     *   <li>base = 激活 Policy 中目标 executorId 的配置依次 combine；</li>
     *   <li>patched = base 依次 applyPatch 补丁表中该 executorId 的补丁；</li>
     *   <li>final = overrideGate 放行 ? patched : base（熔断回落 base）；</li>
     *   <li>mergedCache 缓存 final，executor.onPolicyChanged(final)（异常隔离）。</li>
     * </ol>
     */
    public static void recompute(Collection<Identifier> executorIds) {
        for (Identifier id : executorIds) {
            SecurityPolicyConfig base = null;
            for (ISecurityPolicy p : policies.values()) {
                if (!active.contains(p.getId())) continue;
                for (SecurityPolicyConfig cfg : p.getConfigs()) {
                    if (id.equals(cfg.getExecutorId())) {
                        // 来源回填：静态策略层来源身份的唯一注入点。走接口的多态入口，
                        // Manager 无需认识任何具体配置形状，策略侧也全程无感知。
                        SecurityPolicyConfig stamped = cfg.withOrigin(p.getId());
                        base = (base == null) ? stamped : base.combine(stamped);
                    }
                }
            }

            SecurityPolicyConfig finalCfg;
            if (base == null) {
                // 无激活策略贡献该执行器：回落为空（执行器释放全部约束）
                finalCfg = null;
            } else {
                SecurityPolicyConfig patched = base;
                for (SecurityConfigPatch patch : context.patchesFor(id)) {
                    patched = patched.applyPatch(patch);
                }
                finalCfg = overrideGate.get() ? patched : base;
            }

            if (finalCfg == null) {
                mergedCache.remove(id);
            } else {
                mergedCache.put(id, finalCfg);
            }

            ISecurityExecutor ex = executors.get(id);
            if (ex != null) {
                try {
                    ex.onPolicyChanged(finalCfg);
                } catch (Throwable t) {
                    InfrastructureMod.LOGGER.error(
                            "Security executor {} threw an exception in onPolicyChanged", id, t);
                }
            }
        }
    }
}
