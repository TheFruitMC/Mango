package org.fruitmc.mango.render.translucent.bsp;

import it.unimi.dsi.fastutil.ints.IntArrayList;

final class BspLeafNode extends BspNode {

    private final int[] quadIndices;

    BspLeafNode(int[] quadIndices) {
        this.quadIndices = quadIndices;
    }

    @Override
    public void collectSortedQuads(
        IntArrayList output, float camX, float camY, float camZ, BspSortScratch scratch
    ) {
        output.addElements(output.size(), this.quadIndices);
    }

    @Override
    public int quadCount() {
        return this.quadIndices.length;
    }

    int[] quadIndices() {
        return this.quadIndices;
    }
}
