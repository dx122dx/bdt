package com.billy65536.chunkscanner.debugger.builtin.lock;

import com.billy65536.chunkscanner.debugger.CsDebuggerMod;
import com.billy65536.chunkscanner.debugger.core.action.IDebugAction;
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

    public static final Identifier ID = CsDebuggerMod.id("csConfigurationLockerLeaveServer");

    @Override
    public Identifier getId() {
        return ID;
    }

    @Override
    public Text getName() {
        return Text.translatable("chunkscanner-debugger.action.csConfigurationLockerLeaveServer.name");
    }

    @Override
    public Text getDescription() {
        return Text.translatable("chunkscanner-debugger.action.csConfigurationLockerLeaveServer.desc");
    }

    @Override
    public void execute(MinecraftClient client, String[] args) throws Exception {
        ConfigurationLocker.leaveServerLock();
    }
}
