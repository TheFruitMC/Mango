package org.fruitmc.mango.render.gpu.buffer;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import org.fruitmc.mango.mixin.accessor.GpuDeviceAccessor;
import org.fruitmc.mango.render.vulkan.compute.MangoComputePipeline;
import org.fruitmc.mango.render.vulkan.compute.MangoVulkanCommandAccess;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.vulkan.KHRSynchronization2;

import java.nio.ByteBuffer;
import java.util.function.Supplier;

/**
 * A persistent device-local buffer which is re-uploaded only when its logical contents change.
 */
public final class PersistentBufferUploader implements AutoCloseable {

    private static final int GROWTH_FACTOR = 2;
    private static final int MIN_CAPACITY_BYTES = 256;

    private final Supplier<String> label;
    private final int usage;

    @Nullable private GpuBuffer buffer;
    private int capacityBytes;
    private int populatedBytes;

    public PersistentBufferUploader(Supplier<String> label, @GpuBuffer.Usage int usage) {
        this.label = label;
        this.usage = usage | GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST;
    }

    public GpuBufferSlice upload(ByteBuffer source) {
        ByteBuffer data = source.slice();
        int length = data.remaining();
        if (length <= 0) {
            throw new IllegalArgumentException("Persistent buffer source must not be empty");
        }

        GpuBuffer previous = this.buffer;
        int previousPopulatedBytes = this.populatedBytes;
        GpuBuffer target = ensureCapacity(length);
        if (target == previous && previousPopulatedBytes > 0) {
            synchronizeForRewrite(target, Math.min(previousPopulatedBytes, length));
        }
        GpuBufferSlice destination = target.slice(0, length);
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(destination, data);
        this.populatedBytes = length;
        return destination;
    }

    public GpuBufferSlice slice() {
        GpuBuffer current = this.buffer;
        if (current == null || current.isClosed() || this.populatedBytes == 0) {
            throw new IllegalStateException("Persistent buffer has no uploaded contents");
        }
        return current.slice(0, this.populatedBytes);
    }

    public boolean hasBuffer() {
        GpuBuffer current = this.buffer;
        return current != null && !current.isClosed() && this.populatedBytes > 0;
    }

    @Override
    public void close() {
        if (this.buffer != null) {
            this.buffer.close();
        }
        this.buffer = null;
        this.capacityBytes = 0;
        this.populatedBytes = 0;
    }

    private GpuBuffer ensureCapacity(int requiredBytes) {
        GpuBuffer current = this.buffer;
        if (current != null && !current.isClosed() && this.capacityBytes >= requiredBytes) {
            return current;
        }

        int capacity = Math.max(this.capacityBytes, MIN_CAPACITY_BYTES);
        while (capacity < requiredBytes) {
            capacity = Math.multiplyExact(capacity, GROWTH_FACTOR);
        }

        if (current != null) {
            current.close();
        }
        this.buffer = null;
        this.capacityBytes = 0;
        this.populatedBytes = 0;
        GpuBuffer created = RenderSystem.getDevice().createBuffer(this.label, this.usage, capacity);
        this.buffer = created;
        this.capacityBytes = capacity;
        this.populatedBytes = 0;
        return created;
    }

    private static void synchronizeForRewrite(GpuBuffer target, int overlapBytes) {
        // A prior frame may still be reading the old contents before this transfer overwrites them.
        GpuDeviceBackend backend = ((GpuDeviceAccessor)(Object)RenderSystem.getDevice()).mango$getBackend();
        if (!(backend instanceof VulkanDevice vulkanDevice)) {
            return;
        }
        MangoComputePipeline.barrierBuffer(
            (MangoVulkanCommandAccess)(Object)vulkanDevice.createCommandEncoder(),
            KHRSynchronization2.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT_KHR,
            KHRSynchronization2.VK_ACCESS_2_MEMORY_READ_BIT_KHR
                | KHRSynchronization2.VK_ACCESS_2_MEMORY_WRITE_BIT_KHR,
            KHRSynchronization2.VK_PIPELINE_STAGE_2_TRANSFER_BIT_KHR,
            KHRSynchronization2.VK_ACCESS_2_TRANSFER_WRITE_BIT_KHR,
            target.slice(0, overlapBytes)
        );
    }
}
