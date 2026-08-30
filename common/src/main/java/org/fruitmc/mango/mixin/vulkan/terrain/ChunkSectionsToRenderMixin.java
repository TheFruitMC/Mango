package org.fruitmc.mango.mixin.vulkan.terrain;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.fruitmc.mango.render.gpu.terrain.TerrainFrameHolder;
import org.fruitmc.mango.render.gpu.terrain.TerrainFrame;
import org.fruitmc.mango.render.gpu.terrain.TerrainRenderRouter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.EnumMap;
import java.util.List;

@Mixin(ChunkSectionsToRender.class)
public abstract class ChunkSectionsToRenderMixin implements TerrainFrameHolder {

    @Unique
    private TerrainFrame mango$terrainFrame = TerrainFrame.empty();

    @Shadow
    public abstract GpuTextureView textureView();

    @Shadow
    public abstract EnumMap<ChunkSectionLayer, Int2ObjectOpenHashMap<List<com.mojang.blaze3d.systems.RenderPass.Draw<GpuBufferSlice[]>>>> drawGroupsPerLayer();

    @Shadow
    public abstract int maxIndicesRequired();

    @Shadow
    public abstract GpuBufferSlice[] chunkSectionInfos();

    @Inject(
            method = "renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/blaze3d/textures/GpuSampler;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 1
    )
    private void mango$onRenderGroupHead(ChunkSectionLayerGroup group, GpuSampler sampler, CallbackInfo ci) {
        if (TerrainRenderRouter.get().tryRender((ChunkSectionsToRender)(Object)this, group, sampler)) {
            ci.cancel();
            return;
        }
        if (mango$containsOpaqueLayer(group)) {
            ci.cancel();
        }
    }

    @Override
    public void mango$setTerrainFrame(TerrainFrame frame) {
        this.mango$terrainFrame = frame;
    }

    @Override
    public TerrainFrame mango$getTerrainFrame() {
        return this.mango$terrainFrame;
    }

    @Unique
    private static boolean mango$containsOpaqueLayer(ChunkSectionLayerGroup group) {
        for (ChunkSectionLayer layer : group.layers()) {
            if (layer != ChunkSectionLayer.TRANSLUCENT) {
                return true;
            }
        }
        return false;
    }
}
