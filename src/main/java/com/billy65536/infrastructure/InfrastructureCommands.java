package com.billy65536.infrastructure;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.billy65536.infrastructure.core.cli.ArgParser;
import com.billy65536.infrastructure.core.cli.CliCompletion;
import com.billy65536.infrastructure.core.config.ConfigDescriptor;
import com.billy65536.infrastructure.core.config.ConfigManager;
import com.billy65536.infrastructure.core.module.IModule;
import com.billy65536.infrastructure.core.module.ModuleCommandRegistrar;
import com.billy65536.infrastructure.core.module.ModuleRegistry;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * {@code /inf} 根命令的注册与构建。
 *
 * <p>根命令为 {@code /inf}（亦可作为 {@code /billy-inf:inf} 调用，两者等价）。
 * 提供以下子命令：</p>
 * <ul>
 *   <li>{@code config} —— 模块配置统一访问（{@code get|set|reset|reload|gui <module:path>}）</li>
 *   <li>{@code info} —— 显示模组自身信息及全部已注册模块概览 / 指定模块详情</li>
 *   <li>各模块通过 {@link ModuleCommandRegistrar} 登记的命令节点（如 debugger 的 {@code /inf dbg}）</li>
 * </ul>
 *
 * <p>模块命令节点由 {@link ModuleRegistry#register(IModule)} 在模块登记时统一挂入登记器，
 * 本类仅消费登记结果，不自行遍历模块。</p>
 *
 * <p>{@link #register()} 可以在模块发现之前调用：命令树在
 * {@link ClientCommandRegistrationCallback} 触发时（进入世界）才构建，届时模块已由
 * {@code CLIENT_STARTED} 完成发现；回调内仍会调用一次幂等的
 * {@link ModuleRegistry#discover()} 作为兜底。</p>
 */
public final class InfrastructureCommands {

    private InfrastructureCommands() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            // 兜底：模块发现正常在 CLIENT_STARTED 完成；若该事件因故未触发
            // （集成测试 / 非常规启动流程），此处补一次幂等发现，保证模块命令不缺失
            ModuleRegistry.discover();
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

        // /inf config get <path:greedyString>
        // path 形如 module:id/field.path 或 module:field.path（id==module 省略）。
        config.then(ClientCommandManager.literal("get")
                .then(ClientCommandManager.argument("path", StringArgumentType.greedyString())
                        .suggests(CONFIG_PATH_SUGGESTIONS)
                        .executes(ctx -> configGet(ctx.getSource().getClient(),
                                StringArgumentType.getString(ctx, "path")))));

        // /inf config set <assignments:greedyString>
        // assignments 形如 [module:id/field=value ...]，可多条以空白分隔（批量设置）。
        config.then(ClientCommandManager.literal("set")
                .then(ClientCommandManager.argument("assignments", StringArgumentType.greedyString())
                        .suggests(CONFIG_ASSIGNMENT_SUGGESTIONS)
                        .executes(ctx -> configSet(ctx.getSource().getClient(),
                                StringArgumentType.getString(ctx, "assignments")))));

        // /inf config reset <path:greedyString>
        config.then(ClientCommandManager.literal("reset")
                .then(ClientCommandManager.argument("path", StringArgumentType.greedyString())
                        .suggests(CONFIG_PATH_SUGGESTIONS)
                        .executes(ctx -> configReset(ctx.getSource().getClient(),
                                StringArgumentType.getString(ctx, "path")))));

        // /inf config reload [config_id] —— 重新加载模块配置（含锁定值重放）
        // config_id 形如 module 或 module:id（缺省重载全部模块）
        config.then(ClientCommandManager.literal("reload")
                .executes(ctx -> configReload(ctx.getSource().getClient(), null))
                .then(ClientCommandManager.argument("configId", StringArgumentType.greedyString())
                        .suggests(CONFIG_ID_SUGGESTIONS)
                        .executes(ctx -> configReload(ctx.getSource().getClient(),
                                StringArgumentType.getString(ctx, "configId")))));

        // /inf config gui [config_id] —— 打开模块配置 GUI
        config.then(ClientCommandManager.literal("gui")
                .executes(ctx -> configGui(ctx.getSource().getClient(), null))
                .then(ClientCommandManager.argument("configId", StringArgumentType.greedyString())
                        .suggests(CONFIG_ID_SUGGESTIONS)
                        .executes(ctx -> configGui(ctx.getSource().getClient(),
                                StringArgumentType.getString(ctx, "configId")))));

        return config;
    }

    /** 模块 id 为无命名空间的纯名称（如 {@code debugger}），原样使用；空值返回 null。 */
    private static String normalizeModuleId(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        return raw;
    }

    private static int configGet(net.minecraft.client.MinecraftClient client, String fullPath) {
        try {
            Object value = ConfigManager.getValue(fullPath);
            Object def = ConfigManager.getDefaultValue(fullPath);
            MutableText out = Text.literal("")
                    .append(Text.literal(fullPath).formatted(Formatting.GOLD))
                    .append(Text.literal(" = ").formatted(Formatting.GRAY))
                    .append(Text.literal(String.valueOf(value)).formatted(Formatting.AQUA))
                    .append(Text.literal("  (type: ").formatted(Formatting.DARK_GRAY))
                    .append(Text.literal(ConfigManager.getTypeName(
                                    resolveDescriptor(fullPath), dotPathOf(fullPath)))
                            .formatted(Formatting.DARK_GRAY))
                    .append(Text.literal(", default: ").formatted(Formatting.DARK_GRAY))
                    .append(Text.literal(String.valueOf(def)).formatted(Formatting.DARK_GRAY))
                    .append(Text.literal(")").formatted(Formatting.DARK_GRAY));
            send(client, out);
            return 1;
        } catch (ConfigManager.ConfigAccessException e) {
            send(client, Text.translatable("billy-inf.msg.config_error", fullPath, e.getMessage())
                    .formatted(Formatting.RED));
            return 0;
        }
    }

    private static int configSet(net.minecraft.client.MinecraftClient client, String assignments) {
        // 用 core.cli.ArgParser 把整串解析为若干 key[=value] 条目，支持批量设置
        List<ArgParser.Assignment> items = ArgParser.parseAssignments(assignments);
        if (items.isEmpty()) {
            send(client, Text.translatable("billy-inf.msg.config_set_usage").formatted(Formatting.RED));
            return 0;
        }
        int applied = 0;
        for (ArgParser.Assignment a : items) {
            try {
                Object old = ConfigManager.getValue(a.key);
                ConfigManager.setValue(a.key, a.value);
                applied++;
                send(client, Text.translatable("billy-inf.msg.config_set",
                                Text.literal(a.key).formatted(Formatting.GOLD),
                                Text.literal(String.valueOf(old)).formatted(Formatting.GRAY),
                                Text.literal(a.value).formatted(Formatting.GREEN)));
            } catch (ConfigManager.ConfigAccessException e) {
                send(client, Text.translatable("billy-inf.msg.config_error",
                                a.key, e.getMessage()).formatted(Formatting.RED));
            }
        }
        if (applied > 0) {
            saveModuleOfPath(client, items.get(0).key);
        }
        return applied > 0 ? 1 : 0;
    }

    private static int configReset(net.minecraft.client.MinecraftClient client, String fullPath) {
        try {
            Object old = ConfigManager.getValue(fullPath);
            ConfigManager.resetValue(fullPath);
            saveModuleOfPath(client, fullPath);
            send(client, Text.translatable("billy-inf.msg.config_reset",
                            Text.literal(fullPath).formatted(Formatting.GOLD),
                            Text.literal(String.valueOf(old)).formatted(Formatting.GRAY),
                            Text.literal(String.valueOf(ConfigManager.getValue(fullPath)))
                                    .formatted(Formatting.GREEN)));
            return 1;
        } catch (ConfigManager.ConfigAccessException e) {
            send(client, Text.translatable("billy-inf.msg.config_error",
                            fullPath, e.getMessage()).formatted(Formatting.RED));
            return 0;
        }
    }

    /**
     * /inf config reload [config_id] —— 重新加载模块配置。
     * 缺省 reload 全部已登记模块；指定 config_id 时仅 reload 该模块。
     * 重载后由 ConfigLocker.applyAll 重放锁定强制值（防绕过）。
     */
    private static int configReload(net.minecraft.client.MinecraftClient client, String configId) {
        if (configId == null || configId.isEmpty()) {
            for (IModule m : ModuleRegistry.getAll()) {
                m.saveConfig();
            }
            com.billy65536.infrastructure.core.security.server.ConfigLocker.applyAll(
                    allDescriptors());
            send(client, Text.translatable("billy-inf.msg.config_reloaded_all")
                    .formatted(Formatting.GREEN));
            return 1;
        }
        IModule module = ModuleRegistry.get(normalizeModuleId(configId));
        if (module == null) {
            send(client, Text.translatable("billy-inf.msg.module_not_found", configId)
                    .formatted(Formatting.RED));
            return 0;
        }
        module.saveConfig();
        com.billy65536.infrastructure.core.security.server.ConfigLocker.applyAll(
                module.getConfigDescriptors());
        send(client, Text.translatable("billy-inf.msg.config_reloaded", configId)
                .formatted(Formatting.GREEN));
        return 1;
    }

    /**
     * /inf config gui [config_id] —— 打开模块配置 GUI。
     * 缺省打开第一个含 GUI 回调的模块；指定 config_id 时打开该模块。
     */
    private static int configGui(net.minecraft.client.MinecraftClient client, String configId) {
        ConfigDescriptor target = null;
        if (configId == null || configId.isEmpty()) {
            for (IModule m : ModuleRegistry.getAll()) {
                for (ConfigDescriptor d : m.getConfigDescriptors()) {
                    if (d.openGui() != null) { target = d; break; }
                }
                if (target != null) break;
            }
        } else {
            IModule module = ModuleRegistry.get(normalizeModuleId(configId));
            if (module == null) {
                send(client, Text.translatable("billy-inf.msg.module_not_found", configId)
                        .formatted(Formatting.RED));
                return 0;
            }
            for (ConfigDescriptor d : module.getConfigDescriptors()) {
                if (d.openGui() != null) { target = d; break; }
            }
        }
        if (target == null) {
            send(client, Text.translatable("billy-inf.msg.config_no_gui",
                            configId == null ? "*" : configId).formatted(Formatting.RED));
            return 0;
        }
        if (target.openGuiOnClient()) {
            send(client, Text.translatable("billy-inf.msg.config_gui_opened",
                            target.path().toString()).formatted(Formatting.GREEN));
            return 1;
        }
        send(client, Text.translatable("billy-inf.msg.config_no_gui",
                        target.path().toString()).formatted(Formatting.RED));
        return 0;
    }

    /** 取某完整路径命中的描述符（供类型名展示）。 */
    private static ConfigDescriptor resolveDescriptor(String fullPath) {
        try {
            com.billy65536.infrastructure.core.config.ConfigPath cp =
                    com.billy65536.infrastructure.core.config.ConfigPath.parse(fullPath);
            IModule m = ModuleRegistry.get(cp.module());
            if (m == null) return null;
            for (ConfigDescriptor d : m.getConfigDescriptors()) {
                if (d.path().module().equals(cp.module()) && d.path().id().equals(cp.id())) {
                    return d;
                }
            }
        } catch (IllegalArgumentException ignored) {}
        return null;
    }

    /** 取完整路径中的字段点分路径（供类型名展示）。 */
    private static String dotPathOf(String fullPath) {
        try {
            return com.billy65536.infrastructure.core.config.ConfigPath.parse(fullPath).dotPath();
        } catch (IllegalArgumentException e) {
            return fullPath;
        }
    }

    /** set/reset 后持久化：按路径定位模块并 saveConfig。 */
    private static void saveModuleOfPath(net.minecraft.client.MinecraftClient client, String fullPath) {
        try {
            com.billy65536.infrastructure.core.config.ConfigPath cp =
                    com.billy65536.infrastructure.core.config.ConfigPath.parse(fullPath);
            IModule m = ModuleRegistry.get(cp.module());
            if (m != null) m.saveConfig();
        } catch (IllegalArgumentException ignored) {}
    }

    /** 全部已登记模块的全部描述符（供 reload all）。 */
    private static java.util.List<ConfigDescriptor> allDescriptors() {
        java.util.List<ConfigDescriptor> all = new java.util.ArrayList<>();
        for (IModule m : ModuleRegistry.getAll()) {
            all.addAll(m.getConfigDescriptors());
        }
        return all;
    }

    /**
     * 配置路径补全（get / reset / set 的 key 部分）：基于已输入前缀，
     * 用 {@link CliCompletion} 的层级模式向下钻取一层，支持含 {@code .} 的嵌套路径。
     */
    private static final SuggestionProvider<FabricClientCommandSource> CONFIG_PATH_SUGGESTIONS =
            CliCompletion.builder()
                    .keySource(ctx -> ConfigManager.suggestPaths(prefixOf(ctx, "path")))
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
                    .keySource(ctx -> ConfigManager.suggestPaths(prefixOf(ctx, "assignments")))
                    .valueProvider((ctx, key) -> {
                        ConfigDescriptor d = resolveDescriptor(key);
                        if (d == null) return List.of();
                        return ConfigManager.suggestValues(d, dotPathOf(key));
                    })
                    .build();

    /** config_id 补全（reload / gui 的 {@code <configId>} 参数）：列出全部模块 id。 */
    private static final SuggestionProvider<FabricClientCommandSource> CONFIG_ID_SUGGESTIONS =
            (ctx, builder) -> {
                String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
                for (IModule m : ModuleRegistry.getAll()) {
                    if (m.getId().toLowerCase(Locale.ROOT).startsWith(remaining)) {
                        builder.suggest(m.getId());
                    }
                }
                return builder.buildFuture();
            };

    /** 从命令上下文取某个 greedyString 参数的已输入前缀（用于补全）。 */
    private static String prefixOf(CommandContext<FabricClientCommandSource> ctx, String arg) {
        try {
            return StringArgumentType.getString(ctx, arg);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

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
        List<ConfigDescriptor> descriptors = module.getConfigDescriptors();
        if (descriptors.isEmpty()) {
            out = out.append(Text.literal("    ")
                    .append(Text.translatable("billy-inf.msg.list_empty").formatted(Formatting.DARK_GRAY)))
                    .append("\n");
        } else {
            boolean any = false;
            for (ConfigDescriptor d : descriptors) {
                for (String p : ConfigManager.listPaths(d)) {
                    any = true;
                    String full = com.billy65536.infrastructure.core.config.ConfigPath
                            .of(d.path().module(), d.path().id(), p).toUserString();
                    out = out.append(Text.literal("    - " + full)
                            .formatted(Formatting.GRAY)).append("\n");
                }
            }
            if (!any) {
                out = out.append(Text.literal("    ")
                        .append(Text.translatable("billy-inf.msg.list_empty").formatted(Formatting.DARK_GRAY)))
                        .append("\n");
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
