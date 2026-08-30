package org.fruitmc.mango.render.gpu.buffer;

import java.nio.ByteBuffer;
import java.util.function.IntSupplier;

public final class GpuBufferUtils {

    private GpuBufferUtils() {
    }

    public static long alignUp(long value, long alignment) {
        long remainder = value % alignment;
        return remainder == 0L ? value : value + alignment - remainder;
    }

    public static int alignUp(int value, int alignment) {
        int remainder = value % alignment;
        return remainder == 0 ? value : value + alignment - remainder;
    }

    public static IntSupplier fixedAlignment(int alignment) {
        if (alignment <= 0) {
            throw new IllegalArgumentException("Alignment must be positive");
        }
        return () -> alignment;
    }

    public static void putColumn(ByteBuffer target, int offset, float x, float y, float z) {
        target.putFloat(offset, x);
        target.putFloat(offset + Float.BYTES, y);
        target.putFloat(offset + Float.BYTES * 2, z);
    }
}