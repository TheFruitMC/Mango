package org.fruitmc.mango.render.vulkan;

import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.jetbrains.annotations.Nullable;

public final class TextureViewAndSamplerHolder {
    private final GpuTextureView view;
    private final GpuSampler sampler;

    public TextureViewAndSamplerHolder(GpuTextureView view, GpuSampler sampler) {
        this.view = view;
        this.sampler = sampler;
    }

    public GpuTextureView view() {
        return this.view;
    }

    public GpuSampler sampler() {
        return this.sampler;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof TextureViewAndSamplerHolder other)) {
            return false;
        }
        return this.view == other.view && this.sampler == other.sampler;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this.view) * 31 + System.identityHashCode(this.sampler);
    }
}
