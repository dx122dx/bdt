package com.billy65536.infrastructure.security.core.policy;

import java.util.List;

import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 安全策略扩展点：安全框架中的一等公民。
 *
 * <p>一个策略代表一组语义相关的安全约束，持有激活状态，并下辖若干
 * {@link ISecurityExecutor}。策略激活时其全部执行器随之启用，停用时随之禁用。</p>
 *
 * <p>外部模组通过
 * {@link com.billy65536.infrastructure.security.api.SecurityPolicyProvider} 扩展点
 * 贡献自己的策略，经 {@link PolicyRegistry#register} 注册后即可由
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
 * {@link com.billy65536.infrastructure.security.policy.ServerOptinPolicy#LOCKS_APPLIED}）。
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
     * 本策略下辖的全部执行器。
     *
     * <p>应返回稳定的集合（每次调用元素一致）：注册时框架据此建立执行器索引，
     * 而启停时又会重新遍历本方法的返回值。若两次结果不一致，注册后新增的执行器
     * 会收到启停回调却无法被命令查询到。允许返回 {@code null} 或空表（视为无执行器）。</p>
     *
     * @return 下辖执行器列表
     */
    List<ISecurityExecutor> getExecutors();

    /**
     * 策略被激活时的回调，在全部执行器 {@code onEnable} <b>之前</b>调用。
     *
     * <p>实现必须幂等。适合放置执行器之间共享的前置准备；实际的安全约束应交由
     * 执行器施加。抛出的异常会被 {@link PolicyRegistry} 捕获记录，不会中断激活流程。</p>
     */
    default void onActivate() {}

    /**
     * 策略被停用时的回调，在全部执行器 {@code onDisable} <b>之后</b>调用。
     *
     * <p>幂等要求与异常处理同 {@link #onActivate()}。</p>
     */
    default void onDeactivate() {}
}
