package com.billy65536.infrastructure.security.core.policy;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.billy65536.infrastructure.InfrastructureMod;
import com.billy65536.infrastructure.security.core.event.SecurityEvents;

import net.minecraft.util.Identifier;

/**
 * 全局安全策略注册表兼状态机。
 *
 * <p>所有 {@link ISecurityPolicy} 实现通过此注册表注册，供命令系统查询与操控。
 * 注册表为静态单例，在模块初始化时完成注册。</p>
 *
 * <h2>状态模型</h2>
 *
 * <p>只有<b>策略</b>持有激活态（{@link #active} 集合）。执行器的启用态一律实时派生自
 * 其所属策略（{@link #isExecutorEnabled}），不单独存储，从根本上杜绝两份状态漂移。</p>
 *
 * <p>状态变更是<b>幂等</b>的：目标状态与当前一致时直接返回，不重复触发任何回调。
 * 无论是框架按 {@link ActivationTrigger} 自动判定，还是
 * {@code /inf security active|deactive} 手动切换，都走
 * {@link #setActive(Identifier, boolean)} 这一条通路，因而行为完全一致。</p>
 *
 * <h2>异常隔离</h2>
 *
 * <p>策略与执行器的回调<b>逐个</b>用 {@code try/catch(Throwable)} 包住：任一回调抛出
 * 异常只记录日志，不会中断同批其余回调，也不会让 {@link #setActive} 失败——安全回调
 * 的失败绝不允许阻断连接流程或使游戏崩溃。需要注意的副作用是，回调失败时激活态
 * <b>依然会被置为目标值</b>，即注册表记录的状态可能与实际施加的约束不符，
 * 此类情形只能通过日志发现。</p>
 *
 * <p>注册顺序决定命令补全与列表展示的排列顺序。</p>
 *
 * <p><b>线程约束</b>：内部集合均为普通非同步实现，假定注册与状态变更都发生在
 * 客户端主线程（模块初始化、连接事件、命令执行均在此线程）。</p>
 */
public final class PolicyRegistry {

    /** 已注册策略，按注册顺序保序。 */
    private static final Map<Identifier, ISecurityPolicy> policies = new LinkedHashMap<>();

    /** 执行器索引：执行器 id → 执行器，便于命令按 id 直接反查。 */
    private static final Map<Identifier, ISecurityExecutor> executors = new LinkedHashMap<>();

    /** 执行器归属索引：执行器 id → 所属策略 id。 */
    private static final Map<Identifier, Identifier> executorOwner = new LinkedHashMap<>();

    /** 当前处于激活状态的策略 id 集合。 */
    private static final Set<Identifier> active = new LinkedHashSet<>();

    private PolicyRegistry() {}

    /**
     * 注册一个安全策略。重复注册会覆盖之前同 ID 的策略。
     *
     * <p>注册时按 {@link ActivationTrigger#ALWAYS} 决定是否立即激活；其余触发条件
     * 的策略初始均为未激活，等待框架判定或手动开关。</p>
     *
     * @param policy 策略实例，null 或 id 为 null 时忽略并告警
     */
    public static void register(ISecurityPolicy policy) {
        if (policy == null || policy.getId() == null) {
            InfrastructureMod.LOGGER.warn(
                    "Attempted to register null security policy or policy with null ID, ignored");
            return;
        }
        Identifier id = policy.getId();
        if (policies.containsKey(id)) {
            InfrastructureMod.LOGGER.warn("Security policy {} is already registered, overwriting", id);
            unindexExecutors(id);
        }
        policies.put(id, policy);

        List<ISecurityExecutor> list = policy.getExecutors();
        int execCount = 0;
        if (list != null) {
            for (ISecurityExecutor executor : list) {
                if (executor == null || executor.getId() == null) {
                    InfrastructureMod.LOGGER.warn(
                            "Security policy {} contains a null executor or executor with null ID, ignored", id);
                    continue;
                }
                executors.put(executor.getId(), executor);
                executorOwner.put(executor.getId(), id);
                execCount++;
            }
        }

        InfrastructureMod.LOGGER.info(
                "Registered security policy: {} (trigger: {}, executors: {})",
                id, policy.getTrigger(), execCount);

        if (policy.getTrigger() == ActivationTrigger.ALWAYS) {
            setActive(id, true);
        }
    }

    /** 移除某策略此前登记的执行器索引，用于覆盖注册时清理陈旧条目。 */
    private static void unindexExecutors(Identifier policyId) {
        executorOwner.entrySet().removeIf(e -> {
            if (e.getValue().equals(policyId)) {
                executors.remove(e.getKey());
                return true;
            }
            return false;
        });
    }

    /**
     * 通过 ID 获取策略。
     *
     * @param id 策略 id
     * @return 对应策略；未注册时返回 {@code null}
     */
    public static ISecurityPolicy get(Identifier id) {
        return policies.get(id);
    }

    /**
     * 获取所有已注册的策略。
     *
     * @return 按注册顺序保序的只读视图；底层变化会反映到该视图上
     */
    public static Collection<ISecurityPolicy> getAll() {
        return Collections.unmodifiableCollection(policies.values());
    }

    /**
     * 已注册策略数量。
     *
     * @return 策略总数
     */
    public static int size() {
        return policies.size();
    }

    /**
     * 通过 ID 获取执行器。
     *
     * @param id 执行器 id
     * @return 对应执行器；未注册时返回 {@code null}
     */
    public static ISecurityExecutor getExecutor(Identifier id) {
        return executors.get(id);
    }

    /**
     * 获取所有已注册的执行器（跨策略汇总）。
     *
     * @return 按注册顺序保序的只读视图；底层变化会反映到该视图上
     */
    public static Collection<ISecurityExecutor> getAllExecutors() {
        return Collections.unmodifiableCollection(executors.values());
    }

    /**
     * 反查执行器所属的策略。
     *
     * @param executorId 执行器 id
     * @return 所属策略；执行器未注册时返回 {@code null}
     */
    public static ISecurityPolicy findPolicyOf(Identifier executorId) {
        Identifier owner = executorOwner.get(executorId);
        return owner == null ? null : policies.get(owner);
    }

    /**
     * 查询策略是否处于激活状态。
     *
     * @param policyId 策略 id
     * @return 是否激活；未注册的 id 视为未激活
     */
    public static boolean isActive(Identifier policyId) {
        return active.contains(policyId);
    }

    /**
     * 查询执行器是否处于启用状态。
     *
     * <p>状态完全派生自所属策略的激活态，执行器自身不持有启用标志。</p>
     *
     * @param executorId 执行器 id
     * @return 所属策略是否处于激活态；执行器未注册或找不到归属时返回 {@code false}
     */
    public static boolean isExecutorEnabled(Identifier executorId) {
        Identifier owner = executorOwner.get(executorId);
        return owner != null && active.contains(owner);
    }

    /**
     * 设置策略的激活状态，并联动其下辖全部执行器。
     *
     * <p>状态未发生变化时直接返回，避免重复触发回调。发生变化时依次：
     * 更新内存状态 → 策略 {@code onActivate} / 执行器 {@code onEnable}
     * （停用时顺序相反：执行器 {@code onDisable} → 策略 {@code onDeactivate}）
     * → 触发 {@link SecurityEvents} 对应事件。</p>
     *
     * @param policyId 策略 id
     * @param value    目标状态
     * @return true 表示状态确实发生了变更，false 表示策略未注册或状态未变
     */
    public static boolean setActive(Identifier policyId, boolean value) {
        ISecurityPolicy policy = policies.get(policyId);
        if (policy == null) {
            InfrastructureMod.LOGGER.warn(
                    "Attempted to set state of unregistered security policy: {}", policyId);
            return false;
        }
        if (active.contains(policyId) == value) {
            return false;
        }

        if (value) {
            active.add(policyId);
            invokePolicy(policy, true);
            forEachExecutor(policy, true);
        } else {
            active.remove(policyId);
            forEachExecutor(policy, false);
            invokePolicy(policy, false);
        }

        if (value) {
            SecurityEvents.ACTIVATE.invoker().onActivated(policy);
        } else {
            SecurityEvents.DEACTIVATE.invoker().onDeactivated(policy);
        }
        InfrastructureMod.LOGGER.info("Security policy {} is now {}",
                policyId, value ? "active" : "inactive");
        return true;
    }

    /** 遍历策略下辖执行器并触发启停回调，逐个隔离异常。 */
    private static void forEachExecutor(ISecurityPolicy policy, boolean enable) {
        List<ISecurityExecutor> list = policy.getExecutors();
        if (list == null) return;
        for (ISecurityExecutor executor : list) {
            if (executor == null) continue;
            try {
                if (enable) {
                    executor.onEnable();
                } else {
                    executor.onDisable();
                }
            } catch (Throwable t) {
                InfrastructureMod.LOGGER.error("Security executor {} threw an exception in {} callback",
                        executor.getId(), enable ? "onEnable" : "onDisable", t);
            }
        }
    }

    /** 触发策略自身的启停回调，捕获一切异常。 */
    private static void invokePolicy(ISecurityPolicy policy, boolean activate) {
        try {
            if (activate) {
                policy.onActivate();
            } else {
                policy.onDeactivate();
            }
        } catch (Throwable t) {
            InfrastructureMod.LOGGER.error("Security policy {} threw an exception in {} callback",
                    policy.getId(), activate ? "onActivate" : "onDeactivate", t);
        }
    }
}
