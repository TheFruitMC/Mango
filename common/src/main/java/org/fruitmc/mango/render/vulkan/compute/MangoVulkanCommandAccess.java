package org.fruitmc.mango.render.vulkan.compute;

import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import org.lwjgl.vulkan.VkCommandBuffer;

public interface MangoVulkanCommandAccess {

    VkCommandBuffer mango$getCommandBuffer();

    VulkanDevice mango$getDevice();

    void mango$queueForDestroy(Destroyable resource);

}
