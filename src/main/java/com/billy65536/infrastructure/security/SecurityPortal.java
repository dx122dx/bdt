package com.billy65536.infrastructure.security;

import com.billy65536.infrastructure.InfrastructureMod;
import com.billy65536.infrastructure.security.builtin.ConfigLockerPolicyConfig;
import com.billy65536.infrastructure.security.builtin.ServerOptinPolicy;
import com.billy65536.infrastructure.security.core.policy.SecurityPolicyConfig;

import net.minecraft.util.Identifier;

/**
 * 静态配置注入门户（仅服务静态配置登记，临时补丁不走此处）。
 *
 * <p>外部模组在初始化时经本门户把各自的默认受保护配置（默认锁）注入目标内置策略，
 * 而不直接触碰执行器内部状态。例如 chunkscanner 经
 * {@link #newConfigBuilder(Identifier)} 取得 {@link ConfigLockerPolicyConfig.Builder}，
 * 填好默认锁后 {@link #injectConfig(Identifier, SecurityPolicyConfig)} 注入
 * {@code security:server-optin} 策略。</p>
 *
 * <p>本门户只引用 builtin 包类型，不反向依赖下游模组；注入后的累积配置由策略自行持有，
 * 并在 {@link com.billy65536.infrastructure.security.core.policy.SecurityManager} 重算时
 * 合并推送。</p>
 */
public final class SecurityPortal {

    private SecurityPortal() {}

    /**
     * 取得指定执行器配置的构造器。
     *
     * @param executorId 目标执行器 id（如 {@code ConfigLocker.EXECUTOR_ID}）
     * @return 该执行器类型的配置构造器
     */
    public static ConfigLockerPolicyConfig.Builder newConfigBuilder(Identifier executorId) {
        return ConfigLockerPolicyConfig.builder(executorId);
    }

    /**
     * 向指定策略注入一份静态配置片段（默认锁）。
     *
     * <p>当前内置策略 {@code security:server-optin} 接受 {@link ConfigLockerPolicyConfig}；
     * 其他类型暂不支持，会被记录并忽略。</p>
     *
     * @param policyId 目标策略 id
     * @param config   静态配置片段
     */
    public static void injectConfig(Identifier policyId, SecurityPolicyConfig config) {
        if (config instanceof ConfigLockerPolicyConfig clc) {
            ServerOptinPolicy.injectStaticConfig(clc);
        } else {
            InfrastructureMod.LOGGER.warn(
                    "SecurityPortal: no static config sink for policy {} with config type {}",
                    policyId, config.getClass().getName());
        }
    }
}
