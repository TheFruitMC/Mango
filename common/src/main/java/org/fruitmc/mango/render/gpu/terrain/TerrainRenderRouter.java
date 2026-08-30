package org.fruitmc.mango.render.gpu.terrain;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.vulkan.VulkanConst;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.fruitmc.mango.mixin.accessor.GpuDeviceAccessor;
import org.fruitmc.mango.render.gpu.hiz.HiZCulling;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TerrainRenderRouter implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger("Mango/TerrainRouter");

    private static final TerrainRenderRouter INSTANCE = new TerrainRenderRouter();

    private static final int COLOR_FORMAT = VulkanConst.toVk(GpuFormat.RGBA8_UNORM);
    private static final int DEPTH_FORMAT = VulkanConst.toVk(GpuFormat.D32_FLOAT);

    private volatile boolean initialized;

    private TerrainRenderRouter() {
    }

    public static TerrainRenderRouter get() {
        return INSTANCE;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public boolean shouldCaptureTerrain() {
        warmup();
        return initialized;
    }

    public void warmup() {
        if (initialized) {
            return;
        }
        GpuDevice gpuDevice = RenderSystem.tryGetDevice();
        if (gpuDevice == null) {
            return;
        }
        GpuDeviceAccessor accessor = (GpuDeviceAccessor) gpuDevice;
        if (!(accessor.mango$getBackend() instanceof VulkanDevice vkDevice)) {
            return;
        }
        initialize(vkDevice, COLOR_FORMAT, DEPTH_FORMAT);
    }

    public void initialize(VulkanDevice vkDevice, int colorFormat, int depthFormat) {
        if (initialized) {
            return;
        }

        TerrainRenderer.get().initialize(vkDevice);
        LOG.info("Terrain path: MDI (indirect draw with GPU culling)");

        initialized = true;
    }

    public boolean isOpaqueTerrainReady() {
        warmup();
        return initialized;
    }

    public boolean tryRender(
        ChunkSectionsToRender chunks,
        ChunkSectionLayerGroup group,
        GpuSampler sampler
    ) {
        warmup();
        return initialized && TerrainRenderer.get().tryRender(chunks, group, sampler);
    }

    public void endFrame() {
        if (initialized) {
            TerrainRenderer.get().endFrame();
        }
    }

    @Override
    public void close() {
        try {
            if (initialized) {
                TerrainRenderer.get().close();
            }
            HiZCulling.get().close();
            TerrainSectionRegistry.get().close();
        } finally {
            initialized = false;
        }
    }
}
