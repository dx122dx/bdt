package com.billy65536.infrastructure.security;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.billy65536.infrastructure.security.core.internal.AttributedValue;
import com.billy65536.infrastructure.security.core.internal.Origin;

import net.minecraft.util.Identifier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 来源载体（{@link Origin} / {@link AttributedValue}）单元测试。
 *
 * <p>这两个类型是审计溯源链路的地基：值怎么合并，来源就怎么跟随。
 * 一旦合并语义与配置层的「后者覆盖前者」出现分叉，审计报出的来源就会张冠李戴。</p>
 */
@DisplayName("来源载体")
class AttributedValueTest {

    private static final Identifier A = new Identifier("test", "a");
    private static final Identifier B = new Identifier("test", "b");
    private static final Identifier C = new Identifier("test", "c");

    @Nested
    @DisplayName("Origin")
    class OriginBehavior {

        @Test
        @DisplayName("merge 的 primary 取后者，contributors 取有序并集")
        void merge_shouldTakeLatestPrimaryAndUnionContributors() {
            Origin merged = Origin.of(A).merge(Origin.of(B));

            assertEquals(B, merged.getPrimary(), "primary 必须与值覆盖语义一致，取后者");
            assertEquals(List.of(A, B), new ArrayList<>(merged.getContributors()),
                    "contributors 必须保持贡献顺序");
        }

        @Test
        @DisplayName("三方合并逐层累积贡献者")
        void merge_threeWay_shouldAccumulate() {
            Origin merged = Origin.of(A).merge(Origin.of(B)).merge(Origin.of(C));

            assertEquals(C, merged.getPrimary());
            assertEquals(List.of(A, B, C), new ArrayList<>(merged.getContributors()));
        }

        @Test
        @DisplayName("contributors 超过上界后停止累积，primary 不受影响")
        void merge_shouldCapContributors() {
            Origin acc = Origin.of(new Identifier("test", "p0"));
            Identifier last = null;
            for (int i = 1; i <= Origin.MAX_CONTRIBUTORS + 3; i++) {
                last = new Identifier("test", "p" + i);
                acc = acc.merge(Origin.of(last));
            }

            assertEquals(Origin.MAX_CONTRIBUTORS, acc.getContributors().size(),
                    "贡献者集合必须有界，防病态场景下无限增长");
            assertEquals(last, acc.getPrimary(), "截断贡献者不得影响最终生效来源");
        }

        @Test
        @DisplayName("结果与自身等价时 merge 返回 this，避免无谓分配")
        void merge_sameOrigin_shouldReturnSelf() {
            Origin a = Origin.of(A);
            assertSame(a, a.merge(Origin.of(A)));
        }

        @Test
        @DisplayName("与未知来源合并保持原样；未知来源合并具名来源则被取代")
        void merge_withUnknown_shouldDegradeGracefully() {
            Origin a = Origin.of(A);
            assertSame(a, a.merge(Origin.UNKNOWN));
            assertSame(a, a.merge(null));
            assertEquals(A, Origin.UNKNOWN.merge(a).getPrimary());
        }

        @Test
        @DisplayName("null 来源归一为 UNKNOWN")
        void of_null_shouldBeUnknown() {
            assertSame(Origin.UNKNOWN, Origin.of(null));
            assertTrue(Origin.UNKNOWN.isUnknown());
            assertNull(Origin.UNKNOWN.getPrimary());
            assertTrue(Origin.UNKNOWN.getContributors().isEmpty());
        }

        @Test
        @DisplayName("contributors 视图不可变")
        void contributors_shouldBeImmutable() {
            Origin merged = Origin.of(A).merge(Origin.of(B));
            assertThrows(UnsupportedOperationException.class,
                    () -> merged.getContributors().add(C));
        }

        @Test
        @DisplayName("相同来源的 Origin 相等")
        void equality() {
            assertEquals(Origin.of(A), Origin.of(A));
            assertEquals(Origin.of(A).hashCode(), Origin.of(A).hashCode());
            assertNotEquals(Origin.of(A), Origin.of(B));
        }
    }

    @Nested
    @DisplayName("AttributedValue")
    class ValueBehavior {

        @Test
        @DisplayName("merge 的值取后者，来源走 Origin.merge")
        void merge_shouldTakeLatestValueAndMergeOrigin() {
            AttributedValue<String> merged = AttributedValue.of("x", Origin.of(A))
                    .merge(AttributedValue.of("y", Origin.of(B)));

            assertEquals("y", merged.getValue());
            assertEquals(B, merged.getOrigin().getPrimary());
            assertEquals(List.of(A, B), new ArrayList<>(merged.getOrigin().getContributors()));
        }

        @Test
        @DisplayName("merge null 时返回自身")
        void merge_null_shouldReturnSelf() {
            AttributedValue<String> v = AttributedValue.of("x", Origin.of(A));
            assertSame(v, v.merge(null));
        }

        @Test
        @DisplayName("包装 null 值不丢语义，且 Origin 永不为 null")
        void nullValue_shouldSurviveWrapping() {
            AttributedValue<String> v = AttributedValue.of(null);

            assertNull(v.getValue(), "null 表示「仅锁定无强制值」，必须原样保留");
            assertNotNull(v.getOrigin());
            assertTrue(v.getOrigin().isUnknown());
        }

        @Test
        @DisplayName("空串与 null 是两态，合并后依旧区分")
        void emptyStringAndNull_shouldStayDistinct() {
            assertEquals("", AttributedValue.of("", Origin.of(A)).getValue());
            assertNull(AttributedValue.of("x", Origin.of(A))
                    .merge(AttributedValue.of(null, Origin.of(B))).getValue());
        }

        @Test
        @DisplayName("withOrigin 只替换来源，值不变")
        void withOrigin_shouldReplaceOriginOnly() {
            AttributedValue<String> stamped = AttributedValue.of("x").withOrigin(A);

            assertEquals("x", stamped.getValue());
            assertEquals(A, stamped.getOrigin().getPrimary());
        }

        @Test
        @DisplayName("withOrigin 无变化时返回自身")
        void withOrigin_sameOrigin_shouldReturnSelf() {
            AttributedValue<String> v = AttributedValue.of("x", Origin.of(A));
            assertSame(v, v.withOrigin(A));
        }

        @Test
        @DisplayName("of(value, null) 降级为未知来源而非抛异常")
        void of_nullOrigin_shouldDegrade() {
            assertSame(Origin.UNKNOWN, AttributedValue.of("x", null).getOrigin());
        }

        @Test
        @DisplayName("实现类不对外暴露：只能经静态工厂获得，且无公开构造器")
        void implementation_shouldNotBePubliclyConstructible() {
            AttributedValue<String> v = AttributedValue.of("x");
            Class<?> impl = v.getClass();

            assertEquals("SimpleAttributedValue", impl.getSimpleName());
            assertEquals(0, impl.getConstructors().length,
                    "唯一实现必须包私有，外部无法 new，保证实现可自由演进");
        }
    }
}
