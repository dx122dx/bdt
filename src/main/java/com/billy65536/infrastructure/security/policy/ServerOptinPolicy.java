package com.billy65536.infrastructure.security.policy;

import java.util.List;

import com.billy65536.infrastructure.InfrastructureMod;
import com.billy65536.infrastructure.security.core.policy.ActivationTrigger;
import com.billy65536.infrastructure.security.core.policy.ISecurityExecutor;
import com.billy65536.infrastructure.security.core.policy.ISecurityPolicy;
import com.billy65536.infrastructure.security.policy.server_optin.ConfigLocker;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 内置「服务端 opt-in」安全策略，id 为 {@code security:server-optin}。
 *
 * <p>语义：进入多人服务器时，把各模块登记的默认受保护配置项全部锁定，等待服务端
 * 显式授权后方可放开；断开连接时释放全部锁定。实际动作由下辖的
 * {@link ConfigLocker} 执行。</p>
 *
 * <p>本策略允许手动开关（{@link #isManuallyToggleable()} 为 {@code true}），
 * 以便调试模组与玩家在必要时经
 * {@code /inf security active|deactive security:server-optin} 模拟进出服务器的锁定行为。
 * 手动通路与框架自动判定共用
 * {@link com.billy65536.infrastructure.security.core.policy.PolicyRegistry#setActive}，
 * 行为完全一致。</p>
 *
 * <h2>子事件</h2>
 *
 * <p>本策略以静态字段 {@link #LOCKS_APPLIED} 暴露专属子事件，在下辖执行器完成锁定
 * 之后触发。任何持有本类引用的一方均可直接订阅，用于在锁定落地时同步刷新自己的
 * 界面或状态。该事件由
 * {@link com.billy65536.infrastructure.security.SecurityManagerModule} 监听框架级
 * {@link com.billy65536.infrastructure.security.core.event.SecurityEvents#ACTIVATE}
 * 后经 {@link #fireLocksApplied()} 补发。</p>
 */
public final class ServerOptinPolicy implements ISecurityPolicy {

    /** 策略名（不含命名空间），同时用于拼接下辖执行器的 id。 */
    public static final String POLICY_NAME = "server-optin";

    /** 策略 id：{@code security:server-optin}。 */
    public static final Identifier ID = new Identifier("security", POLICY_NAME);

    /** 单例：策略本身无状态，激活态由注册表统一持有。 */
    public static final ServerOptinPolicy INSTANCE = new ServerOptinPolicy();

    /** 锁定生效后的回调。 */
    @FunctionalInterface
    public interface LocksAppliedCallback {
        /**
         * @param lockedCount 触发时锁定表中的条目<b>总数</b>，而非本次新增的条数
         */
        void onLocksApplied(int lockedCount);
    }

    /**
     * 子事件：配置锁定生效后触发（在下辖执行器完成 {@code onEnable} 之后）。
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
    public List<ISecurityExecutor> getExecutors() {
        return List.of(ConfigLocker.getInstance());
    }

    /**
     * 激活回调：空实现。
     *
     * <p>实际锁定由 {@link ConfigLocker} 承担，本策略无需额外动作。
     * {@link #LOCKS_APPLIED} 也不在此发出——注册表的调用顺序是
     * 「策略 {@code onActivate} → 执行器 {@code onEnable}」，此刻锁定尚未生效；
     * 该子事件改由 {@link #fireLocksApplied()} 在锁定完成后补发。</p>
     */
    @Override
    public void onActivate() {
    }

    /**
     * 停用回调：空实现。
     *
     * <p>锁定释放同样由 {@link ConfigLocker} 承担，本策略无需额外动作。</p>
     */
    @Override
    public void onDeactivate() {
    }

    /**
     * 触发 {@link #LOCKS_APPLIED} 子事件，上报当前锁定表中的条目总数。
     *
     * <p>由 {@link com.billy65536.infrastructure.security.SecurityManagerModule} 在框架级
     * {@code ACTIVATE} 事件中调用。该事件在策略 {@code onActivate} 与全部执行器
     * {@code onEnable} 完成之后才触发，故此时锁定必然已经生效。</p>
     */
    public static void fireLocksApplied() {
        LOCKS_APPLIED.invoker().onLocksApplied(ConfigLocker.getLockStatusSnapshot().size());
    }
}
