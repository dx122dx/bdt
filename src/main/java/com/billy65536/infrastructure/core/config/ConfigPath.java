package com.billy65536.infrastructure.core.config;

import java.util.Arrays;
import java.util.Objects;

/**
 * 配置路径解析单元：封装「模块 + 自定义段 id + 点分路径」三段结构。
 *
 * <p>完整的用户可见路径形如 {@code <module>:<id>/<dot.path>}，例：
 * {@code chunkscanner:config/components.qshop.highlightEnabled}。其中：</p>
 * <ul>
 *   <li>{@code module} —— 模块 id（无命名空间纯名，如 {@code chunkscanner}）；</li>
 *   <li>{@code id} —— 模块自定义的段名（如 {@code config}），模块可借此把多个配置对象
 *       归到不同命名空间下，避免路径冲突；</li>
 *   <li>{@code path} —— 实际点分配置字段路径（如 {@code components.qshop.highlightEnabled}），
 *       对应配置对象图内的叶子字段。</li>
 * </ul>
 *
 * <p><b>省略规则</b>：当 {@code id} 与 {@code module} 相同时，允许省略，即
 * {@code chunkscanner:chunkscanner/x} 等价简写为 {@code chunkscanner:x}。
 * 这是为「单一配置对象的模块」省去冗余段名——解析时若分号后不含 {@code /}，
 * 即判定为省略形态，把整段当作 {@code path}，{@code id} 取 {@code module}。</p>
 *
 * <p>本类为不可变 record，仅做纯字符串解析，不持有任何配置实例。
 * 模块 id 内的冒号会导致切分错位，沿用 {@code IModule} 的约束（id 不可含冒号）。</p>
 *
 * @param module 模块 id（无命名空间纯名）
 * @param id     模块自定义段名；省略形态下等于 module
 * @param path   点分配置字段路径（不会为空，至少含一段）
 */
public record ConfigPath(String module, String id, String[] path) {

    /** 完整路径分隔符：模块与段之间用前缀 {@code <module>:<id>}。 */
    private static final String PREFIX_SEP = ":";
    /** 段与字段路径之间用 {@code /} 分隔。 */
    private static final String SEG_SEP = "/";

    /**
     * 解析用户可见的完整路径串。
     *
     * <p>支持两种形态：</p>
     * <ul>
     *   <li>完整：{@code module:id/dot.path}（含 {@code /}）；</li>
     *   <li>省略：{@code module:dot.path}（无 {@code /}，id 取 module）。</li>
     * </ul>
     *
     * <p>模块与段之间必须含前缀分隔符 {@code :}；缺失或段为空视为非法，抛出
     * {@link IllegalArgumentException}。路径点分至少一段，空 path 亦非法。</p>
     *
     * @param raw 形如 {@code chunkscanner:config/components.qshop.highlightEnabled}
     *            或省略形态 {@code chunkscanner:components.qshop.highlightEnabled}
     * @return 解析后的 ConfigPath
     * @throws IllegalArgumentException 格式非法（缺前缀分隔符、段或路径为空）
     */
    public static ConfigPath parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("Config path is empty");
        }
        int prefixIdx = raw.indexOf(PREFIX_SEP);
        if (prefixIdx < 0) {
            throw new IllegalArgumentException(
                    "Config path '" + raw + "' missing module prefix (expected '<module>:<id>/<path>')");
        }
        String module = raw.substring(0, prefixIdx);
        if (module.isEmpty()) {
            throw new IllegalArgumentException("Config path '" + raw + "' has empty module");
        }
        String rest = raw.substring(prefixIdx + 1);
        int segIdx = rest.indexOf(SEG_SEP);
        String id;
        String dotPath;
        if (segIdx < 0) {
            // 省略形态：整段当作 path，id 取 module
            id = module;
            dotPath = rest;
        } else {
            id = rest.substring(0, segIdx);
            dotPath = rest.substring(segIdx + 1);
        }
        if (id.isEmpty()) {
            throw new IllegalArgumentException("Config path '" + raw + "' has empty segment id");
        }
        if (dotPath.isEmpty()) {
            throw new IllegalArgumentException("Config path '" + raw + "' has empty field path");
        }
        String[] path = dotPath.split("\\.", -1);
        if (Arrays.stream(path).anyMatch(String::isEmpty)) {
            throw new IllegalArgumentException(
                    "Config path '" + raw + "' has empty segment in field path");
        }
        return new ConfigPath(module, id, path);
    }

    /**
     * 由已知 module/id/path 三段构造（避免重复 split）。
     * {@code dotPath} 允许为空串——表示「整个配置对象」（描述符级路径，无具体字段），
     * 此时 {@code path} 为空数组。外部用户路径一律走 {@link #parse(String)}（必有字段）。
     */
    public static ConfigPath of(String module, String id, String dotPath) {
        if (dotPath == null || dotPath.isEmpty()) {
            return new ConfigPath(module, id, new String[0]);
        }
        String[] p = dotPath.split("\\.", -1);
        if (Arrays.stream(p).anyMatch(String::isEmpty)) {
            throw new IllegalArgumentException("Field path '" + dotPath + "' has empty segment");
        }
        return new ConfigPath(module, id, p);
    }

    /** 点分路径拼接串，如 {@code components.qshop.highlightEnabled}；空数组返回空串。 */
    public String dotPath() {
        return path.length == 0 ? "" : String.join(".", path);
    }

    /**
     * 还原为完整用户可见路径（含段名，不做省略简化）。
     * 即 {@code <module>:<id>/<dot.path>}；无字段时退化为 {@code <module>:<id>}。
     */
    @Override
    public String toString() {
        if (path.length == 0) {
            return module + PREFIX_SEP + id;
        }
        return module + PREFIX_SEP + id + SEG_SEP + dotPath();
    }

    /**
     * 还原为命令行最简形态：当 {@code id == module} 时省略段名
     * （{@code module:dot.path}）；无字段时退化为 {@code module}（id==module）或 {@code module:id}。
     */
    public String toUserString() {
        if (path.length == 0) {
            return Objects.equals(id, module) ? module : module + PREFIX_SEP + id;
        }
        if (Objects.equals(id, module)) {
            return module + PREFIX_SEP + dotPath();
        }
        return toString();
    }
}
