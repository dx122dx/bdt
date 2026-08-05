package com.billy65536.chunkscanner.debugger.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.billy65536.chunkscanner.debugger.CsDebuggerMod;
import com.billy65536.chunkscanner.debugger.core.feature.FeatureRegistry;
import com.billy65536.chunkscanner.security.server_optin.ConfigurationLocker;

import net.minecraft.util.Identifier;

/**
 * 让 {@link ConfigurationLocker} 认为所有配置项都未被锁定（调试用）。
 *
 * <p>对应 Feature「解锁全部」：启用时，{@code isLocked} 一律返回 false，
 * {@code getValueLocked} 一律返回 null，使服务端配置锁定机制对该客户端完全失效，
 * 玩家可自由修改任意原本受保护的配置项。</p>
 *
 * <p>通过 Mixin 注入实现，仅在特性启用时拦截返回值；特性未启用时原样放行，
 * 不影响 ChunkScanner 的正常锁定逻辑。</p>
 */
@Mixin(ConfigurationLocker.class)
public abstract class CsUnlockConfigurationLockerMixin {

    private static final Identifier FEATURE = CsDebuggerMod.id("csUnlockConfigurationLocker");

    @Inject(method = "isLocked", at = @At("HEAD"), cancellable = true)
    private static void dbgUnlockIsLocked(String path, CallbackInfoReturnable<Boolean> cir) {
        if (FeatureRegistry.isEnabled(FEATURE)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "getValueLocked", at = @At("HEAD"), cancellable = true)
    private static void dbgUnlockGetValueLocked(String path, CallbackInfoReturnable<String> cir) {
        if (FeatureRegistry.isEnabled(FEATURE)) {
            cir.setReturnValue(null);
        }
    }
}
