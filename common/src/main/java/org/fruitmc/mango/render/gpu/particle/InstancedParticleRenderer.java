package org.fruitmc.mango.render.gpu.particle;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import org.fruitmc.mango.render.gpu.buffer.RingBufferUploader;
import org.fruitmc.mango.render.gpu.policy.DynamicUploadPolicy;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public final class InstancedParticleRenderer implements AutoCloseable {

    private static final int QUAD_INDEX_COUNT = 6;
    private static final int QUAD_VERTEX_COUNT = 4;
    private static final int CORNER_COMPONENTS = 2;
    private static final int MESH_BYTES = QUAD_VERTEX_COUNT * CORNER_COMPONENTS * Float.BYTES;
    private static final int MESH_BINDING = 0;
    private static final int INSTANCE_BINDING = 1;
    private static final String MESH_LABEL = "Mango instanced particle quad";
    private static final String RING_LABEL = "Mango particle instance ring";
    private static final String ATLAS_SAMPLER = "Sampler0";
    private static final int KIB_BYTES = 1024;
    private static final int INITIAL_RING_KIB = 64;
    private static final int VERTEX_UPLOAD_ALIGNMENT = Integer.BYTES;
    private static final int RING_USAGE = GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_VERTEX;
    private static final float[] CORNERS = {1.0F, -1.0F, 1.0F, 1.0F, -1.0F, 1.0F, -1.0F, -1.0F};

    private static final InstancedParticleRenderer INSTANCE = new InstancedParticleRenderer();

    private final RingBufferUploader instanceUploader;
    private final Map<SingleQuadParticle.Layer, LayerBatch> batchesByLayer = new IdentityHashMap<>();
    private final List<LayerBatch> batches = new ArrayList<>();

    @Nullable private GpuBuffer quadMesh;
    @Nullable private LayerBatch collectingBatch;

    private InstancedParticleRenderer() {
        this.instanceUploader = new RingBufferUploader(
            () -> RING_LABEL,
            RING_USAGE,
            VERTEX_UPLOAD_ALIGNMENT,
            INITIAL_RING_KIB * KIB_BYTES
        );
    }

    public static InstancedParticleRenderer get() {
        return INSTANCE;
    }

    public boolean beginLayer(SingleQuadParticle.Layer layer) {
        if (!InstancedParticlePipeline.isSupported(layer.pipeline())) {
            return false;
        }
        this.collectingBatch = batchFor(layer);
        return true;
    }

    public void endLayer() {
        this.collectingBatch = null;
    }

    public boolean collect(
        float x,
        float y,
        float z,
        float xRot,
        float yRot,
        float zRot,
        float wRot,
        float scale,
        float u0,
        float u1,
        float v0,
        float v1,
        int color,
        int lightCoords
    ) {
        LayerBatch batch = this.collectingBatch;
        if (batch == null) {
            return false;
        }
        batch.collector.addInstance(x, y, z, xRot, yRot, zRot, wRot, scale, u0, u1, v0, v1, color, lightCoords);
        return true;
    }

    public void finishPrepare() {
        int totalBytes = 0;
        int sliceCount = 0;
        for (int index = 0; index < this.batches.size(); index++) {
            int bytes = this.batches.get(index).collector.instanceDataBytes();
            totalBytes += bytes;
            if (bytes > 0) {
                sliceCount++;
            }
        }

        DynamicUploadPolicy.beginFrame(this.instanceUploader, totalBytes, sliceCount);
        if (totalBytes == 0) {
            return;
        }

        for (int index = 0; index < this.batches.size(); index++) {
            LayerBatch batch = this.batches.get(index);
            int bytes = batch.collector.instanceDataBytes();
            if (bytes > 0) {
                ByteBuffer records = batch.collector.backingBuffer().slice(0, bytes);
                batch.instanceSlice = DynamicUploadPolicy.uploadSlice(this.instanceUploader, records);
            }
        }
    }

    public void drawLayers(
        StagedVertexBuffer stagedBuffer,
        Map<SingleQuadParticle.Layer, StagedVertexBuffer.Draw> layers,
        RenderPass renderPass,
        TextureManager textureManager
    ) {
        for (Map.Entry<SingleQuadParticle.Layer, StagedVertexBuffer.Draw> entry : layers.entrySet()) {
            SingleQuadParticle.Layer layer = entry.getKey();
            LayerBatch batch = this.batchesByLayer.get(layer);
            if (batch != null && batch.instanceSlice != null && batch.collector.instanceCount() > 0) {
                drawInstanced(layer, batch, renderPass, textureManager);
            } else {
                drawStaged(stagedBuffer, layer, entry.getValue(), renderPass, textureManager);
            }
        }
    }

    public void finishFrame() {
        for (int index = 0; index < this.batches.size(); index++) {
            LayerBatch batch = this.batches.get(index);
            batch.collector.beginFrame();
            batch.instanceSlice = null;
        }
        this.collectingBatch = null;
        DynamicUploadPolicy.endFrame(this.instanceUploader);
    }

    @Override
    public void close() {
        GpuBuffer mesh = this.quadMesh;
        if (mesh != null) {
            mesh.close();
            this.quadMesh = null;
        }
        this.collectingBatch = null;
        for (int index = 0; index < this.batches.size(); index++) {
            LayerBatch batch = this.batches.get(index);
            batch.instanceSlice = null;
            batch.collector.close();
        }
        this.batches.clear();
        this.batchesByLayer.clear();
        this.instanceUploader.close();
    }

    private void drawInstanced(
        SingleQuadParticle.Layer layer,
        LayerBatch batch,
        RenderPass renderPass,
        TextureManager textureManager
    ) {
        GpuBufferSlice instances = batch.instanceSlice;
        if (instances == null) {
            return;
        }
        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        renderPass.setPipeline(InstancedParticlePipeline.get(layer.pipeline()));
        renderPass.setVertexBuffer(MESH_BINDING, quadMesh().slice());
        renderPass.setVertexBuffer(INSTANCE_BINDING, instances);
        renderPass.setIndexBuffer(indices.getBuffer(QUAD_INDEX_COUNT), indices.type());
        bindAtlas(layer, renderPass, textureManager);
        renderPass.drawIndexed(QUAD_INDEX_COUNT, batch.collector.instanceCount(), 0, 0, 0);
    }

    private static void drawStaged(
        StagedVertexBuffer stagedBuffer,
        SingleQuadParticle.Layer layer,
        StagedVertexBuffer.Draw draw,
        RenderPass renderPass,
        TextureManager textureManager
    ) {
        StagedVertexBuffer.ExecuteInfo executeInfo = stagedBuffer.getExecuteInfo(draw);
        if (executeInfo == null) {
            return;
        }
        renderPass.setPipeline(layer.pipeline());
        renderPass.setVertexBuffer(MESH_BINDING, executeInfo.vertexBuffer().slice());
        renderPass.setIndexBuffer(executeInfo.indexBuffer(), executeInfo.indexType());
        bindAtlas(layer, renderPass, textureManager);
        renderPass.drawIndexed(executeInfo.indexCount(), 1, executeInfo.firstIndex(), executeInfo.baseVertex(), 0);
    }

    private static void bindAtlas(SingleQuadParticle.Layer layer, RenderPass renderPass, TextureManager textureManager) {
        AbstractTexture texture = textureManager.getTexture(layer.textureAtlasLocation());
        renderPass.bindTexture(ATLAS_SAMPLER, texture.getTextureView(), texture.getSampler());
    }

    private LayerBatch batchFor(SingleQuadParticle.Layer layer) {
        LayerBatch batch = this.batchesByLayer.get(layer);
        if (batch == null) {
            batch = new LayerBatch();
            this.batchesByLayer.put(layer, batch);
            this.batches.add(batch);
        }
        return batch;
    }

    private GpuBuffer quadMesh() {
        GpuBuffer mesh = this.quadMesh;
        if (mesh != null) {
            return mesh;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = stack.malloc(MESH_BYTES);
            for (int component = 0; component < CORNERS.length; component++) {
                data.putFloat(component * Float.BYTES, CORNERS[component]);
            }
            mesh = RenderSystem.getDevice().createBuffer(() -> MESH_LABEL, GpuBuffer.USAGE_VERTEX, data);
        }
        this.quadMesh = mesh;
        return mesh;
    }

    private static final class LayerBatch {

        private final ParticleInstanceCollector collector = new ParticleInstanceCollector();
        @Nullable private GpuBufferSlice instanceSlice;
    }
}
