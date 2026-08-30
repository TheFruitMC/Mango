package org.fruitmc.mango.client.gui.widgets;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import org.fruitmc.mango.client.gui.style.MangoTheme;

public class MangoSlider extends AbstractSliderButton {

    private static final int FONT_HEIGHT = 9;
    private static final int TEXT_LEFT_PADDING = 12;
    private static final int LABEL_END_OFFSET = 140;
    private static final int TRACK_HEIGHT = 3;
    private static final int THUMB_SIZE = 12;
    private static final int VALUE_RIGHT_MARGIN = 12;
    private static final int VALUE_TRACK_GAP = 8;
    private static final int LABEL_TRACK_GAP = 8;
    private static final int HIT_PADDING = 4;
    private static final int ALPHA = 0xFF;

    private final Component caption;
    private final int minValue;
    private final int maxValue;
    private final IntConsumer onChange;
    private final IntFunction<Component> valueFormatter;

    public MangoSlider(int x, int y, int width, int height, Component caption, int currentValue, int minValue, int maxValue, IntConsumer onChange) {
        this(x, y, width, height, caption, currentValue, minValue, maxValue, onChange, v -> Component.literal(String.valueOf(v)));
    }

    public MangoSlider(int x, int y, int width, int height, Component caption, int currentValue, int minValue, int maxValue, IntConsumer onChange, IntFunction<Component> valueFormatter) {
        super(x, y, width, height, Component.empty(), valueToRatio(currentValue, minValue, maxValue));
        this.caption = caption;
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.onChange = onChange;
        this.valueFormatter = valueFormatter;
    }

    private static double valueToRatio(int value, int min, int max) {
        if (max <= min) {
            return 0.0;
        }
        return Mth.clamp((double) (value - min) / (double) (max - min), 0.0, 1.0);
    }

    public int currentValue() {
        return minValue + (int) Math.round(this.value * (maxValue - minValue));
    }

    private int valueTextWidth() {
        return Minecraft.getInstance().font.width(valueFormatter.apply(currentValue()));
    }

    private int trackLeft() {
        int captionWidth = Minecraft.getInstance().font.width(this.caption);
        return this.getX() + TEXT_LEFT_PADDING + Math.min(captionWidth, LABEL_END_OFFSET) + LABEL_TRACK_GAP;
    }

    private int trackRight() {
        return this.getX() + this.getWidth() - VALUE_RIGHT_MARGIN - valueTextWidth() - VALUE_TRACK_GAP;
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        if (!this.isActive()) {
            return false;
        }
        int left = trackLeft() - HIT_PADDING;
        int right = trackRight() + HIT_PADDING;
        int top = this.getY() + this.getHeight() / 2 - THUMB_SIZE / 2 - HIT_PADDING;
        int bottom = this.getY() + this.getHeight() / 2 + THUMB_SIZE / 2 + HIT_PADDING;
        return mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom;
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        setValueFromMouse(event.x());
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dx, double dy) {
        setValueFromMouse(event.x());
    }

    private void setValueFromMouse(double mouseX) {
        int left = trackLeft();
        int right = trackRight();
        int usable = right - left;
        if (usable <= 0) {
            return;
        }
        setValue((mouseX - left) / usable);
    }

    @Override
    protected void updateMessage() {
    }

    @Override
    protected void applyValue() {
        onChange.accept(currentValue());
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float deltaTicks) {
        int x = this.getX();
        int y = this.getY();
        int width = this.getWidth();
        int height = this.getHeight();

        int bg = this.active ? MangoTheme.CARD : MangoTheme.CARD_DISABLED;
        graphics.fill(x, y, x + width, y + height, MangoTheme.withAlpha(bg, ALPHA));

        int captionColor = this.active ? MangoTheme.TEXT : MangoTheme.TEXT_SECONDARY;
        int textX = x + TEXT_LEFT_PADDING;
        int textY = y + (height - FONT_HEIGHT) / 2;
        graphics.text(Minecraft.getInstance().font, this.caption, textX, textY, MangoTheme.withAlpha(captionColor, ALPHA));

        Component valueText = valueFormatter.apply(currentValue());
        int valueWidth = Minecraft.getInstance().font.width(valueText);
        int valueX = x + width - VALUE_RIGHT_MARGIN - valueWidth;
        int valueColor = this.active ? MangoTheme.TEXT_SECONDARY : MangoTheme.CARD_DISABLED;
        graphics.text(Minecraft.getInstance().font, valueText, valueX, textY, MangoTheme.withAlpha(valueColor, ALPHA));

        int trackLeft = trackLeft();
        int trackRight = trackRight();
        int trackWidth = trackRight - trackLeft;
        if (trackWidth < THUMB_SIZE) {
            return;
        }

        int trackY = y + height / 2 - TRACK_HEIGHT / 2;
        int trackColor = this.active ? MangoTheme.TRACK : MangoTheme.TRACK_DISABLED;
        graphics.fill(trackLeft, trackY, trackRight, trackY + TRACK_HEIGHT, MangoTheme.withAlpha(trackColor, ALPHA));

        int filledRight = trackLeft + (int) (this.value * trackWidth);
        int filledColor = this.active ? MangoTheme.ACCENT : MangoTheme.TRACK_DISABLED;
        graphics.fill(trackLeft, trackY, filledRight, trackY + TRACK_HEIGHT, MangoTheme.withAlpha(filledColor, ALPHA));

        int thumbX = trackLeft + (int) (this.value * (trackWidth - THUMB_SIZE));
        int thumbY = y + height / 2 - THUMB_SIZE / 2;
        boolean hovered = this.isHoveredOrFocused();
        int thumbColor = this.active ? (hovered ? MangoTheme.ACCENT_HOVER : MangoTheme.THUMB) : MangoTheme.TEXT_SECONDARY;
        graphics.fill(thumbX - 1, thumbY - 1, thumbX + THUMB_SIZE + 1, thumbY + THUMB_SIZE + 1, MangoTheme.withAlpha(MangoTheme.SEPARATOR, ALPHA));
        graphics.fill(thumbX, thumbY, thumbX + THUMB_SIZE, thumbY + THUMB_SIZE, MangoTheme.withAlpha(thumbColor, ALPHA));

        this.handleCursor(graphics);
    }
}
