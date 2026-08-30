package org.fruitmc.mango.render.gui.chart;

import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.Arrays;

public final class ChartBatchContext {

    private static final int ZERO = 0;
    private static final int ONE = 1;
    private static final int GROWTH_FACTOR = 2;
    private static final int INITIAL_CAPACITY = 512;
    private static ChartBatchContext current;

    private final GuiGraphicsExtractor graphics;
    private int[] x0 = new int[INITIAL_CAPACITY];
    private int[] y0 = new int[INITIAL_CAPACITY];
    private int[] x1 = new int[INITIAL_CAPACITY];
    private int[] y1 = new int[INITIAL_CAPACITY];
    private int[] colors = new int[INITIAL_CAPACITY];
    private int size;
    private boolean captureEnabled = true;

    private ChartBatchContext(GuiGraphicsExtractor graphics) {
        this.graphics = graphics;
    }

    public static void begin(GuiGraphicsExtractor graphics) {
        if (current != null) {
            throw new IllegalStateException("Debug chart batch already active");
        }
        current = new ChartBatchContext(graphics);
    }

    public static void end(GuiGraphicsExtractor graphics) {
        ChartBatchContext context = current;
        if (context == null || context.graphics != graphics) {
            return;
        }
        context.flush();
        current = null;
    }

    public static boolean captureFill(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int color) {
        ChartBatchContext context = current;
        if (context == null || context.graphics != graphics || !context.captureEnabled) {
            return false;
        }
        context.add(x0, y0, x1, y1, color);
        return true;
    }

    public static void beforeText(GuiGraphicsExtractor graphics) {
        ChartBatchContext context = current;
        if (context != null && context.graphics == graphics) {
            context.flush();
            context.captureEnabled = false;
        }
    }

    private void add(int firstX, int firstY, int secondX, int secondY, int color) {
        if (this.size == this.x0.length) {
            int newCapacity = this.x0.length * GROWTH_FACTOR;
            this.x0 = Arrays.copyOf(this.x0, newCapacity);
            this.y0 = Arrays.copyOf(this.y0, newCapacity);
            this.x1 = Arrays.copyOf(this.x1, newCapacity);
            this.y1 = Arrays.copyOf(this.y1, newCapacity);
            this.colors = Arrays.copyOf(this.colors, newCapacity);
        }

        this.x0[this.size] = Math.min(firstX, secondX);
        this.y0[this.size] = Math.min(firstY, secondY);
        this.x1[this.size] = Math.max(firstX, secondX);
        this.y1[this.size] = Math.max(firstY, secondY);
        this.colors[this.size] = color;
        this.size++;
    }

    private void flush() {
        if (this.size == ZERO) {
            return;
        }

        int minX = this.x0[0];
        int minY = this.y0[0];
        int maxX = this.x1[0];
        int maxY = this.y1[0];
        for (int i = ONE; i < this.size; i++) {
            minX = Math.min(minX, this.x0[i]);
            minY = Math.min(minY, this.y0[i]);
            maxX = Math.max(maxX, this.x1[i]);
            maxY = Math.max(maxY, this.y1[i]);
        }

        ChartBatchRenderState state = new ChartBatchRenderState(
            this.graphics.pose(),
            this.x0,
            this.y0,
            this.x1,
            this.y1,
            this.colors,
            this.size,
            minX,
            minY,
            maxX,
            maxY
        );
        ((ChartBatchGraphics) this.graphics).mango$addChartBatch(state);

        this.captureEnabled = false;
        this.size = ZERO;
    }

    public interface ChartBatchGraphics {
        void mango$addChartBatch(ChartBatchRenderState state);
    }
}
