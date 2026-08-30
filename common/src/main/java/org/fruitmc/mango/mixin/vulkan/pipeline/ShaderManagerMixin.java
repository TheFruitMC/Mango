package org.fruitmc.mango.mixin.vulkan.pipeline;

import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.fruitmc.mango.render.gpu.pipeline.MangoPipelinePrecompiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ShaderManager.class)
public abstract class ShaderManagerMixin {

    @Inject(
            method = "apply(Lnet/minecraft/client/renderer/ShaderManager$Configs;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
            at = @At("RETURN"),
            require = 1
    )
    private void mango$precompilePipelines(
            ShaderManager.Configs preparations,
            ResourceManager manager,
            ProfilerFiller profiler,
            CallbackInfo ci
    ) {
        ShaderManager self = (ShaderManager) (Object) this;
        MangoPipelinePrecompiler.precompileAll(self::getShader);
    }
}
