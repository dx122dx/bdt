package com.billy65536.infrastructure.security.core.policy;

import net.minecraft.util.Identifier;

/**
 * 不可变的安全策略配置片段。
 *
 * <p>每个 {@link ISecurityExecutor} 持有一类私有形状的 {@code SecurityPolicyConfig}
 * （例如 {@code ConfigLocker} 是「路径 → 强制值」的映射）。配置本身不含任何可变状态，
 * 所有「合并 / 打补丁」操作都返回<b>新的</b>实例，便于 Manager 在重算时安全地叠加输入。</p>
 *
 * <p><b>设计声明</b>：Manager 侧只依赖本接口的<b>四个</b>多态入口，即可完成三层合并
 * 与来源回填，<b>无需任何 {@code instanceof} 分支</b>——它自始至终不认识具体配置形状，
 * 新增执行器配置类型也不必改动 Manager：</p>
 * <ul>
 *   <li>{@link #getExecutorId()} —— 标识这份配置要送给哪个执行器；</li>
 *   <li>{@link #combine(SecurityPolicyConfig)} —— 与另一份同类配置合并（后者覆盖前者）；</li>
 *   <li>{@link #applyPatch(SecurityConfigPatch)} —— 应用一条受控补丁（外部来源的唯一入口）；</li>
 *   <li>{@link #withOrigin(Identifier)} —— 合并前回填来源身份，供审计溯源。</li>
 * </ul>
 *
 * <p>「同类判定」的责任落在各实现内部（不同类时返回自身），而非上浮到 Manager，
 * 这正是上述四入口得以保持无分支的前提。</p>
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

    /**
     * 回填这份配置的来源策略 id，返回打上来源标签的新实例（本实例不被修改）。
     *
     * <p><b>框架内部钩子</b>：由 {@code SecurityManager.recompute} 在静态策略层 combine
     * <i>之前</i>调用，是来源身份的唯一注入点。这样一来安全策略的作者无需感知、也无从
     * 篡改来源——他们只描述「锁什么、强制成什么」，来源由框架自行认定。</p>
     *
     * <p>默认实现返回自身：不承载来源诉求的配置类型无需实现本方法，因此新增本入口
     * 对既有执行器配置零影响。</p>
     *
     * @param policyId 贡献这份配置的策略 id
     * @return 回填来源后的新配置实例
     */
    default SecurityPolicyConfig withOrigin(Identifier policyId) {
        return this;
    }
}
