package org.fruitmc.mango.render.translucent.bsp;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public final class TranslucentQuadDecoder {
    private static final Logger LOG = LoggerFactory.getLogger(TranslucentQuadDecoder.class);
    private static final int SMALL_SECTION_QUAD_THRESHOLD = 8;

    private TranslucentQuadDecoder() {
    }

    public static BspSortData decodeSortData(MeshData meshData) {
        MeshData.DrawState drawState = meshData.drawState();
        VertexFormat format = drawState.format();
        int vertexCount = drawState.vertexCount();

        VertexFormatElement positionElement = format.getElement("Position");
        if (positionElement == null) {
            LOG.warn("Cannot decode translucent quads: vertex format has no Position element");
            return BspSortData.empty();
        }

        int quadCount = vertexCount / 4;
        if (quadCount <= SMALL_SECTION_QUAD_THRESHOLD) {
            return BspSortData.empty();
        }

        ByteBuffer vertexBuffer = meshData.vertexBuffer();
        int positionOffset = vertexBuffer.position() + positionElement.offset();
        int vertexStride = format.getVertexSize();
        int quadStride = vertexStride * 4;

        List<TranslucentQuad> quads = new ArrayList<>(quadCount);
        float[] vertices = new float[12];

        for (int i = 0; i < quadCount; i++) {
            int baseOffset = i * quadStride + positionOffset;
            for (int corner = 0; corner < 4; corner++) {
                int cornerOffset = baseOffset + corner * vertexStride;
                vertices[corner * 3] = vertexBuffer.getFloat(cornerOffset);
                vertices[corner * 3 + 1] = vertexBuffer.getFloat(cornerOffset + 4);
                vertices[corner * 3 + 2] = vertexBuffer.getFloat(cornerOffset + 8);
            }
            quads.add(new TranslucentQuad(vertices));
        }

        return BspSortData.fromQuads(quads);
    }
}
