package com.billy65536.infrastructure.debugger.core.action;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.billy65536.infrastructure.InfrastructureMod;

import net.minecraft.util.Identifier;

/**
 * 全局调试动作注册表。
 *
 * <p>所有 {@link IDebugAction} 实现通过此注册表注册，供命令系统查询与执行。
 * 注册表为静态单例，在模组初始化时完成注册。</p>
 *
 * <p>注册顺序决定命令补全和列表展示的排列顺序。</p>
 */
public final class ActionRegistry {

    private static final Map<Identifier, IDebugAction> actions = new LinkedHashMap<>();

    private ActionRegistry() {}

    /**
     * 注册一个调试动作。重复注册会覆盖之前同 ID 的动作。
     *
     * @param action 动作实例，null 或 id 为 null 时忽略并告警
     */
    public static void register(IDebugAction action) {
        if (action == null || action.getId() == null) {
            InfrastructureMod.LOGGER.warn("Attempted to register null action or action with null ID, ignored");
            return;
        }
        Identifier id = action.getId();
        if (actions.containsKey(id)) {
            InfrastructureMod.LOGGER.warn("Debug action {} is already registered, overwriting", id);
        }
        actions.put(id, action);
        InfrastructureMod.LOGGER.info("Registered debug action: {}", id);
    }

    /** 通过 ID 获取动作，不存在返回 null。 */
    public static IDebugAction get(Identifier id) {
        return actions.get(id);
    }

    /** 获取所有已注册的动作（只读）。 */
    public static Collection<IDebugAction> getAll() {
        return Collections.unmodifiableCollection(actions.values());
    }

    /** 已注册动作数量。 */
    public static int size() {
        return actions.size();
    }
}
