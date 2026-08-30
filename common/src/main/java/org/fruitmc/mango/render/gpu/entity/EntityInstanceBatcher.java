package org.fruitmc.mango.render.gpu.entity;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.fruitmc.mango.render.gpu.item.ItemInstanceBatcher;
import org.fruitmc.mango.render.gpu.skinning.BonePalette;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;

public final class EntityInstanceBatcher implements AutoCloseable {

    private static final int DEFAULT_ORDER = 0;

    private final IdentityHashMap<Model<?>, IdentityHashMap<RenderType, Int2ObjectMap<EntityInstanceBatch>>> batchesByModel =
        new IdentityHashMap<>();
    private final IdentityHashMap<
        Model<?>,
        IdentityHashMap<RenderType, IdentityHashMap<TextureAtlasSprite, Int2ObjectMap<EntityInstanceBatch>>>
    > batchesByModelSprite = new IdentityHashMap<>();
    private final IdentityHashMap<ModelPart, IdentityHashMap<RenderType, Int2ObjectMap<EntityInstanceBatch>>> batchesByPart =
        new IdentityHashMap<>();
    private final IdentityHashMap<
        ModelPart,
        IdentityHashMap<RenderType, IdentityHashMap<TextureAtlasSprite, Int2ObjectMap<EntityInstanceBatch>>>
    > batchesByPartSprite = new IdentityHashMap<>();
    private final List<EntityInstanceBatch> allBatches = new ArrayList<>();
    private final List<EntityInstanceBatch> activeBatches = new ArrayList<>();
    private final List<EntityInstanceBatch> batchView = Collections.unmodifiableList(this.activeBatches);
    private final BonePalette bonePalette = new BonePalette();
    private final EntityAnimationPoseCache poseCache = new EntityAnimationPoseCache();
    private final ItemInstanceBatcher itemBatcher = new ItemInstanceBatcher();
    private final EntityRenderDebugMetrics debugMetrics = new EntityRenderDebugMetrics();
    private double instanceTranslationOffsetX;
    private double instanceTranslationOffsetY;
    private double instanceTranslationOffsetZ;
    private int cameraBlockX;
    private int cameraBlockY;
    private int cameraBlockZ;
    private long cameraAnchorRevision;
    private boolean hasCameraAnchor;

    public EntityInstanceBatcher() {
    }

    public EntityInstanceBatch batchFor(Model<?> model, RenderType renderType) {
        return batchForModel(model, DEFAULT_ORDER, renderType);
    }

    private EntityInstanceBatch batchForModel(Model<?> model, int order, RenderType renderType) {
        IdentityHashMap<RenderType, Int2ObjectMap<EntityInstanceBatch>> modelBatches = this.batchesByModel.get(model);
        if (modelBatches == null) {
            modelBatches = new IdentityHashMap<>();
            this.batchesByModel.put(model, modelBatches);
        }

        Int2ObjectMap<EntityInstanceBatch> orderedBatches = modelBatches.get(renderType);
        if (orderedBatches == null) {
            orderedBatches = new Int2ObjectOpenHashMap<>();
            modelBatches.put(renderType, orderedBatches);
        }
        EntityInstanceBatch batch = orderedBatches.get(order);
        if (batch == null) {
            batch = new EntityInstanceBatch(renderType, order);
            orderedBatches.put(order, batch);
            this.allBatches.add(batch);
        }
        return activate(batch);
    }

    public EntityInstanceBatch batchFor(Model<?> model, RenderType renderType, TextureAtlasSprite sprite) {
        return batchFor(model, DEFAULT_ORDER, renderType, sprite);
    }

    EntityInstanceBatch batchFor(Model<?> model, int order, RenderType renderType, TextureAtlasSprite sprite) {
        if (sprite == null) {
            return batchForModel(model, order, renderType);
        }
        IdentityHashMap<RenderType, IdentityHashMap<TextureAtlasSprite, Int2ObjectMap<EntityInstanceBatch>>> byRenderType =
            this.batchesByModelSprite.get(model);
        if (byRenderType == null) {
            byRenderType = new IdentityHashMap<>();
            this.batchesByModelSprite.put(model, byRenderType);
        }

        IdentityHashMap<TextureAtlasSprite, Int2ObjectMap<EntityInstanceBatch>> bySprite = byRenderType.get(renderType);
        if (bySprite == null) {
            bySprite = new IdentityHashMap<>();
            byRenderType.put(renderType, bySprite);
        }

        Int2ObjectMap<EntityInstanceBatch> orderedBatches = bySprite.get(sprite);
        if (orderedBatches == null) {
            orderedBatches = new Int2ObjectOpenHashMap<>();
            bySprite.put(sprite, orderedBatches);
        }
        EntityInstanceBatch batch = orderedBatches.get(order);
        if (batch == null) {
            batch = new EntityInstanceBatch(renderType, order);
            orderedBatches.put(order, batch);
            this.allBatches.add(batch);
        }
        return activate(batch);
    }

    EntityInstanceBatch batchFor(ModelPart modelPart, int order, RenderType renderType, TextureAtlasSprite sprite) {
        if (sprite == null) {
            IdentityHashMap<RenderType, Int2ObjectMap<EntityInstanceBatch>> partBatches = this.batchesByPart.get(modelPart);
            if (partBatches == null) {
                partBatches = new IdentityHashMap<>();
                this.batchesByPart.put(modelPart, partBatches);
            }
            Int2ObjectMap<EntityInstanceBatch> orderedBatches = partBatches.get(renderType);
            if (orderedBatches == null) {
                orderedBatches = new Int2ObjectOpenHashMap<>();
                partBatches.put(renderType, orderedBatches);
            }
            EntityInstanceBatch batch = orderedBatches.get(order);
            if (batch == null) {
                batch = new EntityInstanceBatch(renderType, order);
                orderedBatches.put(order, batch);
                this.allBatches.add(batch);
            }
            return activate(batch);
        }

        IdentityHashMap<RenderType, IdentityHashMap<TextureAtlasSprite, Int2ObjectMap<EntityInstanceBatch>>> byRenderType =
            this.batchesByPartSprite.get(modelPart);
        if (byRenderType == null) {
            byRenderType = new IdentityHashMap<>();
            this.batchesByPartSprite.put(modelPart, byRenderType);
        }
        IdentityHashMap<TextureAtlasSprite, Int2ObjectMap<EntityInstanceBatch>> bySprite = byRenderType.get(renderType);
        if (bySprite == null) {
            bySprite = new IdentityHashMap<>();
            byRenderType.put(renderType, bySprite);
        }
        Int2ObjectMap<EntityInstanceBatch> orderedBatches = bySprite.get(sprite);
        if (orderedBatches == null) {
            orderedBatches = new Int2ObjectOpenHashMap<>();
            bySprite.put(sprite, orderedBatches);
        }
        EntityInstanceBatch batch = orderedBatches.get(order);
        if (batch == null) {
            batch = new EntityInstanceBatch(renderType, order);
            orderedBatches.put(order, batch);
            this.allBatches.add(batch);
        }
        return activate(batch);
    }

    EntityInstanceBatch batchFor(ModelPart modelPart, RenderType renderType, TextureAtlasSprite sprite) {
        return batchFor(modelPart, DEFAULT_ORDER, renderType, sprite);
    }

    private EntityInstanceBatch activate(EntityInstanceBatch batch) {
        if (batch.tryMarkActive()) {
            this.activeBatches.add(batch);
        }
        return batch;
    }

    public BonePalette bonePalette() {
        return this.bonePalette;
    }

    public EntityAnimationPoseCache poseCache() {
        return this.poseCache;
    }

    public ItemInstanceBatcher itemBatcher() {
        return this.itemBatcher;
    }

    EntityRenderDebugMetrics debugMetrics() {
        return this.debugMetrics;
    }

    public void beginFrame(Vec3 cameraPosition) {
        int cameraBlockX = Mth.floor(cameraPosition.x);
        int cameraBlockY = Mth.floor(cameraPosition.y);
        int cameraBlockZ = Mth.floor(cameraPosition.z);
        if (!this.hasCameraAnchor
            || this.cameraBlockX != cameraBlockX
            || this.cameraBlockY != cameraBlockY
            || this.cameraBlockZ != cameraBlockZ) {
            this.cameraAnchorRevision = Math.incrementExact(this.cameraAnchorRevision);
            this.hasCameraAnchor = true;
        }
        this.cameraBlockX = cameraBlockX;
        this.cameraBlockY = cameraBlockY;
        this.cameraBlockZ = cameraBlockZ;
        this.instanceTranslationOffsetX = cameraPosition.x - this.cameraBlockX;
        this.instanceTranslationOffsetY = cameraPosition.y - this.cameraBlockY;
        this.instanceTranslationOffsetZ = cameraPosition.z - this.cameraBlockZ;
        this.debugMetrics.beginFrame();
        for (EntityInstanceBatch batch : this.activeBatches) {
            batch.beginFrame();
        }
        this.activeBatches.clear();
        this.poseCache.beginFrame();
        this.bonePalette.beginFrame();
        this.itemBatcher.beginFrame(
            this.instanceTranslationOffsetX,
            this.instanceTranslationOffsetY,
            this.instanceTranslationOffsetZ
        );
    }

    double instanceTranslationOffsetX() {
        return this.instanceTranslationOffsetX;
    }

    double instanceTranslationOffsetY() {
        return this.instanceTranslationOffsetY;
    }

    double instanceTranslationOffsetZ() {
        return this.instanceTranslationOffsetZ;
    }

    int cameraBlockX() {
        return this.cameraBlockX;
    }

    int cameraBlockY() {
        return this.cameraBlockY;
    }

    int cameraBlockZ() {
        return this.cameraBlockZ;
    }

    long cameraAnchorRevision() {
        return this.cameraAnchorRevision;
    }

    public void clearPersistentResources() {
        for (EntityInstanceBatch batch : this.allBatches) {
            batch.close();
        }
        this.allBatches.clear();
        this.activeBatches.clear();
        this.batchesByModel.clear();
        this.batchesByModelSprite.clear();
        this.batchesByPart.clear();
        this.batchesByPartSprite.clear();
        this.poseCache.clear();
        this.bonePalette.clear();
        this.itemBatcher.clearPersistentResources();
        this.hasCameraAnchor = false;
        this.cameraAnchorRevision = Math.incrementExact(this.cameraAnchorRevision);
    }

    @Override
    public void close() {
        for (EntityInstanceBatch batch : this.allBatches) {
            batch.close();
        }
        this.allBatches.clear();
        this.activeBatches.clear();
        this.batchesByModel.clear();
        this.batchesByModelSprite.clear();
        this.batchesByPart.clear();
        this.batchesByPartSprite.clear();
        this.poseCache.clear();
        this.bonePalette.close();
        this.itemBatcher.close();
    }

    public boolean hasBatches() {
        for (EntityInstanceBatch batch : this.activeBatches) {
            if (batch.shouldRenderNonSkinned()) {
                return true;
            }
            if (batch.shouldRenderSkinned()) {
                return true;
            }
        }
        return this.itemBatcher.hasBatches();
    }

    public boolean hasSkinnedBatches() {
        for (EntityInstanceBatch batch : this.activeBatches) {
            if (batch.shouldRenderSkinned()) {
                return true;
            }
        }
        return false;
    }

    public Iterable<EntityInstanceBatch> batches() {
        return this.batchView;
    }
}
