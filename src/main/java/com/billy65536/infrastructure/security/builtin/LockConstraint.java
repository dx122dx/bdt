package com.billy65536.infrastructure.security.builtin;


/**
 * 单条锁约束的值对象，聚合「约束三要素」。
 *
 * <p>原 {@code ConfigLocker} 的 {@code lockStatus / defaultLocks / preLockValues} 三张平行表
 * 合并为此单表 {@code Map<String, LockConstraint>}，消除时序耦合。</p>
 *
 * <ul>
 *   <li>{@link #fullPath()} —— 完整配置路径（{@code <module>:<id>/<dot.path>}）；</li>
 *   <li>{@link #forcedValue()} —— 强制值；{@code null} 表示「仅锁定无强制值」；空串是合法强制值；</li>
 *   <li>{@link #originalValue()} —— 覆盖前玩家本地值的快照；一经建立<b>不得刷新</b>，
 *        否则解锁后回不到玩家真实设置。</li>
 * </ul>
 */
public final class LockConstraint {

    private final String fullPath;
    private final String forcedValue;
    private final String originalValue;

    /**
     * @param fullPath      完整配置路径
     * @param forcedValue   强制值（允许 null）
     * @param originalValue 覆盖前玩家本地值快照（允许 null，表示当初读不到）
     */
    public LockConstraint(String fullPath, String forcedValue, String originalValue) {
        this.fullPath = fullPath;
        this.forcedValue = forcedValue;
        this.originalValue = originalValue;
    }

    public String fullPath() {
        return fullPath;
    }

    /** 强制值；{@code null} = 仅锁定无强制值。 */
    public String forcedValue() {
        return forcedValue;
    }

    /** 覆盖前玩家本地值快照；{@code null} = 当初读不到，还原时跳过。 */
    public String originalValue() {
        return originalValue;
    }
}
