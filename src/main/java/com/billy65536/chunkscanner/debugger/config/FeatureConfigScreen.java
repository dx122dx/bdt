package com.billy65536.chunkscanner.debugger.config;

import com.billy65536.chunkscanner.debugger.core.feature.FeatureRegistry;
import com.billy65536.chunkscanner.debugger.core.feature.IDebugFeature;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 调试特性开关配置界面。
 *
 * <p>特性数量由运行时注册决定，无法用 AutoConfig 的静态字段表达，
 * 因此改用 Cloth 的 {@link ConfigBuilder} 手工遍历 {@link FeatureRegistry}
 * 动态生成开关条目。</p>
 *
 * <p>保存时统一落盘一次，避免逐条目 I/O。</p>
 */
public final class FeatureConfigScreen {

    private FeatureConfigScreen() {}

    /**
     * 创建配置界面。
     *
     * @param parent 返回时的父界面
     */
    public static Screen create(Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Text.translatable("chunkscanner-debugger.gui.title"));

        ConfigEntryBuilder entryBuilder = builder.entryBuilder();
        ConfigCategory category = builder.getOrCreateCategory(
                Text.translatable("chunkscanner-debugger.gui.category.features"));

        if (FeatureRegistry.size() == 0) {
            category.addEntry(entryBuilder
                    .startTextDescription(Text.translatable("chunkscanner-debugger.gui.no_features"))
                    .build());
        } else {
            for (IDebugFeature feature : FeatureRegistry.getAll()) {
                Identifier id = feature.getId();
                category.addEntry(entryBuilder
                        .startBooleanToggle(feature.getName(), FeatureRegistry.isEnabled(id))
                        .setDefaultValue(feature.isDefaultEnabled())
                        .setTooltip(feature.getDescription())
                        // 延迟版本：仅更新内存与状态存储，由 savingRunnable 统一落盘
                        .setSaveConsumer(value -> FeatureRegistry.setEnabledDeferred(id, value))
                        .build());
            }
        }

        // 全部条目的 saveConsumer 执行完毕后统一持久化一次
        builder.setSavingRunnable(FeatureStateStore::save);

        return builder.build();
    }
}
