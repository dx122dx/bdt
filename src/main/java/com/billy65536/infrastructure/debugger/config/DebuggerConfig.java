package com.billy65536.infrastructure.debugger.config;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;

/**
 * 调试模组自身的固定配置项（AutoConfig 模型）。
 *
 * <p>持久化到 {@code config/infrastructure.json}，JSON 结构与本类字段
 * 一一对应。</p>
 *
 * <p>注意：调试特性（Feature）的启用状态<b>不在此处</b>——其数量由运行时注册决定，
 * 无法用静态字段表达，改由 {@link FeatureStateStore} 独立持久化。</p>
 */
@Config(name = "infrastructure")
public class DebuggerConfig implements ConfigData {

    /** 是否输出框架的详细日志（注册明细、动作执行轨迹等）。 */
    @ConfigEntry.Gui.Tooltip
    public boolean verboseLogging = false;

    /** 动作执行失败时，是否在聊天中附加异常堆栈摘要。 */
    @ConfigEntry.Gui.Tooltip
    public boolean showActionStackTrace = false;
}
