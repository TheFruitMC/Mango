package org.fruitmc.mango.render.gpu;

import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkDrawIndexedIndirectCommand;

import java.nio.ByteBuffer;

public final class IndirectCommandBuffer implements AutoCloseable {

    public static final int COMMAND_SIZE = VkDrawIndexedIndirectCommand.SIZEOF;

    private static final int DEFAULT_INITIAL_CAPACITY = 64;
    private static final int GROWTH_FACTOR = 2;
    private static final int INSTANCE_COUNT = 1;
    private static final int MINIMUM_CAPACITY = 1;

    private ByteBuffer buffer;
    private boolean closed;
    private int drawCount;

    public IndirectCommandBuffer() {
        this(DEFAULT_INITIAL_CAPACITY);
    }

    public IndirectCommandBuffer(int initialCapacity) {
        if (initialCapacity < MINIMUM_CAPACITY) {
            throw new IllegalArgumentException("Indirect command capacity must be positive: " + initialCapacity);
        }
        this.buffer = MemoryUtil.memAlloc(Math.multiplyExact(initialCapacity, COMMAND_SIZE));
        this.drawCount = 0;
    }

    public void ensureCapacity(int drawCount) {
        ensureOpen();
        int requiredBytes = Math.multiplyExact(drawCount, COMMAND_SIZE);
        if (this.buffer.capacity() < requiredBytes) {
            int newCapacityBytes = Math.max(Math.multiplyExact(this.buffer.capacity(), GROWTH_FACTOR), requiredBytes);
            this.buffer = MemoryUtil.memRealloc(this.buffer, newCapacityBytes);
        }
    }

    public void addDraw(int indexCount, int firstIndex, int vertexOffset, int firstInstance) {
        addDraw(indexCount, INSTANCE_COUNT, firstIndex, vertexOffset, firstInstance);
    }

    public void addDraw(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
        ensureCapacity(this.drawCount + 1);
        int baseOffset = this.drawCount * COMMAND_SIZE;
        this.buffer.putInt(baseOffset + VkDrawIndexedIndirectCommand.INDEXCOUNT, indexCount);
        this.buffer.putInt(baseOffset + VkDrawIndexedIndirectCommand.INSTANCECOUNT, instanceCount);
        this.buffer.putInt(baseOffset + VkDrawIndexedIndirectCommand.FIRSTINDEX, firstIndex);
        this.buffer.putInt(baseOffset + VkDrawIndexedIndirectCommand.VERTEXOFFSET, vertexOffset);
        this.buffer.putInt(baseOffset + VkDrawIndexedIndirectCommand.FIRSTINSTANCE, firstInstance);
        this.drawCount++;
    }

    public void addAll(ByteBuffer source, int drawCount) {
        ensureOpen();
        if (drawCount <= 0) {
            return;
        }
        int sourceBytes = Math.multiplyExact(drawCount, COMMAND_SIZE);
        if (source.remaining() < sourceBytes) {
            throw new IllegalArgumentException(
                "Indirect command block is shorter than its draw count: " + source.remaining() + " < " + sourceBytes
            );
        }
        ensureCapacity(this.drawCount + drawCount);
        MemoryUtil.memCopy(
            MemoryUtil.memAddress(source),
            MemoryUtil.memAddress(this.buffer) + (long) this.drawCount * COMMAND_SIZE,
            sourceBytes
        );
        this.drawCount += drawCount;
    }

    public void clear() {
        ensureOpen();
        this.drawCount = 0;
    }

    public int drawCount() {
        ensureOpen();
        return this.drawCount;
    }

    public ByteBuffer buffer() {
        ensureOpen();
        return this.buffer;
    }

    @Override
    public void close() {
        if (!this.closed) {
            MemoryUtil.memFree(this.buffer);
            this.closed = true;
        }
    }

    private void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException("Indirect command buffer is closed");
        }
    }
}
