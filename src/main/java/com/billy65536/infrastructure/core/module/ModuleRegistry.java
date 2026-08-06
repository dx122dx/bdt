package com.billy65536.infrastructure.core.module;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;

import com.billy65536.infrastructure.InfrastructureMod;

/**
 * 全局模块注册表（静态单例）。
 *
 * <p>所有 {@link IModule} 实现通过本注册表登记。登记即一次声明全部能力：
 * 元数据（id/版本/名称/描述）、配置描述符（{@link IModule#getConfigDescriptors()}）、
 * 以及命令子树（{@link IModule#buildCommands()}，由 {@link ModuleCommandRegistrar} 统一挂载）。</p>
 *
 * <p>登记有两种途径：</p>
 * <ul>
 *   <li>显式：调用 {@link #register(IModule)}；</li>
 *   <li>自动：调用 {@link #discover()}，基于 Java SPI 扫描
 *       {@code META-INF/services/com.billy65536.infrastructure.core.module.IModule}，
 *       发现全部 {@link IModule} 实现并登记。新增模块只需在 services 文件中加一行，
 *       无需改动任何启动代码——这正是「模块注册统一处理」的落点。</li>
 * </ul>
 *
 * <p>沿用 {@code ActionRegistry} / {@link com.billy65536.infrastructure.debugger.core.feature.FeatureRegistry}
 * 的静态单例 + {@link LinkedHashMap} 模式；注册顺序决定 {@code /inf info} 列举与命令挂载的排列顺序。</p>
 *
 * <p>登记时机：{@link #discover()} 由模组主类挂在 Fabric 的 {@code CLIENT_STARTED}
 * 生命周期事件上，即<b>所有模组的客户端入口点执行完毕之后</b>才触发，
 * 使下游模块能安全依赖其宿主模组的初始化结果。命令树在
 * {@code ClientCommandRegistrationCallback} 触发时才构建（晚于 {@code CLIENT_STARTED}），
 * 因此命令注册不再要求模块提前登记。</p>
 */
public final class ModuleRegistry {

    private static final Map<String, IModule> modules = new LinkedHashMap<>();
    private static volatile boolean discovered = false;

    private ModuleRegistry() {}

    /**
     * 登记一个模块。重复登记会覆盖同 id 的模块与命令节点。
     *
     * <p>登记时会一并调用 {@link ModuleCommandRegistrar#register(IModule)} 挂载其命令子树，
     * 因此命令装配无需在别处重复进行。</p>
     *
     * @param module 模块实例；null 或 id 为 null 时忽略并告警
     */
    public static void register(IModule module) {
        if (module == null || module.getId() == null) {
            InfrastructureMod.LOGGER.warn("Attempted to register null module or module with null ID, ignored");
            return;
        }
        String id = module.getId();
        if (modules.containsKey(id)) {
            InfrastructureMod.LOGGER.warn("Module {} is already registered, overwriting", id);
        }
        modules.put(id, module);
        ModuleCommandRegistrar.register(module);
        InfrastructureMod.LOGGER.info("Registered module: {} (version {})", id, module.getVersion());
    }

    /** 通过 id 获取模块，不存在返回 null。 */
    public static IModule get(String id) {
        return modules.get(id);
    }

    /** 获取所有已登记模块（只读，按登记顺序）。 */
    public static Collection<IModule> getAll() {
        return Collections.unmodifiableCollection(modules.values());
    }

    /** 已登记模块数量。 */
    public static int size() {
        return modules.size();
    }

    /**
     * 基于 Java SPI 自动发现并登记全部 {@link IModule} 实现。
     *
     * <p>扫描 {@code META-INF/services/com.billy65536.infrastructure.core.module.IModule} 中声明的实现类，
     * 逐个实例化并登记。新增模块只需提供实现类并在该 services 文件中追加一行，
     * 即可被自动纳入——启动代码无需任何改动。本方法幂等，仅执行一次。</p>
     *
     * <p><b>调用时机</b>：由 {@code InfrastructureMod} 挂到 {@code CLIENT_STARTED}
     * 事件上，晚于全部模组的 {@code client} 入口点。模块的
     * {@link IModule#onInitializeModule()} 因此可以读取宿主模组初始化后的状态；
     * 反之，需要更早时机的注册（资源包监听器、注册表条目等）不应放在模块初始化里，
     * 应由宿主模组自己在入口点完成，或改用 {@link #register(IModule)} 显式提前登记。</p>
     *
     * <p>任一模块实现加载失败（如缺失依赖）仅记录并跳过，不阻断其它模块的登记。</p>
     */
    public static void discover() {
        if (discovered) return;
        discovered = true;
        ServiceLoader<IModule> loader =
                ServiceLoader.load(IModule.class, IModule.class.getClassLoader());
        Iterator<IModule> it = loader.iterator();
        while (true) {
            IModule module;
            try {
                // hasNext() 同样会解析 provider 配置并抛 ServiceConfigurationError，必须一并包住
                if (!it.hasNext()) break;
                module = it.next();
            } catch (java.util.ServiceConfigurationError e) {
                InfrastructureMod.LOGGER.error("Failed to load a module service, aborting discovery", e);
                break;
            }
            // 模块初始化与登记的任何 Throwable（含 NoClassDefFoundError 等 LinkageError）
            // 都只记录并跳过：框架与其它模块的可用性优先于任一模块
            try {
                module.onInitializeModule();
                register(module);
            } catch (Throwable t) {
                InfrastructureMod.LOGGER.error("Module {} failed to initialize, skipping", safeId(module), t);
            }
        }
    }

    /** 尽最大努力取模块 id 用于日志；实现异常时退化为类名。 */
    private static String safeId(IModule module) {
        try {
            return String.valueOf(module.getId());
        } catch (Throwable t) {
            return module.getClass().getName();
        }
    }
}
