package com.billy65536.infrastructure.debugger.config;

/**
 * {@code debugger:feature} 配置段的描述符占位配置对象。
 *
 * <p>调试特性的启用状态由 {@link FeatureStateStore} 以动态 {@code Map<Identifier, Boolean>}
 * 持久化（数量由运行时注册决定，无法用静态字段表达），因此本占位类<b>不承载任何字段</b>。
 * 它仅作为 {@link com.billy65536.infrastructure.core.config.ConfigDescriptor} 所需的
 * 配置对象句柄，使 {@code debugger:feature} 能以独立配置段身份接入框架
 * （出现在 {@code /inf info debugger} 与 {@code /inf config gui debugger:feature}），
 * 实际读写与展示均通过其专属 GUI（{@link DebuggerFeaturesScreen}）完成。</p>
 */
public final class DebuggerFeatureConfig { }
