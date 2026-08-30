package org.fruitmc.mango.mixin.vulkan.terrain;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMillisMixin {

    @Unique
    private long mango$cachedFrameMillis;

    @Inject(
        method = "extractVisibleBlockEntities(Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/state/level/LevelRenderState;)V",
        at = @At("HEAD"),
        require = 1
    )
    private void mango$cacheFrameMillis(
        Camera camera,
        float deltaPartialTick,
        LevelRenderState levelRenderState,
        CallbackInfo ci
    ) {
        this.mango$cachedFrameMillis = Util.getMillis();
    }

    @WrapOperation(
        method = "extractVisibleBlockEntities(Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/state/level/LevelRenderState;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/util/Util;getMillis()J"
        ),
        require = 1
    )
    private long mango$wrapGetMillis(Operation<Long> original) {
        return this.mango$cachedFrameMillis;
    }
}
