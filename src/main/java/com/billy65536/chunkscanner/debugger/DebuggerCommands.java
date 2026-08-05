package com.billy65536.chunkscanner.debugger;

import java.util.Collection;
import java.util.function.Supplier;

import com.billy65536.chunkscanner.debugger.config.DebuggerConfigLoader;
import com.billy65536.chunkscanner.debugger.config.FeatureConfigScreen;
import com.billy65536.chunkscanner.debugger.core.action.ActionRegistry;
import com.billy65536.chunkscanner.debugger.core.action.ArgTokenizer;
import com.billy65536.chunkscanner.debugger.core.action.IDebugAction;
import com.billy65536.chunkscanner.debugger.core.feature.FeatureRegistry;
import com.billy65536.chunkscanner.debugger.core.feature.IDebugFeature;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

/**
 * {@code /cs dbg} 命令树的构建与执行逻辑。
 *
 * <p>命令由调试模组自身注册。Brigadier 在遇到同名根 literal 时执行子节点合并
 * 而非覆盖，因此 {@code dbg} 分支会自动挂到主模组已建立的 {@code /cs} 树上，
 * 主模组无需任何改动。</p>
 *
 * <p>提供的子命令：</p>
 * <ul>
 *   <li>{@code /cs dbg action run <id> [args...]} —— 执行调试动作</li>
 *   <li>{@code /cs dbg action info <id>} —— 查询调试动作的元信息</li>
 *   <li>{@code /cs dbg feat about <id>} —— 查询调试特性的启用状态</li>
 *   <li>{@code /cs dbg feat enable|disable <id>} —— 启用/禁用调试特性</li>
 *   <li>{@code /cs dbg feat gui} —— 打开特性开关配置界面</li>
 *   <li>{@code /cs dbg list} —— 列出全部已注册项</li>
 * </ul>
 */
public final class DebuggerCommands {

    private DebuggerCommands() {}

    // ==================== 自动补全 ====================

    /**
     * 构造基于注册表的 id 前缀补全器。
     *
     * @param idSource 提供候选 id 集合的供给器，延迟求值以反映运行时注册变化
     */
    private static SuggestionProvider<FabricClientCommandSource> idSuggestions(
            Supplier<Collection<? extends Identifier>> idSource) {
        return (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase();
            for (Identifier id : idSource.get()) {
                String idStr = id.toString();
                if (idStr.toLowerCase().startsWith(remaining)) {
                    builder.suggest(idStr);
                }
            }
            return builder.buildFuture();
        };
    }

    private static final SuggestionProvider<FabricClientCommandSource> ACTION_ID_SUGGESTIONS =
            idSuggestions(() -> ActionRegistry.getAll().stream().map(IDebugAction::getId).toList());

    private static final SuggestionProvider<FabricClientCommandSource> FEATURE_ID_SUGGESTIONS =
            idSuggestions(() -> FeatureRegistry.getAll().stream().map(IDebugFeature::getId).toList());

    /**
     * 动作参数节点的补全器：先按已输入的 id 找到对应动作，再委托其
     * {@link IDebugAction#suggest} 提供候选，最后按正在输入片段做前缀过滤。
     * 动作未注册或无候选时退化为无补全。
     */
    private static final SuggestionProvider<FabricClientCommandSource> ACTION_ARGS_SUGGESTIONS =
            (ctx, builder) -> {
                String idArg = StringArgumentType.getString(ctx, "id");
                IDebugAction action = ActionRegistry.get(CsDebuggerMod.id(idArg));
                if (action == null) {
                    return builder.buildFuture();
                }
                // 已完整输入的参数（不含正在输入的那一段），交由动作判断当前参数位
                String rawArgs = builder.getInput()
                        .substring(builder.getInput().length() - builder.getRemaining().length());
                String[] completed = ArgTokenizer.tokenize(rawArgs);
                String remaining = builder.getRemaining().toLowerCase();
                for (String candidate : action.suggest(ctx.getSource().getClient(), completed)) {
                    if (candidate.toLowerCase().startsWith(remaining)) {
                        builder.suggest(candidate);
                    }
                }
                return builder.buildFuture();
            };

    // ==================== 命令构建 ====================

    /**
     * 构建 {@code <rootName> dbg ...} 命令树。
     *
     * @param rootName 根命令名（"cs" 或 "chunkscanner"）
     */
    public static LiteralArgumentBuilder<FabricClientCommandSource> buildDbgCommands(String rootName) {
        var root = ClientCommandManager.literal(rootName);
        var dbgNode = ClientCommandManager.literal("dbg");

        // ===== /cs dbg action run <id> [args...] + action info <id> =====
        var actionNode = ClientCommandManager.literal("action");
        actionNode.then(ClientCommandManager.literal("run")
                .then(ClientCommandManager.argument("id", StringArgumentType.string())
                        .suggests(ACTION_ID_SUGGESTIONS)
                        // 带参数形式：args 用 greedyString 整串接收后再做引号感知分词
                        .then(ClientCommandManager.argument("args", StringArgumentType.greedyString())
                                .suggests(ACTION_ARGS_SUGGESTIONS)
                                .executes(ctx -> runAction(
                                        ctx.getSource().getClient(),
                                        StringArgumentType.getString(ctx, "id"),
                                        StringArgumentType.getString(ctx, "args"))))
                        // 无参数形式
                        .executes(ctx -> runAction(
                                ctx.getSource().getClient(),
                                StringArgumentType.getString(ctx, "id"),
                                null))));
        actionNode.then(ClientCommandManager.literal("info")
                .then(ClientCommandManager.argument("id", StringArgumentType.string())
                        .suggests(ACTION_ID_SUGGESTIONS)
                        .executes(ctx -> showAction(
                                ctx.getSource().getClient(),
                                StringArgumentType.getString(ctx, "id")))));
        dbgNode.then(actionNode);

        // ===== /cs dbg feat about|enable|disable <id> + feat gui =====
        var featNode = ClientCommandManager.literal("feat");
        featNode.then(featIdNode("about",
                (client, id) -> showFeature(client, id)));
        featNode.then(featIdNode("enable",
                (client, id) -> setFeature(client, id, true)));
        featNode.then(featIdNode("disable",
                (client, id) -> setFeature(client, id, false)));
        featNode.then(ClientCommandManager.literal("gui")
                .executes(ctx -> openGui(ctx.getSource().getClient())));
        dbgNode.then(featNode);

        // ===== /cs dbg list =====
        dbgNode.then(ClientCommandManager.literal("list")
                .executes(ctx -> listAll(ctx.getSource().getClient())));

        root.then(dbgNode);
        return root;
    }

    /**
     * 构建 {@code <literal> <id>} 形式的特性子命令节点。
     *
     * <p>about / enable / disable 三者结构一致，仅执行逻辑不同，抽出以避免重复。</p>
     */
    private static LiteralArgumentBuilder<FabricClientCommandSource> featIdNode(
            String literal, FeatureCommand command) {
        return ClientCommandManager.literal(literal)
                .then(ClientCommandManager.argument("id", StringArgumentType.string())
                        .suggests(FEATURE_ID_SUGGESTIONS)
                        .executes(ctx -> command.execute(
                                ctx.getSource().getClient(),
                                StringArgumentType.getString(ctx, "id"))));
    }

    /** 特性子命令的执行逻辑，返回值即命令返回码。 */
    @FunctionalInterface
    private interface FeatureCommand {
        int execute(MinecraftClient client, String idArg);
    }

    // ==================== 命令执行 ====================

    /** 执行指定 id 的调试动作。 */
    private static int runAction(MinecraftClient client, String idArg, String rawArgs) {
        Identifier id = parseIdentifier(idArg);
        IDebugAction action = ActionRegistry.get(id);
        if (action == null) {
            sendMsg(client, Text.translatable("chunkscanner-debugger.msg.action_not_found", idArg)
                    .formatted(Formatting.RED));
            return 0;
        }

        String[] args = ArgTokenizer.tokenize(rawArgs);
        if (DebuggerConfigLoader.get().verboseLogging) {
            CsDebuggerMod.LOGGER.info("Executing debug action {} with {} arg(s)", id, args.length);
        }

        // 调试代码稳定性天然偏低，异常必须捕获，绝不允许逸出到 Brigadier
        try {
            action.execute(client, args);
            sendMsg(client, Text.translatable("chunkscanner-debugger.msg.action_success", id.toString())
                    .formatted(Formatting.GREEN));
            return 1;
        } catch (Exception e) {
            CsDebuggerMod.LOGGER.error("Debug action {} failed", id, e);
            String reason = (e.getMessage() != null) ? e.getMessage() : e.getClass().getSimpleName();
            sendMsg(client, Text.translatable("chunkscanner-debugger.msg.action_failed", id.toString(), reason)
                    .formatted(Formatting.RED));
            if (DebuggerConfigLoader.get().showActionStackTrace) {
                sendStackTrace(client, e);
            }
            return 0;
        }
    }

    /** 显示指定调试动作的元信息（id / 名称 / 描述）。 */
    private static int showAction(MinecraftClient client, String idArg) {
        Identifier id = parseIdentifier(idArg);
        IDebugAction action = ActionRegistry.get(id);
        if (action == null) {
            sendMsg(client, Text.translatable("chunkscanner-debugger.msg.action_not_found", idArg)
                    .formatted(Formatting.RED));
            return 0;
        }
        sendMsg(client, Text.translatable("chunkscanner-debugger.msg.action_info_id",
                        Text.literal(id.toString()).formatted(Formatting.GOLD))
                .formatted(Formatting.GRAY));
        sendMsg(client, Text.literal("  ")
                .append(Text.translatable("chunkscanner-debugger.msg.action_info_name")
                        .formatted(Formatting.DARK_GRAY))
                .append(action.getName().copy().formatted(Formatting.AQUA)));
        sendMsg(client, Text.literal("  ")
                .append(Text.translatable("chunkscanner-debugger.msg.action_info_desc")
                        .formatted(Formatting.DARK_GRAY))
                .append(action.getDescription().copy().formatted(Formatting.GRAY)));
        return 1;
    }

    /** 显示指定特性的当前启用状态。 */
    private static int showFeature(MinecraftClient client, String idArg) {
        Identifier id = parseIdentifier(idArg);
        IDebugFeature feature = FeatureRegistry.get(id);
        if (feature == null) {
            sendMsg(client, Text.translatable("chunkscanner-debugger.msg.feature_not_found", idArg)
                    .formatted(Formatting.RED));
            return 0;
        }
        boolean active = FeatureRegistry.isEnabled(id);
        sendMsg(client, Text.translatable("chunkscanner-debugger.msg.feature_status",
                        Text.literal(id.toString()).formatted(Formatting.GOLD),
                        statusText(active))
                .formatted(Formatting.GRAY));
        sendMsg(client, Text.literal("  ")
                .append(feature.getName().copy().formatted(Formatting.AQUA))
                .append(Text.literal(" - ").formatted(Formatting.DARK_GRAY))
                .append(feature.getDescription().copy().formatted(Formatting.GRAY)));
        return 1;
    }

    /** 启用或禁用指定特性。 */
    private static int setFeature(MinecraftClient client, String idArg, boolean value) {
        Identifier id = parseIdentifier(idArg);
        if (FeatureRegistry.get(id) == null) {
            sendMsg(client, Text.translatable("chunkscanner-debugger.msg.feature_not_found", idArg)
                    .formatted(Formatting.RED));
            return 0;
        }
        boolean changed = FeatureRegistry.setEnabled(id, value);
        if (!changed) {
            sendMsg(client, Text.translatable("chunkscanner-debugger.msg.feature_unchanged",
                            Text.literal(id.toString()).formatted(Formatting.GOLD),
                            statusText(value))
                    .formatted(Formatting.YELLOW));
            return 1;
        }
        String key = value
                ? "chunkscanner-debugger.msg.feature_enabled"
                : "chunkscanner-debugger.msg.feature_disabled";
        sendMsg(client, Text.translatable(key, Text.literal(id.toString()).formatted(Formatting.GOLD))
                .formatted(value ? Formatting.GREEN : Formatting.GRAY));
        return 1;
    }

    /** 分节列出全部已注册的动作与特性。 */
    private static int listAll(MinecraftClient client) {
        // Action 分节
        sendMsg(client, Text.translatable("chunkscanner-debugger.msg.list_actions_title",
                ActionRegistry.size()).formatted(Formatting.GOLD, Formatting.BOLD));
        if (ActionRegistry.size() == 0) {
            sendMsg(client, Text.literal("  ")
                    .append(Text.translatable("chunkscanner-debugger.msg.list_empty")
                            .formatted(Formatting.DARK_GRAY)));
        } else {
            for (IDebugAction a : ActionRegistry.getAll()) {
                sendMsg(client, Text.literal("  ")
                        .append(Text.literal(a.getId().toString()).formatted(Formatting.AQUA))
                        .append(Text.literal(" - ").formatted(Formatting.DARK_GRAY))
                        .append(a.getName().copy().formatted(Formatting.GRAY)));
            }
        }

        // Feature 分节
        sendMsg(client, Text.translatable("chunkscanner-debugger.msg.list_features_title",
                FeatureRegistry.size()).formatted(Formatting.GOLD, Formatting.BOLD));
        if (FeatureRegistry.size() == 0) {
            sendMsg(client, Text.literal("  ")
                    .append(Text.translatable("chunkscanner-debugger.msg.list_empty")
                            .formatted(Formatting.DARK_GRAY)));
        } else {
            for (IDebugFeature f : FeatureRegistry.getAll()) {
                sendMsg(client, Text.literal("  ")
                        .append(statusText(FeatureRegistry.isEnabled(f.getId())))
                        .append(Text.literal(" "))
                        .append(Text.literal(f.getId().toString()).formatted(Formatting.AQUA))
                        .append(Text.literal(" - ").formatted(Formatting.DARK_GRAY))
                        .append(f.getName().copy().formatted(Formatting.GRAY)));
            }
        }
        return 1;
    }

    /** 打开特性开关配置界面。 */
    private static int openGui(MinecraftClient client) {
        client.send(() -> client.setScreen(FeatureConfigScreen.create(client.currentScreen)));
        return 1;
    }

    // ==================== 辅助 ====================

    private static void sendMsg(MinecraftClient client, Text msg) {
        if (client.player != null) {
            client.player.sendMessage(msg, false);
        }
    }

    /** 启用状态的彩色文本表示。 */
    private static Text statusText(boolean active) {
        return active
                ? Text.translatable("chunkscanner-debugger.msg.state_enabled").formatted(Formatting.GREEN)
                : Text.translatable("chunkscanner-debugger.msg.state_disabled").formatted(Formatting.GRAY);
    }

    /** 发送异常堆栈摘要（最多 5 帧），供排查动作内部错误。 */
    private static void sendStackTrace(MinecraftClient client, Exception e) {
        StackTraceElement[] trace = e.getStackTrace();
        int limit = Math.min(trace.length, 5);
        for (int i = 0; i < limit; i++) {
            sendMsg(client, Text.literal("  at " + trace[i].toString()).formatted(Formatting.DARK_GRAY));
        }
    }

    /**
     * 将命令参数中的名称解析为 {@link Identifier}。
     *
     * <p>用户输入通常为裸名（如 {@code dumpdb}），统一补全
     * {@code chunkscanner-debugger} 命名空间；若输入已含冒号则按原样解析。
     * 注意 {@code tryParse} 对含非法字符（如大写）的输入返回 null 而非抛异常。</p>
     */
    private static Identifier parseIdentifier(String arg) {
        if (arg == null || arg.isEmpty()) return CsDebuggerMod.id("unknown");
        Identifier parsed = Identifier.tryParse(arg);
        return (parsed != null) ? parsed : CsDebuggerMod.id(arg);
    }
}
