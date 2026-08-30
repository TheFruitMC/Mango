package org.fruitmc.mango.mixin.vulkan.terrain;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.Options;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.fruitmc.mango.render.gpu.terrain.TrackedVisibleSectionList;
import org.fruitmc.mango.render.gpu.terrain.VisibleSectionContentTracker;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class TranslucentResortMixin {

    @Shadow
    @Final
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;

    @Unique
    private long mango$lastResortBlockPos;

    @Unique
    private boolean mango$hasLastResortBlockPos;

    @Unique
    private long mango$lastResortSectionPos;

    @Unique
    private boolean mango$skipRotationLoop;

    @Unique
    private long mango$lastTranslucentGeometryRevision;

    @Unique
    private boolean mango$hasTranslucentGeometryRevision;

    @Unique
    private long mango$lastVisibleSectionsRevision;

    @Unique
    private boolean mango$hasVisibleSectionsRevision;

    @Unique
    private int mango$pendingRotationChecks;

    @Unique
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> mango$currentTranslucentSections =
        new ObjectArrayList<>();

    @Inject(
        method = "scheduleTranslucentSectionResort(Lnet/minecraft/world/phys/Vec3;)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 1
    )
    private void mango$skipStationaryResort(Vec3 cameraPos, CallbackInfo ci) {
        int cameraBlockX = Mth.floor(cameraPos.x());
        int cameraBlockY = Mth.floor(cameraPos.y());
        int cameraBlockZ = Mth.floor(cameraPos.z());
        long currentBlockPos = BlockPos.asLong(cameraBlockX, cameraBlockY, cameraBlockZ);
        long currentSectionPos = SectionPos.asLong(
            SectionPos.blockToSectionCoord(cameraBlockX),
            SectionPos.blockToSectionCoord(cameraBlockY),
            SectionPos.blockToSectionCoord(cameraBlockZ)
        );
        TrackedVisibleSectionList trackedSections = (TrackedVisibleSectionList)this.visibleSections;
        this.mango$currentTranslucentSections = trackedSections.translucentSections();
        long geometryRevision = VisibleSectionContentTracker.translucentGeometryRevision();
        long visibleSectionsRevision = trackedSections.translucentContentRevision();
        boolean sectionChanged = !this.mango$hasLastResortBlockPos
            || currentSectionPos != this.mango$lastResortSectionPos;
        boolean geometryChanged = !this.mango$hasTranslucentGeometryRevision
            || geometryRevision != this.mango$lastTranslucentGeometryRevision;
        boolean visibleSectionsChanged = !this.mango$hasVisibleSectionsRevision
            || visibleSectionsRevision != this.mango$lastVisibleSectionsRevision
            || !trackedSections.isTranslucentSelectionStable();
        boolean blockChanged = !this.mango$hasLastResortBlockPos
            || currentBlockPos != this.mango$lastResortBlockPos;
        if (sectionChanged || geometryChanged || visibleSectionsChanged) {
            this.mango$pendingRotationChecks = this.mango$currentTranslucentSections.size();
            this.mango$lastTranslucentGeometryRevision = geometryRevision;
            this.mango$hasTranslucentGeometryRevision = true;
            this.mango$lastVisibleSectionsRevision = visibleSectionsRevision;
            this.mango$hasVisibleSectionsRevision = true;
        }

        if (this.mango$hasLastResortBlockPos
            && currentBlockPos == this.mango$lastResortBlockPos
            && this.mango$pendingRotationChecks == 0) {
            ci.cancel();
            return;
        }

        this.mango$skipRotationLoop = this.mango$pendingRotationChecks == 0
            && !sectionChanged
            && !blockChanged;
        this.mango$lastResortBlockPos = currentBlockPos;
        this.mango$lastResortSectionPos = currentSectionPos;
        this.mango$hasLastResortBlockPos = true;
    }

    @ModifyExpressionValue(
        method = "scheduleTranslucentSectionResort(Lnet/minecraft/world/phys/Vec3;)V",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;visibleSections:Lit/unimi/dsi/fastutil/objects/ObjectArrayList;"
        ),
        require = 1
    )
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> mango$useTranslucentSections(
        ObjectArrayList<SectionRenderDispatcher.RenderSection> original
    ) {
        return this.mango$currentTranslucentSections;
    }

    @ModifyExpressionValue(
        method = "scheduleTranslucentSectionResort(Lnet/minecraft/world/phys/Vec3;)V",
        at = @At(
            value = "INVOKE",
            target = "Ljava/lang/Math;max(II)I"
        ),
        require = 1
    )
    private int mango$skipRotationWhenSectionStable(int resortsLeft) {
        if (this.mango$skipRotationLoop) {
            return 0;
        }
        if (this.mango$pendingRotationChecks == 0) {
            return resortsLeft;
        }
        int checks = Math.min(resortsLeft, this.mango$pendingRotationChecks);
        this.mango$pendingRotationChecks -= checks;
        return checks;
    }

    @Inject(
        method = "invalidateCompiledGeometry(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/client/Options;Lnet/minecraft/client/Camera;Lnet/minecraft/client/color/block/BlockColors;)V",
        at = @At("HEAD"),
        require = 1
    )
    private void mango$resetResortTrackingForGeometryInvalidation(
        ClientLevel level,
        Options options,
        Camera camera,
        BlockColors blockColors,
        CallbackInfo ci
    ) {
        this.mango$resetResortTracking();
    }

    @Inject(
        method = "resetLevelRenderData()V",
        at = @At("HEAD"),
        require = 1
    )
    private void mango$resetResortTrackingForLevelReset(CallbackInfo ci) {
        this.mango$resetResortTracking();
    }

    @Unique
    private void mango$resetResortTracking() {
        this.mango$lastResortBlockPos = 0L;
        this.mango$hasLastResortBlockPos = false;
        this.mango$lastResortSectionPos = 0L;
        this.mango$skipRotationLoop = false;
        this.mango$lastTranslucentGeometryRevision = 0L;
        this.mango$hasTranslucentGeometryRevision = false;
        this.mango$lastVisibleSectionsRevision = 0L;
        this.mango$hasVisibleSectionsRevision = false;
        this.mango$pendingRotationChecks = 0;
    }
}
