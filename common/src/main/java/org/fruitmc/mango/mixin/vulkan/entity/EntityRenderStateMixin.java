package org.fruitmc.mango.mixin.vulkan.entity;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.fruitmc.mango.render.gpu.entity.MangoEntityRenderStateBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(EntityRenderState.class)
public abstract class EntityRenderStateMixin implements MangoEntityRenderStateBridge {

    @Unique
    private int mango$entityId = MangoEntityRenderStateBridge.NO_ENTITY_ID;

    @Override
    public void mango$setEntityId(int entityId) {
        this.mango$entityId = entityId;
    }

    @Override
    public int mango$entityId() {
        return this.mango$entityId;
    }
}
