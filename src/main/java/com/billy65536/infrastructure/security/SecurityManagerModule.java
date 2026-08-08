package com.billy65536.infrastructure.security;

import java.util.List;

import com.billy65536.infrastructure.core.config.ConfigDescriptor;
import com.billy65536.infrastructure.core.config.ConfigPath;
import com.billy65536.infrastructure.core.module.IModule;
import com.billy65536.infrastructure.security.builtin.ConfigLocker;
import com.billy65536.infrastructure.security.builtin.ConfigLockerPolicyConfig;
import com.billy65536.infrastructure.security.builtin.ServerOptinPolicy;
import com.billy65536.infrastructure.security.core.policy.ActivationTrigger;
import com.billy65536.infrastructure.security.core.policy.ISecurityPolicy;
import com.billy65536.infrastructure.security.core.policy.SecurityManager;
import com.billy65536.infrastructure.security.pack.PolicyPackManager;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/**
 * 安全模块：可拓展安全策略框架的宿主。
 *
 * <p>职责有三：</p>
 * <ol>
 *   <li>注册框架内置策略（{@link ServerOptinPolicy}）与执行器（{@link ConfigLocker}），
 *       并经 {@link PolicyPackManager} 收集外部 mod 通过
 *       {@code infrastructure:security} entrypoint 贡献的策略；</li>
 *   <li>作为<b>唯一</b>的连接事件监听者，按各策略声明的
 *       {@link ActivationTrigger} 集中判定并批量激活 / 停用——连接判定逻辑全局只有一处；</li>
 *   <li>挂载 {@code /inf security} 命令子树（见 {@link SecurityCommands}）。</li>
 * </ol>
 *
 * <p>本模块同时以普通模块身份贡献自己的配置段 {@code security:config} 与一组默认锁。
 * 默认锁经 {@link SecurityPortal} 注入内置策略，而非直调执行器；其中 {@code allowPolicyOverride}
 * 受默认锁保护，进入多人服务器后被锁定为不可篡改，使熔断能力自身也受框架保护。</p>
 */
public final class SecurityManagerModule implements IModule {

    /**
     * 模块自身版本，格式 {@code YYYYMMDD.N}（日期 + 当日第几次更新）。
     *
     * <p>与宿主模组的 {@code mod_version} 解耦：模块的演进节奏与 infrastructure 整体发版
     * 无关，改动本模块时手工递增本常量即可，不再随模组元数据漂移。</p>
     */
    private static final String VERSION = "20260809.1";

    private SecurityConfig config = new SecurityConfig();

    @Override
    public String getId() {
        return "security";
    }

    @Override
    public String getVersion() {
        return VERSION;
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
     * <p>由模块发现流程调用，而发现挂在 {@code CLIENT_STARTED} 上，此时所有模组的客户端入口点
     * 均已执行完毕，故 entrypoint 收集与连接事件注册在此都是安全的（连接必然晚于启动）。</p>
     */
    @Override
    public void onInitializeModule() {
        // 1) 登记内置执行器（即时生效，作为重算推送的目标）
        SecurityPortal.registerExecutor(reg -> reg.register(ConfigLocker.getInstance()));

        // 2) 覆盖门控读取本模块配置开关的活引用（锁定时字段被强制为 false）；
        //    必须早于 apply()，否则物化重算时门控尚未生效
        SecurityPortal.setGate(() -> config.allowPolicyOverride);

        // 3) 登记框架内置策略（即时生效）
        SecurityPortal.registerPolicy(reg -> reg.register(ServerOptinPolicy.INSTANCE));

        // 4) 经门户注入本模块默认锁（缓冲，待 apply 统一物化）
        SecurityPortal.injectConfig(inj -> inj.inject(ServerOptinPolicy.ID,
                ConfigLockerPolicyConfig.builder(ConfigLocker.EXECUTOR_ID)
                        .lock("security", "config", "allowDebugOverride", "false")
                        .lock("security", "config", "allowPolicyOverride", "false")
                        .build()));

        // 5) 外部 mod 经 "infrastructure:security" entrypoint 贡献的策略（同样经门户缓冲）
        PolicyPackManager.registerAll();

        // 6) 统一物化全部缓冲的静态配置注入；此刻所有策略 / 执行器必然已登记
        SecurityPortal.apply();

        // 7) 唯一的连接事件监听：按 trigger 集中判定
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (isRemoteServer(client)) {
                applyTrigger(ActivationTrigger.MULTIPLAYER_JOIN, true);
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            applyTrigger(ActivationTrigger.MULTIPLAYER_JOIN, false);
            // 断连时丢弃所有外部覆盖补丁，回落静态合并结果
            SecurityPortal.clearOverrides();
        });
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
        for (ISecurityPolicy policy : SecurityManager.getAll()) {
            if (policy.getTrigger() == trigger) {
                SecurityPortal.activatePolicyInternal(policy.getId(), value);
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

        /** 是否允许外部来源（调试动作 / 服务端指令）经 Override 补丁覆盖安全配置；
         *  受默认锁保护，进入多人服务器后锁定为 {@code false}（熔断），防止被外部指令自我解锁。 */
        public boolean allowPolicyOverride = true;
    }
}
