package org.fruitmc.mango.render.gpu.hiz;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.fruitmc.mango.config.MangoConfig;
import org.fruitmc.mango.mixin.accessor.GpuDeviceAccessor;
import org.fruitmc.mango.render.gpu.MangoFrameState;
import org.fruitmc.mango.render.gpu.RenderDeviceCapabilities;
import org.fruitmc.mango.render.gpu.buffer.RingBufferUploader;
import org.fruitmc.mango.render.vulkan.MangoVulkanFeatures;
import org.fruitmc.mango.render.vulkan.compute.MangoComputeBinding;
import org.fruitmc.mango.render.vulkan.compute.MangoComputePipeline;
import org.fruitmc.mango.render.vulkan.compute.MangoVulkanCommandAccess;
import org.fruitmc.mango.render.vulkan.compute.MangoVulkanUsage;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

import static org.fruitmc.mango.render.vulkan.MangoVulkanConstants.*;

/**
 * Temporal Hi-Z occlusion culling for terrain. The pyramid stores the nearest depth in every texel;
 * with reverse-Z that is the minimum depth, so an object is hidden only when its far side is closer
 * than the depth already recorded for that screen area.
 */
public final class HiZCulling implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(HiZCulling.class);

    private static final Identifier HIZ_COPY_SHADER = Identifier.fromNamespaceAndPath("mango", "shaders/compute/hiz_copy.comp");
    private static final Identifier HIZ_COPY_SAMPLED_SHADER =
        Identifier.fromNamespaceAndPath("mango", "shaders/compute/hiz_copy_sampled.comp");
    private static final Identifier HIZ_BUILD_SHADER = Identifier.fromNamespaceAndPath("mango", "shaders/compute/hiz_build.comp");
    private static final Identifier HIZ_BUILD_BATCH_SHADER =
        Identifier.fromNamespaceAndPath("mango", "shaders/compute/hiz_build_batch.comp");
    private static final Identifier TERRAIN_CULL_SHADER = Identifier.fromNamespaceAndPath("mango", "shaders/compute/terrain_hiz_cull.comp");
    private static final Identifier TERRAIN_CULL_COMPACT_SHADER = Identifier.fromNamespaceAndPath("mango", "shaders/compute/terrain_hiz_cull_compact.comp");

    private static final int INITIAL_VISIBILITY_CAPACITY = 1024;
    private static final int VISIBILITY_BYTES_PER_SECTION = 4;
    private static final int VISIBILITY_USAGE = MangoVulkanUsage.STORAGE_BUFFER
        | GpuBuffer.USAGE_COPY_DST
        | GpuBuffer.USAGE_COPY_SRC;
    private static final int VISIBILITY_INITIAL_FILL_VALUE = 1;
    private static final int VISIBILITY_PER_FRAME_CLEAR_VALUE = 0;

    private static final int CAMERA_UNIFORM_SLOTS = 2;

    private static final int TERRAIN_UNIFORM_CURRENT_VP = 0;
    private static final int TERRAIN_UNIFORM_HISTORY_VP = 64;
    private static final int TERRAIN_UNIFORM_CURRENT_CAMERA_BLOCK = 128;
    private static final int TERRAIN_UNIFORM_HISTORY_CAMERA_BLOCK = 144;
    private static final int TERRAIN_UNIFORM_CURRENT_CAMERA_OFFSET = 160;
    private static final int TERRAIN_UNIFORM_HISTORY_CAMERA_OFFSET = 176;
    private static final int TERRAIN_UNIFORM_SCREEN_DIM = 192;
    private static final int TERRAIN_UNIFORM_MAX_MIP_LEVEL = 200;
    private static final int TERRAIN_UNIFORM_DEPTH_BIAS = 204;
    private static final int TERRAIN_UNIFORM_RELATIVE_DEPTH_BIAS = 208;
    private static final int TERRAIN_UNIFORM_SCREEN_MARGIN = 212;
    private static final int TERRAIN_UNIFORM_HYSTERESIS_BIAS = 216;
    private static final int TERRAIN_UNIFORM_MIP_REFINE_STEPS = 220;
    private static final int TERRAIN_UNIFORM_RELAX_OFFSCREEN_HISTORY = 224;
    private static final int CAMERA_UNIFORM_BYTES = 228;

    private static final int COMPACT_DISPATCH_UNIFORM_BYTES = 16;

    private static final int VEC_COMPONENT_X = 0;
    private static final int VEC_COMPONENT_Y = 4;
    private static final int VEC_COMPONENT_Z = 8;
    private static final int VEC_COMPONENT_W = 12;

    private static final int MIP_UNIFORM_BYTES = 8;
    private static final int UNIFORM_USAGE = GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_UNIFORM;
    private static final int UNIFORM_ALIGNMENT_FALLBACK = 256;
    private static final int STORAGE_BUFFER_ALIGNMENT_FALLBACK = 16;
    private static final int UNIFORM_INITIAL_CAPACITY = 4096;

    private static final int BUILD_WORKGROUP = 8;
    private static final int BATCH_MIP_COUNT = 4;
    private static final int MAX_MIP_BUILD_DISPATCHES = 8;
    private static final int BATCH_PARAMS_UNIFORM_BYTES = 4;
    private static final int BATCH_PARAMS_BINDING = BATCH_MIP_COUNT + 1;
    private static final int CULL_WORKGROUP = 64;
    private static final int MAX_TERRAIN_DISPATCH_GROUPS = 64;
    private static final int TERRAIN_DISPATCH_UNIFORMS_PER_GROUP = 2;
    private static final int MAX_TRANSLUCENT_DISPATCH_GROUPS = 8;

    private static final int POINT_SAMPLER_ANISOTROPY = 1;
    private static final int INDIRECT_COMMAND_UINTS = 5;
    private static final int OFFSCREEN_HISTORY_STRICT = 0;
    private static final int OFFSCREEN_HISTORY_RELAXED = 1;

    private static final float TERRAIN_DEPTH_BIAS = 1.0e-6f;
    private static final float TERRAIN_RELATIVE_DEPTH_BIAS = 0.0025f;
    private static final float TERRAIN_HYSTERESIS_BIAS = 0.01f;
    private static final float TERRAIN_SCREEN_MARGIN = 1.0f;
    private static final int TERRAIN_MIP_REFINE_STEPS = 1;

    @Nullable
    private static HiZCulling instance;

    @Nullable private VulkanCommandEncoder sharedEncoder;
    @Nullable private HiZDepthPyramid pyramid;
    @Nullable private GpuSampler pointSampler;
    @Nullable private MangoComputePipeline copyPipeline;
    @Nullable private MangoComputePipeline buildPipeline;
    @Nullable private MangoComputePipeline batchBuildPipeline;
    @Nullable private MangoComputePipeline terrainCullPipeline;
    @Nullable private MangoComputePipeline terrainCullCompactPipeline;

    private static final int STAGING_FRAMES = 3;

    @Nullable private GpuBuffer visibilityBufferA;
    @Nullable private GpuBuffer visibilityBufferB;
    // The previous visibility result keeps small sections visible for one extra frame after they leave view.
    @Nullable private GpuBuffer[] stagingRing;
    private final int[] stagingDrawCountRing = new int[STAGING_FRAMES];
    private final int[] stagingSectionCountRing = new int[STAGING_FRAMES];
    private int stagingHead;
    private long stagingFrameCounter;
    private int visibilityCapacity = 0;
    private boolean visibilityInitPending;

    private int totalDrawCountThisFrame;

    @Nullable private GpuBuffer translucentVisibilityA;
    @Nullable private GpuBuffer translucentVisibilityB;
    private int translucentVisibilityCapacity;
    private boolean translucentVisibilityInitPending;
    private boolean translucentWritingToA = true;
    private boolean translucentCullDispatchedThisFrame;
    @Nullable private GpuBufferSlice cachedTranslucentCamSlice;
    @Nullable private GpuBufferSlice cachedTranslucentSectionTableSlice;
    private int cachedTranslucentSectionCount;

    @Nullable private GpuBuffer depthStagingBuffer;
    private int depthStagingWidth;
    private int depthStagingHeight;

    private boolean samplesDepthDirectly;

    private boolean writingToA = true;
    private boolean cullDispatchedThisFrame;
    private boolean terrainHistoryValid;
    private boolean frameOpen;
    private boolean initialized;
    private boolean runtimeUnavailable;

    private final RingBufferUploader uniformUploader =
        new RingBufferUploader(() -> "Mango Hi-Z uniform", UNIFORM_USAGE, HiZCulling::uniformAlignment, UNIFORM_INITIAL_CAPACITY);
    private final ByteBuffer uniformScratch =
        ByteBuffer.allocateDirect(CAMERA_UNIFORM_BYTES).order(ByteOrder.nativeOrder());

    private final List<MangoComputeBinding> copyBindings = new ArrayList<>();
    private final List<MangoComputeBinding> buildBindings = new ArrayList<>();
    private final List<MangoComputeBinding> cullBindings = new ArrayList<>();

    @Nullable private GpuBufferSlice cachedCamSlice;
    @Nullable private GpuBufferSlice cachedSectionTableSlice;
    private int cachedSectionCount;
    private boolean uniformFrameBegun;

    @Nullable private Matrix4fc historyTerrainVP;
    private int historyTerrainCBX, historyTerrainCBY, historyTerrainCBZ;
    private float historyTerrainOX, historyTerrainOY, historyTerrainOZ;

    private HiZCulling() {
    }

    public static HiZCulling get() {
        if (instance == null) {
            instance = new HiZCulling();
        }
        return instance;
    }

    private static int uniformAlignment() {
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            return UNIFORM_ALIGNMENT_FALLBACK;
        }
        return Math.max(1, device.getDeviceInfo().limits().minUniformOffsetAlignment());
    }

    public static int uniformBufferAlignment() {
        return uniformAlignment();
    }

    public static int storageBufferAlignment() {
        return Math.max(STORAGE_BUFFER_ALIGNMENT_FALLBACK, uniformAlignment());
    }

    private static boolean isHiZCullingEnabled() {
        return MangoConfig.INSTANCE.enableHiZCulling && RenderDeviceCapabilities.isVulkanDeviceActive();
    }

    public boolean isReady() {
        if (!isHiZCullingEnabled() || runtimeUnavailable) {
            return false;
        }
        if (!initialized) {
            VulkanDevice vkDevice = getVulkanDevice();
            if (vkDevice != null) {
                initialize(vkDevice);
            }
        }
        return initialized && pyramid != null && pyramid.isReady();
    }

    public boolean canCullTerrain() {
        if (!isReady() || !frameOpen || !terrainHistoryValid || historyTerrainVP == null) {
            return false;
        }
        RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        return pyramid.sourceWidth() == target.width && pyramid.sourceHeight() == target.height;
    }

    public boolean willCullTerrainThisFrame() {
        // Snapshot selection happens before the terrain pass opens, so frameOpen is intentionally omitted.
        if (!isReady() || !terrainHistoryValid || historyTerrainVP == null) {
            return false;
        }
        RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        return pyramid.sourceWidth() == target.width && pyramid.sourceHeight() == target.height;
    }

    public boolean isCullDispatchedThisFrame() {
        return cullDispatchedThisFrame;
    }

    @Nullable
    private GpuBuffer currentVisibilityBuffer() {
        return writingToA ? visibilityBufferA : visibilityBufferB;
    }

    @Nullable
    private GpuBuffer previousVisibilityBuffer() {
        return writingToA ? visibilityBufferB : visibilityBufferA;
    }

    private void initialize(VulkanDevice vkDevice) {
        if (initialized || runtimeUnavailable) {
            return;
        }
        GpuDevice gpuDevice = RenderSystem.getDevice();

        try {
            this.pointSampler = gpuDevice.createSampler(
                AddressMode.CLAMP_TO_EDGE,
                AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST,
                FilterMode.NEAREST,
                POINT_SAMPLER_ANISOTROPY,
                OptionalDouble.empty()
            );

            RenderTarget mainTarget = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            this.pyramid = new HiZDepthPyramid(Math.max(1, mainTarget.width), Math.max(1, mainTarget.height));

            this.samplesDepthDirectly = !RenderDeviceCapabilities.isMoltenVk();
            this.copyPipeline = this.samplesDepthDirectly
                ? new MangoComputePipeline(
                    vkDevice,
                    "Mango Hi-Z copy (sampled)",
                    HIZ_COPY_SAMPLED_SHADER,
                    List.of(
                        MangoComputeBinding.Type.SAMPLED_IMAGE,
                        MangoComputeBinding.Type.STORAGE_IMAGE
                    )
                )
                : new MangoComputePipeline(
                    vkDevice,
                    "Mango Hi-Z copy",
                    HIZ_COPY_SHADER,
                    List.of(
                        MangoComputeBinding.Type.STORAGE_BUFFER,
                        MangoComputeBinding.Type.STORAGE_IMAGE,
                        MangoComputeBinding.Type.UNIFORM_BUFFER
                    )
                );
            this.buildPipeline = new MangoComputePipeline(
                vkDevice,
                "Mango Hi-Z build",
                HIZ_BUILD_SHADER,
                List.of(
                    MangoComputeBinding.Type.STORAGE_IMAGE,
                    MangoComputeBinding.Type.STORAGE_IMAGE
                )
            );
            this.batchBuildPipeline = new MangoComputePipeline(
                vkDevice,
                "Mango Hi-Z build (batched)",
                HIZ_BUILD_BATCH_SHADER,
                List.of(
                    MangoComputeBinding.Type.SAMPLED_IMAGE,
                    MangoComputeBinding.Type.STORAGE_IMAGE,
                    MangoComputeBinding.Type.STORAGE_IMAGE,
                    MangoComputeBinding.Type.STORAGE_IMAGE,
                    MangoComputeBinding.Type.STORAGE_IMAGE,
                    MangoComputeBinding.Type.UNIFORM_BUFFER
                )
            );
            this.terrainCullPipeline = new MangoComputePipeline(
                vkDevice,
                "Mango Hi-Z terrain cull",
                TERRAIN_CULL_SHADER,
                List.of(
                    MangoComputeBinding.Type.UNIFORM_BUFFER,
                    MangoComputeBinding.Type.STORAGE_BUFFER,
                    MangoComputeBinding.Type.SAMPLED_IMAGE,
                    MangoComputeBinding.Type.STORAGE_BUFFER,
                    MangoComputeBinding.Type.STORAGE_BUFFER,
                    MangoComputeBinding.Type.STORAGE_BUFFER,
                    MangoComputeBinding.Type.UNIFORM_BUFFER
                )
            );
            if (MangoVulkanFeatures.isIndirectCountEnabled()) {
                this.terrainCullCompactPipeline = new MangoComputePipeline(
                    vkDevice,
                    "Mango Hi-Z terrain cull (compacting)",
                    TERRAIN_CULL_COMPACT_SHADER,
                    List.of(
                        MangoComputeBinding.Type.UNIFORM_BUFFER,
                        MangoComputeBinding.Type.STORAGE_BUFFER,
                        MangoComputeBinding.Type.SAMPLED_IMAGE,
                        MangoComputeBinding.Type.STORAGE_BUFFER,
                        MangoComputeBinding.Type.STORAGE_BUFFER,
                        MangoComputeBinding.Type.STORAGE_BUFFER,
                        MangoComputeBinding.Type.UNIFORM_BUFFER,
                        MangoComputeBinding.Type.STORAGE_BUFFER,
                        MangoComputeBinding.Type.STORAGE_BUFFER,
                        MangoComputeBinding.Type.STORAGE_BUFFER
                    )
                );
            }
            ensureVisibilityCapacity(INITIAL_VISIBILITY_CAPACITY);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Failed to initialize Hi-Z occlusion culling", e);
            close();
            return;
        }

        initialized = true;
        LOGGER.info("Hi-Z occlusion culling initialized (source {}x{}, pyramid {}x{}, {} mip levels, mip0 from {})",
            pyramid.sourceWidth(), pyramid.sourceHeight(),
            pyramid.width(), pyramid.height(), pyramid.mipLevels(),
            samplesDepthDirectly ? "sampled depth" : "depth staging copy");
    }

    public void captureDepthAndBuildPyramid() {
        if (!frameOpen || !isReady()) {
            return;
        }

        MangoFrameState frameState = MangoFrameState.get();
        Matrix4fc frameViewProjection = frameState.viewProjection();
        if (frameViewProjection == null) {
            return;
        }

        RenderTarget mainTarget = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        GpuTextureView mainDepthView = mainTarget.getDepthTextureView();
        if (mainDepthView == null) {
            return;
        }

        int frameW = mainTarget.width;
        int frameH = mainTarget.height;
        if (frameW <= 0 || frameH <= 0) {
            return;
        }

        pyramid.ensureCapacity(frameW, frameH);

        MangoVulkanCommandAccess access = vulkanCommandAccess();
        beginUniformFrame();
        int firstChainLevel = 1;
        // Sampling the depth attachment avoids a full-resolution staging copy on drivers that support it.
        if (this.samplesDepthDirectly && batchingEnabled()) {
            firstChainLevel = buildFoldedFromSampledDepth(access, mainDepthView);
        } else if (this.samplesDepthDirectly) {
            buildMip0FromSampledDepth(access, mainDepthView);
        } else {
            buildMip0FromCopiedDepth(access, mainDepthView, frameW, frameH);
        }
        buildMipChain(access, firstChainLevel);

        this.historyTerrainVP = frameViewProjection;
        this.historyTerrainCBX = frameState.cameraBlockX();
        this.historyTerrainCBY = frameState.cameraBlockY();
        this.historyTerrainCBZ = frameState.cameraBlockZ();
        this.historyTerrainOX = frameState.cameraOffsetX();
        this.historyTerrainOY = frameState.cameraOffsetY();
        this.historyTerrainOZ = frameState.cameraOffsetZ();
        this.terrainHistoryValid = true;

    }

    private boolean batchingEnabled() {
        return batchBuildPipeline != null;
    }

    private int buildFoldedFromSampledDepth(MangoVulkanCommandAccess access, GpuTextureView mainDepthView) {
        int levelCount = Math.min(BATCH_MIP_COUNT, pyramid.mipLevels());

        try (MemoryStack stack = MemoryStack.stackPush()) {
            MangoComputePipeline.BarrierBatch preBatch =
                MangoComputePipeline.barrierBatch(stack, levelCount + 1, 0);
            preBatch.addImage(
                VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,
                VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_READ_BIT,
                VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                mainDepthView
            );
            for (int level = 0; level < levelCount; level++) {
                preBatch.addImage(
                    VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_READ_BIT,
                    VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_WRITE_BIT,
                    VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                    pyramid.mipView(level)
                );
            }
            preBatch.submit(access);
        }

        buildBindings.clear();
        buildBindings.add(MangoComputeBinding.sampledImage(0, mainDepthView, pointSampler));
        appendBatchDestinations(0, levelCount);
        buildBindings.add(MangoComputeBinding.uniformBuffer(BATCH_PARAMS_BINDING, uploadBatchParams(levelCount)));

        int groupsX = (pyramid.width() + BUILD_WORKGROUP - 1) / BUILD_WORKGROUP;
        int groupsY = (pyramid.height() + BUILD_WORKGROUP - 1) / BUILD_WORKGROUP;
        batchBuildPipeline.dispatch(access, buildBindings, groupsX, groupsY, 1);

        MangoComputePipeline.barrierImage(access,
            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_READ_BIT,
            VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,
            VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
            mainDepthView);
        return levelCount;
    }

    private void appendBatchDestinations(int firstLevel, int levelCount) {
        for (int slot = 0; slot < BATCH_MIP_COUNT; slot++) {
            int level = firstLevel + Math.min(slot, levelCount - 1);
            buildBindings.add(MangoComputeBinding.storageImage(slot + 1, pyramid.mipView(level)));
        }
    }

    private GpuBufferSlice uploadBatchParams(int levelCount) {
        uniformScratch.clear();
        uniformScratch.putInt(levelCount);
        uniformScratch.flip();
        return uniformUploader.upload(uniformScratch);
    }

    private void buildMip0FromSampledDepth(MangoVulkanCommandAccess access, GpuTextureView mainDepthView) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            MangoComputePipeline.BarrierBatch preBatch = MangoComputePipeline.barrierBatch(stack, 2, 0);
            preBatch.addImage(
                VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,
                VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_READ_BIT,
                VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                mainDepthView
            );
            preBatch.addImage(
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_READ_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_WRITE_BIT,
                VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                pyramid.mip0View()
            );
            preBatch.submit(access);
        }

        copyBindings.clear();
        copyBindings.add(MangoComputeBinding.sampledImage(0, mainDepthView, pointSampler));
        copyBindings.add(MangoComputeBinding.storageImage(1, pyramid.mip0View()));
        dispatchMip0(access);

        MangoComputePipeline.barrierImage(access,
            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_READ_BIT,
            VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,
            VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
            mainDepthView);
    }

    private void buildMip0FromCopiedDepth(
        MangoVulkanCommandAccess access,
        GpuTextureView mainDepthView,
        int frameW,
        int frameH
    ) {
        ensureDepthStagingBuffer(frameW, frameH);
        long depthImage = ((VulkanGpuTextureView) mainDepthView).texture().vkImage();
        long stagingBuf = ((VulkanGpuBuffer) depthStagingBuffer).vkBuffer();
        long stagingBytes = (long) frameW * frameH * Float.BYTES;

        MangoComputePipeline.barrierImage(access,
            VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,
            VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
            VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_READ_BIT,
            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            mainDepthView);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.bufferOffset(0)
                .bufferRowLength(0)
                .bufferImageHeight(0);
            region.imageSubresource()
                .aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT)
                .mipLevel(0)
                .baseArrayLayer(0)
                .layerCount(1);
            region.imageOffset().x(0).y(0).z(0);
            region.imageExtent()
                .width(frameW)
                .height(frameH)
                .depth(1);
            VK12.vkCmdCopyImageToBuffer(
                access.mango$getCommandBuffer(),
                depthImage,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                stagingBuf,
                region
            );
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            MangoComputePipeline.BarrierBatch postCopyBatch = MangoComputePipeline.barrierBatch(stack, 2, 1);
            postCopyBatch.addImage(
                VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_READ_BIT,
                VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,
                VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
                mainDepthView
            );
            postCopyBatch.addImage(
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_READ_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_WRITE_BIT,
                VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                pyramid.mip0View()
            );
            postCopyBatch.addBuffer(
                VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_READ_BIT,
                new GpuBufferSlice(depthStagingBuffer, 0L, stagingBytes)
            );
            postCopyBatch.submit(access);
        }

        uniformScratch.clear();
        uniformScratch.putInt(frameW);
        uniformScratch.putInt(frameH);
        uniformScratch.flip();
        GpuBufferSlice copyParamsSlice = uniformUploader.upload(uniformScratch);

        copyBindings.clear();
        copyBindings.add(MangoComputeBinding.storageBuffer(0, new GpuBufferSlice(depthStagingBuffer, 0L, stagingBytes)));
        copyBindings.add(MangoComputeBinding.storageImage(1, pyramid.mip0View()));
        copyBindings.add(MangoComputeBinding.uniformBuffer(2, copyParamsSlice));
        dispatchMip0(access);
    }

    private void dispatchMip0(MangoVulkanCommandAccess access) {
        int groupsX = (pyramid.width() + BUILD_WORKGROUP - 1) / BUILD_WORKGROUP;
        int groupsY = (pyramid.height() + BUILD_WORKGROUP - 1) / BUILD_WORKGROUP;
        copyPipeline.dispatch(access, copyBindings, groupsX, groupsY, 1);
    }

    private void buildMipChain(MangoVulkanCommandAccess access, int startLevel) {
        int mip0Width = pyramid.width();
        int mip0Height = pyramid.height();
        int mipLevels = pyramid.mipLevels();
        boolean batching = batchingEnabled();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int level = startLevel;
            while (level < mipLevels) {
                int batchedLevels = batching ? Math.min(BATCH_MIP_COUNT, mipLevels - level) : 1;
                dispatchMipBuild(access, stack, level, batchedLevels, mip0Width, mip0Height);
                level += batchedLevels;
            }
        }
    }

    private void dispatchMipBuild(
        MangoVulkanCommandAccess access,
        MemoryStack stack,
        int firstLevel,
        int levelCount,
        int mip0Width,
        int mip0Height
    ) {
        MangoComputePipeline.BarrierBatch mipBatch = MangoComputePipeline.barrierBatch(stack, levelCount + 1, 0);
        mipBatch.addImage(
            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_WRITE_BIT,
            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_READ_BIT,
            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
            pyramid.mipView(firstLevel - 1)
        );
        for (int offset = 0; offset < levelCount; offset++) {
            mipBatch.addImage(
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_READ_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_WRITE_BIT,
                VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                pyramid.mipView(firstLevel + offset)
            );
        }
        mipBatch.submit(access);

        boolean batched = batchingEnabled();
        buildBindings.clear();
        if (batched) {
            buildBindings.add(MangoComputeBinding.sampledImage(0, pyramid.mipView(firstLevel - 1), pointSampler));
            appendBatchDestinations(firstLevel, levelCount);
            buildBindings.add(MangoComputeBinding.uniformBuffer(BATCH_PARAMS_BINDING, uploadBatchParams(levelCount)));
        } else {
            buildBindings.add(MangoComputeBinding.storageImage(0, pyramid.mipView(firstLevel - 1)));
            buildBindings.add(MangoComputeBinding.storageImage(1, pyramid.mipView(firstLevel)));
        }

        int dstWidth = Math.max(1, mip0Width >> firstLevel);
        int dstHeight = Math.max(1, mip0Height >> firstLevel);
        int groupsX = (dstWidth + BUILD_WORKGROUP - 1) / BUILD_WORKGROUP;
        int groupsY = (dstHeight + BUILD_WORKGROUP - 1) / BUILD_WORKGROUP;

        MangoComputePipeline pipeline = batched ? batchBuildPipeline : buildPipeline;
        pipeline.dispatch(access, buildBindings, groupsX, groupsY, 1);
    }

    public boolean beginTerrainCull(
        Matrix4fc viewProjection,
        GpuBufferSlice sectionTableSlice,
        int sectionCount
    ) {
        if (!canCullTerrain()) {
            cullDispatchedThisFrame = false;
            return false;
        }

        ensureVisibilityCapacity(sectionCount);

        MangoVulkanCommandAccess access = vulkanCommandAccess();

        beginUniformFrame();

        packCameraUniform(viewProjection);

        GpuBufferSlice camSlice = uniformUploader.upload(uniformScratch);

        GpuBuffer currentVis = currentVisibilityBuffer();
        GpuBuffer prevVis = previousVisibilityBuffer();
        if (currentVis == null || prevVis == null) {
            return false;
        }

        long visBytes = (long) sectionCount * VISIBILITY_BYTES_PER_SECTION;

        flushPendingVisibilityInit(access, prevVis);

        VK12.vkCmdFillBuffer(
            access.mango$getCommandBuffer(),
            ((VulkanGpuBuffer) currentVis).vkBuffer(),
            0L,
            visBytes,
            VISIBILITY_PER_FRAME_CLEAR_VALUE
        );
        // Clear the current buffer only; the previous frame remains available for temporal hysteresis.

        try (MemoryStack stack = MemoryStack.stackPush()) {
            MangoComputePipeline.BarrierBatch cullBatch = MangoComputePipeline.barrierBatch(stack, 1, 3);
            cullBatch.addImage(
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_READ_BIT,
                VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                pyramid.fullView()
            );
            cullBatch.addBuffer(
                VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK_ACCESS_2_SHADER_READ_BIT | VK_ACCESS_2_SHADER_WRITE_BIT,
                new GpuBufferSlice(currentVis, 0L, visBytes)
            );
            cullBatch.addBuffer(
                VK_PIPELINE_STAGE_2_HOST_BIT | VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                VK_ACCESS_2_HOST_WRITE_BIT | VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_READ_BIT,
                sectionTableSlice
            );
            cullBatch.addBuffer(
                VK_PIPELINE_STAGE_2_TRANSFER_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK_ACCESS_2_TRANSFER_WRITE_BIT | VK_ACCESS_2_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_READ_BIT,
                new GpuBufferSlice(prevVis, 0L, visBytes)
            );
            cullBatch.submit(access);
        }

        this.cachedCamSlice = camSlice;
        this.cachedSectionTableSlice = sectionTableSlice;
        this.cachedSectionCount = sectionCount;
        return true;
    }

    public void dispatchCullGroup(GpuBufferSlice commandSlice, int drawCount) {
        if (drawCount == 0) return;

        MangoVulkanCommandAccess access = vulkanCommandAccess();
        GpuBufferSlice camSlice = this.cachedCamSlice;
        GpuBufferSlice sectionTableSlice = this.cachedSectionTableSlice;
        if (camSlice == null || sectionTableSlice == null) {
            return;
        }

        GpuBuffer currentVis = currentVisibilityBuffer();
        GpuBuffer prevVis = previousVisibilityBuffer();
        if (currentVis == null || prevVis == null) {
            return;
        }

        long visBytes = (long) cachedSectionCount * VISIBILITY_BYTES_PER_SECTION;

        MangoComputePipeline.barrierBuffer(
            access,
            VK_PIPELINE_STAGE_2_HOST_BIT | VK_PIPELINE_STAGE_2_TRANSFER_BIT,
            VK_ACCESS_2_HOST_WRITE_BIT | VK_ACCESS_2_TRANSFER_WRITE_BIT,
            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
            VK_ACCESS_2_SHADER_READ_BIT | VK_ACCESS_2_SHADER_WRITE_BIT,
            commandSlice
        );

        uniformScratch.clear();
        uniformScratch.putInt(drawCount * INDIRECT_COMMAND_UINTS);
        uniformScratch.putInt(cachedSectionCount);
        uniformScratch.flip();
        GpuBufferSlice dispatchSlice = uniformUploader.upload(uniformScratch);

        cullBindings.clear();
        cullBindings.add(MangoComputeBinding.uniformBuffer(0, camSlice));
        cullBindings.add(MangoComputeBinding.storageBuffer(1, sectionTableSlice));
        cullBindings.add(MangoComputeBinding.sampledImage(2, pyramid.fullView(), pointSampler));
        cullBindings.add(MangoComputeBinding.storageBuffer(3, commandSlice));
        cullBindings.add(MangoComputeBinding.storageBuffer(4, new GpuBufferSlice(prevVis, 0, visBytes)));
        cullBindings.add(MangoComputeBinding.storageBuffer(5, new GpuBufferSlice(currentVis, 0, visBytes)));
        cullBindings.add(MangoComputeBinding.uniformBuffer(6, dispatchSlice));

        int groups = (drawCount + CULL_WORKGROUP - 1) / CULL_WORKGROUP;
        terrainCullPipeline.dispatch(access, cullBindings, groups, 1, 1);

        cullDispatchedThisFrame = true;
        totalDrawCountThisFrame += drawCount;
    }

    public boolean canCompactTerrainCommands() {
        return this.terrainCullCompactPipeline != null && MangoVulkanFeatures.isIndirectCountEnabled();
    }

    public void dispatchCullGroupCompact(
        GpuBufferSlice srcSlice,
        GpuBufferSlice dstSlice,
        GpuBufferSlice countSlice,
        int drawCount
    ) {
        dispatchCullCompact(srcSlice, dstSlice, countSlice, countSlice, 0, drawCount);
    }

    public void dispatchCullGroupsCompact(
        GpuBufferSlice srcSlice,
        GpuBufferSlice dstSlice,
        GpuBufferSlice countSlice,
        GpuBufferSlice groupRanges,
        int groupCount,
        int drawCount
    ) {
        if (groupCount <= 0) {
            throw new IllegalArgumentException("Merged terrain culling requires at least one group");
        }
        dispatchCullCompact(srcSlice, dstSlice, countSlice, groupRanges, groupCount, drawCount);
    }

    private void dispatchCullCompact(
        GpuBufferSlice srcSlice,
        GpuBufferSlice dstSlice,
        GpuBufferSlice countSlice,
        GpuBufferSlice groupRanges,
        int groupCount,
        int drawCount
    ) {
        if (drawCount == 0) return;

        MangoComputePipeline compactPipeline = this.terrainCullCompactPipeline;
        if (compactPipeline == null) {
            return;
        }

        MangoVulkanCommandAccess access = vulkanCommandAccess();
        GpuBufferSlice camSlice = this.cachedCamSlice;
        GpuBufferSlice sectionTableSlice = this.cachedSectionTableSlice;
        if (camSlice == null || sectionTableSlice == null) {
            return;
        }

        GpuBuffer currentVis = currentVisibilityBuffer();
        GpuBuffer prevVis = previousVisibilityBuffer();
        if (currentVis == null || prevVis == null) {
            return;
        }

        long visBytes = (long) cachedSectionCount * VISIBILITY_BYTES_PER_SECTION;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            int maxBuffers = groupCount > 0 ? 3 : 2;
            MangoComputePipeline.BarrierBatch batch = MangoComputePipeline.barrierBatch(stack, 0, maxBuffers);
            batch.addBuffer(
                VK_PIPELINE_STAGE_2_HOST_BIT | VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                VK_ACCESS_2_HOST_WRITE_BIT | VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK_ACCESS_2_SHADER_READ_BIT | VK_ACCESS_2_SHADER_WRITE_BIT,
                srcSlice
            );
            batch.addBuffer(
                VK_PIPELINE_STAGE_2_HOST_BIT | VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                VK_ACCESS_2_HOST_WRITE_BIT | VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK_ACCESS_2_SHADER_READ_BIT | VK_ACCESS_2_SHADER_WRITE_BIT,
                countSlice
            );
            if (groupCount > 0) {
                batch.addBuffer(
                    VK_PIPELINE_STAGE_2_HOST_BIT | VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                    VK_ACCESS_2_HOST_WRITE_BIT | VK_ACCESS_2_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                    VK_ACCESS_2_SHADER_READ_BIT,
                    groupRanges
                );
            }
            batch.submit(access);
        }

        uniformScratch.clear();
        uniformScratch.putInt(drawCount * INDIRECT_COMMAND_UINTS);
        uniformScratch.putInt(cachedSectionCount);
        uniformScratch.putInt(groupCount);
        uniformScratch.putInt(0);
        uniformScratch.flip();
        GpuBufferSlice dispatchSlice = uniformUploader.upload(uniformScratch);

        cullBindings.clear();
        cullBindings.add(MangoComputeBinding.uniformBuffer(0, camSlice));
        cullBindings.add(MangoComputeBinding.storageBuffer(1, sectionTableSlice));
        cullBindings.add(MangoComputeBinding.sampledImage(2, pyramid.fullView(), pointSampler));
        cullBindings.add(MangoComputeBinding.storageBuffer(3, srcSlice));
        cullBindings.add(MangoComputeBinding.storageBuffer(4, new GpuBufferSlice(prevVis, 0, visBytes)));
        cullBindings.add(MangoComputeBinding.storageBuffer(5, new GpuBufferSlice(currentVis, 0, visBytes)));
        cullBindings.add(MangoComputeBinding.uniformBuffer(6, dispatchSlice));
        cullBindings.add(MangoComputeBinding.storageBuffer(7, dstSlice));
        cullBindings.add(MangoComputeBinding.storageBuffer(8, countSlice));
        cullBindings.add(MangoComputeBinding.storageBuffer(9, groupRanges));

        int groups = (drawCount + CULL_WORKGROUP - 1) / CULL_WORKGROUP;
        compactPipeline.dispatch(access, cullBindings, groups, 1, 1);

        cullDispatchedThisFrame = true;
        totalDrawCountThisFrame += drawCount;
    }

    public void endTerrainCull() {
        this.cachedCamSlice = null;
        this.cachedSectionTableSlice = null;
    }

    private void packCameraUniform(Matrix4fc viewProjection) {
        MangoFrameState fs = MangoFrameState.get();
        uniformScratch.clear();
        viewProjection.get(TERRAIN_UNIFORM_CURRENT_VP, uniformScratch);
        historyTerrainVP.get(TERRAIN_UNIFORM_HISTORY_VP, uniformScratch);
        uniformScratch.putInt(TERRAIN_UNIFORM_CURRENT_CAMERA_BLOCK + VEC_COMPONENT_X, fs.cameraBlockX());
        uniformScratch.putInt(TERRAIN_UNIFORM_CURRENT_CAMERA_BLOCK + VEC_COMPONENT_Y, fs.cameraBlockY());
        uniformScratch.putInt(TERRAIN_UNIFORM_CURRENT_CAMERA_BLOCK + VEC_COMPONENT_Z, fs.cameraBlockZ());
        uniformScratch.putInt(TERRAIN_UNIFORM_CURRENT_CAMERA_BLOCK + VEC_COMPONENT_W, 0);
        uniformScratch.putInt(TERRAIN_UNIFORM_HISTORY_CAMERA_BLOCK + VEC_COMPONENT_X, historyTerrainCBX);
        uniformScratch.putInt(TERRAIN_UNIFORM_HISTORY_CAMERA_BLOCK + VEC_COMPONENT_Y, historyTerrainCBY);
        uniformScratch.putInt(TERRAIN_UNIFORM_HISTORY_CAMERA_BLOCK + VEC_COMPONENT_Z, historyTerrainCBZ);
        uniformScratch.putInt(TERRAIN_UNIFORM_HISTORY_CAMERA_BLOCK + VEC_COMPONENT_W, 0);
        uniformScratch.putFloat(TERRAIN_UNIFORM_CURRENT_CAMERA_OFFSET + VEC_COMPONENT_X, fs.cameraOffsetX());
        uniformScratch.putFloat(TERRAIN_UNIFORM_CURRENT_CAMERA_OFFSET + VEC_COMPONENT_Y, fs.cameraOffsetY());
        uniformScratch.putFloat(TERRAIN_UNIFORM_CURRENT_CAMERA_OFFSET + VEC_COMPONENT_Z, fs.cameraOffsetZ());
        uniformScratch.putFloat(TERRAIN_UNIFORM_CURRENT_CAMERA_OFFSET + VEC_COMPONENT_W, 0);
        uniformScratch.putFloat(TERRAIN_UNIFORM_HISTORY_CAMERA_OFFSET + VEC_COMPONENT_X, historyTerrainOX);
        uniformScratch.putFloat(TERRAIN_UNIFORM_HISTORY_CAMERA_OFFSET + VEC_COMPONENT_Y, historyTerrainOY);
        uniformScratch.putFloat(TERRAIN_UNIFORM_HISTORY_CAMERA_OFFSET + VEC_COMPONENT_Z, historyTerrainOZ);
        uniformScratch.putFloat(TERRAIN_UNIFORM_HISTORY_CAMERA_OFFSET + VEC_COMPONENT_W, 0);
        RenderTarget mainTarget = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        uniformScratch.putInt(TERRAIN_UNIFORM_SCREEN_DIM + VEC_COMPONENT_X, mainTarget.width);
        uniformScratch.putInt(TERRAIN_UNIFORM_SCREEN_DIM + VEC_COMPONENT_Y, mainTarget.height);
        uniformScratch.putInt(TERRAIN_UNIFORM_MAX_MIP_LEVEL, pyramid.mipLevels() - 1);
        uniformScratch.putFloat(TERRAIN_UNIFORM_DEPTH_BIAS, TERRAIN_DEPTH_BIAS);
        uniformScratch.putFloat(TERRAIN_UNIFORM_RELATIVE_DEPTH_BIAS, TERRAIN_RELATIVE_DEPTH_BIAS);
        uniformScratch.putFloat(TERRAIN_UNIFORM_SCREEN_MARGIN, TERRAIN_SCREEN_MARGIN);
        uniformScratch.putFloat(TERRAIN_UNIFORM_HYSTERESIS_BIAS, TERRAIN_HYSTERESIS_BIAS);
        uniformScratch.putInt(TERRAIN_UNIFORM_MIP_REFINE_STEPS, TERRAIN_MIP_REFINE_STEPS);
        uniformScratch.putInt(TERRAIN_UNIFORM_RELAX_OFFSCREEN_HISTORY, OFFSCREEN_HISTORY_RELAXED);
        uniformScratch.limit(CAMERA_UNIFORM_BYTES);
    }

    public boolean canCullTranslucentTerrain() {
        return canCullTerrain();
    }

    public boolean beginTranslucentTerrainCull(
        Matrix4fc viewProjection,
        GpuBufferSlice sectionTableSlice,
        int sectionCount
    ) {
        if (!canCullTranslucentTerrain()) {
            translucentCullDispatchedThisFrame = false;
            return false;
        }

        ensureTranslucentVisibilityCapacity(sectionCount);

        MangoVulkanCommandAccess access = vulkanCommandAccess();

        beginUniformFrame();

        packCameraUniform(viewProjection);

        GpuBufferSlice camSlice = uniformUploader.upload(uniformScratch);

        GpuBuffer currentVis = currentTranslucentVisibilityBuffer();
        GpuBuffer prevVis = previousTranslucentVisibilityBuffer();
        if (currentVis == null || prevVis == null) {
            return false;
        }

        long visBytes = (long) sectionCount * VISIBILITY_BYTES_PER_SECTION;

        flushPendingTranslucentVisibilityInit(access, prevVis);

        VK12.vkCmdFillBuffer(
            access.mango$getCommandBuffer(),
            ((VulkanGpuBuffer) currentVis).vkBuffer(),
            0L,
            visBytes,
            VISIBILITY_PER_FRAME_CLEAR_VALUE
        );

        try (MemoryStack stack = MemoryStack.stackPush()) {
            MangoComputePipeline.BarrierBatch cullBatch = MangoComputePipeline.barrierBatch(stack, 1, 3);
            cullBatch.addImage(
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_READ_BIT,
                VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                pyramid.fullView()
            );
            cullBatch.addBuffer(
                VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK_ACCESS_2_SHADER_READ_BIT | VK_ACCESS_2_SHADER_WRITE_BIT,
                new GpuBufferSlice(currentVis, 0L, visBytes)
            );
            cullBatch.addBuffer(
                VK_PIPELINE_STAGE_2_HOST_BIT | VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                VK_ACCESS_2_HOST_WRITE_BIT | VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_READ_BIT,
                sectionTableSlice
            );
            cullBatch.addBuffer(
                VK_PIPELINE_STAGE_2_HOST_BIT | VK_PIPELINE_STAGE_2_TRANSFER_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK_ACCESS_2_HOST_WRITE_BIT | VK_ACCESS_2_TRANSFER_WRITE_BIT | VK_ACCESS_2_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_READ_BIT,
                new GpuBufferSlice(prevVis, 0L, visBytes)
            );
            cullBatch.submit(access);
        }

        this.cachedTranslucentCamSlice = camSlice;
        this.cachedTranslucentSectionTableSlice = sectionTableSlice;
        this.cachedTranslucentSectionCount = sectionCount;
        return true;
    }

    public void dispatchTranslucentCullGroup(GpuBufferSlice commandSlice, int drawCount) {
        if (drawCount == 0) return;

        MangoVulkanCommandAccess access = vulkanCommandAccess();
        GpuBufferSlice camSlice = this.cachedTranslucentCamSlice;
        GpuBufferSlice sectionTableSlice = this.cachedTranslucentSectionTableSlice;
        if (camSlice == null || sectionTableSlice == null) {
            return;
        }

        GpuBuffer currentVis = currentTranslucentVisibilityBuffer();
        GpuBuffer prevVis = previousTranslucentVisibilityBuffer();
        if (currentVis == null || prevVis == null) {
            return;
        }

        long visBytes = (long) cachedTranslucentSectionCount * VISIBILITY_BYTES_PER_SECTION;

        MangoComputePipeline.barrierBuffer(
            access,
            VK_PIPELINE_STAGE_2_HOST_BIT | VK_PIPELINE_STAGE_2_TRANSFER_BIT,
            VK_ACCESS_2_HOST_WRITE_BIT | VK_ACCESS_2_TRANSFER_WRITE_BIT,
            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
            VK_ACCESS_2_SHADER_READ_BIT | VK_ACCESS_2_SHADER_WRITE_BIT,
            commandSlice
        );

        uniformScratch.clear();
        uniformScratch.putInt(drawCount * INDIRECT_COMMAND_UINTS);
        uniformScratch.putInt(cachedTranslucentSectionCount);
        uniformScratch.flip();
        GpuBufferSlice dispatchSlice = uniformUploader.upload(uniformScratch);

        cullBindings.clear();
        cullBindings.add(MangoComputeBinding.uniformBuffer(0, camSlice));
        cullBindings.add(MangoComputeBinding.storageBuffer(1, sectionTableSlice));
        cullBindings.add(MangoComputeBinding.sampledImage(2, pyramid.fullView(), pointSampler));
        cullBindings.add(MangoComputeBinding.storageBuffer(3, commandSlice));
        cullBindings.add(MangoComputeBinding.storageBuffer(4, new GpuBufferSlice(prevVis, 0, visBytes)));
        cullBindings.add(MangoComputeBinding.storageBuffer(5, new GpuBufferSlice(currentVis, 0, visBytes)));
        cullBindings.add(MangoComputeBinding.uniformBuffer(6, dispatchSlice));

        int groups = (drawCount + CULL_WORKGROUP - 1) / CULL_WORKGROUP;
        terrainCullPipeline.dispatch(access, cullBindings, groups, 1, 1);

        translucentCullDispatchedThisFrame = true;
    }

    public void endTranslucentTerrainCull() {
        this.cachedTranslucentCamSlice = null;
        this.cachedTranslucentSectionTableSlice = null;
    }

    @Nullable
    private GpuBuffer currentTranslucentVisibilityBuffer() {
        return translucentWritingToA ? translucentVisibilityA : translucentVisibilityB;
    }

    @Nullable
    private GpuBuffer previousTranslucentVisibilityBuffer() {
        return translucentWritingToA ? translucentVisibilityB : translucentVisibilityA;
    }

    private void ensureTranslucentVisibilityCapacity(int sectionCount) {
        int required = sectionCount;
        if (translucentVisibilityCapacity >= required) return;

        int newCapacity = Math.max(required, translucentVisibilityCapacity * 2);
        if (newCapacity < INITIAL_VISIBILITY_CAPACITY) newCapacity = INITIAL_VISIBILITY_CAPACITY;

        GpuDevice gpuDevice = RenderSystem.getDevice();

        if (translucentVisibilityA != null) translucentVisibilityA.close();
        if (translucentVisibilityB != null) translucentVisibilityB.close();

        long bytes = (long) newCapacity * VISIBILITY_BYTES_PER_SECTION;
        translucentVisibilityA = gpuDevice.createBuffer(
            () -> "Mango Hi-Z translucent visibility A",
            VISIBILITY_USAGE,
            bytes
        );
        translucentVisibilityB = gpuDevice.createBuffer(
            () -> "Mango Hi-Z translucent visibility B",
            VISIBILITY_USAGE,
            bytes
        );

        translucentVisibilityInitPending = true;

        translucentVisibilityCapacity = newCapacity;
    }

    private void flushPendingTranslucentVisibilityInit(MangoVulkanCommandAccess access, GpuBuffer previousVisibility) {
        if (!translucentVisibilityInitPending) {
            return;
        }
        VK12.vkCmdFillBuffer(
            access.mango$getCommandBuffer(),
            ((VulkanGpuBuffer) previousVisibility).vkBuffer(),
            0L,
            (long) translucentVisibilityCapacity * VISIBILITY_BYTES_PER_SECTION,
            VISIBILITY_INITIAL_FILL_VALUE
        );
        translucentVisibilityInitPending = false;
    }

    public void beginFrame() {
        frameOpen = true;
        cullDispatchedThisFrame = false;
        translucentCullDispatchedThisFrame = false;
        totalDrawCountThisFrame = 0;
        if (!isHiZCullingEnabled()) {
            terrainHistoryValid = false;
        }
        RenderTarget mainTarget = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if (pyramid != null && (pyramid.sourceWidth() != mainTarget.width || pyramid.sourceHeight() != mainTarget.height)) {
            terrainHistoryValid = false;
        }
    }

    public void endFrame() {
        if (cullDispatchedThisFrame) {
            if (HiZDebugOverlay.isReadbackRequested()) {
                stagingDrawCountRing[stagingHead] = totalDrawCountThisFrame;
                stagingSectionCountRing[stagingHead] = cachedSectionCount;
                copyVisibilityToStaging();
            } else {
                invalidateStagingReadback();
            }
            // Swap only after a completed dispatch so failed frames retain a usable history.
            writingToA = !writingToA;
        }
        if (translucentCullDispatchedThisFrame) {
            translucentWritingToA = !translucentWritingToA;
        }
        frameOpen = false;
        cullDispatchedThisFrame = false;
        translucentCullDispatchedThisFrame = false;
        cachedTranslucentCamSlice = null;
        cachedTranslucentSectionTableSlice = null;
        if (uniformFrameBegun) {
            uniformUploader.endFrame();
            uniformFrameBegun = false;
        }
    }

    public int visibilitySectionCount() {
        return stagingFrameCounter >= STAGING_FRAMES
            ? stagingSectionCountRing[stagingHead]
            : cachedSectionCount;
    }

    @Nullable
    public GpuBufferSlice.MappedView mapVisibilityForRead() {
        GpuBuffer[] ring = this.stagingRing;
        int sectionCount = visibilitySectionCount();
        if (ring == null || sectionCount <= 0) {
            return null;
        }
        if (stagingFrameCounter < STAGING_FRAMES) {
            return null;
        }
        GpuBuffer staging = ring[stagingHead];
        if (staging == null) {
            return null;
        }
        long bytes = (long) sectionCount * VISIBILITY_BYTES_PER_SECTION;
        return new GpuBufferSlice(staging, 0L, bytes).map(true, false);
    }

    public boolean isStagingReadbackReady() {
        return stagingRing != null && stagingFrameCounter >= STAGING_FRAMES;
    }

    public int stagingDrawCount() {
        if (stagingFrameCounter < STAGING_FRAMES) return 0;
        return stagingDrawCountRing[stagingHead];
    }

    private void invalidateStagingReadback() {
        stagingHead = 0;
        stagingFrameCounter = 0;
    }

    private void copyVisibilityToStaging() {
        GpuBuffer src = currentVisibilityBuffer();
        GpuBuffer[] ring = this.stagingRing;
        if (src == null || ring == null || cachedSectionCount <= 0) {
            return;
        }
        GpuBuffer staging = ring[stagingHead];
        if (staging == null) {
            return;
        }
        long bytes = (long) cachedSectionCount * VISIBILITY_BYTES_PER_SECTION;
        MangoVulkanCommandAccess access = vulkanCommandAccess();
        barrierBufferRange(access, src, bytes,
            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
            VK_ACCESS_2_SHADER_WRITE_BIT,
            VK_PIPELINE_STAGE_2_TRANSFER_BIT,
            VK_ACCESS_2_TRANSFER_READ_BIT
        );
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack)
                .srcOffset(0L)
                .dstOffset(0L)
                .size(bytes);
            VK12.vkCmdCopyBuffer(
                access.mango$getCommandBuffer(),
                ((VulkanGpuBuffer) src).vkBuffer(),
                ((VulkanGpuBuffer) staging).vkBuffer(),
                region
            );
        }
        barrierBufferRange(access, staging, bytes,
            VK_PIPELINE_STAGE_2_TRANSFER_BIT,
            VK_ACCESS_2_TRANSFER_WRITE_BIT,
            VK_PIPELINE_STAGE_2_HOST_BIT,
            VK_ACCESS_2_HOST_READ_BIT
        );
        stagingHead = (stagingHead + 1) % STAGING_FRAMES;
        stagingFrameCounter++;
    }

    private void beginUniformFrame() {
        if (!uniformFrameBegun) {
            int alignment = uniformAlignment();
            int cameraBytes = alignUp(CAMERA_UNIFORM_BYTES, alignment) * CAMERA_UNIFORM_SLOTS;
            int terrainUniformSlots = Math.multiplyExact(
                MAX_TERRAIN_DISPATCH_GROUPS,
                TERRAIN_DISPATCH_UNIFORMS_PER_GROUP
            );
            terrainUniformSlots += MAX_TRANSLUCENT_DISPATCH_GROUPS;
            int smallUniformSlots = 1 + MAX_MIP_BUILD_DISPATCHES + terrainUniformSlots;
            int smallUniformSize = Math.max(
                Math.max(MIP_UNIFORM_BYTES, BATCH_PARAMS_UNIFORM_BYTES),
                COMPACT_DISPATCH_UNIFORM_BYTES
            );
            int smallUniformBytes = alignUp(smallUniformSize, alignment) * smallUniformSlots;
            uniformUploader.beginFrame(cameraBytes + smallUniformBytes, 0);
            uniformFrameBegun = true;
        }
    }

    private static int alignUp(int value, int alignment) {
        return (value + alignment - 1) & ~(alignment - 1);
    }

    private void barrierBufferRange(
        MangoVulkanCommandAccess access,
        GpuBuffer buffer,
        long size,
        long srcStage,
        long srcAccess,
        long dstStage,
        long dstAccess
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferMemoryBarrier2.Buffer barrier = VkBufferMemoryBarrier2.calloc(1, stack);
            barrier.sType$Default()
                .srcStageMask(srcStage)
                .srcAccessMask(srcAccess)
                .dstStageMask(dstStage)
                .dstAccessMask(dstAccess)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .buffer(((VulkanGpuBuffer) buffer).vkBuffer())
                .offset(0)
                .size(size > 0 ? size : VK_WHOLE_SIZE);

            VkDependencyInfo depInfo = VkDependencyInfo.calloc(stack).sType$Default()
                .pBufferMemoryBarriers(barrier);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(access.mango$getCommandBuffer(), depInfo);
        }
    }

    private void ensureDepthStagingBuffer(int width, int height) {
        if (depthStagingBuffer != null && depthStagingWidth == width && depthStagingHeight == height) {
            return;
        }
        if (depthStagingBuffer != null) {
            depthStagingBuffer.close();
        }
        long bytes = (long) width * height * Float.BYTES;
        depthStagingBuffer = RenderSystem.getDevice().createBuffer(
            () -> "Mango Hi-Z depth staging",
            GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC | MangoVulkanUsage.STORAGE_BUFFER,
            bytes
        );
        depthStagingWidth = width;
        depthStagingHeight = height;
    }

    private void ensureVisibilityCapacity(int sectionCount) {
        int required = sectionCount;
        if (visibilityCapacity >= required) return;

        int newCapacity = Math.max(required, visibilityCapacity * 2);
        if (newCapacity < INITIAL_VISIBILITY_CAPACITY) newCapacity = INITIAL_VISIBILITY_CAPACITY;

        GpuDevice gpuDevice = RenderSystem.getDevice();

        if (visibilityBufferA != null) visibilityBufferA.close();
        if (visibilityBufferB != null) visibilityBufferB.close();
        if (stagingRing != null) {
            for (GpuBuffer s : stagingRing) {
                if (s != null) s.close();
            }
        }

        long bytes = (long) newCapacity * VISIBILITY_BYTES_PER_SECTION;
        visibilityBufferA = gpuDevice.createBuffer(
            () -> "Mango Hi-Z visibility A",
            VISIBILITY_USAGE,
            bytes
        );
        visibilityBufferB = gpuDevice.createBuffer(
            () -> "Mango Hi-Z visibility B",
            VISIBILITY_USAGE,
            bytes
        );
        stagingRing = new GpuBuffer[STAGING_FRAMES];
        for (int i = 0; i < STAGING_FRAMES; i++) {
            final int slotIndex = i;
            stagingRing[i] = gpuDevice.createBuffer(
                () -> "Mango Hi-Z visibility staging #" + slotIndex,
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                bytes
            );
        }
        invalidateStagingReadback();

        visibilityInitPending = true;

        visibilityCapacity = newCapacity;
    }

    private void flushPendingVisibilityInit(MangoVulkanCommandAccess access, GpuBuffer previousVisibility) {
        if (!visibilityInitPending) {
            return;
        }
        VK12.vkCmdFillBuffer(
            access.mango$getCommandBuffer(),
            ((VulkanGpuBuffer) previousVisibility).vkBuffer(),
            0L,
            (long) visibilityCapacity * VISIBILITY_BYTES_PER_SECTION,
            VISIBILITY_INITIAL_FILL_VALUE
        );
        visibilityInitPending = false;
    }

    private MangoVulkanCommandAccess vulkanCommandAccess() {
        VulkanCommandEncoder cached = this.sharedEncoder;
        if (cached != null) {
            return (MangoVulkanCommandAccess)(Object)cached;
        }
        VulkanDevice vkDevice = getVulkanDevice();
        if (vkDevice == null) {
            throw new IllegalStateException("Hi-Z requires the Vulkan backend");
        }
        VulkanCommandEncoder encoder = vkDevice.createCommandEncoder();
        this.sharedEncoder = encoder;
        return (MangoVulkanCommandAccess)(Object)encoder;
    }

    @Nullable
    private VulkanDevice getVulkanDevice() {
        GpuDevice gpuDevice = RenderSystem.tryGetDevice();
        if (gpuDevice == null) return null;
        if (!(((GpuDeviceAccessor)(Object)gpuDevice).mango$getBackend() instanceof VulkanDevice vk)) {
            return null;
        }
        return vk;
    }

    @Override
    public void close() {
        if (copyPipeline != null) { copyPipeline.close(); copyPipeline = null; }
        if (buildPipeline != null) { buildPipeline.close(); buildPipeline = null; }
        if (batchBuildPipeline != null) { batchBuildPipeline.close(); batchBuildPipeline = null; }
        if (terrainCullPipeline != null) { terrainCullPipeline.close(); terrainCullPipeline = null; }
        if (terrainCullCompactPipeline != null) { terrainCullCompactPipeline.close(); terrainCullCompactPipeline = null; }
        if (pyramid != null) { pyramid.close(); pyramid = null; }
        if (pointSampler != null) { pointSampler.close(); pointSampler = null; }
        if (visibilityBufferA != null) { visibilityBufferA.close(); visibilityBufferA = null; }
        if (visibilityBufferB != null) { visibilityBufferB.close(); visibilityBufferB = null; }
        if (translucentVisibilityA != null) { translucentVisibilityA.close(); translucentVisibilityA = null; }
        if (translucentVisibilityB != null) { translucentVisibilityB.close(); translucentVisibilityB = null; }
        translucentVisibilityCapacity = 0;
        translucentVisibilityInitPending = false;
        if (stagingRing != null) {
            for (GpuBuffer s : stagingRing) {
                if (s != null) s.close();
            }
            stagingRing = null;
        }
        if (depthStagingBuffer != null) { depthStagingBuffer.close(); depthStagingBuffer = null; }
        uniformUploader.close();
        sharedEncoder = null;
        historyTerrainVP = null;
        terrainHistoryValid = false;
        frameOpen = false;
        runtimeUnavailable = true;
        initialized = false;
    }
}
