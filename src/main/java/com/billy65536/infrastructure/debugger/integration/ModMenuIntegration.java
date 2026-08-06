package com.billy65536.infrastructure.debugger.integration;

import com.billy65536.infrastructure.debugger.config.DebuggerConfig;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import me.shedaniel.autoconfig.AutoConfig;

/**
 * ModMenu API 集成入口。
 *
 * <p>实现 ModMenuApi 接口，使得 Billy's Mod infrastructure 在 ModMenu 模组列表中
 * 显示「设置」按钮，点击后跳转到 {@code debugger:config} 的 AutoConfig 原生配置界面。</p>
 *
 * TODO 改为复合配置界面
 * 
 * <p>仅在 ModMenu 被加载时生效（通过 fabric.mod.json 中的 modmenu entrypoint）。
 * ModMenu 为可选依赖，缺失时本类不会被加载。</p>
 */
public class ModMenuIntegration implements ModMenuApi {

    /** 提供配置界面工厂，暂时委托给 {@code debugger:config} 的 AutoConfig 界面。 */
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return (parent) -> AutoConfig.getConfigScreen(DebuggerConfig.class, parent).get();
    }
}
