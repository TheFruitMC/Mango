package org.fruitmc.mango.render.translucent.bsp;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntArrays;

final class BspInnerNode extends BspNode {

    private final float planeNX;
    private final float planeNY;
    private final float planeNZ;
    private final float planeDist;
    private final BspNode front;
    private final BspNode back;
    private final int[] onPlaneQuads;
    private final float[] onPlaneCentroids;
    private final int totalQuads;

    BspInnerNode(
        float planeNX, float planeNY, float planeNZ, float planeDist,
        BspNode front, BspNode back, int[] onPlaneQuads, float[] onPlaneCentroids
    ) {
        this.planeNX = planeNX;
        this.planeNY = planeNY;
        this.planeNZ = planeNZ;
        this.planeDist = planeDist;
        this.front = front;
        this.back = back;
        this.onPlaneQuads = onPlaneQuads;
        this.onPlaneCentroids = onPlaneCentroids;
        int count = onPlaneQuads.length;
        count += front.quadCount();
        count += back.quadCount();
        this.totalQuads = count;
    }

    @Override
    public void collectSortedQuads(
        IntArrayList output, float camX, float camY, float camZ, BspSortScratch scratch
    ) {
        float cameraDist = this.planeNX * camX + this.planeNY * camY + this.planeNZ * camZ - this.planeDist;
        if (cameraDist >= 0.0F) {
            this.back.collectSortedQuads(output, camX, camY, camZ, scratch);
            this.collectSortedOnPlaneQuads(output, camX, camY, camZ, scratch);
            this.front.collectSortedQuads(output, camX, camY, camZ, scratch);
        } else {
            this.front.collectSortedQuads(output, camX, camY, camZ, scratch);
            this.collectSortedOnPlaneQuads(output, camX, camY, camZ, scratch);
            this.back.collectSortedQuads(output, camX, camY, camZ, scratch);
        }
    }

    private void collectSortedOnPlaneQuads(
        IntArrayList output, float camX, float camY, float camZ, BspSortScratch scratch
    ) {
        int n = this.onPlaneQuads.length;
        if (n == 0) {
            return;
        }
        if (n == 1) {
            output.add(this.onPlaneQuads[0]);
            return;
        }

        scratch.ensureCapacity(n);
        float[] keys = scratch.keys();
        int[] order = scratch.order();
        float[] centroids = this.onPlaneCentroids;
        for (int i = 0; i < n; i++) {
            order[i] = i;
            int base = i * 3;
            float dx = centroids[base] - camX;
            float dy = centroids[base + 1] - camY;
            float dz = centroids[base + 2] - camZ;
            keys[i] = dx * dx + dy * dy + dz * dz;
        }
        IntArrays.mergeSort(order, 0, n, (a, b) -> Float.compare(keys[b], keys[a]));
        for (int i = 0; i < n; i++) {
            output.add(this.onPlaneQuads[order[i]]);
        }
    }

    @Override
    public int quadCount() {
        return this.totalQuads;
    }

    public float planeNX() { return this.planeNX; }
    public float planeNY() { return this.planeNY; }
    public float planeNZ() { return this.planeNZ; }
    public float planeDist() { return this.planeDist; }
    public BspNode front() { return this.front; }
    public BspNode back() { return this.back; }
    public int[] onPlaneQuads() { return this.onPlaneQuads; }
}
