package org.fruitmc.mango.render.gpu.entity;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.fruitmc.mango.render.gpu.skinning.SkinnedEntityInstanceCollector;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

public final class EntityInstanceBatch implements AutoCloseable {

    private final RenderType renderType;
    private final int order;
    private final EntityInstanceCollector collector;
    private final SkinnedEntityInstanceCollector skinnedCollector;
    private final PersistentInstanceSnapshot persistentSnapshot =
        new PersistentInstanceSnapshot(() -> "Mango entity instance persistent");
    private final PersistentInstanceSnapshot persistentSkinnedSnapshot =
        new PersistentInstanceSnapshot(() -> "Mango skinned entity instance persistent");
    private @Nullable EntityMesh mesh;
    private @Nullable EntityMesh skinnedMesh;
    private @Nullable GpuBufferSlice framePersistentSnapshot;
    private @Nullable GpuBufferSlice framePersistentSkinnedSnapshot;
    private boolean framePersistentPrepared;
    private boolean framePersistentSkinnedPrepared;
    private boolean skinningOverflowed;
    private boolean active;

    EntityInstanceBatch(RenderType renderType, int order) {
        this.renderType = renderType;
        this.order = order;
        this.collector = new EntityInstanceCollector();
        this.skinnedCollector = new SkinnedEntityInstanceCollector();
    }

    public RenderType renderType() {
        return this.renderType;
    }

    public int order() {
        return this.order;
    }

    public EntityInstanceCollector collector() {
        return this.collector;
    }

    public SkinnedEntityInstanceCollector skinnedCollector() {
        return this.skinnedCollector;
    }

    @Nullable
    GpuBufferSlice preparePersistentNonSkinned(EntityInstanceBatcher batcher) {
        if (!this.framePersistentPrepared) {
            this.framePersistentSnapshot = this.persistentSnapshot.prepare(
                this.collector.backingBuffer(),
                this.collector.instanceDataBytes(),
                this.collector.isFrameUnchanged(),
                batcher.cameraBlockX(),
                batcher.cameraBlockY(),
                batcher.cameraBlockZ(),
                batcher.cameraAnchorRevision()
            );
            this.framePersistentPrepared = true;
        }
        return this.framePersistentSnapshot;
    }

    @Nullable
    GpuBufferSlice preparePersistentSkinned(EntityInstanceBatcher batcher) {
        if (!this.framePersistentSkinnedPrepared) {
            this.framePersistentSkinnedSnapshot = this.persistentSkinnedSnapshot.prepare(
                this.skinnedCollector.backingBuffer(),
                this.skinnedCollector.instanceDataBytes(),
                this.skinnedCollector.isFrameUnchanged(),
                batcher.cameraBlockX(),
                batcher.cameraBlockY(),
                batcher.cameraBlockZ(),
                batcher.cameraAnchorRevision()
            );
            this.framePersistentSkinnedPrepared = true;
        }
        return this.framePersistentSkinnedSnapshot;
    }

    public @Nullable EntityMesh mesh() {
        return this.mesh;
    }

    void setMesh(EntityMesh mesh) {
        if (this.mesh != null) {
            this.mesh.close();
        }
        this.mesh = mesh;
    }

    public @Nullable EntityMesh skinnedMesh() {
        return this.skinnedMesh;
    }

    void setSkinnedMesh(EntityMesh mesh) {
        if (this.skinnedMesh != null) {
            this.skinnedMesh.close();
        }
        this.skinnedMesh = mesh;
    }

    void markSkinningOverflowed() {
        this.skinningOverflowed = true;
    }

    void removeLastNonSkinnedInstance() {
        this.collector.removeLastInstance();
    }

    void removeLastSkinnedInstance() {
        this.skinnedCollector.removeLastInstance();
    }

    void addNonSkinnedInstance(
        Matrix4f modelMatrix,
        double translationOffsetX,
        double translationOffsetY,
        double translationOffsetZ,
        int light,
        int overlay,
        int tintColor
    ) {
        this.collector.addInstance(
            modelMatrix,
            translationOffsetX,
            translationOffsetY,
            translationOffsetZ,
            light,
            overlay,
            tintColor
        );
    }

    void addSkinnedInstance(
        Matrix4f modelMatrix,
        double translationOffsetX,
        double translationOffsetY,
        double translationOffsetZ,
        int light,
        int overlay,
        int tintColor,
        int bonePaletteOffset
    ) {
        this.skinnedCollector.addInstance(
            modelMatrix,
            translationOffsetX,
            translationOffsetY,
            translationOffsetZ,
            light,
            overlay,
            tintColor,
            bonePaletteOffset
        );
    }

    public boolean isSkinningOverflowed() {
        return this.skinningOverflowed;
    }

    public boolean shouldRenderNonSkinned() {
        return this.collector.instanceCount() > 0 && this.mesh != null;
    }

    public boolean shouldRenderSkinned() {
        return this.skinnedCollector.instanceCount() > 0 && this.skinnedMesh != null;
    }

    boolean tryMarkActive() {
        if (this.active) {
            return false;
        }
        this.active = true;
        return true;
    }

    void beginFrame() {
        this.collector.beginFrame();
        this.skinnedCollector.beginFrame();
        this.framePersistentSnapshot = null;
        this.framePersistentSkinnedSnapshot = null;
        this.framePersistentPrepared = false;
        this.framePersistentSkinnedPrepared = false;
        this.skinningOverflowed = false;
        this.active = false;
    }

    void closeMeshes() {
        if (this.mesh != null) {
            this.mesh.close();
            this.mesh = null;
        }
        if (this.skinnedMesh != null) {
            this.skinnedMesh.close();
            this.skinnedMesh = null;
        }
    }

    @Override
    public void close() {
        this.closeMeshes();
        this.collector.close();
        this.skinnedCollector.close();
        this.persistentSnapshot.close();
        this.persistentSkinnedSnapshot.close();
    }
}
