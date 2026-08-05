package com.billy65536.infrastructure;

import java.util.ArrayList;
import java.util.List;

import com.billy65536.infrastructure.core.module.IModule;
import com.billy65536.infrastructure.core.module.ModuleCommandRegistrar;
import com.billy65536.infrastructure.core.module.ModuleConfigReflectionAccessor;
import com.billy65536.infrastructure.core.module.ModuleRegistry;
import com.billy65536.infrastructure.debugger.DebuggerCommands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

/**
 * {@code /inf} 根命令的注册与构建。
 *
 * <p>根命令为 {@code /inf}（亦可作为 {@code /billy-inf:inf} 调用，两者等价）。
 * 提供以下子命令：</p>
 * <ul>
 *   <li>{@code dbg} —— 调试子模块命令树（见 {@link DebuggerCommands}）</li>
 *   <li>{@code config} —— 模块配置统一访问（{@code get|set|reset <moduleId:path>}）</li>
 *   <li>{@code info} —— 显示模组自身信息及全部已注册模块概览 / 指定模块详情</li>
 *   <li>各模块通过 {@link ModuleCommandRegistrar} 登记的命令节点（如 {@code /inf dbg} 之外的其它模块）</li>
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
            root.then(DebuggerCommands.buildDbgCommands());
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

        // /inf config get <moduleId:path>
        config.then(ClientCommandManager.literal("get")
                .then(ClientCommandManager.argument("target", StringArgumentType.greedyString())
                        .suggests(CONFIG_PATH_SUGGESTIONS)
                        .executes(ctx -> configGet(ctx.getSource().getClient(),
                                StringArgumentType.getString(ctx, "target")))));

        // /inf config set <moduleId:path> <value>
        config.then(ClientCommandManager.literal("set")
                .then(ClientCommandManager.argument("target", StringArgumentType.greedyString())
                        .suggests(CONFIG_PATH_SUGGESTIONS)
                        .then(ClientCommandManager.argument("value", StringArgumentType.greedyString())
                                .executes(ctx -> configSet(
                                        ctx.getSource().getClient(),
                                        StringArgumentType.getString(ctx, "target"),
                                        StringArgumentType.getString(ctx, "value"))))));

        // /inf config reset <moduleId:path>
        config.then(ClientCommandManager.literal("reset")
                .then(ClientCommandManager.argument("target", StringArgumentType.greedyString())
                        .suggests(CONFIG_PATH_SUGGESTIONS)
                        .executes(ctx -> configReset(ctx.getSource().getClient(),
                                StringArgumentType.getString(ctx, "target")))));

        return config;
    }

    /** 将 {@code <moduleId:path>} 拆解为模块 id 与配置路径。按最后一个冒号分割。 */
    private static String[] splitTarget(String target) {
        if (target == null) return new String[] { "", "" };
        int idx = target.lastIndexOf(':');
        if (idx < 0) return new String[] { target, "" }; // 无冒号：整串当作 path，moduleId 留空
        return new String[] { target.substring(0, idx), target.substring(idx + 1) };
    }

    /** 模块标识符归一到 billy-inf 命名空间（裸名 → billy-inf:裸名）。 */
    private static Identifier normalizeModuleId(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        int colon = raw.indexOf(':');
        if (colon >= 0) {
            // 显式带命名空间：原样保留（new Identifier 构造）
            return new Identifier(raw.substring(0, colon), raw.substring(colon + 1));
        }
        return InfrastructureMod.id(raw);
    }

    private static int configGet(net.minecraft.client.MinecraftClient client, String target) {
        String[] parts = splitTarget(target);
        Identifier moduleId = normalizeModuleId(parts[0]);
        String path = parts[1];
        IModule module = (moduleId == null) ? null : ModuleRegistry.get(moduleId);
        if (module == null || module.getConfig() == null) {
            send(client, Text.translatable("billy-inf.msg.module_config_none",
                            moduleId == null ? "?" : moduleId.toString())
                    .formatted(Formatting.RED));
            return 0;
        }
        Object config = module.getConfig();
        if (!ModuleConfigReflectionAccessor.hasPath(config, path)) {
            send(client, Text.translatable("billy-inf.msg.config_path_unknown",
                            moduleId.toString() + ":" + path)
                    .formatted(Formatting.RED));
            return 0;
        }
        Object value = ModuleConfigReflectionAccessor.getValue(config, path);
        Object def = ModuleConfigReflectionAccessor.getDefaultValue(config, path);
        MutableText out = Text.literal("")
                .append(Text.literal(moduleId.toString() + ":" + path)
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

    private static int configSet(net.minecraft.client.MinecraftClient client, String target, String value) {
        String[] parts = splitTarget(target);
        Identifier moduleId = normalizeModuleId(parts[0]);
        String path = parts[1];
        IModule module = (moduleId == null) ? null : ModuleRegistry.get(moduleId);
        if (module == null || module.getConfig() == null) {
            send(client, Text.translatable("billy-inf.msg.module_config_none",
                            moduleId == null ? "?" : moduleId.toString())
                    .formatted(Formatting.RED));
            return 0;
        }
        Object config = module.getConfig();
        if (!ModuleConfigReflectionAccessor.hasPath(config, path)) {
            send(client, Text.translatable("billy-inf.msg.config_path_unknown",
                            moduleId.toString() + ":" + path)
                    .formatted(Formatting.RED));
            return 0;
        }
        try {
            Object old = ModuleConfigReflectionAccessor.getValue(config, path);
            ModuleConfigReflectionAccessor.setValue(config, path, value);
            module.saveConfig();
            send(client, Text.translatable("billy-inf.msg.config_set",
                            Text.literal(moduleId.toString() + ":" + path).formatted(Formatting.GOLD),
                            Text.literal(String.valueOf(old)).formatted(Formatting.GRAY),
                            Text.literal(value).formatted(Formatting.GREEN)));
            return 1;
        } catch (ModuleConfigReflectionAccessor.ConfigAccessException e) {
            send(client, Text.translatable("billy-inf.msg.config_error",
                            moduleId.toString() + ":" + path, e.getMessage())
                    .formatted(Formatting.RED));
            return 0;
        }
    }

    private static int configReset(net.minecraft.client.MinecraftClient client, String target) {
        String[] parts = splitTarget(target);
        Identifier moduleId = normalizeModuleId(parts[0]);
        String path = parts[1];
        IModule module = (moduleId == null) ? null : ModuleRegistry.get(moduleId);
        if (module == null || module.getConfig() == null) {
            send(client, Text.translatable("billy-inf.msg.module_config_none",
                            moduleId == null ? "?" : moduleId.toString())
                    .formatted(Formatting.RED));
            return 0;
        }
        Object config = module.getConfig();
        if (!ModuleConfigReflectionAccessor.hasPath(config, path)) {
            send(client, Text.translatable("billy-inf.msg.config_path_unknown",
                            moduleId.toString() + ":" + path)
                    .formatted(Formatting.RED));
            return 0;
        }
        try {
            Object old = ModuleConfigReflectionAccessor.getValue(config, path);
            ModuleConfigReflectionAccessor.resetValue(config, path);
            module.saveConfig();
            send(client, Text.translatable("billy-inf.msg.config_reset",
                            Text.literal(moduleId.toString() + ":" + path).formatted(Formatting.GOLD),
                            Text.literal(String.valueOf(old)).formatted(Formatting.GRAY),
                            Text.literal(String.valueOf(ModuleConfigReflectionAccessor.getValue(config, path)))
                                    .formatted(Formatting.GREEN)));
            return 1;
        } catch (ModuleConfigReflectionAccessor.ConfigAccessException e) {
            send(client, Text.translatable("billy-inf.msg.config_error",
                            moduleId.toString() + ":" + path, e.getMessage())
                    .formatted(Formatting.RED));
            return 0;
        }
    }

    /** 配置目标（moduleId:path）补全：优先按已输入前缀匹配模块 id，再匹配路径。 */
    private static final SuggestionProvider<FabricClientCommandSource> CONFIG_PATH_SUGGESTIONS =
            (ctx, builder) -> {
                String remaining = builder.getRemaining().toLowerCase();
                for (IModule module : ModuleRegistry.getAll()) {
                    Object config = module.getConfig();
                    if (config == null) continue;
                    String moduleStr = module.getId().toString().toLowerCase();
                    if (!moduleStr.startsWith(remaining) && !remaining.startsWith(moduleStr)) continue;
                    for (String path : ModuleConfigReflectionAccessor.listPaths(config)) {
                        String full = module.getId().toString() + ":" + path;
                        if (full.toLowerCase().startsWith(remaining)) {
                            builder.suggest(full);
                        }
                    }
                }
                return builder.buildFuture();
            };

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
                String remaining = builder.getRemaining().toLowerCase();
                for (IModule m : ModuleRegistry.getAll()) {
                    String id = m.getId().toString();
                    if (id.toLowerCase().startsWith(remaining)) {
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
                                .append(Text.literal(m.getId().toString()).formatted(Formatting.AQUA))
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
        Identifier moduleId = normalizeModuleId(rawModuleId);
        IModule module = (moduleId == null) ? null : ModuleRegistry.get(moduleId);
        if (module == null) {
            send(client, Text.translatable("billy-inf.msg.module_not_found",
                            rawModuleId == null ? "?" : rawModuleId)
                    .formatted(Formatting.RED));
            return 0;
        }
        MutableText out = Text.literal("");
        out = out.append(Text.literal(module.getId().toString())
                .formatted(Formatting.GOLD, Formatting.BOLD)).append("\n");
        out = out.append(Text.literal("  ")
                .append(Text.translatable("billy-inf.msg.info_version").formatted(Formatting.DARK_GRAY))
                .append(Text.literal(module.getVersion()).formatted(Formatting.GRAY))).append("\n");
        out = out.append(Text.literal("  ")
                .append(Text.translatable("billy-inf.msg.info_name").formatted(Formatting.DARK_GRAY))
                .append(module.getName().copy().formatted(Formatting.AQUA))).append("\n");
        out = out.append(Text.literal("  ")
                .append(Text.translatable("billy-inf.msg.info_desc").formatted(Formatting.DARK_GRAY))
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
