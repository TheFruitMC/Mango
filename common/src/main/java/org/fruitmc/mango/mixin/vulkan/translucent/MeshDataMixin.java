package org.fruitmc.mango.mixin.vulkan.translucent;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.CompactVectorArray;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import org.fruitmc.mango.render.chunk.vbm.VbmMeshData;
import org.fruitmc.mango.render.translucent.BspSortDataHolder;
import org.fruitmc.mango.render.translucent.MangoVertexSorting;
import org.fruitmc.mango.render.translucent.bsp.BspSortData;
import org.fruitmc.mango.render.translucent.bsp.TranslucentQuadDecoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MeshData.class)
public abstract class MeshDataMixin implements VbmMeshData {

    @Shadow
    private ByteBufferBuilder.Result indexBuffer;

    @Unique
    private ByteBufferBuilder mango$ownedVertexBuffer;

    @Override
    public void mango$adoptVertexBuffer(ByteBufferBuilder owner) {
        if (this.mango$ownedVertexBuffer != null) {
            throw new IllegalStateException("MeshData already owns a compacted vertex buffer");
        }
        this.mango$ownedVertexBuffer = owner;
    }

    @Inject(method = "close()V", at = @At("RETURN"), require = 1)
    private void mango$closeOwnedVertexBuffer(CallbackInfo ci) {
        if (this.mango$ownedVertexBuffer != null) {
            this.mango$ownedVertexBuffer.close();
            this.mango$ownedVertexBuffer = null;
        }
    }

    @Inject(
        method = "sortQuads(Lcom/mojang/blaze3d/vertex/ByteBufferBuilder;Lcom/mojang/blaze3d/vertex/VertexSorting;)Lcom/mojang/blaze3d/vertex/MeshData$SortState;",
        at = @At("HEAD"),
        cancellable = true,
        require = 1
    )
    private void mango$bspSortQuads(
        ByteBufferBuilder indexBufferTarget,
        VertexSorting sorting,
        CallbackInfoReturnable<MeshData.SortState> cir
    ) {
        MeshData self = (MeshData) (Object) this;
        MeshData.DrawState drawState = self.drawState();

        if (drawState.primitiveTopology() != PrimitiveTopology.QUADS) {
            cir.setReturnValue(null);
            return;
        }

        BspSortData bspData = TranslucentQuadDecoder.decodeSortData(self);

        CompactVectorArray centroids = new CompactVectorArray(drawState.vertexCount() / 4);
        MeshData.decodeQuadCentroids(
            self.vertexBuffer(), drawState.vertexCount(), drawState.format(), centroids, 0
        );

        MeshData.SortState sortState = new MeshData.SortState(centroids, drawState.indexType());
        ((BspSortDataHolder) (Object) sortState).mango$setBspSortData(bspData);

        if (sorting instanceof MangoVertexSorting mangoSorting) {
            mangoSorting.setBspSortData(bspData);
        }

        this.indexBuffer = sortState.buildSortedIndexBuffer(indexBufferTarget, sorting);

        cir.setReturnValue(sortState);
    }
}
