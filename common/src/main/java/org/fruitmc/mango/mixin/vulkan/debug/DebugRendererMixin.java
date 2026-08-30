package org.fruitmc.mango.mixin.vulkan.debug;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.debug.DebugRenderer;
import org.fruitmc.mango.render.gpu.hiz.HiZDebugOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DebugRenderer.class)
public abstract class DebugRendererMixin {

    @Inject(
        method = "emitGizmos(Lnet/minecraft/client/renderer/culling/Frustum;DDDF)V",
        at = @At("TAIL"),
        require = 1
    )
    private void mango$collectHiZStats(
        Frustum frustum,
        double cameraX,
        double cameraY,
        double cameraZ,
        float partialTick,
        CallbackInfo ci
    ) {
        if (!Minecraft.getInstance().debugEntries.isOverlayVisible()) {
            return;
        }
        HiZDebugOverlay.collectStats();
    }
}
