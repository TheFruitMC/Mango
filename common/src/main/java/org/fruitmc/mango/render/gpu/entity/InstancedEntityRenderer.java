package org.fruitmc.mango.render.gpu.entity;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.fruitmc.mango.render.gpu.buffer.GpuBufferUtils;
import org.fruitmc.mango.render.gpu.buffer.RingBufferUploader;
import org.fruitmc.mango.render.gpu.buffer.TexelBufferUploader;
import org.fruitmc.mango.render.gpu.item.InstancedItemPipeline;
import org.fruitmc.mango.render.gpu.item.ItemInstanceBatch;
import org.fruitmc.mango.render.gpu.item.ItemInstanceBatcher;
import org.fruitmc.mango.render.gpu.policy.DynamicUploadPolicy;
import org.fruitmc.mango.render.gpu.skinning.BonePalette;
import org.fruitmc.mango.render.gpu.skinning.SkinnedEntityInstanceCollector;
import org.fruitmc.mango.render.vulkan.compute.MangoVulkanUsage;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

public final class InstancedEntityRenderer implements AutoCloseable {

    private static final String ENTITY_LABEL_PREFIX = "Mango entity";
    private static final String BLOCK_ENTITY_LABEL_PREFIX = "Mango block entity";
    private static final int KIB_BYTES = 1024;
    private static final int MIB_BYTES = KIB_BYTES * KIB_BYTES;
    private static final int INITIAL_INSTANCE_RING_KIB = 256;
    private static final int INITIAL_BONE_BUFFER_KIB = 256;
    private static final int MAX_BONE_BUFFER_MIB = 64;
    private static final int DEFAULT_ORDER = 0;
    private static final int VERTEX_UPLOAD_ALIGNMENT = Integer.BYTES;
    private static final int SCRATCH_GROWTH_FACTOR = 2;
    private static final PreparedRenderType[] EMPTY_PREPARED = new PreparedRenderType[0];
    private static final GpuTextureView[] EMPTY_VIEWS = new GpuTextureView[0];
    private static final InstancedEntityRenderer INSTANCE = new InstancedEntityRenderer(ENTITY_LABEL_PREFIX);
    private static final InstancedEntityRenderer BLOCK_ENTITY_INSTANCE = new InstancedEntityRenderer(BLOCK_ENTITY_LABEL_PREFIX);
    private static final int INSTANCE_RING_USAGE = GpuBuffer.USAGE_MAP_WRITE
        | GpuBuffer.USAGE_VERTEX
        | MangoVulkanUsage.STORAGE_BUFFER;

    @Nullable private EntityInstanceBatcher currentBatcher;
    @Nullable private GpuBuffer currentBonePaletteBuffer;
    private final RingBufferUploader instanceUploader;
    private final TexelBufferUploader bonePaletteUploader;
    private final List<PreparedInstancedDraw> preparedDraws = new ArrayList<>();
    private final Int2ObjectMap<IdentityHashMap<RenderType, DrawGroup>> drawGroupsByOrder = new Int2ObjectOpenHashMap<>();
    private final List<DrawGroup> activeDrawGroups = new ArrayList<>();
    private final IdentityHashMap<RenderType, PreparedRenderType> preparedRenderTypes = new IdentityHashMap<>();
    private final DrawPlan solidPlan = new DrawPlan();
    private final DrawPlan translucentPlan = new DrawPlan();
    private PreparedRenderType[] scratchPrepared = EMPTY_PREPARED;
    private GpuTextureView[] scratchColorViews = EMPTY_VIEWS;
    private GpuTextureView[] scratchDepthViews = EMPTY_VIEWS;
    private int scratchGroupCount;
    private boolean uploadFrameOpen;
    private int frameInstances;
    private int frameBatches;
    private int frameDirectDraws;
    private int frameRenderPasses;
    private int frameInstanceBytes;

    private final String drawLabel;
    private InstancedEntityRenderer(String labelPrefix) {
        this.instanceUploader = new RingBufferUploader(
            () -> labelPrefix + " instance ring",
            INSTANCE_RING_USAGE,
            VERTEX_UPLOAD_ALIGNMENT,
            INITIAL_INSTANCE_RING_KIB * KIB_BYTES
        );
        this.bonePaletteUploader = new TexelBufferUploader(
            () -> labelPrefix + " bone palette ring",
            INITIAL_BONE_BUFFER_KIB * KIB_BYTES,
            MAX_BONE_BUFFER_MIB * MIB_BYTES,
            MangoVulkanUsage.STORAGE_BUFFER
        );
        this.drawLabel = labelPrefix + " grouped instanced draws";
    }

    public static InstancedEntityRenderer get() {
        return INSTANCE;
    }

    public static InstancedEntityRenderer getBlockEntity() {
        return BLOCK_ENTITY_INSTANCE;
    }

    public void setBatcher(EntityInstanceBatcher batcher) {
        finishUploadFrame();
        this.currentBatcher = batcher;
        resetFrameMetrics();
    }

    public void clearBatcher() {
        this.currentBatcher = null;
        finishUploadFrame();
        resetFrameMetrics();
        EntityRenderDebugMetrics.clearPublished(this == BLOCK_ENTITY_INSTANCE);
    }

    public void renderSolid() {
        renderPhase(false, false);
    }

    public void renderTranslucent() {
        renderPhase(true, true);
    }

    private void renderPhase(boolean translucent, boolean finishFrame) {
        EntityInstanceBatcher queuedBatcher = this.currentBatcher;
        if (queuedBatcher == null) {
            return;
        }

        boolean phaseCompleted = false;
        try {
            beginUploadFrame(queuedBatcher);
            prepareEntityDraws(queuedBatcher, translucent);
            prepareSkinnedDraws(queuedBatcher, translucent);
            if (!translucent) {
                prepareItemDraws(queuedBatcher);
            }
            buildDrawGroups(translucent ? this.translucentPlan : this.solidPlan);
            renderDrawGroups(this.currentBonePaletteBuffer);
            recordPhaseMetrics();
            phaseCompleted = true;
        } finally {
            this.preparedDraws.clear();
            clearActiveDrawGroups();
            releaseScratch();
            if (finishFrame || !phaseCompleted) {
                this.currentBatcher = null;
                finishUploadFrame();
                if (phaseCompleted) {
                    publishDebugMetrics(queuedBatcher);
                }
            }
        }
    }

    private void beginUploadFrame(EntityInstanceBatcher batcher) {
        if (this.uploadFrameOpen || !batcher.hasBatches()) {
            return;
        }
        int estimatedInstanceBytes = preparePersistentSnapshotsAndEstimateUploadBytes(batcher);
        DynamicUploadPolicy.beginFrame(this.instanceUploader, estimatedInstanceBytes, 0);
        this.uploadFrameOpen = true;

        if (batcher.hasSkinnedBatches()) {
            BonePalette bonePalette = batcher.bonePalette();
            if (bonePalette.entryCount() > 0) {
                this.currentBonePaletteBuffer = this.bonePaletteUploader.uploadSparse(
                    bonePalette.dataSlice(),
                    BonePalette.MATRIX_BYTES,
                    bonePalette.dirtyRecords(),
                    bonePalette.revision()
                );
            }
        }
    }

    private void prepareSkinnedDraws(EntityInstanceBatcher batcher, boolean translucent) {
        for (EntityInstanceBatch batch : batcher.batches()) {
            SkinnedEntityInstanceCollector collector = batch.skinnedCollector();
            EntityMesh mesh = batch.skinnedMesh();
            if (!isPhase(batch.renderType(), translucent) || !batch.shouldRenderSkinned() || mesh == null) {
                continue;
            }
            if (this.currentBonePaletteBuffer == null) {
                throw new IllegalStateException("Missing bone palette buffer for a skinned entity phase");
            }

            this.preparedDraws.add(prepareDraw(
                batch.order(),
                batch.renderType(),
                collector.backingBuffer(),
                collector.instanceDataBytes(),
                collector.instanceCount(),
                mesh,
                DrawKind.SKINNED_ENTITY,
                translucent ? null : batch.preparePersistentSkinned(batcher)
            ));
        }
    }

    private void prepareEntityDraws(EntityInstanceBatcher batcher, boolean translucent) {
        for (EntityInstanceBatch batch : batcher.batches()) {
            EntityInstanceCollector collector = batch.collector();
            EntityMesh mesh = batch.mesh();
            if (!isPhase(batch.renderType(), translucent) || !batch.shouldRenderNonSkinned() || mesh == null) {
                continue;
            }

            this.preparedDraws.add(prepareDraw(
                batch.order(),
                batch.renderType(),
                collector.backingBuffer(),
                collector.instanceDataBytes(),
                collector.instanceCount(),
                mesh,
                DrawKind.ENTITY,
                translucent ? null : batch.preparePersistentNonSkinned(batcher)
            ));
        }
    }

    private void prepareItemDraws(EntityInstanceBatcher batcher) {
        ItemInstanceBatcher itemBatcher = batcher.itemBatcher();
        for (ItemInstanceBatch batch : itemBatcher.batches()) {
            EntityMesh mesh = batch.mesh();
            if (!batch.shouldRender() || mesh == null) {
                continue;
            }

            EntityInstanceCollector collector = batch.collector();
            this.preparedDraws.add(prepareDraw(
                DEFAULT_ORDER,
                batch.renderType(),
                collector.backingBuffer(),
                collector.instanceDataBytes(),
                collector.instanceCount(),
                mesh,
                DrawKind.ITEM,
                batch.preparePersistent(
                    batcher.cameraBlockX(),
                    batcher.cameraBlockY(),
                    batcher.cameraBlockZ(),
                    batcher.cameraAnchorRevision()
                )
            ));
        }
    }

    private PreparedInstancedDraw prepareDraw(
        int order,
        RenderType renderType,
        ByteBuffer backingBuffer,
        int instanceDataBytes,
        int instanceCount,
        EntityMesh mesh,
        DrawKind kind,
        @Nullable GpuBufferSlice persistentSlice
    ) {
        GpuBufferSlice instanceUpload = persistentSlice;
        if (instanceUpload == null) {
            ByteBuffer instanceData = backingBuffer.slice(0, instanceDataBytes);
            instanceUpload = DynamicUploadPolicy.uploadSlice(this.instanceUploader, instanceData);
            this.frameInstanceBytes = Math.addExact(this.frameInstanceBytes, instanceDataBytes);
        }
        return new PreparedInstancedDraw(order, renderType, mesh, instanceUpload, instanceCount, kind);
    }

    private void buildDrawGroups(DrawPlan plan) {
        List<PreparedInstancedDraw> draws = this.preparedDraws;
        if (draws.isEmpty()) {
            return;
        }
        if (plan.matches(draws)) {
            plan.replay(draws, this.activeDrawGroups);
            return;
        }

        plan.beginRecord(draws.size());
        for (int index = 0; index < draws.size(); index++) {
            PreparedInstancedDraw draw = draws.get(index);
            DrawGroup group = groupFor(draw.order(), draw.renderType());
            if (group.draws().isEmpty()) {
                this.activeDrawGroups.add(group);
            }
            group.draws().add(draw);
            plan.record(index, draw, group);
        }
        this.activeDrawGroups.sort(DrawGroup.ORDER_COMPARATOR);
        plan.finishRecord(this.activeDrawGroups);
    }

    private DrawGroup groupFor(int order, RenderType renderType) {
        IdentityHashMap<RenderType, DrawGroup> orderGroups = this.drawGroupsByOrder.get(order);
        if (orderGroups == null) {
            orderGroups = new IdentityHashMap<>();
            this.drawGroupsByOrder.put(order, orderGroups);
        }
        DrawGroup group = orderGroups.get(renderType);
        if (group == null) {
            group = new DrawGroup(order, renderType);
            orderGroups.put(renderType, group);
        }
        return group;
    }

    private void renderDrawGroups(@Nullable GpuBuffer bonePaletteBuffer) {
        int groupCount = this.activeDrawGroups.size();
        if (groupCount == 0) {
            return;
        }
        RenderSystem.AutoStorageIndexBuffer autoIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        GpuBuffer indexBuffer = autoIndices.getBuffer(maximumIndexCount());
        IndexType indexType = autoIndices.type();

        ensureScratchCapacity(groupCount);
        this.scratchGroupCount = groupCount;
        PreparedRenderType[] prepared = this.scratchPrepared;
        GpuTextureView[] colorViews = this.scratchColorViews;
        GpuTextureView[] depthViews = this.scratchDepthViews;
        for (int i = 0; i < groupCount; i++) {
            RenderType renderType = this.activeDrawGroups.get(i).renderType();
            PreparedRenderType preparedRenderType = this.preparedRenderTypes.get(renderType);
            if (preparedRenderType == null) {
                preparedRenderType = renderType.prepare();
                this.preparedRenderTypes.put(renderType, preparedRenderType);
            }
            prepared[i] = preparedRenderType;
            RenderTarget renderTarget = renderType.outputTarget().getRenderTarget();
            colorViews[i] = RenderSystem.outputColorTextureOverride != null
                ? RenderSystem.outputColorTextureOverride
                : renderTarget.getColorTextureView();
            depthViews[i] = renderTarget.useDepth
                ? (RenderSystem.outputDepthTextureOverride != null
                    ? RenderSystem.outputDepthTextureOverride
                    : renderTarget.getDepthTextureView())
                : null;
        }

        int runStart = 0;
        while (runStart < groupCount) {
            int runEnd = runStart + 1;
            while (runEnd < groupCount) {
                if (colorViews[runEnd] != colorViews[runStart] || depthViews[runEnd] != depthViews[runStart]) {
                    break;
                }
                if (samplesAttachmentsOf(prepared[runEnd], colorViews[runStart], depthViews[runStart])) {
                    break;
                }
                runEnd++;
            }
            renderPassRun(
                runStart,
                runEnd,
                prepared,
                colorViews[runStart],
                depthViews[runStart],
                bonePaletteBuffer,
                indexBuffer,
                indexType
            );
            this.frameRenderPasses = Math.addExact(this.frameRenderPasses, 1);
            runStart = runEnd;
        }
    }

    private void renderPassRun(
        int runStart,
        int runEnd,
        PreparedRenderType[] prepared,
        GpuTextureView colorView,
        @Nullable GpuTextureView depthView,
        @Nullable GpuBuffer bonePaletteBuffer,
        GpuBuffer indexBuffer,
        IndexType indexType
    ) {
        try (RenderPass renderPass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(
                    () -> this.drawLabel,
                    colorView,
                    Optional.empty(),
                    depthView,
                    OptionalDouble.empty()
                )) {
            RenderType activeRenderType = null;
            DrawKind activeKind = null;
            for (int i = runStart; i < runEnd; i++) {
                DrawGroup group = this.activeDrawGroups.get(i);
                RenderType renderType = group.renderType();
                PreparedRenderType prep = prepared[i];
                if (renderType != activeRenderType) {
                    activeRenderType = renderType;
                    activeKind = null;
                    for (PreparedRenderType.Texture texture : prep.textures()) {
                        renderPass.bindTexture(texture.name(), texture.textureView(), texture.sampler());
                    }
                }

                for (PreparedInstancedDraw draw : group.draws()) {
                    if (draw.kind() != activeKind) {
                        activeKind = draw.kind();
                        renderPass.setPipeline(activeKind.resolve(renderType.pipeline()));
                        RenderSystem.bindDefaultUniforms(renderPass);
                        renderPass.setUniform("DynamicTransforms", prep.dynamicTransforms());
                        if (activeKind == DrawKind.SKINNED_ENTITY) {
                            renderPass.setUniform("BonePalette", bonePaletteBuffer);
                        }
                    }
                    EntityMesh mesh = draw.mesh();
                    renderPass.setVertexBuffer(0, mesh.gpuBuffer().slice());
                    renderPass.setVertexBuffer(1, draw.instanceSlice());
                    renderPass.setIndexBuffer(indexBuffer, indexType);
                    renderPass.drawIndexed(mesh.indexCount(), draw.instanceCount(), 0, 0, 0);
                }
            }
        }
    }

    private void clearActiveDrawGroups() {
        for (DrawGroup group : this.activeDrawGroups) {
            group.draws().clear();
        }
        this.activeDrawGroups.clear();
    }

    private void ensureScratchCapacity(int groupCount) {
        if (this.scratchPrepared.length >= groupCount) {
            return;
        }
        int capacity = Math.max(groupCount, this.scratchPrepared.length * SCRATCH_GROWTH_FACTOR);
        this.scratchPrepared = new PreparedRenderType[capacity];
        this.scratchColorViews = new GpuTextureView[capacity];
        this.scratchDepthViews = new GpuTextureView[capacity];
    }

    private void releaseScratch() {
        for (int i = 0; i < this.scratchGroupCount; i++) {
            this.scratchPrepared[i] = null;
            this.scratchColorViews[i] = null;
            this.scratchDepthViews[i] = null;
        }
        this.preparedRenderTypes.clear();
        this.scratchGroupCount = 0;
    }

    private void recordPhaseMetrics() {
        for (PreparedInstancedDraw draw : this.preparedDraws) {
            this.frameInstances = Math.addExact(this.frameInstances, draw.instanceCount());
        }
        this.frameBatches = Math.addExact(this.frameBatches, this.preparedDraws.size());
        this.frameDirectDraws = Math.addExact(this.frameDirectDraws, this.preparedDraws.size());
    }

    private void publishDebugMetrics(EntityInstanceBatcher batcher) {
        batcher.debugMetrics().publish(
            this.frameInstances,
            this.frameBatches,
            this.frameDirectDraws,
            0,
            this.frameRenderPasses,
            this.frameInstanceBytes,
            batcher.hasSkinnedBatches() ? batcher.bonePalette().dataBytes() : 0,
            this == BLOCK_ENTITY_INSTANCE
        );
    }

    private void finishUploadFrame() {
        if (!this.uploadFrameOpen) {
            this.currentBonePaletteBuffer = null;
            return;
        }
        this.bonePaletteUploader.endFrame();
        DynamicUploadPolicy.endFrame(this.instanceUploader);
        this.currentBonePaletteBuffer = null;
        this.uploadFrameOpen = false;
    }

    private void resetFrameMetrics() {
        this.frameInstances = 0;
        this.frameBatches = 0;
        this.frameDirectDraws = 0;
        this.frameRenderPasses = 0;
        this.frameInstanceBytes = 0;
    }

    private static boolean isPhase(RenderType renderType, boolean translucent) {
        return renderType.hasBlending() == translucent;
    }

    private static int preparePersistentSnapshotsAndEstimateUploadBytes(EntityInstanceBatcher batcher) {
        int requiredBytes = 0;
        for (EntityInstanceBatch batch : batcher.batches()) {
            boolean translucent = batch.renderType().hasBlending();
            if (batch.shouldRenderNonSkinned()
                && (translucent || batch.preparePersistentNonSkinned(batcher) == null)) {
                requiredBytes = addUploadBytes(requiredBytes, batch.collector().instanceDataBytes());
            }
            if (batch.shouldRenderSkinned()
                && (translucent || batch.preparePersistentSkinned(batcher) == null)) {
                requiredBytes = addUploadBytes(requiredBytes, batch.skinnedCollector().instanceDataBytes());
            }
        }
        for (ItemInstanceBatch batch : batcher.itemBatcher().batches()) {
            if (batch.shouldRender()
                && batch.preparePersistent(
                    batcher.cameraBlockX(),
                    batcher.cameraBlockY(),
                    batcher.cameraBlockZ(),
                    batcher.cameraAnchorRevision()
                ) == null) {
                requiredBytes = addUploadBytes(requiredBytes, batch.collector().instanceDataBytes());
            }
        }
        return requiredBytes;
    }

    private static int addUploadBytes(int requiredBytes, int instanceBytes) {
        return Math.addExact(
            GpuBufferUtils.alignUp(requiredBytes, VERTEX_UPLOAD_ALIGNMENT),
            instanceBytes
        );
    }

    @Override
    public void close() {
        finishUploadFrame();
        this.instanceUploader.close();
        this.bonePaletteUploader.close();
        this.preparedDraws.clear();
        this.drawGroupsByOrder.clear();
        this.activeDrawGroups.clear();
        this.preparedRenderTypes.clear();
        this.solidPlan.invalidate();
        this.translucentPlan.invalidate();
        releaseScratch();
        this.scratchPrepared = EMPTY_PREPARED;
        this.scratchColorViews = EMPTY_VIEWS;
        this.scratchDepthViews = EMPTY_VIEWS;
    }

    private int maximumIndexCount() {
        int maximum = 0;
        for (PreparedInstancedDraw draw : this.preparedDraws) {
            maximum = Math.max(maximum, draw.mesh().indexCount());
        }
        if (maximum <= 0) {
            throw new IllegalStateException("Instanced draw phase has no indices");
        }
        return maximum;
    }

    private record DrawGroup(int order, RenderType renderType, List<PreparedInstancedDraw> draws) {
        private static final Comparator<DrawGroup> ORDER_COMPARATOR = Comparator.comparingInt(DrawGroup::order);

        private DrawGroup(int order, RenderType renderType) {
            this(order, renderType, new ArrayList<>());
        }
    }

    private static final class DrawPlan {

        private static final RenderType[] EMPTY_RENDER_TYPES = new RenderType[0];
        private static final int[] EMPTY_ORDERS = new int[0];
        private static final DrawGroup[] EMPTY_GROUPS = new DrawGroup[0];

        private final List<DrawGroup> groups = new ArrayList<>();
        private RenderType[] renderTypes = EMPTY_RENDER_TYPES;
        private int[] orders = EMPTY_ORDERS;
        private DrawGroup[] drawGroups = EMPTY_GROUPS;
        private int drawCount;
        private boolean recorded;

        private boolean matches(List<PreparedInstancedDraw> draws) {
            int count = draws.size();
            if (!this.recorded || count != this.drawCount) {
                return false;
            }
            for (int index = 0; index < count; index++) {
                PreparedInstancedDraw draw = draws.get(index);
                if (draw.renderType() != this.renderTypes[index] || draw.order() != this.orders[index]) {
                    return false;
                }
            }
            return true;
        }

        private void replay(List<PreparedInstancedDraw> draws, List<DrawGroup> activeGroups) {
            activeGroups.addAll(this.groups);
            for (int index = 0; index < this.drawCount; index++) {
                this.drawGroups[index].draws().add(draws.get(index));
            }
        }

        private void beginRecord(int drawCount) {
            this.recorded = false;
            this.drawCount = drawCount;
            this.groups.clear();
            if (this.orders.length < drawCount) {
                int capacity = Math.max(drawCount, this.orders.length * SCRATCH_GROWTH_FACTOR);
                this.renderTypes = new RenderType[capacity];
                this.orders = new int[capacity];
                this.drawGroups = new DrawGroup[capacity];
            }
        }

        private void record(int index, PreparedInstancedDraw draw, DrawGroup group) {
            this.renderTypes[index] = draw.renderType();
            this.orders[index] = draw.order();
            this.drawGroups[index] = group;
        }

        private void finishRecord(List<DrawGroup> activeGroups) {
            this.groups.addAll(activeGroups);
            this.recorded = true;
        }

        private void invalidate() {
            this.recorded = false;
            this.drawCount = 0;
            this.groups.clear();
            this.renderTypes = EMPTY_RENDER_TYPES;
            this.orders = EMPTY_ORDERS;
            this.drawGroups = EMPTY_GROUPS;
        }
    }

    private static boolean samplesAttachmentsOf(
        PreparedRenderType candidate,
        GpuTextureView colorView,
        @Nullable GpuTextureView depthView
    ) {
        GpuTexture color = colorView.texture();
        GpuTexture depth = depthView != null ? depthView.texture() : null;
        for (PreparedRenderType.Texture texture : candidate.textures()) {
            GpuTextureView view = texture.textureView();
            if (view == null) {
                continue;
            }
            GpuTexture backing = view.texture();
            if (backing == color || (depth != null && backing == depth)) {
                return true;
            }
        }
        return false;
    }

    private enum DrawKind {
        ENTITY,
        SKINNED_ENTITY,
        ITEM;

        private RenderPipeline resolve(RenderPipeline source) {
            return switch (this) {
                case ENTITY -> InstancedEntityPipeline.get(source);
                case SKINNED_ENTITY -> InstancedEntityPipeline.getSkinned(source);
                case ITEM -> InstancedItemPipeline.get(source);
            };
        }
    }

    private record PreparedInstancedDraw(
        int order,
        RenderType renderType,
        EntityMesh mesh,
        GpuBufferSlice instanceSlice,
        int instanceCount,
        DrawKind kind
    ) {
    }
}
