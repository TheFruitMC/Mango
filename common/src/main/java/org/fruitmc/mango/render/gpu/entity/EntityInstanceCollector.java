package org.fruitmc.mango.render.gpu.entity;

import net.minecraft.util.ARGB;
import org.fruitmc.mango.render.gpu.buffer.GpuBufferUtils;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

public final class EntityInstanceCollector implements AutoCloseable {

    private static final int INT_BYTES = Integer.BYTES;
    private static final int SHORT_BYTES = Short.BYTES;
    private static final int FLOAT_BYTES = Float.BYTES;
    private static final int SHORT_PAIR_BYTES = SHORT_BYTES * 2;

    private static final int MATRIX_COLUMN_COUNT = 4;
    private static final int MATRIX_FLOATS_PER_COLUMN = 3;
    private static final int MATRIX_BYTES = MATRIX_COLUMN_COUNT * MATRIX_FLOATS_PER_COLUMN * FLOAT_BYTES;
    private static final int COLUMN_BYTES = MATRIX_FLOATS_PER_COLUMN * FLOAT_BYTES;
    private static final int COLUMN_0_OFFSET = 0;
    private static final int COLUMN_1_OFFSET = COLUMN_0_OFFSET + COLUMN_BYTES;
    private static final int COLUMN_2_OFFSET = COLUMN_1_OFFSET + COLUMN_BYTES;
    private static final int COLUMN_3_OFFSET = COLUMN_2_OFFSET + COLUMN_BYTES;
    private static final int PACKED_COMPONENT_BITS = Short.SIZE;
    private static final int PACKED_COMPONENT_MASK = 0xFFFF;

    private static final int UV2_OFFSET = MATRIX_BYTES;
    private static final int UV1_OFFSET = UV2_OFFSET + SHORT_PAIR_BYTES;
    private static final int TINT_OFFSET = UV1_OFFSET + SHORT_PAIR_BYTES;
    public static final int BYTES_PER_INSTANCE = TINT_OFFSET + INT_BYTES;

    private static final int INITIAL_CAPACITY = 1024;
    private static final int GROWTH_FACTOR = 2;

    private ByteBuffer buffer;
    private int instanceCount;
    private int previousInstanceCount;
    private boolean frameDataUnchanged;

    public EntityInstanceCollector() {
        this.buffer = MemoryUtil.memAlloc(INITIAL_CAPACITY * BYTES_PER_INSTANCE);
        this.instanceCount = 0;
        this.previousInstanceCount = 0;
        this.frameDataUnchanged = false;
    }

    public void beginFrame() {
        this.previousInstanceCount = this.instanceCount;
        this.instanceCount = 0;
        this.frameDataUnchanged = true;
    }

    public int addInstance(
        Matrix4f modelMatrix,
        double translationOffsetX,
        double translationOffsetY,
        double translationOffsetZ,
        int light,
        int overlay,
        int tintColor
    ) {
        ensureCapacity();
        int index = this.instanceCount;
        int base = index * BYTES_PER_INSTANCE;
        float translationX = (float)(modelMatrix.m30() + translationOffsetX);
        float translationY = (float)(modelMatrix.m31() + translationOffsetY);
        float translationZ = (float)(modelMatrix.m32() + translationOffsetZ);
        int packedTint = ARGB.toABGR(tintColor);

        boolean recordUnchanged = index < this.previousInstanceCount
            && sameRecord(base, modelMatrix, translationX, translationY, translationZ, light, overlay, packedTint);
        if (!recordUnchanged) {
            this.frameDataUnchanged = false;
            GpuBufferUtils.putColumn(this.buffer, base + COLUMN_0_OFFSET, modelMatrix.m00(), modelMatrix.m01(), modelMatrix.m02());
            GpuBufferUtils.putColumn(this.buffer, base + COLUMN_1_OFFSET, modelMatrix.m10(), modelMatrix.m11(), modelMatrix.m12());
            GpuBufferUtils.putColumn(this.buffer, base + COLUMN_2_OFFSET, modelMatrix.m20(), modelMatrix.m21(), modelMatrix.m22());
            GpuBufferUtils.putColumn(this.buffer, base + COLUMN_3_OFFSET, translationX, translationY, translationZ);

            this.buffer.putShort(base + UV2_OFFSET, (short)(light & PACKED_COMPONENT_MASK));
            this.buffer.putShort(base + UV2_OFFSET + SHORT_BYTES, (short)((light >> PACKED_COMPONENT_BITS) & PACKED_COMPONENT_MASK));
            this.buffer.putShort(base + UV1_OFFSET, (short)(overlay & PACKED_COMPONENT_MASK));
            this.buffer.putShort(base + UV1_OFFSET + SHORT_BYTES, (short)((overlay >> PACKED_COMPONENT_BITS) & PACKED_COMPONENT_MASK));
            this.buffer.putInt(base + TINT_OFFSET, packedTint);
        }

        this.instanceCount++;
        return index;
    }

    public int instanceCount() {
        return this.instanceCount;
    }

    public boolean isFrameUnchanged() {
        return this.frameDataUnchanged && this.instanceCount == this.previousInstanceCount;
    }

    public void removeLastInstance() {
        if (this.instanceCount == 0) {
            throw new IllegalStateException("Cannot roll back an empty entity instance collector");
        }
        this.instanceCount--;
    }

    public int instanceDataBytes() {
        return this.instanceCount * BYTES_PER_INSTANCE;
    }

    public ByteBuffer backingBuffer() {
        return this.buffer;
    }

    public void clear() {
        this.instanceCount = 0;
        this.previousInstanceCount = 0;
        this.frameDataUnchanged = false;
        int initialBytes = INITIAL_CAPACITY * BYTES_PER_INSTANCE;
        if (this.buffer.capacity() != initialBytes) {
            MemoryUtil.memFree(this.buffer);
            this.buffer = MemoryUtil.memAlloc(initialBytes);
        }
    }

    @Override
    public void close() {
        if (this.buffer != null) {
            MemoryUtil.memFree(this.buffer);
            this.buffer = null;
        }
        this.instanceCount = 0;
        this.previousInstanceCount = 0;
        this.frameDataUnchanged = false;
    }

    private boolean sameRecord(
        int base,
        Matrix4f modelMatrix,
        float translationX,
        float translationY,
        float translationZ,
        int light,
        int overlay,
        int packedTint
    ) {
        return this.buffer.getInt(base + COLUMN_0_OFFSET) == Float.floatToRawIntBits(modelMatrix.m00())
            && this.buffer.getInt(base + COLUMN_0_OFFSET + FLOAT_BYTES) == Float.floatToRawIntBits(modelMatrix.m01())
            && this.buffer.getInt(base + COLUMN_0_OFFSET + FLOAT_BYTES * 2) == Float.floatToRawIntBits(modelMatrix.m02())
            && this.buffer.getInt(base + COLUMN_1_OFFSET) == Float.floatToRawIntBits(modelMatrix.m10())
            && this.buffer.getInt(base + COLUMN_1_OFFSET + FLOAT_BYTES) == Float.floatToRawIntBits(modelMatrix.m11())
            && this.buffer.getInt(base + COLUMN_1_OFFSET + FLOAT_BYTES * 2) == Float.floatToRawIntBits(modelMatrix.m12())
            && this.buffer.getInt(base + COLUMN_2_OFFSET) == Float.floatToRawIntBits(modelMatrix.m20())
            && this.buffer.getInt(base + COLUMN_2_OFFSET + FLOAT_BYTES) == Float.floatToRawIntBits(modelMatrix.m21())
            && this.buffer.getInt(base + COLUMN_2_OFFSET + FLOAT_BYTES * 2) == Float.floatToRawIntBits(modelMatrix.m22())
            && this.buffer.getInt(base + COLUMN_3_OFFSET) == Float.floatToRawIntBits(translationX)
            && this.buffer.getInt(base + COLUMN_3_OFFSET + FLOAT_BYTES) == Float.floatToRawIntBits(translationY)
            && this.buffer.getInt(base + COLUMN_3_OFFSET + FLOAT_BYTES * 2) == Float.floatToRawIntBits(translationZ)
            && this.buffer.getShort(base + UV2_OFFSET) == (short)(light & PACKED_COMPONENT_MASK)
            && this.buffer.getShort(base + UV2_OFFSET + SHORT_BYTES) == (short)((light >> PACKED_COMPONENT_BITS) & PACKED_COMPONENT_MASK)
            && this.buffer.getShort(base + UV1_OFFSET) == (short)(overlay & PACKED_COMPONENT_MASK)
            && this.buffer.getShort(base + UV1_OFFSET + SHORT_BYTES) == (short)((overlay >> PACKED_COMPONENT_BITS) & PACKED_COMPONENT_MASK)
            && this.buffer.getInt(base + TINT_OFFSET) == packedTint;
    }

    private void ensureCapacity() {
        int required = (this.instanceCount + 1) * BYTES_PER_INSTANCE;
        if (required <= this.buffer.capacity()) {
            return;
        }

        int newCapacity = this.buffer.capacity();
        while (newCapacity < required) {
            newCapacity *= GROWTH_FACTOR;
        }

        ByteBuffer newBuffer = MemoryUtil.memAlloc(newCapacity);
        int usedBytes = this.instanceCount * BYTES_PER_INSTANCE;
        newBuffer.put(this.buffer.slice(0, usedBytes));
        newBuffer.position(usedBytes);

        MemoryUtil.memFree(this.buffer);
        this.buffer = newBuffer;
    }
}
