package org.fruitmc.mango.render.gpu.entity;

public interface MangoEntityRenderStateBridge {

    int NO_ENTITY_ID = Integer.MIN_VALUE;

    void mango$setEntityId(int entityId);

    int mango$entityId();
}
