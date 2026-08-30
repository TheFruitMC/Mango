package org.fruitmc.mango.mixin.vulkan.pipeline;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.vulkan.VulkanConst;
import org.fruitmc.mango.render.vulkan.compute.MangoVulkanUsage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import static org.fruitmc.mango.render.vulkan.MangoVulkanConstants.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.fruitmc.mango.render.vulkan.MangoVulkanConstants.VK_IMAGE_USAGE_STORAGE_BIT;

@Mixin(VulkanConst.class)
public abstract class VulkanConstMixin {

    @ModifyReturnValue(
        method = "bufferUsageToVk(I)I",
        at = @At("RETURN")
    )
    private static int mango$appendStorageBufferUsage(int original, int usage) {
        return (usage & MangoVulkanUsage.STORAGE_BUFFER) != 0
            ? original | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
            : original;
    }

    @ModifyReturnValue(
        method = "textureUsageToVk(ILcom/mojang/blaze3d/GpuFormat;)I",
        at = @At("RETURN")
    )
    private static int mango$appendStorageTextureUsage(int original, int usage, GpuFormat format) {
        return (usage & MangoVulkanUsage.STORAGE_TEXTURE) != 0
            ? original | VK_IMAGE_USAGE_STORAGE_BIT
            : original;
    }
}
