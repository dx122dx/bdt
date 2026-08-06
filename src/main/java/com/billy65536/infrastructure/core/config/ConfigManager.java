package com.billy65536.infrastructure.core.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import com.billy65536.infrastructure.core.module.IModule;
import com.billy65536.infrastructure.core.module.ModuleConfigReflectionAccessor;
import com.billy65536.infrastructure.core.module.ModuleRegistry;
import com.billy65536.infrastructure.core.security.SecurityPolicyViolationException;
import com.billy65536.infrastructure.core.security.server.ConfigLocker;

/**
 * 模块配置统一操作管理器（静态工具）。
 *
 * <p>把「用户可见的完整路径 → 模块 → 描述符 → 字段路径」的解析与读写集中在此，
 * 供命令层（{@code /inf config}）与配置锁定层（{@code ConfigLocker}）共用，
 * 使模块配置访问成为 billy-inf 的通用能力。</p>
 *
 * <p>路径解析规则见 {@link ConfigPath}：完整形态 {@code <module>:<id>/<dot.path>}，
 * 省略形态 {@code <module>:<dot.path>}（id==module）。本管理器按 module 查模块，
 * 再在模块的描述符列表中按 id 匹配段，最后用 dot.path 调
 * {@link ModuleConfigReflectionAccessor} 做反射读写。</p>
 */
public final class ConfigManager {

    private ConfigManager() {}

    /** 配置访问异常：路径不存在、值格式非法、无无参构造或反射失败。 */
    public static class ConfigAccessException extends Exception {
        public ConfigAccessException(String message) {
            super(message);
        }
    }

    /**
     * 解析完整路径，返回命中的（模块, 描述符, 字段点分路径）。
     * 任一环缺失即抛 {@link ConfigAccessException}。
     */
    private static Resolved resolve(String fullPath) throws ConfigAccessException {
        ConfigPath cp;
        try {
            cp = ConfigPath.parse(fullPath);
        } catch (IllegalArgumentException e) {
            throw new ConfigAccessException(e.getMessage());
        }
        IModule module = ModuleRegistry.get(cp.module());
        if (module == null) {
            throw new ConfigAccessException("Unknown module: " + cp.module());
        }
        ConfigDescriptor descriptor = findDescriptor(module, cp);
        if (descriptor == null) {
            throw new ConfigAccessException(
                    "Module '" + cp.module() + "' has no config segment '" + cp.id() + "'");
        }
        return new Resolved(module, descriptor, cp.dotPath());
    }

    /** 在模块描述符列表中按 id 匹配段名；id 与 module 相同时退化为匹配任意单段描述符。 */
    private static ConfigDescriptor findDescriptor(IModule module, ConfigPath cp) {
        ConfigDescriptor fallback = null;
        for (ConfigDescriptor d : module.getConfigDescriptors()) {
            ConfigPath dp = d.path();
            if (dp.module().equals(cp.module()) && dp.id().equals(cp.id())) {
                return d;
            }
            // id 省略形态：cp.id()==module，匹配该模块下唯一段名等于 module 的描述符
            if (cp.id().equals(cp.module()) && dp.module().equals(cp.module())
                    && dp.id().equals(cp.module())) {
                fallback = d;
            }
        }
        return fallback;
    }

    /** 全部配置路径（某模块某段），供补全。 */
    public static Collection<String> listPaths(ConfigDescriptor descriptor) {
        return ModuleConfigReflectionAccessor.listPaths(descriptor);
    }

    /** 路径是否存在（基于描述符的配置对象）。 */
    public static boolean hasPath(ConfigDescriptor descriptor, String dotPath) {
        return ModuleConfigReflectionAccessor.hasPath(descriptor, dotPath);
    }

    /** 字段类型简名。 */
    public static String getTypeName(ConfigDescriptor descriptor, String dotPath) {
        return ModuleConfigReflectionAccessor.getTypeName(descriptor, dotPath);
    }

    /** 读取值。 */
    public static Object getValue(ConfigDescriptor descriptor, String dotPath) {
        return ModuleConfigReflectionAccessor.getValue(descriptor, dotPath);
    }

    /** 默认值。 */
    public static Object getDefaultValue(ConfigDescriptor descriptor, String dotPath) {
        return ModuleConfigReflectionAccessor.getDefaultValue(descriptor, dotPath);
    }

    /** 值补全候选。 */
    public static List<String> suggestValues(ConfigDescriptor descriptor, String dotPath) {
        return ModuleConfigReflectionAccessor.suggestValues(descriptor, dotPath);
    }

    /**
     * 写入字符串值。
     * @throws ConfigAccessException 路径不存在、格式非法或锁定禁止
     */
    public static void setValue(ConfigDescriptor descriptor, String dotPath, String raw)
            throws ConfigAccessException {
        // 写入前检查服务器锁定：被锁项禁止修改（防命令通道绕过）。
        String full = ConfigPath.of(descriptor.path().module(), descriptor.path().id(), dotPath).toString();
        if (ConfigLocker.isLocked(full)) {
            throw new ConfigAccessException(
                    "Config '" + full + "' is locked by server policy and cannot be modified");
        }
        try {
            ModuleConfigReflectionAccessor.setValue(descriptor, dotPath, raw);
        } catch (ModuleConfigReflectionAccessor.ConfigAccessException e) {
            throw new ConfigAccessException(e.getMessage());
        }
    }

    /**
     * 重置为默认值。
     * @throws ConfigAccessException 路径不存在、默认值缺失或锁定禁止
     */
    public static void resetValue(ConfigDescriptor descriptor, String dotPath)
            throws ConfigAccessException {
        try {
            ModuleConfigReflectionAccessor.resetValue(descriptor, dotPath);
        } catch (ModuleConfigReflectionAccessor.ConfigAccessException e) {
            throw new ConfigAccessException(e.getMessage());
        }
    }

    /** 解析完整路径并读取字段值（供命令层 get）。 */
    public static Object getValue(String fullPath) throws ConfigAccessException {
        Resolved r = resolve(fullPath);
        return getValue(r.descriptor, r.dotPath);
    }

    /** 解析完整路径并读取默认值（供命令层 get 展示）。 */
    public static Object getDefaultValue(String fullPath) throws ConfigAccessException {
        Resolved r = resolve(fullPath);
        return getDefaultValue(r.descriptor, r.dotPath);
    }

    /** 解析完整路径并写值（供命令层 set）。 */
    public static void setValue(String fullPath, String raw) throws ConfigAccessException {
        Resolved r = resolve(fullPath);
        // 路径已解析（模块/段/字段均存在），再经锁定检查后写入
        if (ConfigLocker.isLocked(fullPath)) {
            throw new ConfigAccessException(
                    "Config '" + fullPath + "' is locked by server policy and cannot be modified");
        }
        setValue(r.descriptor, r.dotPath, raw);
    }

    /** 解析完整路径并重置（供命令层 reset）。 */
    public static void resetValue(String fullPath) throws ConfigAccessException {
        Resolved r = resolve(fullPath);
        resetValue(r.descriptor, r.dotPath);
    }

    /** 补全：解析已输入的前缀，返回候选的完整路径串（含用户最简形态）。 */
    public static List<String> suggestPaths(String prefix) {
        List<String> out = new ArrayList<>();
        // 尝试按已输入前缀匹配 module / segment
        String lower = (prefix == null) ? "" : prefix.toLowerCase(Locale.ROOT);
        for (IModule m : ModuleRegistry.getAll()) {
            for (ConfigDescriptor d : m.getConfigDescriptors()) {
                ConfigPath cp = d.path();
                for (String dotPath : ModuleConfigReflectionAccessor.listPaths(d)) {
                    ConfigPath full = ConfigPath.of(cp.module(), cp.id(), dotPath);
                    String user = full.toUserString();
                    if (user.toLowerCase(Locale.ROOT).startsWith(lower)) {
                        out.add(user);
                    }
                }
            }
        }
        return out;
    }

    /** 补全：赋值候选（路径=value）。 */
    public static List<String> suggestAssignments(String prefix) {
        // 复用 suggestPaths，交由命令层 CliCompletion 拼 =value
        return suggestPaths(prefix);
    }

    /** 内部解析结果。 */
    private record Resolved(IModule module, ConfigDescriptor descriptor, String dotPath) {}
}
