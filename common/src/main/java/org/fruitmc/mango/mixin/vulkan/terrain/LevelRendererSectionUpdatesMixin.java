package org.fruitmc.mango.mixin.vulkan.terrain;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.SectionPos;
import org.fruitmc.mango.render.gpu.terrain.DirtySectionUpdateCoordinator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererSectionUpdatesMixin {

    @Shadow
    @Final
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;

    @WrapOperation(
        method = "repositionCamera(Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ViewArea;repositionCamera(Lnet/minecraft/core/SectionPos;)Z"
        ),
        require = 1
    )
    private boolean mango$refreshVisibleSectionNodes(
        ViewArea viewArea,
        SectionPos cameraSectionPos,
        Operation<Boolean> original
    ) {
        boolean repositioned = original.call(viewArea, cameraSectionPos);
        if (repositioned) {
            DirtySectionUpdateCoordinator.get().onVisibleSectionNodesChanged(this.visibleSections);
        }
        return repositioned;
    }
}
