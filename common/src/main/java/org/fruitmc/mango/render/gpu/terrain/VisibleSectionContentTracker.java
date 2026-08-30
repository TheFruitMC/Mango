package org.fruitmc.mango.render.gpu.terrain;

import java.util.concurrent.atomic.AtomicLong;

public final class VisibleSectionContentTracker {

    private static final long INITIAL_REVISION = 1L;

    private static final AtomicLong TRANSLUCENT_GEOMETRY_REVISION = new AtomicLong(INITIAL_REVISION);
    private static final AtomicLong TRANSLUCENT_BINDING_REVISION = new AtomicLong(INITIAL_REVISION);
    private static final AtomicLong BLOCK_ENTITY_REVISION = new AtomicLong(INITIAL_REVISION);

    private VisibleSectionContentTracker() {
    }

    public static void recordTranslucentGeometryChange() {
        TRANSLUCENT_GEOMETRY_REVISION.incrementAndGet();
    }

    public static long recordTranslucentBindingChange() {
        return TRANSLUCENT_BINDING_REVISION.incrementAndGet();
    }

    public static void recordBlockEntityChange() {
        BLOCK_ENTITY_REVISION.incrementAndGet();
    }

    public static long translucentGeometryRevision() {
        return TRANSLUCENT_GEOMETRY_REVISION.get();
    }

    public static long translucentBindingRevision() {
        return TRANSLUCENT_BINDING_REVISION.get();
    }

    public static long blockEntityRevision() {
        return BLOCK_ENTITY_REVISION.get();
    }
}
