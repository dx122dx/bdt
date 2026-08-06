package com.billy65536.infrastructure.debugger.config;

import com.billy65536.infrastructure.debugger.core.feature.FeatureRegistry;
import com.billy65536.infrastructure.debugger.core.feature.IDebugFeature;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 调试特性开关的独立配置界面（对应 {@code debugger:feature} 配置段）。
 *
 * <p>遍历 {@link FeatureRegistry} 动态生成每个特性的布尔开关；保存时先把各开关的
 * 待定值写入注册表（{@code setEnabledDeferred}），再由 {@link FeatureStateStore#save()}
 * 统一落盘。</p>
 */
public final class DebuggerFeaturesScreen {

    private DebuggerFeaturesScreen() {}

    /**
     * 创件特性配置界面。
     *
     * @param parent 返回时的父界面
     */
    public static Screen create(Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Text.translatable("infrastructure.gui.category.features"));

        ConfigEntryBuilder entryBuilder = builder.entryBuilder();
        ConfigCategory category = builder.getOrCreateCategory(
                Text.translatable("infrastructure.gui.category.features"));

        if (FeatureRegistry.size() == 0) {
            category.addEntry(entryBuilder
                    .startTextDescription(Text.translatable("infrastructure.gui.no_features"))
                    .build());
        } else {
            for (IDebugFeature feature : FeatureRegistry.getAll()) {
                Identifier id = feature.getId();
                category.addEntry(entryBuilder
                        .startBooleanToggle(feature.getName(), FeatureRegistry.isEnabled(id))
                        .setDefaultValue(feature.isDefaultEnabled())
                        .setTooltip(feature.getDescription())
                        .setSaveConsumer(value -> FeatureRegistry.setEnabledDeferred(id, value))
                        .build());
            }
        }

        builder.setSavingRunnable(FeatureStateStore::save);

        return builder.build();
    }
}
