package com.billy65536.infrastructure.core.config;

import com.billy65536.infrastructure.security.SecurityPolicyViolationException;
import com.billy65536.infrastructure.security.builtin.ConfigLocker;
import com.billy65536.infrastructure.security.core.internal.Origin;

/**
 * 配置写入被安全策略拒绝：目标路径处于 {@link ConfigLocker} 锁定之下。
 *
 * <p>继承自 {@link SecurityPolicyViolationException}，以便安全审计层统一捕获锁定的来源
 * （违规策略 id 与触发执行器）。本异常只由 {@link ConfigAccessor} 在写入门禁命中时抛出。</p>
 *
 * <p>推荐经 {@link #of(String)} 构造：来源查询逻辑在该工厂内单点收口，抛出点无需自行
 * 关心来源从哪来。</p>
 */
public class ConfigLockedException extends SecurityPolicyViolationException {

    /** 无法归因到具体策略时的占位来源。 */
    public static final String UNKNOWN_POLICY = "<unknown-policy>";

    /**
     * 按被拒路径构造异常，自动回查该路径的锁定来源。
     *
     * <p>回查不到来源（路径已解锁，或来源未回填）时降级为 {@link #UNKNOWN_POLICY}，
     * 不抛异常——门禁已经在拒绝写入，归因失败不该再掩盖真正的拒绝原因。</p>
     *
     * @param fullPath 被拒的完整配置路径
     * @return 携带真实来源（或占位来源）的异常实例，由调用方抛出
     */
    public static ConfigLockedException of(String fullPath) {
        Origin origin = ConfigLocker.getSource(fullPath);
        String policy = (origin == null || origin.isUnknown())
                ? UNKNOWN_POLICY
                : origin.getPrimary().toString();
        return new ConfigLockedException(fullPath, policy, ConfigLocker.EXECUTOR_ID.toString());
    }

    /**
     * 显式指定来源构造。
     *
     * @param fullPath   被拒的完整配置路径
     * @param policy     锁定该路径的策略 id
     * @param executorId 执行拦截的执行器 id
     */
    public ConfigLockedException(String fullPath, String policy, String executorId) {
        super("Config '" + fullPath + "' is locked by security policy and cannot be modified",
                policy,
                executorId);
    }
}
