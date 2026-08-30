package org.fruitmc.mango.mixin.vulkan.pipeline;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import org.fruitmc.mango.render.vulkan.MangoVulkanFeatures;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;
import java.util.Set;

@Mixin(VulkanBackend.class)
public abstract class VulkanBackendMixin {

    @Inject(
        method = "createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;",
        at = @At("HEAD"),
        require = 1
    )
    private static void mango$addIndirectCountExtension(
        Collection<String> deviceExtensions,
        VulkanPhysicalDevice physicalDevice,
        Set<?> vulkanFeatures,
        CallbackInfoReturnable<VkDevice> cir
    ) {
        if (physicalDevice.hasDeviceExtension("VK_KHR_draw_indirect_count")) {
            deviceExtensions.add("VK_KHR_draw_indirect_count");
            MangoVulkanFeatures.setIndirectCountEnabled(true);
        }
    }
}
