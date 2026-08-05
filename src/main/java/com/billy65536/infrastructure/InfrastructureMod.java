package com.billy65536.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.billy65536.infrastructure.core.module.ModuleRegistry;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.util.Identifier;

/**
 * 模组入口。mod id 为 {@code billy-inf}，中文名「Billy's Mod infrastructure」。
 *
 * <p>本模组是通用客户端基础设施，按子模块组织（目前仅有 debugger 调试子模块）。
 * 初始化顺序硬约束：模块自动发现（内含各模块自身的 {@code onInitializeModule}）→ 命令注册。
 * 模块私有的初始化步骤一律下沉到模块自身，本类不感知任何具体子模块。</p>
 */
public final class InfrastructureMod implements ClientModInitializer {

    public static final String MOD_ID = "billy-inf";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static Identifier id(String path) {
        return new Identifier(MOD_ID, path);
    }

    @Override
    public void onInitializeClient() {
        ModuleRegistry.discover();
        InfrastructureCommands.register();
    }
}
