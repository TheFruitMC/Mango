package org.fruitmc.mango.render.gpu.item;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.fruitmc.mango.render.gpu.entity.EntityInstanceCollector;
import org.fruitmc.mango.render.gpu.entity.EntityMesh;
import org.fruitmc.mango.render.gpu.entity.PersistentInstanceSnapshot;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class ItemInstanceBatch implements AutoCloseable {

    private final RenderType renderType;
    private final List<BakedQuad> quads;
    private final EntityInstanceCollector collector = new EntityInstanceCollector();
    private final PersistentInstanceSnapshot persistentSnapshot =
        new PersistentInstanceSnapshot(() -> "Mango item instance persistent");
    @Nullable private EntityMesh mesh;
    @Nullable private GpuBufferSlice framePersistentSnapshot;
    private boolean framePersistentPrepared;
    private boolean active;

    ItemInstanceBatch(RenderType renderType, List<BakedQuad> quads) {
        this.renderType = renderType;
        this.quads = List.copyOf(quads);
    }

    public boolean matches(RenderType candidateRenderType, List<BakedQuad> candidateQuads) {
        if (this.renderType != candidateRenderType || this.quads.size() != candidateQuads.size()) {
            return false;
        }
        for (int index = 0; index < this.quads.size(); index++) {
            if (this.quads.get(index) != candidateQuads.get(index)) {
                return false;
            }
        }
        return true;
    }

    public RenderType renderType() {
        return this.renderType;
    }

    public List<BakedQuad> quads() {
        return this.quads;
    }

    public EntityInstanceCollector collector() {
        return this.collector;
    }

    @Nullable
    public GpuBufferSlice preparePersistent(
        int cameraBlockX,
        int cameraBlockY,
        int cameraBlockZ,
        long cameraAnchorRevision
    ) {
        if (!this.framePersistentPrepared) {
            this.framePersistentSnapshot = this.persistentSnapshot.prepare(
                this.collector.backingBuffer(),
                this.collector.instanceDataBytes(),
                this.collector.isFrameUnchanged(),
                cameraBlockX,
                cameraBlockY,
                cameraBlockZ,
                cameraAnchorRevision
            );
            this.framePersistentPrepared = true;
        }
        return this.framePersistentSnapshot;
    }

    @Nullable
    public EntityMesh mesh() {
        return this.mesh;
    }

    void setMesh(EntityMesh mesh) {
        if (this.mesh != null) {
            this.mesh.close();
        }
        this.mesh = mesh;
    }

    public boolean shouldRender() {
        return this.collector.instanceCount() > 0 && this.mesh != null;
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
        this.framePersistentSnapshot = null;
        this.framePersistentPrepared = false;
        this.active = false;
    }

    void closeMesh() {
        if (this.mesh != null) {
            this.mesh.close();
        }
        this.mesh = null;
    }

    @Override
    public void close() {
        this.closeMesh();
        this.collector.close();
        this.persistentSnapshot.close();
    }
}
