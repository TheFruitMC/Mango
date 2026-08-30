package org.fruitmc.mango.render.gpu.entity;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;

import java.nio.ByteBuffer;

public final class EntityMesh {

    private static final int INDICES_PER_QUAD = 6;
    private static final int VERTICES_PER_QUAD = 4;

    private final int vertexCount;
    private final int quadCount;
    private final GpuBuffer gpuBuffer;

    public EntityMesh(ByteBuffer cpuData, int vertexCount) {
        this.vertexCount = vertexCount;
        this.quadCount = vertexCount / VERTICES_PER_QUAD;
        this.gpuBuffer = RenderSystem.getDevice().createBuffer(
            () -> "Mango instanced entity mesh",
            GpuBuffer.USAGE_VERTEX,
            cpuData
        );
    }

    public int vertexCount() {
        return this.vertexCount;
    }

    public int indexCount() {
        return this.quadCount * INDICES_PER_QUAD;
    }

    public GpuBuffer indexBuffer() {
        return RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).getBuffer(indexCount());
    }

    public IndexType indexType() {
        return RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).type();
    }

    public GpuBuffer gpuBuffer() {
        return this.gpuBuffer;
    }

    public void close() {
        this.gpuBuffer.close();
    }
}
