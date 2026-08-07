package com.billy65536.infrastructure.core.config;

import com.billy65536.infrastructure.security.SecurityPolicyViolationException;
import com.billy65536.infrastructure.security.builtin.ConfigLocker;

/**
 * 配置写入被安全策略拒绝：目标路径处于 {@link ConfigLocker} 锁定之下。
 *
 * <p>继承自 {@link SecurityPolicyViolationException}，以便安全审计层统一捕获锁定的来源
 * （违规策略 id 与触发执行器）。本异常只由 {@link ConfigAccessor} 在写入门禁命中时抛出。</p>
 */
public class ConfigLockedException extends SecurityPolicyViolationException {
    public ConfigLockedException(String fullPath) {
        super("Config '" + fullPath + "' is locked by security policy and cannot be modified",
                "unknown",
                ConfigLocker.EXECUTOR_ID.toString());
    }
}
