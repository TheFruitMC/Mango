package org.fruitmc.mango.render.framegen;

import java.util.List;
import java.util.Locale;

public final class FrameGenerationDebug {

    private static final double NANOSECONDS_PER_MILLISECOND = 1_000_000.0;
    private static final long BYTES_PER_PIXEL = 9L;
    private static final long BYTES_PER_MEBIBYTE = 1_048_576L;
    private static final long TIMING_UNAVAILABLE = -1L;
    private static final long TIMING_PROFILING_DISABLED = -2L;
    private static final String GPU_TIMING_PENDING = "pending";
    private static final String GPU_TIMING_PROFILING_DISABLED = "profiling disabled";

    private static volatile Status status = Status.DISABLED;
    private static volatile int textureWidth;
    private static volatile int textureHeight;
    private static volatile long renderedFrames;
    private static volatile long generatedFrames;
    private static volatile long presentedFrames;
    private static volatile long fallbackCount;
    private static volatile long resetCount;
    private static volatile long cpuEncodeTimeNs;
    private static volatile long gpuTimeNs = TIMING_UNAVAILABLE;

    private FrameGenerationDebug() {
    }

    static void publish(
        Status newStatus,
        int width,
        int height,
        long newRenderedFrames,
        long newGeneratedFrames,
        long newPresentedFrames,
        long newFallbackCount,
        long newResetCount,
        long newCpuEncodeTimeNs,
        long newGpuTimeNs
    ) {
        textureWidth = width;
        textureHeight = height;
        renderedFrames = newRenderedFrames;
        generatedFrames = newGeneratedFrames;
        presentedFrames = newPresentedFrames;
        fallbackCount = newFallbackCount;
        resetCount = newResetCount;
        cpuEncodeTimeNs = newCpuEncodeTimeNs;
        gpuTimeNs = newGpuTimeNs;
        status = newStatus;
    }

    public static void appendDebugLines(List<String> lines) {
        Status snapshotStatus = status;
        lines.add("");
        lines.add("Mango FG: " + snapshotStatus.label);

        if (snapshotStatus == Status.DISABLED) {
            return;
        }

        int snapshotWidth = textureWidth;
        int snapshotHeight = textureHeight;
        if (snapshotWidth > 0 && snapshotHeight > 0) {
            long memoryMiB = snapshotWidth * (long) snapshotHeight * BYTES_PER_PIXEL / BYTES_PER_MEBIBYTE;
            lines.add(String.format(
                Locale.ROOT,
                "Mango FG: %d x %d, ~%d MiB",
                snapshotWidth,
                snapshotHeight,
                memoryMiB
            ));
        }

        lines.add(String.format(
            Locale.ROOT,
            "Mango FG frames: output %d = rendered %d + generated %d",
            presentedFrames,
            renderedFrames,
            generatedFrames
        ));

        long snapshotGpuTimeNs = gpuTimeNs;
        String gpuTiming;
        if (snapshotGpuTimeNs == TIMING_PROFILING_DISABLED) {
            gpuTiming = GPU_TIMING_PROFILING_DISABLED;
        } else if (snapshotGpuTimeNs >= 0L) {
            gpuTiming = String.format(Locale.ROOT, "%.3f ms", snapshotGpuTimeNs / NANOSECONDS_PER_MILLISECOND);
        } else {
            gpuTiming = GPU_TIMING_PENDING;
        }
        lines.add(String.format(
            Locale.ROOT,
            "Mango FG encode: CPU %.3f ms, GPU %s",
            cpuEncodeTimeNs / NANOSECONDS_PER_MILLISECOND,
            gpuTiming
        ));

        if (fallbackCount > 0L || resetCount > 0L) {
            lines.add(String.format(
                Locale.ROOT,
                "Mango FG safety: fallbacks %d, resets %d",
                fallbackCount,
                resetCount
            ));
        }
    }

    enum Status {
        DISABLED("disabled"),
        WARMING_UP("warming up"),
        GENERATED("generated intermediate"),
        PRESENTED_RENDERED("presented rendered frame"),
        CAMERA_CUT("camera cut fallback"),
        PAUSED("paused/no world"),
        ERROR("error fallback (see log)");

        private final String label;

        Status(String label) {
            this.label = label;
        }
    }
}
