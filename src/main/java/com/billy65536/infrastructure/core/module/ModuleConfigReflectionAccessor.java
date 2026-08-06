package com.billy65536.infrastructure.core.module;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.billy65536.infrastructure.core.config.ConfigDescriptor;

/**
 * 通用配置反射访问器，为 {@code /inf config get|set|reset|gui} 与
 * {@code ConfigLocker} 提供支撑。
 *
 * <p>与 chunkscanner 的 {@code ConfigReflectionAccessor}（硬编码 {@code ChunkScannerConfig.class}）不同，
 * 本访问器<b>基于 {@link ConfigDescriptor}</b>：每个模块通过描述符暴露配置实例，
 * 访问器按描述符持有的配置对象的 {@link Class} 参数化地构建点分路径索引，
 * 默认值通过无参构造创建快照。索引与默认值实例均按 Class 缓存
 * （{@link ConcurrentHashMap#computeIfAbsent}），每个配置类仅构建一次。</p>
 *
 * <p>所有方法接收 {@link ConfigDescriptor}（而非具体类型），通过
 * {@link ConfigDescriptor#getConfig()} 现取活动实例做反射读写——
 * 配置类无需在编译期对框架可见，模块可位于任意 mod 内。</p>
 *
 * <p>索引构建规则：递归遍历对象图，仅纳入 public、非 static、非 transient、非 synthetic 的字段；
 * 基本类型 / 包装类 / String / 枚举视为叶子（停止递归），其余 POJO 继续向下展开。
 * 使用 {@link LinkedHashMap} 保证路径顺序与字段声明顺序一致（影响补全与列举）。</p>
 */
public final class ModuleConfigReflectionAccessor {

    private ModuleConfigReflectionAccessor() {}

    /** Class → 点分路径到 Field 链（从根到叶）索引，按类缓存。 */
    private static final Map<Class<?>, Map<String, Field[]>> INDEX_CACHE = new ConcurrentHashMap<>();

    /** Class → 默认值实例快照，按类缓存。 */
    private static final Map<Class<?>, Object> PRISTINE_CACHE = new ConcurrentHashMap<>();

    /** 配置访问异常：路径不存在、值格式非法、无参构造缺失或反射失败。 */
    public static class ConfigAccessException extends Exception {
        public ConfigAccessException(String message) {
            super(message);
        }
    }

    // ==================== 索引构建 ====================

    /** 取得（按需构建并缓存）某配置类的路径索引。 */
    private static Map<String, Field[]> indexOf(Class<?> type) {
        return INDEX_CACHE.computeIfAbsent(type, t -> {
            Map<String, Field[]> map = new LinkedHashMap<>();
            collect(t, "", new ArrayList<>(), map, new HashSet<>(), 0);
            return Collections.unmodifiableMap(map);
        });
    }

    /** 取得（按需创建并缓存）某配置类的默认值实例。无参构造缺失时返回 null。 */
    private static Object pristineOf(Class<?> type) {
        return PRISTINE_CACHE.computeIfAbsent(type, t -> {
            try {
                return t.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                InfrastructureModHolder.LOGGER.warn(
                        "Module config class {} has no accessible no-arg constructor, default-value lookup disabled",
                        t.getName());
                return null;
            }
        });
    }

    /** 递归深度上限：配置对象图正常不会超过数层，超出即视为存在环。 */
    private static final int MAX_DEPTH = 8;

    /**
     * 递归收集叶子字段路径。
     *
     * <p>用 {@code getFields()} 而非 {@code getDeclaredFields()}：本方法只纳入 public 字段，
     * 前者同时覆盖继承自基类的 public 字段，避免配置类继承时静默丢路径。</p>
     */
    private static void collect(Class<?> type, String prefix, List<Field> chain,
            Map<String, Field[]> out, Set<Class<?>> visiting, int depth) {
        if (depth > MAX_DEPTH || !visiting.add(type)) {
            InfrastructureModHolder.LOGGER.warn(
                    "Config path '{}' skipped: cyclic or too deeply nested type {}",
                    prefix.isEmpty() ? "<root>" : prefix, type.getName());
            return;
        }
        try {
            for (Field f : type.getFields()) {
                int mod = f.getModifiers();
                if (Modifier.isStatic(mod) || Modifier.isTransient(mod) || f.isSynthetic()) continue;

                String path = prefix.isEmpty() ? f.getName() : prefix + "." + f.getName();
                chain.add(f);
                if (isLeaf(f.getType())) {
                    out.put(path, chain.toArray(new Field[0]));
                } else {
                    collect(f.getType(), path, chain, out, visiting, depth + 1);
                }
                chain.remove(chain.size() - 1);
            }
        } finally {
            visiting.remove(type);
        }
    }

    /** 叶子类型判定：基本类型、其包装类、String、枚举为叶子，其余 POJO 继续递归。 */
    private static boolean isLeaf(Class<?> t) {
        return t.isPrimitive()
                || t.isEnum()
                || t == String.class
                || t == Integer.class || t == Long.class || t == Double.class
                || t == Float.class || t == Boolean.class || t == Short.class
                || t == Byte.class || t == Character.class;
    }

    // ==================== 查询 ====================

    /** 全部配置路径，按字段声明顺序。descriptor 为 null 或配置为 null 时返回空集合。 */
    public static Collection<String> listPaths(ConfigDescriptor descriptor) {
        Object config = descriptor == null ? null : descriptor.getConfig();
        if (config == null) return List.of();
        return indexOf(config.getClass()).keySet();
    }

    /** 路径是否存在。 */
    public static boolean hasPath(ConfigDescriptor descriptor, String path) {
        Object config = descriptor == null ? null : descriptor.getConfig();
        return config != null && indexOf(config.getClass()).containsKey(path);
    }

    /** 该路径字段类型简名，用于错误提示与 get 展示。路径不存在或 config 为 null 返回 null。 */
    public static String getTypeName(ConfigDescriptor descriptor, String path) {
        Object config = descriptor == null ? null : descriptor.getConfig();
        if (config == null) return null;
        Field[] chain = indexOf(config.getClass()).get(path);
        return chain == null ? null : chain[chain.length - 1].getType().getSimpleName();
    }

    /** 读取配置实例中该路径的值。路径不存在或反射失败返回 null。 */
    public static Object getValue(ConfigDescriptor descriptor, String path) {
        Object config = descriptor == null ? null : descriptor.getConfig();
        return read(config, path);
    }

    /** 该路径的默认值（来自默认值快照）。无快照或路径不存在返回 null。 */
    public static Object getDefaultValue(ConfigDescriptor descriptor, String path) {
        Object config = descriptor == null ? null : descriptor.getConfig();
        if (config == null) return null;
        Object pristine = pristineOf(config.getClass());
        return read(pristine, path);
    }

    private static Object read(Object root, String path) {
        if (root == null) return null;
        Field[] chain = indexOf(root.getClass()).get(path);
        if (chain == null) return null;
        try {
            Object cur = root;
            for (Field f : chain) {
                if (cur == null) return null;
                cur = f.get(cur);
            }
            return cur;
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    // ==================== 写入 ====================

    /**
     * 解析字符串并写入配置实例。
     *
     * @throws ConfigAccessException 路径不存在、值格式非法、无参构造缺失或反射失败
     */
    public static void setValue(ConfigDescriptor descriptor, String path, String rawValue)
            throws ConfigAccessException {
        Object config = requireConfig(descriptor);
        Field[] chain = requireChain(config, path);
        Field leaf = chain[chain.length - 1];
        write(config, chain, parseValue(leaf.getType(), rawValue, path));
    }

    /**
     * 从默认值快照恢复该路径的值。
     *
     * @throws ConfigAccessException 路径不存在、默认值缺失、值格式非法或反射失败
     */
    public static void resetValue(ConfigDescriptor descriptor, String path)
            throws ConfigAccessException {
        Object config = requireConfig(descriptor);
        Field[] chain = requireChain(config, path);
        Object pristine = pristineOf(config.getClass());
        Object defaultValue = read(pristine, path);
        if (defaultValue == null && pristine == null) {
            throw new ConfigAccessException(
                    "Cannot reset '" + path + "': config class has no no-arg constructor for default value");
        }
        write(config, chain, defaultValue);
    }

    /**
     * 服务器锁定重放：强制写入被锁配置项的锁定值（不检查锁定状态）。
     * 专供 {@code ConfigLocker} 内部施加 / 重放强制值使用。
     *
     * <p>强制值约定：{@code forcedValue} 为 {@code null} 表示「仅锁定无强制值」
     * （不写入，返回 null）；空串 {@code ""} 是合法强制值（会写入空串）。</p>
     *
     * @param descriptor 配置描述符（提供活动实例）
     * @param path       字段点分路径
     * @param forcedValue 锁定强制值（null = 仅锁定无强制值）
     * @return 实际写入的值；若仅锁定无强制值（forcedValue 为 null）则返回 null
     * @throws ConfigAccessException 路径不存在、值格式非法或反射失败
     */
    public static Object applyLockedValue(ConfigDescriptor descriptor, String path, String forcedValue)
            throws ConfigAccessException {
        Object config = requireConfig(descriptor);
        Field[] chain = requireChain(config, path);
        if (forcedValue == null) {
            return null; // 仅锁定无强制值，不写入
        }
        Object value = parseValue(chain[chain.length - 1].getType(), forcedValue, path);
        write(config, chain, value);
        return value;
    }

    private static Object requireConfig(ConfigDescriptor descriptor) throws ConfigAccessException {
        if (descriptor == null) {
            throw new ConfigAccessException("Config descriptor is null");
        }
        Object config = descriptor.getConfig();
        if (config == null) {
            throw new ConfigAccessException("Config instance is null");
        }
        return config;
    }

    private static Field[] requireChain(Object config, String path) throws ConfigAccessException {
        if (config == null) {
            throw new ConfigAccessException("Config instance is null");
        }
        Field[] chain = indexOf(config.getClass()).get(path);
        if (chain == null) {
            throw new ConfigAccessException("Unknown config path: " + path);
        }
        return chain;
    }

    /** 沿 Field 链导航到叶子的宿主对象并写入。 */
    private static void write(Object root, Field[] chain, Object value) throws ConfigAccessException {
        try {
            Object holder = root;
            for (int i = 0; i < chain.length - 1; i++) {
                holder = chain[i].get(holder);
                if (holder == null) {
                    throw new ConfigAccessException("Config sub-object is null at: " + chain[i].getName());
                }
            }
            chain[chain.length - 1].set(holder, value);
        } catch (IllegalAccessException e) {
            throw new ConfigAccessException("Failed to write config: " + e.getMessage());
        }
    }

    // ==================== 类型转换 ====================

    /** 按目标类型解析字符串。失败时抛出携带期望类型描述的异常。 */
    private static Object parseValue(Class<?> type, String raw, String path) throws ConfigAccessException {
        String s = raw == null ? "" : raw.trim();
        try {
            if (type == int.class || type == Integer.class) return Integer.parseInt(s);
            if (type == long.class || type == Long.class) return Long.parseLong(s);
            if (type == double.class || type == Double.class) return Double.parseDouble(s);
            if (type == float.class || type == Float.class) return Float.parseFloat(s);
            if (type == short.class || type == Short.class) return Short.parseShort(s);
            if (type == byte.class || type == Byte.class) return Byte.parseByte(s);
        } catch (NumberFormatException e) {
            throw new ConfigAccessException(
                    "Invalid value '" + raw + "' for " + path + ", expected " + type.getSimpleName());
        }

        if (type == boolean.class || type == Boolean.class) {
            // Boolean.parseBoolean 对一切非 "true" 返回 false，会静默吞错，故显式校验
            if ("true".equalsIgnoreCase(s)) return Boolean.TRUE;
            if ("false".equalsIgnoreCase(s)) return Boolean.FALSE;
            throw new ConfigAccessException(
                    "Invalid value '" + raw + "' for " + path + ", expected true or false");
        }

        if (type.isEnum()) {
            for (Object c : type.getEnumConstants()) {
                if (((Enum<?>) c).name().equalsIgnoreCase(s)) return c;
            }
            throw new ConfigAccessException(
                    "Invalid value '" + raw + "' for " + path
                            + ", expected one of: " + String.join(", ", enumNames(type)));
        }

        // String：原样保留（不 trim，路径点名/正则可能含意义前后空格）
        if (type == String.class) return raw == null ? "" : raw;

        throw new ConfigAccessException("Unsupported config type: " + type.getSimpleName());
    }

    // ==================== 补全 ====================

    /** 路径补全：返回全部已知路径前缀匹配 {@code prefix} 的候选（大小写不敏感）。 */
    public static List<String> suggestPaths(ConfigDescriptor descriptor, String prefix) {
        Object config = descriptor == null ? null : descriptor.getConfig();
        if (config == null) return List.of();
        String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String path : indexOf(config.getClass()).keySet()) {
            if (path.toLowerCase(Locale.ROOT).startsWith(p)) out.add(path);
        }
        return out;
    }

    /**
     * value 参数的补全候选。
     * <ul>
     *   <li>boolean → {@code true} / {@code false}</li>
     *   <li>enum → 全部常量名</li>
     *   <li>其余类型 → 当前值（作为可编辑起点）</li>
     * </ul>
     * 路径不存在或 config 为 null 时返回空列表（补全需安全降级，不抛异常）。
     */
    public static List<String> suggestValues(ConfigDescriptor descriptor, String path) {
        Object config = descriptor == null ? null : descriptor.getConfig();
        if (config == null) return List.of();
        Field[] chain = indexOf(config.getClass()).get(path);
        if (chain == null) return List.of();
        Class<?> type = chain[chain.length - 1].getType();

        if (type == boolean.class || type == Boolean.class) {
            return List.of("true", "false");
        }
        if (type.isEnum()) {
            return enumNames(type);
        }
        Object cur = read(config, path);
        return cur == null ? List.of() : List.of(String.valueOf(cur));
    }

    private static List<String> enumNames(Class<?> type) {
        List<String> names = new ArrayList<>();
        for (Object c : type.getEnumConstants()) {
            names.add(((Enum<?>) c).name());
        }
        return names;
    }

    /**
     * 延迟引用 InfrastructureMod.LOGGER，避免访问器静态初始化时触发模组类提前加载。
     */
    private static final class InfrastructureModHolder {
        private static final org.slf4j.Logger LOGGER =
                org.slf4j.LoggerFactory.getLogger(ModuleConfigReflectionAccessor.class);
    }
}
