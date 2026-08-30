package org.fruitmc.mango.mixin.vulkan.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.debugchart.AbstractDebugChart;
import org.fruitmc.mango.render.gui.chart.ChartBatchContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractDebugChart.class)
public abstract class AbstractDebugChartMixin {

    @Inject(
        method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;II)V",
        at = @At("HEAD"),
        require = 1
    )
    private void mango$beginChartBatch(GuiGraphicsExtractor graphics, int left, int width, CallbackInfo ci) {
        ChartBatchContext.begin(graphics);
    }

    @Inject(
        method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;II)V",
        at = @At("RETURN"),
        require = 1
    )
    private void mango$endChartBatch(GuiGraphicsExtractor graphics, int left, int width, CallbackInfo ci) {
        ChartBatchContext.end(graphics);
    }
}
