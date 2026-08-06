package com.billy65536.infrastructure.debugger.core.action;

import java.util.List;

import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 调试动作扩展点。
 *
 * <p>Action 表示一次性执行的调试操作，通过 {@link ActionRegistry} 注册后，
 * 可由命令 {@code /inf dbg action run <id> [args...]} 触发。</p>
 *
 * <p>每个动作有三个属性：id（唯一不变，用于注册和命令选择）、
 * name（本地化显示名）、description（本地化描述）。</p>
 */
public interface IDebugAction {

    /** 唯一标识符，不可变，用于注册和命令选择。 */
    Identifier getId();

    /** 显示名称，用于列表与 GUI 展示。 */
    Text getName();

    /** 描述文本，用于悬停提示与帮助信息。 */
    Text getDescription();

    /**
     * 执行调试动作。
     *
     * <p>异常由命令层统一捕获并转为红色聊天反馈，实现方无需自行包裹 try-catch。</p>
     *
     * <p>动作在客户端主线程同步执行。若需要长耗时操作，实现方应自行决定是否
     * 切换到其他线程，框架不做隐式线程切换以免语义不明。</p>
     *
     * @param client 当前客户端实例
     * @param args   已完成引号感知分词的参数数组，可能为空数组但保证不为 null
     * @throws Exception 任何执行失败，将由命令层捕获并反馈给玩家
     */
    void execute(MinecraftClient client, String[] args) throws Exception;

    /**
     * 为当前正在输入的参数提供补全候选。
     *
     * <p>默认返回空列表（无补全）。实现方按 {@code args} 的长度判断当前位于第几个
     * 参数位，返回该位置的候选值即可——框架负责前缀过滤与拼接，实现方无需自行过滤。</p>
     *
     * <p>示例：{@code args = ["components.qshop"]} 表示用户已输入完整的第 1 个参数并
     * 正在输入第 2 个；{@code args = []} 表示正在输入第 1 个参数。</p>
     *
     * @param client 当前客户端实例
     * @param args   当前已完整输入的参数（不含正在输入的那一段），不为 null
     * @return 候选值列表，不应返回 null
     */
    default List<String> suggest(MinecraftClient client, String[] args) {
        return List.of();
    }

    /**
     * 可选：为 {@code [args...]} 参数提供<strong>层级化</strong>补全器，覆盖默认
     * {@link #suggest} 的扁平列表补全。
     *
     * <p>返回非 null 时，框架将直接委托该 {@link SuggestionProvider}（而非调用
     * {@link #suggest}），从而支持基于 {@code infrastructure.core.cli.CliCompletion}
     * 的按 {@code . : /} 分隔路径逐层钻取补全。返回 null（默认）则回退到
     * {@link #suggest} 返回的扁平候选列表。</p>
     *
     * <p>典型用法：配置路径类动作用
     * {@code CliCompletion.builder().separators(".:/").multiple(true)
     * .keySource(ctx -> ConfigManager.suggestPathsFull("")).build()} 实现逐层钻取。</p>
     *
     * @return 层级化补全器，或 null 表示使用默认 {@link #suggest}
     */
    default SuggestionProvider<FabricClientCommandSource> getArgsCompleter() {
        return null;
    }
}
