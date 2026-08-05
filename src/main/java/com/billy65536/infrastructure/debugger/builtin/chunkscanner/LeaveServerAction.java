package com.billy65536.infrastructure.debugger.builtin.chunkscanner;

import com.billy65536.infrastructure.InfrastructureMod;
import com.billy65536.infrastructure.debugger.core.action.IDebugAction;
import com.billy65536.chunkscanner.security.server_optin.ConfigurationLocker;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 调试动作：调用 {@link ConfigurationLocker#leaveServerLock} 模拟退出服务器。
 *
 * <p>清空全部锁定登记、恢复玩家自由配置，不接收任何参数。</p>
 */
public class LeaveServerAction implements IDebugAction {

    public static final Identifier ID = InfrastructureMod.id("cs.configuration-locker.leave-server");

    @Override
    public Identifier getId() {
        return ID;
    }

    @Override
    public Text getName() {
        return Text.translatable("billy-inf.action.cs.configuration-locker.leave-server.name");
    }

    @Override
    public Text getDescription() {
        return Text.translatable("billy-inf.action.cs.configuration-locker.leave-server.desc");
    }

    @Override
    public void execute(MinecraftClient client, String[] args) throws Exception {
        ConfigurationLocker.leaveServerLock();
    }
}
