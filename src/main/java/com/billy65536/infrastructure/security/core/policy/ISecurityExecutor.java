package com.billy65536.infrastructure.security.core.policy;

import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 安全执行器：安全策略之下的具体执行单元。
 *
 * <p>执行器是「实际动手做事」的一层，例如
 * {@code security:config-locker} 负责在策略激活时锁定配置、停用时释放锁定。</p>
 *
 * <h2>极简契约</h2>
 *
 * <p>执行器<b>只认一份全量配置</b>：{@link #onPolicyChanged(SecurityPolicyConfig)} 收到本类型
 * 合并后的全量配置即幂等应用；不再有启用 / 停用概念，「停用」等价于收到 {@code null}
 * （空配置，释放全部约束）。全部信任决策集中在 {@link SecurityManager} 一处，执行器
 * 不持有任何启用态字段，避免与策略激活态出现两份真相。</p>
 *
 * <h2>命名约定</h2>
 *
 * <p>id 采用 {@code <module>:<policy>/<executor>} 形式，即命名空间为所属模块，
 * path 为「所属策略名 / 执行器名」。</p>
 */
public interface ISecurityExecutor {

    /** 唯一标识符，形如 {@code module:policy/executor}，不可变。 */
    Identifier getId();

    /** 显示名称，用于命令列表展示。 */
    Text getName();

    /** 描述文本，用于 {@code info} 子命令与帮助信息。 */
    Text getDescription();

    /**
     * 收到本执行器合并后的全量配置时幂等应用。
     *
     * <p>传入 {@code null} 表示「无配置」（策略停用或未被任何激活策略覆盖），执行器应
     * 释放全部已施加的约束。</p>
     *
     * <p>实现<b>必须幂等</b>：框架侧保证激活态未变化时不会重复触发，但同一执行器可被
     * 登记到多个策略下，且外部调用者行为不受框架约束。</p>
     *
     * <p>抛出的异常会被 {@link SecurityManager} 捕获记录，不影响其余执行器，也不会使
     * 策略激活失败。</p>
     *
     * @param config 合并后的全量配置；{@code null} 表示释放全部约束
     */
    void onPolicyChanged(SecurityPolicyConfig config);
}
