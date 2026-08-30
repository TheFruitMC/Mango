package org.fruitmc.mango.mixin.vulkan.translucent;

import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.fruitmc.mango.render.translucent.MangoVertexSorting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class TranslucentVertexSortingMixin {

    @Inject(
        method = "createVertexSorting(Lnet/minecraft/core/SectionPos;Lnet/minecraft/world/phys/Vec3;)Lcom/mojang/blaze3d/vertex/VertexSorting;",
        at = @At("HEAD"),
        cancellable = true,
        require = 1
    )
    private void mango$useBspVertexSorting(
        SectionPos sectionPos,
        Vec3 cameraPos,
        CallbackInfoReturnable<VertexSorting> cir
    ) {
        cir.setReturnValue(new MangoVertexSorting(
            (float) (cameraPos.x() - sectionPos.minBlockX()),
            (float) (cameraPos.y() - sectionPos.minBlockY()),
            (float) (cameraPos.z() - sectionPos.minBlockZ())
        ));
    }
}
