package com.billy65536.infrastructure.security.core.policy;

import java.util.Collection;

import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 安全策略扩展点：安全框架中的一等公民。
 *
 * <p>一个策略代表一组语义相关的安全约束，持有激活状态，并产出若干
 * {@link SecurityPolicyConfig}（不可变配置片段）。策略激活时其配置片段被
 * {@link SecurityManager} 合并后全量推送给对应的 {@link ISecurityExecutor}。</p>
 *
 * <p>外部模组通过
 * {@link com.billy65536.infrastructure.security.api.SecurityPolicyProvider} 扩展点
 * 贡献自己的策略，经 {@link SecurityManager#register(ISecurityPolicy)} 注册后即可由
 * {@code /inf security list|status|info|active|deactive} 查询与操控。</p>
 *
 * <h2>被动性</h2>
 *
 * <p>策略<b>不应自行监听</b>连接事件等游戏事件来决定自己的启停，而是通过
 * {@link #getTrigger()} 声明触发条件，由框架集中判定。这样判定逻辑只有一处。</p>
 *
 * <h2>子事件</h2>
 *
 * <p>策略可以自行以 {@code public static final Event<XxxCallback>} 字段暴露专属子事件
 * （参见
 * {@link com.billy65536.infrastructure.security.builtin.ServerOptinPolicy#LOCKS_APPLIED}）。
 * 这类字段是静态的，任何能引用到该策略类的一方都可直接订阅，无需持有策略实例。</p>
 */
public interface ISecurityPolicy {

    /** 唯一标识符，形如 {@code module:policy}，不可变。 */
    Identifier getId();

    /** 显示名称，用于列表与状态展示。 */
    Text getName();

    /** 描述文本，用于 {@code info} 子命令与帮助信息。 */
    Text getDescription();

    /**
     * 激活触发条件，由框架集中判定时读取。
     *
     * <p>默认 {@link ActivationTrigger#MULTIPLAYER_JOIN}：安全策略的典型场景是
     * 进入多人服务器时收紧约束。</p>
     */
    default ActivationTrigger getTrigger() {
        return ActivationTrigger.MULTIPLAYER_JOIN;
    }

    /**
     * 本策略是否允许通过 {@code /inf security active|deactive} 手动开关。
     *
     * <p>默认 {@code false}：安全策略默认不允许玩家自行解除，否则安全约束形同虚设。
     * 仅在策略本身设计为可由玩家自主控制（或用于调试）时才返回 {@code true}。</p>
     */
    default boolean isManuallyToggleable() {
        return false;
    }

    /**
     * 本策略产出的全部不可变配置片段。
     *
     * <p>返回的片段应稳定（每次调用元素一致）。每个片段的 {@link SecurityPolicyConfig#getExecutorId()}
     * 决定它被推送给哪个执行器。允许返回 {@code null} 或空集合（视为无配置）。</p>
     *
     * @return 配置片段集合
     */
    Collection<SecurityPolicyConfig> getConfigs();

    /**
     * 接受一份外部注入的<b>静态</b>配置片段（默认锁等），与本策略已持有的配置合并。
     *
     * <p>由 {@link com.billy65536.infrastructure.security.SecurityPortal#injectConfig} 转发，
     * 是「下游模组声明默认受保护配置」的唯一受控入口。本方法让门户无需认识任何具体的
     * 配置类型或执行器——类型判定由策略自己完成。</p>
     *
     * <p>默认实现拒绝一切注入（返回 {@code false}）：不打算开放静态配置扩展的策略无需实现。
     * 实现方应自行判定 {@code config} 是否为本策略可承载的形状，不匹配即返回 {@code false}；
     * 接受后须触发一次 {@link SecurityManager#recompute(Collection)} 使新配置立即生效。</p>
     *
     * @param config 待注入的静态配置片段（非 null）
     * @return {@code true} 表示已接受并合并，{@code false} 表示本策略不接受该配置
     */
    default boolean injectStaticConfig(SecurityPolicyConfig config) {
        return false;
    }
}
