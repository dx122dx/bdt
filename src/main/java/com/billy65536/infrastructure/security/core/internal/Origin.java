package com.billy65536.infrastructure.security.core.internal;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import net.minecraft.util.Identifier;

/**
 * 安全配置项的<b>来源身份</b>载体，不可变。
 *
 * <p>安全框架把多个策略贡献的配置压平成扁平表时，来源身份会随之丢失，导致门禁拦截后
 * 无法回答「这条路径是被谁锁的」。本类就是钉在每个配置值上的来源标签，随合并链路
 * 一路传递到执行层与拦截点。</p>
 *
 * <ul>
 *   <li>{@link #getPrimary()} —— <b>最终生效来源</b>。与 {@code combine} 的「后者覆盖前者」
 *       值覆盖语义严格一致：多个策略锁同一路径时，取最后一个贡献者。</li>
 *   <li>{@link #getContributors()} —— <b>全部贡献来源</b>的有序集合（通常含 primary，
 *       触及 {@link #MAX_CONTRIBUTORS} 上界后可能不含），
 *       用于「另有 N 个策略贡献」这类审计附注。</li>
 * </ul>
 *
 * <p>{@code contributors} 设有 {@link #MAX_CONTRIBUTORS} 上界：安全策略数量本就是个位数，
 * 设界是为防病态场景下集合无界增长。超限时保留最早的若干个并停止累积，
 * {@code primary} 不受影响——审计的主诉求「精确到最终生效来源」始终得到保证。</p>
 *
 * @apiNote <b>框架内部类型，不属于公开 API，随时可能变更；下游模组不得引用。</b>
 *          本类型不出现在任何面向下游的 API 签名上：策略开发者只调
 *          {@code ConfigLockerPolicyConfig.Builder.lock(...)} 四参方法，来源由框架自动注入。
 */
public final class Origin {

    /** 贡献者集合的容量上界，防病态增长。 */
    public static final int MAX_CONTRIBUTORS = 8;

    /** 来源未知（尚未回填，或确实无法归因）时的空来源单例。 */
    public static final Origin UNKNOWN = new Origin(null, Set.of());

    private final Identifier primary;
    private final Set<Identifier> contributors;

    private Origin(Identifier primary, Set<Identifier> contributors) {
        this.primary = primary;
        this.contributors = contributors;
    }

    /**
     * 创建单一来源。
     *
     * @param source 来源策略 id；{@code null} 时返回 {@link #UNKNOWN}
     * @return 以 {@code source} 为唯一贡献者的来源
     */
    public static Origin of(Identifier source) {
        if (source == null) return UNKNOWN;
        return new Origin(source, Set.of(source));
    }

    /**
     * 与更晚的来源合并：{@code primary} 取 {@code later}，{@code contributors} 取有序并集。
     *
     * <p>primary 取后者，是为了与 {@code SecurityPolicyConfig.combine} 的值覆盖语义保持
     * 严格一致——值来自谁，来源就标谁，不引入语义分叉。</p>
     *
     * <p>结果与自身等价时直接返回 {@code this}，避免温热路径（每次策略启停都会触发
     * {@code recompute}）上的无谓分配。</p>
     *
     * <p>累积到 {@link #MAX_CONTRIBUTORS} 后停止收录新贡献者，此时 {@code primary}
     * 仍如实更新为 {@code later} 的，但可能<b>不在</b> {@code contributors} 中——
     * 上界只影响「另有 N 个」这类附注的完整度，不影响最终生效来源的准确性。</p>
     *
     * @param later 更晚参与合并的来源；{@code null} 或 {@link #UNKNOWN} 时返回 {@code this}
     * @return 合并后的来源；与自身等价时为 {@code this}
     */
    public Origin merge(Origin later) {
        if (later == null || later.primary == null) return this;
        if (this.primary == null) return later;
        if (this.primary.equals(later.primary) && this.contributors.containsAll(later.contributors)) {
            return this;
        }

        Set<Identifier> merged = new LinkedHashSet<>(this.contributors);
        for (Identifier c : later.contributors) {
            if (merged.size() >= MAX_CONTRIBUTORS && !merged.contains(c)) break;
            merged.add(c);
        }
        return new Origin(later.primary, Collections.unmodifiableSet(merged));
    }

    /** 最终生效来源的策略 id；{@code null} 表示来源未知。 */
    public Identifier getPrimary() {
        return primary;
    }

    /**
     * 全部贡献来源的有序不可变视图；来源未知时为空集。
     *
     * <p>通常包含 {@link #getPrimary()}；仅在贡献者数触及 {@link #MAX_CONTRIBUTORS}
     * 上界后新来源被拒收时可能不含。</p>
     */
    public Set<Identifier> getContributors() {
        return contributors;
    }

    /** 来源是否未知（无 primary）。 */
    public boolean isUnknown() {
        return primary == null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Origin other)) return false;
        return Objects.equals(primary, other.primary)
                && contributors.equals(other.contributors);
    }

    @Override
    public int hashCode() {
        return Objects.hash(primary, contributors);
    }

    @Override
    public String toString() {
        return isUnknown() ? "Origin[unknown]"
                : "Origin[" + primary + ", contributors=" + contributors + "]";
    }
}
