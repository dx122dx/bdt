package com.billy65536.infrastructure.security.config;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;

/**
 * 安全模块自身的固定配置项（AutoConfig 模型），对应 {@code security:config} 配置段。
 *
 * <p>持久化到 {@code config/infrastructure-security.json}，JSON 结构与本类字段
 * 一一对应。两类开关都受默认锁（{@code ConfigLocker}）保护：正常应保持默认值。</p>
 */
@Config(name = "infrastructure-security")
public class SecurityConfig implements ConfigData {

    /** 是否允许客户端绕过安全约束进行调试；受默认锁保护，正常应保持 {@code false}。 */
    @ConfigEntry.Gui.Tooltip
    public boolean allowDebugOverride = false;

    /**
     * 是否允许外部来源（调试动作 / 服务端指令）经 Override 补丁覆盖安全配置；
     * 受默认锁保护，进入多人服务器后锁定为 {@code false}（熔断），防止被外部指令自我解锁。
     */
    @ConfigEntry.Gui.Tooltip
    public boolean allowPolicyOverride = true;
}
