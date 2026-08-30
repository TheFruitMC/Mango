package org.fruitmc.mango.render.gpu.buffer;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import org.fruitmc.mango.mixin.accessor.GpuDeviceAccessor;
import org.fruitmc.mango.render.vulkan.compute.MangoComputePipeline;
import org.fruitmc.mango.render.vulkan.compute.MangoVulkanCommandAccess;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.vulkan.KHRSynchronization2;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.function.Supplier;

/**
 * Uploads device-local texel buffers while coalescing sparse record updates into contiguous copies.
 */
public final class DeviceLocalTexelBufferUploader implements AutoCloseable {

    private static final int GROWTH_FACTOR = 2;

    private final Supplier<String> label;
    private final int initialCapacity;
    private final int maxCapacity;
    private final int usage;

    @Nullable private GpuBuffer buffer;
    private int capacityBytes;
    private int populatedBytes;

    public DeviceLocalTexelBufferUploader(
        Supplier<String> label,
        int initialCapacity,
        int maxCapacity,
        @GpuBuffer.Usage int additionalUsage
    ) {
        if (initialCapacity <= 0 || maxCapacity < initialCapacity) {
            throw new IllegalArgumentException(
                "Invalid device-local texel buffer capacities: initial=" + initialCapacity
                    + ", max=" + maxCapacity
            );
        }
        this.label = label;
        this.initialCapacity = initialCapacity;
        this.maxCapacity = maxCapacity;
        this.usage = GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER
            | GpuBuffer.USAGE_COPY_DST
            | additionalUsage;
    }

    public GpuBuffer upload(ByteBuffer source) {
        ByteBuffer data = validateSource(source);
        GpuBuffer previous = this.buffer;
        int previousPopulatedBytes = this.populatedBytes;
        GpuBuffer target = ensureCapacity(data.remaining());
        if (target == previous && previousPopulatedBytes > 0) {
            synchronizeForRewrite(target, Math.min(previousPopulatedBytes, data.remaining()));
        }
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(
            target.slice(0, data.remaining()),
            data
        );
        this.populatedBytes = data.remaining();
        return target;
    }

    public GpuBuffer uploadSparse(ByteBuffer source, int recordSize, int[] dirtyRecords) {
        ByteBuffer data = validateSource(source);
        int length = data.remaining();
        if (recordSize <= 0 || length % recordSize != 0) {
            throw new IllegalArgumentException(
                "Invalid sparse device-local texel table: bytes=" + length
                    + ", recordSize=" + recordSize
            );
        }

        GpuBuffer previous = this.buffer;
        int previousPopulatedBytes = this.populatedBytes;
        GpuBuffer target = ensureCapacity(length);
        if (target != previous || previousPopulatedBytes == 0) {
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(target.slice(0, length), data);
            this.populatedBytes = length;
            return target;
        }

        int[] records = Arrays.copyOf(dirtyRecords, dirtyRecords.length);
        // Sorted records let adjacent dirty ranges share one buffer copy.
        Arrays.sort(records);
        int recordCount = length / recordSize;
        int firstRecord = -1;
        int lastRecord = -1;
        synchronizeForRewrite(target, Math.min(previousPopulatedBytes, length));
        for (int record : records) {
            if (record < 0 || record >= recordCount || record == lastRecord) {
                continue;
            }
            if (firstRecord < 0) {
                firstRecord = record;
                lastRecord = record;
            } else if (record == lastRecord + 1) {
                lastRecord = record;
            } else {
                uploadRecordRange(target, data, recordSize, firstRecord, lastRecord);
                firstRecord = record;
                lastRecord = record;
            }
        }
        if (firstRecord >= 0) {
            uploadRecordRange(target, data, recordSize, firstRecord, lastRecord);
        }
        this.populatedBytes = length;
        return target;
    }

    @Override
    public void close() {
        GpuBuffer current = this.buffer;
        if (current != null) {
            current.close();
        }
        this.buffer = null;
        this.capacityBytes = 0;
        this.populatedBytes = 0;
    }

    private ByteBuffer validateSource(ByteBuffer source) {
        ByteBuffer data = source.slice();
        int length = data.remaining();
        if (length <= 0) {
            throw new IllegalArgumentException("Device-local texel buffer source must not be empty");
        }
        if (length > this.maxCapacity) {
            throw new IllegalArgumentException(
                "Device-local texel buffer capacity exceeded: " + length + " > " + this.maxCapacity
            );
        }
        return data;
    }

    private GpuBuffer ensureCapacity(int requiredBytes) {
        GpuBuffer current = this.buffer;
        if (current != null && !current.isClosed() && this.capacityBytes >= requiredBytes) {
            return current;
        }

        int capacity = this.capacityBytes > 0 ? this.capacityBytes : this.initialCapacity;
        while (capacity < requiredBytes) {
            if (capacity > this.maxCapacity / GROWTH_FACTOR) {
                capacity = this.maxCapacity;
                break;
            }
            capacity = Math.multiplyExact(capacity, GROWTH_FACTOR);
        }
        if (capacity < requiredBytes) {
            throw new IllegalArgumentException(
                "Device-local texel buffer capacity exceeded: " + requiredBytes + " > " + this.maxCapacity
            );
        }

        if (current != null) {
            current.close();
        }
        GpuBuffer created = RenderSystem.getDevice().createBuffer(this.label, this.usage, capacity);
        this.buffer = created;
        this.capacityBytes = capacity;
        this.populatedBytes = 0;
        return created;
    }

    private static void uploadRecordRange(
        GpuBuffer target,
        ByteBuffer source,
        int recordSize,
        int firstRecord,
        int lastRecord
    ) {
        int offset = Math.multiplyExact(firstRecord, recordSize);
        int length = Math.multiplyExact(lastRecord - firstRecord + 1, recordSize);
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(
            target.slice(offset, length),
            source.slice(offset, length)
        );
    }

    private static void synchronizeForRewrite(GpuBuffer target, int overlapBytes) {
        if (overlapBytes <= 0) {
            return;
        }
        GpuDeviceBackend backend = ((GpuDeviceAccessor) RenderSystem.getDevice()).mango$getBackend();
        if (!(backend instanceof VulkanDevice vulkanDevice)) {
            return;
        }
        MangoComputePipeline.barrierBuffer(
            (MangoVulkanCommandAccess) vulkanDevice.createCommandEncoder(),
            KHRSynchronization2.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT_KHR,
            KHRSynchronization2.VK_ACCESS_2_MEMORY_READ_BIT_KHR
                | KHRSynchronization2.VK_ACCESS_2_MEMORY_WRITE_BIT_KHR,
            KHRSynchronization2.VK_PIPELINE_STAGE_2_TRANSFER_BIT_KHR,
            KHRSynchronization2.VK_ACCESS_2_TRANSFER_WRITE_BIT_KHR,
            target.slice(0, overlapBytes)
        );
    }
}
