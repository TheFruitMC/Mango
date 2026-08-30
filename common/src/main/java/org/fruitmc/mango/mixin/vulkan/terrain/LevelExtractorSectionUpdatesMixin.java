package org.fruitmc.mango.mixin.vulkan.terrain;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.SectionPos;
import org.fruitmc.mango.render.gpu.terrain.DirtySectionUpdateCoordinator;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
public abstract class LevelExtractorSectionUpdatesMixin {

    @Shadow
    private @Nullable SectionUpdateTracker sectionUpdateTracker;

    @Inject(
        method = "<init>(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/client/renderer/LevelRenderer;)V",
        at = @At("RETURN"),
        require = 1
    )
    private void mango$clearDirtySectionCoordinator(CallbackInfo ci) {
        DirtySectionUpdateCoordinator.get().setTracker(null);
    }

    @Inject(method = "allChanged()V", at = @At("RETURN"), require = 1)
    private void mango$replaceSectionUpdateTracker(CallbackInfo ci) {
        DirtySectionUpdateCoordinator.get().setTracker(this.sectionUpdateTracker);
    }

    @Inject(
        method = "setLevel(Lnet/minecraft/client/multiplayer/ClientLevel;)V",
        at = @At("RETURN"),
        require = 1
    )
    private void mango$clearSectionUpdatesForLevelUnload(@Nullable ClientLevel level, CallbackInfo ci) {
        if (level == null) {
            DirtySectionUpdateCoordinator.get().setTracker(null);
        }
    }

    @Inject(method = "setSectionDirty(IIIZ)V", at = @At("RETURN"), require = 1)
    private void mango$trackDirtySection(
        int sectionX,
        int sectionY,
        int sectionZ,
        boolean playerChanged,
        CallbackInfo ci
    ) {
        DirtySectionUpdateCoordinator.get().onSectionDirty(SectionPos.asLong(sectionX, sectionY, sectionZ));
    }

    @WrapOperation(
        method = "extract(Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/Camera;F)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;visibleSections()Lit/unimi/dsi/fastutil/objects/ObjectArrayList;",
            ordinal = 0
        ),
        require = 1
    )
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> mango$selectDirtyVisibleSections(
        LevelRenderer levelRenderer,
        Operation<ObjectArrayList<SectionRenderDispatcher.RenderSection>> original
    ) {
        return DirtySectionUpdateCoordinator.get().selectCandidates(original.call(levelRenderer), this.sectionUpdateTracker);
    }
}
