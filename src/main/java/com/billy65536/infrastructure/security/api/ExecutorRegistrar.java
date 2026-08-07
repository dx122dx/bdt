package com.billy65536.infrastructure.security.api;

import com.billy65536.infrastructure.security.core.policy.ISecurityExecutor;

/**
 * 执行器登记器：由 {@link SecurityPortal#registerExecutor} 提供给外部模组，
 * 在其 lambda 内调用 {@link #register(ISecurityExecutor)} 完成执行器登记。
 *
 * <p>外部模组<b>不应</b>自行实现本接口，只应在框架传入的实例上调用方法；
 * 登记的实际落地（含时序编排）由框架内部处理。</p>
 */
@FunctionalInterface
public interface ExecutorRegistrar {

    /** 登记一个安全执行器。 */
    void register(ISecurityExecutor executor);
}
