package org.fruitmc.mango.render.translucent.bsp;

import it.unimi.dsi.fastutil.ints.IntArrayList;

abstract class BspNode {
    public abstract void collectSortedQuads(
        IntArrayList output, float camX, float camY, float camZ, BspSortScratch scratch
    );

    public abstract int quadCount();
}
