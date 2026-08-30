package org.fruitmc.mango.mixin.vulkan.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import org.fruitmc.mango.render.gpu.skinning.BoneCaptureContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ModelPart.class)
public abstract class ModelPartMixin {

    private static final int NO_BONE = -1;

    @Inject(
            method = "compile(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V",
            at = @At("HEAD"),
            require = 1
    )
    private void mango$onCompileHead(
            PoseStack.Pose pose, VertexConsumer builder,
            int lightCoords, int overlayCoords, int color,
            CallbackInfo ci
    ) {
        BoneCaptureContext context = BoneCaptureContext.current();
        if (context == null) {
            return;
        }

        ModelPart self = (ModelPart) (Object) this;
        int boneIndex = context.boneIndexMap().indexOf(self);
        if (boneIndex == NO_BONE) {
            return;
        }

        context.receiver().setCurrentBoneIndex(boneIndex);
    }
}
