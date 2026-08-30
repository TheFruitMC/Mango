package org.fruitmc.mango.render.gpu.hiz;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import net.minecraft.util.Mth;
import org.fruitmc.mango.mixin.accessor.GpuDeviceAccessor;
import org.fruitmc.mango.render.vulkan.compute.MangoVulkanUsage;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class HiZDepthPyramid implements AutoCloseable {

    private static final int DEPTH_USAGE =
        GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING | MangoVulkanUsage.STORAGE_TEXTURE;

    private static final int MIP0_DOWNSAMPLE = 2;

    private int sourceWidth;
    private int sourceHeight;
    private int width;
    private int height;
    private int mipLevels;

    @Nullable private GpuTexture pyramidTexture;
    @Nullable private GpuTextureView fullView;
    @Nullable private List<GpuTextureView> perMipViews;

    public HiZDepthPyramid(int sourceWidth, int sourceHeight) {
        ensureCapacity(sourceWidth, sourceHeight);
    }

    public void ensureCapacity(int newSourceWidth, int newSourceHeight) {
        if (newSourceWidth <= 0 || newSourceHeight <= 0) {
            return;
        }
        if (this.pyramidTexture != null
            && this.sourceWidth == newSourceWidth
            && this.sourceHeight == newSourceHeight) {
            return;
        }

        close();

        int newWidth = ceilDiv(newSourceWidth, MIP0_DOWNSAMPLE);
        int newHeight = ceilDiv(newSourceHeight, MIP0_DOWNSAMPLE);

        this.sourceWidth = newSourceWidth;
        this.sourceHeight = newSourceHeight;
        this.width = newWidth;
        this.height = newHeight;
        this.mipLevels = computeMipLevels(newWidth, newHeight);

        GpuDevice device = RenderSystem.getDevice();
        this.pyramidTexture = device.createTexture(
            () -> "Mango Hi-Z depth pyramid",
            DEPTH_USAGE,
            GpuFormat.R16_FLOAT,
            newWidth,
            newHeight,
            1,
            this.mipLevels
        );

        this.fullView = device.createTextureView(this.pyramidTexture, 0, this.mipLevels);

        this.perMipViews = new ArrayList<>(this.mipLevels);
        for (int level = 0; level < this.mipLevels; level++) {
            this.perMipViews.add(
                device.createTextureView(this.pyramidTexture, level, 1)
            );
        }
    }

    private static int computeMipLevels(int width, int height) {
        int maxDim = Math.max(width, height);
        return Mth.log2(maxDim) + 1;
    }

    private static int ceilDiv(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    public int sourceWidth() { return this.sourceWidth; }

    public int sourceHeight() { return this.sourceHeight; }

    public int width() { return this.width; }

    public int height() { return this.height; }

    public int mipLevels() { return this.mipLevels; }

    public GpuTextureView mip0View() {
        return requireViews().get(0);
    }

    public GpuTextureView mipView(int level) {
        List<GpuTextureView> views = requireViews();
        if (level < 0 || level >= views.size()) {
            throw new IndexOutOfBoundsException("Hi-Z mip level " + level + " out of [0," + views.size() + ")");
        }
        return views.get(level);
    }

    public GpuTextureView fullView() {
        GpuTextureView view = this.fullView;
        if (view == null) {
            throw new IllegalStateException("Hi-Z pyramid not initialized");
        }
        return view;
    }

    public GpuTexture texture() {
        GpuTexture texture = this.pyramidTexture;
        if (texture == null) {
            throw new IllegalStateException("Hi-Z pyramid not initialized");
        }
        return texture;
    }

    private List<GpuTextureView> requireViews() {
        List<GpuTextureView> views = this.perMipViews;
        if (views == null) {
            throw new IllegalStateException("Hi-Z pyramid not initialized");
        }
        return views;
    }

    public boolean isReady() {
        return this.pyramidTexture != null && !this.pyramidTexture.isClosed();
    }

    public static boolean isVulkanBackend() {
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) return false;
        return ((GpuDeviceAccessor)(Object)device).mango$getBackend() instanceof VulkanDevice;
    }

    @Override
    public void close() {
        if (this.fullView != null) {
            this.fullView.close();
            this.fullView = null;
        }
        if (this.perMipViews != null) {
            for (GpuTextureView view : this.perMipViews) {
                view.close();
            }
            this.perMipViews.clear();
            this.perMipViews = null;
        }
        if (this.pyramidTexture != null) {
            this.pyramidTexture.close();
            this.pyramidTexture = null;
        }
    }
}
