package com.billy65536.infrastructure.security.builtin;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.billy65536.infrastructure.security.core.policy.SecurityConfigPatch;
import com.billy65536.infrastructure.security.core.policy.RegistrationCoordinator;

import net.minecraft.util.Identifier;

/**
 * {@link ConfigLocker} 的受控补丁实现（Override 机制载体）。
 *
 * <p>持 {@code adds}（路径 → 强制值，允许 null）与 {@code removes}（路径集合）两张表。
 * 严格区分 {@code null}（仅锁定）与空串（强制空值）两态。</p>
 *
 * <p>同一 Patch 内 {@code remove} 优先级高于 {@code add}：{@link ConfigLockerPolicyConfig#applyPatch}
 * 先 apply 全部 add 再移除全部 remove，故两态冲突时 remove 胜出。</p>
 */
public final class ConfigLockPatch implements SecurityConfigPatch {

    private final Identifier policyId;
    private final Identifier executorId;
    /** 路径 → 强制值；value 允许为 null（仅锁定无强制值）。保序。 */
    private final Map<String, String> adds;
    private final Set<String> removes;

    private ConfigLockPatch(Identifier policyId, Identifier executorId,
            Map<String, String> adds, Set<String> removes) {
        this.policyId = policyId;
        this.executorId = executorId;
        this.adds = adds;
        this.removes = removes;
    }

    @Override
    public Identifier getPolicyId() {
        return policyId;
    }

    @Override
    public Identifier getExecutorId() {
        return executorId;
    }

    /** 追加的约束（路径 → 强制值）。 */
    public Map<String, String> adds() {
        return adds;
    }

    /** 移除的约束（路径集合）。 */
    public Set<String> removes() {
        return removes;
    }

    /** 构造器。 */
    public static Builder builder(Identifier policyId, Identifier executorId) {
        return new Builder(policyId, executorId);
    }

    /** 补丁构造器，链式 {@code add / remove} 后 {@code apply()} 提交。 */
    public static final class Builder {
        private final Identifier policyId;
        private final Identifier executorId;
        private final Map<String, String> adds = new LinkedHashMap<>();
        private final Set<String> removes = new LinkedHashSet<>();

        private Builder(Identifier policyId, Identifier executorId) {
            this.policyId = policyId;
            this.executorId = executorId;
        }

        /** 追加 / 覆盖一条约束，强制为指定值（允许 null = 仅锁定）。 */
        public Builder add(String path, String value) {
            adds.put(path, value);
            return this;
        }

        /** 仅锁定指定路径（无强制值）。 */
        public Builder add(String path) {
            return add(path, null);
        }

        /** 移除一条约束（解锁 / 服务端授权放行）。 */
        public Builder remove(String path) {
            removes.add(path);
            return this;
        }

        /** 构建补丁并提交给 Manager 触发重算。 */
        public void apply() {
            RegistrationCoordinator.submitPatchNow(new ConfigLockPatch(policyId, executorId, adds, removes));
        }
    }
}
