package org.fruitmc.mango.render.gui.chart;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import org.jspecify.annotations.Nullable;

public final class ChartBatchRenderState implements GuiElementRenderState {

    private final Matrix3x2fc pose;
    private final int[] x0;
    private final int[] y0;
    private final int[] x1;
    private final int[] y1;
    private final int[] colors;
    private final int size;
    private final ScreenRectangle bounds;

    public ChartBatchRenderState(
        Matrix3x2fc pose,
        int[] x0,
        int[] y0,
        int[] x1,
        int[] y1,
        int[] colors,
        int size,
        int minX,
        int minY,
        int maxX,
        int maxY
    ) {
        this.pose = new Matrix3x2f(pose);
        this.x0 = x0;
        this.y0 = y0;
        this.x1 = x1;
        this.y1 = y1;
        this.colors = colors;
        this.size = size;
        this.bounds = new ScreenRectangle(minX, minY, maxX - minX, maxY - minY).transformMaxBounds(this.pose);
    }

    @Override
    public void buildVertices(VertexConsumer vertexConsumer) {
        for (int i = 0; i < this.size; i++) {
            vertexConsumer.addVertexWith2DPose(this.pose, this.x0[i], this.y0[i]).setColor(this.colors[i]);
            vertexConsumer.addVertexWith2DPose(this.pose, this.x0[i], this.y1[i]).setColor(this.colors[i]);
            vertexConsumer.addVertexWith2DPose(this.pose, this.x1[i], this.y1[i]).setColor(this.colors[i]);
            vertexConsumer.addVertexWith2DPose(this.pose, this.x1[i], this.y0[i]).setColor(this.colors[i]);
        }
    }

    @Override
    public RenderPipeline pipeline() {
        return RenderPipelines.GUI;
    }

    @Override
    public TextureSetup textureSetup() {
        return TextureSetup.noTexture();
    }

    @Override
    public @Nullable ScreenRectangle scissorArea() {
        return null;
    }

    @Override
    public @Nullable ScreenRectangle bounds() {
        return this.bounds;
    }
}
