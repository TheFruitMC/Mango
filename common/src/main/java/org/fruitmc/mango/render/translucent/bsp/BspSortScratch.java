package org.fruitmc.mango.render.translucent.bsp;

final class BspSortScratch {

    private float[] keys = new float[16];
    private int[] order = new int[16];
    private int[] types = new int[16];
    private float[] minExtents = new float[16];
    private float[] maxExtents = new float[16];
    private float[] intervalKeys = new float[32];
    private int[] intervalTypes = new int[32];

    void ensureCapacity(int n) {
        if (this.keys.length < n) {
            int newSize = this.keys.length;
            while (newSize < n) {
                newSize <<= 1;
            }
            this.keys = new float[newSize];
            this.order = new int[newSize];
            this.types = new int[newSize];
            this.minExtents = new float[newSize];
            this.maxExtents = new float[newSize];
        }
    }

    void ensureIntervalCapacity(int n) {
        if (this.intervalKeys.length < n) {
            int newSize = this.intervalKeys.length;
            while (newSize < n) {
                newSize <<= 1;
            }
            this.intervalKeys = new float[newSize];
            this.intervalTypes = new int[newSize];
        }
    }

    float[] keys() {
        return this.keys;
    }

    int[] order() {
        return this.order;
    }

    int[] types() {
        return this.types;
    }

    float[] minExtents() {
        return this.minExtents;
    }

    float[] maxExtents() {
        return this.maxExtents;
    }

    float[] intervalKeys() {
        return this.intervalKeys;
    }

    int[] intervalTypes() {
        return this.intervalTypes;
    }
}
