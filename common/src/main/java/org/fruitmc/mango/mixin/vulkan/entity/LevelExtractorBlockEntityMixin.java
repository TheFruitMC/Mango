package org.fruitmc.mango.mixin.vulkan.entity;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.fruitmc.mango.render.extract.BlockEntitySectionCuller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LevelExtractor.class)
public abstract class LevelExtractorBlockEntityMixin {

    @WrapOperation(
        method = "extractVisibleBlockEntities("
            + "Lnet/minecraft/client/Camera;F"
            + "Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;visibleSections()"
                + "Lit/unimi/dsi/fastutil/objects/ObjectArrayList;"
        ),
        require = 1
    )
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> mango$cullFarBlockEntitySections(
        LevelRenderer levelRenderer,
        Operation<ObjectArrayList<SectionRenderDispatcher.RenderSection>> original,
        @Local(argsOnly = true) Camera camera
    ) {
        ObjectArrayList<SectionRenderDispatcher.RenderSection> visible = original.call(levelRenderer);
        return BlockEntitySectionCuller.get().retainNearSections(
            visible,
            levelRenderer.blockEntityRenderDispatcher(),
            camera.position(),
            Minecraft.getInstance().options.getEffectiveRenderDistance()
        );
    }
}
