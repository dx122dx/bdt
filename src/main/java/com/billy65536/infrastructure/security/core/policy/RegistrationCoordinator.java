package com.billy65536.infrastructure.security.core.policy;

import java.util.ArrayList;
import java.util.List;

import com.billy65536.infrastructure.InfrastructureMod;

import net.minecraft.util.Identifier;

/**
 * 注册编排器：把外部经 {@link SecurityPortal} 提交的「执行器 / 策略 / 静态配置注入」
 * 按固定顺序落地，消除注册时序混乱。
 *
 * <p>执行器与策略登记为<b>即时</b>生效（二者无相互依赖的顺序要求）；静态配置注入则<b>缓冲</b>，
 * 直到 {@link #apply()} 被框架宿主在收集完所有贡献后统一物化——此时全部策略与执行器必然已登记，
 * 注入不会再出现「找不到对应 Policy / Executor」。</p>
 *
 * <p>本类是框架内部实现，外部不应直接使用；外部统一走 {@link SecurityPortal}。</p>
 */
public final class RegistrationCoordinator {

    /** 缓冲的静态配置注入（policyId + 配置片段），待 {@link #apply()} 落盘。 */
    private static final List<InjectionRequest> pendingInjections = new ArrayList<>();

    private RegistrationCoordinator() {}

    /** 即时登记执行器（实际落地在同包的 {@link SecurityManager}）。 */
    public static void registerExecutorNow(ISecurityExecutor executor) {
        SecurityManager.registerExecutor(executor);
    }

    /** 即时登记策略。 */
    public static void registerPolicyNow(ISecurityPolicy policy) {
        SecurityManager.register(policy);
    }

    /** 缓冲一份静态配置注入，待 {@link #apply()} 统一物化。 */
    public static void enqueueInjection(Identifier policyId, SecurityPolicyConfig config) {
        pendingInjections.add(new InjectionRequest(policyId, config));
    }

    /**
     * 统一物化全部缓冲的静态配置注入。
     *
     * <p>必须在所有执行器 / 策略登记完成后调用一次，由框架宿主在
     * {@code PolicyPackManager.registerAll()} 之后调用。目标策略缺失或拒绝注入只记警告并跳过，
     * 不阻断其余注入。</p>
     */
    public static void apply() {
        if (pendingInjections.isEmpty()) return;
        for (InjectionRequest req : pendingInjections) {
            ISecurityPolicy policy = SecurityManager.get(req.policyId());
            if (policy == null) {
                InfrastructureMod.LOGGER.warn(
                        "SecurityPortal: injection target policy {} is not registered, ignored",
                        req.policyId());
                continue;
            }
            if (!policy.injectStaticConfig(req.config())) {
                InfrastructureMod.LOGGER.warn(
                        "SecurityPortal: policy {} rejected static config of type {}",
                        req.policyId(), req.config().getClass().getName());
            }
        }
        pendingInjections.clear();
    }

    /** 缓冲的注入请求。 */
    private record InjectionRequest(Identifier policyId, SecurityPolicyConfig config) {}
}
