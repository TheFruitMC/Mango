package org.fruitmc.mango.client.gui.widgets;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.fruitmc.mango.client.gui.style.MangoMotion;
import org.fruitmc.mango.client.gui.style.MangoTheme;

public class MangoCycleButton<T> extends AbstractButton {

    private static final int FONT_HEIGHT = 9;
    private static final int TEXT_LEFT_PADDING = 12;
    private static final int VALUE_RIGHT_PADDING = 18;
    private static final int CHEVRON_RIGHT_MARGIN = 10;
    private static final int CHEVRON_WIDTH = 4;
    private static final int CHEVRON_HEIGHT = 7;
    private static final float HOVER_LERP_SPEED = 12.0F;
    private static final float PULSE_BACKGROUND_INTENSITY = 0.08F;
    private static final int ALPHA = 0xFF;

    private final Component caption;
    private final List<T> values;
    private final Supplier<T> getter;
    private final Consumer<T> setter;
    private final Function<T, Component> formatter;
    private float hoverProgress;
    private float pressProgress = 1.0F;

    public MangoCycleButton(int x, int y, int width, int height, Component caption, List<T> values, Supplier<T> getter, Consumer<T> setter, Function<T, Component> formatter) {
        super(x, y, width, height, caption);
        this.caption = caption;
        this.values = List.copyOf(values);
        this.getter = getter;
        this.setter = setter;
        this.formatter = formatter;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        this.pressProgress = 0.0F;
        T current = getter.get();
        int idx = values.indexOf(current);
        int next = idx >= 0 ? (idx + 1) % values.size() : 0;
        setter.accept(values.get(next));
    }

    public void refresh() {
    }

    @Override
    public void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, this.createNarrationMessage());
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float deltaTicks) {
        float targetHover = this.isHoveredOrFocused() ? 1.0F : 0.0F;
        this.hoverProgress = MangoMotion.lerp(this.hoverProgress, targetHover, HOVER_LERP_SPEED, deltaTicks);
        this.pressProgress = MangoMotion.advanceProgress(this.pressProgress, deltaTicks);

        int x = this.getX();
        int y = this.getY();
        int width = this.getWidth();
        int height = this.getHeight();

        int bg = this.active ? (this.isHoveredOrFocused() ? MangoTheme.CARD_HOVER : MangoTheme.CARD) : MangoTheme.CARD_DISABLED;
        float pulse = MangoMotion.pulse(this.pressProgress);
        if (pulse > 0.0F) {
            bg = MangoMotion.color(bg, MangoTheme.ACCENT, pulse * PULSE_BACKGROUND_INTENSITY, ALPHA);
        }
        graphics.fill(x, y, x + width, y + height, MangoTheme.withAlpha(bg, ALPHA));

        int captionColor = MangoMotion.color(MangoTheme.TEXT_SECONDARY, MangoTheme.TEXT, this.hoverProgress, ALPHA);
        int textX = x + TEXT_LEFT_PADDING;
        int textY = y + (height - FONT_HEIGHT) / 2;
        graphics.text(Minecraft.getInstance().font, this.caption, textX, textY, captionColor);

        Component valueText = formatter.apply(getter.get());
        int valueWidth = Minecraft.getInstance().font.width(valueText);
        int valueX = x + width - VALUE_RIGHT_PADDING - valueWidth - CHEVRON_WIDTH - 2;
        int valueColor = this.active ? MangoTheme.TEXT : MangoTheme.TEXT_SECONDARY;
        graphics.text(Minecraft.getInstance().font, valueText, valueX, textY, MangoTheme.withAlpha(valueColor, ALPHA));

        int chevronX = x + width - CHEVRON_RIGHT_MARGIN - CHEVRON_WIDTH;
        int chevronY = y + (height - CHEVRON_HEIGHT) / 2;
        int chevronColor = this.active ? MangoTheme.TEXT_SECONDARY : MangoTheme.CARD_DISABLED;
        drawChevron(graphics, chevronX, chevronY, MangoTheme.withAlpha(chevronColor, ALPHA));

        this.handleCursor(graphics);
    }

    private static void drawChevron(GuiGraphicsExtractor graphics, int x, int y, int color) {
        for (int i = 0; i < CHEVRON_HEIGHT; i++) {
            int half = i < CHEVRON_HEIGHT / 2 ? i : CHEVRON_HEIGHT - 1 - i;
            int startX = x + (CHEVRON_HEIGHT / 2 - 1 - half);
            graphics.fill(startX, y + i, startX + 1, y + i + 1, color);
        }
    }
}
