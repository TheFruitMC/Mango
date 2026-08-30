package org.fruitmc.mango.render.translucent.bsp;

import it.unimi.dsi.fastutil.ints.IntArrayList;

import java.util.List;

public final class BspSortData {

    private static final BspNode EMPTY_ROOT = new BspLeafNode(new int[0]);
    private static final BspSortData EMPTY = new BspSortData(EMPTY_ROOT, 0, false);

    private static final ThreadLocal<TraversalBuffers> TRAVERSAL_BUFFERS =
        ThreadLocal.withInitial(TraversalBuffers::new);

    private final BspNode rootNode;
    private final int quadCount;
    private final boolean hasTree;

    private BspSortData(BspNode rootNode, int quadCount, boolean hasTree) {
        this.rootNode = rootNode;
        this.quadCount = quadCount;
        this.hasTree = hasTree;
    }

    public static BspSortData empty() {
        return EMPTY;
    }

    static BspSortData fromQuads(List<TranslucentQuad> quads) {
        if (quads.isEmpty()) {
            return EMPTY;
        }
        BspNode root = BspBuilder.build(quads);
        boolean valid = hasValidMembership(root, quads.size());
        return new BspSortData(valid ? root : EMPTY_ROOT, quads.size(), valid);
    }

    private static boolean hasValidMembership(BspNode root, int quadCount) {
        if (root.quadCount() != quadCount) {
            return false;
        }
        TraversalBuffers buffers = TRAVERSAL_BUFFERS.get();
        IntArrayList output = buffers.output;
        output.clear();
        root.collectSortedQuads(output, 0.0F, 0.0F, 0.0F, buffers.scratch);
        if (output.size() != quadCount) {
            return false;
        }
        boolean[] seen = new boolean[quadCount];
        for (int index : output) {
            if (index < 0 || index >= quadCount || seen[index]) {
                return false;
            }
            seen[index] = true;
        }
        return true;
    }

    public int[] sortSortedIndices(float camX, float camY, float camZ) {
        if (!this.hasTree) {
            return new int[0];
        }
        TraversalBuffers buffers = TRAVERSAL_BUFFERS.get();
        IntArrayList output = buffers.output;
        output.clear();
        this.rootNode.collectSortedQuads(output, camX, camY, camZ, buffers.scratch);
        if (output.size() != this.quadCount) {
            return output.toIntArray();
        }
        int[] elements = output.elements();
        return elements.length == this.quadCount ? elements : output.toIntArray();
    }

    public boolean hasTree() {
        return this.hasTree;
    }

    int quadCount() {
        return this.quadCount;
    }

    BspNode rootNode() {
        return this.rootNode;
    }

    private static final class TraversalBuffers {
        final IntArrayList output = new IntArrayList(64);
        final BspSortScratch scratch = new BspSortScratch();
    }
}
