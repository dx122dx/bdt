package com.billy65536.infrastructure.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import com.billy65536.infrastructure.InfrastructureMod;
import com.billy65536.infrastructure.security.core.policy.ISecurityExecutor;
import com.billy65536.infrastructure.security.core.policy.ISecurityPolicy;
import com.billy65536.infrastructure.security.core.policy.PolicyRegistry;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

/**
 * {@code /inf security} 命令树的构建与执行逻辑。
 *
 * <p>命令树完全由 {@link PolicyRegistry} 的注册内容动态驱动，infrastructure 自身不硬编码
 * 任何具体策略。</p>
 *
 * <p>提供的子命令：</p>
 * <ul>
 *   <li>{@code /inf security list} —— 树形列出全部策略及其下辖执行器</li>
 *   <li>{@code /inf security status} —— 仅到策略层级，展示各策略是否激活</li>
 *   <li>{@code /inf security status <policy|executor>} —— 展示指定项的完整详情</li>
 *   <li>{@code /inf security info <policy|executor>} —— 展示元信息（来源、描述、子事件等）</li>
 *   <li>{@code /inf security active|deactive <policy>} —— 手动激活 / 停用策略，
 *       仅对声明了 {@link ISecurityPolicy#isManuallyToggleable()} 的策略开放</li>
 * </ul>
 */
public final class SecurityCommands {

    private SecurityCommands() {}

    // ==================== 自动补全 ====================

    /**
     * 构造基于注册表的 id 前缀补全器。
     *
     * @param idSource 提供候选 id 集合的供给器，延迟求值以反映运行时注册变化
     */
    private static SuggestionProvider<FabricClientCommandSource> idSuggestions(
            Supplier<Collection<? extends Identifier>> idSource) {
        return (ctx, builder) -> {
            // 容忍用户已手动输入的前导引号，统一按无引号串做前缀过滤。
            // 大小写归一固定 Locale.ROOT：默认 locale 为 tr_TR 时 'I' 会转成 'ı'，
            // 导致含大写 I 的 id 补全静默失效
            String remaining = builder.getRemaining().replace("\"", "").toLowerCase(Locale.ROOT);
            for (Identifier id : idSource.get()) {
                String idStr = id.toString();
                if (idStr.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                    builder.suggest(idStr);
                }
            }
            return builder.buildFuture();
        };
    }

    /** 策略与执行器的合集补全，供 status / info 使用。 */
    private static final SuggestionProvider<FabricClientCommandSource> TARGET_ID_SUGGESTIONS =
            idSuggestions(SecurityCommands::allTargetIds);

    /**
     * 仅列出允许手动开关的策略，供 active / deactive 使用。
     *
     * <p>补全是<b>引导</b>，执行时仍会二次校验：玩家可以绕过补全直接输入不可切换的 id。</p>
     */
    private static final SuggestionProvider<FabricClientCommandSource> TOGGLEABLE_ID_SUGGESTIONS =
            idSuggestions(() -> PolicyRegistry.getAll().stream()
                    .filter(ISecurityPolicy::isManuallyToggleable)
                    .map(ISecurityPolicy::getId)
                    .toList());

    /** 全部策略 id 与执行器 id 的合集（策略在前，保持展示顺序）。 */
    private static Collection<Identifier> allTargetIds() {
        List<Identifier> ids = new ArrayList<>();
        PolicyRegistry.getAll().forEach(p -> ids.add(p.getId()));
        PolicyRegistry.getAllExecutors().forEach(e -> ids.add(e.getId()));
        return ids;
    }

    // ==================== 命令构建 ====================

    /** 构建 {@code security ...} 子树，挂载到 {@code /inf} 根命令之下。 */
    public static LiteralArgumentBuilder<FabricClientCommandSource> buildSecurityCommands() {
        var root = ClientCommandManager.literal("security");

        // ===== /inf security list =====
        root.then(ClientCommandManager.literal("list")
                .executes(ctx -> listAll(ctx.getSource().getClient())));

        // ===== /inf security status [<policy|executor>] =====
        root.then(ClientCommandManager.literal("status")
                .executes(ctx -> statusOverview(ctx.getSource().getClient()))
                .then(ClientCommandManager.argument("id", IdentifierArgumentType.identifier())
                        .suggests(TARGET_ID_SUGGESTIONS)
                        .executes(ctx -> statusDetail(
                                ctx.getSource().getClient(),
                                ctx.getArgument("id", Identifier.class)))));

        // ===== /inf security info <policy|executor> =====
        root.then(ClientCommandManager.literal("info")
                .then(ClientCommandManager.argument("id", IdentifierArgumentType.identifier())
                        .suggests(TARGET_ID_SUGGESTIONS)
                        .executes(ctx -> showInfo(
                                ctx.getSource().getClient(),
                                ctx.getArgument("id", Identifier.class)))));

        // ===== /inf security active|deactive <policy> =====
        root.then(toggleNode("active", true));
        root.then(toggleNode("deactive", false));

        return root;
    }

    /** 构建 {@code active} / {@code deactive} 节点，二者仅目标状态不同。 */
    private static LiteralArgumentBuilder<FabricClientCommandSource> toggleNode(
            String literal, boolean value) {
        return ClientCommandManager.literal(literal)
                .then(ClientCommandManager.argument("id", IdentifierArgumentType.identifier())
                        .suggests(TOGGLEABLE_ID_SUGGESTIONS)
                        .executes(ctx -> setPolicyActive(
                                ctx.getSource().getClient(),
                                ctx.getArgument("id", Identifier.class),
                                value)));
    }

    // ==================== 命令执行 ====================

    /** 树形列出全部策略及其下辖执行器。 */
    private static int listAll(MinecraftClient client) {
        sendMsg(client, Text.translatable("infrastructure.msg.security.list_policies_title",
                PolicyRegistry.size()).formatted(Formatting.GOLD, Formatting.BOLD));
        if (PolicyRegistry.size() == 0) {
            sendMsg(client, Text.literal("  ")
                    .append(Text.translatable("infrastructure.msg.security.list_empty")
                            .formatted(Formatting.DARK_GRAY)));
            return 1;
        }
        for (ISecurityPolicy policy : PolicyRegistry.getAll()) {
            sendMsg(client, Text.literal("  ")
                    .append(statusText(PolicyRegistry.isActive(policy.getId())))
                    .append(Text.literal(" "))
                    .append(Text.literal(policy.getId().toString()).formatted(Formatting.AQUA))
                    .append(Text.literal(" - ").formatted(Formatting.DARK_GRAY))
                    .append(policy.getName().copy().formatted(Formatting.GRAY)));
            List<ISecurityExecutor> execs = policy.getExecutors();
            if (execs == null) continue;
            for (ISecurityExecutor exec : execs) {
                if (exec == null) continue;
                sendMsg(client, Text.literal("    - ").formatted(Formatting.DARK_GRAY)
                        .append(statusText(PolicyRegistry.isExecutorEnabled(exec.getId())))
                        .append(Text.literal(" "))
                        .append(Text.literal(exec.getId().toString()).formatted(Formatting.AQUA))
                        .append(Text.literal(" - ").formatted(Formatting.DARK_GRAY))
                        .append(exec.getName().copy().formatted(Formatting.GRAY)));
            }
        }
        return 1;
    }

    /** 无参 status：仅展示到策略层级（是否激活）。 */
    private static int statusOverview(MinecraftClient client) {
        sendMsg(client, Text.translatable("infrastructure.msg.security.status_title",
                PolicyRegistry.size()).formatted(Formatting.GOLD, Formatting.BOLD));
        if (PolicyRegistry.size() == 0) {
            sendMsg(client, Text.literal("  ")
                    .append(Text.translatable("infrastructure.msg.security.list_empty")
                            .formatted(Formatting.DARK_GRAY)));
            return 1;
        }
        for (ISecurityPolicy policy : PolicyRegistry.getAll()) {
            sendMsg(client, Text.literal("  ")
                    .append(statusText(PolicyRegistry.isActive(policy.getId())))
                    .append(Text.literal(" "))
                    .append(Text.literal(policy.getId().toString()).formatted(Formatting.AQUA)));
        }
        return 1;
    }

    /** 带参 status：展示指定策略或执行器的完整详情。 */
    private static int statusDetail(MinecraftClient client, Identifier rawId) {
        Identifier id = normalizeTargetId(rawId);

        ISecurityPolicy policy = PolicyRegistry.get(id);
        if (policy != null) {
            boolean active = PolicyRegistry.isActive(id);
            sendMsg(client, Text.translatable("infrastructure.msg.security.policy_status",
                            Text.literal(id.toString()).formatted(Formatting.GOLD),
                            statusText(active))
                    .formatted(Formatting.GRAY));
            sendMsg(client, indent(Text.translatable("infrastructure.msg.security.field_trigger")
                    .append(Text.literal(policy.getTrigger().name()).formatted(Formatting.AQUA))));
            sendMsg(client, indent(Text.translatable("infrastructure.msg.security.field_toggleable")
                    .append(boolText(policy.isManuallyToggleable()))));

            List<ISecurityExecutor> execs = policy.getExecutors();
            int count = (execs == null) ? 0 : execs.size();
            sendMsg(client, indent(Text.translatable("infrastructure.msg.security.field_executors", count)));
            if (execs != null) {
                for (ISecurityExecutor exec : execs) {
                    if (exec == null) continue;
                    sendMsg(client, Text.literal("    - ").formatted(Formatting.DARK_GRAY)
                            .append(statusText(PolicyRegistry.isExecutorEnabled(exec.getId())))
                            .append(Text.literal(" "))
                            .append(Text.literal(exec.getId().toString()).formatted(Formatting.AQUA)));
                }
            }
            sendLockSnapshot(client);
            return 1;
        }

        ISecurityExecutor executor = PolicyRegistry.getExecutor(id);
        if (executor != null) {
            ISecurityPolicy owner = PolicyRegistry.findPolicyOf(id);
            sendMsg(client, Text.translatable("infrastructure.msg.security.executor_status",
                            Text.literal(id.toString()).formatted(Formatting.GOLD),
                            statusText(PolicyRegistry.isExecutorEnabled(id)))
                    .formatted(Formatting.GRAY));
            sendMsg(client, indent(Text.translatable("infrastructure.msg.security.field_owner")
                    .append(Text.literal(owner == null ? "-" : owner.getId().toString())
                            .formatted(Formatting.AQUA))));
            sendMsg(client, indent(Text.translatable("infrastructure.msg.security.field_desc")
                    .append(executor.getDescription().copy().formatted(Formatting.GRAY))));
            return 1;
        }

        sendNotFound(client, id);
        return 0;
    }

    /** 展示策略或执行器的元信息。 */
    private static int showInfo(MinecraftClient client, Identifier rawId) {
        Identifier id = normalizeTargetId(rawId);

        ISecurityPolicy policy = PolicyRegistry.get(id);
        if (policy != null) {
            sendMsg(client, Text.translatable("infrastructure.msg.security.info_id",
                            Text.literal(id.toString()).formatted(Formatting.GOLD))
                    .formatted(Formatting.GRAY));
            sendMsg(client, indent(Text.translatable("infrastructure.msg.security.field_name")
                    .append(policy.getName().copy().formatted(Formatting.AQUA))));
            sendMsg(client, indent(Text.translatable("infrastructure.msg.security.field_desc")
                    .append(policy.getDescription().copy().formatted(Formatting.GRAY))));
            sendMsg(client, indent(Text.translatable("infrastructure.msg.security.field_source")
                    .append(Text.literal(id.getNamespace()).formatted(Formatting.AQUA))));
            sendMsg(client, indent(Text.translatable("infrastructure.msg.security.field_trigger")
                    .append(Text.literal(policy.getTrigger().name()).formatted(Formatting.AQUA))));
            sendMsg(client, indent(Text.translatable("infrastructure.msg.security.field_toggleable")
                    .append(boolText(policy.isManuallyToggleable()))));
            sendMsg(client, indent(Text.translatable("infrastructure.msg.security.field_state")
                    .append(statusText(PolicyRegistry.isActive(id)))));
            return 1;
        }

        ISecurityExecutor executor = PolicyRegistry.getExecutor(id);
        if (executor != null) {
            ISecurityPolicy owner = PolicyRegistry.findPolicyOf(id);
            sendMsg(client, Text.translatable("infrastructure.msg.security.info_id",
                            Text.literal(id.toString()).formatted(Formatting.GOLD))
                    .formatted(Formatting.GRAY));
            sendMsg(client, indent(Text.translatable("infrastructure.msg.security.field_name")
                    .append(executor.getName().copy().formatted(Formatting.AQUA))));
            sendMsg(client, indent(Text.translatable("infrastructure.msg.security.field_desc")
                    .append(executor.getDescription().copy().formatted(Formatting.GRAY))));
            sendMsg(client, indent(Text.translatable("infrastructure.msg.security.field_owner")
                    .append(Text.literal(owner == null ? "-" : owner.getId().toString())
                            .formatted(Formatting.AQUA))));
            sendMsg(client, indent(Text.translatable("infrastructure.msg.security.field_state")
                    .append(statusText(PolicyRegistry.isExecutorEnabled(id)))));
            return 1;
        }

        sendNotFound(client, id);
        return 0;
    }

    /**
     * 手动激活 / 停用策略。
     *
     * <p>对不允许手动开关的策略<b>显式报错</b>而非静默忽略：补全只是引导，
     * 玩家完全可以绕过补全直接输入。</p>
     */
    private static int setPolicyActive(MinecraftClient client, Identifier rawId, boolean value) {
        Identifier id = normalizePolicyId(rawId);
        ISecurityPolicy policy = PolicyRegistry.get(id);
        if (policy == null) {
            sendMsg(client, Text.translatable("infrastructure.msg.security.policy_not_found",
                    id.toString()).formatted(Formatting.RED));
            return 0;
        }
        if (!policy.isManuallyToggleable()) {
            sendMsg(client, Text.translatable("infrastructure.msg.security.policy_not_toggleable",
                    Text.literal(id.toString()).formatted(Formatting.GOLD)).formatted(Formatting.RED));
            return 0;
        }
        boolean changed = PolicyRegistry.setActive(id, value);
        if (!changed) {
            sendMsg(client, Text.translatable("infrastructure.msg.security.policy_unchanged",
                            Text.literal(id.toString()).formatted(Formatting.GOLD),
                            statusText(value))
                    .formatted(Formatting.YELLOW));
            return 1;
        }
        String key = value
                ? "infrastructure.msg.security.policy_activated"
                : "infrastructure.msg.security.policy_deactivated";
        sendMsg(client, Text.translatable(key, Text.literal(id.toString()).formatted(Formatting.GOLD))
                .formatted(value ? Formatting.GREEN : Formatting.GRAY));
        return 1;
    }

    // ==================== 辅助 ====================

    private static void sendMsg(MinecraftClient client, Text msg) {
        if (client.player != null) {
            client.player.sendMessage(msg, false);
        }
    }

    /** 统一的二级缩进 + 暗灰标签样式。 */
    private static Text indent(Text body) {
        return Text.literal("  ").append(body.copy().formatted(Formatting.DARK_GRAY));
    }

    /** 激活状态的彩色文本表示。 */
    private static Text statusText(boolean active) {
        return active
                ? Text.translatable("infrastructure.msg.security.state_active").formatted(Formatting.GREEN)
                : Text.translatable("infrastructure.msg.security.state_inactive").formatted(Formatting.GRAY);
    }

    /** 是 / 否的彩色文本表示。 */
    private static Text boolText(boolean value) {
        return value
                ? Text.translatable("infrastructure.msg.security.yes").formatted(Formatting.GREEN)
                : Text.translatable("infrastructure.msg.security.no").formatted(Formatting.GRAY);
    }

    /** 输出当前锁定表快照，用于策略详情。 */
    private static void sendLockSnapshot(MinecraftClient client) {
        Map<String, String> snapshot = ConfigLocker.getLockStatusSnapshot();
        if (snapshot.isEmpty()) {
            sendMsg(client, indent(Text.translatable("infrastructure.msg.security.locks_empty")));
            return;
        }
        sendMsg(client, indent(Text.translatable("infrastructure.msg.security.locks_header",
                snapshot.size())));
        snapshot.forEach((k, v) -> sendMsg(client, Text.literal("    ")
                .append(Text.literal(k).formatted(Formatting.DARK_GRAY))
                .append(Text.literal(" = ").formatted(Formatting.DARK_GRAY))
                .append(Text.literal(String.valueOf(v)).formatted(Formatting.GRAY))));
    }

    /** 统一的「未找到」提示。 */
    private static void sendNotFound(MinecraftClient client, Identifier id) {
        sendMsg(client, Text.translatable("infrastructure.msg.security.target_not_found",
                id.toString()).formatted(Formatting.RED));
    }

    /** 归一化 status / info 的目标 id（策略与执行器合集）。 */
    private static Identifier normalizeTargetId(Identifier id) {
        return normalizeIdentifier(id, SecurityCommands::allTargetIds);
    }

    /** 归一化 active / deactive 的策略 id。 */
    private static Identifier normalizePolicyId(Identifier id) {
        return normalizeIdentifier(id,
                () -> PolicyRegistry.getAll().stream().map(ISecurityPolicy::getId).toList());
    }

    /**
     * 归一化 {@link IdentifierArgumentType} 解析出的 {@link Identifier}。
     *
     * <p>策略 / 执行器由各上层 mod 以<b>自身</b>命名空间注册，因此<b>显式带命名空间的
     * 输入必须原样保留</b>。</p>
     *
     * <p>仅当输入是裸名时才需要推断命名空间：{@link IdentifierArgumentType} 会把裸名
     * 补成 {@code minecraft} 命名空间。此时在注册表中按 path 回查，命中唯一项则用其
     * 真实命名空间；无命中或有歧义时退回本模组命名空间，由调用方报「未找到」。</p>
     *
     * @param id       已解析的 id，可为 null
     * @param idSource 注册表内全部 id 的供给器，延迟求值以反映运行时注册变化
     */
    private static Identifier normalizeIdentifier(
            Identifier id, Supplier<Collection<? extends Identifier>> idSource) {
        if (id == null) return InfrastructureMod.id("unknown");
        // 非 minecraft 命名空间 = 用户显式书写，原样尊重
        if (!"minecraft".equals(id.getNamespace())) return id;
        // 裸名：在注册表中按 path 回查真实命名空间
        Identifier matched = null;
        for (Identifier known : idSource.get()) {
            if (known.getPath().equals(id.getPath())) {
                // 多个命名空间下同名，无法判定，退回本模组命名空间由调用方报「未找到」
                if (matched != null) return new Identifier(InfrastructureMod.MOD_ID, id.getPath());
                matched = known;
            }
        }
        return (matched != null) ? matched : new Identifier(InfrastructureMod.MOD_ID, id.getPath());
    }
}
