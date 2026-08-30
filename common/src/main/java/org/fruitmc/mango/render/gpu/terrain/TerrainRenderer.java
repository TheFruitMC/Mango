package org.fruitmc.mango.render.gpu.terrain;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.fruitmc.mango.mixin.accessor.GpuDeviceAccessor;
import org.fruitmc.mango.mixin.accessor.RenderPassAccessor;
import org.fruitmc.mango.render.gpu.IndirectCommandBuffer;
import org.fruitmc.mango.render.gpu.buffer.DeviceLocalTexelBufferUploader;
import org.fruitmc.mango.render.gpu.buffer.PersistentBufferUploader;
import org.fruitmc.mango.render.gpu.buffer.RingBufferUploader;
import org.fruitmc.mango.render.gpu.hiz.HiZCulling;
import org.fruitmc.mango.render.gpu.policy.DynamicUploadPolicy;
import org.fruitmc.mango.render.vulkan.MangoIndirectRenderPass;
import org.fruitmc.mango.render.vulkan.compute.MangoComputePipeline;
import org.fruitmc.mango.render.vulkan.compute.MangoVulkanCommandAccess;
import org.fruitmc.mango.render.vulkan.compute.MangoVulkanUsage;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCopy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Terrain draw preparation and submission. The renderer keeps persistent section/command tables where
 * possible, but falls back to frame-local uploads whenever visibility filtering changes the draw set.
 */
public final class TerrainRenderer implements AutoCloseable {

    private static final TerrainRenderer INSTANCE = new TerrainRenderer();
    private static final int KIB_BYTES = 1024;
    private static final int MIB_BYTES = KIB_BYTES * KIB_BYTES;
    private static final int SECTION_RECORD_BYTES = Integer.BYTES * 4;
    private static final int INITIAL_UPLOAD_KIB = 256;
    private static final int MAX_SECTION_TABLE_MIB = 64;
    private static final int COMMAND_UPLOAD_USAGE = GpuBuffer.USAGE_MAP_WRITE
        | GpuBuffer.USAGE_COPY_DST
        | GpuBuffer.USAGE_INDIRECT_PARAMETERS
        | MangoVulkanUsage.STORAGE_BUFFER;
    private static final int COMPACT_COMMAND_USAGE = GpuBuffer.USAGE_INDIRECT_PARAMETERS
        | MangoVulkanUsage.STORAGE_BUFFER;
    private static final int MERGED_COMMAND_TEMPLATE_USAGE = MangoVulkanUsage.STORAGE_BUFFER;
    private static final String SAMPLER_ZERO = "Sampler0";
    private static final String SAMPLER_TWO = "Sampler2";
    private static final int DRAW_COUNT_BYTES = Integer.BYTES;
    private static final int DRAW_COUNT_INITIAL_CAPACITY = 4096;
    private static final int DRAW_COUNT_ZERO = 0;
    private static final int GROUP_RANGE_UINTS = 2;
    private static final int GROUP_RANGE_BYTES = GROUP_RANGE_UINTS * Integer.BYTES;
    private static final int GROUP_RANGE_INITIAL_CAPACITY = 4096;
    private static final int GROUP_RANGE_USAGE = MangoVulkanUsage.STORAGE_BUFFER;
    private static final int MERGED_COMPACT_SLICE_COUNT = 1;
    private static final float WHITE_COMPONENT = 1.0F;
    private static final Vector4f WHITE_COLOR = new Vector4f(
        WHITE_COMPONENT,
        WHITE_COMPONENT,
        WHITE_COMPONENT,
        WHITE_COMPONENT
    );
    private static final Vector3f ZERO_MODEL_OFFSET = new Vector3f();
    private static final Matrix4f IDENTITY_TEXTURE_TRANSFORM = new Matrix4f();
    private static final int INCOMPLETE_BINDINGS = -1;
    private static final int PRODUCED_RING_COUNT = 3;
    private static final int COUNT_BUFFER_USAGE = GpuBuffer.USAGE_MAP_WRITE
        | GpuBuffer.USAGE_INDIRECT_PARAMETERS
        | MangoVulkanUsage.STORAGE_BUFFER;
    private final DeviceLocalTexelBufferUploader registrySectionUploader = new DeviceLocalTexelBufferUploader(
        () -> "Mango registry terrain section table",
        INITIAL_UPLOAD_KIB * KIB_BYTES,
        MAX_SECTION_TABLE_MIB * MIB_BYTES,
        MangoVulkanUsage.STORAGE_BUFFER
    );
    private final DeviceLocalTexelBufferUploader fallbackSectionUploader = new DeviceLocalTexelBufferUploader(
        () -> "Mango fallback terrain section table",
        INITIAL_UPLOAD_KIB * KIB_BYTES,
        MAX_SECTION_TABLE_MIB * MIB_BYTES,
        MangoVulkanUsage.STORAGE_BUFFER
    );
    private final RingBufferUploader commandUploader = new RingBufferUploader(
        () -> "Mango terrain indirect commands",
        COMMAND_UPLOAD_USAGE,
        HiZCulling::storageBufferAlignment,
        INITIAL_UPLOAD_KIB * KIB_BYTES
    );
    private final RingBufferUploader compactCommandUploader = new RingBufferUploader(
        () -> "Mango terrain compact indirect commands",
        COMPACT_COMMAND_USAGE,
        HiZCulling::storageBufferAlignment,
        INITIAL_UPLOAD_KIB * KIB_BYTES
    );
    private final RingBufferUploader drawCountUploader = new RingBufferUploader(
        () -> "Mango terrain indirect draw counts",
        COUNT_BUFFER_USAGE,
        HiZCulling::storageBufferAlignment,
        DRAW_COUNT_INITIAL_CAPACITY
    );
    private final PersistentBufferUploader mergedGroupRangeUploader = new PersistentBufferUploader(
        () -> "Mango terrain persistent cull group ranges",
        GROUP_RANGE_USAGE
    );
    private final PersistentBufferUploader mergedCommandTemplateUploader = new PersistentBufferUploader(
        () -> "Mango terrain merged persistent indirect template",
        MERGED_COMMAND_TEMPLATE_USAGE
    );
    private final IndirectCommandBuffer commandBuilder = new IndirectCommandBuffer();
    private final ByteBuffer drawCountScratch =
        ByteBuffer.allocateDirect(DRAW_COUNT_BYTES).order(ByteOrder.nativeOrder());
    private final List<PreparedIndirectGroup> preparedGroups = new ArrayList<>();
    private final List<MergedGroupPlan> mergedGroupPlans = new ArrayList<>();
    private final IntArrayList perGroupDrawCounts = new IntArrayList();
    private final RegionVisibilityIndex visibility = new RegionVisibilityIndex();
    private ByteBuffer sectionStaging = MemoryUtil.memAlloc(INITIAL_UPLOAD_KIB * KIB_BYTES);
    private ByteBuffer drawCountTableScratch = MemoryUtil.memAlloc(DRAW_COUNT_INITIAL_CAPACITY).order(ByteOrder.nativeOrder());
    private ByteBuffer groupRangeScratch = MemoryUtil.memAlloc(GROUP_RANGE_INITIAL_CAPACITY).order(ByteOrder.nativeOrder());
    @Nullable private VulkanDevice device;
    @Nullable private VulkanCommandEncoder sharedEncoder;
    private boolean mango$commandFrameBegun;
    private long mango$lastUploadedSectionRevision = Long.MIN_VALUE;
    @Nullable private GpuBuffer mango$lastUploadedSectionBuffer;
    private long mango$visibilityIndexVisibleRevision = TerrainFrame.NO_VISIBLE_SECTIONS_REVISION;
    private long mango$visibilityIndexSectionRevision = Long.MIN_VALUE;
    private boolean mango$visibilityIndexBuilt;
    private long mango$mergedCommandTemplateRevision = Long.MIN_VALUE;
    private long mango$mergedGroupRangeRevision = Long.MIN_VALUE;

    private TerrainRenderer() {
    }

    public static TerrainRenderer get() {
        return INSTANCE;
    }

    public void initialize(VulkanDevice vkDevice) {
        this.device = vkDevice;
    }

    public boolean tryRender(ChunkSectionsToRender chunks, ChunkSectionLayerGroup group, GpuSampler sampler) {
        TerrainFrame frame = ((TerrainFrameHolder)(Object)chunks).mango$getTerrainFrame();
        TerrainSectionRegistry.Snapshot registrySnapshot = frame.registrySnapshot();
        IntSet visibleSlots = frame.visibleSlots();

        boolean useRegistry = registrySnapshot != null && isOpaque(group);
        int fallbackDrawCount = 0;
        if (!useRegistry) {
            if (!frame.isReady()) {
                return false;
            }
            fallbackDrawCount = countCompleteDraws(chunks, group, frame);
            if (fallbackDrawCount == INCOMPLETE_BINDINGS) {
                return false;
            }
        }

        boolean hiZActive = useRegistry && HiZCulling.get().canCullTerrain();
        boolean compactRegistryCommands = hiZActive && HiZCulling.get().canCompactTerrainCommands();
        boolean filteredRegistry = visibleSlots != null && !hiZActive;

        int drawCount;
        if (useRegistry) {
            this.perGroupDrawCounts.clear();
            drawCount = 0;
            long visibleRevision = frame.visibleSectionsRevision();
            long sectionRevision = registrySnapshot.sectionRevision();
            if (filteredRegistry) {
                buildVisibilityIndex(visibleSlots, frame, registrySnapshot);
            }
            for (TerrainSectionRegistry.DrawGroup drawGroup : registrySnapshot.groups()) {
                int dc;
                if (drawGroup.isEmpty()) {
                    dc = 0;
                } else {
                    dc = filteredRegistry
                        ? drawGroup.prepareVisibleCommands(
                            this.visibility,
                            visibleRevision,
                            sectionRevision,
                            true
                        )
                        : drawGroup.drawCount();
                }
                this.perGroupDrawCounts.add(dc);
                drawCount = Math.addExact(drawCount, dc);
            }
        } else {
            drawCount = fallbackDrawCount;
        }
        if (drawCount == 0) {
            return false;
        }

        ByteBuffer sectionData = useRegistry
            ? registrySnapshot.sectionTable()
            : writeSectionTable(frame.sections());
        GpuBuffer sectionBuffer;
        if (useRegistry) {
            long currentRevision = registrySnapshot.sectionRevision();
            GpuBuffer cachedBuffer = this.mango$lastUploadedSectionBuffer;
            if (currentRevision == this.mango$lastUploadedSectionRevision
                && cachedBuffer != null
                && !cachedBuffer.isClosed()) {
                sectionBuffer = cachedBuffer;
            } else {
                sectionBuffer = this.registrySectionUploader.uploadSparse(
                    sectionData,
                    SECTION_RECORD_BYTES,
                    registrySnapshot.dirtySectionSlots()
                );
                this.mango$lastUploadedSectionRevision = currentRevision;
                this.mango$lastUploadedSectionBuffer = sectionBuffer;
            }
        } else {
            sectionBuffer = this.fallbackSectionUploader.upload(sectionData);
        }
        try {
            int commandFrameBytes = Math.multiplyExact(drawCount, IndirectCommandBuffer.COMMAND_SIZE);
            if (!this.mango$commandFrameBegun) {
                int preparedRegistryGroupCount = useRegistry ? countPreparedRegistryGroups() : 0;
                int commandRingBytes = commandFrameBytes;
                int commandSliceCount;
                if (useRegistry) {
                    int translucentBytes = Math.multiplyExact(
                        countDraws(chunks, ChunkSectionLayerGroup.TRANSLUCENT),
                        IndirectCommandBuffer.COMMAND_SIZE
                    );
                    int translucentSliceCount = countNonEmptyDrawGroups(
                        chunks,
                        ChunkSectionLayerGroup.TRANSLUCENT
                    );
                    if (compactRegistryCommands) {
                        commandRingBytes = translucentBytes;
                        commandSliceCount = translucentSliceCount;
                    } else {
                        commandRingBytes = Math.addExact(commandFrameBytes, translucentBytes);
                        commandSliceCount = Math.addExact(
                            preparedRegistryGroupCount,
                            translucentSliceCount
                        );
                    }
                } else {
                    commandSliceCount = countNonEmptyDrawGroups(chunks, group);
                    for (ChunkSectionLayerGroup otherGroup : ChunkSectionLayerGroup.values()) {
                        if (otherGroup == group) continue;
                        int otherBytes = Math.multiplyExact(
                            countDraws(chunks, otherGroup),
                            IndirectCommandBuffer.COMMAND_SIZE
                        );
                        commandRingBytes = Math.addExact(commandRingBytes, otherBytes);
                        commandSliceCount = Math.addExact(
                            commandSliceCount, countNonEmptyDrawGroups(chunks, otherGroup)
                        );
                    }
                }
                DynamicUploadPolicy.beginFrame(this.commandUploader, commandRingBytes, commandSliceCount);
                DynamicUploadPolicy.beginFrame(
                    this.compactCommandUploader,
                    compactRegistryCommands ? commandFrameBytes : 0,
                    compactRegistryCommands ? MERGED_COMPACT_SLICE_COUNT : 0
                );
                DynamicUploadPolicy.beginFrame(
                    this.drawCountUploader,
                    compactRegistryCommands
                        ? Math.multiplyExact(preparedRegistryGroupCount, DRAW_COUNT_BYTES)
                        : 0,
                    compactRegistryCommands ? MERGED_COMPACT_SLICE_COUNT : 0
                );
                this.mango$commandFrameBegun = true;
            }
            RenderTarget renderTarget = group.outputTarget();
            if (useRegistry) {
                prepareRegistryGroups(
                    registrySnapshot, sectionBuffer, filteredRegistry,
                    hiZActive ? frame : null
                );
            } else {
                prepareIndirectGroups(chunks, group, frame);
                if (group == ChunkSectionLayerGroup.TRANSLUCENT) {
                    cullTranslucentGroups(frame, sectionBuffer, sectionData.remaining() / SECTION_RECORD_BYTES);
                }
            }
            renderIndirect(chunks, group, sampler, frame, sectionBuffer, renderTarget);
        } finally {
            this.preparedGroups.clear();
            this.mergedGroupPlans.clear();
        }
        return true;
    }

    public void endFrame() {
        if (this.mango$commandFrameBegun) {
            DynamicUploadPolicy.endFrame(this.commandUploader);
            DynamicUploadPolicy.endFrame(this.compactCommandUploader);
            DynamicUploadPolicy.endFrame(this.drawCountUploader);
            this.mango$commandFrameBegun = false;
        }
    }

    private void renderIndirect(
        ChunkSectionsToRender chunks,
        ChunkSectionLayerGroup group,
        GpuSampler sampler,
        TerrainFrame frame,
        GpuBuffer sectionBuffer,
        RenderTarget renderTarget
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean wireframe = SharedConstants.DEBUG_HOTKEYS && minecraft.wireframe;
        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
            .writeTransform(new DynamicUniforms.Transform(
                frame.modelView(),
                WHITE_COLOR,
                ZERO_MODEL_OFFSET,
                IDENTITY_TEXTURE_TRANSFORM
            ));

        try (RenderPass renderPass = RenderSystem.getDevice()
                    .createCommandEncoder()
                    .createRenderPass(
                        () -> "Mango indirect section layers for " + group.label(),
                        renderTarget.getColorTextureView(),
                        Optional.empty(),
                        renderTarget.getDepthTextureView(),
                        OptionalDouble.empty()
                    )) {
                RenderSystem.bindDefaultUniforms(renderPass);
                renderPass.setUniform("DynamicTransforms", dynamicTransforms);
                renderPass.setUniform("MangoChunkSections", sectionBuffer);
                renderPass.bindTexture(SAMPLER_ZERO, chunks.textureView(), sampler);
                renderPass.bindTexture(
                    SAMPLER_TWO,
                    minecraft.gameRenderer.lightmap(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
                );

                RenderPipeline currentPipeline = null;
                for (PreparedIndirectGroup prepared : this.preparedGroups) {
                    RenderPipeline targetPipeline = pipelineFor(prepared.layer(), wireframe);
                    if (targetPipeline != currentPipeline) {
                        currentPipeline = targetPipeline;
                        renderPass.setPipeline(targetPipeline);
                    }
                    renderPass.setVertexBuffer(prepared.vertexSlot(), prepared.vertexBuffer().slice());
                    renderPass.setIndexBuffer(prepared.indexBuffer(), prepared.indexType());
                    GpuBufferSlice drawCountSlice = prepared.drawCountSlice();
                    if (drawCountSlice != null) {
                        RenderPassBackend backend = ((RenderPassAccessor)(Object)renderPass).mango$getBackend();
                        ((MangoIndirectRenderPass) backend).mango$drawIndexedIndirectCount(
                            prepared.commandSlice(), drawCountSlice, prepared.drawCount()
                        );
                    } else {
                        renderPass.drawIndexedIndirect(prepared.commandSlice(), prepared.drawCount());
                    }
                }
        }
    }

    private void cullTranslucentGroups(TerrainFrame frame, GpuBuffer sectionBuffer, int sectionCount) {
        if (sectionCount <= 0) {
            return;
        }
        HiZCulling hiZ = HiZCulling.get();
        if (this.preparedGroups.isEmpty() || !hiZ.canCullTranslucentTerrain()) {
            return;
        }
        boolean dispatched = false;
        try {
            if (hiZ.beginTranslucentTerrainCull(
                    frame.viewProjection(),
                    new GpuBufferSlice(sectionBuffer, 0L, (long) sectionCount * SECTION_RECORD_BYTES),
                    sectionCount)) {
                for (PreparedIndirectGroup prepared : this.preparedGroups) {
                    hiZ.dispatchTranslucentCullGroup(prepared.commandSlice(), prepared.drawCount());
                    dispatched = true;
                }
            }
        } finally {
            hiZ.endTranslucentTerrainCull();
        }
        if (dispatched) {
            synchronizePreparedForDrawing(false, true, false);
        }
    }

    private void prepareIndirectGroups(
        ChunkSectionsToRender chunks,
        ChunkSectionLayerGroup group,
        TerrainFrame frame
    ) {
        RenderSystem.AutoStorageIndexBuffer autoIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        int maxIndices = chunks.maxIndicesRequired();
        GpuBuffer defaultIndexBuffer = maxIndices == 0
            ? null
            : autoIndices.getBuffer(maxIndices);
        IndexType defaultIndexType = maxIndices == 0
            ? null
            : autoIndices.type();

        this.preparedGroups.clear();
        for (ChunkSectionLayer layer : group.layers()) {
            Int2ObjectOpenHashMap<List<RenderPass.Draw<GpuBufferSlice[]>>> drawGroups = chunks.drawGroupsPerLayer().get(layer);
            for (List<RenderPass.Draw<GpuBufferSlice[]>> draws : drawGroups.values()) {
                if (draws.isEmpty()) {
                    continue;
                }

                RenderPass.Draw<GpuBufferSlice[]> first = draws.getFirst();
                GpuBuffer indexBuffer = first.indexBuffer() != null ? first.indexBuffer() : defaultIndexBuffer;
                if (indexBuffer == null) {
                    throw new IllegalStateException("Terrain indirect group is missing an index buffer");
                }
                IndexType indexType = first.indexType() != null ? first.indexType() : defaultIndexType;
                if (indexType == null) {
                    throw new IllegalStateException("Terrain indirect group is missing an index type");
                }

                boolean backToFront = layer == ChunkSectionLayer.TRANSLUCENT;
                int drawSize = draws.size();

                this.commandBuilder.clear();
                for (int i = 0; i < drawSize; i++) {
                    RenderPass.Draw<GpuBufferSlice[]> draw = draws.get(backToFront ? drawSize - 1 - i : i);
                    this.commandBuilder.addDraw(
                        draw.indexCount(),
                        draw.firstIndex(),
                        draw.baseVertex(),
                        frame.sectionIndex(draw)
                    );
                }

                int preparedDrawCount = this.commandBuilder.drawCount();
                int commandBytes = Math.multiplyExact(preparedDrawCount, IndirectCommandBuffer.COMMAND_SIZE);
                ByteBuffer commands = this.commandBuilder.buffer().slice(0, commandBytes);
                GpuBufferSlice commandUpload = DynamicUploadPolicy.uploadSlice(
                    this.commandUploader,
                    commands
                );
                this.preparedGroups.add(
                    new PreparedIndirectGroup(
                        layer,
                        first.slot(),
                        first.vertexBuffer(),
                        indexBuffer,
                        indexType,
                        commandUpload,
                        preparedDrawCount,
                        null,
                        null
                    )
                );
            }
        }
    }

    private void prepareRegistryGroups(
        TerrainSectionRegistry.Snapshot snapshot, GpuBuffer sectionBuffer,
        boolean filtered, @Nullable TerrainFrame frame
    ) {
        RenderSystem.AutoStorageIndexBuffer autoIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        int largestDefaultIndexCount = 0;
        for (TerrainSectionRegistry.DrawGroup group : snapshot.groups()) {
            if (group.key().indexBuffer() == null) {
                largestDefaultIndexCount = Math.max(largestDefaultIndexCount, group.maxIndexCount());
            }
        }
        GpuBuffer defaultIndexBuffer = largestDefaultIndexCount == 0
            ? null
            : autoIndices.getBuffer(largestDefaultIndexCount);
        IndexType defaultIndexType = largestDefaultIndexCount == 0
            ? null
            : autoIndices.type();

        boolean hasTransferCopies = false;
        boolean hasHostWrites = filtered;
        HiZCulling hiZ = null;
        if (frame != null) {
            hiZ = HiZCulling.get();
            if (hiZ.isReady()) {
                int sectionCount = snapshot.sectionTable().remaining() / SECTION_RECORD_BYTES;
                long sectionTableBytes = (long) sectionCount * SECTION_RECORD_BYTES;
                GpuBufferSlice sectionTableSlice = new GpuBufferSlice(sectionBuffer, 0, sectionTableBytes);
                if (!hiZ.beginTerrainCull(frame.viewProjection(), sectionTableSlice, sectionCount)) {
                    hiZ = null;
                }
            } else {
                hiZ = null;
            }
        }

        boolean compactCommands = hiZ != null && hiZ.canCompactTerrainCommands();

        if (hiZ != null) {
            prepareMergedRegistryGroups(
                snapshot,
                filtered,
                hiZ,
                defaultIndexBuffer,
                defaultIndexType,
                compactCommands
            );
            return;
        }

        this.preparedGroups.clear();
        int groupIndex = 0;
        for (TerrainSectionRegistry.DrawGroup group : snapshot.groups()) {
            int preparedGroupIndex = groupIndex++;
            int preparedDrawCount = this.perGroupDrawCounts.getInt(preparedGroupIndex);
            if (preparedDrawCount == 0) {
                continue;
            }
            TerrainSectionRegistry.GroupKey key = group.key();
            GpuBuffer indexBuffer = key.indexBuffer() != null ? key.indexBuffer() : defaultIndexBuffer;
            if (indexBuffer == null) {
                throw new IllegalStateException("Registry terrain group is missing an index buffer");
            }
            IndexType indexType = key.indexType() != null ? key.indexType() : defaultIndexType;
            if (indexType == null) {
                throw new IllegalStateException("Registry terrain group is missing an index type");
            }

            int commandBytes = Math.multiplyExact(preparedDrawCount, IndirectCommandBuffer.COMMAND_SIZE);

            GpuBufferSlice commandUpload;
            if (filtered) {
                RingBufferUploader.MappedSlice mapped = DynamicUploadPolicy.allocateMapped(
                    this.commandUploader,
                    commandBytes
                );
                int written = group.writePreparedCommands(mapped.data());
                if (written != preparedDrawCount) {
                    throw new IllegalStateException(
                        "Terrain command count changed during preparation: "
                            + written + " != " + preparedDrawCount
                    );
                }
                commandUpload = mapped.slice();
            } else {
                GpuBufferSlice templateSlice = group.gpuCommandTemplate();
                int templateCommandBytes = (int) templateSlice.length();
                commandUpload = DynamicUploadPolicy.allocateSlice(
                    this.commandUploader,
                    templateCommandBytes
                );
                copyCommands(templateSlice, commandUpload);
                hasTransferCopies = true;
                preparedDrawCount = templateCommandBytes / IndirectCommandBuffer.COMMAND_SIZE;
            }

            GpuBufferSlice compactUpload = null;
            GpuBufferSlice drawCountUpload = null;
            if (hiZ != null) {
                if (compactCommands) {
                    compactUpload = DynamicUploadPolicy.allocateSlice(
                        this.compactCommandUploader,
                        Math.multiplyExact(preparedDrawCount, IndirectCommandBuffer.COMMAND_SIZE)
                    );
                    drawCountUpload = allocateZeroedDrawCount();
                    hasHostWrites = true;
                    hiZ.dispatchCullGroupCompact(
                        commandUpload, compactUpload, drawCountUpload, preparedDrawCount
                    );
                } else {
                    hiZ.dispatchCullGroup(commandUpload, preparedDrawCount);
                }
            }

            this.preparedGroups.add(
                new PreparedIndirectGroup(
                    key.layer(),
                    0,
                    key.vertexBuffer(),
                    indexBuffer,
                    indexType,
                    commandUpload,
                    preparedDrawCount,
                    compactUpload,
                    drawCountUpload
                )
            );
        }

        boolean hasComputeWrites = false;
        if (hiZ != null) {
            hasComputeWrites = hiZ.isCullDispatchedThisFrame();
            hiZ.endTerrainCull();
        }

        if (this.preparedGroups.isEmpty()) {
            return;
        }

        synchronizePreparedForDrawing(hasTransferCopies, hasComputeWrites, hasHostWrites);
    }

    private void prepareMergedRegistryGroups(
        TerrainSectionRegistry.Snapshot snapshot,
        boolean filtered,
        @Nullable HiZCulling hiZ,
        @Nullable GpuBuffer defaultIndexBuffer,
        @Nullable IndexType defaultIndexType,
        boolean compactCommands
    ) {
        this.preparedGroups.clear();
        this.mergedGroupPlans.clear();
        // An unfiltered compact path can reuse the merged template because the draw set is known stable.
        boolean persistentMergedSource = !filtered && compactCommands;

        try {
            int groupIndex = 0;
            int commandOffset = 0;
            for (TerrainSectionRegistry.DrawGroup group : snapshot.groups()) {
                int preparedDrawCount = this.perGroupDrawCounts.getInt(groupIndex++);
                if (preparedDrawCount == 0) {
                    continue;
                }

                TerrainSectionRegistry.GroupKey key = group.key();
                GpuBuffer indexBuffer = key.indexBuffer() != null ? key.indexBuffer() : defaultIndexBuffer;
                if (indexBuffer == null) {
                    throw new IllegalStateException("Merged terrain group is missing an index buffer");
                }
                IndexType indexType = key.indexType() != null ? key.indexType() : defaultIndexType;
                if (indexType == null) {
                    throw new IllegalStateException("Merged terrain group is missing an index type");
                }

                GpuBufferSlice template = null;
                if (!filtered && !persistentMergedSource) {
                    template = group.gpuCommandTemplate();
                    preparedDrawCount = Math.toIntExact(template.length() / IndirectCommandBuffer.COMMAND_SIZE);
                }

                this.mergedGroupPlans.add(
                    new MergedGroupPlan(
                        group,
                        key,
                        indexBuffer,
                        indexType,
                        commandOffset,
                        preparedDrawCount,
                        template
                    )
                );
                commandOffset = Math.addExact(commandOffset, preparedDrawCount);
            }

            if (this.mergedGroupPlans.isEmpty()) {
                return;
            }

            int totalDrawCount = commandOffset;
            int totalCommandBytes = Math.multiplyExact(totalDrawCount, IndirectCommandBuffer.COMMAND_SIZE);
            GpuBufferSlice sourceUpload;
            boolean hasTransferCopies = !filtered && !persistentMergedSource;
            boolean hasHostWrites = filtered;
            if (filtered) {
                RingBufferUploader.MappedSlice mapped = DynamicUploadPolicy.allocateMapped(
                    this.commandUploader,
                    totalCommandBytes
                );
                ByteBuffer arena = mapped.data();
                for (MergedGroupPlan plan : this.mergedGroupPlans) {
                    int planBytes = Math.multiplyExact(plan.drawCount(), IndirectCommandBuffer.COMMAND_SIZE);
                    int planOffset = Math.multiplyExact(plan.commandOffset(), IndirectCommandBuffer.COMMAND_SIZE);
                    int appended = plan.group().writePreparedCommands(arena.slice(planOffset, planBytes));
                    if (appended != plan.drawCount()) {
                        throw new IllegalStateException(
                            "Merged terrain command count changed during preparation: "
                                + appended + " != " + plan.drawCount()
                        );
                    }
                }
                sourceUpload = mapped.slice();
            } else if (persistentMergedSource) {
                sourceUpload = preparePersistentMergedCommandTemplate(snapshot, totalDrawCount);
            } else {
                sourceUpload = DynamicUploadPolicy.allocateSlice(
                    this.commandUploader,
                    totalCommandBytes
                );
                for (MergedGroupPlan plan : this.mergedGroupPlans) {
                    GpuBufferSlice template = plan.template();
                    if (template == null) {
                        throw new IllegalStateException("Merged terrain template was not prepared");
                    }
                    copyCommands(template, commandSubSlice(sourceUpload, plan));
                }
            }

            GpuBufferSlice compactUpload = null;
            GpuBufferSlice countUpload = null;
            if (compactCommands) {
                compactUpload = DynamicUploadPolicy.allocateSlice(
                    this.compactCommandUploader,
                    totalCommandBytes
                );
                countUpload = allocateZeroedDrawCounts(this.mergedGroupPlans.size());
                GpuBufferSlice rangeUpload = uploadGroupRanges(snapshot.sectionRevision(), this.mergedGroupPlans);
                hasHostWrites = true;
                if (hiZ == null) {
                    throw new IllegalStateException("Compact terrain culling requires Hi-Z");
                }
                hiZ.dispatchCullGroupsCompact(
                    sourceUpload,
                    compactUpload,
                    countUpload,
                    rangeUpload,
                    this.mergedGroupPlans.size(),
                    totalDrawCount
                );
            } else {
                if (hiZ != null) {
                    hiZ.dispatchCullGroup(sourceUpload, totalDrawCount);
                }
            }

            for (int planIndex = 0; planIndex < this.mergedGroupPlans.size(); planIndex++) {
                MergedGroupPlan plan = this.mergedGroupPlans.get(planIndex);
                GpuBufferSlice sourceReference = commandSubSlice(sourceUpload, plan);
                GpuBufferSlice compactReference = compactUpload == null
                    ? null
                    : commandSubSlice(compactUpload, plan);
                GpuBufferSlice countReference = countUpload == null
                    ? null
                    : countUpload.slice(Math.multiplyExact(planIndex, DRAW_COUNT_BYTES), DRAW_COUNT_BYTES);
                TerrainSectionRegistry.GroupKey key = plan.key();
                this.preparedGroups.add(
                    new PreparedIndirectGroup(
                        key.layer(),
                        0,
                        key.vertexBuffer(),
                        plan.indexBuffer(),
                        plan.indexType(),
                        sourceReference,
                        plan.drawCount(),
                        compactReference,
                        countReference
                    )
                );
            }
            boolean hasComputeWrites = hiZ != null && hiZ.isCullDispatchedThisFrame();
            synchronizePreparedForDrawing(hasTransferCopies, hasComputeWrites, hasHostWrites);
        } finally {
            if (hiZ != null) {
                hiZ.endTerrainCull();
            }
        }
    }

    private GpuBufferSlice preparePersistentMergedCommandTemplate(
        TerrainSectionRegistry.Snapshot snapshot,
        int totalDrawCount
    ) {
        int totalCommandBytes = Math.multiplyExact(totalDrawCount, IndirectCommandBuffer.COMMAND_SIZE);
        long sectionRevision = snapshot.sectionRevision();
        if (sectionRevision != this.mango$mergedCommandTemplateRevision
            || !this.mergedCommandTemplateUploader.hasBuffer()) {
            this.commandBuilder.clear();
            for (MergedGroupPlan plan : this.mergedGroupPlans) {
                ByteBuffer commands = plan.group().commandTemplate();
                int expectedBytes = Math.multiplyExact(plan.drawCount(), IndirectCommandBuffer.COMMAND_SIZE);
                if (commands.remaining() != expectedBytes) {
                    throw new IllegalStateException(
                        "Merged terrain template length changed during preparation: "
                            + commands.remaining() + " != " + expectedBytes
                    );
                }
                this.commandBuilder.addAll(commands, plan.drawCount());
            }
            if (this.commandBuilder.drawCount() != totalDrawCount) {
                throw new IllegalStateException(
                    "Merged terrain template count changed during preparation: "
                        + this.commandBuilder.drawCount() + " != " + totalDrawCount
                );
            }

            GpuBufferSlice uploaded = this.mergedCommandTemplateUploader.upload(
                this.commandBuilder.buffer().slice(0, totalCommandBytes)
            );
            this.mango$mergedCommandTemplateRevision = sectionRevision;
            return uploaded;
        }

        GpuBufferSlice cached = this.mergedCommandTemplateUploader.slice();
        if (cached.length() != totalCommandBytes) {
            throw new IllegalStateException(
                "Cached merged terrain template length does not match registry revision: "
                    + cached.length() + " != " + totalCommandBytes
            );
        }
        return cached;
    }

    private static GpuBufferSlice commandSubSlice(GpuBufferSlice arena, MergedGroupPlan plan) {
        long byteOffset = Math.multiplyExact((long)plan.commandOffset(), IndirectCommandBuffer.COMMAND_SIZE);
        long byteLength = Math.multiplyExact((long)plan.drawCount(), IndirectCommandBuffer.COMMAND_SIZE);
        return arena.slice(byteOffset, byteLength);
    }

    private GpuBufferSlice allocateZeroedDrawCounts(int groupCount) {
        int bytes = Math.multiplyExact(groupCount, DRAW_COUNT_BYTES);
        ensureDrawCountTableCapacity(bytes);
        for (int offset = 0; offset < bytes; offset += DRAW_COUNT_BYTES) {
            this.drawCountTableScratch.putInt(offset, DRAW_COUNT_ZERO);
        }
        ByteBuffer counts = this.drawCountTableScratch.slice(0, bytes);
        return DynamicUploadPolicy.uploadSlice(
            this.drawCountUploader,
            counts
        );
    }

    private GpuBufferSlice uploadGroupRanges(long sectionRevision, List<MergedGroupPlan> plans) {
        int bytes = Math.multiplyExact(plans.size(), GROUP_RANGE_BYTES);
        if (sectionRevision == this.mango$mergedGroupRangeRevision
            && this.mergedGroupRangeUploader.hasBuffer()) {
            GpuBufferSlice cached = this.mergedGroupRangeUploader.slice();
            if (cached.length() == bytes) {
                return cached;
            }
        }
        ensureGroupRangeCapacity(bytes);
        int offset = 0;
        for (MergedGroupPlan plan : plans) {
            this.groupRangeScratch.putInt(offset, Math.addExact(plan.commandOffset(), plan.drawCount()));
            this.groupRangeScratch.putInt(offset + Integer.BYTES, plan.commandOffset());
            offset += GROUP_RANGE_BYTES;
        }
        ByteBuffer ranges = this.groupRangeScratch.slice(0, bytes);
        GpuBufferSlice uploaded = this.mergedGroupRangeUploader.upload(ranges);
        this.mango$mergedGroupRangeRevision = sectionRevision;
        return uploaded;
    }

    private GpuBufferSlice allocateZeroedDrawCount() {
        this.drawCountScratch.clear();
        this.drawCountScratch.putInt(DRAW_COUNT_ZERO);
        this.drawCountScratch.flip();
        return DynamicUploadPolicy.uploadSlice(
            this.drawCountUploader,
            this.drawCountScratch
        );
    }

    private void copyCommands(GpuBufferSlice source, GpuBufferSlice target) {
        if (source.length() != target.length()) {
            throw new IllegalArgumentException(
                "Terrain command copy length mismatch: " + source.length() + " != " + target.length()
            );
        }
        MangoVulkanCommandAccess encoder = vulkanCommandAccess();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack)
                .srcOffset(source.offset())
                .dstOffset(target.offset())
                .size(source.length());
            VK12.vkCmdCopyBuffer(
                encoder.mango$getCommandBuffer(),
                ((VulkanGpuBuffer)source.buffer()).vkBuffer(),
                ((VulkanGpuBuffer)target.buffer()).vkBuffer(),
                region
            );
        }
    }

    private void synchronizePreparedForDrawing(
        boolean hasTransferCopies,
        boolean hasComputeWrites,
        boolean hasHostWrites
    ) {
        long sourceStage = producerStages(hasTransferCopies, hasComputeWrites, hasHostWrites);
        if (sourceStage == 0L) {
            return;
        }
        long sourceAccess = producerAccess(hasTransferCopies, hasComputeWrites, hasHostWrites);
        long destinationStage = KHRSynchronization2.VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT_KHR;
        long destinationAccess = KHRSynchronization2.VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT_KHR;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            MangoComputePipeline.BarrierBatch batch =
                MangoComputePipeline.barrierBatch(stack, 0, PRODUCED_RING_COUNT);
            addProducedRingBarrier(batch, this.commandUploader,
                sourceStage, sourceAccess, destinationStage, destinationAccess);
            addProducedRingBarrier(batch, this.drawCountUploader,
                sourceStage, sourceAccess, destinationStage, destinationAccess);
            addProducedRingBarrier(
                batch,
                this.compactCommandUploader,
                KHRSynchronization2.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT_KHR,
                KHRSynchronization2.VK_ACCESS_2_SHADER_WRITE_BIT_KHR,
                destinationStage,
                destinationAccess
            );
            batch.submit(vulkanCommandAccess());
        }
    }

    private static void addProducedRingBarrier(
        MangoComputePipeline.BarrierBatch batch,
        RingBufferUploader uploader,
        long sourceStage,
        long sourceAccess,
        long destinationStage,
        long destinationAccess
    ) {
        GpuBufferSlice reserved = uploader.reservedSlice();
        if (reserved != null) {
            batch.addBuffer(sourceStage, sourceAccess, destinationStage, destinationAccess, reserved);
        }
    }

    private static long producerStages(boolean hasTransferCopies, boolean hasComputeWrites, boolean hasHostWrites) {
        long stages = 0L;
        if (hasTransferCopies) {
            stages |= KHRSynchronization2.VK_PIPELINE_STAGE_2_TRANSFER_BIT_KHR;
        }
        if (hasComputeWrites) {
            stages |= KHRSynchronization2.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT_KHR;
        }
        if (hasHostWrites) {
            stages |= KHRSynchronization2.VK_PIPELINE_STAGE_2_HOST_BIT_KHR;
        }
        return stages;
    }

    private static long producerAccess(boolean hasTransferCopies, boolean hasComputeWrites, boolean hasHostWrites) {
        long access = 0L;
        if (hasTransferCopies) {
            access |= KHRSynchronization2.VK_ACCESS_2_TRANSFER_WRITE_BIT_KHR;
        }
        if (hasComputeWrites) {
            access |= KHRSynchronization2.VK_ACCESS_2_SHADER_WRITE_BIT_KHR;
        }
        if (hasHostWrites) {
            access |= KHRSynchronization2.VK_ACCESS_2_HOST_WRITE_BIT_KHR;
        }
        return access;
    }

    private MangoVulkanCommandAccess vulkanCommandAccess() {
        VulkanCommandEncoder cached = this.sharedEncoder;
        if (cached != null) {
            return (MangoVulkanCommandAccess)(Object)cached;
        }
        VulkanDevice vulkanDevice = vulkanDevice();
        VulkanCommandEncoder encoder = vulkanDevice.createCommandEncoder();
        this.sharedEncoder = encoder;
        return (MangoVulkanCommandAccess)(Object)encoder;
    }

    private VulkanDevice vulkanDevice() {
        VulkanDevice currentDevice = this.device;
        if (currentDevice != null) {
            return currentDevice;
        }
        GpuDeviceBackend backend = ((GpuDeviceAccessor)(Object)RenderSystem.getDevice()).mango$getBackend();
        if (!(backend instanceof VulkanDevice vulkanDevice)) {
            throw new IllegalStateException("GPU-driven terrain requires the Vulkan backend");
        }
        this.device = vulkanDevice;
        return vulkanDevice;
    }

    private ByteBuffer writeSectionTable(List<DynamicUniforms.ChunkSectionInfo> sections) {
        int requiredBytes = Math.multiplyExact(sections.size(), SECTION_RECORD_BYTES);
        ensureSectionCapacity(requiredBytes);
        int offset = 0;
        for (DynamicUniforms.ChunkSectionInfo section : sections) {
            this.sectionStaging.putInt(offset, section.x());
            this.sectionStaging.putInt(offset + Integer.BYTES, section.y());
            this.sectionStaging.putInt(offset + Integer.BYTES * 2, section.z());
            this.sectionStaging.putInt(offset + Integer.BYTES * 3, Float.floatToRawIntBits(section.visibility()));
            offset += SECTION_RECORD_BYTES;
        }
        return this.sectionStaging.slice(0, requiredBytes);
    }

    private void ensureSectionCapacity(int requiredBytes) {
        if (requiredBytes <= this.sectionStaging.capacity()) {
            return;
        }

        int maxBytes = MAX_SECTION_TABLE_MIB * MIB_BYTES;
        int newCapacity = this.sectionStaging.capacity();
        while (newCapacity < requiredBytes && newCapacity < maxBytes) {
            newCapacity = Math.min(newCapacity * 2, maxBytes);
        }
        if (newCapacity < requiredBytes) {
            throw new IllegalStateException("Terrain section table capacity exceeded: " + requiredBytes + " > " + maxBytes);
        }

        MemoryUtil.memFree(this.sectionStaging);
        this.sectionStaging = MemoryUtil.memAlloc(newCapacity);
    }

    private void ensureDrawCountTableCapacity(int requiredBytes) {
        if (requiredBytes <= this.drawCountTableScratch.capacity()) {
            return;
        }
        int newCapacity = growCapacity(this.drawCountTableScratch.capacity(), requiredBytes);
        MemoryUtil.memFree(this.drawCountTableScratch);
        this.drawCountTableScratch = MemoryUtil.memAlloc(newCapacity).order(ByteOrder.nativeOrder());
    }

    private void ensureGroupRangeCapacity(int requiredBytes) {
        if (requiredBytes <= this.groupRangeScratch.capacity()) {
            return;
        }
        int newCapacity = growCapacity(this.groupRangeScratch.capacity(), requiredBytes);
        MemoryUtil.memFree(this.groupRangeScratch);
        this.groupRangeScratch = MemoryUtil.memAlloc(newCapacity).order(ByteOrder.nativeOrder());
    }

    private static int growCapacity(int currentCapacity, int requiredBytes) {
        int capacity = currentCapacity;
        while (capacity < requiredBytes) {
            capacity = Math.multiplyExact(capacity, 2);
        }
        return capacity;
    }

    private void buildVisibilityIndex(
        IntSet visibleSlots, TerrainFrame frame, TerrainSectionRegistry.Snapshot registrySnapshot
    ) {
        long visibleRevision = frame.visibleSectionsRevision();
        long sectionRevision = registrySnapshot.sectionRevision();
        if (this.mango$visibilityIndexBuilt
            && visibleRevision != TerrainFrame.NO_VISIBLE_SECTIONS_REVISION
            && visibleRevision == this.mango$visibilityIndexVisibleRevision
            && sectionRevision == this.mango$visibilityIndexSectionRevision) {
            return;
        }
        TerrainSectionRegistry.get().buildVisibleRegions(visibleSlots, this.visibility);
        this.mango$visibilityIndexVisibleRevision = visibleRevision;
        this.mango$visibilityIndexSectionRevision = sectionRevision;
        this.mango$visibilityIndexBuilt = true;
    }

    private static int countCompleteDraws(ChunkSectionsToRender chunks, ChunkSectionLayerGroup group, TerrainFrame frame) {
        RenderSystem.AutoStorageIndexBuffer autoIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        int maxIndices = chunks.maxIndicesRequired();
        GpuBuffer defaultIndexBuffer = maxIndices == 0
            ? null
            : autoIndices.getBuffer(maxIndices);
        IndexType defaultIndexType = maxIndices == 0
            ? null
            : autoIndices.type();

        int count = 0;
        for (ChunkSectionLayer layer : group.layers()) {
            for (List<RenderPass.Draw<GpuBufferSlice[]>> draws : chunks.drawGroupsPerLayer().get(layer).values()) {
                if (draws.isEmpty()) {
                    continue;
                }
                RenderPass.Draw<GpuBufferSlice[]> first = draws.getFirst();
                GpuBuffer expectedIndexBuffer = first.indexBuffer() != null ? first.indexBuffer() : defaultIndexBuffer;
                IndexType expectedIndexType = first.indexType() != null ? first.indexType() : defaultIndexType;
                if (expectedIndexBuffer == null || expectedIndexType == null) {
                    return INCOMPLETE_BINDINGS;
                }

                for (RenderPass.Draw<GpuBufferSlice[]> draw : draws) {
                    GpuBuffer indexBuffer = draw.indexBuffer() != null ? draw.indexBuffer() : defaultIndexBuffer;
                    IndexType indexType = draw.indexType() != null ? draw.indexType() : defaultIndexType;
                    if (frame.sectionIndexOrAbsent(draw) == TerrainFrame.NO_SECTION_INDEX
                            || draw.vertexBuffer() != first.vertexBuffer()
                            || indexBuffer != expectedIndexBuffer
                            || indexType != expectedIndexType) {
                        return INCOMPLETE_BINDINGS;
                    }
                }
                count = Math.addExact(count, draws.size());
            }
        }
        return count;
    }

    private static int countDraws(ChunkSectionsToRender chunks, ChunkSectionLayerGroup group) {
        int count = 0;
        for (ChunkSectionLayer layer : group.layers()) {
            for (List<RenderPass.Draw<GpuBufferSlice[]>> draws : chunks.drawGroupsPerLayer().get(layer).values()) {
                count = Math.addExact(count, draws.size());
            }
        }
        return count;
    }

    private static int countNonEmptyDrawGroups(ChunkSectionsToRender chunks, ChunkSectionLayerGroup group) {
        int count = 0;
        for (ChunkSectionLayer layer : group.layers()) {
            for (List<RenderPass.Draw<GpuBufferSlice[]>> draws : chunks.drawGroupsPerLayer().get(layer).values()) {
                if (!draws.isEmpty()) {
                    count++;
                }
            }
        }
        return count;
    }

    private int countPreparedRegistryGroups() {
        int count = 0;
        for (int index = 0; index < this.perGroupDrawCounts.size(); index++) {
            if (this.perGroupDrawCounts.getInt(index) > 0) {
                count++;
            }
        }
        return count;
    }

    private static boolean isOpaque(ChunkSectionLayerGroup group) {
        boolean hasLayer = false;
        for (ChunkSectionLayer layer : group.layers()) {
            if (layer == ChunkSectionLayer.TRANSLUCENT) {
                return false;
            }
            hasLayer = true;
        }
        return hasLayer;
    }

    private static RenderPipeline pipelineFor(ChunkSectionLayer layer, boolean wireframe) {
        if (wireframe) {
            return layer == ChunkSectionLayer.TRANSLUCENT
                ? TerrainPipeline.translucentWireframe()
                : TerrainPipeline.wireframe();
        }
        return switch (layer) {
            case SOLID -> TerrainPipeline.solid();
            case CUTOUT -> TerrainPipeline.cutout();
            case TRANSLUCENT -> TerrainPipeline.translucent();
        };
    }

    private record PreparedIndirectGroup(
        ChunkSectionLayer layer,
        int vertexSlot,
        GpuBuffer vertexBuffer,
        GpuBuffer indexBuffer,
        IndexType indexType,
        GpuBufferSlice commandUpload,
        int drawCount,
        @Nullable GpuBufferSlice compactUpload,
        @Nullable GpuBufferSlice drawCountUpload
    ) {
        private GpuBufferSlice commandSlice() {
            return this.compactUpload != null ? this.compactUpload : this.commandUpload;
        }

        @Nullable
        private GpuBufferSlice drawCountSlice() {
            return this.drawCountUpload;
        }
    }

    private static final class MergedGroupPlan {
        private final TerrainSectionRegistry.DrawGroup group;
        private final TerrainSectionRegistry.GroupKey key;
        private final GpuBuffer indexBuffer;
        private final IndexType indexType;
        private final int commandOffset;
        private final int drawCount;
        @Nullable private final GpuBufferSlice template;

        private MergedGroupPlan(
            TerrainSectionRegistry.DrawGroup group,
            TerrainSectionRegistry.GroupKey key,
            GpuBuffer indexBuffer,
            IndexType indexType,
            int commandOffset,
            int drawCount,
            @Nullable GpuBufferSlice template
        ) {
            this.group = group;
            this.key = key;
            this.indexBuffer = indexBuffer;
            this.indexType = indexType;
            this.commandOffset = commandOffset;
            this.drawCount = drawCount;
            this.template = template;
        }

        private TerrainSectionRegistry.DrawGroup group() { return this.group; }
        private TerrainSectionRegistry.GroupKey key() { return this.key; }
        private GpuBuffer indexBuffer() { return this.indexBuffer; }
        private IndexType indexType() { return this.indexType; }
        private int commandOffset() { return this.commandOffset; }
        private int drawCount() { return this.drawCount; }
        @Nullable private GpuBufferSlice template() { return this.template; }
    }

    @Override
    public void close() {
        this.registrySectionUploader.close();
        this.fallbackSectionUploader.close();
        this.mango$lastUploadedSectionRevision = Long.MIN_VALUE;
        this.mango$lastUploadedSectionBuffer = null;
        this.commandUploader.close();
        this.compactCommandUploader.close();
        this.drawCountUploader.close();
        this.mergedGroupRangeUploader.close();
        this.mergedCommandTemplateUploader.close();
        this.mango$mergedCommandTemplateRevision = Long.MIN_VALUE;
        this.mango$mergedGroupRangeRevision = Long.MIN_VALUE;
        this.commandBuilder.close();
        if (this.sectionStaging != null) {
            MemoryUtil.memFree(this.sectionStaging);
            this.sectionStaging = null;
        }
        if (this.drawCountTableScratch != null) {
            MemoryUtil.memFree(this.drawCountTableScratch);
            this.drawCountTableScratch = null;
        }
        if (this.groupRangeScratch != null) {
            MemoryUtil.memFree(this.groupRangeScratch);
            this.groupRangeScratch = null;
        }
        this.mergedGroupPlans.clear();
        this.preparedGroups.clear();
        this.device = null;
        this.sharedEncoder = null;
    }
}
