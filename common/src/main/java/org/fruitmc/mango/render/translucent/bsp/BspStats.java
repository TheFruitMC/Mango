package org.fruitmc.mango.render.translucent.bsp;

public final class BspStats {

    public static long alignedPartitions;
    public static long unalignedPartitions;
    public static long dynamicLeafFallbacks;
    public static long intersectingRedirected;
    public static long totalBuilds;

    private BspStats() {
    }

    public static Snapshot snapshot() {
        return new Snapshot(
            alignedPartitions, unalignedPartitions, dynamicLeafFallbacks,
            intersectingRedirected, totalBuilds
        );
    }

    public static void reset() {
        alignedPartitions = 0L;
        unalignedPartitions = 0L;
        dynamicLeafFallbacks = 0L;
        intersectingRedirected = 0L;
        totalBuilds = 0L;
    }

    public record Snapshot(
        long alignedPartitions,
        long unalignedPartitions,
        long dynamicLeafFallbacks,
        long intersectingRedirected,
        long totalBuilds
    ) {
        public double fallbackRate() {
            return totalBuilds == 0L ? 0.0 : (double) dynamicLeafFallbacks / (double) totalBuilds;
        }

        @Override
        public String toString() {
            return String.format(
                "BSP stats: builds=%d, aligned=%d, unaligned=%d, dynamicLeaf=%d (%.1f%%), intersectingRedirected=%d",
                totalBuilds, alignedPartitions, unalignedPartitions,
                dynamicLeafFallbacks, fallbackRate() * 100.0, intersectingRedirected
            );
        }
    }
}
