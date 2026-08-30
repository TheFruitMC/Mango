package org.fruitmc.mango.render.gpu.entity;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

public final class MeshCapturingVertexConsumer implements VertexConsumer {

    private static final int FLOAT_BYTES = Float.BYTES;
    private static final int POSITION_BYTES = 3 * FLOAT_BYTES;
    private static final int UV0_BYTES = 2 * FLOAT_BYTES;
    private static final int NORMAL_BYTES = 4;
    static final int VERTEX_SIZE = POSITION_BYTES + UV0_BYTES + NORMAL_BYTES;

    private static final int INITIAL_VERTEX_CAPACITY = 256;
    private static final int GROWTH_FACTOR = 2;
    private static final float NORMAL_MIN = -1.0F;
    private static final float NORMAL_MAX = 1.0F;
    private static final float NORMAL_SCALE = 127.0F;
    private static final int NORMAL_W_PAD = 0;

    private ByteBuffer buffer;
    private int vertexCount;

    private boolean hasPending;
    private float pendingX;
    private float pendingY;
    private float pendingZ;
    private float pendingU;
    private float pendingV;
    private float pendingNX;
    private float pendingNY;
    private float pendingNZ;

    public MeshCapturingVertexConsumer() {
        this.buffer = MemoryUtil.memAlloc(INITIAL_VERTEX_CAPACITY * VERTEX_SIZE);
        this.vertexCount = 0;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        flushPending();
        this.pendingX = x;
        this.pendingY = y;
        this.pendingZ = z;
        this.hasPending = true;
        return this;
    }

    @Override
    public VertexConsumer setColor(int r, int g, int b, int a) {
        return this;
    }

    @Override
    public VertexConsumer setColor(int color) {
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        this.pendingU = u;
        this.pendingV = v;
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        this.pendingNX = x;
        this.pendingNY = y;
        this.pendingNZ = z;
        return this;
    }

    @Override
    public VertexConsumer setLineWidth(float width) {
        return this;
    }

    @Override
    public void addVertex(
        float x, float y, float z, int color, float u, float v,
        int overlayCoords, int lightCoords, float nx, float ny, float nz
    ) {
        writeVertex(x, y, z, u, v, nx, ny, nz);
    }

    public void finish() {
        flushPending();
    }

    private void flushPending() {
        if (this.hasPending) {
            writeVertex(this.pendingX, this.pendingY, this.pendingZ,
                        this.pendingU, this.pendingV,
                        this.pendingNX, this.pendingNY, this.pendingNZ);
            this.hasPending = false;
        }
    }

    private void writeVertex(float x, float y, float z, float u, float v, float nx, float ny, float nz) {
        ensureCapacity();
        long base = MemoryUtil.memAddress(this.buffer) + (long) this.vertexCount * VERTEX_SIZE;
        MemoryUtil.memPutFloat(base, x);
        MemoryUtil.memPutFloat(base + FLOAT_BYTES, y);
        MemoryUtil.memPutFloat(base + FLOAT_BYTES * 2L, z);
        MemoryUtil.memPutFloat(base + POSITION_BYTES, u);
        MemoryUtil.memPutFloat(base + POSITION_BYTES + FLOAT_BYTES, v);
        MemoryUtil.memPutByte(base + POSITION_BYTES + UV0_BYTES, packNormal(nx));
        MemoryUtil.memPutByte(base + POSITION_BYTES + UV0_BYTES + 1L, packNormal(ny));
        MemoryUtil.memPutByte(base + POSITION_BYTES + UV0_BYTES + 2L, packNormal(nz));
        MemoryUtil.memPutByte(base + POSITION_BYTES + UV0_BYTES + 3L, (byte) NORMAL_W_PAD);
        this.vertexCount++;
    }

    private static byte packNormal(float value) {
        float clamped = value < NORMAL_MIN ? NORMAL_MIN : (value > NORMAL_MAX ? NORMAL_MAX : value);
        return (byte) ((int) (clamped * NORMAL_SCALE) & 0xFF);
    }

    private void ensureCapacity() {
        int requiredBytes = (this.vertexCount + 1) * VERTEX_SIZE;
        if (requiredBytes <= this.buffer.capacity()) {
            return;
        }
        int newCapacity = this.buffer.capacity();
        while (newCapacity < requiredBytes) {
            newCapacity *= GROWTH_FACTOR;
        }
        this.buffer = MemoryUtil.memRealloc(this.buffer, newCapacity);
    }

    public int vertexCount() {
        return this.vertexCount;
    }

    public int quadCount() {
        return this.vertexCount / 4;
    }

    public ByteBuffer uploadSlice() {
        ByteBuffer slice = this.buffer.slice(0, this.vertexCount * VERTEX_SIZE);
        slice.position(0);
        return slice;
    }

    public void clear() {
        this.vertexCount = 0;
        this.hasPending = false;
    }

    public void close() {
        if (this.buffer != null) {
            MemoryUtil.memFree(this.buffer);
            this.buffer = null;
        }
    }
}
