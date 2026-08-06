package com.billy65536.infrastructure.debugger.api;

/**
 * 外部 mod 向 infrastructure 调试框架注入内置调试包（builtin pack）的扩展入口。
 *
 * <p>实现本接口，并向自身 {@code fabric.mod.json} 注册自定义 entrypoint
 * {@code "infrastructure:debugger"}（值为实现类的全限定名），infrastructure 在初始化时
 * 会收集所有此类贡献的包并注册。</p>
 *
 * <p>内置包对应一个目标模组：仅当该模组已加载时才注册其中的调试项，
 * 从而让外部 mod 也能以「惰性、目标缺失即跳过」的方式扩展调试框架，
 * 而 infrastructure 自身不再硬编码任何具体目标模组的调试逻辑。</p>
 *
 * <p>示例（fabric.mod.json）：</p>
 * <pre>{@code
 * "entrypoints": {
 *   "infrastructure:debugger": ["com.example.mymod.MyBuiltinProvider"]
 * }
 * }</pre>
 */
@FunctionalInterface
public interface DebuggerBuiltinProvider {

    /** 向调试框架贡献内置包。 */
    void contribute(Contributor contributor);

    /** 内置包登记器：外部 mod 通过它声明自己要注入的包。 */
    interface Contributor {
        /**
         * 增加一个内置包。
         *
         * @param requiredModId 目标模组 id，未加载时跳过本包
         * @param displayName   日志展示名
         * @param entry         注册入口（Runnable，仅在目标模组在场时被调用）
         */
        void add(String requiredModId, String displayName, Runnable entry);
    }
}
