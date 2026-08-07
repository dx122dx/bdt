package com.billy65536.infrastructure.security.pack;

import java.util.ArrayList;
import java.util.List;

import com.billy65536.infrastructure.InfrastructureMod;
import com.billy65536.infrastructure.security.api.SecurityPolicyProvider;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;

/**
 * 外部安全策略包的统一注册出口。
 *
 * <p>每个「策略包」对应一个目标模组，仅当该模组已加载时才注册其安全策略。
 * 这样 infrastructure 可以脱离任何目标模组独立运行。</p>
 *
 * <h2>延迟类加载（关键约束）</h2>
 *
 * <p>策略包中的 Policy / Executor 实现类会在<b>类加载阶段</b>就解析目标模组的类型。
 * 若本类的方法体中直接出现 {@code new XxxPolicy()}，JVM 在校验本类时就可能提前解析
 * 这些常量池引用，目标模组缺失时抛 {@link NoClassDefFoundError}，使
 * {@code isModLoaded} 判断形同虚设。</p>
 *
 * <p>因此本类<b>只引用各策略包的注册入口类</b>，绝不引用其中的具体实现类。入口类作为
 * 独立类，只有在 {@code register()} 被实际调用时才由 JVM 加载并解析其常量池，
 * 此时目标模组必然在场。</p>
 *
 * <h2>扩展方式</h2>
 *
 * <ol>
 *   <li>外部 mod 实现 {@link SecurityPolicyProvider} 接口；</li>
 *   <li>在其 {@code fabric.mod.json} 注册自定义 entrypoint {@code "infrastructure:security"}，
 *       值为该实现类的全限定名；</li>
 *   <li>在 {@link SecurityPolicyProvider#contribute} 中调用
 *       {@code contributor.add(requiredModId, displayName, XxxContribution::register)}。</li>
 * </ol>
 */
public final class PolicyPackManager {

    /**
     * 策略包描述。
     *
     * @param requiredModId 目标模组 id，未加载时跳过本包
     * @param displayName   日志展示名
     * @param entry         注册入口。必须是独立入口类的方法引用，
     *                      不可写成持有目标模组类型的 lambda 闭包，否则会破坏惰性加载
     */
    private record PolicyPack(String requiredModId, String displayName, Runnable entry) {}

    /** 框架内置策略包（当前为空；内置策略由 SecurityManagerModule 直接注册）。 */
    private static final List<PolicyPack> PACKS = List.of();

    private PolicyPackManager() {}

    /**
     * 注册全部策略包：先合并框架内置包与外部 mod 经 entrypoint 注入的包，
     * 再逐包判定目标模组是否加载，已加载才调用其注册入口。
     *
     * <p>异常隔离分两级，均捕获任意 {@link Throwable}（含 {@link NoClassDefFoundError}、
     * {@link LinkageError} 等类加载期错误）：收集阶段单个 provider 抛异常，只丢失该
     * provider 的全部策略包；注册阶段单个包抛异常，只跳过该包。两级都绝不阻断模组
     * 初始化——单个策略包的失效不应连累宿主游戏的启动成功率。</p>
     */
    public static void registerAll() {
        FabricLoader loader = FabricLoader.getInstance();
        List<PolicyPack> all = new ArrayList<>(PACKS);

        // 收集外部 mod 通过 "infrastructure:security" entrypoint 注入的策略包
        for (EntrypointContainer<SecurityPolicyProvider> container
                : loader.getEntrypointContainers("infrastructure:security", SecurityPolicyProvider.class)) {
            try {
                container.getEntrypoint().contribute((requiredModId, displayName, entry) ->
                        all.add(new PolicyPack(requiredModId, displayName, entry)));
            } catch (Throwable t) {
                InfrastructureMod.LOGGER.error("Failed to collect security policy packs from mod '{}'",
                        container.getProvider().getMetadata().getId(), t);
            }
        }

        for (PolicyPack pack : all) {
            if (!loader.isModLoaded(pack.requiredModId())) {
                InfrastructureMod.LOGGER.info("Security policy pack '{}': skipped (mod '{}' not loaded)",
                        pack.displayName(), pack.requiredModId());
                continue;
            }
            try {
                pack.entry().run();
                InfrastructureMod.LOGGER.info("Security policy pack '{}': registered", pack.displayName());
            } catch (Throwable t) {
                InfrastructureMod.LOGGER.error("Security policy pack '{}': registration failed, skipping",
                        pack.displayName(), t);
            }
        }
    }
}
