package com.billy65536.infrastructure.security.config;

import com.billy65536.infrastructure.InfrastructureMod;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigHolder;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;

/**
 * 安全模块固定配置的加载器：AutoConfig 的薄封装。
 *
 * <p>持久化完全由 AutoConfig 的 {@code GsonConfigSerializer} 接管，
 * 写入 {@code config/infrastructure-security.json}。</p>
 *
 * <p>{@link #register()} 必须在任何 {@link #get()} 调用之前执行（即
 * {@code onInitializeModule} 的最开头，早于门控读取配置）。</p>
 */
public final class SecurityConfigLoader {

    private static ConfigHolder<SecurityConfig> holder;

    private SecurityConfigLoader() {}

    /** 注册 AutoConfig。幂等，重复调用直接返回。 */
    public static void register() {
        if (holder != null) return;
        holder = AutoConfig.register(SecurityConfig.class, GsonConfigSerializer::new);
        InfrastructureMod.LOGGER.info("AutoConfig registered for SecurityConfig.");
    }

    /**
     * 返回 AutoConfig 持有的活动配置实例。
     *
     * <p>不可缓存返回值：AutoConfig 的 ConfigHolder 在 {@code load()} 时会替换
     * 内部实例，缓存引用会读到陈旧对象。每次访问都应重新调用本方法。</p>
     */
    public static SecurityConfig get() {
        return holder().getConfig();
    }

    /** 将当前配置持久化到磁盘。 */
    public static void save() {
        holder().save();
    }

    private static ConfigHolder<SecurityConfig> holder() {
        if (holder == null) {
            throw new IllegalStateException(
                    "SecurityConfigLoader.register() must be called before accessing the config.");
        }
        return holder;
    }
}
