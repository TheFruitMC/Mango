package org.fruitmc.mango.mixin.vulkan.pipeline;

import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import org.fruitmc.mango.render.vulkan.compute.MangoVulkanCommandAccess;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(VulkanCommandEncoder.class)
public abstract class VulkanCommandEncoderMixin implements MangoVulkanCommandAccess {

    @Shadow
    @Final
    private VulkanDevice device;

    @Shadow
    private VkCommandBuffer commandBuffer() {
        throw new AssertionError();
    }

    @Shadow
    public abstract void queueForDestroy(Destroyable resource);

    @Override
    public VkCommandBuffer mango$getCommandBuffer() {
        return this.commandBuffer();
    }

    @Override
    public VulkanDevice mango$getDevice() {
        return this.device;
    }

    @Override
    public void mango$queueForDestroy(Destroyable resource) {
        this.queueForDestroy(resource);
    }
}
