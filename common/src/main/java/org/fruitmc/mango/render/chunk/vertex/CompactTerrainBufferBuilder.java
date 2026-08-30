package org.fruitmc.mango.render.chunk.vertex;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.util.ARGB;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteOrder;

public final class CompactTerrainBufferBuilder extends BufferBuilder {

    private static final int MAX_VERTEX_COUNT = 16_777_215;
    private static final int VERTICES_PER_QUAD = 4;
    private static final float QUAD_AVERAGE_SCALE = 1.0F / VERTICES_PER_QUAD;
    private static final int POSITION_COMPONENTS = 3;
    private static final int REQUIRED_ATTRIBUTES = 0b111;
    private static final int ATTRIBUTE_COLOR = 0b001;
    private static final int ATTRIBUTE_TEXTURE = 0b010;
    private static final int ATTRIBUTE_LIGHT = 0b100;
    private static final boolean IS_LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;

    private final ByteBufferBuilder buffer;
    private final PrimitiveTopology primitiveTopology;
    private final float[] positions = new float[VERTICES_PER_QUAD * POSITION_COMPONENTS];
    private final float[] textures = new float[VERTICES_PER_QUAD * 2];
    private final int[] colors = new int[VERTICES_PER_QUAD];
    private final int[] lights = new int[VERTICES_PER_QUAD];

    private boolean building = true;
    private boolean hasCurrentVertex;
    private int attributes;
    private int vertexInQuad;
    private int vertices;

    public CompactTerrainBufferBuilder(ByteBufferBuilder buffer, PrimitiveTopology primitiveTopology) {
        super(buffer, primitiveTopology, CompactTerrainVertex.FORMAT);
        if (primitiveTopology != PrimitiveTopology.QUADS) {
            throw new IllegalArgumentException("Compact terrain requires QUADS topology, received " + primitiveTopology);
        }
        this.buffer = buffer;
        this.primitiveTopology = primitiveTopology;
    }

    @Override
    public @Nullable MeshData build() {
        ensureBuilding();
        finishCurrentVertex();
        if (this.vertexInQuad != 0) {
            throw new IllegalStateException("Compact terrain mesh ended with an incomplete quad containing " + this.vertexInQuad + " vertices");
        }
        this.building = false;
        if (this.vertices == 0) {
            return null;
        }

        ByteBufferBuilder.Result vertexBuffer = this.buffer.build();
        if (vertexBuffer == null) {
            return null;
        }
        int indexCount = this.primitiveTopology.indexCount(this.vertices);
        MeshData.DrawState state = new MeshData.DrawState(
            CompactTerrainVertex.FORMAT,
            this.vertices,
            indexCount,
            this.primitiveTopology,
            IndexType.least(this.vertices)
        );
        return new MeshData(vertexBuffer, state);
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        beginVertex(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha) {
        requireCurrentVertex();
        int packed = IS_LITTLE_ENDIAN
            ? red & 0xFF | (green & 0xFF) << 8 | (blue & 0xFF) << 16 | (alpha & 0xFF) << 24
            : (red & 0xFF) << 24 | (green & 0xFF) << 16 | (blue & 0xFF) << 8 | alpha & 0xFF;
        this.colors[this.vertexInQuad] = packed;
        this.attributes |= ATTRIBUTE_COLOR;
        return this;
    }

    @Override
    public VertexConsumer setColor(int color) {
        requireCurrentVertex();
        this.colors[this.vertexInQuad] = nativeRgba(color);
        this.attributes |= ATTRIBUTE_COLOR;
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        requireCurrentVertex();
        int texture = this.vertexInQuad * 2;
        this.textures[texture] = u;
        this.textures[texture + 1] = v;
        this.attributes |= ATTRIBUTE_TEXTURE;
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        requireCurrentVertex();
        return this;
    }

    @Override
    public VertexConsumer setOverlay(int packedOverlayCoords) {
        requireCurrentVertex();
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        requireCurrentVertex();
        this.lights[this.vertexInQuad] = u & 0xFF | (v & 0xFF) << 8;
        this.attributes |= ATTRIBUTE_LIGHT;
        return this;
    }

    @Override
    public VertexConsumer setLight(int packedLightCoords) {
        return setUv2(packedLightCoords, packedLightCoords >>> 16);
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        requireCurrentVertex();
        return this;
    }

    @Override
    public VertexConsumer setLineWidth(float width) {
        requireCurrentVertex();
        return this;
    }

    @Override
    public void addVertex(
        float x,
        float y,
        float z,
        int color,
        float u,
        float v,
        int overlayCoords,
        int lightCoords,
        float normalX,
        float normalY,
        float normalZ
    ) {
        beginVertex(x, y, z);
        this.colors[this.vertexInQuad] = nativeRgba(color);
        int texture = this.vertexInQuad * 2;
        this.textures[texture] = u;
        this.textures[texture + 1] = v;
        this.lights[this.vertexInQuad] = lightCoords & 0xFF | (lightCoords >>> 16 & 0xFF) << 8;
        this.attributes = REQUIRED_ATTRIBUTES;
        finishCurrentVertex();
    }

    private void beginVertex(float x, float y, float z) {
        ensureBuilding();
        finishCurrentVertex();
        if (this.vertices >= MAX_VERTEX_COUNT) {
            throw new IllegalStateException("Trying to write too many compact terrain vertices (>" + MAX_VERTEX_COUNT + ")");
        }
        int position = this.vertexInQuad * POSITION_COMPONENTS;
        this.positions[position] = x;
        this.positions[position + 1] = y;
        this.positions[position + 2] = z;
        this.attributes = 0;
        this.hasCurrentVertex = true;
    }

    private void finishCurrentVertex() {
        if (!this.hasCurrentVertex) {
            return;
        }
        if (this.attributes != REQUIRED_ATTRIBUTES) {
            throw new IllegalStateException("Compact terrain vertex is missing color, texture, or light data");
        }
        this.hasCurrentVertex = false;
        this.vertexInQuad++;
        this.vertices++;
        if (this.vertexInQuad == VERTICES_PER_QUAD) {
            writeQuad();
            this.vertexInQuad = 0;
        }
    }

    private void writeQuad() {
        float centerU = 0.0F;
        float centerV = 0.0F;
        for (int vertex = 0; vertex < VERTICES_PER_QUAD; vertex++) {
            int texture = vertex * 2;
            centerU += this.textures[texture];
            centerV += this.textures[texture + 1];
        }
        centerU *= QUAD_AVERAGE_SCALE;
        centerV *= QUAD_AVERAGE_SCALE;

        long destination = this.buffer.reserve(VERTICES_PER_QUAD * CompactTerrainVertex.STRIDE);
        for (int vertex = 0; vertex < VERTICES_PER_QUAD; vertex++) {
            long address = destination + (long) vertex * CompactTerrainVertex.STRIDE;
            int position = vertex * POSITION_COMPONENTS;
            MemoryUtil.memPutShort(address + CompactTerrainVertex.POSITION_OFFSET, (short) CompactTerrainVertex.encodePosition(this.positions[position]));
            MemoryUtil.memPutShort(address + CompactTerrainVertex.POSITION_OFFSET + Short.BYTES, (short) CompactTerrainVertex.encodePosition(this.positions[position + 1]));
            MemoryUtil.memPutShort(address + CompactTerrainVertex.POSITION_OFFSET + Short.BYTES * 2L, (short) CompactTerrainVertex.encodePosition(this.positions[position + 2]));

            int texture = vertex * 2;
            MemoryUtil.memPutShort(address + CompactTerrainVertex.TEXTURE_OFFSET, (short) CompactTerrainVertex.encodeTexture(centerU, this.textures[texture]));
            MemoryUtil.memPutShort(address + CompactTerrainVertex.TEXTURE_OFFSET + Short.BYTES, (short) CompactTerrainVertex.encodeTexture(centerV, this.textures[texture + 1]));
            MemoryUtil.memPutShort(address + CompactTerrainVertex.LIGHT_OFFSET, (short) this.lights[vertex]);
            MemoryUtil.memPutInt(address + CompactTerrainVertex.COLOR_OFFSET, this.colors[vertex]);
        }
    }

    private void ensureBuilding() {
        if (!this.building) {
            throw new IllegalStateException("Compact terrain builder is no longer building");
        }
    }

    private void requireCurrentVertex() {
        ensureBuilding();
        if (!this.hasCurrentVertex) {
            throw new IllegalArgumentException("Not currently building a compact terrain vertex");
        }
    }

    private static int nativeRgba(int argb) {
        int abgr = ARGB.toABGR(argb);
        return IS_LITTLE_ENDIAN ? abgr : Integer.reverseBytes(abgr);
    }
}
