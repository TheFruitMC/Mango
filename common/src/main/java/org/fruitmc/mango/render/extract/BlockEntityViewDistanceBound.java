package org.fruitmc.mango.render.extract;

public interface BlockEntityViewDistanceBound {

    int UNCOMPUTED_STAMP = -1;
    int mango$getBlockEntityViewDistance();
    int mango$getBlockEntityViewDistanceStamp();
    void mango$setBlockEntityViewDistance(int viewDistance, int stamp);
}
