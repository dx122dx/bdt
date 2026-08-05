package com.billy65536.infrastructure.debugger.builtin.chunkscanner.mixin;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Mixin 条件应用插件：让绑定目标模组的内置 Mixin 仅在该模组存在时生效。
 *
 * <p>本模组不强制依赖任何目标模组，但 {@code builtin.<modid>.mixin} 包下的 Mixin
 * 会注入目标模组的内部类。目标模组缺失时这些 Mixin 必须被跳过，否则 Mixin 子系统
 * 会因找不到目标类而报错。</p>
 *
 * <p>相比引入 conditional-mixin 这类第三方库，本项目只有单个 Mixin、单个条件，
 * 自实现约三十行即可，且不增加运行时依赖与构建期的外部源风险。</p>
 *
 * <p>注意：本类在 Mixin 子系统启动阶段被加载，早于大部分模组初始化，
 * 因此只能使用 {@link FabricLoader} 这类此时已可用的 API，
 * 不可引用本模组自身的 {@code InfrastructureMod} 等尚未初始化的类。</p>
 */
public class ChunkscannerMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger("billy-inf/mixin/chunkscanner");

    private static final String TARGET_MOD_ID = "chunkscanner";

    /** {@code isModLoaded} 结果缓存：本方法在类加载期被高频调用，避免重复查询。 */
    private Boolean chunkscannerLoaded;

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    /**
     * 判定单个 Mixin 是否应当应用。
     *
     * <p>落在内置包下的 Mixin 取决于对应目标模组是否加载；其余（框架自身的通用 Mixin）
     * 一律放行。</p>
     */
    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return isChunkScannerLoaded();
    }

    private boolean isChunkScannerLoaded() {
        Boolean cached = chunkscannerLoaded;
        if (cached == null) {
            cached = FabricLoader.getInstance().isModLoaded(TARGET_MOD_ID);
            chunkscannerLoaded = cached;
            if (!cached) {
                LOGGER.info("Mixins for 'chunkscanner' skipped: mod not loaded.");
            }
        }
        return cached;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
