package org.fruitmc.mango.mixin.vulkan.pipeline;

import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.fruitmc.mango.render.gpu.entity.InstancedEntityRenderer;
import org.fruitmc.mango.render.gpu.hiz.HiZCulling;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FeatureRenderDispatcher.PreparedFrame.class)
public abstract class PreparedFrameMixin {

    @Inject(
        method = "executeSolid()V",
        at = @At("RETURN"),
        require = 1
    )
    private void mango$onExecuteSolidReturn(CallbackInfo ci) {
        HiZCulling.get().captureDepthAndBuildPyramid();
        InstancedEntityRenderer.get().renderSolid();
        InstancedEntityRenderer.getBlockEntity().renderSolid();
    }

    @Inject(
            method = "executeTranslucent()V",
            at = @At("RETURN"),
            require = 1
    )
    private void mango$onExecuteTranslucentReturn(CallbackInfo ci) {
        InstancedEntityRenderer.get().renderTranslucent();
        InstancedEntityRenderer.getBlockEntity().renderTranslucent();
    }
}
