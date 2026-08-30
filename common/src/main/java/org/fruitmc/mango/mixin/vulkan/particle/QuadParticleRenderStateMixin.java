package org.fruitmc.mango.mixin.vulkan.particle;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import org.fruitmc.mango.render.gpu.particle.InstancedParticleRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(QuadParticleRenderState.class)
public abstract class QuadParticleRenderStateMixin {

    @Inject(
            method = "renderRotatedQuad(Lcom/mojang/blaze3d/vertex/VertexConsumer;FFFFFFFFFFFFII)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 1
    )
    private void mango$collectInstance(
            VertexConsumer builder,
            float x,
            float y,
            float z,
            float xRot,
            float yRot,
            float zRot,
            float wRot,
            float scale,
            float u0,
            float u1,
            float v0,
            float v1,
            int color,
            int lightCoords,
            CallbackInfo ci
    ) {
        boolean collected = InstancedParticleRenderer.get()
                .collect(x, y, z, xRot, yRot, zRot, wRot, scale, u0, u1, v0, v1, color, lightCoords);
        if (collected) {
            ci.cancel();
        }
    }
}
