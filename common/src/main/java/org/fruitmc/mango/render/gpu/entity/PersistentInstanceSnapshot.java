package org.fruitmc.mango.render.gpu.entity;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import org.fruitmc.mango.render.gpu.buffer.PersistentBufferUploader;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.function.Supplier;

public final class PersistentInstanceSnapshot implements AutoCloseable {

    private static final int INSTANCE_BUFFER_USAGE = GpuBuffer.USAGE_VERTEX;
    private static final int GROWTH_FACTOR = 2;
    private static final int MIN_BUFFER_CAPACITY_BYTES = 256;
    private static final int KIB_BYTES = 1024;
    private static final int MIB_BYTES = KIB_BYTES * KIB_BYTES;
    private static final int MAX_SNAPSHOT_COUNT = 512;
    private static final int MAX_RESERVED_BYTES = 64 * MIB_BYTES;

    private static int allocatedSnapshotCount;
    private static int reservedSnapshotBytes;

    private final PersistentBufferUploader uploader;
    private int byteLength;
    private int reservedCapacityBytes;
    private int cameraBlockX;
    private int cameraBlockY;
    private int cameraBlockZ;
    private long cameraAnchorRevision;
    private boolean budgetRegistered;
    private boolean hasCameraBlock;
    private boolean valid;

    public PersistentInstanceSnapshot(Supplier<String> label) {
        this.uploader = new PersistentBufferUploader(label, INSTANCE_BUFFER_USAGE);
    }

    @Nullable
    public GpuBufferSlice prepare(
        ByteBuffer source,
        int length,
        boolean unchanged,
        int cameraBlockX,
        int cameraBlockY,
        int cameraBlockZ,
        long cameraAnchorRevision
    ) {
        RenderSystem.assertOnRenderThread();
        boolean cameraBlockChanged = !this.hasCameraBlock
            || this.cameraBlockX != cameraBlockX
            || this.cameraBlockY != cameraBlockY
            || this.cameraBlockZ != cameraBlockZ
            || this.cameraAnchorRevision != cameraAnchorRevision;
        this.cameraBlockX = cameraBlockX;
        this.cameraBlockY = cameraBlockY;
        this.cameraBlockZ = cameraBlockZ;
        this.cameraAnchorRevision = cameraAnchorRevision;
        this.hasCameraBlock = true;
        if (!unchanged || length <= 0 || cameraBlockChanged) {
            this.valid = false;
            return null;
        }
        if (!reserveBudget(length)) {
            this.valid = false;
            return null;
        }
        if (!this.valid || this.byteLength != length) {
            this.uploader.upload(source.slice(0, length));
            this.byteLength = length;
            this.valid = true;
        }
        return this.uploader.slice();
    }

    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        this.uploader.close();
        if (this.budgetRegistered) {
            allocatedSnapshotCount--;
            reservedSnapshotBytes -= this.reservedCapacityBytes;
        }
        this.byteLength = 0;
        this.reservedCapacityBytes = 0;
        this.cameraBlockX = 0;
        this.cameraBlockY = 0;
        this.cameraBlockZ = 0;
        this.cameraAnchorRevision = 0L;
        this.budgetRegistered = false;
        this.hasCameraBlock = false;
        this.valid = false;
    }

    private boolean reserveBudget(int requiredBytes) {
        int requiredCapacity = MIN_BUFFER_CAPACITY_BYTES;
        while (requiredCapacity < requiredBytes) {
            requiredCapacity = Math.multiplyExact(requiredCapacity, GROWTH_FACTOR);
        }
        if (requiredCapacity <= this.reservedCapacityBytes) {
            return true;
        }

        int extraBytes = requiredCapacity - this.reservedCapacityBytes;
        int extraSnapshots = this.budgetRegistered ? 0 : 1;
        if (allocatedSnapshotCount + extraSnapshots > MAX_SNAPSHOT_COUNT
            || reservedSnapshotBytes + extraBytes > MAX_RESERVED_BYTES) {
            return false;
        }
        allocatedSnapshotCount += extraSnapshots;
        reservedSnapshotBytes += extraBytes;
        this.reservedCapacityBytes = requiredCapacity;
        this.budgetRegistered = true;
        return true;
    }
}
