package org.fruitmc.mango.render.extract;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.fruitmc.mango.render.gpu.terrain.TrackedVisibleSectionList;

import java.util.List;

public final class BlockEntitySectionCuller {

    private static final BlockEntitySectionCuller INSTANCE = new BlockEntitySectionCuller();

    private static final int UNBOUNDED_VIEW_DISTANCE = Integer.MAX_VALUE;

    private final ObjectArrayList<SectionRenderDispatcher.RenderSection> retained = new ObjectArrayList<>();

    private BlockEntitySectionCuller() {
    }

    public static BlockEntitySectionCuller get() {
        return INSTANCE;
    }


    public ObjectArrayList<SectionRenderDispatcher.RenderSection> retainNearSections(
        ObjectArrayList<SectionRenderDispatcher.RenderSection> visible,
        BlockEntityRenderDispatcher dispatcher,
        Vec3 cameraPosition,
        int effectiveRenderDistance
    ) {
        double camX = cameraPosition.x();
        double camY = cameraPosition.y();
        double camZ = cameraPosition.z();

        ObjectArrayList<SectionRenderDispatcher.RenderSection> out = this.retained;
        out.clear();
        ObjectArrayList<SectionRenderDispatcher.RenderSection> candidates =
            visible instanceof TrackedVisibleSectionList tracked
                ? tracked.blockEntitySections()
                : visible;

        for (int i = 0, size = candidates.size(); i < size; i++) {
            SectionRenderDispatcher.RenderSection section = candidates.get(i);
            SectionMesh mesh = section.getSectionMesh();
            List<BlockEntity> blockEntities = mesh.getRenderableBlockEntities();
            if (blockEntities.isEmpty()) {
                continue;
            }

            int viewDistance = viewDistanceBound(mesh, blockEntities, dispatcher, effectiveRenderDistance);
            AABB bounds = section.getBoundingBox();
            double dx = axisDistance(bounds.minX, bounds.maxX, camX);
            double dy = axisDistance(bounds.minY, bounds.maxY, camY);
            double dz = axisDistance(bounds.minZ, bounds.maxZ, camZ);
            double limit = (double) viewDistance * (double) viewDistance;
            if (dx * dx + dy * dy + dz * dz >= limit) {
                continue;
            }

            out.add(section);
        }

        return out;
    }

    private static double axisDistance(double min, double max, double camera) {
        return Math.max(Math.max(min - camera, camera - max), 0.0);
    }

    private static int viewDistanceBound(
        SectionMesh mesh,
        List<BlockEntity> blockEntities,
        BlockEntityRenderDispatcher dispatcher,
        int effectiveRenderDistance
    ) {
        if (!(mesh instanceof BlockEntityViewDistanceBound bound)) {
            return UNBOUNDED_VIEW_DISTANCE;
        }
        if (bound.mango$getBlockEntityViewDistanceStamp() == effectiveRenderDistance) {
            return bound.mango$getBlockEntityViewDistance();
        }

        int maxViewDistance = 0;
        for (int i = 0, size = blockEntities.size(); i < size; i++) {
            BlockEntity blockEntity = blockEntities.get(i);
            BlockEntityRenderer<BlockEntity, BlockEntityRenderState> renderer = dispatcher.getRenderer(blockEntity);
            if (renderer == null || renderer.shouldRenderOffScreen()) {
                continue;
            }
            int viewDistance = renderer.getViewDistance();
            if (viewDistance > maxViewDistance) {
                maxViewDistance = viewDistance;
            }
        }

        bound.mango$setBlockEntityViewDistance(maxViewDistance, effectiveRenderDistance);
        return maxViewDistance;
    }
}
