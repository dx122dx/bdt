package com.billy65536.infrastructure.debugger.builtin.chunkscanner.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.billy65536.chunkscanner.config.ChunkScannerConfig;
import com.billy65536.infrastructure.InfrastructureMod;
import com.billy65536.infrastructure.debugger.core.feature.FeatureRegistry;
import com.billy65536.chunkscanner.security.server_optin.ConfigurationLocker;

import net.minecraft.util.Identifier;

@Mixin(ConfigurationLocker.class)
public class ConfigurationLockerMixin {
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
    private static final Identifier DISABLE_APPLY_ALL_FEAT = InfrastructureMod.id("cs.configuration-locker.disable-apply-all");

    @Inject(method = "applyAll", at = @At("HEAD"), cancellable = true)
    private static void dbgDisableApplyAll(ChunkScannerConfig config, CallbackInfo ci) {
        if (FeatureRegistry.isEnabled(DISABLE_APPLY_ALL_FEAT)) {
            ci.cancel();
        }
    }

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
    private static final Identifier UNLOCK_ALL_FEAT = InfrastructureMod.id("cs.configuration-locker.unlock");

    @Inject(method = "isLocked", at = @At("HEAD"), cancellable = true)
    private static void dbgUnlockIsLocked(String path, CallbackInfoReturnable<Boolean> cir) {
        if (FeatureRegistry.isEnabled(UNLOCK_ALL_FEAT)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "getValueLocked", at = @At("HEAD"), cancellable = true)
    private static void dbgUnlockGetValueLocked(String path, CallbackInfoReturnable<String> cir) {
        if (FeatureRegistry.isEnabled(UNLOCK_ALL_FEAT)) {
            cir.setReturnValue(null);
        }
    }
}
