package com.billy65536.infrastructure.security.core.audit;

import java.util.ArrayList;
import java.util.List;

import com.billy65536.infrastructure.security.core.internal.Origin;

import net.minecraft.util.Identifier;

/**
 * 安全门禁的<b>阻止操作审计流水</b>。
 *
 * <p>每次配置写入被安全策略拦截，都在此留下一条{@link AuditEntry}，
 * 供 {@code /inf security audit} 回溯「谁在什么时候拦了什么」。</p>
 *
 * <h2>有界与降噪</h2>
 * <ul>
 *   <li><b>定长环形缓冲</b>（容量 {@link #CAPACITY}）：O(1) 写入、不扩容、无 GC 压力，
 *       长时间运行内存不增长；写满后最旧记录被自然覆盖。</li>
 *   <li><b>折叠计数</b>：新拦截只与<b>最近一条</b>比对，同路径 + 同来源 + 同渠道即折叠。
 *       只比最近一条（而非全表）既是 O(1)，也精准命中「GUI 滑块连续拖动」这一真实刷屏场景；
 *       非相邻的同路径拦截仍会独立成条，时间线因此不会被打乱。</li>
 * </ul>
 *
 * <h2>线程约束</h2>
 * <p>与安全框架其余部分一致，<b>假定只在客户端主线程访问</b>，故使用非同步结构。
 * 拦截点 {@code ConfigAccessor} 与命令层均在主线程，无需额外同步。</p>
 *
 * @apiNote 本包只依赖 {@link Origin} 这一框架内部来源类型，<b>不反向依赖任何
 *          {@code security.builtin} 具体执行器实现</b>：{@code executorId} 一律由调用方传入。
 *          签名中出现的 {@link Origin} 为框架内部类型，下游模组不得引用。
 */
public final class SecurityAuditLog {

    /** 环形缓冲容量。 */
    public static final int CAPACITY = 128;

    private static final AuditEntry[] BUFFER = new AuditEntry[CAPACITY];

    /** 下一次写入的槽位下标。 */
    private static int cursor = 0;
    /** 已写入的记录数，上限 {@link #CAPACITY}。 */
    private static int size = 0;

    private SecurityAuditLog() {
    }

    /**
     * 记录一次拦截：能折叠进<b>最近一条</b>就累加计数，否则新占一个环形槽位。
     *
     * @param fullPath       被拒的完整配置路径
     * @param origin         来源标签，可为 {@code null} 表示无法归因
     * @param executorId     执行拦截的执行器 id
     * @param channel        写入渠道
     * @param attemptedValue 试图写入的值；{@link AuditEntry.Channel#RESET} 时传 {@code null}
     * @return {@code true} 表示新增了一条记录，{@code false} 表示折叠进了最近一条。
     *         调用方据此决定是否输出日志，从而实现「首次告警、重复静默」的降噪。
     */
    public static boolean record(String fullPath, Origin origin, Identifier executorId,
                                 AuditEntry.Channel channel, String attemptedValue) {
        int lastIndex = (cursor - 1 + CAPACITY) % CAPACITY;
        AuditEntry last = size > 0 ? BUFFER[lastIndex] : null;

        if (last != null && last.matchesForFold(fullPath, origin, channel)) {
            BUFFER[lastIndex] = last.withHit();
            return false;
        }

        BUFFER[cursor] = AuditEntry.now(fullPath, origin, executorId, channel, attemptedValue);
        cursor = (cursor + 1) % CAPACITY;
        if (size < CAPACITY) size++;
        return true;
    }

    /**
     * 取最近若干条记录，<b>最新在前</b>。
     *
     * @param n 期望条数；非正数返回空列表，超出现有条数时按现有条数返回
     * @return 新建的可变列表（最新在前），不与内部缓冲共享存储
     */
    public static List<AuditEntry> recent(int n) {
        List<AuditEntry> out = new ArrayList<>();
        if (n <= 0 || size == 0) return out;

        int count = Math.min(n, size);
        for (int i = 1; i <= count; i++) {
            out.add(BUFFER[(cursor - i + CAPACITY) % CAPACITY]);
        }
        return out;
    }

    /**
     * 清空流水。
     *
     * @return 被清除的记录条数
     */
    public static int clear() {
        int cleared = size;
        java.util.Arrays.fill(BUFFER, null);
        cursor = 0;
        size = 0;
        return cleared;
    }

    /** 当前记录条数，上限 {@link #CAPACITY}。 */
    public static int size() {
        return size;
    }
}
