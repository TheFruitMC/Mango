package org.fruitmc.mango.render.translucent.bsp;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntArrays;

final class BspDynamicLeafNode extends BspNode {

    private final int[] quadIndices;
    private final float[] centroids;

    BspDynamicLeafNode(int[] quadIndices, float[] centroids) {
        this.quadIndices = quadIndices;
        this.centroids = centroids;
    }

    @Override
    public void collectSortedQuads(
        IntArrayList output, float camX, float camY, float camZ, BspSortScratch scratch
    ) {
        int n = this.quadIndices.length;
        if (n == 0) {
            return;
        }
        if (n == 1) {
            output.add(this.quadIndices[0]);
            return;
        }

        scratch.ensureCapacity(n);
        float[] keys = scratch.keys();
        int[] order = scratch.order();
        float[] centroids = this.centroids;
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
            output.add(this.quadIndices[order[i]]);
        }
    }

    @Override
    public int quadCount() {
        return this.quadIndices.length;
    }
}
