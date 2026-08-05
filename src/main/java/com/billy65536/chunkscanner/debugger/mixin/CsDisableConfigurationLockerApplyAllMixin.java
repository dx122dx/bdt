package com.billy65536.chunkscanner.debugger.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.billy65536.chunkscanner.config.ChunkScannerConfig;
import com.billy65536.chunkscanner.debugger.CsDebuggerMod;
import com.billy65536.chunkscanner.debugger.core.feature.FeatureRegistry;
import com.billy65536.chunkscanner.security.server_optin.ConfigurationLocker;

import net.minecraft.util.Identifier;

/**
 * 禁用 {@link ConfigurationLocker#applyAll}（调试用）。
 *
 * <p>对应 Feature「禁用强制重放」：启用时，{@code applyAll} 直接返回，
 * 不再把锁定值强制写入活动配置。这样即便进入了服务器锁定状态，
 * 玩家在 GUI 中的手动修改也不会被锁定时刻的 {@code applyAll} 覆盖回锁定值。</p>
 *
 * <p>注意：本 Mixin 仅阻止强制重放，不解除 {@code isLocked} 的「禁止修改」标记，
 * 因此若需同时绕过 GUI 的锁定提示，应配合「解锁全部」特性一起使用。</p>
 */
@Mixin(ConfigurationLocker.class)
public abstract class CsDisableConfigurationLockerApplyAllMixin {

    private static final Identifier FEATURE = CsDebuggerMod.id("cs.configuration-locker.disable-apply-all");

    @Inject(method = "applyAll", at = @At("HEAD"), cancellable = true)
    private static void dbgDisableApplyAll(ChunkScannerConfig config, CallbackInfo ci) {
        if (FeatureRegistry.isEnabled(FEATURE)) {
            ci.cancel();
        }
    }
}
