package com.billy65536.infrastructure.security.core.policy;

/**
 * 安全策略的激活触发条件。
 *
 * <p>策略本身保持<b>被动</b>：不自行监听任何游戏事件，由
 * {@link com.billy65536.infrastructure.security.SecurityManagerModule} 集中判定并批量
 * 激活 / 停用。这样连接判定逻辑全局只有一处，避免各策略重复实现且判定不一致。</p>
 */
public enum ActivationTrigger {

    /**
     * 进入多人服务器时自动激活，断开连接时自动停用。
     *
     * <p>激活判定为 {@code getCurrentServerEntry() != null && !isIntegratedServerRunning()}，
     * 即排除单人存档与局域网自开的集成服务器。停用则在任何断开连接时无条件执行。</p>
     */
    MULTIPLAYER_JOIN,

    /**
     * 仅通过手动方式（命令或 API）开关，框架不做任何自动判定。
     *
     * <p>采用本触发条件的策略通常应同时让
     * {@link ISecurityPolicy#isManuallyToggleable()} 返回 {@code true}，否则该策略
     * 将永远无法被激活。</p>
     */
    MANUAL,

    /** 注册即激活，且不随连接状态变化而停用（长期生效的策略）。 */
    ALWAYS
}
