package org.fruitmc.mango.render.vulkan.pipeline;

import com.mojang.blaze3d.buffers.GpuBuffer;

public record MangoTexelViewKey(GpuBuffer buffer, long offset, long length, int vkFormat) {
}
