package org.fruitmc.mango.mixin.vulkan.terrain;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Util;
import net.minecraft.world.phys.Vec3;
import org.fruitmc.mango.render.chunk.vertex.CompactTerrainVertex;
import org.fruitmc.mango.render.gpu.MangoFrameState;
import org.fruitmc.mango.render.gpu.hiz.HiZCulling;
import org.fruitmc.mango.render.gpu.terrain.TerrainFrameHolder;
import org.fruitmc.mango.render.gpu.terrain.RenderSectionContentRevision;
import org.fruitmc.mango.render.gpu.terrain.TerrainFrame;
import org.fruitmc.mango.render.gpu.terrain.TerrainRenderRouter;
import org.fruitmc.mango.render.gpu.terrain.TerrainSectionRegistry;
import org.fruitmc.mango.render.gpu.terrain.TrackedVisibleSectionList;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

    @Unique
    private static final GpuBufferSlice[] mango$NO_SECTION_INFOS = new GpuBufferSlice[0];

    @Unique
    private static final ChunkSectionLayer[] mango$TRANSLUCENT_ONLY = {ChunkSectionLayer.TRANSLUCENT};

    @Unique
    private static final long mango$NO_CAPTURED_REVISION = 0L;

    @Shadow
    @Final
    private LevelRenderState levelRenderState;

    @Shadow
    @Final
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;

    @Shadow
    @Final
    private TextureManager textureManager;

    @Shadow
    @Nullable
    private SectionRenderDispatcher sectionRenderDispatcher;

    @Unique
    private final List<DynamicUniforms.ChunkSectionInfo> mango$SECTION_RECORDS = new ArrayList<>();
    @Unique
    private final Reference2IntOpenHashMap<RenderPass.Draw<?>> mango$SECTION_INDICES = new Reference2IntOpenHashMap<>();
    @Unique
    private boolean mango$captureTerrain;
    @Unique
    @Nullable
    private TerrainSectionRegistry.Snapshot mango$registrySnapshot;
    @Unique
    @Nullable
    private IntSet mango$visibleSlots;
    @Unique
    private final IntOpenHashSet mango$visibleSlotsBuffer = new IntOpenHashSet();
    @Unique
    private long mango$visibleContentsRevision;
    @Unique
    private long mango$translucentVisibleContentsRevision;
    @Unique
    private long mango$visibleSlotsContentsRevision = mango$NO_CAPTURED_REVISION;
    @Unique
    private boolean mango$hasCurrentSection;
    @Unique
    private int mango$currentSectionIndex;
    @Unique
    @Nullable
    private SectionRenderDispatcher.RenderSection mango$currentSection;
    @Unique
    private long mango$currentSectionBindingRevision;
    @Unique
    private final List<SectionRenderDispatcher.RenderSection> mango$capturedSections = new ArrayList<>();
    @Unique
    private final LongArrayList mango$capturedSectionBindingRevisions = new LongArrayList();
    @Unique
    private final List<SectionRenderDispatcher.RenderSection> mango$cachedSections = new ArrayList<>();
    @Unique
    private final LongArrayList mango$cachedSectionBindingRevisions = new LongArrayList();
    @Unique
    private final Reference2IntOpenHashMap<RenderPass.Draw<?>> mango$cachedSectionIndices =
        new Reference2IntOpenHashMap<>();
    @Unique
    @Nullable
    private ChunkSectionsToRender mango$cachedTranslucentPreparation;
    @Unique
    private DynamicUniforms.ChunkSectionInfo[] mango$sectionInfoScratch =
        new DynamicUniforms.ChunkSectionInfo[0];
    @Unique
    // One mutable matrix is safe here because every cached record is rewritten on the render thread.
    private final Matrix4f mango$cachedSectionModelView = new Matrix4f();
    @Unique
    private DynamicUniforms.ChunkSectionInfo[] mango$cachedSectionInfos =
        new DynamicUniforms.ChunkSectionInfo[0];
    @Unique
    private int mango$cachedAtlasWidth;
    @Unique
    private int mango$cachedAtlasHeight;
    @Unique
    @Nullable
    private SectionRenderDispatcher mango$cachedDispatcher;
    @Unique
    private long mango$cachedTranslucentVisibleContentsRevision = mango$NO_CAPTURED_REVISION;
    @Unique
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> mango$preparedSections = new ObjectArrayList<>();
    @Unique
    private boolean mango$hasPreparedSectionSelection;
    @Unique
    private boolean mango$translucentSelectionStable;
    @Unique
    private boolean mango$captureBindingRevisionsStable;
    @Unique
    private boolean mango$translucentCacheHit;
    @Unique
    private boolean mango$terrainFrameCaptured;

    @WrapOperation(
        method = "<init>(Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;"
            + "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;"
            + "Lnet/minecraft/client/resources/model/ModelManager;"
            + "Lnet/minecraft/client/renderer/texture/TextureManager;"
            + "Lnet/minecraft/client/resources/model/sprite/AtlasManager;"
            + "Lnet/minecraft/client/renderer/ShaderManager;"
            + "Lnet/minecraft/client/renderer/GameRenderer;II)V",
        at = @At(
            value = "NEW",
            target = "(I)Lit/unimi/dsi/fastutil/objects/ObjectArrayList;",
            ordinal = 0
        ),
        require = 1
    )
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> mango$createTrackedVisibleSections(
        int initialCapacity,
        Operation<ObjectArrayList<SectionRenderDispatcher.RenderSection>> original
    ) {
        return new TrackedVisibleSectionList(initialCapacity);
    }

    @Inject(
        method = "prepareChunkRenders(Lorg/joml/Matrix4fc;)Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;",
        at = @At("HEAD"),
        cancellable = true,
        require = 1
    )
    private void mango$beginTerrainCapture(
        Matrix4fc modelViewMatrix,
        CallbackInfoReturnable<ChunkSectionsToRender> cir
    ) {
        this.mango$captureTerrain = TerrainRenderRouter.get().shouldCaptureTerrain();
        this.mango$registrySnapshot = this.mango$captureTerrain
            ? TerrainSectionRegistry.get().snapshot()
            : null;

        this.mango$SECTION_RECORDS.clear();
        this.mango$SECTION_INDICES.clear();
        this.mango$capturedSections.clear();
        this.mango$capturedSectionBindingRevisions.clear();
        this.mango$hasCurrentSection = false;
        this.mango$currentSection = null;
        this.mango$currentSectionBindingRevision = mango$NO_CAPTURED_REVISION;
        this.mango$hasPreparedSectionSelection = false;
        this.mango$translucentSelectionStable = false;
        this.mango$captureBindingRevisionsStable = true;
        this.mango$translucentCacheHit = false;
        this.mango$terrainFrameCaptured = false;
        this.mango$visibleSlots = null;

        if (this.mango$captureTerrain
            && this.mango$registrySnapshot != null
            && TerrainRenderRouter.get().isOpaqueTerrainReady()) {
            mango$refreshVisibleContentsRevision();
            mango$refreshTranslucentSectionSelection();
        }

        if (this.mango$captureTerrain
            && this.mango$registrySnapshot != null
            && TerrainRenderRouter.get().isOpaqueTerrainReady()
            && mango$translucentCacheMatches()) {
            ChunkSectionsToRender cached = mango$reuseTranslucentPreparation(modelViewMatrix);
            if (!mango$cachedSectionBindingsMatch()) {
                this.mango$SECTION_RECORDS.clear();
                this.mango$SECTION_INDICES.clear();
                this.mango$hasPreparedSectionSelection = false;
                this.mango$translucentSelectionStable = false;
                return;
            }
            this.mango$translucentCacheHit = true;
            cir.setReturnValue(cached);
            this.mango$terrainFrameCaptured = true;
            mango$captureTerrainFrame(modelViewMatrix, cached);
            cir.cancel();
        }
    }

    @WrapOperation(
        method = "prepareChunkRenders(Lorg/joml/Matrix4fc;)Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;getSectionMesh()Lnet/minecraft/client/renderer/chunk/SectionMesh;"
        ),
        require = 1
    )
    private SectionMesh mango$captureCurrentSection(
        SectionRenderDispatcher.RenderSection section,
        Operation<SectionMesh> original
    ) {
        RenderSectionContentRevision revision = (RenderSectionContentRevision)(Object)section;
        long revisionBefore = revision.mango$getTranslucentBindingRevision();
        SectionMesh mesh = original.call(section);
        long revisionAfter = revision.mango$getTranslucentBindingRevision();
        if (revisionBefore != revisionAfter) {
            this.mango$captureBindingRevisionsStable = false;
        }
        this.mango$currentSection = section;
        this.mango$currentSectionBindingRevision = revisionAfter;
        return mesh;
    }

    @ModifyExpressionValue(
        method = "prepareChunkRenders(Lorg/joml/Matrix4fc;)Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;visibleSections:Lit/unimi/dsi/fastutil/objects/ObjectArrayList;"
        ),
        require = 1
    )
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> mango$selectPreparedSections(
        ObjectArrayList<SectionRenderDispatcher.RenderSection> original
    ) {
        return this.mango$hasPreparedSectionSelection ? this.mango$preparedSections : original;
    }

    @ModifyExpressionValue(
        method = "prepareChunkRenders(Lorg/joml/Matrix4fc;)Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;values()[Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;",
            ordinal = 1
        ),
        require = 1
    )
    private ChunkSectionLayer[] mango$selectPreparedLayers(ChunkSectionLayer[] original) {
        if (this.mango$registrySnapshot != null && TerrainRenderRouter.get().isOpaqueTerrainReady()) {
            return mango$TRANSLUCENT_ONLY;
        }
        return original;
    }

    @ModifyExpressionValue(
        method = "prepareChunkRenders(Lorg/joml/Matrix4fc;)Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/pipeline/RenderPipeline;getVertexFormatBinding(I)Lcom/mojang/blaze3d/vertex/VertexFormat;"
        ),
        require = 1
    )
    private VertexFormat mango$selectPreparedTerrainFormat(
        VertexFormat original,
        @Local ChunkSectionLayer layer
    ) {
        return layer != ChunkSectionLayer.TRANSLUCENT
            ? CompactTerrainVertex.FORMAT
            : original;
    }

    @WrapOperation(
        method = "prepareChunkRenders(Lorg/joml/Matrix4fc;)Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/List;add(Ljava/lang/Object;)Z"
        ),
        require = 2
    )
    private boolean mango$captureTerrainListAdd(
        List<Object> owner,
        Object value,
        Operation<Boolean> original
    ) {
        boolean added = original.call(owner, value);
        if (!added || !this.mango$captureTerrain) {
            return added;
        }

        if (value instanceof DynamicUniforms.ChunkSectionInfo sectionInfo) {
            this.mango$currentSectionIndex = mango$SECTION_RECORDS.size();
            this.mango$hasCurrentSection = true;
            mango$SECTION_RECORDS.add(sectionInfo);
            if (this.mango$currentSection != null) {
                this.mango$capturedSections.add(this.mango$currentSection);
                this.mango$capturedSectionBindingRevisions.add(this.mango$currentSectionBindingRevision);
            }
        } else if (value instanceof RenderPass.Draw<?> draw && this.mango$hasCurrentSection) {
            mango$SECTION_INDICES.put(draw, this.mango$currentSectionIndex);
        }
        return true;
    }

    @Inject(
        method = "prepareChunkRenders(Lorg/joml/Matrix4fc;)Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;",
        at = @At("RETURN"),
        require = 1
    )
    private void mango$finishTerrainCapture(
        Matrix4fc modelViewMatrix,
        CallbackInfoReturnable<ChunkSectionsToRender> cir
    ) {
        if (this.mango$terrainFrameCaptured) {
            return;
        }
        if (this.mango$captureTerrain
            && this.mango$registrySnapshot != null
            && TerrainRenderRouter.get().isOpaqueTerrainReady()
            && !this.mango$translucentCacheHit) {
            mango$rememberTranslucentPreparation(cir.getReturnValue());
        }
        mango$captureTerrainFrame(modelViewMatrix, cir.getReturnValue());
    }

    @Unique
    private boolean mango$translucentCacheMatches() {
        ChunkSectionsToRender cached = this.mango$cachedTranslucentPreparation;
        if (cached == null
            || this.sectionRenderDispatcher == null
            || this.mango$cachedDispatcher != this.sectionRenderDispatcher
            || this.mango$registrySnapshot == null
            || !this.mango$translucentSelectionStable
            || this.mango$cachedTranslucentVisibleContentsRevision
                != this.mango$translucentVisibleContentsRevision) {
            return false;
        }
        if (cached.textureView() != this.textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView()) {
            return false;
        }
        return mango$cachedSectionBindingsMatch();
    }

    @Unique
    private boolean mango$cachedSectionBindingsMatch() {
        int sectionCount = this.mango$cachedSections.size();
        if (sectionCount != this.mango$cachedSectionBindingRevisions.size()) {
            return false;
        }
        for (int index = 0; index < sectionCount; index++) {
            RenderSectionContentRevision revision =
                (RenderSectionContentRevision)(Object)this.mango$cachedSections.get(index);
            if (revision.mango$getTranslucentBindingRevision()
                != this.mango$cachedSectionBindingRevisions.getLong(index)) {
                return false;
            }
        }
        return true;
    }

    @Unique
    private ChunkSectionsToRender mango$reuseTranslucentPreparation(Matrix4fc modelViewMatrix) {
        long now = Util.getMillis();
        int recordCount = this.mango$cachedSections.size();
        // Geometry records can be reused, but fade visibility and the camera-relative matrix still change.
        this.mango$cachedSectionModelView.set(modelViewMatrix);
        if (this.mango$cachedSectionInfos.length != recordCount) {
            this.mango$cachedSectionInfos = new DynamicUniforms.ChunkSectionInfo[recordCount];
        }
        for (int index = 0; index < recordCount; index++) {
            SectionRenderDispatcher.RenderSection section = this.mango$cachedSections.get(index);
            BlockPos origin = section.getRenderOrigin();
            float visibility = section.getVisibility(now);
            DynamicUniforms.ChunkSectionInfo cachedInfo = this.mango$cachedSectionInfos[index];
            if (cachedInfo == null
                || cachedInfo.x() != origin.getX()
                || cachedInfo.y() != origin.getY()
                || cachedInfo.z() != origin.getZ()
                || cachedInfo.visibility() != visibility
                || cachedInfo.textureAtlasWidth() != this.mango$cachedAtlasWidth
                || cachedInfo.textureAtlasHeight() != this.mango$cachedAtlasHeight) {
                this.mango$cachedSectionInfos[index] = new DynamicUniforms.ChunkSectionInfo(
                    this.mango$cachedSectionModelView,
                    origin.getX(),
                    origin.getY(),
                    origin.getZ(),
                    visibility,
                    this.mango$cachedAtlasWidth,
                    this.mango$cachedAtlasHeight
                );
            }
            this.mango$SECTION_RECORDS.add(this.mango$cachedSectionInfos[index]);
        }
        this.mango$SECTION_INDICES.putAll(this.mango$cachedSectionIndices);
        GpuBufferSlice[] sectionInfos = RenderSystem.getDynamicUniforms()
            .writeChunkSections(this.mango$cachedSectionInfos);
        ChunkSectionsToRender cached = this.mango$cachedTranslucentPreparation;
        return new ChunkSectionsToRender(
            cached.textureView(),
            cached.drawGroupsPerLayer(),
            cached.maxIndicesRequired(),
            sectionInfos
        );
    }

    @Unique
    private void mango$rememberTranslucentPreparation(ChunkSectionsToRender prepared) {
        if (this.sectionRenderDispatcher == null
            || this.mango$registrySnapshot == null
            || !this.mango$translucentSelectionStable
            || !this.mango$captureBindingRevisionsStable
            || this.mango$capturedSections.size() != this.mango$SECTION_RECORDS.size()
            || this.mango$capturedSections.size() != this.mango$capturedSectionBindingRevisions.size()
            || !mango$capturedSectionBindingsMatch()) {
            mango$clearTranslucentCache();
            return;
        }
        this.mango$cachedSections.clear();
        this.mango$cachedSections.addAll(this.mango$capturedSections);
        this.mango$cachedSectionBindingRevisions.clear();
        this.mango$cachedSectionBindingRevisions.addAll(this.mango$capturedSectionBindingRevisions);
        this.mango$cachedSectionIndices.clear();
        this.mango$cachedSectionIndices.putAll(this.mango$SECTION_INDICES);
        this.mango$cachedAtlasWidth = prepared.textureView().getWidth(0);
        this.mango$cachedAtlasHeight = prepared.textureView().getHeight(0);
        this.mango$cachedDispatcher = this.sectionRenderDispatcher;
        this.mango$cachedTranslucentVisibleContentsRevision = this.mango$translucentVisibleContentsRevision;
        this.mango$cachedTranslucentPreparation = new ChunkSectionsToRender(
            prepared.textureView(),
            prepared.drawGroupsPerLayer(),
            prepared.maxIndicesRequired(),
            mango$NO_SECTION_INFOS
        );
    }

    @Unique
    private boolean mango$capturedSectionBindingsMatch() {
        for (int index = 0, size = this.mango$capturedSections.size(); index < size; index++) {
            RenderSectionContentRevision revision =
                (RenderSectionContentRevision)(Object)this.mango$capturedSections.get(index);
            if (revision.mango$getTranslucentBindingRevision()
                != this.mango$capturedSectionBindingRevisions.getLong(index)) {
                return false;
            }
        }
        return true;
    }

    @Unique
    private void mango$refreshVisibleContentsRevision() {
        TrackedVisibleSectionList trackedSections = (TrackedVisibleSectionList)this.visibleSections;
        trackedSections.settleChanges();
        this.mango$visibleContentsRevision = trackedSections.contentRevision();
    }

    @Unique
    private void mango$refreshTranslucentSectionSelection() {
        TrackedVisibleSectionList trackedSections = (TrackedVisibleSectionList)this.visibleSections;
        this.mango$preparedSections = trackedSections.translucentSections();
        this.mango$hasPreparedSectionSelection = true;
        this.mango$translucentSelectionStable = trackedSections.isTranslucentSelectionStable();
        this.mango$translucentVisibleContentsRevision = trackedSections.translucentContentRevision();
    }

    @Unique
    private void mango$clearTranslucentCache() {
        this.mango$cachedSections.clear();
        this.mango$cachedSectionBindingRevisions.clear();
        this.mango$cachedSectionIndices.clear();
        this.mango$cachedTranslucentPreparation = null;
        this.mango$cachedSectionInfos = new DynamicUniforms.ChunkSectionInfo[0];
        this.mango$cachedDispatcher = null;
        this.mango$cachedTranslucentVisibleContentsRevision = mango$NO_CAPTURED_REVISION;
    }

    @Unique
    private void mango$captureTerrainFrame(Matrix4fc modelViewMatrix, ChunkSectionsToRender prepared) {
        Vec3 cameraPos = this.levelRenderState.cameraRenderState.pos;
        BlockPos cameraBlock = this.levelRenderState.cameraRenderState.blockPos;
        MangoFrameState frameState = MangoFrameState.get();
        frameState.update(
            this.levelRenderState.cameraRenderState.projectionMatrix,
            modelViewMatrix,
            cameraBlock.getX(),
            cameraBlock.getY(),
            cameraBlock.getZ(),
            (float)(cameraBlock.getX() - cameraPos.x),
            (float)(cameraBlock.getY() - cameraPos.y),
            (float)(cameraBlock.getZ() - cameraPos.z)
        );
        Matrix4fc viewProjection = frameState.viewProjection();
        if (viewProjection == null) {
            return;
        }
        HiZCulling.get().beginFrame();
        if (this.mango$captureTerrain
            && this.mango$registrySnapshot != null
            && !HiZCulling.get().canCullTerrain()) {
            this.mango$visibleSlots = this.mango$visibleSlotsForFallback();
        }
        if (!((Object) prepared instanceof TerrainFrameHolder chunks)) {
            return;
        }
        if (!this.mango$captureTerrain) {
            chunks.mango$setTerrainFrame(TerrainFrame.empty());
            return;
        }

        chunks.mango$setTerrainFrame(
            new TerrainFrame(
                modelViewMatrix,
                viewProjection,
                cameraBlock.getX(),
                cameraBlock.getY(),
                cameraBlock.getZ(),
                (float)(cameraBlock.getX() - cameraPos.x),
                (float)(cameraBlock.getY() - cameraPos.y),
                (float)(cameraBlock.getZ() - cameraPos.z),
                mango$SECTION_RECORDS,
                mango$SECTION_INDICES,
                this.mango$registrySnapshot,
                this.mango$visibleSlots,
                this.mango$visibleSlots == null
                    ? TerrainFrame.NO_VISIBLE_SECTIONS_REVISION
                : this.mango$visibleContentsRevision
            )
        );
        this.mango$visibleSlots = null;
    }

    @Unique
    private IntSet mango$visibleSlotsForFallback() {
        mango$refreshVisibleContentsRevision();
        if (this.mango$visibleSlotsContentsRevision != this.mango$visibleContentsRevision) {
            this.mango$visibleSlotsBuffer.clear();
            this.mango$visibleSlotsBuffer.ensureCapacity(this.visibleSections.size());
            for (SectionRenderDispatcher.RenderSection section : this.visibleSections) {
                this.mango$visibleSlotsBuffer.add(section.index);
            }
            this.mango$visibleSlotsContentsRevision = this.mango$visibleContentsRevision;
        }
        return this.mango$visibleSlotsBuffer;
    }

    @Inject(
        method = "invalidateCompiledGeometry(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/client/Options;Lnet/minecraft/client/Camera;Lnet/minecraft/client/color/block/BlockColors;)V",
        at = @At("HEAD"),
        require = 1
    )
    private void mango$clearTerrainCacheForGeometryInvalidation(CallbackInfo ci) {
        mango$clearTranslucentCache();
        mango$resetVisibleSectionTracking();
    }

    @Inject(method = "resetLevelRenderData()V", at = @At("HEAD"), require = 1)
    private void mango$clearTerrainCacheForLevelReset(CallbackInfo ci) {
        mango$clearTranslucentCache();
        mango$resetVisibleSectionTracking();
        this.mango$registrySnapshot = null;
        this.mango$visibleSlots = null;
        this.mango$capturedSections.clear();
        this.mango$capturedSectionBindingRevisions.clear();
        this.mango$currentSection = null;
        this.mango$currentSectionBindingRevision = mango$NO_CAPTURED_REVISION;
        this.mango$hasPreparedSectionSelection = false;
        this.mango$translucentSelectionStable = false;
        this.mango$SECTION_RECORDS.clear();
        this.mango$SECTION_INDICES.clear();
    }

    @Unique
    private void mango$resetVisibleSectionTracking() {
        TrackedVisibleSectionList trackedSections = (TrackedVisibleSectionList)this.visibleSections;
        trackedSections.resetTracking();
        this.mango$visibleContentsRevision = trackedSections.contentRevision();
        this.mango$visibleSlotsContentsRevision = mango$NO_CAPTURED_REVISION;
        this.mango$visibleSlotsBuffer.clear();
        this.mango$visibleSlots = null;
    }

    @Inject(
        method = "compileSections(Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        at = @At("HEAD"),
        require = 1
    )
    private void mango$endTerrainFrame(CameraRenderState camera, CallbackInfo ci) {
        TerrainRenderRouter.get().endFrame();
        HiZCulling.get().endFrame();
    }
}
