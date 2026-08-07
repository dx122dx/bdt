package com.billy65536.infrastructure.security.api;

/**
 * 外部 mod 向 infrastructure 安全框架注入安全策略的扩展入口。
 *
 * <p>实现本接口，并向自身 {@code fabric.mod.json} 注册自定义 entrypoint
 * {@code "infrastructure:security"}（值为实现类的全限定名），infrastructure 在初始化时
 * 会收集所有此类贡献的策略包并注册。</p>
 *
 * <p>一个策略包对应一个目标模组：仅当该模组已加载时才注册其中的安全策略，
 * 从而让外部 mod 也能以「惰性、目标缺失即跳过」的方式扩展安全框架，
 * 而 infrastructure 自身不再硬编码任何具体目标模组的安全逻辑。</p>
 *
 * <p>示例（fabric.mod.json）：</p>
 * <pre>{@code
 * "entrypoints": {
 *   "infrastructure:security": ["com.example.mymod.MySecurityProvider"]
 * }
 * }</pre>
 *
 * <p>注册入口内部即可通过
 * {@link com.billy65536.infrastructure.security.core.policy.SecurityManager#register}
 * 注册全新策略，或经
 * {@link com.billy65536.infrastructure.security.SecurityPortal}
 * 向内置策略贡献默认锁约束；
 * 同时也可顺势订阅相关策略以静态字段暴露的专属子事件。</p>
 */
@FunctionalInterface
public interface SecurityPolicyProvider {

    /**
     * 向安全框架贡献策略包。
     *
     * <p>在 infrastructure 初始化期间由
     * {@link com.billy65536.infrastructure.security.pack.PolicyPackManager#registerAll()}
     * 调用。本方法只应<b>登记</b>策略包，不应在此直接注册策略或触碰目标模组的类型
     * ——真正的注册动作发生在 {@code entry} 被调用时。</p>
     *
     * <p>本方法抛出的异常会被框架捕获记录，仅导致本 provider 的策略包全部落空，
     * 不影响其他 mod 与宿主游戏启动。</p>
     *
     * @param contributor 策略包登记器，由框架传入
     */
    void contribute(Contributor contributor);

    /** 策略包登记器：外部 mod 通过它声明自己要注入的策略包。 */
    interface Contributor {
        /**
         * 增加一个策略包。
         *
         * <p>框架会先判定 {@code requiredModId} 是否已加载，未加载则跳过本包，
         * 已加载才调用 {@code entry}。</p>
         *
         * <p><b>惰性加载警示</b>：{@code entry} 必须是<b>独立入口类的方法引用</b>
         * （如 {@code XxxSecurityContribution::register}）。若写成 lambda，其方法体会被编译
         * 进调用方所在的类，导致目标模组的类型出现在该类的常量池中；JVM 校验调用方时
         * 就可能提前解析这些引用，在目标模组缺失时抛 {@link NoClassDefFoundError}，
         * 使上面的「未加载则跳过」判定形同虚设。</p>
         *
         * @param requiredModId 目标模组 id，未加载时跳过本包
         * @param displayName   日志展示名
         * @param entry         注册入口，必须是独立入口类的方法引用，理由见上
         */
        void add(String requiredModId, String displayName, Runnable entry);
    }
}
