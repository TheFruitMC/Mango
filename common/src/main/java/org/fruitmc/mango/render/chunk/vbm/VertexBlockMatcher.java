package org.fruitmc.mango.render.chunk.vbm;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import org.fruitmc.mango.render.chunk.vertex.CompactTerrainVertex;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Optional;

public final class VertexBlockMatcher {

    private static final int VERTICES_PER_QUAD = 4;
    private static final int CORNERS_PER_QUAD = 4;
    private static final int CORNER_BITS = 2;
    private static final int MIN_SOURCE_QUADS = 64;
    private static final int MIN_SAVED_QUADS = 8;
    private static final int MIN_REDUCTION_PERCENT = 5;
    private static final int PERCENT_BASE = 100;
    private static final int MAX_SOURCE_QUADS = 16_384;
    private static final int INITIAL_WORKSPACE_QUADS = 256;

    private static final int NO_MATCH = -1;
    private static final int MATCH_SIDE_BITS = 2;
    private static final int MATCH_SIDE_MASK = (1 << MATCH_SIDE_BITS) - 1;
    private static final int MATCH_OTHER_SIDE_SHIFT = MATCH_SIDE_BITS;
    private static final int MATCH_QUAD_SHIFT = MATCH_SIDE_BITS * 2;

    private static final int AXIS_X = 0;
    private static final int AXIS_Y = 1;
    private static final int AXIS_Z = 2;
    private static final int AXIS_COUNT = 3;

    private static final int SIDE_MIN_U = 0;
    private static final int SIDE_MAX_U = 1;
    private static final int SIDE_MIN_V = 2;
    private static final int SIDE_MAX_V = 3;
    private static final int SIDE_COUNT = 4;

    private static final int CORNER_MIN_U_MIN_V = 0;
    private static final int CORNER_MAX_U_MIN_V = 1;
    private static final int CORNER_MIN_U_MAX_V = 2;
    private static final int CORNER_MAX_U_MAX_V = 3;

    private static final int UV_COMPONENT_COUNT = 2;
    private static final int UV_COEFFICIENTS_PER_COMPONENT = 3;
    private static final int UV_COEFFICIENT_COUNT = UV_COMPONENT_COUNT * UV_COEFFICIENTS_PER_COMPONENT;
    private static final int COMPACT_TEXTURE_VALUE_BITS = 15;
    private static final double COMPACT_TEXTURE_QUANTIZATION_STEP = 1.0 / (1 << COMPACT_TEXTURE_VALUE_BITS);
    private static final double COMPACT_UV_TRANSFORM_TOLERANCE = COMPACT_TEXTURE_QUANTIZATION_STEP * 2.0;

    private static final int FLOAT_BYTES = Float.BYTES;
    private static final int SHORT_BYTES = Short.BYTES;
    private static final VertexLayout BLOCK_LAYOUT = new VertexLayout(
        DefaultVertexFormat.BLOCK.getVertexSize(),
        elementOffset("Position"),
        elementOffset("Color"),
        elementOffset("UV0"),
        elementOffset("UV2"),
        false
    );
    private static final VertexLayout COMPACT_LAYOUT = new VertexLayout(
        CompactTerrainVertex.STRIDE,
        CompactTerrainVertex.POSITION_OFFSET,
        CompactTerrainVertex.COLOR_OFFSET,
        CompactTerrainVertex.TEXTURE_OFFSET,
        CompactTerrainVertex.LIGHT_OFFSET,
        true
    );

    private static final long HASH_SEED = 0x9E3779B97F4A7C15L;
    private static final long HASH_MULTIPLIER = 0xC2B2AE3D27D4EB4FL;
    private static final int HASH_SHIFT = 29;

    private static final ThreadLocal<Workspace> WORKSPACE = ThreadLocal.withInitial(Workspace::new);

    private VertexBlockMatcher() {
    }

    public static Optional<CompactedMesh> compact(MeshData source) {
        MeshData.DrawState state = source.drawState();
        VertexLayout layout = layoutFor(state);
        if (layout == null
            || state.primitiveTopology() != PrimitiveTopology.QUADS
            || source.indexBuffer() != null
            || state.vertexCount() % VERTICES_PER_QUAD != 0) {
            return Optional.empty();
        }

        int quadCount = state.vertexCount() / VERTICES_PER_QUAD;
        if (quadCount < MIN_SOURCE_QUADS || quadCount > MAX_SOURCE_QUADS) {
            return Optional.empty();
        }

        ByteBuffer vertices = source.vertexBuffer().duplicate().order(ByteOrder.nativeOrder());
        int requiredBytes = Math.multiplyExact(state.vertexCount(), layout.stride());
        if (vertices.remaining() != requiredBytes) {
            return Optional.empty();
        }

        Workspace workspace = WORKSPACE.get();
        workspace.prepare(quadCount);
        int outputQuads = buildMergePlan(vertices, quadCount, workspace, layout);
        int savedQuads = quadCount - outputQuads;
        if (savedQuads < MIN_SAVED_QUADS
            || Math.multiplyExact(savedQuads, PERCENT_BASE) < Math.multiplyExact(quadCount, MIN_REDUCTION_PERCENT)) {
            return Optional.empty();
        }

        int outputVertexCount = Math.multiplyExact(outputQuads, VERTICES_PER_QUAD);
        int outputBytes = Math.multiplyExact(outputVertexCount, layout.stride());
        ByteBufferBuilder owner = ByteBufferBuilder.exactlySized(outputBytes);
        try {
            long destination = owner.reserve(outputBytes);
            copyPlannedVertices(vertices, workspace, quadCount, destination, layout);
            ByteBufferBuilder.Result result = owner.build();
            if (result == null) {
                owner.close();
                return Optional.empty();
            }

            MeshData.DrawState compactState = new MeshData.DrawState(
                state.format(),
                outputVertexCount,
                state.primitiveTopology().indexCount(outputVertexCount),
                state.primitiveTopology(),
                IndexType.least(outputVertexCount)
            );
            return Optional.of(new CompactedMesh(new MeshData(result, compactState), owner));
        } catch (RuntimeException exception) {
            owner.close();
            throw exception;
        }
    }

    private static int buildMergePlan(
        ByteBuffer vertices,
        int quadCount,
        Workspace workspace,
        VertexLayout layout
    ) {
        int outputQuads = quadCount;
        for (int quad = 0; quad < quadCount; quad++) {
            workspace.active[quad] = true;
            workspace.mergeable[quad] = decodeQuad(vertices, quad, workspace, layout);
            workspace.merged[quad] = false;
            workspace.sourceOrder[quad] = quad;
            workspace.sourceEndOrder[quad] = quad;
        }

        workspace.edgeOwners.clear();
        for (int quad = 0; quad < quadCount; quad++) {
            if (!workspace.mergeable[quad]) {
                continue;
            }

            while (true) {
                int match = findMatch(vertices, quad, workspace, layout);
                if (match == NO_MATCH) {
                    insertEdges(quad, workspace);
                    break;
                }

                int other = match >>> MATCH_QUAD_SHIFT;
                int side = match & MATCH_SIDE_MASK;
                int otherSide = match >> MATCH_OTHER_SIDE_SHIFT & MATCH_SIDE_MASK;
                removeEdges(other, workspace);
                merge(quad, other, side, otherSide, workspace);
                workspace.active[other] = false;
                workspace.merged[quad] = true;
                outputQuads--;
            }
        }
        return outputQuads;
    }

    private static boolean decodeQuad(ByteBuffer vertices, int quad, Workspace workspace, VertexLayout layout) {
        int firstVertex = quad * VERTICES_PER_QUAD;
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;

        for (int vertex = 0; vertex < VERTICES_PER_QUAD; vertex++) {
            int offset = vertexOffset(vertices, firstVertex + vertex, layout);
            float x = normalizeZero(positionComponent(vertices, offset, 0, layout));
            float y = normalizeZero(positionComponent(vertices, offset, 1, layout));
            float z = normalizeZero(positionComponent(vertices, offset, 2, layout));
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                return false;
            }
            workspace.positionScratch[vertex * AXIS_COUNT] = x;
            workspace.positionScratch[vertex * AXIS_COUNT + 1] = y;
            workspace.positionScratch[vertex * AXIS_COUNT + 2] = z;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }

        boolean constantX = sameFloat(minX, maxX);
        boolean constantY = sameFloat(minY, maxY);
        boolean constantZ = sameFloat(minZ, maxZ);
        int constantAxes = (constantX ? 1 : 0) + (constantY ? 1 : 0) + (constantZ ? 1 : 0);
        if (constantAxes != 1) {
            return false;
        }

        int axis = constantX ? AXIS_X : constantY ? AXIS_Y : AXIS_Z;
        float minU = axis == AXIS_X ? minY : minX;
        float maxU = axis == AXIS_X ? maxY : maxX;
        float minV = axis == AXIS_Z ? minY : minZ;
        float maxV = axis == AXIS_Z ? maxY : maxZ;
        if (sameFloat(minU, maxU) || sameFloat(minV, maxV)) {
            return false;
        }

        int cornerBase = quad * CORNERS_PER_QUAD;
        int orderCode = 0;
        int seenCorners = 0;
        for (int vertex = 0; vertex < VERTICES_PER_QUAD; vertex++) {
            float x = workspace.positionScratch[vertex * AXIS_COUNT];
            float y = workspace.positionScratch[vertex * AXIS_COUNT + 1];
            float z = workspace.positionScratch[vertex * AXIS_COUNT + 2];
            float u = axis == AXIS_X ? y : x;
            float v = axis == AXIS_Z ? y : z;
            int corner = cornerOf(u, v, minU, maxU, minV, maxV);
            if (corner < 0 || (seenCorners & 1 << corner) != 0) {
                return false;
            }
            seenCorners |= 1 << corner;
            workspace.cornerSources[cornerBase + corner] = firstVertex + vertex;
            orderCode |= corner << vertex * CORNER_BITS;
        }
        if (!isCyclicOrder(orderCode)) {
            return false;
        }

        int firstOffset = vertexOffset(vertices, firstVertex, layout);
        int color = vertices.getInt(firstOffset + layout.colorOffset());
        int light = lightValue(vertices, firstOffset, layout);
        for (int vertex = 1; vertex < VERTICES_PER_QUAD; vertex++) {
            int offset = vertexOffset(vertices, firstVertex + vertex, layout);
            if (vertices.getInt(offset + layout.colorOffset()) != color
                || lightValue(vertices, offset, layout) != light) {
                return false;
            }
        }

        workspace.axis[quad] = (byte) axis;
        workspace.plane[quad] = axis == AXIS_X ? minX : axis == AXIS_Y ? minY : minZ;
        workspace.minU[quad] = minU;
        workspace.maxU[quad] = maxU;
        workspace.minV[quad] = minV;
        workspace.maxV[quad] = maxV;
        workspace.orderCode[quad] = orderCode;
        workspace.color[quad] = color;
        workspace.light[quad] = light;

        if (!decodeUvTransform(vertices, quad, workspace, layout)) {
            return false;
        }
        workspace.signature[quad] = signatureOf(quad, workspace, layout);
        return true;
    }

    private static boolean decodeUvTransform(
        ByteBuffer vertices,
        int quad,
        Workspace workspace,
        VertexLayout layout
    ) {
        int cornerBase = quad * CORNERS_PER_QUAD;
        int ll = vertexOffset(vertices, workspace.cornerSources[cornerBase + CORNER_MIN_U_MIN_V], layout);
        int hl = vertexOffset(vertices, workspace.cornerSources[cornerBase + CORNER_MAX_U_MIN_V], layout);
        int lh = vertexOffset(vertices, workspace.cornerSources[cornerBase + CORNER_MIN_U_MAX_V], layout);
        int hh = vertexOffset(vertices, workspace.cornerSources[cornerBase + CORNER_MAX_U_MAX_V], layout);

        double minU = workspace.minU[quad];
        double minV = workspace.minV[quad];
        double sizeU = workspace.maxU[quad] - minU;
        double sizeV = workspace.maxV[quad] - minV;
        int coefficientBase = quad * UV_COEFFICIENT_COUNT;
        for (int component = 0; component < UV_COMPONENT_COUNT; component++) {
            double uvLl = textureComponent(vertices, ll, component, layout);
            double uvHl = textureComponent(vertices, hl, component, layout);
            double uvLh = textureComponent(vertices, lh, component, layout);
            double uvHh = textureComponent(vertices, hh, component, layout);
            if (!Double.isFinite(uvLl) || !Double.isFinite(uvHl)
                || !Double.isFinite(uvLh) || !Double.isFinite(uvHh)
                || !sameFloat((float) uvHh, (float) (uvHl + uvLh - uvLl))) {
                return false;
            }

            double slopeU = (uvHl - uvLl) / sizeU;
            double slopeV = (uvLh - uvLl) / sizeV;
            double intercept = uvLl - slopeU * minU - slopeV * minV;
            int target = coefficientBase + component * UV_COEFFICIENTS_PER_COMPONENT;
            workspace.uvCoefficients[target] = slopeU;
            workspace.uvCoefficients[target + 1] = slopeV;
            workspace.uvCoefficients[target + 2] = intercept;
        }
        return true;
    }

    private static int findMatch(ByteBuffer vertices, int quad, Workspace workspace, VertexLayout layout) {
        for (int side = 0; side < SIDE_COUNT; side++) {
            long edgeHash = edgeHash(quad, side, workspace);
            int other = workspace.edgeOwners.get(edgeHash);
            if (other < 0) {
                continue;
            }
            if (!workspace.active[other]) {
                workspace.edgeOwners.remove(edgeHash);
                continue;
            }

            for (int otherSide = 0; otherSide < SIDE_COUNT; otherSide++) {
                if (edgeHash(other, otherSide, workspace) == edgeHash
                    && edgesMatch(quad, side, other, otherSide, workspace)
                    && canMerge(vertices, quad, side, other, otherSide, workspace, layout)) {
                    return other << MATCH_QUAD_SHIFT | otherSide << MATCH_OTHER_SIDE_SHIFT | side;
                }
            }
        }
        return NO_MATCH;
    }

    private static boolean canMerge(
        ByteBuffer vertices,
        int quad,
        int side,
        int other,
        int otherSide,
        Workspace workspace,
        VertexLayout layout
    ) {
        if (!areOppositeSides(side, otherSide)
            || workspace.axis[quad] != workspace.axis[other]
            || !sameFloat(workspace.plane[quad], workspace.plane[other])
            || workspace.orderCode[quad] != workspace.orderCode[other]
            || workspace.color[quad] != workspace.color[other]
            || workspace.light[quad] != workspace.light[other]
            || !areSourceRangesAdjacent(quad, other, workspace)) {
            return false;
        }

        if (!uvTransformsMatch(quad, other, workspace, layout)) {
            return false;
        }

        int firstCornerA;
        int secondCornerA;
        int firstCornerB;
        int secondCornerB;
        if (side == SIDE_MIN_U || side == SIDE_MAX_U) {
            firstCornerA = side == SIDE_MIN_U ? CORNER_MIN_U_MIN_V : CORNER_MAX_U_MIN_V;
            secondCornerA = side == SIDE_MIN_U ? CORNER_MIN_U_MAX_V : CORNER_MAX_U_MAX_V;
            firstCornerB = otherSide == SIDE_MIN_U ? CORNER_MIN_U_MIN_V : CORNER_MAX_U_MIN_V;
            secondCornerB = otherSide == SIDE_MIN_U ? CORNER_MIN_U_MAX_V : CORNER_MAX_U_MAX_V;
        } else {
            firstCornerA = side == SIDE_MIN_V ? CORNER_MIN_U_MIN_V : CORNER_MIN_U_MAX_V;
            secondCornerA = side == SIDE_MIN_V ? CORNER_MAX_U_MIN_V : CORNER_MAX_U_MAX_V;
            firstCornerB = otherSide == SIDE_MIN_V ? CORNER_MIN_U_MIN_V : CORNER_MIN_U_MAX_V;
            secondCornerB = otherSide == SIDE_MIN_V ? CORNER_MAX_U_MIN_V : CORNER_MAX_U_MAX_V;
        }

        int cornerBase = quad * CORNERS_PER_QUAD;
        int otherCornerBase = other * CORNERS_PER_QUAD;
        return visibleAttributesEqual(
            vertices,
            workspace.cornerSources[cornerBase + firstCornerA],
            workspace.cornerSources[otherCornerBase + firstCornerB],
            layout
        ) && visibleAttributesEqual(
            vertices,
            workspace.cornerSources[cornerBase + secondCornerA],
            workspace.cornerSources[otherCornerBase + secondCornerB],
            layout
        );
    }

    private static void merge(int quad, int other, int side, int otherSide, Workspace workspace) {
        int destination = quad * CORNERS_PER_QUAD;
        int source = other * CORNERS_PER_QUAD;
        if (side == SIDE_MIN_U || side == SIDE_MAX_U) {
            boolean quadIsLeft = side == SIDE_MAX_U && otherSide == SIDE_MIN_U;
            int left = quadIsLeft ? destination : source;
            int right = quadIsLeft ? source : destination;
            workspace.cornerScratch[CORNER_MIN_U_MIN_V] = workspace.cornerSources[left + CORNER_MIN_U_MIN_V];
            workspace.cornerScratch[CORNER_MAX_U_MIN_V] = workspace.cornerSources[right + CORNER_MAX_U_MIN_V];
            workspace.cornerScratch[CORNER_MIN_U_MAX_V] = workspace.cornerSources[left + CORNER_MIN_U_MAX_V];
            workspace.cornerScratch[CORNER_MAX_U_MAX_V] = workspace.cornerSources[right + CORNER_MAX_U_MAX_V];
            workspace.minU[quad] = Math.min(workspace.minU[quad], workspace.minU[other]);
            workspace.maxU[quad] = Math.max(workspace.maxU[quad], workspace.maxU[other]);
        } else {
            boolean quadIsBottom = side == SIDE_MAX_V && otherSide == SIDE_MIN_V;
            int bottom = quadIsBottom ? destination : source;
            int top = quadIsBottom ? source : destination;
            workspace.cornerScratch[CORNER_MIN_U_MIN_V] = workspace.cornerSources[bottom + CORNER_MIN_U_MIN_V];
            workspace.cornerScratch[CORNER_MAX_U_MIN_V] = workspace.cornerSources[bottom + CORNER_MAX_U_MIN_V];
            workspace.cornerScratch[CORNER_MIN_U_MAX_V] = workspace.cornerSources[top + CORNER_MIN_U_MAX_V];
            workspace.cornerScratch[CORNER_MAX_U_MAX_V] = workspace.cornerSources[top + CORNER_MAX_U_MAX_V];
            workspace.minV[quad] = Math.min(workspace.minV[quad], workspace.minV[other]);
            workspace.maxV[quad] = Math.max(workspace.maxV[quad], workspace.maxV[other]);
        }
        System.arraycopy(workspace.cornerScratch, 0, workspace.cornerSources, destination, CORNERS_PER_QUAD);
        workspace.sourceOrder[quad] = Math.min(workspace.sourceOrder[quad], workspace.sourceOrder[other]);
        workspace.sourceEndOrder[quad] = Math.max(workspace.sourceEndOrder[quad], workspace.sourceEndOrder[other]);
    }

    private static boolean areSourceRangesAdjacent(int first, int second, Workspace workspace) {
        return workspace.sourceEndOrder[first] + 1 == workspace.sourceOrder[second]
            || workspace.sourceEndOrder[second] + 1 == workspace.sourceOrder[first];
    }

    private static void insertEdges(int quad, Workspace workspace) {
        for (int side = 0; side < SIDE_COUNT; side++) {
            workspace.edgeOwners.put(edgeHash(quad, side, workspace), quad);
        }
    }

    private static void removeEdges(int quad, Workspace workspace) {
        for (int side = 0; side < SIDE_COUNT; side++) {
            long hash = edgeHash(quad, side, workspace);
            if (workspace.edgeOwners.get(hash) == quad) {
                workspace.edgeOwners.remove(hash);
            }
        }
    }

    private static boolean edgesMatch(int quad, int side, int other, int otherSide, Workspace workspace) {
        if (!areOppositeSides(side, otherSide)) {
            return false;
        }
        if (side == SIDE_MIN_U || side == SIDE_MAX_U) {
            return sameFloat(edgeCoordinate(quad, side, workspace), edgeCoordinate(other, otherSide, workspace))
                && sameFloat(workspace.minV[quad], workspace.minV[other])
                && sameFloat(workspace.maxV[quad], workspace.maxV[other]);
        }
        return sameFloat(edgeCoordinate(quad, side, workspace), edgeCoordinate(other, otherSide, workspace))
            && sameFloat(workspace.minU[quad], workspace.minU[other])
            && sameFloat(workspace.maxU[quad], workspace.maxU[other]);
    }

    private static long edgeHash(int quad, int side, Workspace workspace) {
        long hash = workspace.signature[quad];
        boolean vertical = side == SIDE_MIN_U || side == SIDE_MAX_U;
        hash = mix(hash, vertical ? 0 : 1);
        hash = mix(hash, floatBits(edgeCoordinate(quad, side, workspace)));
        hash = mix(hash, floatBits(vertical ? workspace.minV[quad] : workspace.minU[quad]));
        return mix(hash, floatBits(vertical ? workspace.maxV[quad] : workspace.maxU[quad]));
    }

    private static float edgeCoordinate(int quad, int side, Workspace workspace) {
        return switch (side) {
            case SIDE_MIN_U -> workspace.minU[quad];
            case SIDE_MAX_U -> workspace.maxU[quad];
            case SIDE_MIN_V -> workspace.minV[quad];
            case SIDE_MAX_V -> workspace.maxV[quad];
            default -> throw new IllegalArgumentException("Invalid quad side: " + side);
        };
    }

    private static long signatureOf(int quad, Workspace workspace, VertexLayout layout) {
        long hash = HASH_SEED;
        hash = mix(hash, workspace.axis[quad]);
        hash = mix(hash, floatBits(workspace.plane[quad]));
        hash = mix(hash, workspace.orderCode[quad]);
        hash = mix(hash, workspace.color[quad]);
        hash = mix(hash, workspace.light[quad]);
        int coefficientBase = quad * UV_COEFFICIENT_COUNT;
        for (int i = 0; i < UV_COEFFICIENT_COUNT; i++) {
            hash = mix(hash, coefficientKey(workspace.uvCoefficients[coefficientBase + i], layout));
        }
        return hash;
    }

    private static boolean uvTransformsMatch(int quad, int other, Workspace workspace, VertexLayout layout) {
        int coefficients = quad * UV_COEFFICIENT_COUNT;
        int otherCoefficients = other * UV_COEFFICIENT_COUNT;
        if (!layout.compact()) {
            for (int i = 0; i < UV_COEFFICIENT_COUNT; i++) {
                if (Double.doubleToLongBits(workspace.uvCoefficients[coefficients + i])
                    != Double.doubleToLongBits(workspace.uvCoefficients[otherCoefficients + i])) {
                    return false;
                }
            }
            return true;
        }

        double minU = Math.min(workspace.minU[quad], workspace.minU[other]);
        double maxU = Math.max(workspace.maxU[quad], workspace.maxU[other]);
        double minV = Math.min(workspace.minV[quad], workspace.minV[other]);
        double maxV = Math.max(workspace.maxV[quad], workspace.maxV[other]);
        for (int component = 0; component < UV_COMPONENT_COUNT; component++) {
            int first = coefficients + component * UV_COEFFICIENTS_PER_COMPONENT;
            int second = otherCoefficients + component * UV_COEFFICIENTS_PER_COMPONENT;
            if (!uvTransformMatchesAt(workspace, first, second, minU, minV)
                || !uvTransformMatchesAt(workspace, first, second, maxU, minV)
                || !uvTransformMatchesAt(workspace, first, second, minU, maxV)
                || !uvTransformMatchesAt(workspace, first, second, maxU, maxV)) {
                return false;
            }
        }
        return true;
    }

    private static boolean uvTransformMatchesAt(
        Workspace workspace,
        int first,
        int second,
        double u,
        double v
    ) {
        double firstValue = workspace.uvCoefficients[first] * u
            + workspace.uvCoefficients[first + 1] * v
            + workspace.uvCoefficients[first + 2];
        double secondValue = workspace.uvCoefficients[second] * u
            + workspace.uvCoefficients[second + 1] * v
            + workspace.uvCoefficients[second + 2];
        return Math.abs(firstValue - secondValue) <= COMPACT_UV_TRANSFORM_TOLERANCE;
    }

    private static long coefficientKey(double coefficient, VertexLayout layout) {
        if (!layout.compact()) {
            return Double.doubleToLongBits(coefficient);
        }
        return 0L;
    }

    private static void copyPlannedVertices(
        ByteBuffer vertices,
        Workspace workspace,
        int quadCount,
        long destination,
        VertexLayout layout
    ) {
        Arrays.fill(workspace.nodeAtOrder, 0, quadCount, -1);
        for (int quad = 0; quad < quadCount; quad++) {
            if (workspace.active[quad]) {
                workspace.nodeAtOrder[workspace.sourceOrder[quad]] = quad;
            }
        }

        long sourceAddress = MemoryUtil.memAddress(vertices);
        int stride = layout.stride();
        int quadBytes = Math.multiplyExact(stride, VERTICES_PER_QUAD);
        int outputVertex = 0;
        for (int order = 0; order < quadCount; order++) {
            int quad = workspace.nodeAtOrder[order];
            if (quad < 0) {
                continue;
            }
            if (!workspace.merged[quad]) {
                long source = sourceAddress + (long) quad * quadBytes;
                MemoryUtil.memCopy(source, destination + (long) outputVertex * stride, quadBytes);
            } else {
                int cornerBase = quad * CORNERS_PER_QUAD;
                int orderCode = workspace.orderCode[quad];
                for (int vertex = 0; vertex < VERTICES_PER_QUAD; vertex++) {
                    int corner = orderCode >> vertex * CORNER_BITS & 0x3;
                    int sourceVertex = workspace.cornerSources[cornerBase + corner];
                    MemoryUtil.memCopy(
                        sourceAddress + (long) sourceVertex * stride,
                        destination + (long) (outputVertex + vertex) * stride,
                        stride
                    );
                }
            }
            outputVertex += VERTICES_PER_QUAD;
        }
    }

    private static boolean visibleAttributesEqual(
        ByteBuffer vertices,
        int firstVertex,
        int secondVertex,
        VertexLayout layout
    ) {
        int first = vertexOffset(vertices, firstVertex, layout);
        int second = vertexOffset(vertices, secondVertex, layout);
        return vertices.getInt(first + layout.colorOffset()) == vertices.getInt(second + layout.colorOffset())
            && textureValue(vertices, first, layout) == textureValue(vertices, second, layout)
            && lightValue(vertices, first, layout) == lightValue(vertices, second, layout);
    }

    private static boolean areOppositeSides(int side, int otherSide) {
        return side == SIDE_MIN_U && otherSide == SIDE_MAX_U
            || side == SIDE_MAX_U && otherSide == SIDE_MIN_U
            || side == SIDE_MIN_V && otherSide == SIDE_MAX_V
            || side == SIDE_MAX_V && otherSide == SIDE_MIN_V;
    }

    private static int cornerOf(float u, float v, float minU, float maxU, float minV, float maxV) {
        int uBit;
        if (sameFloat(u, minU)) {
            uBit = 0;
        } else if (sameFloat(u, maxU)) {
            uBit = 1;
        } else {
            return -1;
        }

        int vBit;
        if (sameFloat(v, minV)) {
            vBit = 0;
        } else if (sameFloat(v, maxV)) {
            vBit = 2;
        } else {
            return -1;
        }
        return uBit | vBit;
    }

    private static boolean isCyclicOrder(int orderCode) {
        for (int vertex = 0; vertex < VERTICES_PER_QUAD; vertex++) {
            int current = orderCode >> vertex * CORNER_BITS & 0x3;
            int next = orderCode >> (vertex + 1) % VERTICES_PER_QUAD * CORNER_BITS & 0x3;
            int difference = current ^ next;
            if (difference != 1 && difference != 2) {
                return false;
            }
        }
        return true;
    }

    private static int vertexOffset(ByteBuffer vertices, int vertex, VertexLayout layout) {
        return Math.addExact(vertices.position(), Math.multiplyExact(vertex, layout.stride()));
    }

    private static float positionComponent(ByteBuffer vertices, int offset, int component, VertexLayout layout) {
        int componentOffset = offset + layout.positionOffset()
            + component * (layout.compact() ? SHORT_BYTES : FLOAT_BYTES);
        if (layout.compact()) {
            return CompactTerrainVertex.decodePosition(Short.toUnsignedInt(vertices.getShort(componentOffset)));
        }
        return vertices.getFloat(componentOffset);
    }

    private static float textureComponent(ByteBuffer vertices, int offset, int component, VertexLayout layout) {
        int componentOffset = offset + layout.textureOffset()
            + component * (layout.compact() ? SHORT_BYTES : FLOAT_BYTES);
        if (layout.compact()) {
            return CompactTerrainVertex.decodeTexture(Short.toUnsignedInt(vertices.getShort(componentOffset)));
        }
        return vertices.getFloat(componentOffset);
    }

    private static long textureValue(ByteBuffer vertices, int offset, VertexLayout layout) {
        if (layout.compact()) {
            int first = floatBits(CompactTerrainVertex.decodeTexture(
                Short.toUnsignedInt(vertices.getShort(offset + layout.textureOffset()))
            ));
            int second = floatBits(CompactTerrainVertex.decodeTexture(
                Short.toUnsignedInt(vertices.getShort(offset + layout.textureOffset() + SHORT_BYTES))
            ));
            return Integer.toUnsignedLong(first) << Integer.SIZE | Integer.toUnsignedLong(second);
        }
        long first = Integer.toUnsignedLong(vertices.getInt(offset + layout.textureOffset()));
        long second = Integer.toUnsignedLong(vertices.getInt(offset + layout.textureOffset() + FLOAT_BYTES));
        return first << Integer.SIZE | second;
    }

    private static int lightValue(ByteBuffer vertices, int offset, VertexLayout layout) {
        if (layout.compact()) {
            return Short.toUnsignedInt(vertices.getShort(offset + layout.lightOffset()));
        }
        return vertices.getInt(offset + layout.lightOffset());
    }

    private static VertexLayout layoutFor(MeshData.DrawState state) {
        if (state.format().equals(DefaultVertexFormat.BLOCK)) {
            return BLOCK_LAYOUT;
        }
        if (state.format().equals(CompactTerrainVertex.FORMAT)) {
            return COMPACT_LAYOUT;
        }
        return null;
    }

    private static int elementOffset(String name) {
        VertexFormatElement element = DefaultVertexFormat.BLOCK.getElement(name);
        if (element == null) {
            throw new IllegalStateException("BLOCK vertex format is missing " + name);
        }
        return element.offset();
    }

    private static float normalizeZero(float value) {
        return value == 0.0F ? 0.0F : value;
    }

    private static boolean sameFloat(float first, float second) {
        return floatBits(first) == floatBits(second);
    }

    private static int floatBits(float value) {
        return Float.floatToIntBits(normalizeZero(value));
    }

    private static long mix(long hash, long value) {
        long mixed = hash ^ value * HASH_MULTIPLIER;
        mixed ^= mixed >>> HASH_SHIFT;
        return mixed * HASH_MULTIPLIER;
    }

    private record VertexLayout(
        int stride,
        int positionOffset,
        int colorOffset,
        int textureOffset,
        int lightOffset,
        boolean compact
    ) {
    }

    public static final class CompactedMesh implements AutoCloseable {
        private final MeshData mesh;
        private final ByteBufferBuilder owner;
        private boolean transferred;

        private CompactedMesh(MeshData mesh, ByteBufferBuilder owner) {
            this.mesh = mesh;
            this.owner = owner;
        }

        public MeshData mesh() {
            return this.mesh;
        }

        public MeshData transferOwnership() {
            if (this.transferred) {
                throw new IllegalStateException("Compacted mesh ownership was already transferred");
            }
            ((VbmMeshData) this.mesh).mango$adoptVertexBuffer(this.owner);
            this.transferred = true;
            return this.mesh;
        }

        @Override
        public void close() {
            if (!this.transferred) {
                this.mesh.close();
                this.owner.close();
                this.transferred = true;
            }
        }
    }

    private static final class Workspace {
        private final Long2IntOpenHashMap edgeOwners = new Long2IntOpenHashMap();
        private final float[] positionScratch = new float[VERTICES_PER_QUAD * AXIS_COUNT];
        private final int[] cornerScratch = new int[CORNERS_PER_QUAD];

        private boolean[] active = new boolean[INITIAL_WORKSPACE_QUADS];
        private boolean[] mergeable = new boolean[INITIAL_WORKSPACE_QUADS];
        private boolean[] merged = new boolean[INITIAL_WORKSPACE_QUADS];
        private byte[] axis = new byte[INITIAL_WORKSPACE_QUADS];
        private float[] plane = new float[INITIAL_WORKSPACE_QUADS];
        private float[] minU = new float[INITIAL_WORKSPACE_QUADS];
        private float[] maxU = new float[INITIAL_WORKSPACE_QUADS];
        private float[] minV = new float[INITIAL_WORKSPACE_QUADS];
        private float[] maxV = new float[INITIAL_WORKSPACE_QUADS];
        private int[] orderCode = new int[INITIAL_WORKSPACE_QUADS];
        private int[] color = new int[INITIAL_WORKSPACE_QUADS];
        private int[] light = new int[INITIAL_WORKSPACE_QUADS];
        private int[] sourceOrder = new int[INITIAL_WORKSPACE_QUADS];
        private int[] sourceEndOrder = new int[INITIAL_WORKSPACE_QUADS];
        private int[] nodeAtOrder = new int[INITIAL_WORKSPACE_QUADS];
        private int[] cornerSources = new int[INITIAL_WORKSPACE_QUADS * CORNERS_PER_QUAD];
        private double[] uvCoefficients = new double[INITIAL_WORKSPACE_QUADS * UV_COEFFICIENT_COUNT];
        private long[] signature = new long[INITIAL_WORKSPACE_QUADS];

        private Workspace() {
            this.edgeOwners.defaultReturnValue(-1);
        }

        private void prepare(int quadCount) {
            if (quadCount <= this.active.length) {
                return;
            }
            int capacity = this.active.length;
            while (capacity < quadCount) {
                capacity = Math.min(Math.multiplyExact(capacity, 2), MAX_SOURCE_QUADS);
            }
            this.active = new boolean[capacity];
            this.mergeable = new boolean[capacity];
            this.merged = new boolean[capacity];
            this.axis = new byte[capacity];
            this.plane = new float[capacity];
            this.minU = new float[capacity];
            this.maxU = new float[capacity];
            this.minV = new float[capacity];
            this.maxV = new float[capacity];
            this.orderCode = new int[capacity];
            this.color = new int[capacity];
            this.light = new int[capacity];
            this.sourceOrder = new int[capacity];
            this.sourceEndOrder = new int[capacity];
            this.nodeAtOrder = new int[capacity];
            this.cornerSources = new int[Math.multiplyExact(capacity, CORNERS_PER_QUAD)];
            this.uvCoefficients = new double[Math.multiplyExact(capacity, UV_COEFFICIENT_COUNT)];
            this.signature = new long[capacity];
        }
    }
}
