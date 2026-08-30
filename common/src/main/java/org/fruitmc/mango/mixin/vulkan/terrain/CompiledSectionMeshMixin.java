package org.fruitmc.mango.mixin.vulkan.terrain;

import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import org.fruitmc.mango.render.extract.BlockEntityViewDistanceBound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(CompiledSectionMesh.class)
public abstract class CompiledSectionMeshMixin implements BlockEntityViewDistanceBound {

    @Unique
    private int mango$blockEntityViewDistance;

    @Unique
    private int mango$blockEntityViewDistanceStamp = BlockEntityViewDistanceBound.UNCOMPUTED_STAMP;

    @Override
    public int mango$getBlockEntityViewDistance() {
        return this.mango$blockEntityViewDistance;
    }

    @Override
    public int mango$getBlockEntityViewDistanceStamp() {
        return this.mango$blockEntityViewDistanceStamp;
    }

    @Override
    public void mango$setBlockEntityViewDistance(int viewDistance, int stamp) {
        this.mango$blockEntityViewDistance = viewDistance;
        this.mango$blockEntityViewDistanceStamp = stamp;
    }
}
