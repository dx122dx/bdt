package com.billy65536.infrastructure.core.security;

/**
 * 安全策略违规异常：任何违背服务器/框架安全策略的操作抛出（如未授权修改被锁配置）。
 *
 * <p>原位于 chunkscanner 的 {@code security} 包，现上移至 infrastructure 核心安全层，
 * 作为通用安全异常基类，供 {@code ConfigLocker} 等机制复用。</p>
 */
public class SecurityPolicyViolationException extends Exception {
    private final String violatedPolicy;

    public SecurityPolicyViolationException(String message, String policy) {
        super(message + "\t(policy: " + policy + ")");

        this.violatedPolicy = policy;
    }

    public String getViolatedPolicy() {
        return violatedPolicy;
    }
}
