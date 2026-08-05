package com.billy65536.infrastructure.debugger.builtin.chunkscanner;

import com.billy65536.infrastructure.debugger.builtin.BuiltinsManager;
import com.billy65536.infrastructure.debugger.core.action.ActionRegistry;
import com.billy65536.infrastructure.debugger.core.feature.FeatureRegistry;

/**
 * ChunkScanner 内置调试包的注册入口。
 *
 * <p>本类是延迟类加载隔离的落点：{@link BuiltinsManager} 只持有本类的方法引用，
 * 本类常量池中的 ChunkScanner 类型引用只在 {@link #register()} 被实际调用、
 * JVM 加载本类时才解析。因此 ChunkScanner 缺失时，本类及其引用的全部
 * Action / Feature 实现类都不会被触碰。</p>
 *
 * <p>调用方必须先确认 {@code chunkscanner} 已加载，本类不再重复判定。</p>
 */
public final class ChunkScannerBuiltins {

    private ChunkScannerBuiltins() {}

    /** 注册 ChunkScanner 相关的全部调试特性与调试动作。 */
    public static void register() {
        // Feature：解锁全部 / 禁用 applyAll（实际拦截逻辑由 ConfigurationLockerMixin 注入）
        FeatureRegistry.register(new UnlockAllFeature());
        FeatureRegistry.register(new DisableApplyAllFeature());

        // Action：模拟授权 / 模拟锁定 / 进入服务器 / 离开服务器（直接调用 ConfigurationLocker 静态方法）
        ActionRegistry.register(new SetAuthorizedAction());
        ActionRegistry.register(new SetLockedAction());
        ActionRegistry.register(new EnterServerAction());
        ActionRegistry.register(new LeaveServerAction());
    }
}
