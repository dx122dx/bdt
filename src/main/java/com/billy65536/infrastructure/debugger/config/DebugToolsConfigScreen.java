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
 * 模组主配置界面：ModMenu 与 {@code /inf dbg gui} 的统一入口。
 *
 * <p>包含两个分类：</p>
 * <ul>
 *   <li><b>通用设置</b>——{@link DebuggerConfig} 的固定配置项；</li>
 *   <li><b>调试特性</b>——运行时注册的 Feature 开关，数量由 {@link FeatureRegistry} 决定。</li>
 * </ul>
 *
 * <p>不使用 {@code AutoConfig.getConfigScreen()}：它返回已构建完成的 Screen，
 * 内部 {@code ConfigBuilder} 不对外暴露，无法追加运行时动态生成的 Feature 分类。
 * 因此改为完全手工构建，AutoConfig 仅保留持久化职责。</p>
 *
 * <p><b>双持久化隔离（硬约束）</b>：两个分类分别写入
 * {@code config/infrastructure.json} 与 {@code config/infrastructure-features.json}。
 * 动态 Feature 映射绝不可并入 AutoConfig 的文件——其
 * {@code GsonConfigSerializer} 以静态类结构为准反序列化，未知字段会被静默丢弃，
 * 导致每次保存都清空。</p>
 */
public final class DebugToolsConfigScreen {

    private DebugToolsConfigScreen() {}

    /**
     * 创建配置界面。
     *
     * @param parent 返回时的父界面
     */
    public static Screen create(Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Text.translatable("infrastructure.gui.title"));

        ConfigEntryBuilder entryBuilder = builder.entryBuilder();

        buildGeneralCategory(builder, entryBuilder);
        buildFeaturesCategory(builder, entryBuilder);

        // 全部条目的 saveConsumer 执行完毕后，两套配置各自落盘一次
        builder.setSavingRunnable(() -> {
            DebuggerConfigLoader.save();
            FeatureStateStore.save();
        });

        return builder.build();
    }

    /**
     * 通用设置分类：{@link DebuggerConfig} 的固定字段。
     *
     * <p>字段数量极少且属于框架自身配置，逐条显式列出比反射更清晰，
     * 也不会因反射失败而静默丢条目。新增字段时手工补一段即可。</p>
     *
     * <p>注意：{@code DebuggerConfigLoader.get()} 的返回值不可缓存——AutoConfig 在
     * {@code load()} 时会替换内部实例，因此读取初值与写回都各自重新取一次。</p>
     */
    private static void buildGeneralCategory(ConfigBuilder builder, ConfigEntryBuilder entryBuilder) {
        ConfigCategory category = builder.getOrCreateCategory(
                Text.translatable("infrastructure.gui.category.general"));

        category.addEntry(entryBuilder
                .startBooleanToggle(Text.translatable("infrastructure.gui.option.verbose_logging"),
                        DebuggerConfigLoader.get().verboseLogging)
                .setDefaultValue(false)
                .setTooltip(Text.translatable("infrastructure.gui.option.verbose_logging.desc"))
                .setSaveConsumer(value -> DebuggerConfigLoader.get().verboseLogging = value)
                .build());

        category.addEntry(entryBuilder
                .startBooleanToggle(Text.translatable("infrastructure.gui.option.show_action_stack_trace"),
                        DebuggerConfigLoader.get().showActionStackTrace)
                .setDefaultValue(false)
                .setTooltip(Text.translatable("infrastructure.gui.option.show_action_stack_trace.desc"))
                .setSaveConsumer(value -> DebuggerConfigLoader.get().showActionStackTrace = value)
                .build());
    }

    /**
     * 调试特性分类：遍历注册表动态生成开关。
     *
     * <p>用 {@code setEnabledDeferred} 延迟应用，由 {@code savingRunnable} 统一落盘，
     * 避免逐条目 I/O。</p>
     */
    private static void buildFeaturesCategory(ConfigBuilder builder, ConfigEntryBuilder entryBuilder) {
        ConfigCategory category = builder.getOrCreateCategory(
                Text.translatable("infrastructure.gui.category.features"));

        if (FeatureRegistry.size() == 0) {
            category.addEntry(entryBuilder
                    .startTextDescription(Text.translatable("infrastructure.gui.no_features"))
                    .build());
            return;
        }

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
}
