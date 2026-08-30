package org.fruitmc.mango.render.translucent.bsp;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntArrays;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

import java.util.List;

final class BspBuilder {

    public static final int MAX_DEPTH = 48;

    public static final int UNALIGNED_CANDIDATE_LIMIT = 256;

    private static final int ALIGNED_THIN_CANDIDATE_LIMIT = 256;
    private static final int AXIS_X = 0;
    private static final int AXIS_Y = 1;
    private static final int AXIS_Z = 2;
    private static final int AXIS_COUNT = 3;
    private static final int INTERVAL_START = 0;
    private static final int INTERVAL_END = 1;

    private static final ThreadLocal<BuildScratch> BUILD_SCRATCH = ThreadLocal.withInitial(BuildScratch::new);

    private static final class BuildScratch {
        final BspSortScratch sortScratch = new BspSortScratch();
        final IntArrayList before = new IntArrayList(64);
        final IntArrayList after = new IntArrayList(64);
        final IntArrayList onPlane = new IntArrayList(64);
        final IntOpenHashSet attemptedPlanes = new IntOpenHashSet(64);
        final float[] testedNormals = new float[UNALIGNED_CANDIDATE_LIMIT * 3];
    }

    private BspBuilder() {
    }

    static BspNode build(List<TranslucentQuad> quads) {
        BspStats.totalBuilds++;
        if (quads.isEmpty()) {
            return new BspLeafNode(new int[0]);
        }
        int[] indices = new int[quads.size()];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = i;
        }
        return buildRecursive(quads, indices, 0, BUILD_SCRATCH.get());
    }

    private static BspNode buildRecursive(
        List<TranslucentQuad> quads, int[] indices, int depth, BuildScratch scratch
    ) {
        if (indices.length == 0) {
            return new BspLeafNode(indices);
        }
        if (indices.length == 1) {
            return new BspLeafNode(indices);
        }
        if (depth >= MAX_DEPTH) {
            return createDynamicLeaf(quads, indices);
        }

        PartitionResult aligned = tryAlignedPartition(quads, indices, depth, scratch);
        if (aligned != null) {
            BspStats.alignedPartitions++;
            return buildInnerNode(quads, aligned, depth, scratch);
        }

        if (indices.length <= UNALIGNED_CANDIDATE_LIMIT) {
            PartitionResult unaligned = tryUnalignedPartition(quads, indices, scratch);
            if (unaligned != null) {
                BspStats.unalignedPartitions++;
                return buildInnerNode(quads, unaligned, depth, scratch);
            }
        }

        BspStats.dynamicLeafFallbacks++;
        return createDynamicLeaf(quads, indices);
    }

    private static BspNode buildInnerNode(
        List<TranslucentQuad> quads, PartitionResult partition, int depth, BuildScratch scratch
    ) {
        BspNode front = buildRecursive(quads, partition.front, depth + 1, scratch);
        BspNode back = buildRecursive(quads, partition.back, depth + 1, scratch);
        float[] onPlaneCentroids = extractOnPlaneCentroids(quads, partition.onPlane);
        return new BspInnerNode(
            partition.planeNX, partition.planeNY, partition.planeNZ, partition.planeDist,
            front, back, partition.onPlane, onPlaneCentroids
        );
    }

    private static float[] extractOnPlaneCentroids(List<TranslucentQuad> quads, int[] onPlane) {
        int n = onPlane.length;
        float[] centroids = new float[n * 3];
        for (int i = 0; i < n; i++) {
            TranslucentQuad q = quads.get(onPlane[i]);
            int base = i * 3;
            centroids[base] = q.centerX();
            centroids[base + 1] = q.centerY();
            centroids[base + 2] = q.centerZ();
        }
        return centroids;
    }

    private static PartitionResult tryAlignedPartition(
        List<TranslucentQuad> quads, int[] indices, int depth, BuildScratch scratch
    ) {
        PartitionResult best = null;
        int bestBalance = Integer.MAX_VALUE;

        for (int axisOffset = 0; axisOffset < AXIS_COUNT; axisOffset++) {
            int axis = (axisOffset + depth + 1) % AXIS_COUNT;
            PartitionResult candidate = tryAlignedAxis(quads, indices, axis, scratch);
            if (candidate != null) {
                int balance = Math.abs(candidate.front.length - candidate.back.length);
                if (balance < bestBalance) {
                    bestBalance = balance;
                    best = candidate;
                }
            }
        }
        return best;
    }

    private static PartitionResult tryAlignedAxis(
        List<TranslucentQuad> quads, int[] indices, int axis, BuildScratch scratch
    ) {
        int n = indices.length;
        BspSortScratch sortScratch = scratch.sortScratch;
        sortScratch.ensureCapacity(n);
        sortScratch.ensureIntervalCapacity(n * 2);
        float[] minValues = sortScratch.minExtents();
        float[] maxValues = sortScratch.maxExtents();
        for (int i = 0; i < n; i++) {
            TranslucentQuad quad = quads.get(indices[i]);
            minValues[i] = quad.minExtent(axis);
            maxValues[i] = quad.maxExtent(axis);
        }

        float[] sortKeys = sortScratch.intervalKeys();
        int[] sortTypes = sortScratch.intervalTypes();
        for (int i = 0; i < n; i++) {
            sortKeys[i * 2] = minValues[i];
            sortTypes[i * 2] = INTERVAL_START;
            sortKeys[i * 2 + 1] = maxValues[i];
            sortTypes[i * 2 + 1] = INTERVAL_END;
        }
        sortIntervalPoints(sortKeys, sortTypes, n * 2, sortScratch);

        float planeNX = axis == AXIS_X ? 1.0F : 0.0F;
        float planeNY = axis == AXIS_Y ? 1.0F : 0.0F;
        float planeNZ = axis == AXIS_Z ? 1.0F : 0.0F;

        IntArrayList before = scratch.before;
        IntArrayList after = scratch.after;
        IntArrayList onPlane = scratch.onPlane;

        int activeCount = 0;
        int endedCount = 0;
        boolean hasLastEnd = false;
        float lastEnd = 0.0F;
        boolean hasPartition = false;
        float bestPartitionDist = 0.0F;
        int bestBalance = Integer.MAX_VALUE;

        for (int i = 0; i < n * 2; i++) {
            int type = sortTypes[i];

            if (type == INTERVAL_END) {
                activeCount--;
                endedCount++;
                if (activeCount == 0) {
                    hasLastEnd = true;
                    lastEnd = sortKeys[i];
                }
            } else {
                if (activeCount == 0 && hasLastEnd) {
                    float gap = sortKeys[i] - lastEnd;
                    if (gap > TranslucentQuad.VERTEX_EPSILON) {
                        int balance = Math.abs(endedCount - (n - endedCount));
                        if (balance < bestBalance) {
                            bestBalance = balance;
                            bestPartitionDist = lastEnd + gap * 0.5F;
                            hasPartition = true;
                        }
                    }
                }
                activeCount++;
            }
        }

        if (hasPartition) {
            return classifyByAxis(quads, indices, axis, bestPartitionDist,
                planeNX, planeNY, planeNZ, before, after, onPlane);
        }

        IntOpenHashSet attemptedPlanes = scratch.attemptedPlanes;
        attemptedPlanes.clear();
        int candidateCount = 0;
        for (int i = 0; i < n; i++) {
            float thickness = maxValues[i] - minValues[i];
            if (Math.abs(thickness) < TranslucentQuad.VERTEX_EPSILON) {
                float partitionDist = minValues[i] == 0.0F ? 0.0F : minValues[i];
                if (!attemptedPlanes.add(Float.floatToIntBits(partitionDist))) {
                    continue;
                }
                if (candidateCount++ >= ALIGNED_THIN_CANDIDATE_LIMIT) {
                    break;
                }
                PartitionResult candidate = classifyByAxis(quads, indices, axis, partitionDist,
                    planeNX, planeNY, planeNZ, before, after, onPlane);
                if (candidate != null) {
                    return candidate;
                }
            }
        }

        return null;
    }

    private static PartitionResult classifyByAxis(
        List<TranslucentQuad> quads, int[] indices, int axis, float partitionDist,
        float planeNX, float planeNY, float planeNZ,
        IntArrayList before, IntArrayList after, IntArrayList onPlane
    ) {
        before.clear();
        after.clear();
        onPlane.clear();

        for (int index : indices) {
            TranslucentQuad quad = quads.get(index);
            float min = quad.minExtent(axis);
            float max = quad.maxExtent(axis);
            float thickness = max - min;
            if (max < partitionDist - TranslucentQuad.VERTEX_EPSILON) {
                before.add(index);
            } else if (min > partitionDist + TranslucentQuad.VERTEX_EPSILON) {
                after.add(index);
            } else if (thickness < TranslucentQuad.VERTEX_EPSILON) {
                onPlane.add(index);
            } else {
                onPlane.add(index);
                BspStats.intersectingRedirected++;
            }
        }

        if (before.isEmpty() && after.isEmpty()) {
            return null;
        }
        if (before.size() == indices.length || after.size() == indices.length) {
            return null;
        }

        return new PartitionResult(
            planeNX, planeNY, planeNZ, partitionDist,
            after.toIntArray(), before.toIntArray(), onPlane.toIntArray()
        );
    }

    private static PartitionResult tryUnalignedPartition(
        List<TranslucentQuad> quads, int[] indices, BuildScratch scratch
    ) {
        PartitionResult best = null;
        int bestScore = Integer.MAX_VALUE;

        float[] testedNormals = scratch.testedNormals;
        int testedCount = 0;

        for (int index : indices) {
            TranslucentQuad candidate = quads.get(index);
            float pnx = candidate.normalX();
            float pny = candidate.normalY();
            float pnz = candidate.normalZ();

            boolean alreadyTested = false;
            for (int j = 0; j < testedCount; j++) {
                int base = j * 3;
                if (Math.abs(testedNormals[base] - pnx) < TranslucentQuad.NORMAL_EPSILON
                        && Math.abs(testedNormals[base + 1] - pny) < TranslucentQuad.NORMAL_EPSILON
                        && Math.abs(testedNormals[base + 2] - pnz) < TranslucentQuad.NORMAL_EPSILON) {
                    alreadyTested = true;
                    break;
                }
            }
            if (alreadyTested) {
                continue;
            }
            testedNormals[testedCount * 3] = pnx;
            testedNormals[testedCount * 3 + 1] = pny;
            testedNormals[testedCount * 3 + 2] = pnz;
            testedCount++;

            PartitionResult partition = partitionByPlane(quads, indices, pnx, pny, pnz, candidate.dotProduct(), scratch);
            if (partition == null) {
                continue;
            }

            int balance = Math.abs(partition.front.length - partition.back.length);
            int score = balance * 2 + partition.onPlane.length;
            if (score < bestScore) {
                bestScore = score;
                best = partition;
                if (balance == 0 && partition.onPlane.length == 1) {
                    return best;
                }
            }
        }

        return best;
    }

    private static PartitionResult partitionByPlane(
        List<TranslucentQuad> quads, int[] indices,
        float pnx, float pny, float pnz, float pdist,
        BuildScratch scratch
    ) {
        IntArrayList front = scratch.before;
        IntArrayList back = scratch.after;
        IntArrayList onPlane = scratch.onPlane;
        front.clear();
        back.clear();
        onPlane.clear();

        for (int index : indices) {
            int c = quads.get(index).classifyToPlane(pnx, pny, pnz, pdist);
            switch (c) {
                case 1 -> front.add(index);
                case -1 -> back.add(index);
                case 0 -> onPlane.add(index);
                default -> {
                    onPlane.add(index);
                    BspStats.intersectingRedirected++;
                }
            }
        }

        if (onPlane.isEmpty() || front.isEmpty() && back.isEmpty()) {
            return null;
        }

        return new PartitionResult(
            pnx, pny, pnz, pdist,
            front.toIntArray(), back.toIntArray(), onPlane.toIntArray()
        );
    }

    private static BspNode createDynamicLeaf(List<TranslucentQuad> quads, int[] indices) {
        int n = indices.length;
        float[] centroids = new float[n * 3];
        for (int i = 0; i < n; i++) {
            TranslucentQuad q = quads.get(indices[i]);
            int base = i * 3;
            centroids[base] = q.centerX();
            centroids[base + 1] = q.centerY();
            centroids[base + 2] = q.centerZ();
        }
        return new BspDynamicLeafNode(indices, centroids);
    }

    private static void sortIntervalPoints(
        float[] keys, int[] types, int count, BspSortScratch scratch
    ) {
        scratch.ensureCapacity(count);
        int[] order = scratch.order();
        for (int i = 0; i < count; i++) {
            order[i] = i;
        }
        IntArrays.quickSort(order, (a, b) -> {
            int cmp = Float.compare(keys[a], keys[b]);
            if (cmp != 0) return cmp;
            return Integer.compare(types[a], types[b]);
        });
        float[] tempKeys = scratch.keys();
        int[] tempTypes = scratch.types();
        for (int i = 0; i < count; i++) {
            tempKeys[i] = keys[order[i]];
            tempTypes[i] = types[order[i]];
        }
        System.arraycopy(tempKeys, 0, keys, 0, count);
        System.arraycopy(tempTypes, 0, types, 0, count);
    }

    private static final class PartitionResult {
        final float planeNX;
        final float planeNY;
        final float planeNZ;
        final float planeDist;
        final int[] front;
        final int[] back;
        final int[] onPlane;

        PartitionResult(
            float planeNX, float planeNY, float planeNZ, float planeDist,
            int[] front, int[] back, int[] onPlane
        ) {
            this.planeNX = planeNX;
            this.planeNY = planeNY;
            this.planeNZ = planeNZ;
            this.planeDist = planeDist;
            this.front = front;
            this.back = back;
            this.onPlane = onPlane;
        }
    }
}
