package org.fruitmc.mango.client.gui.widgets;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;
import java.util.function.Supplier;
import org.fruitmc.mango.client.gui.style.MangoMotion;
import org.fruitmc.mango.client.gui.style.MangoTheme;

public class MangoToggleButton extends AbstractButton {

    private static final int FONT_HEIGHT = 9;
    private static final int TEXT_LEFT_PADDING = 12;
    private static final int SWITCH_WIDTH = 30;
    private static final int SWITCH_HEIGHT = 16;
    private static final int SWITCH_KNOB_SIZE = 12;
    private static final int SWITCH_PADDING = 2;
    private static final int SWITCH_RIGHT_MARGIN = 10;
    private static final float HOVER_LERP_SPEED = 12.0F;
    private static final float KNOB_LERP_SPEED = 18.0F;
    private static final float PULSE_BACKGROUND_INTENSITY = 0.08F;
    private static final int ALPHA = 0xFF;

    private final Component caption;
    private final Supplier<Boolean> getter;
    private final Consumer<Boolean> setter;
    private float hoverProgress;
    private float knobProgress;
    private float pressProgress = 1.0F;

    public MangoToggleButton(int x, int y, int width, int height, Component caption, Supplier<Boolean> getter, Consumer<Boolean> setter) {
        super(x, y, width, height, caption);
        this.caption = caption;
        this.getter = getter;
        this.setter = setter;
        this.knobProgress = enabled() ? 1.0F : 0.0F;
    }

    private boolean enabled() {
        return Boolean.TRUE.equals(getter.get());
    }

    @Override
    public void onPress(InputWithModifiers input) {
        this.pressProgress = 0.0F;
        setter.accept(!enabled());
    }

    public void refresh() {
    }

    @Override
    public void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, this.createNarrationMessage());
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float deltaTicks) {
        boolean on = enabled();
        float targetHover = this.isHoveredOrFocused() ? 1.0F : 0.0F;
        this.hoverProgress = MangoMotion.lerp(this.hoverProgress, targetHover, HOVER_LERP_SPEED, deltaTicks);
        this.knobProgress = MangoMotion.lerp(this.knobProgress, on ? 1.0F : 0.0F, KNOB_LERP_SPEED, deltaTicks);
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

        int textColor = (on && this.active) ? MangoTheme.TEXT : MangoTheme.TEXT_SECONDARY;
        int textX = x + TEXT_LEFT_PADDING;
        int textY = y + (height - FONT_HEIGHT) / 2;
        graphics.text(Minecraft.getInstance().font, this.caption, textX, textY, MangoTheme.withAlpha(textColor, ALPHA));

        int switchX = x + width - SWITCH_WIDTH - SWITCH_RIGHT_MARGIN;
        int switchY = y + (height - SWITCH_HEIGHT) / 2;
        int pillColor = on ? MangoTheme.ACCENT : MangoTheme.SWITCH_OFF;
        graphics.fill(switchX, switchY, switchX + SWITCH_WIDTH, switchY + SWITCH_HEIGHT, MangoTheme.withAlpha(pillColor, ALPHA));

        int knobTravel = SWITCH_WIDTH - SWITCH_KNOB_SIZE - 2 * SWITCH_PADDING;
        int knobX = switchX + SWITCH_PADDING + (int) (this.knobProgress * knobTravel);
        int knobY = switchY + (SWITCH_HEIGHT - SWITCH_KNOB_SIZE) / 2;
        graphics.fill(knobX, knobY, knobX + SWITCH_KNOB_SIZE, knobY + SWITCH_KNOB_SIZE, MangoTheme.withAlpha(MangoTheme.THUMB, ALPHA));

        this.handleCursor(graphics);
    }
}
