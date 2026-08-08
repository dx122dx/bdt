package com.billy65536.infrastructure.security;

import java.util.Collection;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.minecraft.util.Identifier;

import com.billy65536.infrastructure.security.api.ConfigInjector;
import com.billy65536.infrastructure.security.api.ExecutorRegistrar;
import com.billy65536.infrastructure.security.api.PolicyRegistrar;
import com.billy65536.infrastructure.security.core.policy.RegistrationCoordinator;
import com.billy65536.infrastructure.security.core.policy.SecurityConfigPatch;
import com.billy65536.infrastructure.security.core.policy.SecurityContext;

/**
 * 安全框架对外的<b>唯一</b>注册门户。
 *
 * <p>外部模组（经 {@code infrastructure:security} entrypoint）与框架内置模块都只通过本类登记
 * 执行器 / 策略 / 静态配置，<b>绝不直接</b>触碰 {@code SecurityManager}：注册与注入的时序由本类
 * 与 {@link RegistrationCoordinator} 统一编排，外部无需关心登记顺序，也不会再出现
 * 「注册时找不到对应 Policy / Executor」的时序混乱。</p>
 *
 * <h2>用法</h2>
 *
 * <pre>{@code
 * // 登记执行器
 * SecurityPortal.registerExecutor(reg -> reg.register(myExecutor));
 *
 * // 登记策略
 * SecurityPortal.registerPolicy(reg -> reg.register(myPolicy));
 *
 * // 向某策略注入静态配置（默认锁等）；可在一次调用内注入多份
 * SecurityPortal.injectConfig(inj -> {
 *     inj.inject(ServerOptinPolicy.ID, ConfigLockerPolicyConfig.builder(ConfigLocker.EXECUTOR_ID)
 *             .lock("mymod", "config", "some.field", "false").build());
 * });
 * }</pre>
 *
 * <p>执行器与策略<b>即时</b>登记（二者无相互依赖顺序要求）；静态配置注入<b>缓冲</b>，
 * 由框架宿主在收集完所有贡献后调用 {@link #apply()} 统一物化——此时全部策略 / 执行器必然已登记。</p>
 */
public final class SecurityPortal {

    private SecurityPortal() {}

    /**
     * 登记一个安全执行器。
     *
     * @param action 接收 {@link ExecutorRegistrar}，在其内部调用 {@code register(executor)}
     */
    public static void registerExecutor(Consumer<ExecutorRegistrar> action) {
        ExecutorRegistrar reg = executor -> RegistrationCoordinator.registerExecutorNow(executor);
        action.accept(reg);
    }

    /**
     * 登记一个安全策略。
     *
     * @param action 接收 {@link PolicyRegistrar}，在其内部调用 {@code register(policy)}
     */
    public static void registerPolicy(Consumer<PolicyRegistrar> action) {
        PolicyRegistrar reg = policy -> RegistrationCoordinator.registerPolicyNow(policy);
        action.accept(reg);
    }

    /**
     * 向指定策略注入一份或多份静态配置片段（默认锁等）。
     *
     * <p>注入被<b>缓冲</b>，直到 {@link #apply()} 统一物化；缓冲期内即使目标策略尚未登记也不会
     * 失败，因为物化发生在全部策略登记之后。目标策略缺失或拒绝注入只记警告。</p>
     *
     * @param action 接收 {@link ConfigInjector}，在其内部调用 {@code inject(policyId, config)}
     */
    public static void injectConfig(Consumer<ConfigInjector> action) {
        ConfigInjector inj = (policyId, config) -> RegistrationCoordinator.enqueueInjection(policyId, config);
        action.accept(inj);
    }

    /**
     * 统一物化全部缓冲的静态配置注入。
     *
     * <p>必须在所有执行器 / 策略登记完成后调用一次，由框架宿主（{@code SecurityManagerModule}）
     * 在 {@code PolicyPackManager.registerAll()} 之后调用。重复调用为空操作。</p>
     */
    static void apply() {
        RegistrationCoordinator.apply();
    }

    // ===== 受控写入口转发（包级私有：仅框架内部 SecurityManagerModule / SecurityCommands / 测试可触达） =====
    // 外部模组不得触碰这些入口，激活态只能由框架生命周期驱动或被显式拒绝（不可手动开关的策略）。

    /**
     * 受控入口：手动激活 / 停用策略。
     *
     * <p>对不可手动开关的策略，停用请求会被 {@code SecurityManager} 拒绝；这正是
     * 「Server-Optin 不得手动更改」的硬约束落点。</p>
     */
    static boolean activatePolicy(Identifier id, boolean value) {
        return RegistrationCoordinator.setActiveNow(id, value);
    }

    /**
     * 框架生命周期入口（连接 / 断连触发），绕过手动开关限制。
     */
    static boolean activatePolicyInternal(Identifier id, boolean value) {
        return RegistrationCoordinator.setActiveInternalNow(id, value);
    }

    /** 登记一条 Override 补丁。 */
    static void submitPolicyPatch(SecurityConfigPatch patch) {
        RegistrationCoordinator.submitPatchNow(patch);
    }

    /** 设置覆盖门控（熔断定）。 */
    static void setGate(Supplier<Boolean> gate) {
        RegistrationCoordinator.setGateNow(gate);
    }

    /** 清空全部 Override 补丁并回落静态结果。 */
    static void clearOverrides() {
        RegistrationCoordinator.clearOverridesNow();
    }

    /** 三层合并 + 全量推送。 */
    static void recomputePolicies(Collection<Identifier> executorIds) {
        RegistrationCoordinator.recomputeNow(executorIds);
    }

    /** 取得全局 Override 上下文（修改器入口）。 */
    static SecurityContext getContext() {
        return RegistrationCoordinator.getContextNow();
    }
}
