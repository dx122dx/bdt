package com.billy65536.infrastructure.security.builtin;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

import com.billy65536.infrastructure.core.config.ConfigPath;
import com.billy65536.infrastructure.security.core.policy.SecurityConfigPatch;
import com.billy65536.infrastructure.security.core.policy.SecurityPolicyConfig;

import net.minecraft.util.Identifier;

/**
 * {@link ConfigLocker} 的不可变配置实现。
 *
 * <p>持有一张「路径 → 强制值」映射（{@link LinkedHashMap} 保序且允许 {@code null} value，
 * 以区分「仅锁定」与「强制空值」两态）。配置本身不可变，所有合并 / 打补丁操作均返回
 * 新实例。</p>
 */
public final class ConfigLockerPolicyConfig implements SecurityPolicyConfig {

    private final Identifier executorId;
    /** 路径 → 强制值；value 允许为 null（仅锁定无强制值）。保序。 */
    private final Map<String, String> locks;

    private ConfigLockerPolicyConfig(Identifier executorId, Map<String, String> locks) {
        this.executorId = executorId;
        this.locks = locks;
    }

    /** 空配置（无锁定项）。executorId 默认指向 ConfigLocker。 */
    public static ConfigLockerPolicyConfig empty() {
        return new ConfigLockerPolicyConfig(ConfigLocker.EXECUTOR_ID, Map.of());
    }

    @Override
    public Identifier getExecutorId() {
        return executorId;
    }

    /** 配置的锁定表（只读视图）。 */
    public Map<String, String> getLocks() {
        return locks;
    }

    @Override
    public SecurityPolicyConfig combine(SecurityPolicyConfig other) {
        if (!(other instanceof ConfigLockerPolicyConfig o)) {
            return this;
        }
        Map<String, String> merged = new LinkedHashMap<>(this.locks);
        for (Entry<String, String> e : o.locks.entrySet()) {
            merged.put(e.getKey(), e.getValue());
        }
        return new ConfigLockerPolicyConfig(executorId, merged);
    }

    @Override
    public SecurityPolicyConfig applyPatch(SecurityConfigPatch patch) {
        if (!(patch instanceof ConfigLockPatch p)) {
            return this;
        }
        Map<String, String> result = new LinkedHashMap<>(this.locks);
        // 先 add 后 remove：同一 Patch 内 remove 优先级高于 add
        for (Entry<String, String> e : p.adds().entrySet()) {
            result.put(e.getKey(), e.getValue());
        }
        for (String r : p.removes()) {
            result.remove(r);
        }
        return new ConfigLockerPolicyConfig(executorId, result);
    }

    /** 构造器：按 executorId 创建，经 {@code lock(...)} 展开完整路径。 */
    public static Builder builder(Identifier executorId) {
        return new Builder(executorId);
    }

    /** 配置构造器。 */
    public static final class Builder {
        private final Identifier executorId;
        private final Map<String, String> locks = new LinkedHashMap<>();

        private Builder(Identifier executorId) {
            this.executorId = executorId;
        }

        /**
         * 锁定某完整配置路径并可选地强制其值。
         *
         * @param moduleId 模块 id
         * @param segment  配置段名（如 {@code config}）
         * @param dotPath  纯字段点分路径
         * @param value    强制值；{@code null} 表示仅锁定无强制值，空串是合法强制值
         */
        public Builder lock(String moduleId, String segment, String dotPath, String value) {
            locks.put(ConfigPath.of(moduleId, segment, dotPath).toString(), value);
            return this;
        }

        public ConfigLockerPolicyConfig build() {
            return new ConfigLockerPolicyConfig(executorId, locks);
        }
    }
}
