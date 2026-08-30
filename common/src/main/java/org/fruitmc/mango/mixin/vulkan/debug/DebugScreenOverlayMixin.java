package org.fruitmc.mango.mixin.vulkan.debug;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.fruitmc.mango.render.framegen.FrameGenerationDebug;
import org.fruitmc.mango.render.gpu.entity.EntityRenderDebugMetrics;
import org.fruitmc.mango.render.gpu.hiz.HiZDebugOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(DebugScreenOverlay.class)
public abstract class DebugScreenOverlayMixin {

    @Inject(
        method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/DebugScreenOverlay;"
                + "extractLines(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Ljava/util/List;Z)V",
            ordinal = 0
        ),
        require = 1
    )
    private void mango$appendEntityMetrics(
        GuiGraphicsExtractor graphics,
        CallbackInfo ci,
        @Local(ordinal = 0) List<String> leftLines
    ) {
        if (!Minecraft.getInstance().debugEntries.isOverlayVisible()) {
            return;
        }
        EntityRenderDebugMetrics.appendDebugLines(leftLines);
        HiZDebugOverlay.appendDebugLines(leftLines);
    }

    @Inject(
        method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/DebugScreenOverlay;"
                + "extractLines(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Ljava/util/List;Z)V",
            ordinal = 1
        ),
        require = 1
    )
    private void mango$appendFrameGenInfo(
        GuiGraphicsExtractor graphics,
        CallbackInfo ci,
        @Local(ordinal = 1) List<String> rightLines
    ) {
        if (!Minecraft.getInstance().debugEntries.isOverlayVisible()) {
            return;
        }
        FrameGenerationDebug.appendDebugLines(rightLines);
    }
}
