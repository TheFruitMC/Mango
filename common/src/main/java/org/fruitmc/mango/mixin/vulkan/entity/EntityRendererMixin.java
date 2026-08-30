package org.fruitmc.mango.mixin.vulkan.entity;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.fruitmc.mango.render.gpu.entity.MangoEntityRenderStateBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T extends Entity, S extends EntityRenderState> {

    @Inject(
            method = "extractRenderState(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V",
            at = @At("RETURN"),
            require = 1
    )
    private void mango$captureEntityId(T entity, S state, float partialTicks, CallbackInfo ci) {
        ((MangoEntityRenderStateBridge)state).mango$setEntityId(entity.getId());
    }
}
