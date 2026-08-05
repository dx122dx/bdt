package com.billy65536.infrastructure.debugger.builtin;

import java.util.ArrayList;
import java.util.List;

import com.billy65536.infrastructure.InfrastructureMod;
import com.billy65536.infrastructure.debugger.api.DebuggerBuiltinProvider;

import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.fabricmc.loader.api.FabricLoader;

/**
 * 内置调试组件的统一注册出口。
 *
 * <p>每个「内置包」对应一个目标模组，仅当该模组已加载时才注册其调试项。
 * 这样本模组可以脱离任何目标模组独立运行。</p>
 *
 * <h2>延迟类加载（关键约束）</h2>
 *
 * <p>内置包中的 Action / Feature 实现类会在<b>类加载阶段</b>就解析目标模组的类型
 * （如 {@code ConfigurationLocker}）。若本类的方法体中直接出现 {@code new XxxAction()}，
 * JVM 在校验本类时就可能提前解析这些常量池引用，目标模组缺失时抛
 * {@link NoClassDefFoundError}，使 {@code isModLoaded} 判断形同虚设。</p>
 *
 * <p>因此本类<b>只引用各内置包的注册入口类</b>（如外部 mod 提供的
 * {@code XxxBuiltins}），绝不引用其中的具体实现类。入口类作为独立类，只有在
 * {@code register()} 被实际调用时才由 JVM 加载并解析其常量池，此时目标模组必然在场。</p>
 *
 * <h2>扩展方式</h2>
 *
 * <p>内置调试包由<b>外部 mod</b> 通过 billy-inf 的调试框架扩展点注入：</p>
 * <ol>
 *   <li>外部 mod 实现 {@link DebuggerBuiltinProvider} 接口；</li>
 *   <li>在其 {@code fabric.mod.json} 注册自定义 entrypoint {@code "billy-inf:debugger"}，
 *       值为该实现类的全限定名；</li>
 *   <li>在 {@link DebuggerBuiltinProvider#contribute} 中调用
 *       {@code contributor.add(requiredModId, displayName, XxxBuiltins::register)}。</li>
 * </ol>
 *
 * <p>billy-inf 自身不再硬编码任何具体目标模组的调试逻辑；本类的
 * {@link #PACKS} 仅保留纯框架内置（当前为空）。</p>
 */
public final class BuiltinsManager {

    /**
     * 内置包描述。
     *
     * @param requiredModId 目标模组 id，未加载时跳过本包
     * @param displayName   日志展示名
     * @param entry         注册入口。必须是独立入口类的方法引用，
     *                      不可写成持有目标模组类型的 lambda 闭包，否则会破坏惰性加载
     */
    private record BuiltinPack(String requiredModId, String displayName, Runnable entry) {}

    /** 框架内置包（当前为空；外部 mod 的包经由 {@code billy-inf:debugger} entrypoint 注入）。 */
    private static final List<BuiltinPack> PACKS = List.of();

    private BuiltinsManager() {}

    /**
     * 注册全部内置包：先合并框架内置包与外部 mod 注入的包，再逐包判定目标模组是否加载。
     *
     * <p>外部 mod 通过 {@code "billy-inf:debugger"} entrypoint 贡献其内置包，
     * 由 {@link DebuggerBuiltinProvider} 收集。注册过程中的任何 {@link Throwable}
     * （含 {@link NoClassDefFoundError}、{@link LinkageError} 等类加载期错误）都会被
     * 捕获并记录，绝不阻断模组初始化——调试工具的可用性优先级低于宿主游戏的启动成功率。</p>
     */
    public static void registerAll() {
        FabricLoader loader = FabricLoader.getInstance();
        List<BuiltinPack> all = new ArrayList<>(PACKS);

        // 收集外部 mod 通过 "billy-inf:debugger" entrypoint 注入的内置包
        for (EntrypointContainer<DebuggerBuiltinProvider> container
                : loader.getEntrypointContainers("billy-inf:debugger", DebuggerBuiltinProvider.class)) {
            try {
                container.getEntrypoint().contribute((requiredModId, displayName, entry) ->
                        all.add(new BuiltinPack(requiredModId, displayName, entry)));
            } catch (Throwable t) {
                InfrastructureMod.LOGGER.error("Failed to collect builtin packs from mod '{}'",
                        container.getProvider().getMetadata().getId(), t);
            }
        }

        for (BuiltinPack pack : all) {
            if (!loader.isModLoaded(pack.requiredModId())) {
                InfrastructureMod.LOGGER.info("Builtin pack '{}': skipped (mod '{}' not loaded)",
                        pack.displayName(), pack.requiredModId());
                continue;
            }
            try {
                pack.entry().run();
                InfrastructureMod.LOGGER.info("Builtin pack '{}': registered", pack.displayName());
            } catch (Throwable t) {
                InfrastructureMod.LOGGER.error("Builtin pack '{}': registration failed, skipping",
                        pack.displayName(), t);
            }
        }
    }
}
