package com.billy65536.infrastructure.core.config;

import java.util.Objects;
import java.util.function.Supplier;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;

/**
 * 模块配置描述符：统一管理「模块暴露给框架的配置对象」的元信息与操作入口。
 *
 * <p>每个模块通过 {@link com.billy65536.infrastructure.core.module.IModule#getConfigDescriptors()}
 * 返回一组描述符，框架（{@code /inf config}、{@code ConfigLocker}）据此统一读写配置，
 * 而<b>不再直接持有模块的配置类</b>——这是把配置管理收归 infrastructure 核心的关键落点：
 * 模块配置类对框架完全不可见，框架只通过本描述符持有的 {@code Object} 实例做反射读写。</p>
 *
 * <p>描述符承载：</p>
 * <ul>
 *   <li>{@link #path()} —— 本配置对象的逻辑路径（段名 {@code id} 自定义，
 *       见 {@link ConfigPath} 的省略规则）；</li>
 *   <li>{@link #getConfig()} —— 配置实例引用（供 {@code ConfigAccessor} 反射读写）；</li>
 *   <li>{@link #defaultValue()} —— 该配置对象的整体默认初始值
 *       （一个全新的默认值实例，{@code reset} 与默认值展示用）；</li>
 *   <li>{@link #dangerous()} —— 是否含「危险配置项」：被服务器锁定策略强制的值，
 *       即 {@code ConfigLocker} 进入服务器时默认锁定的强制值来源；</li>
 *   <li>{@link #openGui()} —— 打开该配置 GUI 的回调（如 Cloth Config 界面），
 *       供 {@code /inf config gui} 调用；框架不依赖任何具体 GUI 库，只持 Runnable。</li>
 * </ul>
 *
 * <p>{@code config} 实例由 {@code Supplier} 惰性提供：AutoConfig 在 {@code load()} 时会替换内部实例，
 * 缓存引用会读到陈旧对象（见各模块配置加载器的说明），故改用 Supplier 每次现取活动实例。</p>
 *
 * @param path        配置路径（含 module + 段 id + 实际字段前缀；此处 module/id 已填好，仅存点分部分）
 * @param config      活动配置实例的惰性提供器（现取，避免陈旧引用）
 * @param defaultValue 默认值快照实例（字段初始化器即默认值唯一来源），可为 null（无默认值概念）
 * @param dangerous   是否含危险配置项（server-lock 强制值来源）；true 时其锁定默认值由
 *                     {@code ConfigLocker} 在模块登记时注册进锁定表
 * @param openGui     打开 GUI 的回调（可为 null，表示不支持 GUI）
 */
public record ConfigDescriptor(
        ConfigPath path,
        Supplier<Object> config,
        Object defaultValue,
        boolean dangerous,
        Runnable openGui) {

    /**
     * 完整构造。路径须为带 module + id 的 {@link ConfigPath}；字段路径允许为空数组
     * （表示该描述符覆盖整个配置对象，具体叶子字段由反射索引在运行时枚举）。
     *
     * @throws IllegalArgumentException 路径为 null 或 config supplier 为 null
     */
    public ConfigDescriptor {
        Objects.requireNonNull(path, "ConfigPath must not be null");
        if (config == null) {
            throw new IllegalArgumentException("config supplier must not be null");
        }
    }

    /** 取当前活动配置实例（现取，避免陈旧引用）。 */
    public Object getConfig() {
        return config.get();
    }

    /**
     * 以默认配置实例为基准，是否含危险项。框架登记模块时，若 {@link #dangerous()} 为 true，
     * 则由 {@code ConfigLocker} 扫描默认值实例中的锁定项（约定：危险项默认值即服务器强制值）。
     *
     * <p>约定：dangerous 配置对象中，凡是「默认值非该字段类型自然零值、且语义上为安全策略项」
     * 的字段，由模块在构造描述符时自行决定——本类不做字段级推断，dangerous 仅作整体开关，
     * 具体锁定项清单由模块在 {@code SecurityPortal} 注册时显式给出（见
     * {@code SecurityPortal#injectConfig(Identifier, SecurityPolicyConfig)}）。</p>
     */
    public boolean isDangerous() {
        return dangerous;
    }

    /** 构造一个无 GUI、非危险的描述符（便捷工厂）。 */
    public static ConfigDescriptor of(ConfigPath path, Supplier<Object> config, Object defaultValue) {
        return new ConfigDescriptor(path, config, defaultValue, false, null);
    }

    /** 构造一个含 GUI 回调的描述符（便捷工厂）。 */
    public static ConfigDescriptor withGui(ConfigPath path, Supplier<Object> config,
            Object defaultValue, Runnable openGui) {
        return new ConfigDescriptor(path, config, defaultValue, false, openGui);
    }

    /**
     * 构造一个危险配置描述符（含 server-lock 强制默认值）。
     * openGui 可为 null。
     */
    public static ConfigDescriptor dangerous(ConfigPath path, Supplier<Object> config,
            Object defaultValue, Runnable openGui) {
        return new ConfigDescriptor(path, config, defaultValue, true, openGui);
    }

    /**
     * 在客户端主线程打开该配置的 GUI（若存在）。无 GUI 回调时返回 false。
     * 框架命令层调用前已在客户端环境，此处切主线程确保 GUI 构造安全。
     */
    public boolean openGuiOnClient() {
        if (openGui == null) return false;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return false;
        client.send(() -> {
            Screen screen = client.currentScreen;
            openGui.run();
            // 若回调未自行 setScreen，此处不做额外处理（回调内部应负责切屏）
            if (client.currentScreen == screen && screen != null) {
                // 回调已处理，无需动作
            }
        });
        return true;
    }
}
