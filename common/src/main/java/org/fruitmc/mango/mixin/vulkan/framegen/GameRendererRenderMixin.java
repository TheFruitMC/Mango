package org.fruitmc.mango.mixin.vulkan.framegen;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.fruitmc.mango.render.framegen.FrameGenerationRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererRenderMixin {

    @WrapOperation(
        method = "extract(Lnet/minecraft/client/DeltaTracker;Z)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;extract(Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/Camera;F)V"
        ),
        require = 1
    )
    private void mango$extractWorldState(
        LevelExtractor extractor,
        DeltaTracker deltaTracker,
        Camera camera,
        float partialTick,
        Operation<Void> original
    ) {
        if (!FrameGenerationRuntime.isEnabled()) {
            original.call(extractor, deltaTracker, camera, partialTick);
            return;
        }

        GameRenderer renderer = (GameRenderer) (Object) this;
        RenderTarget mainTarget = renderer.mainRenderTarget();
        if (FrameGenerationRuntime.get().shouldSkipWorldExtraction(mainTarget.width, mainTarget.height)) {
            return;
        }
        original.call(extractor, deltaTracker, camera, partialTick);
    }

    @Redirect(
        method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/client/Minecraft;level:Lnet/minecraft/client/multiplayer/ClientLevel;",
            ordinal = 0
        ),
        require = 1
    )
    private ClientLevel mango$selectLevelForRendering(Minecraft minecraft) {
        if (!FrameGenerationRuntime.isEnabled()) {
            return minecraft.level;
        }
        GameRenderer renderer = (GameRenderer) (Object) this;
        GpuTextureView colorView = renderer.mainRenderTarget().getColorTextureView();
        return FrameGenerationRuntime.get().shouldPresentStoredFrame(colorView)
            ? null
            : minecraft.level;
    }

    @Inject(
        method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V",
            shift = At.Shift.AFTER
        ),
        require = 1
    )
    private void mango$handleFrameGeneration(
        DeltaTracker deltaTracker,
        boolean advanceGameTime,
        CallbackInfo ci
    ) {
        if (!FrameGenerationRuntime.isEnabled()) {
            return;
        }
        if (!advanceGameTime || Minecraft.getInstance().level == null) {
            FrameGenerationRuntime.get().pause();
            return;
        }

        GameRenderer renderer = (GameRenderer) (Object) this;
        GpuTextureView colorView = renderer.mainRenderTarget().getColorTextureView();

        if (FrameGenerationRuntime.get().shouldPresentStoredFrame(colorView)) {
            FrameGenerationRuntime.get().presentStoredFrame(colorView);
            return;
        }

        CameraRenderState cameraState = renderer.gameRenderState().levelRenderState.cameraRenderState;
        if (!cameraState.initialized) {
            FrameGenerationRuntime.get().pause();
            return;
        }

        FrameGenerationRuntime.get().processRenderedFrame(
            colorView,
            renderer.mainRenderTarget().getDepthTextureView(),
            cameraState.projectionMatrix,
            cameraState.viewRotationMatrix,
            cameraState.pos.x,
            cameraState.pos.y,
            cameraState.pos.z,
            cameraState.xRot,
            cameraState.yRot
        );
    }

    @Inject(
        method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V",
            shift = At.Shift.BEFORE
        ),
        require = 1
    )
    private void mango$captureWorldDepthBeforeHands(DeltaTracker deltaTracker, CallbackInfo ci) {
        if (!FrameGenerationRuntime.isEnabled()) {
            return;
        }
        GameRenderer renderer = (GameRenderer) (Object) this;
        FrameGenerationRuntime.get().captureWorldDepth(renderer.mainRenderTarget().getDepthTextureView());
    }
}
