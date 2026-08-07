package com.billy65536.infrastructure.security.api;

import com.billy65536.infrastructure.security.core.policy.ISecurityPolicy;
import com.billy65536.infrastructure.security.core.policy.SecurityPolicyConfig;

import net.minecraft.util.Identifier;

/**
 * 静态配置注入器：由 {@link SecurityPortal#injectConfig} 提供给外部模组，
 * 在其 lambda 内调用 {@link #inject(Identifier, SecurityPolicyConfig)} 向指定策略注入
 * 默认锁等静态配置片段。
 *
 * <p>注入会被框架<b>缓冲</b>，直到 {@link SecurityPortal#apply()} 统一物化，因此外部模组
 * 无需担心目标策略的登记时序——即使注入早于该策略登记，物化也发生在全部策略登记之后。</p>
 */
@FunctionalInterface
public interface ConfigInjector {

    /** 向指定策略注入一份静态配置片段。 */
    void inject(Identifier policyId, SecurityPolicyConfig config);

    /** 便捷重载：直接向某策略实例注入。 */
    default void inject(ISecurityPolicy policy, SecurityPolicyConfig config) {
        inject(policy.getId(), config);
    }
}
