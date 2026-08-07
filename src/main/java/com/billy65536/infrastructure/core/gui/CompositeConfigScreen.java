package com.billy65536.infrastructure.core.gui;

import java.util.Collection;
import java.util.List;

import com.billy65536.infrastructure.InfrastructureMod;
import com.billy65536.infrastructure.core.config.ConfigDescriptor;
import com.billy65536.infrastructure.core.module.IModule;
import com.billy65536.infrastructure.core.module.ModuleRegistry;

import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.math.Rectangle;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * 复合配置总览界面：在一个 Cloth Config 屏幕内按模块分组展示全部配置入口。
 *
 * <p>布局自上而下：</p>
 * <ul>
 *   <li>顶部：最大字号的模组标题，下方小字显示模组版本；</li>
 *   <li>每个模块一个子分组（{@code SubCategory}）：
 *       分组首行是该模块的大字名称，其下小字为该模块的 id 与版本；</li>
 *   <li>模块内每个配置段一行：左侧 {@code mid:cid}（即 {@link ConfigDescriptor#path()} 的
 *       {@code targetString}），右侧一个「编辑」按钮，点击打开该段的 GUI
 *       （{@link ConfigDescriptor#openGuiOnClient()}）。</li>
 * </ul>
 *
 * <p>本界面作为统一的配置入口：ModMenu 的「设置」按钮与 {@code /inf config gui}（无参）均指向它。</p>
 */
public final class CompositeConfigScreen {

    private CompositeConfigScreen() {}

    /** 创建复合配置总览界面；{@code parent} 为返回时的父界面。 */
    public static Screen create(Screen parent) {
        String modVersion = FabricLoader.getInstance()
                .getModContainer(InfrastructureMod.MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("?");

        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Text.translatable("infrastructure.gui.composite.title"));

        ConfigEntryBuilder entryBuilder = builder.entryBuilder();
        ConfigCategory root = builder.getOrCreateCategory(
                Text.translatable("infrastructure.gui.composite.category"));

        // ----- 顶部：模组标题 + 版本 -----
        root.addEntry(entryBuilder
                .startTextDescription(Text.translatable(
                        "infrastructure.gui.composite.header",
                        Text.literal(InfrastructureMod.NAME).styled(s -> s.withBold(true)),
                        Text.literal(modVersion)))
                .build());

        // ----- 各模块分组 -----
        Collection<IModule> modules = ModuleRegistry.getAll();
        if (modules.isEmpty()) {
            root.addEntry(entryBuilder
                    .startTextDescription(Text.translatable("infrastructure.gui.composite.no_modules"))
                    .build());
        } else {
            for (IModule module : modules) {
                Text sub = Text.translatable(
                        "infrastructure.gui.composite.module_sub",
                        Text.literal(module.getId()),
                        Text.literal(module.getVersion()));
                root.addEntry(entryBuilder
                        .startSubCategory(Text.empty()
                                .append(module.getName().copy().styled(s -> s.withBold(true)))
                                .append(Text.literal("  "))
                                .append(sub.copy().styled(s -> s.withColor(0x888888))))
                        .build());

                List<ConfigDescriptor> descriptors = module.getConfigDescriptors();
                if (descriptors == null || descriptors.isEmpty()) {
                    root.addEntry(entryBuilder
                            .startTextDescription(Text.translatable("infrastructure.gui.composite.no_config"))
                            .build());
                } else {
                    for (ConfigDescriptor descriptor : descriptors) {
                        root.addEntry(new ConfigEntryRow(
                                Text.literal(descriptor.path().targetString()),
                                descriptor));
                    }
                }
            }
        }

        // 复合界面本身不做持久化；各段的保存由各段自己的 GUI 负责。
        builder.setSavingRunnable(() -> {});

        return builder.build();
    }

    /**
     * 单行配置段入口：左侧 {@code mid:cid} 标签，右侧「编辑」按钮。
     *
     * <p>按钮点击在主线程打开该配置段的 GUI（{@link ConfigDescriptor#openGuiOnClient()}）。
     * 无 GUI 回调的描述符，编辑按钮被禁用并提示不可用。</p>
     */
    private static final class ConfigEntryRow extends AbstractConfigListEntry<Void> {
        private final ConfigDescriptor descriptor;
        private final ButtonWidget button;

        ConfigEntryRow(Text label, ConfigDescriptor descriptor) {
            super(label, false);
            this.descriptor = descriptor;
            this.button = ButtonWidget.builder(
                            Text.translatable("infrastructure.gui.composite.edit"),
                            btn -> {
                                if (descriptor.openGui() != null) {
                                    descriptor.openGuiOnClient();
                                }
                            })
                    .size(60, 20)
                    .build();
            this.button.active = descriptor.openGui() != null;
        }

        @Override
        public Void getValue() {
            return null;
        }

        @Override
        public java.util.Optional<Void> getDefaultValue() {
            return java.util.Optional.empty();
        }

        @Override
        public void save() {}

        @Override
        public boolean isMouseInside(int mouseX, int mouseY, int x, int y, int entryWidth, int entryHeight) {
            return getEntryArea(x, y, entryWidth, entryHeight).contains(mouseX, mouseY);
        }

        @Override
        public void render(DrawContext graphics, int index, int y, int x, int entryWidth,
                int entryHeight, int mouseX, int mouseY, boolean isHovered, float delta) {
            MinecraftClient client = MinecraftClient.getInstance();
            int textY = y + entryHeight / 2 - client.textRenderer.fontHeight / 2;
            graphics.drawText(client.textRenderer, getFieldName(), x, textY, getPreferredTextColor(), false);

            int btnW = 60;
            int btnX = x + entryWidth - btnW;
            int btnY = y + entryHeight / 2 - 10;
            this.button.setPosition(btnX, btnY);
            this.button.render(graphics, mouseX, mouseY, delta);
        }

        @Override
        public java.util.List<? extends net.minecraft.client.gui.Selectable> narratables() {
            return java.util.List.of();
        }

        @Override
        public java.util.List<? extends net.minecraft.client.gui.Element> children() {
            return java.util.List.of(button);
        }

        @Override
        public int getItemHeight() {
            return 24;
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            return this.button.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            return this.button.mouseReleased(mouseX, mouseY, button);
        }

        @Override
        public Rectangle getEntryArea(int x, int y, int entryWidth, int entryHeight) {
            return new Rectangle(x, y, entryWidth, getItemHeight());
        }
    }
}
