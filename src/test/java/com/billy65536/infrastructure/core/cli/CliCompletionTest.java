package com.billy65536.infrastructure.core.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CliCompletion 建议偏移的回归测试。
 *
 * <p>针对崩溃 {@code StringIndexOutOfBoundsException: Range [0, 81) out of bounds for length 80}：
 * 当输入以空白、分隔符或 {@code '='} 结尾时，原实现算出的建议起始偏移可能越过输入串末尾，
 * 使 {@code Suggestion.apply} 在 {@code input.substring(0, range.getStart())} 处抛异常。</p>
 *
 * <p>本测试不依赖 Minecraft 运行时：{@code CliCompletion} 产出的 {@code SuggestionProvider} 需要
 * {@code FabricClientCommandSource}，无法在单元测试中构造，因此这里直接验证被修复的不变量本身
 * ——「无论期望偏移多离谱，产出的建议区间都必须落在输入串范围内，且 apply 不抛异常」，
 * 复现方式与生产代码一致：对 {@link SuggestionsBuilder#createOffset(int)} 传入经钳制的偏移。</p>
 */
@DisplayName("CliCompletion 建议偏移越界回归")
class CliCompletionTest {

    /** 与生产实现 {@code CliCompletion.offsetOf} 等价的钳制逻辑。 */
    private static int clamp(SuggestionsBuilder builder, int desired) {
        return Math.max(builder.getStart(), Math.min(desired, builder.getInput().length()));
    }

    private static SuggestionsBuilder builderOf(String input, int start) {
        return new SuggestionsBuilder(input, start);
    }

    @Nested
    @DisplayName("越界偏移会导致崩溃（问题复现）")
    class Reproduction {

        @Test
        @DisplayName("偏移超出输入长度时 createOffset 自身即抛异常")
        void createOffsetRejectsOutOfBoundsOffset() {
            String input = "/inf dbg action run inf-dbg:security.config-locker.set-authorized ";
            SuggestionsBuilder builder = builderOf(input, 20);

            assertThrows(StringIndexOutOfBoundsException.class,
                    () -> builder.createOffset(input.length() + 1),
                    "偏移超出输入长度时应当抛出越界异常，这正是线上崩溃的根源");
        }
    }

    @Nested
    @DisplayName("钳制后偏移始终合法")
    class Clamped {

        @Test
        @DisplayName("末尾空格：remaining 长度叠加后不越界，且能给出全部候选")
        void trailingSpaceDoesNotOverflow() {
            String input = "/inf dbg action run inf-dbg:security.config-locker.set-authorized ";
            int start = "/inf dbg action run inf-dbg:security.config-locker.set-authorized ".length();
            SuggestionsBuilder builder = builderOf(input, start);

            // multiple 模式下 remaining 以空白结尾时，token 为空、tokenStartInRemaining == remaining.length()
            String remaining = builder.getRemaining();
            int desired = builder.getStart() + remaining.length();

            SuggestionsBuilder out = assertDoesNotThrow(() -> builder.createOffset(clamp(builder, desired)));
            out.suggest("infrastructure:config");
            out.suggest("chunkscanner:config");

            Suggestions suggestions = out.build();
            assertEquals(2, suggestions.getList().size(), "空片段应给出全部候选");
            for (Suggestion s : suggestions.getList()) {
                assertDoesNotThrow(() -> s.apply(input), "apply 不应越界");
            }
        }

        @Test
        @DisplayName("末尾分隔符：逐层钻取的偏移合法")
        void trailingSeparatorIsSafe() {
            String input = "/inf config set infrastructure:config/";
            SuggestionsBuilder builder = builderOf(input, "/inf config set ".length());

            String token = builder.getRemaining();
            String frag = "";
            int desired = builder.getStart() + (token.length() - frag.length());

            SuggestionsBuilder out = assertDoesNotThrow(() -> builder.createOffset(clamp(builder, desired)));
            out.suggest("someOption");
            for (Suggestion s : out.build().getList()) {
                assertDoesNotThrow(() -> s.apply(input));
                assertTrue(s.getRange().getStart() <= input.length());
            }
        }

        @Test
        @DisplayName("末尾等号：assignment 取值分支偏移合法")
        void trailingEqualsIsSafe() {
            String input = "/inf config set infrastructure:config/foo=";
            SuggestionsBuilder builder = builderOf(input, "/inf config set ".length());

            String token = builder.getRemaining();
            int eq = token.indexOf('=');
            int desired = builder.getStart() + eq + 1;

            SuggestionsBuilder out = assertDoesNotThrow(() -> builder.createOffset(clamp(builder, desired)));
            out.suggest("true");
            out.suggest("false");
            for (Suggestion s : out.build().getList()) {
                assertDoesNotThrow(() -> s.apply(input));
            }
        }

        @Test
        @DisplayName("多条路径：前面已输入的条目不被覆盖")
        void multiplePathsPreservePrefix() {
            String input = "/inf dbg action run x a:b.c ";
            int start = "/inf dbg action run x ".length();
            SuggestionsBuilder builder = builderOf(input, start);

            String remaining = builder.getRemaining(); // "a:b.c "
            int desired = builder.getStart() + remaining.length();

            SuggestionsBuilder out = builder.createOffset(clamp(builder, desired));
            out.suggest("d:e");

            List<Suggestion> list = out.build().getList();
            assertEquals(1, list.size());
            String applied = list.get(0).apply(input);
            assertEquals("/inf dbg action run x a:b.c d:e", applied,
                    "补全第二条路径不应破坏第一条");
        }

        @Test
        @DisplayName("钳制不会把偏移压到参数起点之前")
        void clampRespectsLowerBound() {
            String input = "/inf config set abc";
            int start = "/inf config set ".length();
            SuggestionsBuilder builder = builderOf(input, start);

            assertEquals(start, clamp(builder, start - 5),
                    "下界必须是 builder.getStart()，否则会覆盖已解析的命令片段");
        }

        @Test
        @DisplayName("任意离谱偏移经钳制后 apply 均安全")
        void arbitraryOffsetsAreSafe() {
            String input = "/inf config set infrastructure:config/foo";
            int start = "/inf config set ".length();

            for (int desired = -20; desired <= input.length() + 20; desired++) {
                SuggestionsBuilder builder = builderOf(input, start);
                final int d = desired;
                SuggestionsBuilder out = assertDoesNotThrow(
                        () -> builder.createOffset(clamp(builder, d)),
                        "钳制后 createOffset 不应抛异常，desired=" + d);
                out.suggest("bar");
                for (Suggestion s : out.build().getList()) {
                    assertDoesNotThrow(() -> s.apply(input), "apply 不应抛异常，desired=" + d);
                    assertTrue(s.getRange().getStart() >= 0
                            && s.getRange().getStart() <= input.length());
                }
            }
        }
    }
}
