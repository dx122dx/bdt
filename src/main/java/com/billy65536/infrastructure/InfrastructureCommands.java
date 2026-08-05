package com.billy65536.infrastructure;

import java.util.Map;

import com.billy65536.infrastructure.debugger.DebuggerCommands;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
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
 * 提供两个子命令：{@code dbg}（调试子模块命令树，见 {@link DebuggerCommands}）
 * 与 {@code info}（显示模组版本与子模块清单）。</p>
 */
public final class InfrastructureCommands {

    private InfrastructureCommands() {}

    /** 子模块清单：子模块标识 → 人类可读描述。 */
    private static final Map<String, String> SUBMODULES = Map.of(
            "debugger", "Billy's Debug Tools — debug action / feature framework（调试动作与特性框架）"
    );

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            LiteralArgumentBuilder<FabricClientCommandSource> root = ClientCommandManager.literal("inf");
            root.then(DebuggerCommands.buildDbgCommands());
            root.then(buildInfoCommand());
            dispatcher.register(root);
        });
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> buildInfoCommand() {
        return ClientCommandManager.literal("info")
                .executes(ctx -> {
                    String version = FabricLoader.getInstance()
                            .getModContainer(InfrastructureMod.MOD_ID)
                            .map(c -> c.getMetadata().getVersion().getFriendlyString())
                            .orElse("?");
                    MutableText out = Text.literal("");
                    out = out.append(Text.translatable("billy-inf.msg.info_header")
                            .formatted(Formatting.GOLD, Formatting.BOLD)).append("\n");
                    out = out.append(Text.translatable("billy-inf.msg.info_version", version)
                            .formatted(Formatting.GRAY)).append("\n");
                    out = out.append(Text.translatable("billy-inf.msg.info_modules")
                            .formatted(Formatting.YELLOW)).append("\n");
                    for (Map.Entry<String, String> e : SUBMODULES.entrySet()) {
                        out = out.append(Text.literal("  - " + e.getKey() + ": ")
                                        .formatted(Formatting.AQUA))
                                .append(Text.literal(e.getValue()).formatted(Formatting.GRAY))
                                .append("\n");
                    }
                    ctx.getSource().sendFeedback(out);
                    return 1;
                });
    }
}
