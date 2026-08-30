package org.fruitmc.mango.render.gpu.particle;

import net.minecraft.util.ARGB;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

public final class ParticleInstanceCollector implements AutoCloseable {

    private static final int FLOAT_BYTES = Float.BYTES;
    private static final int INT_BYTES = Integer.BYTES;
    private static final int SHORT_BYTES = Short.BYTES;

    private static final int POSITION_OFFSET = 0;
    private static final int ROTATION_OFFSET = POSITION_OFFSET + 3 * FLOAT_BYTES;
    private static final int SCALE_OFFSET = ROTATION_OFFSET + 4 * FLOAT_BYTES;
    private static final int UV_OFFSET = SCALE_OFFSET + FLOAT_BYTES;
    private static final int COLOR_OFFSET = UV_OFFSET + 4 * FLOAT_BYTES;
    private static final int UV2_OFFSET = COLOR_OFFSET + INT_BYTES;
    public static final int BYTES_PER_INSTANCE = UV2_OFFSET + 2 * SHORT_BYTES;

    private static final int PACKED_COMPONENT_BITS = Short.SIZE;
    private static final int PACKED_COMPONENT_MASK = 0xFFFF;

    private static final int INITIAL_CAPACITY = 1024;
    private static final int GROWTH_FACTOR = 2;

    private ByteBuffer buffer;
    private int instanceCount;
    private boolean closed;

    public ParticleInstanceCollector() {
        this.buffer = MemoryUtil.memAlloc(INITIAL_CAPACITY * BYTES_PER_INSTANCE);
    }

    public void beginFrame() {
        this.instanceCount = 0;
    }

    public void addInstance(
        float x,
        float y,
        float z,
        float xRot,
        float yRot,
        float zRot,
        float wRot,
        float scale,
        float u0,
        float u1,
        float v0,
        float v1,
        int color,
        int lightCoords
    ) {
        ensureCapacity();
        int base = this.instanceCount * BYTES_PER_INSTANCE;

        this.buffer.putFloat(base + POSITION_OFFSET, x);
        this.buffer.putFloat(base + POSITION_OFFSET + FLOAT_BYTES, y);
        this.buffer.putFloat(base + POSITION_OFFSET + 2 * FLOAT_BYTES, z);

        this.buffer.putFloat(base + ROTATION_OFFSET, xRot);
        this.buffer.putFloat(base + ROTATION_OFFSET + FLOAT_BYTES, yRot);
        this.buffer.putFloat(base + ROTATION_OFFSET + 2 * FLOAT_BYTES, zRot);
        this.buffer.putFloat(base + ROTATION_OFFSET + 3 * FLOAT_BYTES, wRot);

        this.buffer.putFloat(base + SCALE_OFFSET, scale);

        this.buffer.putFloat(base + UV_OFFSET, u0);
        this.buffer.putFloat(base + UV_OFFSET + FLOAT_BYTES, u1);
        this.buffer.putFloat(base + UV_OFFSET + 2 * FLOAT_BYTES, v0);
        this.buffer.putFloat(base + UV_OFFSET + 3 * FLOAT_BYTES, v1);

        this.buffer.putInt(base + COLOR_OFFSET, ARGB.toABGR(color));

        this.buffer.putShort(base + UV2_OFFSET, (short)(lightCoords & PACKED_COMPONENT_MASK));
        this.buffer.putShort(
            base + UV2_OFFSET + SHORT_BYTES,
            (short)((lightCoords >> PACKED_COMPONENT_BITS) & PACKED_COMPONENT_MASK)
        );

        this.instanceCount++;
    }

    public int instanceCount() {
        return this.instanceCount;
    }

    public int instanceDataBytes() {
        return this.instanceCount * BYTES_PER_INSTANCE;
    }

    public ByteBuffer backingBuffer() {
        return this.buffer;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        MemoryUtil.memFree(this.buffer);
        this.instanceCount = 0;
    }

    private void ensureCapacity() {
        int required = (this.instanceCount + 1) * BYTES_PER_INSTANCE;
        if (required <= this.buffer.capacity()) {
            return;
        }

        int newCapacity = Math.max(this.buffer.capacity(), BYTES_PER_INSTANCE);
        while (newCapacity < required) {
            newCapacity = Math.multiplyExact(newCapacity, GROWTH_FACTOR);
        }

        ByteBuffer grown = MemoryUtil.memAlloc(newCapacity);
        MemoryUtil.memCopy(
            MemoryUtil.memAddress(this.buffer),
            MemoryUtil.memAddress(grown),
            this.instanceCount * BYTES_PER_INSTANCE
        );
        MemoryUtil.memFree(this.buffer);
        this.buffer = grown;
    }
}
