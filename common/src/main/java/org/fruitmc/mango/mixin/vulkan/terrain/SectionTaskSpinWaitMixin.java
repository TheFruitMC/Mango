package org.fruitmc.mango.mixin.vulkan.terrain;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.locks.LockSupport;

@Mixin(targets = {
    "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask",
    "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$ResortTransparencyTask"
})
public abstract class SectionTaskSpinWaitMixin {

    private static final long PARK_NANOS = 100_000L;

    @WrapOperation(
        method = "doTask",
        at = @At(
            value = "INVOKE",
            target = "Ljava/lang/Thread;onSpinWait()V"
        ),
        require = 0
    )
    private void mango$parkInsteadOfSpin(Operation<Void> original) {
        LockSupport.parkNanos(PARK_NANOS);
    }
}
