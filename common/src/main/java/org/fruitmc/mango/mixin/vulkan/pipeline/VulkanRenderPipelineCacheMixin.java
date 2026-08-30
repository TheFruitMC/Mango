package org.fruitmc.mango.mixin.vulkan.pipeline;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import org.fruitmc.mango.mixin.accessor.GpuDeviceAccessor;
import org.fruitmc.mango.render.vulkan.cache.MangoPipelineCache;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(VulkanRenderPipeline.class)
public abstract class VulkanRenderPipelineCacheMixin {

    @ModifyArg(
        method = "compile",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK12;vkCreateGraphicsPipelines(Lorg/lwjgl/vulkan/VkDevice;JLorg/lwjgl/vulkan/VkGraphicsPipelineCreateInfo$Buffer;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"
        ),
        index = 1,
        require = 2
    )
    private static long mango$supplyGraphicsPipelineCache(long originalCache) {
        if (originalCache != MemoryUtil.NULL) {
            return originalCache;
        }
        GpuDevice gpuDevice = RenderSystem.getDevice();
        if (!(gpuDevice instanceof GpuDeviceAccessor accessor)) {
            return originalCache;
        }
        if (!(accessor.mango$getBackend() instanceof VulkanDevice vkDevice)) {
            return originalCache;
        }
        long handle = MangoPipelineCache.get().handle(vkDevice);
        return handle != MemoryUtil.NULL ? handle : originalCache;
    }
}
