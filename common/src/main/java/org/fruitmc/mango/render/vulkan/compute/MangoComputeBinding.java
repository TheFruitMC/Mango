package org.fruitmc.mango.render.vulkan.compute;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.jetbrains.annotations.Nullable;

public final class MangoComputeBinding {

    public enum Type {
        UNIFORM_BUFFER,
        STORAGE_BUFFER,
        SAMPLED_IMAGE,
        STORAGE_IMAGE
    }

    private final int binding;
    private final Type type;
    @Nullable private final GpuBufferSlice buffer;
    @Nullable private final GpuTextureView textureView;
    @Nullable private final GpuSampler sampler;

    private MangoComputeBinding(
        int binding,
        Type type,
        @Nullable GpuBufferSlice buffer,
        @Nullable GpuTextureView textureView,
        @Nullable GpuSampler sampler
    ) {
        this.binding = binding;
        this.type = type;
        this.buffer = buffer;
        this.textureView = textureView;
        this.sampler = sampler;
    }

    public static MangoComputeBinding uniformBuffer(int binding, GpuBufferSlice buffer) {
        return buffer(binding, Type.UNIFORM_BUFFER, buffer);
    }

    public static MangoComputeBinding storageBuffer(int binding, GpuBufferSlice buffer) {
        return buffer(binding, Type.STORAGE_BUFFER, buffer);
    }

    public static MangoComputeBinding sampledImage(int binding, GpuTextureView textureView, GpuSampler sampler) {
        return new MangoComputeBinding(
            binding,
            Type.SAMPLED_IMAGE,
            null,
            textureView,
            sampler
        );
    }

    public static MangoComputeBinding storageImage(int binding, GpuTextureView textureView) {
        return new MangoComputeBinding(
            binding,
            Type.STORAGE_IMAGE,
            null,
            textureView,
            null
        );
    }

    public int binding() {
        return this.binding;
    }

    public Type type() {
        return this.type;
    }

    public GpuBufferSlice requireBuffer() {
        if (this.buffer == null) {
            throw new IllegalStateException("Compute binding " + this.binding + " is missing a buffer");
        }
        return this.buffer;
    }

    public GpuTextureView requireTextureView() {
        if (this.textureView == null) {
            throw new IllegalStateException("Compute binding " + this.binding + " is missing a texture view");
        }
        return this.textureView;
    }

    public GpuSampler requireSampler() {
        if (this.sampler == null) {
            throw new IllegalStateException("Compute binding " + this.binding + " is missing a sampler");
        }
        return this.sampler;
    }

    private static MangoComputeBinding buffer(int binding, Type type, GpuBufferSlice buffer) {
        return new MangoComputeBinding(binding, type, buffer, null, null);
    }
}
