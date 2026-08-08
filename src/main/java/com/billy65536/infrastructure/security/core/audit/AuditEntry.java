package com.billy65536.infrastructure.security.core.audit;

import java.util.Objects;

import com.billy65536.infrastructure.security.core.internal.Origin;

import net.minecraft.util.Identifier;

/**
 * 一条「阻止操作」审计记录，不可变值对象。
 *
 * <p>记录安全门禁拦截一次配置写入的完整现场：什么时候、拦了哪条路径、
 * 被哪个策略锁的、由哪个执行器执行、经由哪条写入渠道、试图写入什么值。</p>
 *
 * <p>短时间内的重复拦截（典型场景：GUI 滑块连续拖动）不会各产生一条记录，
 * 而是{@linkplain #withHit() 折叠}进同一条并累加 {@link #hitCount()}。</p>
 */
public final class AuditEntry {

    /** 触发拦截的写入渠道。 */
    public enum Channel {
        /** 显式赋值：{@code ConfigAccessor.setValue}。 */
        SET,
        /** 重置为默认值：{@code ConfigAccessor.resetValue}。 */
        RESET
    }

    private final long timestamp;
    private final String fullPath;
    private final Origin origin;
    private final Identifier executorId;
    private final Channel channel;
    private final String attemptedValue;
    private final int hitCount;

    /**
     * 构造一条完整的审计记录。
     *
     * <p>一般不直接调用，优先用 {@link #now} 建首条、{@link #withHit()} 折叠重复命中。</p>
     *
     * @param timestamp      发生时刻（{@code System.currentTimeMillis()}）
     * @param fullPath       被拒的完整配置路径
     * @param origin         来源标签，可为 {@code null} 表示无法归因
     * @param executorId     执行拦截的执行器 id
     * @param channel        写入渠道
     * @param attemptedValue 试图写入的值；{@link Channel#RESET} 时为 {@code null}
     * @param hitCount       命中次数，至少为 1
     */
    public AuditEntry(long timestamp, String fullPath, Origin origin, Identifier executorId,
                      Channel channel, String attemptedValue, int hitCount) {
        this.timestamp = timestamp;
        this.fullPath = fullPath;
        this.origin = origin;
        this.executorId = executorId;
        this.channel = channel;
        this.attemptedValue = attemptedValue;
        this.hitCount = hitCount;
    }

    /**
     * 建一条首次命中的记录，{@code hitCount} 为 1、时间戳取当前。
     *
     * @param fullPath       被拒的完整配置路径
     * @param origin         来源标签，可为 {@code null} 表示无法归因
     * @param executorId     执行拦截的执行器 id
     * @param channel        写入渠道
     * @param attemptedValue 试图写入的值；{@link Channel#RESET} 时为 {@code null}
     * @return 命中数为 1 的新记录
     */
    public static AuditEntry now(String fullPath, Origin origin, Identifier executorId,
                                 Channel channel, String attemptedValue) {
        return new AuditEntry(System.currentTimeMillis(), fullPath, origin, executorId,
                channel, attemptedValue, 1);
    }

    /** 折叠一次重复命中：计数加一并刷新时间戳，其余字段沿用。 */
    public AuditEntry withHit() {
        return new AuditEntry(System.currentTimeMillis(), fullPath, origin, executorId,
                channel, attemptedValue, hitCount + 1);
    }

    /**
     * 判定另一次拦截能否折叠进本条：同路径 + 同最终生效来源 + 同渠道。
     *
     * <p>刻意<b>不比较尝试值</b>：滑块拖动每次的值都不同，若比值就折叠不起来，
     * 而降噪正是折叠的目的。</p>
     *
     * <p>来源只比 {@linkplain Origin#getPrimary() 最终生效来源}而非全部贡献者，
     * 与「值来自谁就标谁」的覆盖语义保持一致。</p>
     *
     * @param path  待折叠拦截的完整配置路径
     * @param other 待折叠拦截的来源标签，可为 {@code null}
     * @param ch    待折叠拦截的写入渠道
     * @return {@code true} 表示可折叠进本条
     */
    public boolean matchesForFold(String path, Origin other, Channel ch) {
        return this.channel == ch
                && Objects.equals(this.fullPath, path)
                && Objects.equals(primaryOf(this.origin), primaryOf(other));
    }

    private static Identifier primaryOf(Origin o) {
        return o == null ? null : o.getPrimary();
    }

    /** 发生时刻（{@code System.currentTimeMillis()}）；折叠时刷新为最近一次命中的时刻。 */
    public long timestamp() {
        return timestamp;
    }

    /** 被拒的完整配置路径。 */
    public String fullPath() {
        return fullPath;
    }

    /**
     * 来源标签；{@code null} 表示无法归因。
     *
     * @apiNote 返回框架内部类型 {@link Origin}，下游模组不得引用。
     */
    public Origin origin() {
        return origin;
    }

    /** 最终生效的来源策略 id；无法归因时为 {@code null}。 */
    public Identifier policyId() {
        return primaryOf(origin);
    }

    /** 执行拦截的执行器 id。 */
    public Identifier executorId() {
        return executorId;
    }

    /** 触发拦截的写入渠道。 */
    public Channel channel() {
        return channel;
    }

    /** 试图写入的值；{@link Channel#RESET} 时为 {@code null}。 */
    public String attemptedValue() {
        return attemptedValue;
    }

    /** 本条已折叠的命中次数，至少为 1。 */
    public int hitCount() {
        return hitCount;
    }

    @Override
    public String toString() {
        return "AuditEntry[" + channel + " " + fullPath + " <- " + origin + " x" + hitCount + "]";
    }
}
