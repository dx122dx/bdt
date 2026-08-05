package com.billy65536.infrastructure.debugger.core.feature;

import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 调试特性扩展点。
 *
 * <p>Feature 表示可开关的持续性调试功能，通过 {@link FeatureRegistry} 注册后，
 * 可由命令 {@code /inf dbg feat about|enable|disable <id>} 查询与切换，
 * 启用状态持久化到配置文件，重启游戏后保持。</p>
 *
 * <p>每个特性有三个属性：id（唯一不变，用于注册和命令选择）、
 * name（本地化显示名）、description（本地化描述）。</p>
 */
public interface IDebugFeature {

    /** 唯一标识符，不可变，用于注册和命令选择。 */
    Identifier getId();

    /** 显示名称，用于列表与 GUI 展示。 */
    Text getName();

    /** 描述文本，用于悬停提示与帮助信息。 */
    Text getDescription();

    /**
     * 无持久化记录时采用的初始启用状态。
     *
     * <p>默认为 false：调试特性应当默认关闭，避免意外影响正常游玩。</p>
     */
    default boolean isDefaultEnabled() {
        return false;
    }

    /**
     * 特性被启用时的回调，用于挂载事件监听或渲染钩子。
     *
     * <p>注册阶段若判定特性应处于启用状态，也会调用本方法。
     * 实现必须幂等：框架保证状态未变化时不重复触发，但不保证外部调用者的行为。</p>
     */
    default void onEnable() {}

    /**
     * 特性被禁用时的回调，用于卸载事件监听或渲染钩子。
     *
     * <p>实现必须幂等，理由同 {@link #onEnable()}。</p>
     */
    default void onDisable() {}
}
