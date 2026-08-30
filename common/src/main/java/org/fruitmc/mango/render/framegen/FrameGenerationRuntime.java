package org.fruitmc.mango.render.framegen;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.TimerQuery;
import com.mojang.blaze3d.textures.*;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import org.fruitmc.mango.Constants;
import org.fruitmc.mango.config.MangoConfig;
import org.fruitmc.mango.mixin.accessor.CommandEncoderBackendAccessor;
import org.fruitmc.mango.render.gpu.RenderDeviceCapabilities;
import org.fruitmc.mango.render.vulkan.compute.MangoComputeBinding;
import org.fruitmc.mango.render.vulkan.compute.MangoComputePipeline;
import org.fruitmc.mango.render.vulkan.compute.MangoVulkanCommandAccess;
import org.fruitmc.mango.render.vulkan.compute.MangoVulkanUsage;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

import static org.fruitmc.mango.render.vulkan.MangoVulkanConstants.*;

/**
 * Vulkan frame generation built from a retained rendered frame, depth-guided optical flow, and one
 * interpolated texture per output phase. Camera cuts reset the history instead of extrapolating motion.
 */
public final class FrameGenerationRuntime implements AutoCloseable {

    private static final FrameGenerationRuntime INSTANCE = new FrameGenerationRuntime();

    private static final Identifier OPTICAL_FLOW_SHADER_ID =
        Identifier.fromNamespaceAndPath(Constants.MOD_ID, "shaders/compute/framegen_optical_flow.comp");
    private static final Identifier INTERPOLATE_SHADER_ID =
        Identifier.fromNamespaceAndPath(Constants.MOD_ID, "shaders/compute/framegen_interpolate.comp");

    private static final int WORKGROUP_SIZE = 16;
    private static final int FLOW_UNIFORM_BYTES = 144;
    private static final int INTERPOLATE_UNIFORM_BYTES = 16;
    private static final int GPU_SAMPLE_INTERVAL = 120;
    private static final double GPU_TIMER_EMA_ALPHA = 0.3;
    private static final int MOTION_RESOLUTION_DIVISOR = 2;
    private static final double NANOSECONDS_PER_MILLISECOND = 1_000_000.0;

    private static final float INTERPOLATE_DISOCCLUSION_THRESHOLD = 0.5f;
    private static final float MAX_CAMERA_ANGLE_DELTA_DEGREES = 45.0f;
    private static final double MAX_CAMERA_TRANSLATION = 16.0;
    private static final double MAX_CAMERA_TRANSLATION_SQUARED = MAX_CAMERA_TRANSLATION * MAX_CAMERA_TRANSLATION;
    private static final float MINIMUM_MATRIX_DETERMINANT = 1.0e-8f;
    private static final long TIMING_UNAVAILABLE = -1L;
    private static final long TIMING_PROFILING_DISABLED = -2L;

    private static final int HISTORY_COLOR_USAGE =
        GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING;
    private static final int WORLD_DEPTH_USAGE =
        GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING;
    private static final int MOTION_USAGE =
        GpuTexture.USAGE_TEXTURE_BINDING | MangoVulkanUsage.STORAGE_TEXTURE;
    private static final int OUTPUT_USAGE =
        GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING | MangoVulkanUsage.STORAGE_TEXTURE;
    private static final int UNIFORM_BUFFER_USAGE =
        GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST;

    private final FrameGenerationCadence cadence = new FrameGenerationCadence();
    private final ByteBuffer flowUniformUpload =
        ByteBuffer.allocateDirect(FLOW_UNIFORM_BYTES).order(ByteOrder.LITTLE_ENDIAN);
    private final ByteBuffer interpolateUniformUpload =
        ByteBuffer.allocateDirect(INTERPOLATE_UNIFORM_BYTES).order(ByteOrder.LITTLE_ENDIAN);
    private final Matrix4f previousProjection = new Matrix4f();
    private final Matrix4f previousViewRotation = new Matrix4f();
    private final Matrix4f currentClipFromWorld = new Matrix4f();
    private final Matrix4f currentWorldFromClip = new Matrix4f();
    private final Matrix4f previousClipFromCurrentClip = new Matrix4f();
    private final Matrix4f previousClipFromCurrentClipRot = new Matrix4f();

    @Nullable private MangoComputePipeline opticalFlowPipeline;
    @Nullable private MangoComputePipeline interpolatePipeline;
    @Nullable private GpuTexture historyColor;
    @Nullable private GpuTexture motionTexture;
    @Nullable private GpuTexture worldDepth;
    @Nullable private List<GpuTexture> outputColors;
    @Nullable private GpuBuffer flowUniformBuffer;
    @Nullable private List<GpuBuffer> interpolateUniformBuffers;
    @Nullable private GpuSampler pointSampler;
    @Nullable private GpuSampler linearSampler;
    @Nullable private GpuTextureView historyColorView;
    @Nullable private GpuTextureView motionTextureView;
    @Nullable private GpuTextureView worldDepthView;
    @Nullable private List<GpuTextureView> outputColorViews;
    @Nullable private GpuTextureView sourceColorView;
    @Nullable private GpuTextureView sourceDepthView;
    @Nullable private List<MangoComputeBinding> opticalFlowBindings;
    @Nullable private List<List<MangoComputeBinding>> interpolateBindingsPerPhase;
    @Nullable private TimerQuery gpuTimer;
    private int resourceMultiplier;

    private FrameGenerationDebug.Status status = FrameGenerationDebug.Status.DISABLED;
    private int textureWidth;
    private int textureHeight;
    private long renderedFrames;
    private long generatedFrames;
    private long presentedFrames;
    private long fallbackCount;
    private long resetCount;
    private long cpuEncodeTimeNs;
    private long gpuTimeNs = TIMING_UNAVAILABLE;
    private double gpuTimeEmaNs;
    private boolean gpuTimingInitialized;
    private int gpuSamples;
    private boolean gpuSamplePending;
    private boolean gpuTimingDisabled;
    private boolean previousCameraValid;
    private boolean sessionEnabled;
    private boolean failureLatched;
    private boolean generationActiveLogged;
    private boolean gpuTimingLogged;
    private boolean deviceClassLogged;
    private boolean resourcesNeedLayoutInit;
    private boolean closed;
    private double previousCameraPosX;
    private double previousCameraPosY;
    private double previousCameraPosZ;
    private float previousCameraXRot;
    private float previousCameraYRot;

    private FrameGenerationRuntime() {
    }

    public static FrameGenerationRuntime get() {
        return INSTANCE;
    }

    public static boolean isEnabled() {
        return RenderDeviceCapabilities.isVulkanDeviceActive()
            && MangoConfig.INSTANCE.enableFrameGeneration;
    }

    public void onEnabled() {
        if (this.closed || this.sessionEnabled) {
            return;
        }
        this.sessionEnabled = true;
        this.failureLatched = false;
        this.gpuTimingDisabled = false;
        this.resetSessionMetrics();
        this.status = FrameGenerationDebug.Status.WARMING_UP;
        if (!this.deviceClassLogged) {
            this.deviceClassLogged = true;
            Constants.LOG.info(
                "Frame generation device class: {} (MoltenVK: {})",
                RenderDeviceCapabilities.isMoltenVk() ? "translated" : "native-vulkan",
                RenderDeviceCapabilities.isMoltenVk()
            );
        }
        if (RenderDeviceCapabilities.isVulkanDeviceActive()) {
            precompilePipelines();
        }
        this.publishDebug();
    }

    private void precompilePipelines() {
        try {
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            CommandEncoderBackend backend = ((CommandEncoderBackendAccessor) encoder).mango$invokeBackend();
            if (backend instanceof MangoVulkanCommandAccess vulkanAccess) {
                Constants.LOG.info("Pre-compiling frame generation compute pipelines...");
                long startNs = Util.getNanos();
                this.ensureOpticalFlowPipeline(vulkanAccess);
                this.ensureInterpolatePipeline(vulkanAccess);
                long elapsedMs = (Util.getNanos() - startNs) / 1_000_000L;
                Constants.LOG.info("Frame generation pipelines compiled in {} ms", elapsedMs);
            }
        } catch (RuntimeException e) {
            Constants.LOG.warn(
                "Frame generation pipeline pre-compilation failed; will retry on first rendered frame",
                e
            );
        }
    }

    public void onDisabled() {
        if (this.sessionEnabled) {
            this.sessionEnabled = false;
            this.failureLatched = false;
            this.cadence.reset();
            this.previousCameraValid = false;
            this.closeFrameResources();
            this.closeGpuTimer();
            this.resetSessionMetrics();
        }
        this.status = FrameGenerationDebug.Status.DISABLED;
        this.publishDebug();
    }

    private void syncMultiplier() {
        int multiplier = MangoConfig.clampFrameGenerationMultiplier(
            MangoConfig.INSTANCE.frameGenerationMultiplier
        );
        if (multiplier == this.cadence.multiplier()) {
            return;
        }
        this.cadence.configureMultiplier(multiplier);
        this.cadence.reset();
        this.previousCameraValid = false;
        if (this.outputColors != null) {
            this.resetCount++;
        }
    }

    public boolean shouldSkipWorldExtraction(int width, int height) {
        return this.canPresentStoredFrame(width, height);
    }

    public boolean shouldPresentStoredFrame(GpuTextureView colorView) {
        return colorView != null
            && this.canPresentStoredFrame(colorView.getWidth(0), colorView.getHeight(0));
    }

    public void processRenderedFrame(
        GpuTextureView colorView,
        GpuTextureView depthView,
        Matrix4f projection,
        Matrix4f viewRotation,
        double cameraPosX,
        double cameraPosY,
        double cameraPosZ,
        float cameraXRot,
        float cameraYRot
    ) {
        if (this.closed || !isEnabled()) {
            return;
        }
        this.onEnabled();
        this.syncMultiplier();
        this.pollGpuTimer();

        long encodeStartNs = Util.getNanos();
        boolean gpuTimerStarted = false;
        try {
            if (this.failureLatched) {
                this.renderedFrames++;
                this.presentedFrames++;
                this.status = FrameGenerationDebug.Status.ERROR;
                return;
            }

            int width = colorView.getWidth(0);
            int height = colorView.getHeight(0);
            if (width <= 0 || height <= 0 || depthView.getWidth(0) != width || depthView.getHeight(0) != height) {
                this.renderedFrames++;
                this.presentedFrames++;
                this.fallbackCount++;
                this.status = FrameGenerationDebug.Status.CAMERA_CUT;
                return;
            }

            try {
                this.ensureResources(width, height, colorView, depthView);
            } catch (RuntimeException e) {
                this.handleFailure(e);
                this.renderedFrames++;
                this.presentedFrames++;
                return;
            }

            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            CommandEncoderBackend backend = ((CommandEncoderBackendAccessor) encoder).mango$invokeBackend();
            if (!(backend instanceof MangoVulkanCommandAccess vulkanAccess)) {
                throw new IllegalStateException("Frame generation requires a Vulkan command encoder");
            }

            try {
                this.ensureOpticalFlowPipeline(vulkanAccess);
                this.ensureInterpolatePipeline(vulkanAccess);
            } catch (RuntimeException e) {
                this.handleFailure(e);
                this.renderedFrames++;
                this.presentedFrames++;
                return;
            }

            if (this.resourcesNeedLayoutInit) {
                this.initializeTextureLayouts(vulkanAccess);
                this.resourcesNeedLayoutInit = false;
            }

            if (!this.cadence.hasHistory()) {
                this.barrierColorViewForTransferRead(vulkanAccess, colorView);
                this.copyColorToHistory(encoder, colorView);
                this.updatePreviousCamera(
                    projection, viewRotation, cameraPosX, cameraPosY, cameraPosZ, cameraXRot, cameraYRot
                );
                this.cadence.markHistoryReady();
                this.renderedFrames++;
                this.presentedFrames++;
                this.status = FrameGenerationDebug.Status.WARMING_UP;
                return;
            }

            if (this.isCameraCut(cameraPosX, cameraPosY, cameraPosZ, cameraXRot, cameraYRot)
                || !this.prepareReprojectionMatrix(
                    projection, viewRotation, cameraPosX, cameraPosY, cameraPosZ
                )) {
                // Reusing motion across a discontinuity would smear the first frame after the cut.
                this.barrierColorAndHistoryForCopy(vulkanAccess, colorView);
                this.copyColorToHistory(encoder, colorView);
                this.updatePreviousCamera(
                    projection, viewRotation, cameraPosX, cameraPosY, cameraPosZ, cameraXRot, cameraYRot
                );
                this.cadence.markHistoryReady();
                this.renderedFrames++;
                this.presentedFrames++;
                this.fallbackCount++;
                this.resetCount++;
                this.status = FrameGenerationDebug.Status.CAMERA_CUT;
                return;
            }

            this.uploadUniforms(encoder, width, height);
            gpuTimerStarted = this.beginGpuTimerIfDue();

            int phases = this.cadence.multiplier() - 1;

            try (MemoryStack stack = MemoryStack.stackPush()) {
                MangoComputePipeline.BarrierBatch batch = MangoComputePipeline.barrierBatch(stack, 3, 1 + phases)
                    .addBuffer(
                        VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                        VK_ACCESS_2_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                        VK_ACCESS_2_UNIFORM_READ_BIT,
                        this.flowUniformBuffer.slice()
                    );
                for (GpuBuffer uniformBuffer : this.interpolateUniformBuffers) {
                    batch.addBuffer(
                        VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                        VK_ACCESS_2_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                        VK_ACCESS_2_UNIFORM_READ_BIT,
                        uniformBuffer.slice()
                    );
                }
                batch
                    .addImage(
                        VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                        VK_ACCESS_2_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                        VK_ACCESS_2_SHADER_READ_BIT,
                        VK_IMAGE_LAYOUT_GENERAL,
                        VK_IMAGE_LAYOUT_GENERAL,
                        this.historyColorView
                    )
                    .addImage(
                        VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                        VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_2_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                        VK_ACCESS_2_SHADER_READ_BIT,
                        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                        VK_IMAGE_LAYOUT_GENERAL,
                        colorView
                    )
                    .addImage(
                        VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT
                            | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT
                            | VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                        VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT
                            | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT
                            | VK_ACCESS_2_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                        VK_ACCESS_2_SHADER_READ_BIT,
                        VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                        VK_IMAGE_LAYOUT_GENERAL,
                        depthView
                    )
                    .submit(vulkanAccess);
            }

            this.opticalFlowPipeline.dispatch(
                vulkanAccess,
                this.opticalFlowBindings,
                divUp(divUp(width, MOTION_RESOLUTION_DIVISOR), WORKGROUP_SIZE),
                divUp(divUp(height, MOTION_RESOLUTION_DIVISOR), WORKGROUP_SIZE),
                1
            );

            MangoComputePipeline.barrierImage(
                vulkanAccess,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK_ACCESS_2_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK_ACCESS_2_SHADER_READ_BIT,
                VK_IMAGE_LAYOUT_GENERAL,
                VK_IMAGE_LAYOUT_GENERAL,
                this.motionTextureView
            );

            for (int phase = 0; phase < phases; phase++) {
                this.interpolatePipeline.dispatch(
                    vulkanAccess,
                    this.interpolateBindingsPerPhase.get(phase),
                    divUp(width, WORKGROUP_SIZE),
                    divUp(height, WORKGROUP_SIZE),
                    1
                );
            }

            try (MemoryStack stack = MemoryStack.stackPush()) {
                MangoComputePipeline.BarrierBatch batch = MangoComputePipeline.barrierBatch(stack, 2 + phases, 0);
                for (GpuTextureView outputView : this.outputColorViews) {
                    batch.addImage(
                        VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                        VK_ACCESS_2_SHADER_WRITE_BIT,
                        VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                        VK_ACCESS_2_TRANSFER_READ_BIT,
                        VK_IMAGE_LAYOUT_GENERAL,
                        VK_IMAGE_LAYOUT_GENERAL,
                        outputView
                    );
                }
                batch
                    .addImage(
                        VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                        VK_ACCESS_2_SHADER_READ_BIT,
                        VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                        VK_ACCESS_2_TRANSFER_WRITE_BIT,
                        VK_IMAGE_LAYOUT_GENERAL,
                        VK_IMAGE_LAYOUT_GENERAL,
                        this.historyColorView
                    )
                    .addImage(
                        VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                        VK_ACCESS_2_SHADER_READ_BIT,
                        VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                        VK_ACCESS_2_TRANSFER_READ_BIT,
                        VK_IMAGE_LAYOUT_GENERAL,
                        VK_IMAGE_LAYOUT_GENERAL,
                        colorView
                    )
                    .submit(vulkanAccess);
            }

            this.copyColorToHistory(encoder, colorView);
            MangoComputePipeline.barrierImage(
                vulkanAccess,
                VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                VK_ACCESS_2_TRANSFER_READ_BIT,
                VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_IMAGE_LAYOUT_GENERAL,
                VK_IMAGE_LAYOUT_GENERAL,
                colorView
            );
            encoder.copyTextureToTexture(
                this.outputColors.get(0),
                colorView.texture(),
                0,
                0,
                0,
                0,
                0,
                width,
                height
            );
            MangoComputePipeline.barrierImage(
                vulkanAccess,
                VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT
                    | VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT
                    | VK_ACCESS_2_TRANSFER_READ_BIT,
                VK_IMAGE_LAYOUT_GENERAL,
                VK_IMAGE_LAYOUT_GENERAL,
                colorView
            );
            if (gpuTimerStarted) {
                this.endGpuTimer();
                gpuTimerStarted = false;
            }

            this.updatePreviousCamera(
                projection, viewRotation, cameraPosX, cameraPosY, cameraPosZ, cameraXRot, cameraYRot
            );
            this.cadence.finishRenderedFrame();
            this.renderedFrames++;
            this.presentedFrames++;
            this.generatedFrames++;
            this.status = FrameGenerationDebug.Status.GENERATED;
            if (!this.generationActiveLogged) {
                this.generationActiveLogged = true;
                Constants.LOG.info(
                    "Frame generation active at {}x{} with {}x interpolation ({} generated phases per rendered frame)",
                    width,
                    height,
                    this.cadence.multiplier(),
                    phases
                );
            }
        } finally {
            if (gpuTimerStarted) {
                this.endGpuTimerAfterFailure();
            }
            this.cpuEncodeTimeNs = Util.getNanos() - encodeStartNs;
            this.publishDebug();
        }
    }

    public boolean presentStoredFrame(GpuTextureView colorView) {
        if (!this.shouldPresentStoredFrame(colorView)) {
            return false;
        }
        this.pollGpuTimer();
        long encodeStartNs = Util.getNanos();
        try {
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            CommandEncoderBackend backend = ((CommandEncoderBackendAccessor) encoder).mango$invokeBackend();
            if (!(backend instanceof MangoVulkanCommandAccess vulkanAccess)) {
                throw new IllegalStateException("Frame generation requires a Vulkan command encoder");
            }
            int presentIndex = this.cadence.currentPresentIndex();
            boolean interpolated = this.cadence.isInterpolatedPresent();
            GpuTextureView sourceView = interpolated
                ? this.outputColorViews.get(presentIndex)
                : this.historyColorView;
            // The last phase re-presents the rendered frame, keeping one unmodified image in every cycle.
            long sourceAccess = interpolated
                ? VK_ACCESS_2_TRANSFER_READ_BIT
                : VK_ACCESS_2_TRANSFER_WRITE_BIT;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                MangoComputePipeline.barrierBatch(stack, 2, 0)
                    .addImage(
                        VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                        sourceAccess,
                        VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                        VK_ACCESS_2_TRANSFER_READ_BIT,
                        VK_IMAGE_LAYOUT_GENERAL,
                        VK_IMAGE_LAYOUT_GENERAL,
                        sourceView
                    )
                    .addImage(
                        VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                        VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_2_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                        VK_ACCESS_2_TRANSFER_WRITE_BIT,
                        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                        VK_IMAGE_LAYOUT_GENERAL,
                        colorView
                    )
                    .submit(vulkanAccess);
            }
            encoder.copyTextureToTexture(
                sourceView.texture(),
                colorView.texture(),
                0,
                0,
                0,
                0,
                0,
                this.textureWidth,
                this.textureHeight
            );
            MangoComputePipeline.barrierImage(
                vulkanAccess,
                VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT
                    | VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT
                    | VK_ACCESS_2_TRANSFER_READ_BIT,
                VK_IMAGE_LAYOUT_GENERAL,
                VK_IMAGE_LAYOUT_GENERAL,
                colorView
            );
            this.cadence.advancePresent();
            this.presentedFrames++;
            if (interpolated) {
                this.generatedFrames++;
                this.status = FrameGenerationDebug.Status.GENERATED;
            } else {
                this.status = FrameGenerationDebug.Status.PRESENTED_RENDERED;
            }
            return true;
        } finally {
            this.cpuEncodeTimeNs = Util.getNanos() - encodeStartNs;
            this.publishDebug();
        }
    }

    public void resetHistory() {
        if (this.cadence.hasHistory() || this.cadence.hasPendingPresent()) {
            this.resetCount++;
        }
        this.cadence.reset();
        this.previousCameraValid = false;
        if (this.sessionEnabled && !this.failureLatched) {
            this.status = FrameGenerationDebug.Status.WARMING_UP;
            this.publishDebug();
        }
    }

    public void pause() {
        this.resetHistory();
        if (this.sessionEnabled && !this.failureLatched) {
            this.status = FrameGenerationDebug.Status.PAUSED;
            this.publishDebug();
        }
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.closeFrameResources();
        this.closePipelines();
        this.closeGpuTimer();
        this.cadence.reset();
        this.previousCameraValid = false;
        this.sessionEnabled = false;
    }

    private boolean canPresentStoredFrame(int width, int height) {
        return !this.closed
            && this.sessionEnabled
            && !this.failureLatched
            && this.cadence.hasPendingPresent()
            && width == this.textureWidth
            && height == this.textureHeight
            && this.historyColor != null
            && !this.historyColor.isClosed();
    }

    private void ensureResources(
        int width,
        int height,
        GpuTextureView colorView,
        GpuTextureView depthView
    ) {
        if (this.hasCompatibleResources(width, height, colorView, depthView)) {
            return;
        }

        int multiplier = this.cadence.multiplier();
        int phases = multiplier - 1;
        if (this.cadence.hasHistory() || this.cadence.hasPendingPresent()) {
            this.resetCount++;
        }
        Constants.LOG.info(
            "Rebuilding frame generation resources: {}x{} -> {}x{} (colorView changed: {}, depthView changed: {}, multiplier: {}x)",
            this.textureWidth,
            this.textureHeight,
            width,
            height,
            this.sourceColorView != colorView,
            this.sourceDepthView != depthView,
            multiplier
        );
        this.cadence.reset();
        this.previousCameraValid = false;
        this.closeFrameResources();
        this.textureWidth = width;
        this.textureHeight = height;
        this.resourceMultiplier = multiplier;

        this.historyColor = RenderSystem.getDevice().createTexture(
            () -> "Mango FG history color",
            HISTORY_COLOR_USAGE,
            GpuFormat.RGBA8_UNORM,
            width,
            height,
            1,
            1
        );
        int motionWidth = divUp(width, MOTION_RESOLUTION_DIVISOR);
        int motionHeight = divUp(height, MOTION_RESOLUTION_DIVISOR);
        this.motionTexture = RenderSystem.getDevice().createTexture(
            () -> "Mango FG motion",
            MOTION_USAGE,
            GpuFormat.RG16_FLOAT,
            motionWidth,
            motionHeight,
            1,
            1
        );
        this.worldDepth = RenderSystem.getDevice().createTexture(
            () -> "Mango FG world depth",
            WORLD_DEPTH_USAGE,
            GpuFormat.D32_FLOAT,
            width,
            height,
            1,
            1
        );
        this.outputColors = new ArrayList<>(phases);
        this.outputColorViews = new ArrayList<>(phases);
        for (int phase = 0; phase < phases; phase++) {
            final int phaseIndex = phase;
            GpuTexture output = RenderSystem.getDevice().createTexture(
                () -> "Mango FG output phase " + phaseIndex,
                OUTPUT_USAGE,
                GpuFormat.RGBA8_UNORM,
                width,
                height,
                1,
                1
            );
            this.outputColors.add(output);
            this.outputColorViews.add(RenderSystem.getDevice().createTextureView(output));
        }
        this.flowUniformBuffer = RenderSystem.getDevice().createBuffer(
            () -> "Mango FG optical flow uniform",
            UNIFORM_BUFFER_USAGE,
            FLOW_UNIFORM_BYTES
        );
        this.interpolateUniformBuffers = new ArrayList<>(phases);
        for (int phase = 0; phase < phases; phase++) {
            final int phaseIndex = phase;
            this.interpolateUniformBuffers.add(
                RenderSystem.getDevice().createBuffer(
                    () -> "Mango FG interpolate uniform phase " + phaseIndex,
                    UNIFORM_BUFFER_USAGE,
                    INTERPOLATE_UNIFORM_BYTES
                )
            );
        }
        this.pointSampler = RenderSystem.getDevice().createSampler(
            AddressMode.CLAMP_TO_EDGE,
            AddressMode.CLAMP_TO_EDGE,
            FilterMode.NEAREST,
            FilterMode.NEAREST,
            1,
            OptionalDouble.empty()
        );
        this.linearSampler = RenderSystem.getDevice().createSampler(
            AddressMode.CLAMP_TO_EDGE,
            AddressMode.CLAMP_TO_EDGE,
            FilterMode.LINEAR,
            FilterMode.LINEAR,
            1,
            OptionalDouble.empty()
        );

        this.historyColorView = RenderSystem.getDevice().createTextureView(this.historyColor);
        this.motionTextureView = RenderSystem.getDevice().createTextureView(this.motionTexture);
        this.worldDepthView = RenderSystem.getDevice().createTextureView(this.worldDepth);
        this.sourceColorView = colorView;
        this.sourceDepthView = depthView;
        this.createCachedBindings(colorView, depthView);
        this.resourcesNeedLayoutInit = true;
    }

    private void initializeTextureLayouts(MangoVulkanCommandAccess vulkanAccess) {
        int outputCount = this.outputColorViews.size();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            MangoComputePipeline.BarrierBatch batch =
                MangoComputePipeline.barrierBatch(stack, 3 + outputCount, 0)
                    .addImage(
                        VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT,
                        VK_ACCESS_2_NONE,
                        VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                        VK_ACCESS_2_SHADER_WRITE_BIT,
                        VK_IMAGE_LAYOUT_UNDEFINED,
                        VK_IMAGE_LAYOUT_GENERAL,
                        this.motionTextureView
                    );
            for (GpuTextureView outputView : this.outputColorViews) {
                batch.addImage(
                    VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT,
                    VK_ACCESS_2_NONE,
                    VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                    VK_ACCESS_2_SHADER_WRITE_BIT,
                    VK_IMAGE_LAYOUT_UNDEFINED,
                    VK_IMAGE_LAYOUT_GENERAL,
                    outputView
                );
            }
            batch
                .addImage(
                    VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT,
                    VK_ACCESS_2_NONE,
                    VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                    VK_ACCESS_2_TRANSFER_WRITE_BIT,
                    VK_IMAGE_LAYOUT_UNDEFINED,
                    VK_IMAGE_LAYOUT_GENERAL,
                    this.worldDepthView
                )
                .addImage(
                    VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT,
                    VK_ACCESS_2_NONE,
                    VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                    VK_ACCESS_2_TRANSFER_WRITE_BIT,
                    VK_IMAGE_LAYOUT_UNDEFINED,
                    VK_IMAGE_LAYOUT_GENERAL,
                    this.historyColorView
                )
                .submit(vulkanAccess);
        }
    }

    private boolean hasCompatibleResources(
        int width,
        int height,
        GpuTextureView colorView,
        GpuTextureView depthView
    ) {
        if (width != this.textureWidth
            || height != this.textureHeight
            || this.sourceColorView == null
            || this.sourceColorView != colorView
            || this.sourceDepthView == null
            || this.sourceDepthView != depthView
            || this.resourceMultiplier != this.cadence.multiplier()
            || this.historyColor == null
            || this.historyColor.isClosed()
            || this.motionTexture == null
            || this.motionTexture.isClosed()
            || this.worldDepth == null
            || this.worldDepth.isClosed()
            || this.outputColors == null
            || this.outputColors.size() != this.cadence.multiplier() - 1
            || this.flowUniformBuffer == null
            || this.flowUniformBuffer.isClosed()
            || this.interpolateUniformBuffers == null
            || this.interpolateUniformBuffers.size() != this.cadence.multiplier() - 1
            || this.historyColorView == null
            || this.historyColorView.isClosed()
            || this.motionTextureView == null
            || this.motionTextureView.isClosed()
            || this.worldDepthView == null
            || this.worldDepthView.isClosed()
            || this.outputColorViews == null
            || this.outputColorViews.size() != this.cadence.multiplier() - 1
            || this.pointSampler == null
            || this.linearSampler == null
            || this.opticalFlowBindings == null
            || this.interpolateBindingsPerPhase == null) {
            return false;
        }
        for (GpuTexture output : this.outputColors) {
            if (output == null || output.isClosed()) {
                return false;
            }
        }
        for (GpuTextureView view : this.outputColorViews) {
            if (view == null || view.isClosed()) {
                return false;
            }
        }
        for (GpuBuffer buffer : this.interpolateUniformBuffers) {
            if (buffer == null || buffer.isClosed()) {
                return false;
            }
        }
        return true;
    }

    private void createCachedBindings(GpuTextureView colorView, GpuTextureView depthView) {
        this.opticalFlowBindings = List.of(
            MangoComputeBinding.sampledImage(0, depthView, this.pointSampler),
            MangoComputeBinding.sampledImage(1, this.historyColorView, this.pointSampler),
            MangoComputeBinding.sampledImage(2, colorView, this.pointSampler),
            MangoComputeBinding.storageImage(3, this.motionTextureView),
            MangoComputeBinding.uniformBuffer(4, this.flowUniformBuffer.slice()),
            MangoComputeBinding.sampledImage(5, this.worldDepthView, this.pointSampler)
        );
        int phases = this.cadence.multiplier() - 1;
        List<List<MangoComputeBinding>> perPhase = new ArrayList<>(phases);
        for (int phase = 0; phase < phases; phase++) {
            final int phaseIndex = phase;
            perPhase.add(List.of(
                MangoComputeBinding.sampledImage(0, this.historyColorView, this.linearSampler),
                MangoComputeBinding.sampledImage(1, colorView, this.linearSampler),
                MangoComputeBinding.sampledImage(2, this.motionTextureView, this.linearSampler),
                MangoComputeBinding.storageImage(3, this.outputColorViews.get(phaseIndex)),
                MangoComputeBinding.uniformBuffer(4, this.interpolateUniformBuffers.get(phaseIndex).slice()),
                MangoComputeBinding.sampledImage(5, this.worldDepthView, this.pointSampler)
            ));
        }
        this.interpolateBindingsPerPhase = perPhase;
    }

    private void uploadUniforms(CommandEncoder encoder, int width, int height) {
        this.flowUniformUpload.clear();
        this.flowUniformUpload.putFloat(1.0f / (float) width);
        this.flowUniformUpload.putFloat(1.0f / (float) height);
        this.flowUniformUpload.putFloat((float) width);
        this.flowUniformUpload.putFloat((float) height);
        this.previousClipFromCurrentClip.get(this.flowUniformUpload);
        this.previousClipFromCurrentClipRot.get(this.flowUniformUpload);
        this.flowUniformUpload.position(FLOW_UNIFORM_BYTES);
        this.flowUniformUpload.flip();
        encoder.writeToBuffer(this.flowUniformBuffer.slice(), this.flowUniformUpload);

        int multiplier = this.cadence.multiplier();
        for (int phase = 0; phase < this.interpolateUniformBuffers.size(); phase++) {
            float phaseValue = (float) (phase + 1) / (float) multiplier;
            this.interpolateUniformUpload.clear();
            this.interpolateUniformUpload.putFloat(1.0f / (float) width);
            this.interpolateUniformUpload.putFloat(1.0f / (float) height);
            this.interpolateUniformUpload.putFloat(INTERPOLATE_DISOCCLUSION_THRESHOLD);
            this.interpolateUniformUpload.putFloat(phaseValue);
            this.interpolateUniformUpload.flip();
            encoder.writeToBuffer(
                this.interpolateUniformBuffers.get(phase).slice(),
                this.interpolateUniformUpload
            );
        }
    }

    private boolean prepareReprojectionMatrix(
        Matrix4f projection,
        Matrix4f viewRotation,
        double cameraPosX,
        double cameraPosY,
        double cameraPosZ
    ) {
        if (!this.previousCameraValid) {
            return false;
        }

        this.currentClipFromWorld.set(projection).mul(viewRotation);
        float determinant = this.currentClipFromWorld.determinant();
        if (!Float.isFinite(determinant) || Math.abs(determinant) < MINIMUM_MATRIX_DETERMINANT) {
            return false;
        }

        this.currentWorldFromClip.set(this.currentClipFromWorld).invert();
        this.previousClipFromCurrentClip
            .set(this.previousProjection)
            .mul(this.previousViewRotation)
            .translate(
                (float) (cameraPosX - this.previousCameraPosX),
                (float) (cameraPosY - this.previousCameraPosY),
                (float) (cameraPosZ - this.previousCameraPosZ)
            )
            .mul(this.currentWorldFromClip);
        this.previousClipFromCurrentClipRot
            .set(this.previousProjection)
            .mul(this.previousViewRotation)
            .mul(this.currentWorldFromClip);
        return this.previousClipFromCurrentClip.isFinite() && this.previousClipFromCurrentClipRot.isFinite();
    }

    private boolean isCameraCut(
        double cameraPosX,
        double cameraPosY,
        double cameraPosZ,
        float cameraXRot,
        float cameraYRot
    ) {
        if (!this.previousCameraValid) {
            return true;
        }
        double deltaX = cameraPosX - this.previousCameraPosX;
        double deltaY = cameraPosY - this.previousCameraPosY;
        double deltaZ = cameraPosZ - this.previousCameraPosZ;
        double distanceSquared = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
        float xRotationDelta = Math.abs(Mth.wrapDegrees(cameraXRot - this.previousCameraXRot));
        float yRotationDelta = Math.abs(Mth.wrapDegrees(cameraYRot - this.previousCameraYRot));
        return distanceSquared > MAX_CAMERA_TRANSLATION_SQUARED
            || xRotationDelta > MAX_CAMERA_ANGLE_DELTA_DEGREES
            || yRotationDelta > MAX_CAMERA_ANGLE_DELTA_DEGREES;
    }

    private void updatePreviousCamera(
        Matrix4f projection,
        Matrix4f viewRotation,
        double cameraPosX,
        double cameraPosY,
        double cameraPosZ,
        float cameraXRot,
        float cameraYRot
    ) {
        this.previousProjection.set(projection);
        this.previousViewRotation.set(viewRotation);
        this.previousCameraPosX = cameraPosX;
        this.previousCameraPosY = cameraPosY;
        this.previousCameraPosZ = cameraPosZ;
        this.previousCameraXRot = cameraXRot;
        this.previousCameraYRot = cameraYRot;
        this.previousCameraValid = true;
    }

    private void barrierColorViewForTransferRead(
        MangoVulkanCommandAccess vulkanAccess,
        GpuTextureView colorView
    ) {
        MangoComputePipeline.barrierImage(
            vulkanAccess,
            VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_2_TRANSFER_BIT,
            VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_2_TRANSFER_WRITE_BIT,
            VK_PIPELINE_STAGE_2_TRANSFER_BIT,
            VK_ACCESS_2_TRANSFER_READ_BIT,
            VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
            VK_IMAGE_LAYOUT_GENERAL,
            colorView
        );
    }

    private void barrierColorAndHistoryForCopy(
        MangoVulkanCommandAccess vulkanAccess,
        GpuTextureView colorView
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            MangoComputePipeline.barrierBatch(stack, 2, 0)
                .addImage(
                    VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                    VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_2_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                    VK_ACCESS_2_TRANSFER_READ_BIT,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    VK_IMAGE_LAYOUT_GENERAL,
                    colorView
                )
                .addImage(
                    VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                    VK_ACCESS_2_SHADER_READ_BIT | VK_ACCESS_2_TRANSFER_WRITE_BIT | VK_ACCESS_2_TRANSFER_READ_BIT,
                    VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                    VK_ACCESS_2_TRANSFER_WRITE_BIT,
                    VK_IMAGE_LAYOUT_GENERAL,
                    VK_IMAGE_LAYOUT_GENERAL,
                    this.historyColorView
                )
                .submit(vulkanAccess);
        }
    }

    private void copyColorToHistory(CommandEncoder encoder, GpuTextureView colorView) {
        encoder.copyTextureToTexture(
            colorView.texture(),
            this.historyColor,
            0,
            0,
            0,
            0,
            0,
            this.textureWidth,
            this.textureHeight
        );
    }

    public void captureWorldDepth(GpuTextureView mainDepthView) {
        if (!this.canCaptureWorldDepth(mainDepthView)) {
            return;
        }
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        CommandEncoderBackend backend = ((CommandEncoderBackendAccessor) encoder).mango$invokeBackend();
        if (!(backend instanceof MangoVulkanCommandAccess vulkanAccess)) {
            return;
        }
        // Capture before the hand pass so optical flow sees the world, not the player's arms.
        MangoComputePipeline.barrierImage(
            vulkanAccess,
            VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
            VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT | VK_ACCESS_2_TRANSFER_WRITE_BIT,
            VK_PIPELINE_STAGE_2_TRANSFER_BIT,
            VK_ACCESS_2_TRANSFER_READ_BIT,
            VK_IMAGE_LAYOUT_GENERAL,
            VK_IMAGE_LAYOUT_GENERAL,
            mainDepthView
        );
        encoder.copyTextureToTexture(
            mainDepthView.texture(),
            this.worldDepth,
            0,
            0,
            0,
            0,
            0,
            this.textureWidth,
            this.textureHeight
        );
    }

    private boolean canCaptureWorldDepth(GpuTextureView mainDepthView) {
        return this.worldDepth != null
            && this.worldDepthView != null
            && !this.worldDepth.isClosed()
            && !this.worldDepthView.isClosed()
            && !mainDepthView.isClosed()
            && mainDepthView == this.sourceDepthView
            && mainDepthView.getWidth(0) == this.textureWidth
            && mainDepthView.getHeight(0) == this.textureHeight
            && this.worldDepthView.getWidth(0) == this.textureWidth
            && this.worldDepthView.getHeight(0) == this.textureHeight;
    }

    private MangoComputePipeline ensureOpticalFlowPipeline(MangoVulkanCommandAccess deviceAccess) {
        if (this.opticalFlowPipeline != null) {
            return this.opticalFlowPipeline;
        }
        try {
            MangoComputePipeline pipeline = new MangoComputePipeline(
                deviceAccess.mango$getDevice(),
                "Mango FG optical flow",
                OPTICAL_FLOW_SHADER_ID,
                List.of(
                    MangoComputeBinding.Type.SAMPLED_IMAGE,
                    MangoComputeBinding.Type.SAMPLED_IMAGE,
                    MangoComputeBinding.Type.SAMPLED_IMAGE,
                    MangoComputeBinding.Type.STORAGE_IMAGE,
                    MangoComputeBinding.Type.UNIFORM_BUFFER,
                    MangoComputeBinding.Type.SAMPLED_IMAGE
                )
            );
            this.opticalFlowPipeline = pipeline;
            return pipeline;
        } catch (IOException e) {
            throw new IllegalStateException("Frame generation optical flow shader is unavailable", e);
        }
    }

    private MangoComputePipeline ensureInterpolatePipeline(MangoVulkanCommandAccess deviceAccess) {
        if (this.interpolatePipeline != null) {
            return this.interpolatePipeline;
        }
        try {
            MangoComputePipeline pipeline = new MangoComputePipeline(
                deviceAccess.mango$getDevice(),
                "Mango FG interpolate",
                INTERPOLATE_SHADER_ID,
                List.of(
                    MangoComputeBinding.Type.SAMPLED_IMAGE,
                    MangoComputeBinding.Type.SAMPLED_IMAGE,
                    MangoComputeBinding.Type.SAMPLED_IMAGE,
                    MangoComputeBinding.Type.STORAGE_IMAGE,
                    MangoComputeBinding.Type.UNIFORM_BUFFER,
                    MangoComputeBinding.Type.SAMPLED_IMAGE
                )
            );
            this.interpolatePipeline = pipeline;
            return pipeline;
        } catch (IOException e) {
            throw new IllegalStateException("Frame generation interpolate shader is unavailable", e);
        }
    }

    private boolean beginGpuTimerIfDue() {
        if (this.gpuTimingDisabled
            || this.gpuSamplePending
            || this.generatedFrames % GPU_SAMPLE_INTERVAL != 0L) {
            return false;
        }
        try {
            if (this.gpuTimer == null) {
                this.gpuTimer = new TimerQuery();
            }
            this.gpuTimer.beginProfile();
            return true;
        } catch (RuntimeException e) {
            this.disableGpuTiming(e);
            return false;
        }
    }

    private void endGpuTimer() {
        try {
            this.gpuTimer.endProfile();
            this.gpuSamplePending = true;
        } catch (RuntimeException e) {
            this.disableGpuTiming(e);
        }
    }

    private void endGpuTimerAfterFailure() {
        try {
            if (this.gpuTimer != null
                && this.gpuTimer.getStatus() == TimerQuery.Status.STARTED) {
                this.gpuTimer.endProfile();
            }
        } catch (RuntimeException e) {
            this.disableGpuTiming(e);
        }
    }

    private void pollGpuTimer() {
        if (!this.gpuSamplePending || this.gpuTimer == null) {
            return;
        }
        try {
            TimerQuery timer = this.gpuTimer;
            if (timer.getStatus() == TimerQuery.Status.NOT_RECORDING) {
                long sample = timer.get();
                if (!this.gpuTimingInitialized) {
                    this.gpuTimeEmaNs = sample;
                    this.gpuTimingInitialized = true;
                } else {
                    this.gpuTimeEmaNs = sample * GPU_TIMER_EMA_ALPHA
                        + this.gpuTimeEmaNs * (1.0 - GPU_TIMER_EMA_ALPHA);
                }
                this.gpuTimeNs = (long) this.gpuTimeEmaNs;
                this.gpuSamples++;
                this.gpuSamplePending = false;
                if (!this.gpuTimingLogged) {
                    this.gpuTimingLogged = true;
                    Constants.LOG.info(
                        "Frame generation first GPU timing sample: {} ms",
                        this.gpuTimeNs / NANOSECONDS_PER_MILLISECOND
                    );
                }
            }
        } catch (RuntimeException e) {
            this.disableGpuTiming(e);
        }
    }

    private void disableGpuTiming(RuntimeException exception) {
        if (!this.gpuTimingDisabled) {
            Constants.LOG.warn("Frame generation GPU timing is unavailable; continuing without timing", exception);
        }
        this.gpuTimingDisabled = true;
        this.gpuSamplePending = false;
        this.gpuTimeNs = TIMING_PROFILING_DISABLED;
        this.closeGpuTimer();
    }

    private void handleFailure(RuntimeException exception) {
        if (!this.failureLatched) {
            Constants.LOG.error("Frame generation disabled for this session after a recoverable failure", exception);
        }
        this.failureLatched = true;
        this.fallbackCount++;
        this.cadence.reset();
        this.previousCameraValid = false;
        this.status = FrameGenerationDebug.Status.ERROR;
        this.closeFrameResources();
        this.closePipelines();
        this.closeGpuTimer();
    }

    private void closeFrameResources() {
        this.opticalFlowBindings = null;
        this.interpolateBindingsPerPhase = null;
        this.sourceColorView = null;
        this.sourceDepthView = null;
        if (this.historyColorView != null) {
            this.historyColorView.close();
        }
        if (this.motionTextureView != null) {
            this.motionTextureView.close();
        }
        if (this.worldDepthView != null) {
            this.worldDepthView.close();
        }
        if (this.outputColorViews != null) {
            for (GpuTextureView view : this.outputColorViews) {
                if (view != null) {
                    view.close();
                }
            }
        }
        if (this.historyColor != null) {
            this.historyColor.close();
        }
        if (this.motionTexture != null) {
            this.motionTexture.close();
        }
        if (this.worldDepth != null) {
            this.worldDepth.close();
        }
        if (this.outputColors != null) {
            for (GpuTexture output : this.outputColors) {
                if (output != null) {
                    output.close();
                }
            }
        }
        if (this.flowUniformBuffer != null) {
            this.flowUniformBuffer.close();
        }
        if (this.interpolateUniformBuffers != null) {
            for (GpuBuffer buffer : this.interpolateUniformBuffers) {
                if (buffer != null) {
                    buffer.close();
                }
            }
        }
        if (this.pointSampler != null) {
            this.pointSampler.close();
        }
        if (this.linearSampler != null) {
            this.linearSampler.close();
        }
        this.historyColorView = null;
        this.motionTextureView = null;
        this.worldDepthView = null;
        this.outputColorViews = null;
        this.outputColors = null;
        this.historyColor = null;
        this.motionTexture = null;
        this.worldDepth = null;
        this.flowUniformBuffer = null;
        this.interpolateUniformBuffers = null;
        this.pointSampler = null;
        this.linearSampler = null;
        this.textureWidth = 0;
        this.textureHeight = 0;
        this.resourceMultiplier = 0;
        this.resourcesNeedLayoutInit = false;
    }

    private void closePipelines() {
        if (this.opticalFlowPipeline != null) {
            this.opticalFlowPipeline.close();
        }
        if (this.interpolatePipeline != null) {
            this.interpolatePipeline.close();
        }
        this.opticalFlowPipeline = null;
        this.interpolatePipeline = null;
    }

    private void closeGpuTimer() {
        if (this.gpuTimer != null) {
            this.gpuTimer.close();
        }
        this.gpuTimer = null;
        this.gpuSamplePending = false;
    }

    private void resetSessionMetrics() {
        this.renderedFrames = 0L;
        this.generatedFrames = 0L;
        this.presentedFrames = 0L;
        this.fallbackCount = 0L;
        this.resetCount = 0L;
        this.cpuEncodeTimeNs = 0L;
        this.gpuTimeNs = TIMING_UNAVAILABLE;
        this.gpuTimeEmaNs = 0.0;
        this.gpuTimingInitialized = false;
        this.gpuSamples = 0;
        this.generationActiveLogged = false;
        this.gpuTimingLogged = false;
    }

    private void publishDebug() {
        FrameGenerationDebug.publish(
            this.status,
            this.textureWidth,
            this.textureHeight,
            this.renderedFrames,
            this.generatedFrames,
            this.presentedFrames,
            this.fallbackCount,
            this.resetCount,
            this.cpuEncodeTimeNs,
            this.gpuTimeNs
        );
    }

    private static int divUp(int numerator, int denominator) {
        return (numerator + denominator - 1) / denominator;
    }
}
