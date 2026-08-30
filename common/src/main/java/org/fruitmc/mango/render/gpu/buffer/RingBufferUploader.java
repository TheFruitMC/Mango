package org.fruitmc.mango.render.gpu.buffer;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.MappableRingBuffer;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Frame-local upload arena built from three rotating slots. Two slots can remain in flight while the
 * third is reused, matching the vanilla command encoder's two-submit lifetime.
 */
public final class RingBufferUploader implements AutoCloseable {

    private static final int GROWTH_FACTOR = 2;
    private static final int MIN_CAPACITY_BYTES = 256;
    private static final int RING_SLOT_COUNT = 3;

    private final Supplier<String> label;
    private final int usage;
    private final boolean hostWritable;
    private final IntSupplier alignmentSupplier;
    private final int initialCapacity;
    private final List<MappedSlot> mappedSlots = new ArrayList<>(RING_SLOT_COUNT);

    @Nullable private SlotRing ringBuffer;
    @Nullable private GpuBuffer frameBuffer;
    @Nullable private MappedSlot frameSlot;
    private int writeOffset;
    private boolean usedThisFrame;

    public RingBufferUploader(Supplier<String> label, @GpuBuffer.Usage int usage, int alignment, int initialCapacity) {
        this(label, usage, GpuBufferUtils.fixedAlignment(alignment), initialCapacity);
    }

    public RingBufferUploader(
        Supplier<String> label,
        @GpuBuffer.Usage int usage,
        IntSupplier alignmentSupplier,
        int initialCapacity
    ) {
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("RingBufferUploader initial capacity must be positive");
        }

        this.label = label;
        this.usage = usage;
        this.hostWritable = (usage & GpuBuffer.USAGE_MAP_WRITE) != 0;
        this.alignmentSupplier = alignmentSupplier;
        this.initialCapacity = Math.max(initialCapacity, MIN_CAPACITY_BYTES);
    }

    public void beginFrame(int requiredBytes, int sliceCount) {
        this.writeOffset = 0;
        this.usedThisFrame = false;
        this.frameBuffer = null;
        this.frameSlot = null;
        if (requiredBytes > 0) {
            int paddedBytes = sliceCount > 0
                ? Math.addExact(requiredBytes, Math.multiplyExact(sliceCount, alignment() - 1))
                : requiredBytes;
            ensureCapacity(paddedBytes);
        }
    }

    public GpuBufferSlice upload(ByteBuffer source) {
        int length = source.remaining();
        if (length <= 0) {
            throw new IllegalArgumentException("RingBufferUploader source must not be empty");
        }

        int offset = reserve(length);
        MappedSlot slot = currentSlot();
        MemoryUtil.memCopy(
            MemoryUtil.memAddress(source),
            slot.baseAddress() + offset,
            length
        );
        return slot.buffer().slice(offset, length);
    }

    public MappedSlice allocateMapped(int length) {
        int offset = reserve(length);
        MappedSlot slot = currentSlot();
        return new MappedSlice(
            slot.buffer().slice(offset, length),
            MemoryUtil.memByteBuffer(slot.baseAddress() + offset, length)
        );
    }

    public GpuBufferSlice allocateSlice(int length) {
        int offset = reserve(length);
        return currentBuffer().slice(offset, length);
    }

    @Nullable
    public GpuBufferSlice reservedSlice() {
        GpuBuffer buffer = this.frameBuffer;
        if (!this.usedThisFrame || this.writeOffset <= 0 || buffer == null) {
            return null;
        }
        return buffer.slice(0, this.writeOffset);
    }

    public void endFrame() {
        if (this.usedThisFrame) {
            SlotRing ring = this.ringBuffer;
            if (ring != null) {
                ring.rotate();
            }
            this.frameBuffer = null;
            this.frameSlot = null;
            this.usedThisFrame = false;
        }
    }

    @Override
    public void close() {
        releaseMappings();
        SlotRing ring = this.ringBuffer;
        if (ring != null) {
            ring.close();
        }
        this.ringBuffer = null;
        this.frameBuffer = null;
        this.writeOffset = 0;
        this.usedThisFrame = false;
    }

    public record MappedSlice(GpuBufferSlice slice, ByteBuffer data) {
    }

    private record MappedSlot(GpuBuffer buffer, GpuBufferSlice.MappedView view, long baseAddress) {
    }

    private int reserve(int length) {
        if (length <= 0) {
            throw new IllegalArgumentException("RingBufferUploader slice length must be positive");
        }

        int offset = GpuBufferUtils.alignUp(this.writeOffset, alignment());
        ensureCapacity(offset + length);
        this.writeOffset = offset + length;
        this.usedThisFrame = true;
        return offset;
    }

    private GpuBuffer currentBuffer() {
        GpuBuffer buffer = this.frameBuffer;
        if (buffer != null) {
            return buffer;
        }
        SlotRing ring = this.ringBuffer;
        if (ring == null) {
            throw new IllegalStateException(
                "RingBufferUploader '" + this.label.get() + "' has no backing ring; reserve() must create one first"
            );
        }
        buffer = ring.currentBuffer();
        this.frameBuffer = buffer;
        return buffer;
    }

    private MappedSlot currentSlot() {
        MappedSlot slot = this.frameSlot;
        if (slot != null) {
            return slot;
        }
        if (!this.hostWritable) {
            throw new IllegalStateException(
                "RingBufferUploader '" + this.label.get() + "' is device-local; host writes require "
                    + "GpuBuffer.USAGE_MAP_WRITE in its usage flags"
            );
        }
        GpuBuffer current = currentBuffer();
        slot = findMappedSlot(current);
        if (slot == null) {
            GpuBufferSlice.MappedView view = current.map(false, true);
            slot = new MappedSlot(current, view, MemoryUtil.memAddress(view.data()));
            this.mappedSlots.add(slot);
        }
        this.frameSlot = slot;
        return slot;
    }

    @Nullable
    private MappedSlot findMappedSlot(GpuBuffer buffer) {
        for (int index = 0; index < this.mappedSlots.size(); index++) {
            MappedSlot slot = this.mappedSlots.get(index);
            if (slot.buffer() == buffer) {
                return slot;
            }
        }
        return null;
    }

    private void releaseMappings() {
        for (int index = 0; index < this.mappedSlots.size(); index++) {
            this.mappedSlots.get(index).view().close();
        }
        this.mappedSlots.clear();
        this.frameSlot = null;
    }

    private void ensureCapacity(int requiredBytes) {
        int minimumCapacity = Math.max(requiredBytes, this.initialCapacity);

        SlotRing current = this.ringBuffer;
        if (current != null && current.size() >= minimumCapacity) {
            return;
        }
        if (this.usedThisFrame && current != null) {
            // Growing after the first slice has been handed out would invalidate already-returned views.
            throw new IllegalStateException(
                "RingBufferUploader capacity exceeded after uploads began: need " + minimumCapacity
                    + " bytes, slot holds " + current.size()
            );
        }

        releaseMappings();
        this.ringBuffer = null;
        this.frameBuffer = null;
        if (current != null) {
            current.close();
        }
        int capacity = current != null ? current.size() : this.initialCapacity;
        capacity = Math.max(capacity, MIN_CAPACITY_BYTES);
        while (capacity < minimumCapacity) {
            capacity = Math.multiplyExact(capacity, GROWTH_FACTOR);
        }

        this.ringBuffer = this.hostWritable
            ? new MappableSlotRing(new MappableRingBuffer(this.label, this.usage, capacity))
            : new DeviceLocalSlotRing(this.label, this.usage, capacity);
    }

    private int alignment() {
        int alignment = this.alignmentSupplier.getAsInt();
        if (alignment <= 0) {
            throw new IllegalStateException("RingBufferUploader alignment must be positive");
        }
        return alignment;
    }

    private interface SlotRing extends AutoCloseable {

        int size();

        GpuBuffer currentBuffer();

        void rotate();

        @Override
        void close();
    }

    private record MappableSlotRing(MappableRingBuffer delegate) implements SlotRing {

        @Override
        public int size() {
            return this.delegate.size();
        }

        @Override
        public GpuBuffer currentBuffer() {
            return this.delegate.currentBuffer();
        }

        @Override
        public void rotate() {
            this.delegate.rotate();
        }

        @Override
        public void close() {
            this.delegate.close();
        }
    }

    private static final class DeviceLocalSlotRing implements SlotRing {

        private final GpuBuffer[] buffers = new GpuBuffer[RING_SLOT_COUNT];
        private final @Nullable GpuFence[] fences = new GpuFence[RING_SLOT_COUNT];
        private final int size;
        private int current;

        DeviceLocalSlotRing(Supplier<String> label, @GpuBuffer.Usage int usage, int size) {
            GpuDevice device = RenderSystem.getDevice();
            for (int index = 0; index < RING_SLOT_COUNT; index++) {
                int slot = index;
                this.buffers[index] = device.createBuffer(() -> label.get() + " #" + slot, usage, size);
            }
            this.size = size;
        }

        @Override
        public int size() {
            return this.size;
        }

        @Override
        public GpuBuffer currentBuffer() {
            GpuFence fence = this.fences[this.current];
            if (fence != null) {
                fence.awaitCompletion(Long.MAX_VALUE);
                fence.close();
                this.fences[this.current] = null;
            }
            return this.buffers[this.current];
        }

        @Override
        public void rotate() {
            GpuFence previous = this.fences[this.current];
            if (previous != null) {
                previous.close();
            }
            this.fences[this.current] = RenderSystem.getDevice().createCommandEncoder().createFence();
            this.current = (this.current + 1) % RING_SLOT_COUNT;
        }

        @Override
        public void close() {
            for (int index = 0; index < RING_SLOT_COUNT; index++) {
                this.buffers[index].close();
                GpuFence fence = this.fences[index];
                if (fence != null) {
                    fence.close();
                }
            }
        }
    }
}
