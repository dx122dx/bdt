package com.billy65536.infrastructure.core.security.server;

import com.billy65536.infrastructure.core.security.SecurityPolicyViolationException;

/**
 * 服务端授权缺失异常：尝试修改未被服务器授权的危险配置项目时抛出。
 *
 * <p>原位于 chunkscanner 的 {@code security.server_optin} 包，现上移至 infrastructure
 * 核心安全层。语义不变：纯客户端模组本身不定义服务端授权信号，进入多人服务器时默认
 * 锁定（等待授权），未授权即尝试修改被锁项即抛此异常。</p>
 */
public class ServerAuthorizationRequiredException extends SecurityPolicyViolationException {
    public ServerAuthorizationRequiredException(String message) {
        super(message, "server_authorization");
    }
}
