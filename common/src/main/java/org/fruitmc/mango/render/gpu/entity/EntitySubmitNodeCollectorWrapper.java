package org.fruitmc.mango.render.gpu.entity;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Unit;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.fruitmc.mango.render.gpu.skinning.BoneCaptureContext;
import org.fruitmc.mango.render.gpu.skinning.BoneIndexMap;
import org.fruitmc.mango.render.gpu.skinning.BonePalette;
import org.fruitmc.mango.render.gpu.skinning.PoseSnapshot;
import org.fruitmc.mango.render.gpu.skinning.SkinnedMeshCapturingVertexConsumer;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.List;

public final class EntitySubmitNodeCollectorWrapper implements SubmitNodeCollector {

    private static final int MATRIX_FLOATS_PER_BONE = 16;
    private static final int DEFAULT_ORDER = 0;
    private static final int NO_OUTLINE = 0;
    private static final int INITIAL_ORDERED_COLLECTORS = 4;
    private static final int INITIAL_PENDING_SUBMISSIONS = 4;
    private static final int PENDING_GROWTH_FACTOR = 2;
    private static final long EMPTY_FINGERPRINT = 0L;
    private static final long EMPTY_PAGE_KEY = 0L;
    private static final Object EMPTY_SUBJECT = new Object();

    private final EntityInstanceBatcher batcher;
    private final MeshCapturingVertexConsumer meshConsumer = new MeshCapturingVertexConsumer();
    private final SkinnedMeshCapturingVertexConsumer skinnedMeshConsumer = new SkinnedMeshCapturingVertexConsumer();
    private final PoseStack identityPoseStack = new PoseStack();

    private float[] matrixBuffer = new float[0];
    private OrderedCollector[] orderedCollectors;
    private int orderedCollectorCount;
    private PendingSubmission[] pendingSubmissions;
    private int pendingSubmissionCount;
    private boolean requiresVanillaSubmission;
    private SubmitNodeCollector delegate;

    public EntitySubmitNodeCollectorWrapper(EntityInstanceBatcher batcher) {
        this.batcher = batcher;
        this.orderedCollectors = new OrderedCollector[INITIAL_ORDERED_COLLECTORS];
        for (int index = 0; index < this.orderedCollectors.length; index++) {
            this.orderedCollectors[index] = new OrderedCollector();
        }
        this.pendingSubmissions = new PendingSubmission[INITIAL_PENDING_SUBMISSIONS];
        for (int index = 0; index < this.pendingSubmissions.length; index++) {
            this.pendingSubmissions[index] = new PendingSubmission();
        }
    }

    public void prepare(SubmitNodeCollector delegate) {
        discardPendingSubmissions();
        this.delegate = delegate;
    }

    public void finishSubmission() {
        if (this.pendingSubmissionCount == 0) {
            discardPendingSubmissions();
            return;
        }

        if (this.requiresVanillaSubmission) {
            replayPendingSubmissions();
            discardPendingSubmissions();
            return;
        }

        int committedCount = 0;
        while (committedCount < this.pendingSubmissionCount) {
            PendingSubmission submission = this.pendingSubmissions[committedCount];
            CommitKind kind = submitPendingToMango(submission);
            if (kind == CommitKind.FAILED) {
                rollbackPendingSubmissions(committedCount);
                replayPendingSubmissions();
                discardPendingSubmissions();
                return;
            }
            submission.commitKind = kind;
            committedCount++;
        }

        EntityRenderDebugMetrics metrics = this.batcher.debugMetrics();
        for (int index = 0; index < this.pendingSubmissionCount; index++) {
            switch (this.pendingSubmissions[index].commitKind) {
                case STATIC_MODEL -> metrics.recordStaticSubmission();
                case SKINNED_MODEL -> metrics.recordSkinnedSubmission();
                case SKINNED_MODEL_PART -> metrics.recordSkinnedModelPartSubmission();
                case FAILED -> throw new IllegalStateException("Uncommitted Mango submission after successful group commit");
            }
        }
        discardPendingSubmissions();
    }

    @Override
    public <S> void submitModel(
        Model<? super S> model,
        S state,
        PoseStack poseStack,
        RenderType renderType,
        int lightCoords,
        int overlayCoords,
        int tintedColor,
        @Nullable TextureAtlasSprite sprite,
        int outlineColor,
        ModelFeatureRenderer.@Nullable CrumblingOverlay crumblingOverlay
    ) {
        submitModel(
            DEFAULT_ORDER,
            model,
            state,
            poseStack,
            renderType,
            lightCoords,
            overlayCoords,
            tintedColor,
            sprite,
            outlineColor,
            crumblingOverlay
        );
    }

    private <S> void submitModel(
        int order,
        Model<? super S> model,
        S state,
        PoseStack poseStack,
        RenderType renderType,
        int lightCoords,
        int overlayCoords,
        int tintedColor,
        @Nullable TextureAtlasSprite sprite,
        int outlineColor,
        ModelFeatureRenderer.@Nullable CrumblingOverlay crumblingOverlay
    ) {
        EntityRenderDebugMetrics metrics = this.batcher.debugMetrics();
        metrics.recordModelSubmission();
        RenderPipeline pipeline = renderType.pipeline();
        boolean compatible = InstancedEntityPipeline.isSupported(pipeline)
            && crumblingOverlay == null
            && outlineColor == NO_OUTLINE;
        if (!compatible) {
            recordCompatibilityFallback(metrics, pipeline, crumblingOverlay, outlineColor);
            this.requiresVanillaSubmission = true;
        }
        if (!shouldUseGpuSkinning() && state != Unit.INSTANCE) {
            metrics.recordFallback(EntityRenderDebugMetrics.FallbackReason.SKINNING_DISABLED);
            this.requiresVanillaSubmission = true;
        }
        nextPendingSubmission().prepareModel(
            model,
            state,
            poseStack,
            renderType,
            lightCoords,
            overlayCoords,
            tintedColor,
            sprite,
            outlineColor,
            crumblingOverlay,
            this.delegate,
            compatible,
            order
        );
    }

    @Override
    public void submitModelPart(
        ModelPart modelPart,
        PoseStack poseStack,
        RenderType renderType,
        int lightCoords,
        int overlayCoords,
        @Nullable TextureAtlasSprite sprite,
        int tintedColor,
        ModelFeatureRenderer.@Nullable CrumblingOverlay crumblingOverlay,
        int outlineColor
    ) {
        submitModelPart(
            DEFAULT_ORDER,
            modelPart,
            poseStack,
            renderType,
            lightCoords,
            overlayCoords,
            sprite,
            tintedColor,
            crumblingOverlay,
            outlineColor
        );
    }

    private void submitModelPart(
        int order,
        ModelPart modelPart,
        PoseStack poseStack,
        RenderType renderType,
        int lightCoords,
        int overlayCoords,
        @Nullable TextureAtlasSprite sprite,
        int tintedColor,
        ModelFeatureRenderer.@Nullable CrumblingOverlay crumblingOverlay,
        int outlineColor
    ) {
        EntityRenderDebugMetrics metrics = this.batcher.debugMetrics();
        metrics.recordModelPartSubmission();
        RenderPipeline pipeline = renderType.pipeline();
        boolean compatible = InstancedEntityPipeline.isSupported(pipeline)
            && crumblingOverlay == null
            && outlineColor == NO_OUTLINE;
        if (!compatible) {
            recordCompatibilityFallback(metrics, pipeline, crumblingOverlay, outlineColor);
            this.requiresVanillaSubmission = true;
        } else if (!shouldUseGpuSkinning()) {
            metrics.recordFallback(EntityRenderDebugMetrics.FallbackReason.SKINNING_DISABLED);
            this.requiresVanillaSubmission = true;
        }
        nextPendingSubmission().prepareModelPart(
            modelPart,
            poseStack,
            renderType,
            lightCoords,
            overlayCoords,
            sprite,
            tintedColor,
            crumblingOverlay,
            outlineColor,
            this.delegate,
            compatible,
            order
        );
    }

    private PendingSubmission nextPendingSubmission() {
        if (this.pendingSubmissionCount == this.pendingSubmissions.length) {
            int oldLength = this.pendingSubmissions.length;
            PendingSubmission[] grown = new PendingSubmission[oldLength * PENDING_GROWTH_FACTOR];
            System.arraycopy(this.pendingSubmissions, 0, grown, 0, oldLength);
            for (int index = oldLength; index < grown.length; index++) {
                grown[index] = new PendingSubmission();
            }
            this.pendingSubmissions = grown;
        }
        return this.pendingSubmissions[this.pendingSubmissionCount++];
    }

    private CommitKind submitPendingToMango(PendingSubmission submission) {
        if (submission.isModelPart) {
            ModelPart modelPart = (ModelPart)submission.subject;
            EntityInstanceBatch batch = this.batcher.batchFor(modelPart, submission.order, submission.renderType, submission.sprite);
            if (!trySubmitSkinnedModelPart(
                    batch,
                    modelPart,
                    submission.poseStack,
                    submission.sprite,
                    submission.lightCoords,
                    submission.overlayCoords,
                    submission.tintedColor
            )) {
                return CommitKind.FAILED;
            }
            submission.committedBatch = batch;
            return CommitKind.SKINNED_MODEL_PART;
        }
        return submitPendingModelToMango(submission);
    }

    private CommitKind submitPendingModelToMango(PendingSubmission submission) {
        @SuppressWarnings("unchecked")
        Model<Object> model = (Model<Object>)submission.subject;
        Object state = submission.state;
        EntityInstanceBatch batch = this.batcher.batchFor(model, submission.order, submission.renderType, submission.sprite);
        if (state == Unit.INSTANCE) {
            setupStaticModel(model, state);
            if (!ensureFallbackMesh(
                    batch,
                    model,
                    submission.sprite,
                    submission.lightCoords,
                    submission.overlayCoords,
                    submission.tintedColor
            )) {
                this.batcher.debugMetrics().recordFallback(EntityRenderDebugMetrics.FallbackReason.CAPTURE);
                return CommitKind.FAILED;
            }
            addFallbackInstance(
                batch,
                submission.poseStack,
                submission.lightCoords,
                submission.overlayCoords,
                submission.tintedColor
            );
            submission.committedBatch = batch;
            return CommitKind.STATIC_MODEL;
        }
        if (!trySubmitSkinnedModel(
                batch,
                model,
                state,
                submission.poseStack,
                submission.sprite,
                submission.lightCoords,
                submission.overlayCoords,
                submission.tintedColor
        )) {
            return CommitKind.FAILED;
        }
        submission.committedBatch = batch;
        return CommitKind.SKINNED_MODEL;
    }

    private void rollbackPendingSubmissions(int committedCount) {
        for (int index = committedCount - 1; index >= 0; index--) {
            this.pendingSubmissions[index].rollback();
        }
    }

    private void replayPendingSubmissions() {
        EntityRenderDebugMetrics metrics = this.batcher.debugMetrics();
        for (int index = 0; index < this.pendingSubmissionCount; index++) {
            PendingSubmission submission = this.pendingSubmissions[index];
            if (submission.compatible) {
                metrics.recordFallback(EntityRenderDebugMetrics.FallbackReason.GROUPED_SUBMISSION);
            }
            submission.submitVanilla();
        }
    }

    private void discardPendingSubmissions() {
        for (int index = 0; index < this.pendingSubmissionCount; index++) {
            this.pendingSubmissions[index].clear();
        }
        this.pendingSubmissionCount = 0;
        this.requiresVanillaSubmission = false;
        for (int index = 0; index < this.orderedCollectorCount; index++) {
            this.orderedCollectors[index].clear();
        }
        this.orderedCollectorCount = 0;
    }

    private boolean trySubmitSkinnedModelPart(
        EntityInstanceBatch batch,
        ModelPart modelPart,
        PoseStack poseStack,
        @Nullable TextureAtlasSprite sprite,
        int lightCoords,
        int overlayCoords,
        int tintedColor
    ) {
        if (batch.isSkinningOverflowed()) {
            this.batcher.debugMetrics().recordFallback(EntityRenderDebugMetrics.FallbackReason.SKINNING_OVERFLOW);
            return false;
        }

        BoneIndexMap boneIndexMap = BoneIndexMap.forRoot(modelPart);
        int boneCount = boneIndexMap.boneCount();
        if (boneCount == 0) {
            this.batcher.debugMetrics().recordFallback(EntityRenderDebugMetrics.FallbackReason.CAPTURE);
            return false;
        }
        if (!tryReserveSkinningSpace(batch, boneCount)) {
            return false;
        }

        if (batch.skinnedMesh() == null) {
            captureSkinnedMesh(modelPart, boneIndexMap, sprite, lightCoords, overlayCoords, tintedColor);
            if (this.skinnedMeshConsumer.vertexCount() > 0) {
                batch.setSkinnedMesh(new EntityMesh(
                    this.skinnedMeshConsumer.uploadSlice(),
                    this.skinnedMeshConsumer.vertexCount()
                ));
            }
        }
        if (batch.skinnedMesh() == null) {
            batch.markSkinningOverflowed();
            this.batcher.debugMetrics().recordFallback(EntityRenderDebugMetrics.FallbackReason.CAPTURE);
            return false;
        }

        BonePalette bonePalette = this.batcher.bonePalette();
        if (!bonePalette.canStoreTransientPage(boneCount)) {
            batch.markSkinningOverflowed();
            this.batcher.debugMetrics().recordFallback(EntityRenderDebugMetrics.FallbackReason.SKINNING_OVERFLOW);
            return false;
        }
        ensureMatrixBuffer(boneCount);
        boneIndexMap.writeMatrices(this.matrixBuffer);
        int bonePaletteOffset = bonePalette.addInstance(this.matrixBuffer, boneCount);
        addSkinnedInstance(
            batch, poseStack, lightCoords, overlayCoords, tintedColor, bonePaletteOffset
        );
        return true;
    }

    private static boolean shouldUseGpuSkinning() {
        return true;
    }

    private static <S> void setupStaticModel(Model<? super S> model, S state) {
        if (!(model instanceof Model.Simple)) {
            model.setupAnim(state);
        }
    }

    private <S> boolean trySubmitSkinnedModel(
        EntityInstanceBatch batch,
        Model<? super S> model, S state, PoseStack poseStack, @Nullable TextureAtlasSprite sprite,
        int lightCoords, int overlayCoords, int tintedColor
    ) {
        if (batch.isSkinningOverflowed()) {
            this.batcher.debugMetrics().recordFallback(EntityRenderDebugMetrics.FallbackReason.SKINNING_OVERFLOW);
            return false;
        }

        BoneIndexMap boneIndexMap = BoneIndexMap.forRoot(model.root());
        int boneCount = boneIndexMap.boneCount();

        if (boneCount == 0) {
            this.batcher.debugMetrics().recordFallback(EntityRenderDebugMetrics.FallbackReason.CAPTURE);
            return false;
        }
        if (!tryReserveSkinningSpace(batch, boneCount)) {
            return false;
        }

        if (batch.skinnedMesh() == null) {
            captureSkinnedMesh(model, boneIndexMap, sprite, lightCoords, overlayCoords, tintedColor);
            if (this.skinnedMeshConsumer.vertexCount() > 0) {
                batch.setSkinnedMesh(new EntityMesh(
                    this.skinnedMeshConsumer.uploadSlice(),
                    this.skinnedMeshConsumer.vertexCount()
                ));
            }
        }

        if (batch.skinnedMesh() == null) {
            batch.markSkinningOverflowed();
            this.batcher.debugMetrics().recordFallback(EntityRenderDebugMetrics.FallbackReason.CAPTURE);
            return false;
        }

        EntityAnimationPoseCache poseCache = this.batcher.poseCache();
        BonePalette bonePalette = this.batcher.bonePalette();
        boolean cacheSupported = poseCache.supports(model, state);
        boolean persistentCacheSupported = state instanceof EntityRenderState
            && state instanceof LivingEntityRenderState;
        long exactFingerprint = EMPTY_FINGERPRINT;
        long pageFingerprint = EMPTY_FINGERPRINT;
        long pageKey = EMPTY_PAGE_KEY;
        boolean hasPageKey = false;

        if (cacheSupported) {
            exactFingerprint = poseCache.exactFingerprint(model, state, boneIndexMap);
            int exactOffset = poseCache.findExactOffset(exactFingerprint);
            if (exactOffset != EntityAnimationPoseCache.NO_EXACT_OFFSET) {
                addSkinnedInstance(
                    batch, poseStack, lightCoords, overlayCoords, tintedColor, exactOffset
                );
                return true;
            }

            if (persistentCacheSupported) {
                EntityRenderState entityState = (EntityRenderState)state;
                hasPageKey = poseCache.canUsePersistentPage(entityState);
                pageKey = hasPageKey ? poseCache.persistentPageKey(model, entityState) : EMPTY_PAGE_KEY;
            }

            if (hasPageKey) {
                LivingEntityRenderState livingState = (LivingEntityRenderState)state;
                EntityRenderState entityState = (EntityRenderState)state;
                boolean useLod = poseCache.canUseLod(entityState);
                pageFingerprint = useLod
                    ? poseCache.lodFingerprint(model, livingState, boneIndexMap)
                    : exactFingerprint;
                int maxFrameAge = useLod ? poseCache.maxLodFrameAge() : poseCache.maxExactFrameAge();
                int reusableOffset = bonePalette.findReusablePageOffset(
                    pageKey,
                    pageFingerprint,
                    boneCount,
                    maxFrameAge
                );
                if (reusableOffset != BonePalette.NO_PAGE_OFFSET) {
                    poseCache.rememberExact(exactFingerprint, reusableOffset);
                    addSkinnedInstance(
                        batch, poseStack, lightCoords, overlayCoords, tintedColor, reusableOffset
                    );
                    return true;
                }
                if (!bonePalette.canStorePage(pageKey, boneCount)) {
                    batch.markSkinningOverflowed();
                    this.batcher.debugMetrics().recordFallback(EntityRenderDebugMetrics.FallbackReason.SKINNING_OVERFLOW);
                    return false;
                }
            }
        }

        if (!hasPageKey && !bonePalette.canStoreTransientPage(boneCount)) {
            batch.markSkinningOverflowed();
            this.batcher.debugMetrics().recordFallback(EntityRenderDebugMetrics.FallbackReason.SKINNING_OVERFLOW);
            return false;
        }

        model.setupAnim(state);
        ensureMatrixBuffer(boneCount);
        extractBoneMatrices(model, boneIndexMap);

        int bonePaletteOffset;
        if (hasPageKey) {
            bonePaletteOffset = bonePalette.putPersistentPage(pageKey, pageFingerprint, this.matrixBuffer, boneCount);
        } else {
            bonePaletteOffset = bonePalette.addInstance(this.matrixBuffer, boneCount);
        }
        if (cacheSupported) {
            poseCache.rememberExact(exactFingerprint, bonePaletteOffset);
        }

        addSkinnedInstance(
            batch, poseStack, lightCoords, overlayCoords, tintedColor, bonePaletteOffset
        );
        return true;
    }

    private void addSkinnedInstance(
        EntityInstanceBatch batch, PoseStack poseStack,
        int lightCoords, int overlayCoords, int tintedColor, int bonePaletteOffset
    ) {
        Matrix4f matrix = poseStack.last().pose();
        batch.addSkinnedInstance(
            matrix,
            this.batcher.instanceTranslationOffsetX(),
            this.batcher.instanceTranslationOffsetY(),
            this.batcher.instanceTranslationOffsetZ(),
            lightCoords,
            overlayCoords,
            tintedColor,
            bonePaletteOffset
        );
    }

    private boolean tryReserveSkinningSpace(EntityInstanceBatch batch, int boneCount) {
        BonePalette bonePalette = this.batcher.bonePalette();
        if (!bonePalette.hasRoom(boneCount)) {
            batch.markSkinningOverflowed();
            this.batcher.debugMetrics().recordFallback(EntityRenderDebugMetrics.FallbackReason.SKINNING_OVERFLOW);
            return false;
        }
        return true;
    }

    private static void recordCompatibilityFallback(
        EntityRenderDebugMetrics metrics,
        RenderPipeline pipeline,
        ModelFeatureRenderer.@Nullable CrumblingOverlay crumblingOverlay,
        int outlineColor
    ) {
        if (!InstancedEntityPipeline.isSupported(pipeline)) {
            metrics.recordFallback(EntityRenderDebugMetrics.FallbackReason.PIPELINE);
        } else if (crumblingOverlay != null) {
            metrics.recordFallback(EntityRenderDebugMetrics.FallbackReason.CRUMBLING);
        } else if (outlineColor != NO_OUTLINE) {
            metrics.recordFallback(EntityRenderDebugMetrics.FallbackReason.OUTLINE);
        }
    }

    private void addFallbackInstance(
        EntityInstanceBatch batch, PoseStack poseStack,
        int lightCoords, int overlayCoords, int tintedColor
    ) {
        Matrix4f matrix = poseStack.last().pose();
        batch.addNonSkinnedInstance(
            matrix,
            this.batcher.instanceTranslationOffsetX(),
            this.batcher.instanceTranslationOffsetY(),
            this.batcher.instanceTranslationOffsetZ(),
            lightCoords,
            overlayCoords,
            tintedColor
        );
    }

    private boolean ensureFallbackMesh(
        EntityInstanceBatch batch, Model<?> model, @Nullable TextureAtlasSprite sprite,
        int lightCoords, int overlayCoords, int tintedColor
    ) {
        if (batch.mesh() == null) {
            this.meshConsumer.clear();
            this.identityPoseStack.setIdentity();
            VertexConsumer target = sprite == null ? this.meshConsumer : sprite.wrap(this.meshConsumer);
            model.renderToBuffer(this.identityPoseStack, target, lightCoords, overlayCoords, tintedColor);
            this.meshConsumer.finish();
            if (this.meshConsumer.vertexCount() > 0) {
                batch.setMesh(new EntityMesh(
                    this.meshConsumer.uploadSlice(),
                    this.meshConsumer.vertexCount()
                ));
            }
        }
        return batch.mesh() != null;
    }

    private void captureSkinnedMesh(
        Model<?> model, BoneIndexMap boneIndexMap, @Nullable TextureAtlasSprite sprite,
        int lightCoords, int overlayCoords, int tintedColor
    ) {
        this.skinnedMeshConsumer.clear();

        PoseSnapshot snapshot = PoseSnapshot.capture(model.root());
        try {
            PoseSnapshot.resetToRest(model.root());

            BoneCaptureContext context = BoneCaptureContext.forMeshCapture(boneIndexMap, this.skinnedMeshConsumer);
            BoneCaptureContext.setCurrent(context);
            try {
                this.identityPoseStack.setIdentity();
                VertexConsumer target = sprite == null ? this.skinnedMeshConsumer : sprite.wrap(this.skinnedMeshConsumer);
                model.renderToBuffer(this.identityPoseStack, target, lightCoords, overlayCoords, tintedColor);
                this.skinnedMeshConsumer.finish();
            } finally {
                BoneCaptureContext.clear();
            }
        } finally {
            snapshot.restore(model.root());
        }
    }

    private void captureSkinnedMesh(
        ModelPart modelPart,
        BoneIndexMap boneIndexMap,
        @Nullable TextureAtlasSprite sprite,
        int lightCoords,
        int overlayCoords,
        int tintedColor
    ) {
        this.skinnedMeshConsumer.clear();

        PoseSnapshot snapshot = PoseSnapshot.capture(modelPart);
        try {
            PoseSnapshot.resetToRest(modelPart);
            BoneCaptureContext.setCurrent(BoneCaptureContext.forMeshCapture(boneIndexMap, this.skinnedMeshConsumer));
            try {
                this.identityPoseStack.setIdentity();
                VertexConsumer target = sprite == null ? this.skinnedMeshConsumer : sprite.wrap(this.skinnedMeshConsumer);
                modelPart.render(this.identityPoseStack, target, lightCoords, overlayCoords, tintedColor);
                this.skinnedMeshConsumer.finish();
            } finally {
                BoneCaptureContext.clear();
            }
        } finally {
            snapshot.restore(modelPart);
        }
    }

    private void extractBoneMatrices(Model<?> model, BoneIndexMap boneIndexMap) {
        boneIndexMap.writeMatrices(this.matrixBuffer);
    }

    private void ensureMatrixBuffer(int boneCount) {
        int required = boneCount * MATRIX_FLOATS_PER_BONE;
        if (this.matrixBuffer.length < required) {
            this.matrixBuffer = new float[required];
        }
    }

    @Override
    public OrderedSubmitNodeCollector order(int order) {
        if (this.orderedCollectorCount == this.orderedCollectors.length) {
            int oldLength = this.orderedCollectors.length;
            OrderedCollector[] grown = new OrderedCollector[oldLength * PENDING_GROWTH_FACTOR];
            System.arraycopy(this.orderedCollectors, 0, grown, 0, oldLength);
            for (int index = oldLength; index < grown.length; index++) {
                grown[index] = new OrderedCollector();
            }
            this.orderedCollectors = grown;
        }
        OrderedCollector collector = this.orderedCollectors[this.orderedCollectorCount++];
        collector.prepare(order);
        return collector;
    }

    @Override
    public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) {
        this.delegate.submitShadow(poseStack, radius, pieces);
    }

    @Override
    public void submitNameTag(
        PoseStack poseStack,
        @Nullable Vec3 nameTagAttachment,
        int offset,
        Component name,
        boolean seeThrough,
        int lightCoords,
        CameraRenderState camera
    ) {
        this.delegate.submitNameTag(poseStack, nameTagAttachment, offset, name, seeThrough, lightCoords, camera);
    }

    @Override
    public void submitText(
        PoseStack poseStack,
        float x,
        float y,
        FormattedCharSequence string,
        boolean dropShadow,
        Font.DisplayMode displayMode,
        int lightCoords,
        int color,
        int backgroundColor,
        int outlineColor
    ) {
        this.delegate.submitText(poseStack, x, y, string, dropShadow, displayMode, lightCoords, color, backgroundColor, outlineColor);
    }

    @Override
    public void submitFlame(PoseStack poseStack, EntityRenderState renderState, Quaternionf rotation) {
        this.delegate.submitFlame(poseStack, renderState, rotation);
    }

    @Override
    public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leashState) {
        this.delegate.submitLeash(poseStack, leashState);
    }

    @Override
    public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState movingBlockRenderState, int outlineColor) {
        this.delegate.submitMovingBlock(poseStack, movingBlockRenderState, outlineColor);
    }

    @Override
    public void submitBlockModel(
        PoseStack poseStack,
        RenderType renderType,
        List<BlockStateModelPart> parts,
        int[] tintLayers,
        int lightCoords,
        int overlayCoords,
        int outlineColor
    ) {
        this.delegate.submitBlockModel(poseStack, renderType, parts, tintLayers, lightCoords, overlayCoords, outlineColor);
    }

    @Override
    public void submitBreakingBlockModel(PoseStack poseStack, List<BlockStateModelPart> parts, int progress) {
        this.delegate.submitBreakingBlockModel(poseStack, parts, progress);
    }

    @Override
    public void submitShapeOutline(
        PoseStack poseStack, VoxelShape shape, RenderType renderType, int color, float width, boolean afterTerrain
    ) {
        this.delegate.submitShapeOutline(poseStack, shape, renderType, color, width, afterTerrain);
    }

    @Override
    public void submitItem(
        PoseStack poseStack,
        ItemDisplayContext displayContext,
        int lightCoords,
        int overlayCoords,
        int outlineColor,
        int[] tintLayers,
        List<BakedQuad> quads,
        ItemStackRenderState.FoilType foilType
    ) {
        if (this.batcher.itemBatcher().trySubmit(
                poseStack,
                displayContext,
                lightCoords,
                overlayCoords,
                outlineColor,
                tintLayers,
                quads,
                foilType
        )) {
            return;
        }
        this.delegate.submitItem(poseStack, displayContext, lightCoords, overlayCoords, outlineColor, tintLayers, quads, foilType);
    }

    @Override
    public void submitCustomGeometry(
        PoseStack poseStack, RenderType renderType, SubmitNodeCollector.CustomGeometryRenderer customGeometryRenderer
    ) {
        this.delegate.submitCustomGeometry(poseStack, renderType, customGeometryRenderer);
    }

    @Override
    public void submitQuadParticleGroup(QuadParticleRenderState particles) {
        this.delegate.submitQuadParticleGroup(particles);
    }

    @Override
    public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group group, CameraRenderState camera, boolean onTop) {
        this.delegate.submitGizmoPrimitives(group, camera, onTop);
    }


    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        return this.delegate.equals(obj);
    }

    @Override
    public int hashCode() {
        return this.delegate.hashCode();
    }

    @Override
    public String toString() {
        return this.delegate.toString();
    }

    private final class OrderedCollector implements OrderedSubmitNodeCollector {
        private int order;

        private void prepare(int order) {
            this.order = order;
        }

        private void clear() {
            this.order = DEFAULT_ORDER;
        }

        private OrderedSubmitNodeCollector delegate() {
            return EntitySubmitNodeCollectorWrapper.this.delegate.order(this.order);
        }

        @Override
        public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) {
            delegate().submitShadow(poseStack, radius, pieces);
        }

        @Override
        public void submitNameTag(
            PoseStack poseStack,
            @Nullable Vec3 nameTagAttachment,
            int offset,
            Component name,
            boolean seeThrough,
            int lightCoords,
            CameraRenderState camera
        ) {
            delegate().submitNameTag(poseStack, nameTagAttachment, offset, name, seeThrough, lightCoords, camera);
        }

        @Override
        public void submitText(
            PoseStack poseStack,
            float x,
            float y,
            FormattedCharSequence string,
            boolean dropShadow,
            Font.DisplayMode displayMode,
            int lightCoords,
            int color,
            int backgroundColor,
            int outlineColor
        ) {
            delegate().submitText(poseStack, x, y, string, dropShadow, displayMode, lightCoords, color, backgroundColor, outlineColor);
        }

        @Override
        public void submitFlame(PoseStack poseStack, EntityRenderState renderState, Quaternionf rotation) {
            delegate().submitFlame(poseStack, renderState, rotation);
        }

        @Override
        public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leashState) {
            delegate().submitLeash(poseStack, leashState);
        }

        @Override
        public <S> void submitModel(
            Model<? super S> model,
            S state,
            PoseStack poseStack,
            RenderType renderType,
            int lightCoords,
            int overlayCoords,
            int tintedColor,
            @Nullable TextureAtlasSprite sprite,
            int outlineColor,
            ModelFeatureRenderer.@Nullable CrumblingOverlay crumblingOverlay
        ) {
            EntitySubmitNodeCollectorWrapper.this.submitModel(
                this.order,
                model,
                state,
                poseStack,
                renderType,
                lightCoords,
                overlayCoords,
                tintedColor,
                sprite,
                outlineColor,
                crumblingOverlay
            );
        }

        @Override
        public void submitModelPart(
            ModelPart modelPart,
            PoseStack poseStack,
            RenderType renderType,
            int lightCoords,
            int overlayCoords,
            @Nullable TextureAtlasSprite sprite,
            int tintedColor,
            ModelFeatureRenderer.@Nullable CrumblingOverlay crumblingOverlay,
            int outlineColor
        ) {
            EntitySubmitNodeCollectorWrapper.this.submitModelPart(
                this.order,
                modelPart,
                poseStack,
                renderType,
                lightCoords,
                overlayCoords,
                sprite,
                tintedColor,
                crumblingOverlay,
                outlineColor
            );
        }

        @Override
        public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState movingBlockRenderState, int outlineColor) {
            delegate().submitMovingBlock(poseStack, movingBlockRenderState, outlineColor);
        }

        @Override
        public void submitBlockModel(
            PoseStack poseStack,
            RenderType renderType,
            List<BlockStateModelPart> parts,
            int[] tintLayers,
            int lightCoords,
            int overlayCoords,
            int outlineColor
        ) {
            delegate().submitBlockModel(poseStack, renderType, parts, tintLayers, lightCoords, overlayCoords, outlineColor);
        }

        @Override
        public void submitBreakingBlockModel(PoseStack poseStack, List<BlockStateModelPart> parts, int progress) {
            delegate().submitBreakingBlockModel(poseStack, parts, progress);
        }

        @Override
        public void submitShapeOutline(
            PoseStack poseStack, VoxelShape shape, RenderType renderType, int color, float width, boolean afterTerrain
        ) {
            delegate().submitShapeOutline(poseStack, shape, renderType, color, width, afterTerrain);
        }

        @Override
        public void submitItem(
            PoseStack poseStack,
            ItemDisplayContext displayContext,
            int lightCoords,
            int overlayCoords,
            int outlineColor,
            int[] tintLayers,
            List<BakedQuad> quads,
            ItemStackRenderState.FoilType foilType
        ) {
            delegate().submitItem(poseStack, displayContext, lightCoords, overlayCoords, outlineColor, tintLayers, quads, foilType);
        }

        @Override
        public void submitCustomGeometry(
            PoseStack poseStack, RenderType renderType, SubmitNodeCollector.CustomGeometryRenderer customGeometryRenderer
        ) {
            delegate().submitCustomGeometry(poseStack, renderType, customGeometryRenderer);
        }

        @Override
        public void submitQuadParticleGroup(QuadParticleRenderState particles) {
            delegate().submitQuadParticleGroup(particles);
        }

        @Override
        public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group group, CameraRenderState camera, boolean onTop) {
            delegate().submitGizmoPrimitives(group, camera, onTop);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            return delegate().equals(obj);
        }

        @Override
        public int hashCode() {
            return delegate().hashCode();
        }

        @Override
        public String toString() {
            return delegate().toString();
        }
    }

    private enum CommitKind {
        FAILED,
        STATIC_MODEL,
        SKINNED_MODEL,
        SKINNED_MODEL_PART
    }

    private static final class PendingSubmission {
        private final PoseStack poseStack = new PoseStack();
        private boolean isModelPart;
        private Object subject = EMPTY_SUBJECT;
        private Object state = Unit.INSTANCE;
        private RenderType renderType;
        private int lightCoords;
        private int overlayCoords;
        private int tintedColor;
        private @Nullable TextureAtlasSprite sprite;
        private int outlineColor;
        private ModelFeatureRenderer.@Nullable CrumblingOverlay crumblingOverlay;
        private SubmitNodeCollector fallbackCollector;
        private boolean compatible;
        private CommitKind commitKind = CommitKind.FAILED;
        private @Nullable EntityInstanceBatch committedBatch;
        private int order;

        private <S> void prepareModel(
            Model<? super S> model,
            S state,
            PoseStack poseStack,
            RenderType renderType,
            int lightCoords,
            int overlayCoords,
            int tintedColor,
            @Nullable TextureAtlasSprite sprite,
            int outlineColor,
            ModelFeatureRenderer.@Nullable CrumblingOverlay crumblingOverlay,
            SubmitNodeCollector fallbackCollector,
            boolean compatible,
            int order
        ) {
            this.isModelPart = false;
            this.subject = model;
            this.state = state;
            prepareCommon(
                poseStack,
                renderType,
                lightCoords,
                overlayCoords,
                tintedColor,
                sprite,
                outlineColor,
                crumblingOverlay,
                fallbackCollector,
                compatible,
                order
            );
        }

        private void prepareModelPart(
            ModelPart modelPart,
            PoseStack poseStack,
            RenderType renderType,
            int lightCoords,
            int overlayCoords,
            @Nullable TextureAtlasSprite sprite,
            int tintedColor,
            ModelFeatureRenderer.@Nullable CrumblingOverlay crumblingOverlay,
            int outlineColor,
            SubmitNodeCollector fallbackCollector,
            boolean compatible,
            int order
        ) {
            this.isModelPart = true;
            this.subject = modelPart;
            this.state = Unit.INSTANCE;
            prepareCommon(
                poseStack,
                renderType,
                lightCoords,
                overlayCoords,
                tintedColor,
                sprite,
                outlineColor,
                crumblingOverlay,
                fallbackCollector,
                compatible,
                order
            );
        }

        private void prepareCommon(
            PoseStack poseStack,
            RenderType renderType,
            int lightCoords,
            int overlayCoords,
            int tintedColor,
            @Nullable TextureAtlasSprite sprite,
            int outlineColor,
            ModelFeatureRenderer.@Nullable CrumblingOverlay crumblingOverlay,
            SubmitNodeCollector fallbackCollector,
            boolean compatible,
            int order
        ) {
            this.poseStack.last().set(poseStack.last());
            this.renderType = renderType;
            this.lightCoords = lightCoords;
            this.overlayCoords = overlayCoords;
            this.tintedColor = tintedColor;
            this.sprite = sprite;
            this.outlineColor = outlineColor;
            this.crumblingOverlay = crumblingOverlay;
            this.fallbackCollector = fallbackCollector;
            this.compatible = compatible;
            this.order = order;
            this.commitKind = CommitKind.FAILED;
            this.committedBatch = null;
        }

        private void submitVanilla() {
            OrderedSubmitNodeCollector collector = this.order == 0
                ? this.fallbackCollector
                : this.fallbackCollector.order(this.order);
            if (this.isModelPart) {
                collector.submitModelPart(
                    (ModelPart)this.subject,
                    this.poseStack,
                    this.renderType,
                    this.lightCoords,
                    this.overlayCoords,
                    this.sprite,
                    this.tintedColor,
                    this.crumblingOverlay,
                    this.outlineColor
                );
                return;
            }
            submitVanillaModel(collector);
        }

        private void submitVanillaModel(OrderedSubmitNodeCollector collector) {
            @SuppressWarnings("unchecked")
            Model<Object> model = (Model<Object>)this.subject;
            collector.submitModel(
                model,
                this.state,
                this.poseStack,
                this.renderType,
                this.lightCoords,
                this.overlayCoords,
                this.tintedColor,
                this.sprite,
                this.outlineColor,
                this.crumblingOverlay
            );
        }

        private void rollback() {
            EntityInstanceBatch batch = this.committedBatch;
            if (batch == null) {
                throw new IllegalStateException("Missing batch for committed Mango submission rollback");
            }
            switch (this.commitKind) {
                case STATIC_MODEL -> batch.removeLastNonSkinnedInstance();
                case SKINNED_MODEL, SKINNED_MODEL_PART -> batch.removeLastSkinnedInstance();
                case FAILED -> throw new IllegalStateException("Cannot roll back an uncommitted Mango submission");
            }
            this.commitKind = CommitKind.FAILED;
            this.committedBatch = null;
        }

        private void clear() {
            this.subject = EMPTY_SUBJECT;
            this.state = Unit.INSTANCE;
            this.sprite = null;
            this.crumblingOverlay = null;
            this.fallbackCollector = null;
            this.compatible = false;
            this.commitKind = CommitKind.FAILED;
            this.committedBatch = null;
            this.order = 0;
        }
    }
}
