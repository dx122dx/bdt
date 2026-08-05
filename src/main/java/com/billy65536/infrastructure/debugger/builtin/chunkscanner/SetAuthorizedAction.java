package com.billy65536.infrastructure.debugger.builtin.chunkscanner;

import java.util.ArrayList;
import java.util.List;

import com.billy65536.chunkscanner.config.ConfigReflectionAccessor;
import com.billy65536.infrastructure.InfrastructureMod;
import com.billy65536.infrastructure.debugger.core.action.IDebugAction;
import com.billy65536.chunkscanner.security.server_optin.ConfigurationLocker;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 调试动作：调用 {@link ConfigurationLocker#setAuthorized} 模拟服务端授权。
 *
 * <p>传入一个或多个配置路径（点分形式，如 {@code components.qshop.highlightEnabled}），
 * 将对应路径从锁定表移除，模拟服务器已授权的效果。可一次解锁多个路径。</p>
 *
 * <p>参数补全：每个参数位均列出 {@link ConfigReflectionAccessor#listPaths()} 的全部路径。</p>
 */
public class SetAuthorizedAction implements IDebugAction {

    public static final Identifier ID = InfrastructureMod.id("cs.configuration-locker.set-authorized");

    @Override
    public Identifier getId() {
        return ID;
    }

    @Override
    public Text getName() {
        return Text.translatable("billy-inf.action.cs.configuration-locker.set-authorized.name");
    }

    @Override
    public Text getDescription() {
        return Text.translatable("billy-inf.action.cs.configuration-locker.set-authorized.desc");
    }

    @Override
    public void execute(MinecraftClient client, String[] args) throws Exception {
        if (args.length == 0) {
            throw new IllegalArgumentException("至少提供一个配置路径参数");
        }
        for (String path : args) {
            if (!ConfigReflectionAccessor.hasPath(path)) {
                throw new IllegalArgumentException("未知配置路径: " + path);
            }
        }
        ConfigurationLocker.setAuthorized(args);
    }

    @Override
    public List<String> suggest(MinecraftClient client, String[] args) {
        return new ArrayList<>(ConfigReflectionAccessor.listPaths());
    }
}
