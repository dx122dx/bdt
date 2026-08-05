package com.billy65536.infrastructure.core.module;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.billy65536.infrastructure.InfrastructureMod;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

/**
 * 模块命令统一登记器（静态单例）。
 *
 * <p>职责：在 {@link ModuleRegistry#register(IModule)} 时，从模块的
 * {@link IModule#buildCommands()} 取出命令子树，按 {@link IModule#getCommandLiterals()}
 * 声明的字面量登记到本器。{@code InfrastructureCommands.register()} 构建 {@code /inf} 根命令时
 * 仅从本器取出已登记的节点挂载，不再自行遍历模块——即「命令注册统一在模块注册流程内完成」。</p>
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>登记键为<strong>命令字面量字符串</strong>（如 {@code "dbg"}），而非模块 id。
 *       这保证挂载到 {@code /inf} 根的是 {@code /inf dbg ...} 而非 {@code /inf <moduleId> ...}，
 *       与用户「通过 {@code /inf xxx} 访问」的预期一致。</li>
 *   <li>同一字面量重复登记会覆盖（后注册的模块胜出），与注册表覆盖语义一致。</li>
 *   <li>模块无命令（{@code buildCommands()} 返回 null）或无字面量声明时跳过登记。</li>
 * </ul>
 */
public final class ModuleCommandRegistrar {

    private static final Map<String, LiteralArgumentBuilder<FabricClientCommandSource>> commandNodes =
            new LinkedHashMap<>();

    private ModuleCommandRegistrar() {}

    /**
     * 登记模块的命令子树。从 {@link IModule#buildCommands()} 取到节点后，
     * 按 {@link IModule#getCommandLiterals()} 中声明的字面量注册。
     *
     * <p>约定：{@code getCommandLiterals()} 中的字面量必须与 {@code buildCommands()}
     * 返回的节点顶层 literal 一致。登记键<strong>只取节点自身的 literal</strong>——
     * 根命令挂载走 {@code root.then(node)}，Brigadier 按节点自身 literal 建树，
     * 以声明列表为键会登记出实际不存在的命令（并让同一节点被重复挂载）。
     * 声明列表因此仅用于一致性校验：不匹配时告警，便于模块作者及时发现。</p>
     *
     * @param module 已登记的模块实例
     */
    public static void register(IModule module) {
        if (module == null) return;
        LiteralArgumentBuilder<FabricClientCommandSource> node = module.buildCommands();
        if (node == null) return;

        String actual = node.getLiteral();
        if (actual == null || actual.isEmpty()) {
            InfrastructureMod.LOGGER.warn(
                    "Module {} returned a command node without a literal name, ignored", module.getId());
            return;
        }
        Collection<String> literals = module.getCommandLiterals();
        if (literals != null && !literals.isEmpty() && !literals.contains(actual)) {
            InfrastructureMod.LOGGER.warn(
                    "Module {} declares command literals {} but its node literal is '{}'; "
                            + "only '/inf {}' will be mounted", module.getId(), literals, actual, actual);
        }
        registerNode(actual, node, module.getId());
    }

    private static void registerNode(String literal,
            LiteralArgumentBuilder<FabricClientCommandSource> node, String moduleId) {
        if (commandNodes.containsKey(literal)) {
            InfrastructureMod.LOGGER.warn(
                    "Module command literal '{}' (from {}) is already registered, overwriting", literal, moduleId);
        }
        commandNodes.put(literal, node);
        InfrastructureMod.LOGGER.info("Registered module command node: /inf {} (module {})", literal, moduleId);
    }

    /** 取出所有已登记模块命令节点（只读，按登记顺序），供根命令挂载。 */
    public static Collection<LiteralArgumentBuilder<FabricClientCommandSource>> getAllNodes() {
        return Collections.unmodifiableCollection(commandNodes.values());
    }

    /** 已登记命令节点数量。 */
    public static int size() {
        return commandNodes.size();
    }
}
