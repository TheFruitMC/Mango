package org.fruitmc.mango.mixin.vulkan.terrain;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.util.Util;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMillisMixin {

    @Unique
    private long mango$cachedFrameMillis;

    @Inject(
        method = "prepareChunkRenders(Lorg/joml/Matrix4fc;)Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;",
        at = @At("HEAD"),
        require = 1
    )
    private void mango$cacheFrameMillis(Matrix4fc modelViewMatrix, CallbackInfoReturnable<ChunkSectionsToRender> cir) {
        this.mango$cachedFrameMillis = Util.getMillis();
    }

    @WrapOperation(
        method = "prepareChunkRenders(Lorg/joml/Matrix4fc;)Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/util/Util;getMillis()J"
        ),
        require = 0
    )
    private long mango$wrapGetMillis(Operation<Long> original) {
        return this.mango$cachedFrameMillis;
    }
}
