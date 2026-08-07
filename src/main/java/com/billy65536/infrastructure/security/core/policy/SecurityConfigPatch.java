package com.billy65536.infrastructure.security.core.policy;

import net.minecraft.util.Identifier;

/**
 * 受控补丁的标记接口（Override 机制的核心载体）。
 *
 * <p>外部来源（调试动作、服务端数据包）不可信，其指令一律转换为一条针对
 * 「特定 Policy 的特定 Executor 配置」的补丁，经
 * {@link SecurityManager#submitPatch(SecurityConfigPatch)} 登记后，由 Manager 在重算时
 * 统一合并推送。补丁<b>绝不</b>绕过策略系统直接写执行器内部状态。</p>
 *
 * <p>具体的增删内容由各 Config 配套实现自行承载（例如 {@code ConfigLockPatch} 持
 * {@code adds} 与 {@code removes} 两张表），本接口只声明 Manager 归类所需的
 * {@link #getPolicyId()} / {@link #getExecutorId()} 两个坐标。</p>
 */
public interface SecurityConfigPatch {

    /** 补丁所针对的策略 id。 */
    Identifier getPolicyId();

    /** 补丁所针对的执行器 id（决定这份补丁参与哪个执行器的合并）。 */
    Identifier getExecutorId();
}
