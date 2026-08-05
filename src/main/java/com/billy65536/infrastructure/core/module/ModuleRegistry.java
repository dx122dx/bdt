package com.billy65536.infrastructure.core.module;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.billy65536.infrastructure.InfrastructureMod;

import net.minecraft.util.Identifier;

/**
 * 全局模块注册表（静态单例）。
 *
 * <p>所有 {@link IModule} 实现通过本注册表登记。登记即一次声明全部能力：
 * 元数据（id/版本/名称/描述）、配置对象（{@link IModule#getConfig()}）、
 * 以及命令子树（{@link IModule#buildCommands()}，由 {@link ModuleCommandRegistrar} 统一挂载）。</p>
 *
 * <p>沿用 {@code ActionRegistry} / {@link com.billy65536.infrastructure.debugger.core.feature.FeatureRegistry}
 * 的静态单例 + {@link LinkedHashMap} 模式；注册顺序决定 {@code /inf info} 列举与命令挂载的排列顺序。</p>
 *
 * <p>模块必须在 {@code InfrastructureCommands.register()} 之前完成登记
 * （初始化顺序步骤 3 由 BuiltinsManager 或其它启动代码调用 {@link #register(IModule)}）。</p>
 */
public final class ModuleRegistry {

    private static final Map<Identifier, IModule> modules = new LinkedHashMap<>();

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
        Identifier id = module.getId();
        if (modules.containsKey(id)) {
            InfrastructureMod.LOGGER.warn("Module {} is already registered, overwriting", id);
        }
        modules.put(id, module);
        ModuleCommandRegistrar.register(module);
        InfrastructureMod.LOGGER.info("Registered module: {} (version {})", id, module.getVersion());
    }

    /** 通过 id 获取模块，不存在返回 null。 */
    public static IModule get(Identifier id) {
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
}
