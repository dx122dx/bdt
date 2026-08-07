package com.billy65536.infrastructure.security.core.policy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.billy65536.infrastructure.security.builtin.ConfigLockPatch;

import net.minecraft.util.Identifier;

/**
 * Override 补丁的持有者（纯输入容器），不参与任何决策。
 *
 * <p>外部来源要改写某个执行器的配置，唯一通路是
 * {@link #overrides(Identifier, Identifier)} 拿到该 Config 类型专属的修改器，链式
 * {@code add / remove} 后 {@code apply()} 提交；提交经
 * {@link SecurityManager#submitPatch(SecurityConfigPatch)} 登记，由 Manager 在
 * {@code recompute} 时叠加到静态合并结果之上。</p>
 *
 * <p>本类<b>不</b>持有配置副本的真值——真值始终是「静态 Policy 配置 + 补丁表」经 Manager
 * 重算的产物。它只做两件事：持有补丁表、提供修改器入口。这是与前几轮方案的关键差异：
 * Context 退化为纯输入容器。</p>
 *
 * <h2>会话维度预留</h2>
 *
 * <p>当前为全局单例（单会话场景）。未来多连接隔离时，只需把补丁表按连接 key 分桶，
 * {@code getContext(connectionKey)} 返回对应桶，Manager 的 {@code recompute} 读取当前
 * 活动 key 的补丁即可，合并算法与执行器契约完全不动。</p>
 */
public final class SecurityContext {

    /**
     * 补丁表：按登记顺序保序（登记顺序即 Manager 重算时的应用顺序）。
     *
     * <p>同一 (policyId, executorId) 可登记多条补丁，故不按键去重，使用有序列表。</p>
     */
    private final List<SecurityConfigPatch> patches = new ArrayList<>();

    SecurityContext() {}

    /**
     * 取得针对指定 (policyId, executorId) 的补丁修改器。
     *
     * <p>当前内置执行器只有 {@code ConfigLocker}，故返回 {@link ConfigLockPatch.Builder}；
     * 若该执行器类型未来扩展，修改器类型随之扩展，Manager 侧只依赖
     * {@link SecurityConfigPatch} 多态入口，无需改动。</p>
     *
     * @param policyId    目标策略 id
     * @param executorId  目标执行器 id
     * @return 该 Config 类型的补丁修改器
     */
    public ConfigLockPatch.Builder overrides(Identifier policyId, Identifier executorId) {
        return ConfigLockPatch.builder(policyId, executorId);
    }

    /**
     * 登记一条补丁并触发重算。由修改器的 {@code apply()} 内部调用。
     *
     * @param patch 已构建好的补丁
     */
    void submitPatch(SecurityConfigPatch patch) {
        patches.add(patch);
        SecurityManager.recompute(List.of(patch.getExecutorId()));
    }

    /** 清空全部补丁（断连场景：如退出服务器时丢弃所有外部覆盖）。 */
    public void clearPatches() {
        patches.clear();
    }

    /**
     * 读取参与指定执行器合并的全部补丁（按登记顺序）。
     *
     * @param executorId 执行器 id
     * @return 该执行器的补丁列表（可能为空）
     */
    Collection<SecurityConfigPatch> patchesFor(Identifier executorId) {
        List<SecurityConfigPatch> out = new ArrayList<>();
        for (SecurityConfigPatch p : patches) {
            if (executorId.equals(p.getExecutorId())) {
                out.add(p);
            }
        }
        return out;
    }

    /** 当前登记的补丁总数（诊断用）。 */
    public int patchCount() {
        return patches.size();
    }
}
