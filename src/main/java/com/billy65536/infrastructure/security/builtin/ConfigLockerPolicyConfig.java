package com.billy65536.infrastructure.security.builtin;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

import com.billy65536.infrastructure.core.config.ConfigPath;
import com.billy65536.infrastructure.security.core.internal.AttributedValue;
import com.billy65536.infrastructure.security.core.internal.Origin;
import com.billy65536.infrastructure.security.core.policy.SecurityConfigPatch;
import com.billy65536.infrastructure.security.core.policy.SecurityPolicyConfig;

import net.minecraft.util.Identifier;

/**
 * {@link ConfigLocker} 的不可变配置实现。
 *
 * <p>持有一张「路径 → {@linkplain AttributedValue 带来源的强制值}」映射
 * （{@link LinkedHashMap} 保序）。配置不可变，所有合并 / 打补丁 / 回填来源操作均返回新实例。</p>
 *
 * <h2>两态严格区分</h2>
 * <p>强制值 {@code null} 与空串 {@code ""} 是<b>语义不同的两态</b>，全链路必须保持区分：</p>
 * <ul>
 *   <li>{@code null} —— <b>仅锁定</b>，不改动玩家当前值，只禁止其再被修改；</li>
 *   <li>{@code ""} —— <b>强制为空值</b>，是合法强制值，会真正写入空串。</li>
 * </ul>
 * <p>因此判断是否锁定一律看 key 是否存在，绝不可用 {@code value == null} 代替。
 * 而包装用的 {@code AttributedValue} 实例本身<b>永不为 {@code null}</b>。</p>
 *
 * <h2>来源承载</h2>
 * <p>策略开发者<b>完全无需感知来源</b>：只调 {@link Builder#lock} 四参方法提供值，
 * 来源由框架在 {@code SecurityManager.recompute} 中经 {@link #withOrigin(Identifier)}
 * 统一回填。这样来源注入点唯一且位于框架内部，策略侧没有任何可篡改的入口。</p>
 */
public final class ConfigLockerPolicyConfig implements SecurityPolicyConfig {

    private final Identifier executorId;
    /**
     * 路径 → 带来源的强制值；条目值永不为 null，但其内部 value 允许为 null
     * （仅锁定无强制值）。保序。
     */
    private final Map<String, AttributedValue<String>> entries;

    private ConfigLockerPolicyConfig(Identifier executorId, Map<String, AttributedValue<String>> entries) {
        this.executorId = executorId;
        this.entries = entries;
    }

    /** 空配置（无锁定项）。executorId 默认指向 ConfigLocker。 */
    public static ConfigLockerPolicyConfig empty() {
        return new ConfigLockerPolicyConfig(ConfigLocker.EXECUTOR_ID, Map.of());
    }

    @Override
    public Identifier getExecutorId() {
        return executorId;
    }

    /**
     * 配置的锁定表（只读视图，路径 → 强制值）。
     *
     * <p>由 {@link #getEntries()} 解包投影而来——单一真相源是 {@code entries}，
     * 本方法只是面向既有调用点的兼容视图，签名与语义保持历史不变。</p>
     */
    public Map<String, String> getLocks() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Entry<String, AttributedValue<String>> e : entries.entrySet()) {
            out.put(e.getKey(), e.getValue().getValue());
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * 带来源的锁定表（只读视图），供框架内部（{@link ConfigLocker}）消费。
     *
     * @apiNote 暴露框架内部类型 {@link AttributedValue}，下游模组不得引用。
     */
    public Map<String, AttributedValue<String>> getEntries() {
        return Collections.unmodifiableMap(entries);
    }

    /**
     * 与另一份同类配置按路径取并集合并；非同类配置一律忽略并返回自身。
     *
     * <p>同路径冲突时走 {@link AttributedValue#merge}：值取后者，来源随之更新为后者，
     * 并累积全部贡献者——「值来自谁，来源就标谁」。</p>
     */
    @Override
    public SecurityPolicyConfig combine(SecurityPolicyConfig other) {
        if (!(other instanceof ConfigLockerPolicyConfig o)) {
            return this;
        }
        Map<String, AttributedValue<String>> merged = new LinkedHashMap<>(this.entries);
        for (Entry<String, AttributedValue<String>> e : o.entries.entrySet()) {
            merged.merge(e.getKey(), e.getValue(), AttributedValue::merge);
        }
        return new ConfigLockerPolicyConfig(executorId, merged);
    }

    /**
     * 应用一条 {@link ConfigLockPatch}；非该类型的补丁一律忽略并返回自身。
     *
     * <p><b>铁律</b>：<b>先 adds 后 removes</b>——同一 Patch 内 remove 优先级高于 add，
     * 即同时出现在 adds 与 removes 的路径最终为「未锁定」。次序颠倒会让 add 复活已删项。</p>
     *
     * <p>补丁自带 policyId，来源就地取用，不参与框架的 {@link #withOrigin} 回填。</p>
     */
    @Override
    public SecurityPolicyConfig applyPatch(SecurityConfigPatch patch) {
        if (!(patch instanceof ConfigLockPatch p)) {
            return this;
        }
        // 补丁自带 policyId（SecurityConfigPatch 的既定契约），来源直接取用无需框架回填
        Origin patchOrigin = Origin.of(p.getPolicyId());
        Map<String, AttributedValue<String>> result = new LinkedHashMap<>(this.entries);
        // 先 add 后 remove：同一 Patch 内 remove 优先级高于 add
        for (Entry<String, String> e : p.adds().entrySet()) {
            result.merge(e.getKey(), AttributedValue.of(e.getValue(), patchOrigin), AttributedValue::merge);
        }
        for (String r : p.removes()) {
            result.remove(r);
        }
        return new ConfigLockerPolicyConfig(executorId, result);
    }

    /**
     * 为表内每条锁定项回填来源，值不变。
     *
     * <p>框架在合并前对每份策略配置调用本方法，是静态策略层来源的唯一注入点。</p>
     *
     * @param policyId 贡献这份配置的策略 id；为 {@code null}（或表为空）时原样返回自身
     * @return 每条锁定项均打上该来源的新配置
     */
    @Override
    public SecurityPolicyConfig withOrigin(Identifier policyId) {
        if (entries.isEmpty() || policyId == null) return this;
        Map<String, AttributedValue<String>> stamped = new LinkedHashMap<>();
        for (Entry<String, AttributedValue<String>> e : entries.entrySet()) {
            stamped.put(e.getKey(), e.getValue().withOrigin(policyId));
        }
        return new ConfigLockerPolicyConfig(executorId, stamped);
    }

    /**
     * 构造器：按 executorId 创建，经 {@code lock(...)} 展开完整路径。
     *
     * @param executorId 目标执行器 id
     * @return 新的构造器实例
     */
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
         * <p>无需提供来源——框架会在合并前自动回填。</p>
         *
         * @param moduleId 模块 id
         * @param segment  配置段名（如 {@code config}）
         * @param dotPath  纯字段点分路径
         * @param value    强制值；{@code null} 表示仅锁定无强制值，空串是合法强制值
         * @return 本构造器自身，便于链式调用
         */
        public Builder lock(String moduleId, String segment, String dotPath, String value) {
            locks.put(ConfigPath.of(moduleId, segment, dotPath).toString(), value);
            return this;
        }

        /** 构建配置。此时来源尚未知，统一包装为空来源，等待框架回填。 */
        public ConfigLockerPolicyConfig build() {
            Map<String, AttributedValue<String>> entries = new LinkedHashMap<>();
            for (Entry<String, String> e : locks.entrySet()) {
                entries.put(e.getKey(), AttributedValue.of(e.getValue()));
            }
            return new ConfigLockerPolicyConfig(executorId, entries);
        }
    }
}
