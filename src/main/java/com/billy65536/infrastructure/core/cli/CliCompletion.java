package com.billy65536.infrastructure.core.cli;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

/**
 * 层级化命令补全工具。
 *
 * <p>把若干候选键（可含 {@code .} 或 {@code :} 分隔的层级）建成字典树，每次补全只向下钻取
 * <strong>一层</strong>节点（而非一次补全整条深层路径）。配合 Brigadier 的
 * {@link SuggestionsBuilder#createOffset(int)}，只替换正在输入的片段，绝不波及已输入的其它参数。
 * 所有偏移统一经 {@code offsetOf} 钳制到合法区间，保证产出的建议区间始终有效。</p>
 *
 * <h2>两种模式</h2>
 * <ul>
 *   <li><b>层级模式（hierarchical，默认）</b>：候选项是一组固定层级键（如配置路径）。
 *       补全时在字典树中按已输入前缀向下钻取一层。
 *       <ul>
 *         <li>{@code assignment=true}：键补全到叶子后追加 {@code =} 候选，
 *             并通过 {@code valueProvider} 取得该键的合法取值列表一并给出；</li>
 *         <li>{@code multiple=true}：以空白分隔的多个条目可循环补全，每条目独立钻取。</li>
 *       </ul>
 *   </li>
 *   <li><b>位置模式（positional）</b>：候选由 {@code nextProvider} 依据「已完成的参数数组」
 *       动态给出（如调试动作按参数位返回候选）。{@code multiple=true} 时同样按空白循环。</li>
 * </ul>
 */
public final class CliCompletion {

    private CliCompletion() {}

    /** 候选键默认分隔符集合：点号与冒号。 */
    public static final String DEFAULT_SEPARATORS = ".:";

    // ==================== 字典树 ====================

    /** 字典树节点：children 为子段 → 子节点；leaf 表示对应完整键是终点；outSep 为通往其子节点的分隔符（叶子为 0）。 */
    private static final class Node {
        final Map<String, Node> children = new LinkedHashMap<>();
        boolean leaf = false;
        char outSep = 0;
    }

    private static Node buildTrie(Collection<String> keys, String seps) {
        Node root = new Node();
        for (String key : keys) {
            if (key == null || key.isEmpty()) continue;
            Node cur = root;
            int i = 0;
            while (i < key.length()) {
                int next = nextSep(key, i, seps);
                String seg = (next < 0) ? key.substring(i) : key.substring(i, next);
                if (!seg.isEmpty()) {
                    Node child = cur.children.computeIfAbsent(seg, k -> new Node());
                    child.outSep = (next < 0) ? 0 : key.charAt(next);
                    cur = child;
                }
                if (next < 0) break;
                i = next + 1;
            }
            cur.leaf = true;
        }
        return root;
    }

    private static int nextSep(String s, int from, String seps) {
        for (int i = from; i < s.length(); i++) {
            if (seps.indexOf(s.charAt(i)) >= 0) return i;
        }
        return -1;
    }

    /** 把前缀（可能含末尾分隔符）切分为字典树段序列。 */
    private static List<String> segments(String prefix, String seps) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < prefix.length()) {
            int next = nextSep(prefix, i, seps);
            String seg = (next < 0) ? prefix.substring(i) : prefix.substring(i, next);
            if (!seg.isEmpty()) out.add(seg);
            if (next < 0) break;
            i = next + 1;
        }
        return out;
    }

    private static Node navigate(Node root, List<String> segs) {
        Node cur = root;
        for (String seg : segs) {
            cur = cur.children.get(seg);
            if (cur == null) return null;
        }
        return cur;
    }

    // ==================== 构造器 ====================

    /** 补全构造器。通过 {@link #build()} 产出 {@link SuggestionProvider}。 */
    public static final class Builder {
        private String separators = DEFAULT_SEPARATORS;
        private boolean assignment = false;
        private boolean multiple = false;

        private Function<CommandContext<FabricClientCommandSource>, Collection<String>> keySource = null;
        private BiFunction<CommandContext<FabricClientCommandSource>, String, List<String>> valueProvider = null;
        private BiFunction<CommandContext<FabricClientCommandSource>, String[], List<String>> nextProvider = null;

        private Builder() {}

        public Builder separators(String s) { this.separators = (s == null) ? "" : s; return this; }
        public Builder assignment(boolean b) { this.assignment = b; return this; }
        public Builder multiple(boolean b) { this.multiple = b; return this; }

        /** 层级模式：依据命令上下文动态取得候选键集合（可反映运行时变化）。 */
        public Builder keySource(Function<CommandContext<FabricClientCommandSource>, Collection<String>> f) {
            this.keySource = f; return this;
        }

        /** 层级模式 assignment=true 时，依据完整键取得其合法取值候选。 */
        public Builder valueProvider(BiFunction<CommandContext<FabricClientCommandSource>, String, List<String>> f) {
            this.valueProvider = f; return this;
        }

        /** 位置模式：依据已完成的参数片段数组，返回下一个参数的候选。 */
        public Builder positional(BiFunction<CommandContext<FabricClientCommandSource>, String[], List<String>> f) {
            this.nextProvider = f; return this;
        }

        public SuggestionProvider<FabricClientCommandSource> build() {
            if (nextProvider != null) return positionalProvider();
            if (keySource == null) {
                throw new IllegalStateException("CliCompletion.Builder: 需提供 keySource（层级模式）或 positional（位置模式）");
            }
            return hierarchicalProvider();
        }

        // ----- 位置模式 -----
        private SuggestionProvider<FabricClientCommandSource> positionalProvider() {
            return (ctx, builder) -> {
                String remaining = builder.getRemaining();
                String token;
                int tokenStartInRemaining;
                if (multiple) {
                    int ws = lastWhitespace(remaining);
                    if (ws < 0) { token = remaining; tokenStartInRemaining = 0; }
                    else { token = remaining.substring(ws + 1); tokenStartInRemaining = ws + 1; }
                } else {
                    token = remaining;
                    tokenStartInRemaining = 0;
                }
                String frag = token;
                String[] completed = (multiple && tokenStartInRemaining > 0)
                        ? ArgTokenizer.tokenize(remaining.substring(0, tokenStartInRemaining))
                        : new String[0];
                String lower = frag.toLowerCase(Locale.ROOT);
                SuggestionsBuilder out = builder.createOffset(
                        offsetOf(builder, builder.getStart() + tokenStartInRemaining));
                List<String> candidates = nextProvider.apply(ctx, completed);
                if (candidates != null) {
                    for (String c : candidates) {
                        if (c != null && c.toLowerCase(Locale.ROOT).startsWith(lower)) {
                            out.suggest(c);
                        }
                    }
                }
                return out.buildFuture();
            };
        }

        // ----- 层级模式 -----
        private SuggestionProvider<FabricClientCommandSource> hierarchicalProvider() {
            return (ctx, builder) -> {
                Collection<String> keys = keySource.apply(ctx);
                if (keys == null || keys.isEmpty()) return builder.buildFuture();
                Node root = buildTrie(keys, separators);

                String remaining = builder.getRemaining();
                String token;
                int tokenStartInRemaining;
                if (multiple) {
                    int ws = lastWhitespace(remaining);
                    if (ws < 0) { token = remaining; tokenStartInRemaining = 0; }
                    else { token = remaining.substring(ws + 1); tokenStartInRemaining = ws + 1; }
                } else {
                    token = remaining;
                    tokenStartInRemaining = 0;
                }

                // 已含 '='：补全取值
                if (assignment) {
                    int eq = token.indexOf('=');
                    if (eq >= 0) {
                        String key = token.substring(0, eq);
                        String valFrag = token.substring(eq + 1);
                        SuggestionsBuilder out = builder.createOffset(
                                offsetOf(builder, builder.getStart() + tokenStartInRemaining + eq + 1));
                        String lower = valFrag.toLowerCase(Locale.ROOT);
                        if (valueProvider != null) {
                            List<String> vals = valueProvider.apply(ctx, key);
                            if (vals != null) {
                                for (String v : vals) {
                                    if (v != null && v.toLowerCase(Locale.ROOT).startsWith(lower)) {
                                        out.suggest(v);
                                    }
                                }
                            }
                        }
                        return out.buildFuture();
                    }
                }

                // 补全键（向下钻取一层）
                int lastSep = lastSep(token, separators);
                String prefix = (lastSep < 0) ? "" : token.substring(0, lastSep + 1);
                String frag = (lastSep < 0) ? token : token.substring(lastSep + 1);
                Node node = navigate(root, segments(prefix, separators));
                if (node == null) return builder.buildFuture();

                SuggestionsBuilder out = builder.createOffset(offsetOf(builder,
                        builder.getStart() + tokenStartInRemaining + (token.length() - frag.length())));
                String lower = frag.toLowerCase(Locale.ROOT);
                for (Map.Entry<String, Node> e : node.children.entrySet()) {
                    String seg = e.getKey();
                    Node child = e.getValue();
                    if (!seg.toLowerCase(Locale.ROOT).startsWith(lower)) continue;
                    if (child.leaf) {
                        if (assignment) {
                            String fullKey = prefix + seg;
                            out.suggest(seg + "=");
                            if (valueProvider != null) {
                                List<String> vals = valueProvider.apply(ctx, fullKey);
                                if (vals != null) {
                                    for (String v : vals) {
                                        if (v != null) out.suggest(seg + "=" + v);
                                    }
                                }
                            }
                        } else {
                            out.suggest(seg);
                        }
                    } else {
                        // 内部节点：补全本层并补充分隔符以便继续钻取
                        out.suggest(seg + (child.outSep != 0 ? child.outSep : ""));
                    }
                }
                return out.buildFuture();
            };
        }
    }

    public static Builder builder() { return new Builder(); }

    // ==================== 工具方法 ====================

    private static int lastWhitespace(String s) {
        for (int i = s.length() - 1; i >= 0; i--) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }

    /**
     * 把期望的建议起始偏移钳制到 Brigadier 允许的合法区间 {@code [builder.getStart(), input.length()]}。
     *
     * <p><b>上界 {@code input.length()}</b>：{@link SuggestionsBuilder#suggest} 产出的区间恒为
     * {@code StringRange.between(start, input.length())}，而 {@code Suggestion.apply} 会执行
     * {@code input.substring(0, range.getStart())}，{@code start} 超出输入长度即抛
     * {@link StringIndexOutOfBoundsException}；{@link SuggestionsBuilder#createOffset(int)} 内部的
     * {@code input.substring(start)} 同样要求不越界。当输入以空白、分隔符或 {@code '='} 结尾时，
     * 原始偏移计算可能落到输入末尾之后，故必须在调用前钳制。</p>
     *
     * <p><b>下界 {@code builder.getStart()}</b>：偏移不得回退到当前参数起点之前，
     * 否则补全会覆盖前面已解析的命令片段。</p>
     *
     * <p>注意：装有 SmartCompletion 等在建议窗口构造期就调用 {@code Suggestion.apply} 的模组时，
     * 越界不再只是「点选建议才崩」，而是一打开补全窗口即崩，因此该钳制不可省略。</p>
     */
    private static int offsetOf(SuggestionsBuilder builder, int desiredOffset) {
        return Math.max(builder.getStart(), Math.min(desiredOffset, builder.getInput().length()));
    }

    private static int lastSep(String s, String seps) {
        int idx = -1;
        for (int i = 0; i < s.length(); i++) {
            if (seps.indexOf(s.charAt(i)) >= 0) idx = i;
        }
        return idx;
    }
}
