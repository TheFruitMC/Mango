package org.fruitmc.mango.mixin.vulkan.terrain;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import org.fruitmc.mango.render.chunk.vbm.VertexBlockMatcher;
import org.fruitmc.mango.render.chunk.vertex.CompactTerrainBufferBuilder;
import org.fruitmc.mango.render.fluid.FluidLightingAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.Optional;

@Mixin(SectionCompiler.class)
public abstract class SectionCompilerMixin {

    @Shadow
    @Final
    private boolean ambientOcclusion;

    @WrapOperation(
        method = "compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;",
        at = @At(
            value = "NEW",
            target = "(Lnet/minecraft/client/renderer/block/FluidStateModelSet;)Lnet/minecraft/client/renderer/block/FluidRenderer;"
        ),
        require = 1
    )
    private FluidRenderer mango$createFluidRenderer(
        FluidStateModelSet fluidModelSet,
        Operation<FluidRenderer> original
    ) {
        FluidRenderer renderer = original.call(fluidModelSet);
        ((FluidLightingAccess) renderer).mango$setFluidSmoothLighting(this.ambientOcclusion);
        return renderer;
    }

    @WrapOperation(
        method = "getOrBeginLayer(Ljava/util/Map;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;)Lcom/mojang/blaze3d/vertex/BufferBuilder;",
        at = @At(
            value = "NEW",
            target = "(Lcom/mojang/blaze3d/vertex/ByteBufferBuilder;Lcom/mojang/blaze3d/PrimitiveTopology;Lcom/mojang/blaze3d/vertex/VertexFormat;)Lcom/mojang/blaze3d/vertex/BufferBuilder;"
        ),
        require = 1
    )
    private BufferBuilder mango$createTerrainBuilder(
        ByteBufferBuilder buffer,
        PrimitiveTopology topology,
        VertexFormat format,
        Operation<BufferBuilder> original,
        Map<ChunkSectionLayer, BufferBuilder> startedLayers,
        SectionBufferBuilderPack buffers,
        ChunkSectionLayer layer
    ) {
        if (layer != ChunkSectionLayer.TRANSLUCENT) {
            return new CompactTerrainBufferBuilder(buffer, topology);
        }
        return original.call(buffer, topology, format);
    }

    @Inject(
        method = "compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;",
        at = @At("RETURN"),
        require = 1
    )
    private void mango$compactCompatibleTerrain(
        SectionPos sectionPos,
        RenderSectionRegion region,
        VertexSorting vertexSorting,
        SectionBufferBuilderPack builders,
        CallbackInfoReturnable<SectionCompiler.Results> cir
    ) {
        SectionCompiler.Results results = cir.getReturnValue();
        for (Map.Entry<ChunkSectionLayer, MeshData> entry : results.renderedLayers.entrySet()) {
            if (entry.getKey() == ChunkSectionLayer.TRANSLUCENT) {
                continue;
            }

            MeshData original = entry.getValue();
            Optional<VertexBlockMatcher.CompactedMesh> compacted = VertexBlockMatcher.compact(original);
            if (compacted.isPresent()) {
                try (VertexBlockMatcher.CompactedMesh replacement = compacted.get()) {
                    entry.setValue(replacement.transferOwnership());
                    original.close();
                }
            }
        }
    }
}
