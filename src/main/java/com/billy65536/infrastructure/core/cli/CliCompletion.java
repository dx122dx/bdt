package com.billy65536.infrastructure.core.cli;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
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
            return (ctx, builder) -> positionalProvide(builder, ctx, this);
        }

        // ----- 层级模式 -----
        private SuggestionProvider<FabricClientCommandSource> hierarchicalProvider() {
            return (ctx, builder) -> hierarchicalProvide(builder, ctx, this);
        }

    }

    public static Builder builder() { return new Builder(); }

    // ==================== 补全核心逻辑（包级可见，供单元测试直接驱动） ====================

    /**
     * 位置模式核心逻辑。
     *
     * <p>关键修正：{@code remaining} 末尾的空白（通常是玩家敲空格触发补全时遗留）在
     * 建议被应用的时刻可能已被某些模组（如 SmartCompletion 会拦截空格插入）从文本中移除，
     * 导致「解析期输入串」比「应用期文本」长 1 个尾随空白。若把补全偏移算到 {@code remaining}
     * 末尾（即解析期输入串末尾），{@link Suggestion#apply(String)} 会对其执行
     * {@code input.substring(0, range.getStart())} 而越界崩溃。因此：末尾空白存在时，将当前
     * 片段视为空、偏移锚定到末尾空白之前，并在补全文本前补一个空格——这样无论应用期文本
     * 是否含该尾随空白都能正确追加且不越界。</p>
     */
    static CompletableFuture<Suggestions> positionalProvide(
            SuggestionsBuilder builder, CommandContext<FabricClientCommandSource> ctx, Builder b) {
        String remaining = builder.getRemaining();
        int trailingWs = countTrailingWs(remaining);
        int effLen = remaining.length() - trailingWs;
        String content = remaining.substring(0, effLen);
        boolean trailingWsPresent = b.multiple && trailingWs > 0;

        String curToken;
        int curTokenStart;
        if (trailingWsPresent) {
            curToken = "";
            curTokenStart = effLen;
        } else if (b.multiple) {
            int ws = lastWhitespace(content);
            if (ws < 0) { curToken = content; curTokenStart = 0; }
            else { curToken = content.substring(ws + 1); curTokenStart = ws + 1; }
        } else {
            curToken = content; curTokenStart = 0;
        }

        String frag = curToken;
        String[] completed = (b.multiple && curTokenStart > 0)
                ? ArgTokenizer.tokenize(content.substring(0, curTokenStart))
                : new String[0];
        String lower = frag.toLowerCase(Locale.ROOT);
        int offset = builder.getStart() + curTokenStart;
        SuggestionsBuilder out = builder.createOffset(offsetOf(builder, offset));
        List<String> candidates = b.nextProvider.apply(ctx, completed);
        if (candidates != null) {
            for (String c : candidates) {
                if (c != null && c.toLowerCase(Locale.ROOT).startsWith(lower)) {
                    out.suggest(trailingWsPresent ? " " + c : c);
                }
            }
        }
        return out.buildFuture();
    }

    /**
     * 层级模式核心逻辑。与 {@link #positionalProvide} 同样的尾随空白修正。
     */
    static CompletableFuture<Suggestions> hierarchicalProvide(
            SuggestionsBuilder builder, CommandContext<FabricClientCommandSource> ctx, Builder b) {
        Collection<String> keys = b.keySource.apply(ctx);
        if (keys == null || keys.isEmpty()) return builder.buildFuture();
        Node root = buildTrie(keys, b.separators);

        String remaining = builder.getRemaining();
        int trailingWs = countTrailingWs(remaining);
        int effLen = remaining.length() - trailingWs;
        String content = remaining.substring(0, effLen);

        // 当前片段 = content 中最后一个空白分隔片段（尾随空白已去除）
        String curToken;
        int curTokenStart;
        if (b.multiple) {
            int ws = lastWhitespace(content);
            if (ws < 0) { curToken = content; curTokenStart = 0; }
            else { curToken = content.substring(ws + 1); curTokenStart = ws + 1; }
        } else {
            curToken = content; curTokenStart = 0;
        }

        // assignment=true 且当前片段含 '='：补全取值（即使后面跟有触发补全的空格也以 '=' 前的键为准）
        if (b.assignment) {
            int eq = curToken.indexOf('=');
            if (eq >= 0) {
                String key = curToken.substring(0, eq);
                String valFrag = curToken.substring(eq + 1);
                int offset = builder.getStart() + curTokenStart + eq + 1;
                SuggestionsBuilder out = builder.createOffset(offsetOf(builder, offset));
                String lower = valFrag.toLowerCase(Locale.ROOT);
                if (b.valueProvider != null) {
                    List<String> vals = b.valueProvider.apply(ctx, key);
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

        // 键补全（向下钻取一层）
        boolean trailingWsPresent = b.multiple && trailingWs > 0;
        String token;
        int tokenStart;
        if (trailingWsPresent) {
            token = "";
            tokenStart = effLen;       // 锚定到末尾空白之前，作为「追加新条目」的起点
        } else {
            token = curToken;
            tokenStart = curTokenStart;
        }

        int lastSep = lastSep(token, b.separators);
        String prefix = (lastSep < 0) ? "" : token.substring(0, lastSep + 1);
        String frag = (lastSep < 0) ? token : token.substring(lastSep + 1);
        Node node = navigate(root, segments(prefix, b.separators));
        if (node == null) return builder.buildFuture();

        int offset = builder.getStart() + tokenStart;
        SuggestionsBuilder out = builder.createOffset(offsetOf(builder, offset));
        String lower = frag.toLowerCase(Locale.ROOT);
        boolean lead = trailingWsPresent;
        for (Map.Entry<String, Node> e : node.children.entrySet()) {
            String seg = e.getKey();
            Node child = e.getValue();
            if (!seg.toLowerCase(Locale.ROOT).startsWith(lower)) continue;
            if (child.leaf) {
                if (b.assignment) {
                    String fullKey = prefix + seg;
                    out.suggest(lead ? " " + seg + "=" : seg + "=");
                    if (b.valueProvider != null) {
                        List<String> vals = b.valueProvider.apply(ctx, fullKey);
                        if (vals != null) {
                            for (String v : vals) {
                                if (v != null) out.suggest(lead ? " " + seg + "=" + v : seg + "=" + v);
                            }
                        }
                    }
                } else {
                    out.suggest(lead ? " " + seg : seg);
                }
            } else {
                // 内部节点：补全本层并补充分隔符以便继续钻取
                out.suggest(lead ? " " + seg + (child.outSep != 0 ? child.outSep : "")
                                : seg + (child.outSep != 0 ? child.outSep : ""));
            }
        }
        return out.buildFuture();
    }

    // ==================== 工具方法 ====================

    private static int lastWhitespace(String s) {
        for (int i = s.length() - 1; i >= 0; i--) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }

    /** 统计字符串末尾连续空白字符的个数（用于识别「触发补全的尾随空格」）。 */
    private static int countTrailingWs(String s) {
        int c = 0;
        for (int i = s.length() - 1; i >= 0 && Character.isWhitespace(s.charAt(i)); i--) c++;
        return c;
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
