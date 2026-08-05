package com.billy65536.chunkscanner.debugger.core.action;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 调试动作扩展点。
 *
 * <p>Action 表示一次性执行的调试操作，通过 {@link ActionRegistry} 注册后，
 * 可由命令 {@code /cs dbg action <id> [args...]} 触发。</p>
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
}
