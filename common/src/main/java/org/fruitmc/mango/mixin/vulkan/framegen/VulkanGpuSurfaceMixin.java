package org.fruitmc.mango.mixin.vulkan.framegen;

import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanGpuSurface;
import org.fruitmc.mango.render.framegen.FrameGenerationRuntime;
import org.fruitmc.mango.render.vulkan.compute.MangoComputePipeline;
import org.fruitmc.mango.render.vulkan.compute.MangoVulkanCommandAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.fruitmc.mango.render.vulkan.MangoVulkanConstants.*;

@Mixin(VulkanGpuSurface.class)
public abstract class VulkanGpuSurfaceMixin {

    private static final long SOURCE_STAGES =
        VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT
            | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT
            | VK_PIPELINE_STAGE_2_TRANSFER_BIT;
    private static final long SOURCE_ACCESS =
        VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT
            | VK_ACCESS_2_SHADER_WRITE_BIT
            | VK_ACCESS_2_TRANSFER_WRITE_BIT;
    private static final long DEST_STAGE = VK_PIPELINE_STAGE_2_TRANSFER_BIT;
    private static final long DEST_ACCESS = VK_ACCESS_2_TRANSFER_READ_BIT;

    @Inject(
        method = "blitFromTexture(Lcom/mojang/blaze3d/systems/CommandEncoderBackend;Lcom/mojang/blaze3d/textures/GpuTextureView;)V",
        at = @At("HEAD"),
        require = 1
    )
    private void mango$barrierSourceTextureForBlit(
        CommandEncoderBackend commandEncoder,
        GpuTextureView textureView,
        CallbackInfo ci
    ) {
        if (FrameGenerationRuntime.isEnabled()
            && commandEncoder instanceof MangoVulkanCommandAccess vulkanAccess) {
            MangoComputePipeline.barrierImage(
                vulkanAccess,
                SOURCE_STAGES,
                SOURCE_ACCESS,
                DEST_STAGE,
                DEST_ACCESS,
                VK_IMAGE_LAYOUT_GENERAL,
                VK_IMAGE_LAYOUT_GENERAL,
                textureView
            );
        }
    }

}
