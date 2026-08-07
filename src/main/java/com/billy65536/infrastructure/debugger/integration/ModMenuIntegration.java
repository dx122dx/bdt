package com.billy65536.infrastructure.debugger.integration;

import com.billy65536.infrastructure.core.gui.CompositeConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import net.minecraft.client.gui.screen.Screen;

/**
 * ModMenu API 集成入口。
 *
 * <p>实现 ModMenuApi 接口，使得 Billy's Mod infrastructure 在 ModMenu 模组列表中
 * 显示「设置」按钮，点击后打开复合配置总览界面
 * （{@link CompositeConfigScreen}），按模块分组展示全部配置入口。</p>
 *
 * <p>仅在 ModMenu 被加载时生效（通过 fabric.mod.json 中的 modmenu entrypoint）。
 * ModMenu 为可选依赖，缺失时本类不会被加载。</p>
 */
public class ModMenuIntegration implements ModMenuApi {

    /** 配置界面工厂：打开复合配置总览屏。 */
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return (ConfigScreenFactory<Screen>) CompositeConfigScreen::create;
    }
}
