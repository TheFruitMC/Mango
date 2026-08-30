package org.fruitmc.mango.render.gpu.terrain;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.fruitmc.mango.render.gpu.IndirectCommandBuffer;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;

final class RegionBatch implements AutoCloseable {

    private final IndirectCommandBuffer commands = new IndirectCommandBuffer(INITIAL_COMMAND_CAPACITY);
    private int[] cachedVisibleSlots = new int[INITIAL_VISIBLE_SLOT_CAPACITY];
    private int cachedVisibleCount;
    private boolean geometryDirty = true;
    private boolean everBuilt;

    private static final int INITIAL_COMMAND_CAPACITY = 16;
    private static final int INITIAL_VISIBLE_SLOT_CAPACITY = 16;

    void markGeometryDirty() {
        this.geometryDirty = true;
    }

    boolean isValidFor(@Nullable IntArrayList visibleSlots) {
        if (this.geometryDirty || !this.everBuilt) {
            return false;
        }
        if (visibleSlots == null) {
            return this.cachedVisibleCount == 0;
        }
        int size = visibleSlots.size();
        if (size != this.cachedVisibleCount) {
            return false;
        }
        return Arrays.equals(this.cachedVisibleSlots, 0, size, visibleSlots.elements(), 0, size);
    }

    void markBuilt(@Nullable IntArrayList visibleSlots) {
        int size = visibleSlots != null ? visibleSlots.size() : 0;
        if (size > this.cachedVisibleSlots.length) {
            this.cachedVisibleSlots = new int[size];
        }
        if (size > 0) {
            System.arraycopy(visibleSlots.elements(), 0, this.cachedVisibleSlots, 0, size);
        }
        this.cachedVisibleCount = size;
        this.geometryDirty = false;
        this.everBuilt = true;
    }

    IndirectCommandBuffer commandBuffer() {
        return this.commands;
    }

    int drawCount() {
        return this.commands.drawCount();
    }

    int commandByteCount() {
        return Math.multiplyExact(this.commands.drawCount(), IndirectCommandBuffer.COMMAND_SIZE);
    }

    int copyCommandsTo(long destinationAddress) {
        int bytes = commandByteCount();
        if (bytes > 0) {
            MemoryUtil.memCopy(
                MemoryUtil.memAddress(this.commands.buffer(), 0),
                destinationAddress,
                bytes
            );
        }
        return bytes;
    }

    @Override
    public void close() {
        this.commands.close();
        this.cachedVisibleCount = 0;
        this.everBuilt = false;
    }
}
