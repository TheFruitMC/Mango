package org.fruitmc.mango.mixin.vulkan.entity;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.phys.Vec3;
import org.fruitmc.mango.render.gpu.entity.EntityInstanceBatcher;
import org.fruitmc.mango.render.gpu.entity.EntitySubmitNodeCollectorWrapper;
import org.fruitmc.mango.render.gpu.entity.InstancedEntityRenderer;
import org.fruitmc.mango.render.gpu.skinning.BoneIndexMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {

    private final EntityInstanceBatcher mango$batcher = new EntityInstanceBatcher();
    private final EntitySubmitNodeCollectorWrapper mango$wrapper =
            new EntitySubmitNodeCollectorWrapper(mango$batcher);

    @Inject(
            method = "prepare(Lnet/minecraft/world/phys/Vec3;)V",
            at = @At("HEAD"),
            require = 1
    )
    private void mango$onPrepareHead(Vec3 cameraPos, CallbackInfo ci) {
        mango$batcher.beginFrame(cameraPos);
        InstancedEntityRenderer.getBlockEntity().setBatcher(mango$batcher);
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
            method = "submit(Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderer;submit(Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"
            ),
            index = 2
    )
    private SubmitNodeCollector mango$wrapCollector(SubmitNodeCollector original) {
        mango$wrapper.prepare(original);
        return mango$wrapper;
    }

    @Inject(
            method = "submit(Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderer;submit(Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
                    shift = At.Shift.AFTER
            ),
            require = 1
    )
    private void mango$finishSubmission(CallbackInfo ci) {
        this.mango$wrapper.finishSubmission();
    }

}
