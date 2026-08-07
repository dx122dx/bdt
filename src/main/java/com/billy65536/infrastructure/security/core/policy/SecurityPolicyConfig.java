package com.billy65536.infrastructure.security.core.policy;

import net.minecraft.util.Identifier;

/**
 * 不可变的安全策略配置片段。
 *
 * <p>每个 {@link ISecurityExecutor} 持有一类私有形状的 {@code SecurityPolicyConfig}
 * （例如 {@code ConfigLocker} 是「路径 → 强制值」的映射）。配置本身不含任何可变状态，
 * 所有「合并 / 打补丁」操作都返回<b>新的</b>实例，便于 Manager 在重算时安全地叠加输入。</p>
 *
 * <p>Manager 侧只依赖本接口的三个多态入口，不需要 instanceof 分支即可完成三层合并：</p>
 * <ul>
 *   <li>{@link #getExecutorId()} —— 标识这份配置要送给哪个执行器；</li>
 *   <li>{@link #combine(SecurityPolicyConfig)} —— 与另一份同类配置合并（后者覆盖前者）；</li>
 *   <li>{@link #applyPatch(SecurityConfigPatch)} —— 应用一条受控补丁（外部来源的唯一入口）。</li>
 * </ul>
 */
public interface SecurityPolicyConfig {

    /** 这份配置目标执行器的 id（形如 {@code module:policy/executor}）。 */
    Identifier getExecutorId();

    /**
     * 与另一份配置合并，返回合并后的新实例（本实例不被修改）。
     *
     * <p>合并语义由实现定义，通常是「后者覆盖前者」（按 key 取并集）。
     * 仅当 {@code other} 为同类配置时才合并，否则实现应返回自身。</p>
     *
     * @param other 另一份配置（可能为 null，表示无额外输入）
     * @return 合并后的新配置实例
     */
    SecurityPolicyConfig combine(SecurityPolicyConfig other);

    /**
     * 应用一条受控补丁，返回打过补丁的新实例（本实例不被修改）。
     *
     * <p>这是外部来源（调试动作、服务端指令）改写配置的唯一受控入口；补丁内部只表达
     * 「增 / 删」语义，绝不允许直接触碰执行器内部状态。</p>
     *
     * @param patch 补丁（非同类补丁时实现应返回自身，忽略之）
     * @return 应用补丁后的新配置实例
     */
    SecurityPolicyConfig applyPatch(SecurityConfigPatch patch);
}
