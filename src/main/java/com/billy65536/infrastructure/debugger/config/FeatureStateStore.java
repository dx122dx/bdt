package com.billy65536.infrastructure.debugger.config;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

import com.billy65536.infrastructure.InfrastructureMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Identifier;

/**
 * 调试特性启用状态的持久化存储。
 *
 * <p>以扁平的 {@code {"命名空间:路径": true/false}} 结构写入
 * {@code config/infrastructure-features.json}。</p>
 *
 * <p>不并入 AutoConfig 的配置文件：特性数量由运行时注册决定，而
 * AutoConfig 的 {@code GsonConfigSerializer} 以静态类结构为准反序列化，
 * 未知字段会被静默丢弃，动态 Map 混入同一文件将在每次保存时被清空。</p>
 *
 * <p>已持久化但当前未注册的条目（对应模块被移除或尚未初始化）会保留在内存中
 * 并原样写回，避免注册顺序变化导致用户设置被静默重置。</p>
 */
public final class FeatureStateStore {

    private static final String FILENAME = "infrastructure-features.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, Boolean>>() {}.getType();

    /** id 字符串 → 启用状态。保序以获得稳定的文件输出。 */
    private static final Map<String, Boolean> states = new LinkedHashMap<>();

    private FeatureStateStore() {}

    /** 配置文件路径。 */
    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILENAME);
    }

    /**
     * 从磁盘加载状态。必须在任何特性注册之前调用。
     *
     * <p>文件缺失、格式损坏或读取失败时降级为空映射并告警，绝不抛异常阻断初始化。</p>
     */
    public static void load() {
        states.clear();
        Path file = path();
        if (!Files.exists(file)) {
            InfrastructureMod.LOGGER.info("No feature state file found, starting with defaults.");
            return;
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, Boolean> loaded = GSON.fromJson(content, MAP_TYPE);
            if (loaded != null) {
                // 过滤 null 值：手工编辑的文件可能出现 "id": null
                loaded.forEach((key, value) -> {
                    if (key != null && value != null) {
                        states.put(key, value);
                    }
                });
            }
            InfrastructureMod.LOGGER.info("Loaded {} feature state(s) from {}", states.size(), file);
        } catch (Exception e) {
            InfrastructureMod.LOGGER.warn("Failed to read feature state file {}, falling back to defaults: {}",
                    file, e.getMessage());
            states.clear();
        }
    }

    /**
     * 查询已持久化的状态。
     *
     * @return 启用状态；返回 null 表示无记录，调用方应采用特性的默认值
     */
    public static Boolean getState(Identifier id) {
        if (id == null) return null;
        return states.get(id.toString());
    }

    /** 写入状态到内存映射，不落盘。 */
    public static void setState(Identifier id, boolean value) {
        if (id == null) return;
        states.put(id.toString(), value);
    }

    /**
     * 将当前状态持久化到磁盘。
     *
     * <p>写入失败仅告警，不抛异常：调试工具的状态丢失不应影响游戏运行。</p>
     */
    public static void save() {
        Path file = path();
        Path tmp = file.resolveSibling(FILENAME + ".tmp");
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // 先写临时文件再原子替换：直接覆盖目标文件时若中途崩溃，
            // 残留的半截 JSON 会在下次 load() 被判为损坏，用户全部开关被静默重置
            Files.writeString(tmp, GSON.toJson(states), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            InfrastructureMod.LOGGER.error("Failed to save feature state file {}: {}", file, e.getMessage());
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 清理失败无需处理：下次 save 会覆盖同名临时文件
            }
        }
    }
}
