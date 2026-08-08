package com.billy65536.infrastructure.security;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import com.billy65536.infrastructure.InfrastructureMod;
import com.billy65536.infrastructure.security.builtin.ConfigLocker;
import com.billy65536.infrastructure.security.core.audit.AuditEntry;
import com.billy65536.infrastructure.security.core.audit.SecurityAuditLog;
import com.billy65536.infrastructure.security.core.internal.Origin;
import com.billy65536.infrastructure.security.core.policy.ISecurityExecutor;
import com.billy65536.infrastructure.security.core.policy.ISecurityPolicy;
import com.billy65536.infrastructure.security.core.policy.SecurityManager;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

/**
 * {@code /inf security} 命令树的构建与执行逻辑。
 *
 * <p>命令树完全由 {@link SecurityManager} 的注册内容动态驱动，infrastructure 自身不硬编码
 * 任何具体策略。</p>
 *
 * <p>提供的子命令：</p>
 * <ul>
 *   <li>{@code /inf security list} —— 树形列出全部策略及其下辖执行器</li>
 *   <li>{@code /inf security status} —— 仅到策略层级，展示各策略是否激活</li>
 *   <li>{@code /inf security status <policy|executor>} —— 展示指定项的完整详情</li>
 *   <li>{@code /inf security active|deactive <policy>} —— 手动激活 / 停用策略，
 *       仅对声明了 {@link ISecurityPolicy#isManuallyToggleable()} 的策略开放</li>
 *   <li>{@code /inf security audit [count]} —— 列出最近的阻止记录（最新在前），
 *       不带参数时取 {@value #DEFAULT_AUDIT_LIMIT} 条</li>
 *   <li>{@code /inf security audit clear} —— 清空审计流水</li>
 * </ul>
 */
public final class SecurityCommands {

    private SecurityCommands() {}

    /** {@code /inf security audit} 不带参数时默认列出的记录条数。 */
    private static final int DEFAULT_AUDIT_LIMIT = 20;

    /** 审计时间戳的展示格式（本地时区，精确到秒）。 */
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    // ==================== 自动补全 ====================

    /**
     * 构造基于注册表的 id 前缀补全器。
     *
     * @param idSource 提供候选 id 集合的供给器，延迟求值以反映运行时注册变化
     */
    private static SuggestionProvider<FabricClientCommandSource> idSuggestions(
            Supplier<Collection<? extends Identifier>> idSource) {
        return (ctx, builder) -> {
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
     */
    private static final SuggestionProvider<FabricClientCommandSource> TOGGLEABLE_ID_SUGGESTIONS =
            idSuggestions(() -> SecurityManager.getAll().stream()
                    .filter(ISecurityPolicy::isManuallyToggleable)
                    .map(ISecurityPolicy::getId)
                    .toList());

    /** 全部策略 id 与执行器 id 的合集（策略在前，保持展示顺序）。 */
    private static Collection<Identifier> allTargetIds() {
        List<Identifier> ids = new ArrayList<>();
        SecurityManager.getAll().forEach(p -> ids.add(p.getId()));
        SecurityManager.getAllExecutors().forEach(e -> ids.add(e.getId()));
        return ids;
    }

    // ==================== 命令构建 ====================

    /** 构建 {@code security ...} 子树，挂载到 {@code /inf} 根命令之下。 */
    public static LiteralArgumentBuilder<FabricClientCommandSource> buildSecurityCommands() {
        var root = ClientCommandManager.literal("security");

        root.then(ClientCommandManager.literal("list")
                .executes(ctx -> listAll(ctx.getSource().getClient())));

        root.then(ClientCommandManager.literal("status")
                .executes(ctx -> statusOverview(ctx.getSource().getClient()))
                .then(ClientCommandManager.argument("id", IdentifierArgumentType.identifier())
                        .suggests(TARGET_ID_SUGGESTIONS)
                        .executes(ctx -> statusDetail(
                                ctx.getSource().getClient(),
                                ctx.getArgument("id", Identifier.class)))));

        root.then(toggleNode("active", true));
        root.then(toggleNode("deactive", false));

        root.then(ClientCommandManager.literal("audit")
                .executes(ctx -> showAudit(ctx.getSource().getClient(), DEFAULT_AUDIT_LIMIT))
                .then(ClientCommandManager.literal("clear")
                        .executes(ctx -> clearAudit(ctx.getSource().getClient())))
                .then(ClientCommandManager.argument("count", IntegerArgumentType.integer(1))
                        .executes(ctx -> showAudit(
                                ctx.getSource().getClient(),
                                IntegerArgumentType.getInteger(ctx, "count")))));

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
                SecurityManager.size()).formatted(Formatting.GOLD, Formatting.BOLD));
        if (SecurityManager.size() == 0) {
            sendMsg(client, Text.literal("  ")
                    .append(Text.translatable("infrastructure.msg.security.list_empty")
                            .formatted(Formatting.DARK_GRAY)));
            return 1;
        }
        for (ISecurityPolicy policy : SecurityManager.getAll()) {
            sendMsg(client, Text.literal("  ")
                    .append(statusText(SecurityManager.isActive(policy.getId())))
                    .append(Text.literal(" "))
                    .append(Text.literal(policy.getId().toString()).formatted(Formatting.AQUA))
                    .append(Text.literal(" - ").formatted(Formatting.DARK_GRAY))
                    .append(policy.getName().copy().formatted(Formatting.GRAY)));
            for (ISecurityExecutor exec : executorsOf(policy)) {
                sendMsg(client, Text.literal("    - ").formatted(Formatting.DARK_GRAY)
                        .append(statusText(SecurityManager.isExecutorEnabled(exec.getId())))
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
                SecurityManager.size()).formatted(Formatting.GOLD, Formatting.BOLD));
        if (SecurityManager.size() == 0) {
            sendMsg(client, Text.literal("  ")
                    .append(Text.translatable("infrastructure.msg.security.list_empty")
                            .formatted(Formatting.DARK_GRAY)));
            return 1;
        }
        for (ISecurityPolicy policy : SecurityManager.getAll()) {
            sendMsg(client, Text.literal("  ")
                    .append(statusText(SecurityManager.isActive(policy.getId())))
                    .append(Text.literal(" "))
                    .append(Text.literal(policy.getId().toString()).formatted(Formatting.AQUA)));
        }
        return 1;
    }

    /** 带参 status：展示指定策略或执行器的完整详情。 */
    private static int statusDetail(MinecraftClient client, Identifier rawId) {
        Identifier id = normalizeTargetId(rawId);

        ISecurityPolicy policy = SecurityManager.get(id);
        if (policy != null) {
            boolean active = SecurityManager.isActive(id);
            sendMsg(client, Text.translatable("infrastructure.msg.security.policy_status",
                            Text.literal(id.toString()).formatted(Formatting.GOLD),
                            statusText(active))
                    .formatted(Formatting.GRAY));
            sendMsg(client, indent(Text.translatable("infrastructure.msg.security.field_trigger")
                    .append(Text.literal(policy.getTrigger().name()).formatted(Formatting.AQUA))));
            sendMsg(client, indent(Text.translatable("infrastructure.msg.security.field_toggleable")
                    .append(boolText(policy.isManuallyToggleable()))));

            List<ISecurityExecutor> execs = executorsOf(policy);
            sendMsg(client, indent(Text.translatable("infrastructure.msg.security.field_executors",
                    execs.size())));
            for (ISecurityExecutor exec : execs) {
                sendMsg(client, Text.literal("    - ").formatted(Formatting.DARK_GRAY)
                        .append(statusText(SecurityManager.isExecutorEnabled(exec.getId())))
                        .append(Text.literal(" "))
                        .append(Text.literal(exec.getId().toString()).formatted(Formatting.AQUA)));
            }
            sendOverrideDiagnostics(client);
            sendLockSnapshot(client);
            return 1;
        }

        ISecurityExecutor executor = SecurityManager.getExecutor(id);
        if (executor != null) {
            sendMsg(client, Text.translatable("infrastructure.msg.security.executor_status",
                            Text.literal(id.toString()).formatted(Formatting.GOLD),
                            statusText(SecurityManager.isExecutorEnabled(id)))
                    .formatted(Formatting.GRAY));
            sendMsg(client, indent(Text.translatable("infrastructure.msg.security.field_desc")
                    .append(executor.getDescription().copy().formatted(Formatting.GRAY))));
            sendOverrideDiagnostics(client);
            return 1;
        }

        sendNotFound(client, id);
        return 0;
    }

    /** 展示 Override 补丁数量与门控状态（熔断诊断）。 */
    private static void sendOverrideDiagnostics(MinecraftClient client) {
        sendMsg(client, indent(Text.translatable("infrastructure.msg.security.field_override_patches")
                .append(Text.literal(String.valueOf(SecurityManager.getContext().patchCount()))
                        .formatted(Formatting.AQUA))));
        sendMsg(client, indent(Text.translatable("infrastructure.msg.security.field_override_allowed")
                .append(boolText(SecurityManager.isOverrideAllowed()))));
    }

    /**
     * 手动激活 / 停用策略。
     */
    private static int setPolicyActive(MinecraftClient client, Identifier rawId, boolean value) {
        Identifier id = normalizePolicyId(rawId);
        ISecurityPolicy policy = SecurityManager.get(id);
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
        boolean changed = SecurityManager.setActive(id, value);
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

    /**
     * 列出最近若干条阻止记录（最新在前）。
     *
     * <p>标题展示「本次列出条数 / 流水现存总条数」；每条附时间、命中次数，
     * 并在存在多个贡献策略时补注「另有 N 个策略贡献」。</p>
     *
     * @param count 期望列出的条数，超出现存条数时按现存条数列出
     * @return 恒为 1（命令执行成功）
     */
    private static int showAudit(MinecraftClient client, int count) {
        List<AuditEntry> entries = SecurityAuditLog.recent(count);
        sendMsg(client, Text.translatable("infrastructure.msg.security.audit_title",
                entries.size(), SecurityAuditLog.size()).formatted(Formatting.GOLD, Formatting.BOLD));
        if (entries.isEmpty()) {
            sendMsg(client, indent(Text.translatable("infrastructure.msg.security.audit_empty")));
            return 1;
        }
        for (AuditEntry e : entries) {
            sendMsg(client, Text.literal("  ")
                    .append(Text.translatable(channelKey(e.channel())).formatted(Formatting.RED))
                    .append(Text.literal(" "))
                    .append(Text.literal(e.fullPath()).formatted(Formatting.DARK_GRAY))
                    .append(Text.literal(" "))
                    .append(Text.translatable("infrastructure.msg.security.audit_locked_by",
                            Text.literal(policyLabel(e)).formatted(Formatting.AQUA))
                            .formatted(Formatting.GRAY)));

            MutableText note = Text.translatable("infrastructure.msg.security.audit_entry_note",
                    TIME_FORMAT.format(Instant.ofEpochMilli(e.timestamp())), e.hitCount());
            int extra = e.origin() == null ? 0 : Math.max(0, e.origin().getContributors().size() - 1);
            if (extra > 0) {
                note.append(Text.literal(" "))
                        .append(Text.translatable("infrastructure.msg.security.audit_contributors", extra));
            }
            sendMsg(client, Text.literal("    ").append(note.formatted(Formatting.GRAY)));
        }
        return 1;
    }

    /**
     * 清空审计流水并回报清除条数。
     *
     * @return 恒为 1（命令执行成功）
     */
    private static int clearAudit(MinecraftClient client) {
        int cleared = SecurityAuditLog.clear();
        sendMsg(client, Text.translatable("infrastructure.msg.security.audit_cleared", cleared)
                .formatted(Formatting.GREEN));
        return 1;
    }

    /** 审计条目的来源策略展示文本；无法归因时给出明确占位。 */
    private static String policyLabel(AuditEntry e) {
        Identifier policy = e.policyId();
        return policy != null ? policy.toString()
                : Text.translatable("infrastructure.msg.security.audit_unknown_policy").getString();
    }

    /** 写入渠道对应的翻译键。 */
    private static String channelKey(AuditEntry.Channel channel) {
        return channel == AuditEntry.Channel.RESET
                ? "infrastructure.msg.security.audit_channel_reset"
                : "infrastructure.msg.security.audit_channel_set";
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
        var snapshot = ConfigLocker.getLockStatusSnapshot();
        if (snapshot.isEmpty()) {
            sendMsg(client, indent(Text.translatable("infrastructure.msg.security.locks_empty")));
            return;
        }
        var sources = ConfigLocker.getLockSourceSnapshot();
        sendMsg(client, indent(Text.translatable("infrastructure.msg.security.locks_header",
                snapshot.size())));
        snapshot.forEach((k, v) -> {
            MutableText line = Text.literal("    ")
                    .append(Text.literal(k).formatted(Formatting.DARK_GRAY))
                    .append(Text.literal(" = ").formatted(Formatting.DARK_GRAY))
                    .append(Text.literal(String.valueOf(v)).formatted(Formatting.GRAY));
            Origin origin = sources.get(k);
            if (origin != null && !origin.isUnknown()) {
                line.append(Text.literal(" "))
                        .append(Text.translatable("infrastructure.msg.security.audit_locked_by",
                                Text.literal(origin.getPrimary().toString()).formatted(Formatting.AQUA))
                                .formatted(Formatting.DARK_GRAY));
            }
            sendMsg(client, line);
        });
    }

    /** 统一的「未找到」提示。 */
    private static void sendNotFound(MinecraftClient client, Identifier id) {
        sendMsg(client, Text.translatable("infrastructure.msg.security.target_not_found",
                id.toString()).formatted(Formatting.RED));
    }

    /** 取策略下辖执行器（按 getConfigs 的 executorId 反查）。 */
    private static List<ISecurityExecutor> executorsOf(ISecurityPolicy policy) {
        List<ISecurityExecutor> out = new ArrayList<>();
        for (var cfg : policy.getConfigs()) {
            ISecurityExecutor ex = SecurityManager.executorOf(cfg.getExecutorId());
            if (ex != null) out.add(ex);
        }
        return out;
    }

    /** 归一化 status / info 的目标 id（策略与执行器合集）。 */
    private static Identifier normalizeTargetId(Identifier id) {
        return normalizeIdentifier(id, SecurityCommands::allTargetIds);
    }

    /** 归一化 active / deactive 的策略 id。 */
    private static Identifier normalizePolicyId(Identifier id) {
        return normalizeIdentifier(id,
                () -> SecurityManager.getAll().stream().map(ISecurityPolicy::getId).toList());
    }

    /**
     * 归一化 {@link IdentifierArgumentType} 解析出的 {@link Identifier}。
     *
     * <p>策略 / 执行器由各上层 mod 以<b>自身</b>命名空间注册，因此<b>显式带命名空间的
     * 输入必须原样保留</b>。</p>
     *
     * <p>仅当输入是裸名时才需要推断命名空间：{@link IdentifierArgumentType} 会把裸名补成
     * {@code minecraft} 命名空间。此时在注册表中按 path 回查，命中唯一项则用其真实命名空间；
     * 无命中或有歧义时退回本模组命名空间，由调用方报「未找到」。</p>
     *
     * @param id       已解析的 id，可为 null
     * @param idSource 注册表内全部 id 的供给器，延迟求值以反映运行时注册变化
     */
    private static Identifier normalizeIdentifier(
            Identifier id, Supplier<Collection<? extends Identifier>> idSource) {
        if (id == null) return InfrastructureMod.id("unknown");
        if (!"minecraft".equals(id.getNamespace())) return id;
        Identifier matched = null;
        for (Identifier known : idSource.get()) {
            if (known.getPath().equals(id.getPath())) {
                if (matched != null) return new Identifier(InfrastructureMod.MOD_ID, id.getPath());
                matched = known;
            }
        }
        return (matched != null) ? matched : new Identifier(InfrastructureMod.MOD_ID, id.getPath());
    }
}
