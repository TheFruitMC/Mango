package org.fruitmc.mango.render.gpu.policy;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import org.fruitmc.mango.render.gpu.buffer.RingBufferUploader;

import java.nio.ByteBuffer;

public final class DynamicUploadPolicy {

    private DynamicUploadPolicy() {
    }

    public static void beginFrame(RingBufferUploader uploader, int requiredBytes, int sliceCount) {
        uploader.beginFrame(requiredBytes, sliceCount);
    }

    public static void endFrame(RingBufferUploader uploader) {
        uploader.endFrame();
    }

    public static GpuBufferSlice uploadSlice(RingBufferUploader uploader, ByteBuffer source) {
        return uploader.upload(source);
    }

    public static RingBufferUploader.MappedSlice allocateMapped(RingBufferUploader uploader, int length) {
        return uploader.allocateMapped(length);
    }

    public static GpuBufferSlice allocateSlice(RingBufferUploader uploader, int length) {
        return uploader.allocateSlice(length);
    }
}
