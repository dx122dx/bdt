package com.billy65536.chunkscanner.debugger.integration;

import com.billy65536.chunkscanner.debugger.config.FeatureConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * ModMenu API 集成入口。
 *
 * <p>实现 ModMenuApi 接口，使得 ChunkScanner Debugger 在 ModMenu 模组列表中
 * 显示「设置」按钮，点击后跳转到调试特性开关界面。</p>
 *
 * <p>仅在 ModMenu 被加载时生效（通过 fabric.mod.json 中的 modmenu entrypoint）。
 * ModMenu 为可选依赖，缺失时本类不会被加载。</p>
 */
public class ModMenuIntegration implements ModMenuApi {

    /** 提供配置界面工厂，委托给 {@link FeatureConfigScreen}。 */
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return FeatureConfigScreen::create;
    }
}
