package com.billy65536.infrastructure.security.policy;

import com.billy65536.infrastructure.security.ConfigLocker;
import com.billy65536.infrastructure.security.core.policy.ISecurityExecutor;

import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 配置锁定执行器，id 为 {@code security:server-optin/config-locker}。
 *
 * <p>本类是<b>配置锁定生命周期的唯一入口</b>：{@link ConfigLocker#enterServerLock()} 与
 * {@link ConfigLocker#leaveServerLock()} 只应由此处调用，其他任何地方都不得再直接触发，
 * 否则锁定状态将与策略激活态脱节。</p>
 *
 * <p>{@link ConfigLocker} 自身的锁定状态机（锁定表、强制值重放、防绕过闭环）保持不变，
 * 本执行器仅负责把「策略激活 / 停用」翻译为「进入 / 离开服务器锁定」。</p>
 */
public final class ConfigLockerExecutor implements ISecurityExecutor {

    /** 执行器 id：命名空间为所属模块，path 为「所属策略 / 执行器」。 */
    public static final Identifier ID =
            new Identifier("security", ServerOptinPolicy.POLICY_NAME + "/config-locker");

    /** 单例：执行器无状态，避免重复实例化。 */
    public static final ConfigLockerExecutor INSTANCE = new ConfigLockerExecutor();

    private ConfigLockerExecutor() {}

    @Override
    public Identifier getId() {
        return ID;
    }

    @Override
    public Text getName() {
        return Text.translatable("infrastructure.security.executor.config_locker.name");
    }

    @Override
    public Text getDescription() {
        return Text.translatable("infrastructure.security.executor.config_locker.description");
    }

    /**
     * 锁定全部已登记的默认受保护配置项。
     *
     * <p>幂等：{@code enterServerLock} 内部为 {@code putAll}，重复调用只是覆盖同样的值。</p>
     */
    @Override
    public void onEnable() {
        ConfigLocker.enterServerLock();
    }

    /**
     * 释放全部锁定，恢复玩家自由配置。
     *
     * <p>幂等：{@code leaveServerLock} 内部为 {@code clear}，重复调用无副作用。</p>
     */
    @Override
    public void onDisable() {
        ConfigLocker.leaveServerLock();
    }
}
