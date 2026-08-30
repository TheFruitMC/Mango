package org.fruitmc.mango.render.gpu.item;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.world.item.ItemDisplayContext;
import org.fruitmc.mango.render.gpu.entity.EntityMesh;
import org.fruitmc.mango.render.gpu.entity.MeshCapturingVertexConsumer;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ItemInstanceBatcher implements AutoCloseable {

    private static final int NO_OUTLINE = 0;
    private static final int NO_TINT = -1;
    private static final int NO_MATERIAL_FLAGS = 0;
    private static final long FINGERPRINT_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FINGERPRINT_PRIME = 0x100000001b3L;

    private final Long2ObjectOpenHashMap<List<ItemInstanceBatch>> batchesByFingerprint = new Long2ObjectOpenHashMap<>();
    private final List<ItemInstanceBatch> allBatches = new ArrayList<>();
    private final List<ItemInstanceBatch> activeBatches = new ArrayList<>();
    private final List<ItemInstanceBatch> batchView = Collections.unmodifiableList(this.activeBatches);
    private final MeshCapturingVertexConsumer meshConsumer = new MeshCapturingVertexConsumer();
    private final PoseStack identityPoseStack = new PoseStack();
    private final QuadInstance quadInstance = new QuadInstance();
    private double instanceTranslationOffsetX;
    private double instanceTranslationOffsetY;
    private double instanceTranslationOffsetZ;

    public ItemInstanceBatcher() {
    }

    public boolean trySubmit(
        PoseStack poseStack,
        ItemDisplayContext displayContext,
        int lightCoords,
        int overlayCoords,
        int outlineColor,
        int[] tintLayers,
        List<BakedQuad> quads,
        ItemStackRenderState.FoilType foilType
    ) {
        if (!isCompatible(displayContext, outlineColor, tintLayers, quads, foilType)) {
            return false;
        }

        RenderType renderType = quads.getFirst().materialInfo().itemRenderType();
        ItemInstanceBatch batch = batchFor(renderType, quads);
        Matrix4f matrix = poseStack.last().pose();
        batch.collector().addInstance(
            matrix,
            this.instanceTranslationOffsetX,
            this.instanceTranslationOffsetY,
            this.instanceTranslationOffsetZ,
            lightCoords,
            overlayCoords,
            NO_TINT
        );
        ensureMesh(batch);
        return batch.mesh() != null;
    }

    public void beginFrame(
        double translationOffsetX,
        double translationOffsetY,
        double translationOffsetZ
    ) {
        this.instanceTranslationOffsetX = translationOffsetX;
        this.instanceTranslationOffsetY = translationOffsetY;
        this.instanceTranslationOffsetZ = translationOffsetZ;
        for (ItemInstanceBatch batch : this.activeBatches) {
            batch.beginFrame();
        }
        this.activeBatches.clear();
    }

    public void clearPersistentResources() {
        for (ItemInstanceBatch batch : this.allBatches) {
            batch.close();
        }
        this.allBatches.clear();
        this.activeBatches.clear();
        this.batchesByFingerprint.clear();
    }

    @Override
    public void close() {
        this.clearPersistentResources();
        this.meshConsumer.close();
    }

    public boolean hasBatches() {
        for (ItemInstanceBatch batch : this.activeBatches) {
            if (batch.shouldRender()) {
                return true;
            }
        }
        return false;
    }

    public Iterable<ItemInstanceBatch> batches() {
        return this.batchView;
    }

    private ItemInstanceBatch batchFor(RenderType renderType, List<BakedQuad> quads) {
        long fingerprint = fingerprint(renderType, quads);
        List<ItemInstanceBatch> candidates;
        if (this.batchesByFingerprint.containsKey(fingerprint)) {
            candidates = this.batchesByFingerprint.get(fingerprint);
            for (ItemInstanceBatch batch : candidates) {
                if (batch.matches(renderType, quads)) {
                    return activate(batch);
                }
            }
        } else {
            candidates = new ArrayList<>();
            this.batchesByFingerprint.put(fingerprint, candidates);
        }

        ItemInstanceBatch batch = new ItemInstanceBatch(renderType, quads);
        candidates.add(batch);
        this.allBatches.add(batch);
        return activate(batch);
    }

    private ItemInstanceBatch activate(ItemInstanceBatch batch) {
        if (batch.tryMarkActive()) {
            this.activeBatches.add(batch);
        }
        return batch;
    }

    private void ensureMesh(ItemInstanceBatch batch) {
        if (batch.mesh() != null) {
            return;
        }

        this.meshConsumer.clear();
        this.identityPoseStack.setIdentity();
        this.quadInstance.setColor(NO_TINT);
        for (BakedQuad quad : batch.quads()) {
            this.meshConsumer.putBakedQuad(this.identityPoseStack.last(), quad, this.quadInstance);
        }
        this.meshConsumer.finish();
        if (this.meshConsumer.vertexCount() > 0) {
            batch.setMesh(new EntityMesh(
                this.meshConsumer.uploadSlice(),
                this.meshConsumer.vertexCount()
            ));
        }
    }

    private static boolean isCompatible(
        ItemDisplayContext displayContext,
        int outlineColor,
        int[] tintLayers,
        List<BakedQuad> quads,
        ItemStackRenderState.FoilType foilType
    ) {
        if (displayContext != ItemDisplayContext.GROUND
                || outlineColor != NO_OUTLINE
                || foilType != ItemStackRenderState.FoilType.NONE
                || tintLayers.length != 0
                || quads.isEmpty()) {
            return false;
        }

        RenderType renderType = quads.getFirst().materialInfo().itemRenderType();
        if (renderType.hasBlending() || !InstancedItemPipeline.isSupported(renderType.pipeline())) {
            return false;
        }

        for (BakedQuad quad : quads) {
            BakedQuad.MaterialInfo material = quad.materialInfo();
            if (material.isTinted()
                    || material.itemRenderType() != renderType
                    || material.flags() != NO_MATERIAL_FLAGS) {
                return false;
            }
        }
        return true;
    }

    private static long fingerprint(RenderType renderType, List<BakedQuad> quads) {
        long fingerprint = mix(FINGERPRINT_OFFSET_BASIS, System.identityHashCode(renderType));
        fingerprint = mix(fingerprint, quads.size());
        for (BakedQuad quad : quads) {
            fingerprint = mix(fingerprint, System.identityHashCode(quad));
        }
        return fingerprint;
    }

    private static long mix(long fingerprint, int value) {
        return (fingerprint ^ value) * FINGERPRINT_PRIME;
    }
}
