package com.billy65536.infrastructure.security;

import com.billy65536.infrastructure.InfrastructureMod;
import com.billy65536.infrastructure.security.core.policy.ISecurityPolicy;
import com.billy65536.infrastructure.security.core.policy.SecurityManager;
import com.billy65536.infrastructure.security.core.policy.SecurityPolicyConfig;

import net.minecraft.util.Identifier;

/**
 * 静态配置注入门户（仅服务静态配置登记，临时补丁不走此处）。
 *
 * <p>外部模组在初始化时经本门户把各自的默认受保护配置注入目标策略，而不直接触碰
 * {@link SecurityManager} 或执行器内部状态。调用方自行用目标执行器的配置类型构造片段，
 * 例如：</p>
 *
 * <pre>{@code
 * SecurityPortal.injectConfig(ServerOptinPolicy.ID,
 *         ConfigLockerPolicyConfig.builder(ConfigLocker.EXECUTOR_ID)
 *                 .lock("mymod", "config", "some.field", "false")
 *                 .build());
 * }</pre>
 *
 * <p>门户本身<b>不认识任何具体配置类型</b>：它只把片段转交给目标策略的
 * {@link ISecurityPolicy#injectStaticConfig(SecurityPolicyConfig)}，由策略判定是否接受。
 * 因此新增执行器 / 配置形状时无需改动本类。</p>
 */
public final class SecurityPortal {

    private SecurityPortal() {}

    /**
     * 向指定策略注入一份静态配置片段。
     *
     * <p>目标策略必须<b>已注册</b>到 {@link SecurityManager}；注入失败只记录警告，
     * 绝不抛出——安全层的登记失败不应阻断宿主模组的初始化。</p>
     *
     * @param policyId 目标策略 id
     * @param config   静态配置片段
     * @return {@code true} 表示策略已接受该片段
     */
    public static boolean injectConfig(Identifier policyId, SecurityPolicyConfig config) {
        if (policyId == null || config == null) {
            InfrastructureMod.LOGGER.warn("SecurityPortal: null policyId or config, injection ignored");
            return false;
        }
        ISecurityPolicy policy = SecurityManager.get(policyId);
        if (policy == null) {
            InfrastructureMod.LOGGER.warn(
                    "SecurityPortal: target policy {} is not registered, injection ignored", policyId);
            return false;
        }
        if (!policy.injectStaticConfig(config)) {
            InfrastructureMod.LOGGER.warn(
                    "SecurityPortal: policy {} rejected static config of type {}",
                    policyId, config.getClass().getName());
            return false;
        }
        return true;
    }
}
