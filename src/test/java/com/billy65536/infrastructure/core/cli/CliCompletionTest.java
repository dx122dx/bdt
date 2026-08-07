package com.billy65536.infrastructure.core.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CliCompletion 补全建议回归测试。
 *
 * <p>针对崩溃 {@code StringIndexOutOfBoundsException: Range [0, 83) out of bounds for length 82}：
 * 玩家敲空格触发 {@code set-authorized} 后的配置名补全时，某些模组（如 SmartCompletion）会拦截空格
 * 插入，使「解析期输入串」比「应用期文本」长 1 个尾随空白。原实现把补全偏移算到解析期串末尾，
 * 而 {@link Suggestion#apply(String)} 会对其执行 {@code input.substring(0, range.getStart())} 而越界。
 * 修复：尾随空白存在时把当前片段视为空、偏移锚定到空白之前、补全文本前补一个空格。</p>
 *
 * <p>本测试直接驱动生产代码 {@link CliCompletion#hierarchicalProvide} / {@link CliCompletion#positionalProvide}
 * （传入 {@code null} 命令上下文，由测试提供的 source 忽略上下文），并模拟 SmartCompletion 把建议
 * 应用到「缺少尾随空格的文本」上，断言既不抛异常又能产出正确结果。</p>
 */
@DisplayName("CliCompletion 补全建议回归")
class CliCompletionTest {

    /** 模拟 SmartCompletion：把触发补全的尾随空格从应用期文本中移除。 */
    private static String dropTrailingWs(String s) {
        int i = s.length();
        while (i > 0 && Character.isWhitespace(s.charAt(i - 1))) i--;
        return s.substring(0, i);
    }

    private static Suggestions buildHierarchical(String input, int start, CliCompletion.Builder b) {
        SuggestionsBuilder sb = new SuggestionsBuilder(input, start);
        return CliCompletion.hierarchicalProvide(sb, null, b).join();
    }

    private static Suggestions buildPositional(String input, int start, CliCompletion.Builder b) {
        SuggestionsBuilder sb = new SuggestionsBuilder(input, start);
        return CliCompletion.positionalProvide(sb, null, b).join();
    }

    @Nested
    @DisplayName("set-authorized 触发补全崩溃（SmartCompletion 移除尾随空格）")
    class SetAuthorizedCrash {

        @Test
        @DisplayName("末尾空格：建议可安全应用于缺少尾随空格的文本，且正确追加带前导空格的条目")
        void trailingSpaceSafeAgainstShortText() {
            String input = "/inf dbg action run inf-dbg:security.config-locker.set-authorized ";
            int start = "/inf dbg action run ".length(); // args 节点起点（剩余部分含尾随空格）
            CliCompletion.Builder b = CliCompletion.builder()
                    .separators(".:/")
                    .multiple(true)
                    .keySource(ctx -> List.of(
                            "infrastructure:config", "chunkscanner:config",
                            "inf-dbg:security.config-locker.set-authorized"));

            Suggestions s = buildHierarchical(input, start, b);
            String applyText = dropTrailingWs(input); // SmartCompletion 实际传入 apply 的文本

            for (Suggestion sug : s.getList()) {
                String applied = assertDoesNotThrow(() -> sug.apply(applyText),
                        "建议应用于缺尾随空格的文本时不应越界");
                // 正确不变量：应用期文本 + 补全文本（带前导空格）
                assertEquals(applyText + sug.getText(), applied, "补全结果应为应用期文本拼接补全文本");
                assertTrue(sug.getText().startsWith(" "), "追加的新条目补全文本应带前导空格");
            }
        }

        @Test
        @DisplayName("无尾随空格：正常补全当前片段不崩溃")
        void noTrailingSpaceIsFine() {
            String input = "/inf dbg action run inf-dbg:security.config-locker.";
            int start = "/inf dbg action run ".length();
            CliCompletion.Builder b = CliCompletion.builder()
                    .separators(".:/")
                    .multiple(true)
                    .keySource(ctx -> List.of("inf-dbg:security.config-locker.set-authorized"));

            Suggestions s = buildHierarchical(input, start, b);
            for (Suggestion sug : s.getList()) {
                assertDoesNotThrow(() -> sug.apply(input));
            }
        }

        @Test
        @DisplayName("assignment + 尾随空格：仍以 '=' 前的键为准补全取值，不崩溃")
        void assignmentTrailingSpaceIsSafe() {
            String input = "/inf config set infrastructure:config/foo=";
            int start = "/inf config set ".length();
            CliCompletion.Builder b = CliCompletion.builder()
                    .separators(".:/")
                    .multiple(true)
                    .assignment(true)
                    .keySource(ctx -> List.of("infrastructure:config/foo=bar"))
                    .valueProvider((ctx, key) -> List.of("true", "false"));

            Suggestions s = buildHierarchical(input, start, b);
            String applyText = dropTrailingWs(input);
            for (Suggestion sug : s.getList()) {
                assertDoesNotThrow(() -> sug.apply(applyText), "assignment 取值补全应用于短文本不应越界");
            }
        }
    }

    @Nested
    @DisplayName("层级键补全")
    class Hierarchical {

        @Test
        @DisplayName("多条路径保留前缀，补全第二条不破坏第一条，且带前导空格")
        void multiplePaths() {
            String input = "/inf dbg action run x a:b.c ";
            int start = "/inf dbg action run x ".length();
            CliCompletion.Builder b = CliCompletion.builder()
                    .separators(".:/")
                    .multiple(true)
                    .keySource(ctx -> List.of("a:b.c.d", "a:b.c.e", "d:e"));

            Suggestions s = buildHierarchical(input, start, b);
            String applyText = dropTrailingWs(input);
            for (Suggestion sug : s.getList()) {
                String applied = assertDoesNotThrow(() -> sug.apply(applyText));
                assertEquals(applyText + sug.getText(), applied, "补全结果应为应用期文本拼接补全文本");
                assertTrue(sug.getText().startsWith(" "), "追加的新条目补全文本应带前导空格");
            }
            // 根字典树有 2 个分支（a、d），应给出 2 条候选
            assertEquals(2, s.getList().size());
        }

        @Test
        @DisplayName("末尾分隔符：逐层钻取偏移合法")
        void trailingSeparatorIsSafe() {
            String input = "/inf config set infrastructure:config/";
            int start = "/inf config set ".length();
            CliCompletion.Builder b = CliCompletion.builder()
                    .separators(".:/")
                    .keySource(ctx -> List.of("infrastructure:config/someOption"));

            Suggestions s = buildHierarchical(input, start, b);
            String applyText = dropTrailingWs(input);
            for (Suggestion sug : s.getList()) {
                assertDoesNotThrow(() -> sug.apply(applyText));
            }
        }
    }

    @Nested
    @DisplayName("位置模式")
    class Positional {

        @Test
        @DisplayName("末尾空格追加新参数且不崩溃")
        void trailingSpaceAppends() {
            String input = "/mycmd first ";
            int start = "/mycmd ".length();
            CliCompletion.Builder b = CliCompletion.builder()
                    .multiple(true)
                    .positional((ctx, completed) -> List.of("alpha", "beta"));

            Suggestions s = buildPositional(input, start, b);
            String applyText = dropTrailingWs(input);
            for (Suggestion sug : s.getList()) {
                String applied = assertDoesNotThrow(() -> sug.apply(applyText));
                assertEquals(applyText + sug.getText(), applied);
                assertTrue(sug.getText().startsWith(" "));
            }
        }
    }
}
