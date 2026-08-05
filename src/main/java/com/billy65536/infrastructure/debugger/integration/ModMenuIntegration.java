package com.billy65536.infrastructure.debugger.integration;

import com.billy65536.infrastructure.debugger.config.DebugToolsConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * ModMenu API 集成入口。
 *
 * <p>实现 ModMenuApi 接口，使得 Billy's Debug Tools 在 ModMenu 模组列表中
 * 显示「设置」按钮，点击后跳转到主配置界面（含通用设置与调试特性两个分类）。</p>
 *
 * <p>仅在 ModMenu 被加载时生效（通过 fabric.mod.json 中的 modmenu entrypoint）。
 * ModMenu 为可选依赖，缺失时本类不会被加载。</p>
 */
public class ModMenuIntegration implements ModMenuApi {

    /** 提供配置界面工厂，委托给 {@link DebugToolsConfigScreen}。 */
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return DebugToolsConfigScreen::create;
    }
}
