package com.billy65536.infrastructure.core.config;

/**
 * 配置访问异常：路径不存在、值格式非法、无参构造缺失或反射失败。
 *
 * <p>作为 {@link ConfigAccessor} 读写操作的统一受检异常，从原先嵌套在访问器内的
 * 内部类提升为顶层类，使命令层 / 锁定层无需再写 {@code ConfigAccessor.ConfigAccessException}
 * 这样的长限定名。</p>
 */
public class ConfigAccessException extends Exception {
    public ConfigAccessException(String message) {
        super(message);
    }
}
