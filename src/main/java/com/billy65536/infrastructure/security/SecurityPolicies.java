package com.billy65536.infrastructure.security;

import java.util.Map;

import com.billy65536.infrastructure.security.core.policy.PolicyRegistry;

/**
 * 安全框架面向外部模组的公共门面。
 *
 * <p>外部模组在其安全策略包（经 {@code infrastructure:security} entrypoint 注入）的
 * 注册入口中调用本类的方法，向内置策略贡献自己的安全约束；若需要注册全新的策略，
 * 则直接使用 {@link PolicyRegistry#register}。</p>
 *
 * <p>本类的存在是为了让 {@link ConfigLocker} 的登记入口收敛为包内可见：默认锁必须经由
 * 安全策略框架登记，其来源才能被 {@code /inf security} 追溯。</p>
 */
public final class SecurityPolicies {

    private SecurityPolicies() {}

    /**
     * 向内置 {@code security:server-optin} 策略贡献默认受保护配置项，段名取默认值
     * {@code config}。
     *
     * <p>这些配置项会在该策略激活（默认为进入多人服务器时）后被锁定，并在停用后释放。
     * 应在策略包的注册入口中调用，重复登记同一路径会覆盖先前的强制值。</p>
     *
     * @param moduleId 模块 id（无命名空间纯名）
     * @param locks    纯字段点分路径 → 强制值；值为 {@code null} 表示仅锁定无强制值，
     *                 空串 {@code ""} 则是会被真正写入的合法强制值
     */
    public static void contributeDefaultLocks(String moduleId, Map<String, String> locks) {
        ConfigLocker.registerDefaultLocks(moduleId, locks);
    }

    /**
     * 向内置 {@code security:server-optin} 策略贡献默认受保护配置项，显式指定段名。
     *
     * <p>供配置段名不是默认 {@code config} 的模块使用，其余语义同
     * {@link #contributeDefaultLocks(String, Map)}。</p>
     *
     * @param moduleId 模块 id（无命名空间纯名）
     * @param segment  配置段名，须与该模块配置描述符声明的段名一致
     * @param locks    纯字段点分路径 → 强制值，取值语义同
     *                 {@link #contributeDefaultLocks(String, Map)}
     */
    public static void contributeDefaultLocks(String moduleId, String segment, Map<String, String> locks) {
        ConfigLocker.registerDefaultLocks(moduleId, segment, locks);
    }
}
