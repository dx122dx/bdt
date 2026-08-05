package com.billy65536.debugger;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.util.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.billy65536.debugger.builtin.BuiltinsManager;
import com.billy65536.debugger.config.DebuggerConfigLoader;
import com.billy65536.debugger.config.FeatureStateStore;
import com.billy65536.debugger.core.action.ActionRegistry;
import com.billy65536.debugger.core.feature.FeatureRegistry;

/**
 * Billy's Debug Tools 客户端入口。
 *
 * <p>提供一套通用的调试扩展框架：Action（一次性调试动作）与 Feature（可开关调试特性），
 * 均以 {@link Identifier} 为唯一标识，通过 {@code /bdt} 命令与配置界面统一管理。</p>
 *
 * <p>本模组不绑定任何特定目标模组。针对具体模组的调试项以「内置组件包」形式存在，
 * 由 {@link BuiltinsManager} 在检测到对应模组已加载时才注册；目标模组缺失时本模组
 * 照常启动，只是相应调试项不出现在列表与界面中。</p>
 *
 * <p>本类只负责装配（注册配置、内置组件、命令），框架本体位于
 * {@code com.billy65536.debugger.core} 包。</p>
 */
public class BdtMod implements ClientModInitializer {

	public static final String MOD_ID = "bdt";

	/** 命令根名。Brigadier 不支持真正的别名，需为每个根名各注册一次命令树。 */
	private static final String[] COMMAND_ROOTS = { "bdt", "billysdebugtools" };

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/**
	 * 构造本模组命名空间下的 {@link Identifier}。
	 *
	 * <p>必须使用构造函数而非 {@code Identifier.of(ns, path)} 两参静态方法——
	 * 后者在 MC 1.20.1 的 Yarn 映射下行为异常，会产生 {@code minecraft:} 前缀。</p>
	 */
	public static Identifier id(String path) {
		return new Identifier(MOD_ID, path);
	}

	/**
	 * 初始化顺序为硬约束，不可调整：
	 * <ol>
	 *   <li>AutoConfig 注册——必须先于任何 {@code DebuggerConfigLoader.get()}；</li>
	 *   <li>特性状态加载——必须先于任何 Feature 注册，否则注册时读不到持久化记录，
	 *       会误用默认值覆盖用户设置；</li>
	 *   <li>内置组件注册；</li>
	 *   <li>命令注册。</li>
	 * </ol>
	 */
	@Override
	public void onInitializeClient() {
		LOGGER.info("Billy's Debug Tools initializing...");

		DebuggerConfigLoader.register();
		FeatureStateStore.load();

		BuiltinsManager.registerAll();

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			for (String root : COMMAND_ROOTS) {
				dispatcher.register(BdtCommands.buildCommands(root));
			}
		});

		LOGGER.info("Billy's Debug Tools initialized! {} action(s), {} feature(s) registered. /bdt list",
				ActionRegistry.size(), FeatureRegistry.size());
	}
}
