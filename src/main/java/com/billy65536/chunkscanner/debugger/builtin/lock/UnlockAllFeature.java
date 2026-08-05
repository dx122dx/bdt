package com.billy65536.chunkscanner.debugger.builtin.lock;

import com.billy65536.chunkscanner.debugger.CsDebuggerMod;
import com.billy65536.chunkscanner.debugger.core.feature.IDebugFeature;

import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 调试特性：让 {@link com.billy65536.chunkscanner.security.server_optin.ConfigurationLocker}
 * 认为所有配置项都未被锁定。
 *
 * <p>实际的拦截逻辑由 {@code ConfigurationLockerUnlockAllMixin} 注入完成，
 * 本特性仅作为开关登记到注册表与配置界面，并在启用时由 Mixin 读取启用状态。</p>
 */
public class UnlockAllFeature implements IDebugFeature {

    public static final Identifier ID = CsDebuggerMod.id("csUnlockConfigurationLocker");

    @Override
    public Identifier getId() {
        return ID;
    }

    @Override
    public Text getName() {
        return Text.translatable("chunkscanner-debugger.feature.csUnlockConfigurationLocker.name");
    }

    @Override
    public Text getDescription() {
        return Text.translatable("chunkscanner-debugger.feature.csUnlockConfigurationLocker.desc");
    }
}
