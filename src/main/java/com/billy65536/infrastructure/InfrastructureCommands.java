package com.billy65536.infrastructure;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.billy65536.infrastructure.core.cli.ArgParser;
import com.billy65536.infrastructure.core.cli.CliCompletion;
import com.billy65536.infrastructure.core.module.IModule;
import com.billy65536.infrastructure.core.module.ModuleCommandRegistrar;
import com.billy65536.infrastructure.core.module.ModuleConfigReflectionAccessor;
import com.billy65536.infrastructure.core.module.ModuleRegistry;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.util.Identifier;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * {@code /inf} 根命令的注册与构建。
 *
 * <p>根命令为 {@code /inf}（亦可作为 {@code /billy-inf:inf} 调用，两者等价）。
 * 提供以下子命令：</p>
 * <ul>
 *   <li>{@code config} —— 模块配置统一访问（{@code get|set|reset <module> <path|assignments>}）</li>
 *   <li>{@code info} —— 显示模组自身信息及全部已注册模块概览 / 指定模块详情</li>
 *   <li>各模块通过 {@link ModuleCommandRegistrar} 登记的命令节点（如 debugger 的 {@code /inf dbg}）</li>
 * </ul>
 *
 * <p>模块命令节点由 {@link ModuleRegistry#register(IModule)} 在模块登记时统一挂入登记器，
 * 本类仅消费登记结果，不自行遍历模块。</p>
 */
public final class InfrastructureCommands {

    private InfrastructureCommands() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            LiteralArgumentBuilder<FabricClientCommandSource> root = ClientCommandManager.literal("inf");
            root.then(buildConfigCommand());
            root.then(buildInfoCommand());
            // 挂载各模块登记的命令节点（登记已在 ModuleRegistry.register 时完成）
            for (LiteralArgumentBuilder<FabricClientCommandSource> node : ModuleCommandRegistrar.getAllNodes()) {
                root.then(node);
            }
            dispatcher.register(root);
        });
    }

    // ==================== /inf config ====================

    private static LiteralArgumentBuilder<FabricClientCommandSource> buildConfigCommand() {
        var config = ClientCommandManager.literal("config");

        // /inf config get <module:Identifier> <path:greedyString>
        // 参考 debugger 的「IdentifierArgumentType + greedyString」结构：模块以 Identifier 标识，
        // 路径作为自由串（配置字段名含大写，不能整体作为 Identifier，故路径用 greedy 接收）。
        config.then(ClientCommandManager.literal("get")
                .then(ClientCommandManager.argument("module", IdentifierArgumentType.identifier())
                        .suggests(MODULE_ID_SUGGESTIONS)
                        .then(ClientCommandManager.argument("path", StringArgumentType.greedyString())
                                .suggests(CONFIG_PATH_SUGGESTIONS)
                                .executes(ctx -> configGet(ctx.getSource().getClient(),
                                        moduleId(ctx, "module"),
                                        StringArgumentType.getString(ctx, "path"))))));

        // /inf config set <module:Identifier> <assignments:greedyString>
        // assignments 形如 [path=value ...]，可多条以空白分隔（多条批量设置）。
        // 不再合并为单个 assignment 参数：分层补全与解析交由 core.cli 工具类完成。
        config.then(ClientCommandManager.literal("set")
                .then(ClientCommandManager.argument("module", IdentifierArgumentType.identifier())
                        .suggests(MODULE_ID_SUGGESTIONS)
                        .then(ClientCommandManager.argument("assignments", StringArgumentType.greedyString())
                                .suggests(CONFIG_ASSIGNMENT_SUGGESTIONS)
                                .executes(ctx -> configSet(ctx.getSource().getClient(),
                                        moduleId(ctx, "module"),
                                        StringArgumentType.getString(ctx, "assignments"))))));

        // /inf config reset <module:Identifier> <path:greedyString>
        config.then(ClientCommandManager.literal("reset")
                .then(ClientCommandManager.argument("module", IdentifierArgumentType.identifier())
                        .suggests(MODULE_ID_SUGGESTIONS)
                        .then(ClientCommandManager.argument("path", StringArgumentType.greedyString())
                                .suggests(CONFIG_PATH_SUGGESTIONS)
                                .executes(ctx -> configReset(ctx.getSource().getClient(),
                                        moduleId(ctx, "module"),
                                        StringArgumentType.getString(ctx, "path"))))));

        return config;
    }

    /** 从命令上下文取出 {@code <module>} 参数对应的模块 id（裸名即取 path 部分）。 */
    private static String moduleId(CommandContext<FabricClientCommandSource> ctx, String arg) {
        return ctx.getArgument(arg, Identifier.class).getPath();
    }

    /** 从命令上下文取出 {@code <module>} 参数对应的模块实例；未找到或模块无配置返回 null。 */
    private static IModule moduleOf(CommandContext<FabricClientCommandSource> ctx, String arg) {
        String id = moduleId(ctx, arg);
        return (id == null) ? null : ModuleRegistry.get(id);
    }

    /** 模块 id 为无命名空间的纯名称（如 {@code debugger}），原样使用；空值返回 null。 */
    private static String normalizeModuleId(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        return raw;
    }

    private static int configGet(net.minecraft.client.MinecraftClient client, String moduleId, String path) {
        IModule module = ModuleRegistry.get(normalizeModuleId(moduleId));
        if (module == null || module.getConfig() == null) {
            send(client, Text.translatable("billy-inf.msg.module_config_none",
                            moduleId == null ? "?" : moduleId)
                    .formatted(Formatting.RED));
            return 0;
        }
        Object config = module.getConfig();
        if (!ModuleConfigReflectionAccessor.hasPath(config, path)) {
            send(client, Text.translatable("billy-inf.msg.config_path_unknown",
                            moduleId + ":" + path)
                    .formatted(Formatting.RED));
            return 0;
        }
        Object value = ModuleConfigReflectionAccessor.getValue(config, path);
        Object def = ModuleConfigReflectionAccessor.getDefaultValue(config, path);
        MutableText out = Text.literal("")
                .append(Text.literal(moduleId + ":" + path)
                        .formatted(Formatting.GOLD))
                .append(Text.literal(" = ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(value)).formatted(Formatting.AQUA))
                .append(Text.literal("  (type: ").formatted(Formatting.DARK_GRAY))
                .append(Text.literal(ModuleConfigReflectionAccessor.getTypeName(config, path))
                        .formatted(Formatting.DARK_GRAY))
                .append(Text.literal(", default: ").formatted(Formatting.DARK_GRAY))
                .append(Text.literal(String.valueOf(def)).formatted(Formatting.DARK_GRAY))
                .append(Text.literal(")").formatted(Formatting.DARK_GRAY));
        send(client, out);
        return 1;
    }

    private static int configSet(net.minecraft.client.MinecraftClient client, String moduleId, String assignments) {
        IModule module = ModuleRegistry.get(normalizeModuleId(moduleId));
        if (module == null || module.getConfig() == null) {
            send(client, Text.translatable("billy-inf.msg.module_config_none",
                            moduleId == null ? "?" : moduleId)
                    .formatted(Formatting.RED));
            return 0;
        }
        Object config = module.getConfig();
        // 用 core.cli.ArgParser 把整串解析为若干 key[=value] 条目，支持批量设置
        List<ArgParser.Assignment> items = ArgParser.parseAssignments(assignments);
        if (items.isEmpty()) {
            send(client, Text.translatable("billy-inf.msg.config_set_usage").formatted(Formatting.RED));
            return 0;
        }
        int applied = 0;
        for (ArgParser.Assignment a : items) {
            if (!ModuleConfigReflectionAccessor.hasPath(config, a.key)) {
                send(client, Text.translatable("billy-inf.msg.config_path_unknown",
                                moduleId + ":" + a.key)
                        .formatted(Formatting.RED));
                continue;
            }
            if (!a.hasValue) {
                send(client, Text.translatable("billy-inf.msg.config_set_need_value",
                                moduleId + ":" + a.key)
                        .formatted(Formatting.RED));
                continue;
            }
            try {
                Object old = ModuleConfigReflectionAccessor.getValue(config, a.key);
                ModuleConfigReflectionAccessor.setValue(config, a.key, a.value);
                applied++;
                send(client, Text.translatable("billy-inf.msg.config_set",
                                Text.literal(moduleId + ":" + a.key).formatted(Formatting.GOLD),
                                Text.literal(String.valueOf(old)).formatted(Formatting.GRAY),
                                Text.literal(a.value).formatted(Formatting.GREEN)));
            } catch (ModuleConfigReflectionAccessor.ConfigAccessException e) {
                send(client, Text.translatable("billy-inf.msg.config_error",
                                moduleId + ":" + a.key, e.getMessage())
                        .formatted(Formatting.RED));
            }
        }
        if (applied > 0) {
            module.saveConfig();
        }
        return applied > 0 ? 1 : 0;
    }

    private static int configReset(net.minecraft.client.MinecraftClient client, String moduleId, String path) {
        IModule module = ModuleRegistry.get(normalizeModuleId(moduleId));
        if (module == null || module.getConfig() == null) {
            send(client, Text.translatable("billy-inf.msg.module_config_none",
                            moduleId == null ? "?" : moduleId)
                    .formatted(Formatting.RED));
            return 0;
        }
        Object config = module.getConfig();
        if (!ModuleConfigReflectionAccessor.hasPath(config, path)) {
            send(client, Text.translatable("billy-inf.msg.config_path_unknown",
                            moduleId + ":" + path)
                    .formatted(Formatting.RED));
            return 0;
        }
        try {
            Object old = ModuleConfigReflectionAccessor.getValue(config, path);
            ModuleConfigReflectionAccessor.resetValue(config, path);
            module.saveConfig();
            send(client, Text.translatable("billy-inf.msg.config_reset",
                            Text.literal(moduleId + ":" + path).formatted(Formatting.GOLD),
                            Text.literal(String.valueOf(old)).formatted(Formatting.GRAY),
                            Text.literal(String.valueOf(ModuleConfigReflectionAccessor.getValue(config, path)))
                                    .formatted(Formatting.GREEN)));
            return 1;
        } catch (ModuleConfigReflectionAccessor.ConfigAccessException e) {
            send(client, Text.translatable("billy-inf.msg.config_error",
                            moduleId + ":" + path, e.getMessage())
                    .formatted(Formatting.RED));
            return 0;
        }
    }

    /**
     * 配置路径补全（get / reset 的 {@code <path>} 参数）：基于当前 {@code <module>} 参数，
     * 用 {@link CliCompletion} 的层级模式按字典树向下钻取一层，支持含 {@code .} 的嵌套路径。
     */
    private static final SuggestionProvider<FabricClientCommandSource> CONFIG_PATH_SUGGESTIONS =
            CliCompletion.builder()
                    .keySource(ctx -> {
                        IModule m = moduleOf(ctx, "module");
                        return (m == null || m.getConfig() == null)
                                ? List.of() : ModuleConfigReflectionAccessor.listPaths(m.getConfig());
                    })
                    .build();

    /**
     * 配置赋值补全（set 的 {@code <assignments>} 参数）：层级模式 + assignment + multiple。
     * <ul>
     *   <li>逐层钻取配置路径，补全到叶子后追加 {@code =} 候选；</li>
     *   <li>通过 {@code valueProvider} 取得该路径的合法取值（bool/枚举/当前值）一并给出；</li>
     *   <li>多条 {@code path=value} 以空白分隔时自动循环补全。</li>
     * </ul>
     */
    private static final SuggestionProvider<FabricClientCommandSource> CONFIG_ASSIGNMENT_SUGGESTIONS =
            CliCompletion.builder()
                    .separators(".:")
                    .assignment(true)
                    .multiple(true)
                    .keySource(ctx -> {
                        IModule m = moduleOf(ctx, "module");
                        return (m == null || m.getConfig() == null)
                                ? List.of() : ModuleConfigReflectionAccessor.listPaths(m.getConfig());
                    })
                    .valueProvider((ctx, key) -> {
                        IModule m = moduleOf(ctx, "module");
                        if (m == null || m.getConfig() == null) return List.of();
                        return ModuleConfigReflectionAccessor.suggestValues(m.getConfig(), key);
                    })
                    .build();

    // ==================== /inf info ====================

    private static LiteralArgumentBuilder<FabricClientCommandSource> buildInfoCommand() {
        return ClientCommandManager.literal("info")
                // /inf info [moduleId] —— 可选模块 id 参数
                .then(ClientCommandManager.argument("moduleId", StringArgumentType.word())
                        .suggests(MODULE_ID_SUGGESTIONS)
                        .executes(ctx -> showModuleInfo(ctx.getSource().getClient(),
                                StringArgumentType.getString(ctx, "moduleId"))))
                // /inf info —— 无参数，显示自身 + 全部模块概览
                .executes(ctx -> showSelfInfo(ctx.getSource().getClient()));
    }

    private static final SuggestionProvider<FabricClientCommandSource> MODULE_ID_SUGGESTIONS =
            (ctx, builder) -> {
                String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
                for (IModule m : ModuleRegistry.getAll()) {
                    String id = m.getId();
                    if (id.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                        builder.suggest(id);
                    }
                }
                return builder.buildFuture();
            };

    private static int showSelfInfo(net.minecraft.client.MinecraftClient client) {
        String version = FabricLoader.getInstance()
                .getModContainer(InfrastructureMod.MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("?");
        MutableText out = Text.literal("");
        out = out.append(Text.translatable("billy-inf.msg.info_header")
                .formatted(Formatting.GOLD, Formatting.BOLD)).append("\n");
        out = out.append(Text.translatable("billy-inf.msg.info_version", version)
                .formatted(Formatting.GRAY)).append("\n");
        out = out.append(Text.translatable("billy-inf.msg.info_desc")
                .formatted(Formatting.GRAY)).append("\n");
        out = out.append(Text.translatable("billy-inf.msg.info_modules")
                .formatted(Formatting.YELLOW)).append("\n");
        if (ModuleRegistry.size() == 0) {
            out = out.append(Text.literal("  ")
                    .append(Text.translatable("billy-inf.msg.list_empty")
                            .formatted(Formatting.DARK_GRAY))).append("\n");
        } else {
            for (IModule m : ModuleRegistry.getAll()) {
                out = out.append(Text.literal("  - ")
                                .append(Text.literal(m.getId()).formatted(Formatting.AQUA))
                                .append(Text.literal(" (v" + m.getVersion() + ")").formatted(Formatting.GRAY))
                                .append(Text.literal(": ").formatted(Formatting.DARK_GRAY))
                                .append(m.getName().copy().formatted(Formatting.GRAY)))
                        .append("\n");
            }
        }
        send(client, out);
        return 1;
    }

    private static int showModuleInfo(net.minecraft.client.MinecraftClient client, String rawModuleId) {
        String moduleId = normalizeModuleId(rawModuleId);
        IModule module = (moduleId == null) ? null : ModuleRegistry.get(moduleId);
        if (module == null) {
            send(client, Text.translatable("billy-inf.msg.module_not_found",
                            rawModuleId == null ? "?" : rawModuleId)
                    .formatted(Formatting.RED));
            return 0;
        }
        MutableText out = Text.literal("");
        out = out.append(Text.literal(module.getId())
                .formatted(Formatting.GOLD, Formatting.BOLD)).append("\n");
        // 标签键必须与「整句」键区分：info_version 带 %s 占位符、info_desc 是完整句子，
        // 直接当标签用会渲染出 "版本：%s1.0.0" 与整句拼接的错乱文本
        out = out.append(Text.literal("  ")
                .append(Text.translatable("billy-inf.msg.info_version_label").formatted(Formatting.DARK_GRAY))
                .append(Text.literal(module.getVersion()).formatted(Formatting.GRAY))).append("\n");
        out = out.append(Text.literal("  ")
                .append(Text.translatable("billy-inf.msg.info_name").formatted(Formatting.DARK_GRAY))
                .append(module.getName().copy().formatted(Formatting.AQUA))).append("\n");
        out = out.append(Text.literal("  ")
                .append(Text.translatable("billy-inf.msg.info_desc_label").formatted(Formatting.DARK_GRAY))
                .append(module.getDescription().copy().formatted(Formatting.GRAY))).append("\n");

        // 贡献：命令
        out = out.append(Text.literal("  ")
                .append(Text.translatable("billy-inf.msg.info_contrib_commands").formatted(Formatting.YELLOW)))
                .append("\n");
        var literals = module.getCommandLiterals();
        if (literals == null || literals.isEmpty()) {
            out = out.append(Text.literal("    ")
                    .append(Text.translatable("billy-inf.msg.list_empty").formatted(Formatting.DARK_GRAY)))
                    .append("\n");
        } else {
            for (String lit : literals) {
                out = out.append(Text.literal("    - /inf " + lit)
                        .formatted(Formatting.AQUA)).append("\n");
            }
        }

        // 贡献：配置路径
        out = out.append(Text.literal("  ")
                .append(Text.translatable("billy-inf.msg.info_contrib_configs").formatted(Formatting.YELLOW)))
                .append("\n");
        Object config = module.getConfig();
        if (config == null) {
            out = out.append(Text.literal("    ")
                    .append(Text.translatable("billy-inf.msg.list_empty").formatted(Formatting.DARK_GRAY)))
                    .append("\n");
        } else {
            List<String> paths = new ArrayList<>(ModuleConfigReflectionAccessor.listPaths(config));
            if (paths.isEmpty()) {
                out = out.append(Text.literal("    ")
                        .append(Text.translatable("billy-inf.msg.list_empty").formatted(Formatting.DARK_GRAY)))
                        .append("\n");
            } else {
                for (String p : paths) {
                    out = out.append(Text.literal("    - " + module.getId() + ":" + p)
                            .formatted(Formatting.GRAY)).append("\n");
                }
            }
        }
        send(client, out);
        return 1;
    }

    // ==================== 辅助 ====================

    private static void send(net.minecraft.client.MinecraftClient client, Text msg) {
        if (client.player != null) {
            client.player.sendMessage(msg, false);
        }
    }
}
