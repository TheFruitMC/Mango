package org.fruitmc.mango.render.vulkan;

import com.mojang.blaze3d.buffers.GpuBufferSlice;

public interface MangoIndirectRenderPass {

    void mango$drawIndexedIndirectCount(GpuBufferSlice commands, GpuBufferSlice countBuffer, int maxDrawCount);
}
