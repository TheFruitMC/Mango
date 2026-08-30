package org.fruitmc.mango.mixin.vulkan.pipeline;

import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import org.fruitmc.mango.render.vulkan.TextureViewAndSamplerAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(targets = "com.mojang.blaze3d.vulkan.VulkanRenderPass$TextureViewAndSampler")
public abstract class TextureViewAndSamplerMixin implements TextureViewAndSamplerAccess {

    @Shadow
    public abstract VulkanGpuTextureView view();

    @Shadow
    public abstract VulkanGpuSampler sampler();

    @Override
    public GpuTextureView mango$view() {
        return this.view();
    }

    @Override
    public GpuSampler mango$sampler() {
        return this.sampler();
    }
}
