package com.billy65536.infrastructure.security;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.billy65536.infrastructure.security.core.audit.AuditEntry;
import com.billy65536.infrastructure.security.core.audit.SecurityAuditLog;
import com.billy65536.infrastructure.security.core.internal.Origin;

import net.minecraft.util.Identifier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 审计流水单元测试。
 *
 * <p><b>状态隔离</b>：{@link SecurityAuditLog} 是静态环形缓冲，用例间会互相污染，
 * 每个用例前后都显式清空。</p>
 */
@DisplayName("SecurityAuditLog")
class SecurityAuditLogTest {

    private static final Identifier POLICY_A = new Identifier("test", "policy-a");
    private static final Identifier POLICY_B = new Identifier("test", "policy-b");
    private static final Identifier EXECUTOR = new Identifier("test", "executor");

    private static final String PATH_1 = "mod:config/a.b";
    private static final String PATH_2 = "mod:config/c.d";

    @BeforeEach
    @AfterEach
    void reset() {
        SecurityAuditLog.clear();
    }

    private static boolean record(String path, Identifier policy, AuditEntry.Channel channel, String value) {
        return SecurityAuditLog.record(path, Origin.of(policy), EXECUTOR, channel, value);
    }

    @Test
    @DisplayName("首次记录返回 true 并落盘完整现场")
    void firstRecord_shouldBeFreshAndComplete() {
        assertTrue(record(PATH_1, POLICY_A, AuditEntry.Channel.SET, "true"));

        assertEquals(1, SecurityAuditLog.size());
        AuditEntry e = SecurityAuditLog.recent(1).get(0);
        assertEquals(PATH_1, e.fullPath());
        assertEquals(POLICY_A, e.policyId());
        assertEquals(EXECUTOR, e.executorId());
        assertEquals(AuditEntry.Channel.SET, e.channel());
        assertEquals("true", e.attemptedValue());
        assertEquals(1, e.hitCount());
    }

    @Test
    @DisplayName("同路径+同来源+同渠道折叠计数，返回 false 供调用方降噪")
    void repeated_shouldFoldAndReportNotFresh() {
        record(PATH_1, POLICY_A, AuditEntry.Channel.SET, "1");
        assertFalse(record(PATH_1, POLICY_A, AuditEntry.Channel.SET, "2"));
        assertFalse(record(PATH_1, POLICY_A, AuditEntry.Channel.SET, "3"));

        assertEquals(1, SecurityAuditLog.size(), "重复命中必须折叠，不得撑爆流水");
        assertEquals(3, SecurityAuditLog.recent(1).get(0).hitCount());
    }

    @Test
    @DisplayName("折叠不比对尝试值：滑块连续拖动才能真正降噪")
    void fold_shouldIgnoreAttemptedValue() {
        record(PATH_1, POLICY_A, AuditEntry.Channel.SET, "1");
        record(PATH_1, POLICY_A, AuditEntry.Channel.SET, "999");

        assertEquals(1, SecurityAuditLog.size());
    }

    @Test
    @DisplayName("路径不同不折叠")
    void differentPath_shouldNotFold() {
        record(PATH_1, POLICY_A, AuditEntry.Channel.SET, "1");
        assertTrue(record(PATH_2, POLICY_A, AuditEntry.Channel.SET, "1"));

        assertEquals(2, SecurityAuditLog.size());
    }

    @Test
    @DisplayName("渠道不同不折叠")
    void differentChannel_shouldNotFold() {
        record(PATH_1, POLICY_A, AuditEntry.Channel.SET, "1");
        assertTrue(record(PATH_1, POLICY_A, AuditEntry.Channel.RESET, null));

        assertEquals(2, SecurityAuditLog.size());
    }

    @Test
    @DisplayName("来源不同不折叠")
    void differentOrigin_shouldNotFold() {
        record(PATH_1, POLICY_A, AuditEntry.Channel.SET, "1");
        assertTrue(record(PATH_1, POLICY_B, AuditEntry.Channel.SET, "1"));

        assertEquals(2, SecurityAuditLog.size());
    }

    @Test
    @DisplayName("非相邻的同路径记录不折叠，时间线保持完整")
    void nonAdjacentSamePath_shouldNotFold() {
        record(PATH_1, POLICY_A, AuditEntry.Channel.SET, "1");
        record(PATH_2, POLICY_A, AuditEntry.Channel.SET, "1");
        assertTrue(record(PATH_1, POLICY_A, AuditEntry.Channel.SET, "1"),
                "只与最近一条比对，中间隔了别的记录就应独立成条");

        assertEquals(3, SecurityAuditLog.size());
    }

    @Test
    @DisplayName("recent 最新在前")
    void recent_shouldBeNewestFirst() {
        record(PATH_1, POLICY_A, AuditEntry.Channel.SET, "1");
        record(PATH_2, POLICY_A, AuditEntry.Channel.SET, "2");

        List<AuditEntry> recent = SecurityAuditLog.recent(2);
        assertEquals(PATH_2, recent.get(0).fullPath());
        assertEquals(PATH_1, recent.get(1).fullPath());
    }

    @Test
    @DisplayName("recent 参数非法或超量时安全降级")
    void recent_shouldClampArguments() {
        record(PATH_1, POLICY_A, AuditEntry.Channel.SET, "1");

        assertTrue(SecurityAuditLog.recent(0).isEmpty());
        assertTrue(SecurityAuditLog.recent(-5).isEmpty());
        assertEquals(1, SecurityAuditLog.recent(1000).size());
    }

    @Test
    @DisplayName("环形缓冲写满后淘汰最旧记录，容量恒定")
    void ringBuffer_shouldEvictOldest() {
        int total = SecurityAuditLog.CAPACITY + 10;
        for (int i = 0; i < total; i++) {
            record("mod:config/p" + i, POLICY_A, AuditEntry.Channel.SET, "v");
        }

        assertEquals(SecurityAuditLog.CAPACITY, SecurityAuditLog.size(),
                "流水必须有界，长时间运行不得无限增长");

        List<AuditEntry> all = SecurityAuditLog.recent(SecurityAuditLog.CAPACITY);
        assertEquals("mod:config/p" + (total - 1), all.get(0).fullPath(), "最新记录必须保留");
        assertEquals("mod:config/p" + (total - SecurityAuditLog.CAPACITY),
                all.get(all.size() - 1).fullPath(), "最旧记录应被覆盖");
    }

    @Test
    @DisplayName("clear 返回清除条数并复位")
    void clear_shouldResetAndReportCount() {
        record(PATH_1, POLICY_A, AuditEntry.Channel.SET, "1");
        record(PATH_2, POLICY_A, AuditEntry.Channel.SET, "2");

        assertEquals(2, SecurityAuditLog.clear());
        assertEquals(0, SecurityAuditLog.size());
        assertTrue(SecurityAuditLog.recent(10).isEmpty());
        assertEquals(0, SecurityAuditLog.clear());
    }

    @Test
    @DisplayName("clear 后环形缓冲从头写入，无残留串扰")
    void clear_shouldAllowCleanReuse() {
        record(PATH_1, POLICY_A, AuditEntry.Channel.SET, "1");
        SecurityAuditLog.clear();

        assertTrue(record(PATH_1, POLICY_A, AuditEntry.Channel.SET, "1"),
                "清空后同样的记录应被视为全新，而非折叠进已清除的旧条目");
        assertEquals(1, SecurityAuditLog.recent(1).get(0).hitCount());
    }

    @Test
    @DisplayName("来源为 null 时展示降级不抛异常")
    void nullOrigin_shouldDegradeGracefully() {
        assertTrue(SecurityAuditLog.record(PATH_1, null, EXECUTOR, AuditEntry.Channel.SET, "1"));

        AuditEntry e = SecurityAuditLog.recent(1).get(0);
        assertNull(e.origin());
        assertNull(e.policyId());

        assertFalse(SecurityAuditLog.record(PATH_1, null, EXECUTOR, AuditEntry.Channel.SET, "2"),
                "两条都无法归因时仍应折叠");
        assertFalse(SecurityAuditLog.record(PATH_1, Origin.UNKNOWN, EXECUTOR,
                AuditEntry.Channel.SET, "3"), "null 与 UNKNOWN 都是「无 primary」，视为同源");
    }
}
