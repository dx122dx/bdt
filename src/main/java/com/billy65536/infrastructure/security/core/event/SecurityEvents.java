package com.billy65536.infrastructure.security.core.event;

import com.billy65536.infrastructure.InfrastructureMod;
import com.billy65536.infrastructure.security.core.policy.ISecurityPolicy;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

/**
 * 安全框架的<b>框架级</b>事件容器。
 *
 * <p>这里只放与「策略启停」本身相关的通用事件；策略各自的业务子事件由策略以
 * {@code public static final Event<XxxCallback>} 字段自行暴露，不在此集中登记
 * （参见
 * {@link com.billy65536.infrastructure.security.policy.ServerOptinPolicy#LOCKS_APPLIED}）。</p>
 *
 * <p>事件复用 Fabric 的 {@link EventFactory#createArrayBacked}：其实现基于数组快照 +
 * 无锁遍历，回调触发不产生迭代器分配，是 MC 生态的标准做法。</p>
 *
 * <p>无论策略是被框架自动判定启停，还是被
 * {@code /inf security active|deactive} 手动切换，都会触发这里的事件——二者共用
 * {@link com.billy65536.infrastructure.security.core.policy.PolicyRegistry#setActive}
 * 这一条通路。</p>
 */
public final class SecurityEvents {

    private SecurityEvents() {}

    /** 策略被激活后的回调。 */
    @FunctionalInterface
    public interface PolicyActivated {
        /**
         * @param policy 刚被激活的策略，此时其全部执行器均已完成 {@code onEnable}
         */
        void onActivated(ISecurityPolicy policy);
    }

    /** 策略被停用后的回调。 */
    @FunctionalInterface
    public interface PolicyDeactivated {
        /**
         * @param policy 刚被停用的策略，此时其全部执行器均已完成 {@code onDisable}
         */
        void onDeactivated(ISecurityPolicy policy);
    }

    /**
     * 策略激活事件：在策略的 {@code onActivate} 与全部执行器 {@code onEnable}
     * 完成之后触发。
     *
     * <p>单个监听器抛出的异常会被捕获记录，不影响其余监听器——安全回调异常
     * 绝不能阻断连接流程。</p>
     */
    public static final Event<PolicyActivated> ACTIVATE =
            EventFactory.createArrayBacked(PolicyActivated.class, listeners -> policy -> {
                for (PolicyActivated listener : listeners) {
                    try {
                        listener.onActivated(policy);
                    } catch (Throwable t) {
                        InfrastructureMod.LOGGER.error(
                                "Security policy {} ACTIVATE listener threw an exception",
                                policy.getId(), t);
                    }
                }
            });

    /**
     * 策略停用事件：在全部执行器 {@code onDisable} 与策略的 {@code onDeactivate}
     * 完成之后触发。
     *
     * <p>异常处理同 {@link #ACTIVATE}。</p>
     */
    public static final Event<PolicyDeactivated> DEACTIVATE =
            EventFactory.createArrayBacked(PolicyDeactivated.class, listeners -> policy -> {
                for (PolicyDeactivated listener : listeners) {
                    try {
                        listener.onDeactivated(policy);
                    } catch (Throwable t) {
                        InfrastructureMod.LOGGER.error(
                                "Security policy {} DEACTIVATE listener threw an exception",
                                policy.getId(), t);
                    }
                }
            });
}
