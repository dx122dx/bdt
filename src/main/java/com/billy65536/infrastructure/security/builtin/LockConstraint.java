package com.billy65536.infrastructure.security.builtin;

import com.billy65536.infrastructure.security.core.internal.Origin;

/**
 * 单条锁约束的值对象，聚合「约束四要素」。
 *
 * <p>原 {@code ConfigLocker} 的 {@code lockStatus / defaultLocks / preLockValues} 三张平行表
 * 合并为此单表 {@code Map<String, LockConstraint>}，消除时序耦合。</p>
 *
 * <ul>
 *   <li>{@link #fullPath()} —— 完整配置路径（{@code <module>:<id>/<dot.path>}）；</li>
 *   <li>{@link #forcedValue()} —— 强制值；{@code null} 表示「仅锁定无强制值」；空串是合法强制值；</li>
 *   <li>{@link #originalValue()} —— 覆盖前玩家本地值的快照；一经建立<b>不得刷新</b>，
 *        否则解锁后回不到玩家真实设置；</li>
 *   <li>{@link #origin()} —— 这条锁的来源身份，供门禁拦截时溯源到具体策略。</li>
 * </ul>
 */
public final class LockConstraint {

    private final String fullPath;
    private final String forcedValue;
    private final String originalValue;
    private final Origin origin;

    /**
     * 构造一条锁约束。
     *
     * <p>建立后即不可变；需要更新强制值或来源时，应<b>新建</b>一条并沿用原有的
     * {@code originalValue} 快照（见 {@link #originalValue()} 的铁律）。</p>
     *
     * @param fullPath      完整配置路径
     * @param forcedValue   强制值（允许 null）
     * @param originalValue 覆盖前玩家本地值快照（允许 null，表示当初读不到）
     * @param origin        来源身份（允许 null，按未知来源处理）
     */
    public LockConstraint(String fullPath, String forcedValue, String originalValue, Origin origin) {
        this.fullPath = fullPath;
        this.forcedValue = forcedValue;
        this.originalValue = originalValue;
        this.origin = origin == null ? Origin.UNKNOWN : origin;
    }

    /** 完整配置路径（{@code <module>:<id>/<dot.path>}）。 */
    public String fullPath() {
        return fullPath;
    }

    /**
     * 强制值。
     *
     * <p>{@code null} = 仅锁定无强制值（不改动玩家当前值）；空串 {@code ""} 是合法强制值
     * （会写入空串）。两者语义严格区分，不可混同。</p>
     */
    public String forcedValue() {
        return forcedValue;
    }

    /**
     * 覆盖前玩家本地值快照；{@code null} = 当初读不到，还原时跳过。
     *
     * <p><b>铁律</b>：本快照只在约束首次建立时抓取，后续更新强制值或来源时一律沿用，
     * <b>绝不刷新</b>。一旦被强制值污染，解锁后就再也回不到玩家的真实设置。</p>
     */
    public String originalValue() {
        return originalValue;
    }

    /**
     * 来源身份，<b>永不为 {@code null}</b>（未知来源为 {@link Origin#UNKNOWN}）。
     *
     * @apiNote 返回框架内部类型 {@link Origin}，下游模组不得引用。
     */
    public Origin origin() {
        return origin;
    }
}
