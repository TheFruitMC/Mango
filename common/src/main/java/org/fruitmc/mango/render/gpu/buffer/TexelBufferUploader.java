package org.fruitmc.mango.render.gpu.buffer;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.client.renderer.MappableRingBuffer;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Supplier;

public final class TexelBufferUploader implements AutoCloseable {

    private static final int GROWTH_FACTOR = 2;
    private static final long NO_SPARSE_REVISION = Long.MIN_VALUE;

    private final Supplier<String> label;
    private final int initialCapacity;
    private final int maxCapacity;
    private final int additionalUsage;
    private final Map<GpuBuffer, SparseBufferState> sparseStates = new IdentityHashMap<>();

    @Nullable private MappableRingBuffer ringBuffer;
    @Nullable private GpuBufferSlice.MappedView persistentView;
    private long observedSparseRevision = NO_SPARSE_REVISION;
    private boolean usedThisFrame;

    public TexelBufferUploader(Supplier<String> label, int initialCapacity, int maxCapacity) {
        this(label, initialCapacity, maxCapacity, 0);
    }

    public TexelBufferUploader(Supplier<String> label, int initialCapacity, int maxCapacity, int additionalUsage) {
        if (initialCapacity <= 0 || maxCapacity < initialCapacity) {
            throw new IllegalArgumentException(
                "Invalid texel buffer capacities: initial=" + initialCapacity + ", max=" + maxCapacity
            );
        }

        this.label = label;
        this.initialCapacity = initialCapacity;
        this.maxCapacity = maxCapacity;
        this.additionalUsage = additionalUsage;
    }

    public GpuBuffer upload(ByteBuffer source) {
        int length = source.remaining();
        if (length <= 0) {
            throw new IllegalArgumentException("Texel buffer source must not be empty");
        }
        if (length > this.maxCapacity) {
            throw new IllegalArgumentException(
                "Texel buffer capacity exceeded: " + length + " > " + this.maxCapacity
            );
        }

        ensureCapacity(length);
        MappableRingBuffer ring = this.ringBuffer;
        GpuBuffer buffer = ring.currentBuffer();
        copyRange(ensurePersistentMapping(buffer, ring.size()), source.slice(), 0, length);

        resetSparseTracking();
        this.usedThisFrame = true;
        return buffer;
    }

    public GpuBuffer uploadSparse(ByteBuffer source, int recordSize, int[] dirtyRecords, long revision) {
        ByteBuffer data = source.slice();
        int length = data.remaining();
        if (length <= 0 || recordSize <= 0 || length % recordSize != 0) {
            throw new IllegalArgumentException(
                "Invalid sparse texel table: bytes=" + length + ", recordSize=" + recordSize
            );
        }

        ensureCapacity(length);
        MappableRingBuffer ring = this.ringBuffer;
        GpuBuffer buffer = ring.currentBuffer();
        if (revision != this.observedSparseRevision) {
            for (SparseBufferState state : this.sparseStates.values()) {
                state.addAll(dirtyRecords);
            }
            this.observedSparseRevision = revision;
        }

        ByteBuffer mapped = ensurePersistentMapping(buffer, ring.size());
        SparseBufferState state = this.sparseStates.get(buffer);
        if (state == null) {
            copyRange(mapped, data, 0, length);
            this.sparseStates.put(buffer, new SparseBufferState());
        } else {
            applySparseUpdates(mapped, data, recordSize, state);
        }

        this.usedThisFrame = true;
        return buffer;
    }

    public void endFrame() {
        if (this.usedThisFrame) {
            releaseMapping();
            if (this.ringBuffer != null) this.ringBuffer.rotate();
            this.usedThisFrame = false;
        }
    }

    @Override
    public void close() {
        releaseMapping();
        if (this.ringBuffer != null) this.ringBuffer.close();
        this.ringBuffer = null;
        resetSparseTracking();
        this.usedThisFrame = false;
    }

    private void ensureCapacity(int requiredBytes) {
        MappableRingBuffer current = this.ringBuffer;
        if (current != null && current.size() >= requiredBytes) {
            return;
        }
        if (this.usedThisFrame) {
            throw new IllegalStateException("Texel buffer capacity exceeded after this frame's upload");
        }

        int capacity = current != null ? current.size() : this.initialCapacity;
        while (capacity < requiredBytes) {
            if (capacity > this.maxCapacity / GROWTH_FACTOR) {
                capacity = this.maxCapacity;
                break;
            }
            capacity *= GROWTH_FACTOR;
        }
        if (capacity < requiredBytes) {
            throw new IllegalArgumentException(
                "Texel buffer capacity exceeded: " + requiredBytes + " > " + this.maxCapacity
            );
        }

        releaseMapping();
        if (current != null) current.close();
        resetSparseTracking();
        int usage = GpuBuffer.USAGE_MAP_WRITE | dataUsage();
        this.ringBuffer = new MappableRingBuffer(this.label, usage, capacity);
    }

    private ByteBuffer ensurePersistentMapping(GpuBuffer buffer, int size) {
        GpuBufferSlice.MappedView view = this.persistentView;
        if (view != null) {
            return view.data();
        }
        this.persistentView = buffer.slice(0, size).map(false, true);
        return this.persistentView.data();
    }

    private void releaseMapping() {
        if (this.persistentView != null) {
            this.persistentView.close();
            this.persistentView = null;
        }
    }

    private int dataUsage() {
        return GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | this.additionalUsage;
    }

    private void applySparseUpdates(
        ByteBuffer mapped,
        ByteBuffer source,
        int recordSize,
        SparseBufferState state
    ) {
        int[] records = state.takeSortedRecords();
        int recordCount = source.remaining() / recordSize;
        int runStart = -1;
        int runEnd = -1;
        for (int record : records) {
            if (record < 0 || record >= recordCount) {
                continue;
            }
            if (runStart < 0) {
                runStart = record;
                runEnd = record;
            } else if (record == runEnd + 1) {
                runEnd = record;
            } else {
                copyRecordRange(mapped, source, recordSize, runStart, runEnd);
                runStart = record;
                runEnd = record;
            }
        }
        if (runStart >= 0) {
            copyRecordRange(mapped, source, recordSize, runStart, runEnd);
        }
    }

    private static void copyRecordRange(
        ByteBuffer mapped,
        ByteBuffer source,
        int recordSize,
        int firstRecord,
        int lastRecord
    ) {
        int offset = Math.multiplyExact(firstRecord, recordSize);
        int length = Math.multiplyExact(lastRecord - firstRecord + 1, recordSize);
        copyRange(mapped, source, offset, length);
    }

    private static void copyRange(ByteBuffer mapped, ByteBuffer source, int offset, int length) {
        MemoryUtil.memCopy(
            baseAddress(source) + offset,
            baseAddress(mapped) + offset,
            length
        );
    }

    private static long baseAddress(ByteBuffer buffer) {
        return MemoryUtil.memAddress(buffer) - buffer.position();
    }

    private void resetSparseTracking() {
        this.sparseStates.clear();
        this.observedSparseRevision = NO_SPARSE_REVISION;
    }

    private static final class SparseBufferState {
        private final IntOpenHashSet dirtyRecords = new IntOpenHashSet();

        private void addAll(int[] records) {
            for (int record : records) {
                this.dirtyRecords.add(record);
            }
        }

        private int[] takeSortedRecords() {
            int[] records = this.dirtyRecords.toIntArray();
            this.dirtyRecords.clear();
            Arrays.sort(records);
            return records;
        }
    }
}
