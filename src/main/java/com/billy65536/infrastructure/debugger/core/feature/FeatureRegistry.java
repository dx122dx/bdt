package com.billy65536.infrastructure.debugger.core.feature;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.billy65536.infrastructure.InfrastructureMod;
import com.billy65536.infrastructure.debugger.config.FeatureStateStore;

import net.minecraft.util.Identifier;

/**
 * 全局调试特性注册表。
 *
 * <p>所有 {@link IDebugFeature} 实现通过此注册表注册，供命令系统与配置界面查询。
 * 注册表为静态单例，在模组初始化时完成注册。</p>
 *
 * <p>启用状态由 {@link FeatureStateStore} 持久化：注册时读取已保存的状态，
 * 无记录则采用 {@link IDebugFeature#isDefaultEnabled()}。状态变更会触发
 * {@code onEnable}/{@code onDisable} 回调并立即落盘。</p>
 *
 * <p>注册顺序决定命令补全和列表展示的排列顺序。</p>
 */
public final class FeatureRegistry {

    private static final Map<Identifier, IDebugFeature> features = new LinkedHashMap<>();

    /** 当前处于启用状态的特性 id 集合。 */
    private static final Set<Identifier> enabled = new LinkedHashSet<>();

    private FeatureRegistry() {}

    /**
     * 注册一个调试特性。重复注册会覆盖之前同 ID 的特性。
     *
     * <p>注册时确定初始启用状态：优先采用 {@link FeatureStateStore} 中的持久化记录，
     * 无记录时采用特性自身的默认值。若判定为启用，立即触发 {@code onEnable} 回调。</p>
     *
     * @param feature 特性实例，null 或 id 为 null 时忽略并告警
     */
    public static void register(IDebugFeature feature) {
        if (feature == null || feature.getId() == null) {
            InfrastructureMod.LOGGER.warn("Attempted to register null feature or feature with null ID, ignored");
            return;
        }
        Identifier id = feature.getId();
        if (features.containsKey(id)) {
            InfrastructureMod.LOGGER.warn("Debug feature {} is already registered, overwriting", id);
        }
        features.put(id, feature);

        Boolean stored = FeatureStateStore.getState(id);
        boolean active = (stored != null) ? stored : feature.isDefaultEnabled();
        if (active) {
            enabled.add(id);
            invokeCallback(feature, true);
        } else {
            enabled.remove(id);
        }
        InfrastructureMod.LOGGER.info("Registered debug feature: {} (enabled: {})", id, active);
    }

    /** 通过 ID 获取特性，不存在返回 null。 */
    public static IDebugFeature get(Identifier id) {
        return features.get(id);
    }

    /** 获取所有已注册的特性（只读）。 */
    public static Collection<IDebugFeature> getAll() {
        return Collections.unmodifiableCollection(features.values());
    }

    /** 已注册特性数量。 */
    public static int size() {
        return features.size();
    }

    /** 查询特性是否处于启用状态。未注册的 id 视为未启用。 */
    public static boolean isEnabled(Identifier id) {
        return enabled.contains(id);
    }

    /**
     * 设置特性的启用状态。
     *
     * <p>状态未发生变化时直接返回，避免重复触发回调。发生变化时依次：
     * 更新内存状态 → 触发 {@code onEnable}/{@code onDisable} → 写入并持久化状态存储。</p>
     *
     * @param id      特性 id
     * @param value   目标状态
     * @return true 表示状态确实发生了变更，false 表示特性未注册或状态未变
     */
    public static boolean setEnabled(Identifier id, boolean value) {
        IDebugFeature feature = features.get(id);
        if (feature == null) {
            InfrastructureMod.LOGGER.warn("Attempted to set state of unregistered feature: {}", id);
            return false;
        }
        if (enabled.contains(id) == value) {
            return false;
        }
        if (value) {
            enabled.add(id);
        } else {
            enabled.remove(id);
        }
        invokeCallback(feature, value);
        FeatureStateStore.setState(id, value);
        FeatureStateStore.save();
        return true;
    }

    /**
     * 仅更新内存状态与状态存储，不落盘。
     *
     * <p>供配置界面批量应用变更后统一保存一次，避免逐条目 I/O。
     * 调用方有责任在批量结束后调用 {@link FeatureStateStore#save()}。</p>
     *
     * @return true 表示状态确实发生了变更
     */
    public static boolean setEnabledDeferred(Identifier id, boolean value) {
        IDebugFeature feature = features.get(id);
        if (feature == null || enabled.contains(id) == value) {
            return false;
        }
        if (value) {
            enabled.add(id);
        } else {
            enabled.remove(id);
        }
        invokeCallback(feature, value);
        FeatureStateStore.setState(id, value);
        return true;
    }

    /**
     * 触发启用/禁用回调，捕获一切异常。
     *
     * <p>调试特性的回调稳定性天然偏低，异常绝不允许逸出，否则会破坏注册流程
     * 或使命令系统异常。</p>
     */
    private static void invokeCallback(IDebugFeature feature, boolean value) {
        try {
            if (value) {
                feature.onEnable();
            } else {
                feature.onDisable();
            }
        } catch (Exception e) {
            InfrastructureMod.LOGGER.error("Debug feature {} threw an exception in {} callback",
                    feature.getId(), value ? "onEnable" : "onDisable", e);
        }
    }
}
