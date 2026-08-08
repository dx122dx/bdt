package com.billy65536.infrastructure.security.builtin;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import com.billy65536.infrastructure.InfrastructureMod;
import com.billy65536.infrastructure.security.core.policy.ActivationTrigger;
import com.billy65536.infrastructure.security.core.policy.ISecurityPolicy;
import com.billy65536.infrastructure.security.core.policy.RegistrationCoordinator;
import com.billy65536.infrastructure.security.core.policy.SecurityPolicyConfig;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 内置「服务端 opt-in」安全策略，id 为 {@code security:server-optin}。
 *
 * <p>语义：进入多人服务器时，把各模块经 {@link com.billy65536.infrastructure.security.SecurityPortal}
 * 注入的默认受保护配置项全部锁定，等待服务端显式授权后方可放开；断开连接时释放全部锁定。
 * 实际动作由下辖的 {@link ConfigLocker} 执行。</p>
 *
 * <p>本策略<b>不可手动开关</b>（{@link #isManuallyToggleable()} 取基类默认 {@code false}）：
 * 进入 / 离开多人服务器完全由框架生命周期（{@link ActivationTrigger#MULTIPLAYER_JOIN}）驱动，
 * 玩家或服务端指令均无法自行解除其施加的锁定——这正是「服务端 opt-in 不得手动更改」的硬约束。
 * 仅框架内部经 {@code SecurityPortal#activatePolicyInternal} 在断连时释放锁定。</p>
 *
 * <h2>子事件</h2>
 *
 * <p>本策略以静态字段 {@link #LOCKS_APPLIED} 暴露专属子事件，在下辖执行器完成锁定之后触发。
 * 任何持有本类引用的一方均可直接订阅，用于在锁定落地时同步刷新自己的界面或状态。该事件由
 * {@link ConfigLocker#onPolicyChanged} 在锁定物化完成后补发。</p>
 */
public final class ServerOptinPolicy implements ISecurityPolicy {

    /** 策略名（不含命名空间），同时用于拼接下辖执行器的 id。 */
    public static final String POLICY_NAME = "server-optin";

    /** 策略 id：{@code security:server-optin}。 */
    public static final Identifier ID = new Identifier("security", POLICY_NAME);

    /** 单例：策略本身无状态，激活态由 {@link SecurityManager} 统一持有。 */
    public static final ServerOptinPolicy INSTANCE = new ServerOptinPolicy();

    /**
     * 本策略累积的静态配置（各模块经 {@link com.billy65536.infrastructure.security.SecurityPortal}
     * 注入的默认锁）。初始为空，激活后由 {@link SecurityManager} 合并推送。
     */
    private static ConfigLockerPolicyConfig staticConfig = ConfigLockerPolicyConfig.empty();

    /** 锁定生效后的回调。 */
    @FunctionalInterface
    public interface LocksAppliedCallback {
        /**
         * @param lockedCount 触发时锁定表中的条目<b>总数</b>，而非本次新增的条数
         */
        void onLocksApplied(int lockedCount);
    }

    /**
     * 子事件：配置锁定生效后触发（在下辖执行器完成 {@code onPolicyChanged} 之后）。
     *
     * <p>单个监听器抛出的异常会被捕获记录，不影响其余监听器。</p>
     */
    public static final Event<LocksAppliedCallback> LOCKS_APPLIED =
            EventFactory.createArrayBacked(LocksAppliedCallback.class, listeners -> lockedCount -> {
                for (LocksAppliedCallback listener : listeners) {
                    try {
                        listener.onLocksApplied(lockedCount);
                    } catch (Throwable t) {
                        InfrastructureMod.LOGGER.error(
                                "Security policy {} LOCKS_APPLIED listener threw an exception", ID, t);
                    }
                }
            });

    private ServerOptinPolicy() {}

    @Override
    public Identifier getId() {
        return ID;
    }

    @Override
    public Text getName() {
        return Text.translatable("infrastructure.security.policy.server_optin.name");
    }

    @Override
    public Text getDescription() {
        return Text.translatable("infrastructure.security.policy.server_optin.description");
    }

    @Override
    public ActivationTrigger getTrigger() {
        return ActivationTrigger.MULTIPLAYER_JOIN;
    }

    @Override
    public Collection<SecurityPolicyConfig> getConfigs() {
        return List.of(staticConfig);
    }

    /**
     * 接受一份静态配置片段（默认锁），与既有累积配置合并并触发重算。
     *
     * <p>本策略只承载 {@link ConfigLockerPolicyConfig}（其下辖执行器为 {@link ConfigLocker}），
     * 其余类型一律拒绝。由 {@link com.billy65536.infrastructure.security.SecurityPortal} 转发。</p>
     */
    @Override
    public boolean injectStaticConfig(SecurityPolicyConfig config) {
        if (!(config instanceof ConfigLockerPolicyConfig clc)) {
            return false;
        }
        staticConfig = (ConfigLockerPolicyConfig) staticConfig.combine(clc);
        RegistrationCoordinator.recomputeNow(Set.of(ConfigLocker.EXECUTOR_ID));
        return true;
    }

    /**
     * 触发 {@link #LOCKS_APPLIED} 子事件，上报当前锁定表中的条目总数。
     *
     * <p>由 {@link ConfigLocker#onPolicyChanged} 在锁定物化完成后调用。</p>
     */
    public static void fireLocksApplied() {
        LOCKS_APPLIED.invoker().onLocksApplied(ConfigLocker.getLockStatusSnapshot().size());
    }
}
