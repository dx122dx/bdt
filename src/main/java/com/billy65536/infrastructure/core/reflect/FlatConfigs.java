package com.billy65536.infrastructure.core.reflect;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.billy65536.infrastructure.core.cli.ArgParser;

/**
 * 扁平配置类的通用反射工具，统一处理复制、合并、解析、格式化与键枚举。
 *
 * <h2>定位</h2>
 *
 * <p>本工具仅服务<b>扁平（非嵌套）配置类</b>的<b>同构</b>操作：源对象与目标对象为同一类型，
 * 字段类型完全一致。它刻意<b>不提供</b>跨类异构映射能力——那类「任务配置 → 全局嵌套配置」
 * 的映射属于具体业务语义，应保留手写代码，不应为它引入路径注解与拆箱转换。</p>
 *
 * <p>与 {@code core.config.ConfigAccessor} 不同，本工具不与任何安全体系（ConfigLocker /
 * 审计）耦合：它处理的是任务级临时数据对象，既无配置描述符也不应触发锁定检查。独立成包，
 * 作为纯通用反射工具存在。</p>
 *
 * <h2>性能</h2>
 *
 * <p>按 {@link Class} 缓存一份不可变字段元数据索引（{@link ConcurrentHashMap} +
 * {@link LinkedHashMap} 保序），首次 O(n) 构建，后续操作纯字段读写，无反射查找开销。</p>
 *
 * <h2>行为契约</h2>
 * <ul>
 *   <li>字段类型为 public、非 static、非 transient、非 synthetic 才纳入（与 ConfigAccessor 同规则）。</li>
 *   <li>非叶子字段（嵌套 POJO）记警告并跳过，不递归展开（扁平约束）。</li>
 *   <li>解析容错：未知键、数值格式错误均记警告并跳过，不中断整体解析。</li>
 *   <li>解析结果若全部字段为 null，返回 null，表示「无需配置」。</li>
 *   <li>键名匹配大小写不敏感。</li>
 * </ul>
 */
public final class FlatConfigs {
    /**
     * 标记扁平配置类的字段对应的「短键别名」，用于命令行解析与展示。
     *
     * <p>配合 {@link FlatConfigs} 使用：解析期按 {@code value()} 声明的别名（大小写不敏感）
     * 匹配用户输入的 {@code key=value} 条目；输出期按 {@code display()} 声明的展示键名
     * 生成紧凑单行（未指定时回退首个别名）。</p>
     *
     * <p><b>适用范围：</b>仅用于扁平（非嵌套）配置类。本注解不解决跨类字段映射——
     * 异构映射（如任务配置落入全局配置的嵌套路径）应保留手写业务代码，不走通用工具。</p>
     *
     * <p>未标注本注解的字段以字段名（小写）作为唯一键。两个字段声明同一别名属编程错误，
     * 索引构建期会抛异常。</p>
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface Key {
        /** 短键别名，解析期小写匹配；至少提供一个。 */
        String[] value();

        /**
         * 输出展示用键名（如 {@code initTasks}）；为空时回退 {@code value()[0]}。
         * 仅影响 {@link FlatConfigs#toString} 的输出，不影响解析键匹配。
         */
        String display() default "";
    }


    private FlatConfigs() {}

    /** 延迟引用日志器，避免静态初始化时提前加载模组主类。 */
    private static final class LogHolder {
        private static final org.slf4j.Logger LOGGER =
                org.slf4j.LoggerFactory.getLogger(FlatConfigs.class);
    }

    // ==================== 元数据索引 ====================

    /** Class → 字段元数据索引（保序），按类缓存。 */
    private static final Map<Class<?>, Map<String, FieldMeta>> META_CACHE = new ConcurrentHashMap<>();

    /** 单字段元数据：反射字段 + 解析期小写别名集合 + 对外枚举键 + 展示键名。 */
    private static final class FieldMeta {
        final Field field;
        final List<String> aliases;   // 已小写化，含字段名本身（用于容错匹配）
        final List<String> keys;      // 对外枚举键（仅注解声明的别名；未标注时回退字段名小写）
        final String display;         // toString 展示键；为空时回退首个别名

        FieldMeta(Field field, List<String> aliases, List<String> keys, String display) {
            this.field = field;
            this.aliases = aliases;
            this.keys = keys;
            this.display = display;
        }
    }

    /** 取得（按需构建并缓存）某配置类的字段索引。 */
    private static Map<String, FieldMeta> metaOf(Class<?> type) {
        return META_CACHE.computeIfAbsent(type, FlatConfigs::buildMeta);
    }

    private static Map<String, FieldMeta> buildMeta(Class<?> type) {
        Map<String, FieldMeta> out = new LinkedHashMap<>();
        for (Field f : type.getFields()) {
            int mod = f.getModifiers();
            if (Modifier.isStatic(mod) || Modifier.isTransient(mod) || f.isSynthetic()) continue;
            if (!isLeaf(f.getType())) {
                LogHolder.LOGGER.warn(
                        "FlatConfigs: field '{}' on {} is a non-leaf type and will be ignored (flat-only constraint)",
                        f.getName(), type.getName());
                continue;
            }
            Key ann = f.getAnnotation(Key.class);
            List<String> aliases = new ArrayList<>();
            aliases.add(f.getName().toLowerCase());
            List<String> keys = new ArrayList<>();
            String firstAlias = f.getName().toLowerCase();
            if (ann != null) {
                for (String a : ann.value()) {
                    String key = a.toLowerCase();
                    if (out.values().stream().anyMatch(m -> m.aliases.contains(key))) {
                        throw new IllegalStateException(
                                "FlatConfigs: duplicate alias '" + key + "' declared on field '"
                                        + f.getName() + "' of " + type.getName());
                    }
                    if (keys.isEmpty()) firstAlias = key;
                    aliases.add(key);
                    keys.add(key);
                }
            } else {
                keys.add(f.getName().toLowerCase());
            }
            String display = (ann != null && !ann.display().isEmpty()) ? ann.display() : firstAlias;
            out.put(f.getName(), new FieldMeta(f, aliases, keys, display));
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    /** 叶子类型判定：基本类型、包装类、String、枚举。 */
    private static boolean isLeaf(Class<?> t) {
        return t.isPrimitive()
                || t.isEnum()
                || t == String.class
                || t == Integer.class || t == Long.class || t == Double.class
                || t == Float.class || t == Short.class || t == Byte.class
                || t == Boolean.class || t == Character.class;
    }

    // ==================== 公开 API ====================

    /** 创建 {@code src} 的独立副本（无参构造）。 */
    public static <T> T copy(T src) {
        if (src == null) return null;
        @SuppressWarnings("unchecked")
        Class<T> type = (Class<T>) src.getClass();
        T target = newInstance(type);
        for (FieldMeta m : metaOf(type).values()) {
            try {
                m.field.set(target, m.field.get(src));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("FlatConfigs: failed to copy field " + m.field.getName(), e);
            }
        }
        return target;
    }

    /**
     * 将 {@code delta} 的非 null 字段覆盖到 {@code base} 的副本上，返回新实例（base 不变）。
     * 用于增量合并：仅覆盖指定的字段，保留其他已设置的字段。
     */
    public static <T> T merge(T base, T delta) {
        if (base == null) return copy(delta);
        if (delta == null) return copy(base);
        @SuppressWarnings("unchecked")
        Class<T> type = (Class<T>) base.getClass();
        T result = copy(base);
        for (FieldMeta m : metaOf(type).values()) {
            try {
                Object v = m.field.get(delta);
                if (v != null) m.field.set(result, v);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("FlatConfigs: failed to merge field " + m.field.getName(), e);
            }
        }
        return result;
    }

    /** 解析 {@code key=value} 形式字符串构造实例；空输入或解析后全 null 返回 null。 */
    public static <T> T createFrom(String raw, Class<T> type) {
        return createFrom(ArgParser.parseAssignments(raw), type);
    }

    /**
     * 从已分词的赋值条目构造实例；空输入或解析后全 null 返回 null。
     * 无值条目（{@code hasValue == false}）跳过。
     */
    public static <T> T createFrom(List<ArgParser.Assignment> assignments, Class<T> type) {
        Map<String, FieldMeta> meta = metaOf(type);
        T instance = newInstance(type);
        boolean anySet = false;
        for (ArgParser.Assignment a : assignments) {
            if (!a.hasValue) continue;
            FieldMeta m = resolve(meta, a.key);
            if (m == null) {
                LogHolder.LOGGER.warn("FlatConfigs: unknown config key '{}' for {}", a.key, type.getSimpleName());
                continue;
            }
            try {
                Object val = convert(m.field.getType(), a.value);
                m.field.set(instance, val);
                anySet = true;
            } catch (NumberFormatException e) {
                LogHolder.LOGGER.warn(
                        "FlatConfigs: invalid value '{}' for key '{}' on {}: {}",
                        a.value, a.key, type.getSimpleName(), e.getMessage());
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("FlatConfigs: failed to set field " + m.field.getName(), e);
            }
        }
        return anySet ? instance : null;
    }

    /** 反向输出为紧凑单行 {@code key=value key=value}（按字段声明顺序，用展示键）。全 null 返回空串。 */
    public static String toString(Object obj) {
        if (obj == null) return "";
        Map<String, FieldMeta> meta = metaOf(obj.getClass());
        StringBuilder sb = new StringBuilder();
        for (FieldMeta m : meta.values()) {
            try {
                Object v = m.field.get(obj);
                if (v != null) {
                    sb.append(m.display).append('=').append(v).append(' ');
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("FlatConfigs: failed to read field " + m.field.getName(), e);
            }
        }
        return sb.toString().trim();
    }

    /** 判断全部纳入字段是否均为 null。 */
    public static boolean isAllNull(Object obj) {
        if (obj == null) return true;
        for (FieldMeta m : metaOf(obj.getClass()).values()) {
            try {
                if (m.field.get(obj) != null) return false;
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("FlatConfigs: failed to read field " + m.field.getName(), e);
            }
        }
        return true;
    }

    /** 列出全部可识别解析键（小写别名，未标注注解的字段回退字段名）。供命令补全使用。 */
    public static List<String> keysOf(Class<?> type) {
        List<String> keys = new ArrayList<>();
        for (FieldMeta m : metaOf(type).values()) {
            keys.addAll(m.keys);
        }
        return java.util.Collections.unmodifiableList(keys);
    }

    // ==================== 内部工具 ====================

    private static FieldMeta resolve(Map<String, FieldMeta> meta, String key) {
        String k = key.toLowerCase();
        for (FieldMeta m : meta.values()) {
            if (m.aliases.contains(k)) return m;
        }
        return null;
    }

    private static <T> T newInstance(Class<T> type) {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "FlatConfigs: " + type.getName() + " lacks an accessible no-arg constructor", e);
        }
    }

    /** 将字符串按目标字段类型转换；数值型先 trim，String 原样保留。 */
    private static Object convert(Class<?> type, String raw) {
        if (type == String.class) return raw;
        String s = raw.trim();
        if (type == Integer.class || type == int.class) return Integer.parseInt(s);
        if (type == Long.class || type == long.class) return Long.parseLong(s);
        if (type == Double.class || type == double.class) return Double.parseDouble(s);
        if (type == Float.class || type == float.class) return Float.parseFloat(s);
        if (type == Short.class || type == short.class) return Short.parseShort(s);
        if (type == Byte.class || type == byte.class) return Byte.parseByte(s);
        if (type == Boolean.class || type == boolean.class) return Boolean.parseBoolean(s);
        if (type == Character.class || type == char.class) {
            if (s.isEmpty()) throw new NumberFormatException("empty char");
            return s.charAt(0);
        }
        if (type.isEnum()) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object e = Enum.valueOf((Class<? extends Enum>) type, s);
            return e;
        }
        // 其它类型（如自定义 POJO）不支持，交由上层；此处按 String 兜底
        return raw;
    }
}
