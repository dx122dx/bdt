package com.billy65536.infrastructure.debugger;

import java.util.Collection;
import java.util.List;

import com.billy65536.infrastructure.InfrastructureMod;
import com.billy65536.infrastructure.core.module.IModule;
import com.billy65536.infrastructure.debugger.config.DebuggerConfigLoader;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.Text;

/**
 * debugger 子模块对 {@link IModule} 的实现，使 debugger 以「模块」身份接入 billy-inf 核心框架。
 *
 * <p>本类通过 Java SPI 自动发现（见
 * {@code META-INF/services/com.billy65536.infrastructure.core.module.IModule}），
 * 由 {@link com.billy65536.infrastructure.core.module.ModuleRegistry#discover()} 统一登记，
 * 无需在启动代码中显式注册。登记后：</p>
 * <ul>
 *   <li>{@code /inf info} 能列出 debugger 并显示其贡献的命令与配置路径；</li>
 *   <li>{@code /inf config get|set|reset debugger:<path>} 可统一读写其配置
 *       （{@link DebuggerConfigLoader} 持有的配置对象）；</li>
 *   <li>{@code /inf dbg ...} 命令树在登记时统一挂入
 *       {@link com.billy65536.infrastructure.core.module.ModuleCommandRegistrar}，
 *       不再由 {@code InfrastructureCommands} 显式挂载。</li>
 * </ul>
 *
 * <p>版本号取自模组元数据（与 billy-inf 一致），无需与 mod_version 手工同步。</p>
 */
public final class DebuggerModule implements IModule {

    private static final String ID = "debugger";

    /** 供 Java SPI 实例化；登记由 {@code ModuleRegistry.discover()} 统一触发。 */
    public DebuggerModule() {}

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getVersion() {
        return FabricLoader.getInstance()
                .getModContainer(InfrastructureMod.MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("?");
    }

    @Override
    public Text getName() {
        return Text.translatable("billy-inf.msg.module_debugger_name");
    }

    @Override
    public Text getDescription() {
        return Text.translatable("billy-inf.msg.module_debugger_desc");
    }

    // ==================== 配置 ====================

    @Override
    public Object getConfig() {
        return DebuggerConfigLoader.get();
    }

    @Override
    public void saveConfig() {
        DebuggerConfigLoader.save();
    }

    // ==================== 命令 ====================

    @Override
    public LiteralArgumentBuilder<FabricClientCommandSource> buildCommands() {
        return DebuggerCommands.buildDbgCommands();
    }

    @Override
    public Collection<String> getCommandLiterals() {
        return List.of("dbg");
    }
}
