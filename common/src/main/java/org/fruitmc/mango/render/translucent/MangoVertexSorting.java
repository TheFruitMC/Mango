package org.fruitmc.mango.render.translucent;

import com.mojang.blaze3d.vertex.CompactVectorArray;
import com.mojang.blaze3d.vertex.VertexSorting;
import it.unimi.dsi.fastutil.ints.IntArrays;
import org.fruitmc.mango.render.translucent.bsp.BspSortData;
import org.joml.Vector3f;

public final class MangoVertexSorting implements VertexSorting {

    private final float camX;
    private final float camY;
    private final float camZ;
    private BspSortData bspSortData = BspSortData.empty();

    private final Vector3f positionScratch = new Vector3f();
    private float[] distanceKeys = new float[16];
    private int[] distanceIndices = new int[16];

    public MangoVertexSorting(float camX, float camY, float camZ) {
        this.camX = camX;
        this.camY = camY;
        this.camZ = camZ;
    }

    public void setBspSortData(BspSortData bspSortData) {
        this.bspSortData = bspSortData;
    }

    @Override
    public int[] sort(CompactVectorArray points) {
        if (this.bspSortData.hasTree()) {
            int[] sortedIndices = this.bspSortData.sortSortedIndices(this.camX, this.camY, this.camZ);
            if (hasValidIndexRange(sortedIndices, points.size())) {
                return sortedIndices;
            }
        }
        return distanceSort(points);
    }

    private static boolean hasValidIndexRange(int[] indices, int expectedSize) {
        if (indices.length != expectedSize) {
            return false;
        }
        for (int index : indices) {
            if (index < 0 || index >= expectedSize) {
                return false;
            }
        }
        return true;
    }

    private int[] distanceSort(CompactVectorArray points) {
        Vector3f scratch = this.positionScratch;
        int size = points.size();
        ensureDistanceCapacity(size);
        float[] keys = this.distanceKeys;
        int[] indices = this.distanceIndices;
        for (int i = 0; i < size; i++) {
            indices[i] = i;
            points.get(i, scratch);
            float dx = scratch.x - this.camX;
            float dy = scratch.y - this.camY;
            float dz = scratch.z - this.camZ;
            keys[i] = dx * dx + dy * dy + dz * dz;
        }
        IntArrays.mergeSort(indices, 0, size, (a, b) -> Float.compare(keys[b], keys[a]));
        if (indices.length == size) {
            return indices;
        }
        int[] result = new int[size];
        System.arraycopy(indices, 0, result, 0, size);
        return result;
    }

    private void ensureDistanceCapacity(int capacity) {
        if (this.distanceKeys.length < capacity) {
            int newSize = this.distanceKeys.length;
            while (newSize < capacity) {
                newSize <<= 1;
            }
            this.distanceKeys = new float[newSize];
            this.distanceIndices = new int[newSize];
        }
    }
}
