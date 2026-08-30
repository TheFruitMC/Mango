package org.fruitmc.mango.mixin.vulkan.entity;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.Entity;
import org.fruitmc.mango.render.gpu.entity.EntityInstanceBatcher;
import org.fruitmc.mango.render.gpu.entity.EntitySubmitNodeCollectorWrapper;
import org.fruitmc.mango.render.gpu.entity.InstancedEntityRenderer;
import org.fruitmc.mango.render.gpu.skinning.BoneIndexMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {

    private final EntityInstanceBatcher mango$batcher = new EntityInstanceBatcher();
    private final EntitySubmitNodeCollectorWrapper mango$wrapper =
            new EntitySubmitNodeCollectorWrapper(mango$batcher);

    @Inject(
            method = "prepare(Lnet/minecraft/client/Camera;Lnet/minecraft/world/entity/Entity;)V",
            at = @At("HEAD"),
            require = 1
    )
    private void mango$onPrepareHead(Camera camera, Entity crosshairPickEntity, CallbackInfo ci) {
        mango$batcher.beginFrame(camera.position());
        InstancedEntityRenderer.get().setBatcher(mango$batcher);
    }

    @Inject(
            method = "onResourceManagerReload(Lnet/minecraft/server/packs/resources/ResourceManager;)V",
            at = @At("HEAD"),
            require = 1
    )
    private void mango$releasePersistentMeshes(ResourceManager resourceManager, CallbackInfo ci) {
        this.mango$batcher.clearPersistentResources();
        BoneIndexMap.clearCache();
    }

    @ModifyArg(
            method = "submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lnet/minecraft/client/renderer/state/level/CameraRenderState;DDDLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/EntityRenderer;submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"
            ),
            index = 2
    )
    private SubmitNodeCollector mango$wrapCollector(SubmitNodeCollector original) {
        mango$wrapper.prepare(original);
        return mango$wrapper;
    }

    @Inject(
            method = "submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lnet/minecraft/client/renderer/state/level/CameraRenderState;DDDLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/EntityRenderer;submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
                    shift = At.Shift.AFTER
            ),
            require = 1
    )
    private void mango$finishSubmission(CallbackInfo ci) {
        this.mango$wrapper.finishSubmission();
    }

}
