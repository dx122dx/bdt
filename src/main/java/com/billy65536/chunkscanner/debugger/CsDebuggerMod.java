package com.billy65536.chunkscanner.debugger;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.util.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.billy65536.chunkscanner.debugger.builtin.lock.DisableApplyAllFeature;
import com.billy65536.chunkscanner.debugger.builtin.lock.EnterServerAction;
import com.billy65536.chunkscanner.debugger.builtin.lock.LeaveServerAction;
import com.billy65536.chunkscanner.debugger.builtin.lock.SetAuthorizedAction;
import com.billy65536.chunkscanner.debugger.builtin.lock.SetLockedAction;
import com.billy65536.chunkscanner.debugger.builtin.lock.UnlockAllFeature;
import com.billy65536.chunkscanner.debugger.config.DebuggerConfigLoader;
import com.billy65536.chunkscanner.debugger.config.FeatureStateStore;
import com.billy65536.chunkscanner.debugger.core.action.ActionRegistry;
import com.billy65536.chunkscanner.debugger.core.feature.FeatureRegistry;

/**
 * ChunkScanner Debugger 客户端入口。
 *
 * <p>提供统一的调试扩展框架：Action（一次性调试动作）与 Feature（可开关调试特性），
 * 均以 {@link Identifier} 为唯一标识，通过 {@code /cs dbg} 命令调用与管理。</p>
 *
 * <p>本类只负责装配（注册配置、命令等），框架本体位于
 * {@code com.billy65536.chunkscanner.debug} 包。</p>
 */
public class CsDebuggerMod implements ClientModInitializer {

	public static final String MOD_ID = "chunkscanner-debugger";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** 构造本模组命名空间下的 {@link Identifier}。*/
	public static Identifier id(String path) {
		return new Identifier(MOD_ID, path);
	}

	@Override
	public void onInitializeClient() {
		LOGGER.info("ChunkScanner Debugger initializing...");

		// 注册 AutoConfig（必须在任何 DebuggerConfigLoader.get() 调用之前）
		DebuggerConfigLoader.register();

		// 加载特性启用状态（必须在任何 FeatureRegistry.register() 之前，
		// 否则注册时读不到持久化记录，会误用默认值覆盖用户设置）
		FeatureStateStore.load();

		// ===== 内置调试单元：配置锁定相关 =====
		// Feature：解锁全部 / 禁用 applyAll（实际拦截逻辑由 Mixin 注入 ConfigurationLocker）
		FeatureRegistry.register(new UnlockAllFeature());
		FeatureRegistry.register(new DisableApplyAllFeature());
		// Action：模拟授权 / 模拟锁定 / 进入服务器 / 离开服务器（直接调用 ConfigurationLocker 静态方法）
		ActionRegistry.register(new SetAuthorizedAction());
		ActionRegistry.register(new SetLockedAction());
		ActionRegistry.register(new EnterServerAction());
		ActionRegistry.register(new LeaveServerAction());

		// 注册命令。Brigadier 对同名根 literal 执行子节点合并而非覆盖，
		// 因此 dbg 分支会自动挂到主模组已建立的命令树上，主模组无需改动。
		// 与主模组一致地为 chunkscanner / cs 两个别名各注册一次。
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(DebuggerCommands.buildDbgCommands("chunkscanner"));
			dispatcher.register(DebuggerCommands.buildDbgCommands("cs"));
		});

		LOGGER.info("ChunkScanner Debugger initialized! {} action(s), {} feature(s) registered. /cs dbg list",
				ActionRegistry.size(), FeatureRegistry.size());
	}
}
