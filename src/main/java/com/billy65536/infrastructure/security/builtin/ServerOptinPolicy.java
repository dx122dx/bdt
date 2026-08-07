package com.billy65536.infrastructure.security.builtin;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import com.billy65536.infrastructure.InfrastructureMod;
import com.billy65536.infrastructure.security.core.policy.ActivationTrigger;
import com.billy65536.infrastructure.security.core.policy.ISecurityPolicy;
import com.billy65536.infrastructure.security.core.policy.SecurityManager;
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
 * <p>本策略允许手动开关（{@link #isManuallyToggleable()} 为 {@code true}），以便调试模组与
 * 玩家在必要时经 {@code /inf security active|deactive security:server-optin} 模拟进出服务器
 * 的锁定行为。手动通路与框架自动判定共用
 * {@link SecurityManager#setActive(Identifier, boolean)}，行为完全一致。</p>
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
    public boolean isManuallyToggleable() {
        return true;
    }

    @Override
    public Collection<SecurityPolicyConfig> getConfigs() {
        return List.of(staticConfig);
    }

    /**
     * 注入一份静态配置片段（默认锁），与既有累积配置合并，并触发重算。
     *
     * <p>由 {@link com.billy65536.infrastructure.security.SecurityPortal} 在模块初始化时调用。</p>
     */
    public static void injectStaticConfig(ConfigLockerPolicyConfig config) {
        staticConfig = (ConfigLockerPolicyConfig) staticConfig.combine(config);
        SecurityManager.recompute(Set.of(ConfigLocker.EXECUTOR_ID));
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
