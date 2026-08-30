package org.fruitmc.mango.mixin.vulkan.terrain;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.fruitmc.mango.render.gpu.terrain.RenderSectionContentRevision;
import org.fruitmc.mango.render.gpu.terrain.TerrainSectionRegistry;
import org.fruitmc.mango.render.gpu.terrain.VisibleSectionContentTracker;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class RenderSectionMixin implements RenderSectionContentRevision {

    @Unique
    private static final long mango$INITIAL_BINDING_REVISION = 1L;

    @Shadow
    @Final
    private SectionRenderDispatcher this$0;

    @Shadow
    public abstract SectionMesh getSectionMesh();

    @Unique
    private volatile long mango$translucentBindingRevision = mango$INITIAL_BINDING_REVISION;

    @Unique
    private boolean mango$resetHadTranslucentGeometry;

    @Unique
    private boolean mango$resetHadBlockEntities;

    @Override
    @Unique
    public long mango$getTranslucentBindingRevision() {
        return this.mango$translucentBindingRevision;
    }

    @Inject(
        method = "reset()V",
        at = @At("HEAD"),
        require = 1
    )
    private void mango$onResetHead(CallbackInfo ci) {
        SectionMesh mesh = this.getSectionMesh();
        this.mango$resetHadTranslucentGeometry = mesh.hasTranslucentGeometry();
        this.mango$resetHadBlockEntities = !mesh.getRenderableBlockEntities().isEmpty();
        if (this.mango$resetHadTranslucentGeometry) {
            this.mango$translucentBindingRevision =
                VisibleSectionContentTracker.recordTranslucentBindingChange();
        }
    }

    @Inject(
        method = "reset()V",
        at = @At("RETURN"),
        require = 1
    )
    private void mango$onResetReturn(CallbackInfo ci) {
        TerrainSectionRegistry.get().onSectionChanged(
            this.this$0,
            (SectionRenderDispatcher.RenderSection)(Object)this
        );
        if (this.mango$resetHadTranslucentGeometry) {
            this.mango$translucentBindingRevision =
                VisibleSectionContentTracker.recordTranslucentBindingChange();
            VisibleSectionContentTracker.recordTranslucentGeometryChange();
            this.mango$resetHadTranslucentGeometry = false;
        }
        if (this.mango$resetHadBlockEntities) {
            VisibleSectionContentTracker.recordBlockEntityChange();
            this.mango$resetHadBlockEntities = false;
        }
    }

    @Inject(
        method = "setSectionMesh(Lnet/minecraft/client/renderer/chunk/SectionMesh;)Lnet/minecraft/client/renderer/chunk/SectionMesh;",
        at = @At("HEAD"),
        require = 1
    )
    private void mango$trackSectionMeshContents(
        SectionMesh mesh,
        CallbackInfoReturnable<SectionMesh> cir
    ) {
        SectionMesh previous = this.getSectionMesh();
        if (previous.hasTranslucentGeometry() || mesh.hasTranslucentGeometry()) {
            this.mango$translucentBindingRevision =
                VisibleSectionContentTracker.recordTranslucentBindingChange();
        }
    }

    @Inject(
        method = "setSectionMesh(Lnet/minecraft/client/renderer/chunk/SectionMesh;)Lnet/minecraft/client/renderer/chunk/SectionMesh;",
        at = @At("RETURN"),
        require = 1
    )
    private void mango$onSetSectionMeshReturn(SectionMesh mesh, CallbackInfoReturnable<SectionMesh> cir) {
        TerrainSectionRegistry.get().onSectionChanged(
            this.this$0,
            (SectionRenderDispatcher.RenderSection)(Object)this
        );
        if (mesh.hasTranslucentGeometry() || cir.getReturnValue().hasTranslucentGeometry()) {
            this.mango$translucentBindingRevision =
                VisibleSectionContentTracker.recordTranslucentBindingChange();
            VisibleSectionContentTracker.recordTranslucentGeometryChange();
        }
        if (!mesh.getRenderableBlockEntities().isEmpty()
            || !cir.getReturnValue().getRenderableBlockEntities().isEmpty()) {
            VisibleSectionContentTracker.recordBlockEntityChange();
        }
    }

    @Inject(
        method = "indexBufferUploadCallback(Lnet/minecraft/client/renderer/chunk/CompiledSectionMesh;Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;Z)V",
        at = @At("HEAD"),
        require = 1
    )
    private void mango$beforeSortedIndexBufferUpload(
        CompiledSectionMesh mesh,
        ChunkSectionLayer layer,
        boolean sortedIndexBuffer,
        CallbackInfo ci
    ) {
        if (sortedIndexBuffer && layer == ChunkSectionLayer.TRANSLUCENT) {
            this.mango$translucentBindingRevision =
                VisibleSectionContentTracker.recordTranslucentBindingChange();
        }
    }

    @Inject(
        method = "indexBufferUploadCallback(Lnet/minecraft/client/renderer/chunk/CompiledSectionMesh;Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;Z)V",
        at = @At("RETURN"),
        require = 1
    )
    private void mango$afterSortedIndexBufferUpload(
        CompiledSectionMesh mesh,
        ChunkSectionLayer layer,
        boolean sortedIndexBuffer,
        CallbackInfo ci
    ) {
        if (sortedIndexBuffer && layer == ChunkSectionLayer.TRANSLUCENT) {
            this.mango$translucentBindingRevision =
                VisibleSectionContentTracker.recordTranslucentBindingChange();
        }
    }
}
