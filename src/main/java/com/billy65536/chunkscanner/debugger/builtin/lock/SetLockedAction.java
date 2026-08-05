package com.billy65536.chunkscanner.debugger.builtin.lock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.billy65536.chunkscanner.config.ConfigReflectionAccessor;
import com.billy65536.chunkscanner.debugger.CsDebuggerMod;
import com.billy65536.chunkscanner.debugger.core.action.IDebugAction;
import com.billy65536.chunkscanner.security.server_optin.ConfigurationLocker;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 调试动作：调用 {@link ConfigurationLocker#setLocked} 模拟服务端锁定。
 *
 * <p>每个参数形如 {@code 路径=值}（如 {@code components.qshop.highlightEnabled=false}），
 * 多参数时逐条登记锁定并立即强制重放。值为空串表示「仅锁定无强制值」，
 * 省略 {@code =} 与值（仅写路径）等价于仅锁定。</p>
 *
 * <p>参数补全：</p>
 * <ul>
 *   <li>当前正在输入第 N 个参数的路径部分时，列出全部配置路径（带 {@code =} 前缀）；</li>
 *   <li>若当前参数已含 {@code =}，依照路径类型列出值候选（boolean→true/false，
 *       enum→常量名，其余→当前值）。</li>
 * </ul>
 */
public class SetLockedAction implements IDebugAction {

    public static final Identifier ID = CsDebuggerMod.id("cs.configuration-locker.set-locked");

    @Override
    public Identifier getId() {
        return ID;
    }

    @Override
    public Text getName() {
        return Text.translatable("chunkscanner-debugger.action.cs.configuration-locker.set-locked.name");
    }

    @Override
    public Text getDescription() {
        return Text.translatable("chunkscanner-debugger.action.cs.configuration-locker.set-locked.desc");
    }

    @Override
    public void execute(MinecraftClient client, String[] args) throws Exception {
        if (args.length == 0) {
            throw new IllegalArgumentException("至少提供一个 路径=值 参数");
        }
        Map<String, String> locks = new LinkedHashMap<>();
        for (String raw : args) {
            int eq = raw.indexOf('=');
            String path;
            String value;
            if (eq < 0) {
                path = raw;
                value = null; // 仅锁定无强制值
            } else {
                path = raw.substring(0, eq);
                value = raw.substring(eq + 1);
                if (value.isEmpty()) {
                    value = ""; // 空串是合法强制值
                }
            }
            if (path.isEmpty()) {
                throw new IllegalArgumentException("空的配置路径: " + raw);
            }
            if (!ConfigReflectionAccessor.hasPath(path)) {
                throw new IllegalArgumentException("未知配置路径: " + path);
            }
            locks.put(path, value);
        }
        ConfigurationLocker.setLocked(locks);
    }

    @Override
    public List<String> suggest(MinecraftClient client, String[] args) {
        // 约定：args 仅含已完成的参数，正在输入的片段由框架按 remaining 做前缀过滤。
        // 因此这里返回所有可能的候选（路径补全 path= + 值补全 path=value），
        // 由框架统一按用户输入片段收窄。配置路径规模极小（数十项），开销可忽略。
        List<String> out = new ArrayList<>();
        for (String path : ConfigReflectionAccessor.listPaths()) {
            out.add(path + "="); // 路径补全：提示「=」后接值
            for (String value : ConfigReflectionAccessor.suggestValues(
                    com.billy65536.chunkscanner.ChunkScannerMod.getConfig(), path)) {
                out.add(path + "=" + value);
            }
        }
        return out;
    }
}
