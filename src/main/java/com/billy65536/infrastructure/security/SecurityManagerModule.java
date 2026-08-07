package com.billy65536.infrastructure.security;

import java.util.List;
import java.util.Map;

import com.billy65536.infrastructure.InfrastructureMod;
import com.billy65536.infrastructure.core.config.ConfigDescriptor;
import com.billy65536.infrastructure.core.config.ConfigPath;
import com.billy65536.infrastructure.core.module.IModule;
import com.billy65536.infrastructure.security.core.event.SecurityEvents;
import com.billy65536.infrastructure.security.core.policy.ActivationTrigger;
import com.billy65536.infrastructure.security.core.policy.ISecurityPolicy;
import com.billy65536.infrastructure.security.core.policy.PolicyRegistry;
import com.billy65536.infrastructure.security.pack.PolicyPackManager;
import com.billy65536.infrastructure.security.policy.ServerOptinPolicy;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/**
 * 安全模块：可拓展安全策略框架的宿主。
 *
 * <p>职责有四：</p>
 * <ol>
 *   <li>注册框架内置策略（{@link ServerOptinPolicy}），并经
 *       {@link PolicyPackManager} 收集外部 mod 通过
 *       {@code infrastructure:security} entrypoint 贡献的策略；</li>
 *   <li>作为<b>唯一</b>的连接事件监听者，按各策略声明的
 *       {@link ActivationTrigger} 集中判定并批量激活 / 停用——这样连接判定逻辑
 *       全局只有一处，各策略保持被动；</li>
 *   <li>在框架级 {@link SecurityEvents#ACTIVATE} 事件中为内置策略补发其子事件，
 *       以保证子事件晚于执行器的实际动作；</li>
 *   <li>挂载 {@code /inf security} 命令子树（见 {@link SecurityCommands}）。</li>
 * </ol>
 *
 * <p>本模块同时以普通模块身份贡献自己的配置段 {@code security:config} 与一组默认锁。</p>
 */
public final class SecurityManagerModule implements IModule {

    /**
     * 本模块自身的默认受保护配置项：进入多人服务器后禁止客户端绕过调试开关。
     *
     * <p>key 为<b>纯字段点分路径</b>，模块与段名前缀由
     * {@link SecurityPolicies#contributeDefaultLocks(String, Map)} 自动补全，
     * 展开后为 {@code security:config/allowDebugOverride}，与
     * {@link #getConfigDescriptors()} 暴露的路径一致。</p>
     */
    private static final Map<String, String> DEFAULT_LOCKS = Map.of(
            "allowDebugOverride", "false"
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

    /**
     * 初始化安全策略框架。
     *
     * <p>由模块发现流程调用，而发现挂在 {@code CLIENT_STARTED} 上，此时所有模组的
     * 客户端入口点均已执行完毕，故 entrypoint 收集与连接事件注册在此都是安全的
     * （连接必然晚于启动）。</p>
     */
    @Override
    public void onInitializeModule() {
        SecurityPolicies.contributeDefaultLocks("security", DEFAULT_LOCKS);

        // 1) 框架内置策略
        PolicyRegistry.register(ServerOptinPolicy.INSTANCE);

        // 2) 外部 mod 经 "infrastructure:security" entrypoint 贡献的策略
        PolicyPackManager.registerAll();

        // 3) 内置策略的子事件：锁定生效后补发，须晚于执行器的实际锁定动作，
        //    故挂在框架级 ACTIVATE 事件上（注册表保证其在执行器 onEnable 之后触发）
        SecurityEvents.ACTIVATE.register(policy -> {
            if (ServerOptinPolicy.ID.equals(policy.getId())) {
                ServerOptinPolicy.fireLocksApplied();
            }
        });

        // 4) 唯一的连接事件监听：按 trigger 集中判定
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (isRemoteServer(client)) {
                applyTrigger(ActivationTrigger.MULTIPLAYER_JOIN, true);
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                applyTrigger(ActivationTrigger.MULTIPLAYER_JOIN, false));
    }

    /**
     * 判定当前是否连接到<b>远程</b>多人服务器。
     *
     * <p>排除单人存档与局域网自开的集成服务器：这两种场景下玩家即服务器管理者，
     * 无需施加「服务端 opt-in」约束。</p>
     */
    private static boolean isRemoteServer(MinecraftClient client) {
        return client != null
                && client.getCurrentServerEntry() != null
                && !client.isIntegratedServerRunning();
    }

    /**
     * 按触发条件批量设置策略激活态。
     *
     * <p>{@link ActivationTrigger#ALWAYS} 的策略不随连接状态变化，
     * {@link ActivationTrigger#MANUAL} 的策略只认手动开关，故都不在此处理。</p>
     */
    private static void applyTrigger(ActivationTrigger trigger, boolean value) {
        for (ISecurityPolicy policy : PolicyRegistry.getAll()) {
            if (policy.getTrigger() == trigger) {
                PolicyRegistry.setActive(policy.getId(), value);
            }
        }
    }

    @Override
    public List<ConfigDescriptor> getConfigDescriptors() {
        ConfigPath path = ConfigPath.of("security", "config", "");
        return List.of(ConfigDescriptor.dangerous(
                path, (java.util.function.Supplier<Object>) () -> config, config, null));
    }

    @Override
    public List<String> getCommandLiterals() {
        return List.of("security");
    }

    @Override
    public LiteralArgumentBuilder<FabricClientCommandSource> buildCommands() {
        return SecurityCommands.buildSecurityCommands();
    }

    /** 安全模块的轻量配置对象，对应配置段 {@code security:config}。 */
    public static final class SecurityConfig {
        /** 是否允许客户端绕过安全约束进行调试；受默认锁保护，正常应保持 {@code false}。 */
        public boolean allowDebugOverride = false;
    }
}
