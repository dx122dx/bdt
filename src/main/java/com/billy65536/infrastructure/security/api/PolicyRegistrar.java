package com.billy65536.infrastructure.security.api;

import com.billy65536.infrastructure.security.core.policy.ISecurityPolicy;

/**
 * 策略登记器：由 {@link SecurityPortal#registerPolicy} 提供给外部模组，
 * 在其 lambda 内调用 {@link #register(ISecurityPolicy)} 完成策略登记。
 *
 * <p>外部模组<b>不应</b>自行实现本接口，只应在框架传入的实例上调用方法。
 * 登记的时序由框架统一编排，外部无需关心策略与执行器的先后关系。</p>
 */
@FunctionalInterface
public interface PolicyRegistrar {

    /** 登记一个安全策略。 */
    void register(ISecurityPolicy policy);
}
