package com.billy65536.infrastructure.security;

/**
 * 安全策略违规异常：任何违背服务器/框架安全策略的操作抛出（如未授权修改被锁配置）。
 */
public class SecurityPolicyViolationException extends Exception {
    private final String violatedPolicy, originExecutor;

    public SecurityPolicyViolationException(String message, String policy, String originExecutor) {
        super(message + "\t(policy: " + policy + "; executor: " + originExecutor + ")");

        this.violatedPolicy = policy;
        this.originExecutor = originExecutor;
    }

    public String getViolatedPolicy() {
        return violatedPolicy;
    }

    public String getOriginExecutor() {
        return originExecutor;
    }
}
