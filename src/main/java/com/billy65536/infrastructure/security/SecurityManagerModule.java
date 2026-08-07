package com.billy65536.infrastructure.security;

import java.util.List;
import java.util.Map;

import com.billy65536.infrastructure.InfrastructureMod;
import com.billy65536.infrastructure.core.config.ConfigDescriptor;
import com.billy65536.infrastructure.core.config.ConfigPath;
import com.billy65536.infrastructure.core.module.IModule;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.text.Text;

/**
 * 安全管理模块：作为 infrastructure 的一级「模块」接入（id={@code security}）。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>统一登记并初始化配置锁定层（{@link ConfigLocker}）；</li>
 *   <li>贡献通用安全默认锁定项（如「禁止客户端绕过调试锁」），经
 *       {@link ConfigLocker#registerDefaultLocks(String, Map)} 注册；</li>
 *   <li>提供 {@code /inf security status} 命令，展示当前服务器锁定的完整状态快照。</li>
 * </ul>
 *
 * <p>本模块本身是安全策略的「宿主」，而非被保护对象：默认锁是给<b>下游模组</b>的配置预留的
 * 通用样板（目前先登记基础设施自身的演示锁），真正的锁值仍由服务器连接期经
 * {@link ConfigLocker#enterServerLock(String)} 注入。</p>
 */
public final class SecurityManagerModule implements IModule {

    /** 通用安全默认锁：演示项，禁止客户端绕过调试锁。 */
    private static final Map<String, String> DEFAULT_LOCKS = Map.of(
            "security:config/allowDebugOverride", "false"
    );

    private SecurityConfig config = new SecurityConfig();

    @Override
    public String getId() {
        return "security";
    }

    @Override
    public String getVersion() {
        return InfrastructureMod.class.getPackage().getImplementationVersion() != null
                ? InfrastructureMod.class.getPackage().getImplementationVersion()
                : "0.1.0";
    }

    @Override
    public Text getName() {
        return Text.translatable("infrastructure.module.security.name");
    }

    @Override
    public Text getDescription() {
        return Text.translatable("infrastructure.module.security.description");
    }

    @Override
    public void onInitializeModule() {
        // 注册基础设施自带的通用安全默认锁。下游模组亦可调用同一 API 追加各自默认锁。
        ConfigLocker.registerDefaultLocks("security", DEFAULT_LOCKS);
        // 集中接管「服务端 opt-in」配置锁定的进入/退出生命周期：各模块只需登记默认锁
        // （registerDefaultLocks），无需各自监听连接事件。仅进入多人服务器（非单人/局域网）
        // 时锁定全部受保护项，等待服务器授权信号；退出时统一释放锁定。
        ClientPlayConnectionEvents.JOIN.register((connection, sender, client) -> {
            if (client.getCurrentServerEntry() != null && !client.isIntegratedServerRunning()) {
                ConfigLocker.enterServerLock();
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((connection, client) -> {
            ConfigLocker.leaveServerLock();
        });
    }

    @Override
    public List<ConfigDescriptor> getConfigDescriptors() {
        // 危险项：该段含服务器可锁定字段，标记为 dangerous。
        ConfigPath path = ConfigPath.of("security", "config", "");
        return List.of(ConfigDescriptor.dangerous(
                path,
                (java.util.function.Supplier<Object>) () -> config,
                config,
                null));
    }

    @Override
    public List<String> getCommandLiterals() {
        return List.of("security");
    }

    @Override
    public LiteralArgumentBuilder<FabricClientCommandSource> buildCommands() {
        return ClientCommandManager.literal("security")
                .then(ClientCommandManager.literal("status")
                        .executes(ctx -> {
                            FabricClientCommandSource src = ctx.getSource();
                            Map<String, String> snapshot = ConfigLocker.getLockStatusSnapshot();
                            if (snapshot.isEmpty()) {
                                src.sendFeedback(Text.translatable(
                                        "infrastructure.command.security.status.empty"));
                            } else {
                                src.sendFeedback(Text.translatable(
                                        "infrastructure.command.security.status.header",
                                        snapshot.size()));
                                snapshot.forEach((k, v) -> src.sendFeedback(Text.literal("  ")
                                        .append(Text.literal(k).styled(s -> s.withColor(0xAAAAAA)))
                                        .append(Text.literal(" = "))
                                        .append(Text.literal(v))));
                            }
                            return 1;
                        }));
    }

    /** 安全模块的轻量配置对象（目前仅含一个演示性锁定项）。 */
    public static final class SecurityConfig {
        /** 是否允许客户端绕过调试锁；默认 false（由默认锁强制）。 */
        public boolean allowDebugOverride = false;
    }
}
