package org.fruitmc.mango.render.gpu.skinning;

import net.minecraft.client.model.geom.ModelPart;
import org.fruitmc.mango.mixin.accessor.ModelPartAccessor;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;

public final class BoneIndexMap {

    private static final IdentityHashMap<ModelPart, BoneIndexMap> CACHE = new IdentityHashMap<>();
    private static final int MATRIX_FLOATS = 16;
    private static final int NO_BONE = -1;
    private static final int NO_PARENT = -1;
    private static final float MODEL_UNIT_SCALE = 1.0F / 16.0F;
    private static final float ZERO_TRANSFORM = 0.0F;

    private final IdentityHashMap<ModelPart, Integer> indices;
    private final ModelPart[] parts;
    private final int[] parentIndices;
    private final int[] boneIndices;
    private final Matrix4f[] matrixScratch;
    private final boolean[] visibleScratch;
    private final int boneCount;

    private BoneIndexMap(
        IdentityHashMap<ModelPart, Integer> indices,
        ModelPart[] parts,
        int[] parentIndices,
        int[] boneIndices,
        int boneCount
    ) {
        this.indices = indices;
        this.parts = parts;
        this.parentIndices = parentIndices;
        this.boneIndices = boneIndices;
        this.matrixScratch = new Matrix4f[parts.length];
        this.visibleScratch = new boolean[parts.length];
        for (int index = 0; index < this.matrixScratch.length; index++) {
            this.matrixScratch[index] = new Matrix4f();
        }
        this.boneCount = boneCount;
    }

    public static BoneIndexMap forRoot(ModelPart root) {
        BoneIndexMap cached = CACHE.get(root);
        if (cached != null) {
            return cached;
        }
        BoneIndexMap built = build(root);
        CACHE.put(root, built);
        return built;
    }

    public static void clearCache() {
        CACHE.clear();
    }

    private static BoneIndexMap build(ModelPart root) {
        List<TopologyNode> nodes = new ArrayList<>();
        appendTopology(root, NO_PARENT, nodes);

        int nodeCount = nodes.size();
        IdentityHashMap<ModelPart, Integer> map = new IdentityHashMap<>(nodeCount);
        ModelPart[] parts = new ModelPart[nodeCount];
        int[] parentIndices = new int[nodeCount];
        int[] boneIndices = new int[nodeCount];
        Arrays.fill(boneIndices, NO_BONE);

        int boneIndex = 0;
        for (int nodeIndex = 0; nodeIndex < nodeCount; nodeIndex++) {
            TopologyNode node = nodes.get(nodeIndex);
            ModelPart part = node.part();
            parts[nodeIndex] = part;
            parentIndices[nodeIndex] = node.parentIndex();
            if (!part.isEmpty()) {
                map.put(part, boneIndex);
                boneIndices[nodeIndex] = boneIndex;
                boneIndex++;
            }
        }

        return new BoneIndexMap(map, parts, parentIndices, boneIndices, boneIndex);
    }

    private static void appendTopology(ModelPart part, int parentIndex, List<TopologyNode> nodes) {
        int nodeIndex = nodes.size();
        nodes.add(new TopologyNode(part, parentIndex));
        ModelPartAccessor accessor = (ModelPartAccessor)(Object)part;
        for (ModelPart child : accessor.mango$children().values()) {
            appendTopology(child, nodeIndex, nodes);
        }
    }

    public int indexOf(ModelPart part) {
        Integer index = this.indices.get(part);
        return index == null ? -1 : index;
    }

    public int boneCount() {
        return this.boneCount;
    }

    public void writeMatrices(float[] target) {
        int requiredFloats = Math.multiplyExact(this.boneCount, MATRIX_FLOATS);
        if (target.length < requiredFloats) {
            throw new IllegalArgumentException(
                "Bone matrix target too small: " + target.length + " < " + requiredFloats
            );
        }
        for (int nodeIndex = 0; nodeIndex < this.parts.length; nodeIndex++) {
            ModelPart part = this.parts[nodeIndex];
            int parentIndex = this.parentIndices[nodeIndex];
            boolean isVisible = part.visible
                && (parentIndex == NO_PARENT || this.visibleScratch[parentIndex]);
            this.visibleScratch[nodeIndex] = isVisible;
            Matrix4f transform = this.matrixScratch[nodeIndex];
            if (!isVisible) {
                transform.identity();
                continue;
            }

            if (parentIndex == NO_PARENT) {
                transform.identity();
            } else {
                transform.set(this.matrixScratch[parentIndex]);
            }
            transform.translate(
                part.x * MODEL_UNIT_SCALE,
                part.y * MODEL_UNIT_SCALE,
                part.z * MODEL_UNIT_SCALE
            );
            if (part.xRot != ZERO_TRANSFORM || part.yRot != ZERO_TRANSFORM || part.zRot != ZERO_TRANSFORM) {
                transform.rotateZYX(part.zRot, part.yRot, part.xRot);
            }
            if (part.xScale != ModelPart.DEFAULT_SCALE
                    || part.yScale != ModelPart.DEFAULT_SCALE
                    || part.zScale != ModelPart.DEFAULT_SCALE) {
                transform.scale(part.xScale, part.yScale, part.zScale);
            }

            int boneIndex = this.boneIndices[nodeIndex];
            if (boneIndex != NO_BONE) {
                int targetOffset = Math.multiplyExact(boneIndex, MATRIX_FLOATS);
                if (isVisible && !part.skipDraw) {
                    transform.get(target, targetOffset);
                } else {
                    clearMatrix(target, targetOffset);
                }
            }
        }
    }

    private static void clearMatrix(float[] target, int offset) {
        Arrays.fill(target, offset, offset + MATRIX_FLOATS, ZERO_TRANSFORM);
    }

    private record TopologyNode(ModelPart part, int parentIndex) {
    }
}
