package org.fruitmc.mango.render.gpu.skinning;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;

import java.util.List;

public final class PoseSnapshot {

    private final PartPose[] poses;
    private final float[] xScales;
    private final float[] yScales;
    private final float[] zScales;
    private final boolean[] visibles;
    private final boolean[] skipDraws;

    private PoseSnapshot(
        PartPose[] poses,
        float[] xScales,
        float[] yScales,
        float[] zScales,
        boolean[] visibles,
        boolean[] skipDraws
    ) {
        this.poses = poses;
        this.xScales = xScales;
        this.yScales = yScales;
        this.zScales = zScales;
        this.visibles = visibles;
        this.skipDraws = skipDraws;
    }

    public static PoseSnapshot capture(ModelPart root) {
        List<ModelPart> parts = root.getAllParts();
        int count = parts.size();
        PartPose[] poses = new PartPose[count];
        float[] xScales = new float[count];
        float[] yScales = new float[count];
        float[] zScales = new float[count];
        boolean[] visibles = new boolean[count];
        boolean[] skipDraws = new boolean[count];
        for (int i = 0; i < count; i++) {
            ModelPart part = parts.get(i);
            poses[i] = part.storePose();
            xScales[i] = part.xScale;
            yScales[i] = part.yScale;
            zScales[i] = part.zScale;
            visibles[i] = part.visible;
            skipDraws[i] = part.skipDraw;
        }
        return new PoseSnapshot(poses, xScales, yScales, zScales, visibles, skipDraws);
    }

    public void restore(ModelPart root) {
        List<ModelPart> parts = root.getAllParts();
        int count = Math.min(parts.size(), this.poses.length);
        for (int i = 0; i < count; i++) {
            ModelPart part = parts.get(i);
            part.loadPose(this.poses[i]);
            part.xScale = this.xScales[i];
            part.yScale = this.yScales[i];
            part.zScale = this.zScales[i];
            part.visible = this.visibles[i];
            part.skipDraw = this.skipDraws[i];
        }
    }

    public static void resetToRest(ModelPart root) {
        for (ModelPart part : root.getAllParts()) {
            part.loadPose(PartPose.ZERO);
            part.visible = true;
            part.skipDraw = false;
        }
    }
}
