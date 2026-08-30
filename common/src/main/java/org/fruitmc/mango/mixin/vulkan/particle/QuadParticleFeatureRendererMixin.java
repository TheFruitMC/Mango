package org.fruitmc.mango.mixin.vulkan.particle;

import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.TextureManager;
import org.fruitmc.mango.render.gpu.particle.InstancedParticleRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(QuadParticleFeatureRenderer.class)
public abstract class QuadParticleFeatureRendererMixin {

    @Redirect(
            method = "prepareGroup(Lnet/minecraft/client/renderer/feature/FeatureFrameContext;Ljava/util/List;Z)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/state/level/QuadParticleRenderState;"
                            + "buildLayer(Lnet/minecraft/client/particle/SingleQuadParticle$Layer;"
                            + "Lcom/mojang/blaze3d/vertex/VertexConsumer;)V"
            ),
            require = 1
    )
    private void mango$buildLayer(
            QuadParticleRenderState particles,
            SingleQuadParticle.Layer layer,
            VertexConsumer builder
    ) {
        InstancedParticleRenderer renderer = InstancedParticleRenderer.get();
        if (!renderer.beginLayer(layer)) {
            particles.buildLayer(layer, builder);
            return;
        }
        try {
            particles.buildLayer(layer, builder);
        } finally {
            renderer.endLayer();
        }
    }

    @Inject(
            method = "finishPrepare(Lnet/minecraft/client/renderer/feature/FeatureFrameContext;)V",
            at = @At("RETURN"),
            require = 1
    )
    private void mango$uploadInstances(FeatureFrameContext context, CallbackInfo ci) {
        InstancedParticleRenderer.get().finishPrepare();
    }

    @Redirect(
            method = "executeGroup(Lnet/minecraft/client/renderer/feature/FeatureFrameContext;ILjava/util/List;Z)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/feature/QuadParticleFeatureRenderer;"
                            + "drawLayers(Lnet/minecraft/client/renderer/StagedVertexBuffer;Ljava/util/Map;"
                            + "Lcom/mojang/blaze3d/systems/RenderPass;"
                            + "Lnet/minecraft/client/renderer/texture/TextureManager;)V"
            ),
            require = 1
    )
    private static void mango$drawLayers(
            StagedVertexBuffer stagedBuffer,
            Map<SingleQuadParticle.Layer, StagedVertexBuffer.Draw> layers,
            RenderPass renderPass,
            TextureManager textureManager
    ) {
        InstancedParticleRenderer.get().drawLayers(stagedBuffer, layers, renderPass, textureManager);
    }

    @Inject(
            method = "finishExecute(Lnet/minecraft/client/renderer/feature/FeatureFrameContext;)V",
            at = @At("HEAD"),
            require = 1
    )
    private void mango$retireInstances(FeatureFrameContext context, CallbackInfo ci) {
        InstancedParticleRenderer.get().finishFrame();
    }
}
