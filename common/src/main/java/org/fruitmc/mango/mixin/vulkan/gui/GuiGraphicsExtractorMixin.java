package org.fruitmc.mango.mixin.vulkan.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.util.FormattedCharSequence;
import org.fruitmc.mango.render.gui.chart.ChartBatchContext;
import org.fruitmc.mango.render.gui.chart.ChartBatchRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiGraphicsExtractor.class)
public abstract class GuiGraphicsExtractorMixin implements ChartBatchContext.ChartBatchGraphics {

    @Shadow @Final private GuiRenderState guiRenderState;

    @Inject(
        method = "fill(IIIII)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 1
    )
    private void mango$captureChartFill(int x0, int y0, int x1, int y1, int color, CallbackInfo ci) {
        if (ChartBatchContext.captureFill((GuiGraphicsExtractor) (Object) this, x0, y0, x1, y1, color)) {
            ci.cancel();
        }
    }

    @Inject(
        method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V",
        at = @At("HEAD"),
        require = 1
    )
    private void mango$flushChartBeforeText(
        Font font,
        FormattedCharSequence text,
        int x,
        int y,
        int color,
        boolean dropShadow,
        CallbackInfo ci
    ) {
        ChartBatchContext.beforeText((GuiGraphicsExtractor) (Object) this);
    }

    @Override
    public void mango$addChartBatch(ChartBatchRenderState state) {
        this.guiRenderState.addGuiElement(state);
    }
}
