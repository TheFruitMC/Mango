package org.fruitmc.mango.mixin.vulkan.framegen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.fruitmc.mango.render.framegen.FrameGenerationRuntime;
import org.fruitmc.mango.render.gpu.terrain.TerrainRenderRouter;
import org.fruitmc.mango.render.vulkan.cache.MangoPipelineCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftRenderFrameMixin {

    @Shadow
    public ClientLevel level;

    @Unique
    private ClientLevel mango$lastLevel;

    @Unique
    private boolean mango$frameGenPreviousEnabled;

    @Inject(
        method = "renderFrame(Z)V",
        at = @At("HEAD"),
        require = 1
    )
    private void mango$frameGenLifecycle(boolean advanceGameTime, CallbackInfo ci) {
        FrameGenerationRuntime manager = FrameGenerationRuntime.get();
        boolean currentlyEnabled = FrameGenerationRuntime.isEnabled();

        if (!currentlyEnabled) {
            manager.onDisabled();
        } else {
            manager.onEnabled();
            ClientLevel currentLevel = this.level;
            if (currentLevel != this.mango$lastLevel) {
                this.mango$lastLevel = currentLevel;
                manager.resetHistory();
            }

            if (!advanceGameTime || currentLevel == null) {
                manager.pause();
            }
        }

        if (currentlyEnabled != this.mango$frameGenPreviousEnabled) {
            this.mango$frameGenPreviousEnabled = currentlyEnabled;
            ((Minecraft) (Object) this).invalidateSurfaceConfiguration();
        }
    }

    @Inject(
        method = "close()V",
        at = @At("HEAD"),
        require = 1
    )
    private void mango$closeFrameGeneration(CallbackInfo ci) {
        FrameGenerationRuntime.get().close();
        TerrainRenderRouter.get().close();
        MangoPipelineCache.get().close();
    }
}
