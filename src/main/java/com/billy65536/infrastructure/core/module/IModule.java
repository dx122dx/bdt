package com.billy65536.infrastructure.core.module;

import java.util.Collection;
import java.util.List;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

/**
 * 模块扩展点接口。任何希望以「模块」身份接入 billy-inf 的组件都应实现本接口，
 * 并通过 {@link ModuleRegistry#register(IModule)} 显式登记，或由
 * {@link ModuleRegistry#discover()} 基于 Java SPI（META-INF/services）自动发现并登记。
 *
 * <p>登记是「一次声明全部能力」：模块 id / 版本 / 名称 / 描述（强制），
 * 以及可选的配置对象（{@link #getConfig()}）与命令树（{@link #buildCommands()}）。
 * 注册表与命令登记器在 {@code register} 时统一接管配置索引与命令挂载，
 * 调用方无需再做额外装配。</p>
 *
 * <p>模块不一定位于 billy-inf 同 mod 内（可跨 mod、可运行时才在 classpath 上）。
 * 框架全程基于反射访问配置对象，不要求编译期可见模块的配置类。</p>
 */
public interface IModule {

    /**
     * 模块唯一标识。无命名空间的纯名称（如 {@code debugger}），
     * 作为配置 / 命令 / 贡献报告的反查主键。
     *
     * <p>不可含冒号：{@code /inf config} 的目标串按最后一个冒号切分为
     * {@code <moduleId>:<path>}，id 内的冒号会导致切分结果错位。</p>
     */
    String getId();

    /** 模块版本字符串（自由格式，如 {@code 1.0.0}）。 */
    String getVersion();

    /** 人类可读的模块名称（支持彩色文本）。 */
    Text getName();

    /** 模块功能描述（支持彩色文本）。 */
    Text getDescription();

    // =================== 可选：模块初始化 ===================

    /**
     * 模块自身的初始化钩子，由 {@link ModuleRegistry#discover()} 在登记前调用。
     * 默认空实现。
     *
     * <p>模块私有的准备工作（配置注册、状态加载、扩展点注册等）应放在这里，
     * 而非由模组主类代劳，以保证主类不感知任何具体模块。</p>
     *
     * <p>本方法抛出的任何 {@link Throwable} 都会被注册表捕获并记录，
     * 该模块随即被跳过，不影响其它模块与框架自身。</p>
     */
    default void onInitializeModule() {}


    // ==================== 可选：模块配置 ====================

    /**
     * 模块配置对象（POJO）。默认无配置。
     *
     * <p>返回非 null 时，框架会按对象的实际类型自动构建点分路径索引，
     * 供 {@code /inf config get|set|reset <moduleId:path>} 访问。
     * 配置类须有无参构造器（用于默认值快照）。</p>
     */
    default Object getConfig() {
        return null;
    }

    /**
     * 配置持久化钩子。{@code /inf config set|reset} 写入后由框架调用，
     * 模块自行决定如何落盘（AutoConfig / Gson / FeatureStateStore 等）。
     * 默认空实现（配置仅在内存中生效）。
     */
    default void saveConfig() {}

    // ==================== 可选：模块命令 ====================

    /**
     * 构建本模块挂载到 {@code /inf} 根之下的命令子树。
     * 默认返回 null（不贡献命令）。
     *
     * <p>返回的节点名（literal）应同时在 {@link #getCommandLiterals()} 中声明，
     * 供 {@code /inf info <moduleId>} 展示贡献的命令清单。</p>
     */
    default LiteralArgumentBuilder<FabricClientCommandSource> buildCommands() {
        return null;
    }

    /**
     * 本模块贡献的命令字面量列表（如 {@code ["dbg"]}），用于 {@code /inf info} 展示。
     * 必须与 {@link #buildCommands()} 返回节点的顶层 literal 一致。默认无。
     */
    default Collection<String> getCommandLiterals() {
        return List.of();
    }
}
