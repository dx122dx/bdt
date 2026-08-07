package com.billy65536.infrastructure.security.core.policy;

import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 安全执行器：安全策略之下的具体执行单元。
 *
 * <p>执行器是「实际动手做事」的一层，例如
 * {@code security:server-optin/config-locker} 负责在策略激活时锁定配置、
 * 停用时释放锁定。</p>
 *
 * <h2>启用态派生（关键约束）</h2>
 *
 * <p>执行器<b>不持有</b>自己的启用态字段。其启用与否完全由所属策略的激活状态决定，
 * 由 {@link PolicyRegistry#isExecutorEnabled(Identifier)} 实时派生。若执行器另存一份
 * 状态，将出现两个可能不一致的「真相」，故刻意不提供 {@code isEnabled()}。</p>
 *
 * <h2>命名约定</h2>
 *
 * <p>id 采用 {@code <module>:<policy>/<executor>} 形式，即命名空间为所属模块，
 * path 为「所属策略名 / 执行器名」。这样从执行器 id 即可反查其所属策略 id
 * （见 {@link PolicyRegistry#findPolicyOf(Identifier)}）。</p>
 */
public interface ISecurityExecutor {

    /** 唯一标识符，形如 {@code module:policy/executor}，不可变。 */
    Identifier getId();

    /** 显示名称，用于命令列表展示。 */
    Text getName();

    /** 描述文本，用于 {@code info} 子命令与帮助信息。 */
    Text getDescription();

    /**
     * 所属策略激活时的回调，在此施加实际的安全约束。
     *
     * <p>在策略自身的 {@code onActivate} <b>之后</b>调用。</p>
     *
     * <p>实现<b>必须幂等</b>：框架侧保证策略激活态未变化时不会重复触发，
     * 但同一执行器可被登记到多个策略下，且外部调用者的行为不受框架约束。</p>
     *
     * <p>抛出的异常会被 {@link PolicyRegistry} 捕获记录，不影响同策略下其余执行器，
     * 也不会使策略激活失败。</p>
     */
    default void onEnable() {}

    /**
     * 所属策略停用时的回调，在此解除安全约束。
     *
     * <p>在策略自身的 {@code onDeactivate} <b>之前</b>调用；
     * 幂等要求与异常处理同 {@link #onEnable()}。</p>
     */
    default void onDisable() {}
}
