package org.fruitmc.mango.mixin.vulkan.terrain;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.fruitmc.mango.config.MangoConfig;
import org.fruitmc.mango.render.chunk.vertex.CompactTerrainVertex;
import org.fruitmc.mango.render.gpu.terrain.TerrainSectionRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SectionRenderDispatcher.class)
public abstract class SectionRenderDispatcherMixin {

    private static final int VERTEX_BUFFER_HEAP_SIZE = 134217728;
    private static final int INDEX_BUFFER_HEAP_SIZE = 33554432;
    private static final int STAGING_BUFFER_SIZE = 102760448;

    private static final int MIN_CHUNK_BUFFER_SCALE = 1;
    private static final int MAX_CHUNK_BUFFER_SCALE = 4;

    private static int mango$scale(int base) {
        // Vanilla creates the dispatcher once per world; the value is captured when its arenas are constructed.
        int scale = MangoConfig.INSTANCE.chunkBufferScale;
        if (scale < MIN_CHUNK_BUFFER_SCALE) {
            scale = MIN_CHUNK_BUFFER_SCALE;
        } else if (scale > MAX_CHUNK_BUFFER_SCALE) {
            scale = MAX_CHUNK_BUFFER_SCALE;
        }
        return base * scale;
    }

    @ModifyExpressionValue(
        method = "lambda$new$0",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/pipeline/RenderPipeline;getVertexFormatBinding(I)Lcom/mojang/blaze3d/vertex/VertexFormat;"
        ),
        require = 1
    )
    private VertexFormat mango$selectTerrainArenaFormat(
        VertexFormat original,
        @Local(argsOnly = true) ChunkSectionLayer layer
    ) {
        return layer != ChunkSectionLayer.TRANSLUCENT
            ? CompactTerrainVertex.FORMAT
            : original;
    }

    @ModifyConstant(
            method = "lambda$new$0",
            constant = @Constant(intValue = VERTEX_BUFFER_HEAP_SIZE),
            require = 1
    )
    private int mango$scaleVertexBufferHeapSize(int original) {
        return mango$scale(original);
    }

    @ModifyConstant(
            method = "lambda$new$0",
            constant = @Constant(intValue = INDEX_BUFFER_HEAP_SIZE),
            require = 1
    )
    private int mango$scaleIndexBufferHeapSize(int original) {
        return mango$scale(original);
    }

    @ModifyConstant(
            method = "<init>",
            slice = @Slice(
                    from = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;getDevice()Lcom/mojang/blaze3d/systems/GpuDevice;")
            ),
            constant = @Constant(intValue = STAGING_BUFFER_SIZE),
            require = 1
    )
    private int mango$scaleStagingBufferSize(int original) {
        return mango$scale(original);
    }

    @Inject(
            method = "dispose()V",
            at = @At("RETURN"),
            require = 1
    )
    private void mango$onDisposeReturn(CallbackInfo ci) {
        TerrainSectionRegistry.get().onDispatcherDisposed((SectionRenderDispatcher)(Object)this);
    }
}
