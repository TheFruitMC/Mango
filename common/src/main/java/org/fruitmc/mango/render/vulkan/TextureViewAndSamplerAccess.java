package org.fruitmc.mango.render.vulkan;

import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;

public interface TextureViewAndSamplerAccess {
    GpuTextureView mango$view();
    GpuSampler mango$sampler();
}
