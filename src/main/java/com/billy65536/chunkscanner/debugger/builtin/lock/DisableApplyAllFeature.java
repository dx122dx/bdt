package com.billy65536.chunkscanner.debugger.builtin.lock;

import com.billy65536.chunkscanner.debugger.CsDebuggerMod;
import com.billy65536.chunkscanner.debugger.core.feature.IDebugFeature;

import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 调试特性：禁用 {@link com.billy65536.chunkscanner.security.server_optin.ConfigurationLocker}
 * 的 {@code applyAll} 强制重放。
 *
 * <p>实际的拦截逻辑由 {@code ConfigurationLockerApplyAllMixin} 注入完成，
 * 本特性仅作为开关登记到注册表与配置界面，并在启用时由 Mixin 读取启用状态。</p>
 */
public class DisableApplyAllFeature implements IDebugFeature {

    public static final Identifier ID = CsDebuggerMod.id("cs.configuration-locker.disable-apply-all");

    @Override
    public Identifier getId() {
        return ID;
    }

    @Override
    public Text getName() {
        return Text.translatable("chunkscanner-debugger.feature.cs.configuration-locker.disable-apply-all.name");
    }

    @Override
    public Text getDescription() {
        return Text.translatable("chunkscanner-debugger.feature.cs.configuration-locker.disable-apply-all.desc");
    }
}
