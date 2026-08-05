package com.billy65536.infrastructure.debugger.builtin.chunkscanner;

import com.billy65536.infrastructure.InfrastructureMod;
import com.billy65536.infrastructure.debugger.core.action.IDebugAction;
import com.billy65536.chunkscanner.security.server_optin.ConfigurationLocker;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 调试动作：调用 {@link ConfigurationLocker#enterServerLock} 模拟进入多人服务器。
 *
 * <p>会按 {@link ConfigurationLocker} 的默认受保护配置进行锁定（等待授权状态），
 * 不接收任何参数。</p>
 */
public class EnterServerAction implements IDebugAction {

    public static final Identifier ID = InfrastructureMod.id("cs.configuration-locker.enter-server");

    @Override
    public Identifier getId() {
        return ID;
    }

    @Override
    public Text getName() {
        return Text.translatable("billy-inf.action.cs.configuration-locker.enter-server.name");
    }

    @Override
    public Text getDescription() {
        return Text.translatable("billy-inf.action.cs.configuration-locker.enter-server.desc");
    }

    @Override
    public void execute(MinecraftClient client, String[] args) throws Exception {
        ConfigurationLocker.enterServerLock();
    }
}
