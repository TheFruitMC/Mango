package org.fruitmc.mango.client.gui.widgets;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import org.fruitmc.mango.client.gui.style.MangoMotion;
import org.fruitmc.mango.client.gui.style.MangoTheme;

public final class MangoTabButton extends AbstractButton {

    private static final int FONT_HEIGHT = 9;
    private static final int ACCENT_BAR_WIDTH = 3;
    private static final int TEXT_LEFT_PADDING = 32;
    private static final float HOVER_LERP_SPEED = 12.0F;
    private static final float PULSE_BACKGROUND_INTENSITY = 0.10F;
    private static final int ALPHA = 0xFF;

    private final Runnable onSelect;
    private boolean selected;
    private float hoverProgress;
    private float pressProgress;

    public MangoTabButton(int x, int y, int width, int height, Component label, Runnable onSelect) {
        super(x, y, width, height, label);
        this.onSelect = onSelect;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        this.pressProgress = 0.0F;
        this.onSelect.run();
    }

    @Override
    public void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, this.createNarrationMessage());
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float deltaTicks) {
        float targetHover = this.isHoveredOrFocused() || this.selected ? 1.0F : 0.0F;
        this.hoverProgress = MangoMotion.lerp(this.hoverProgress, targetHover, HOVER_LERP_SPEED, deltaTicks);
        this.pressProgress = MangoMotion.advanceProgress(this.pressProgress, deltaTicks);

        int x = this.getX();
        int y = this.getY();
        int width = this.getWidth();
        int height = this.getHeight();

        float pulse = MangoMotion.pulse(this.pressProgress);
        float fillAmount = this.selected ? 1.0F : Math.max(this.hoverProgress, pulse);
        if (fillAmount > 0.001F) {
            int bg = this.selected ? MangoTheme.CARD_SELECTED : MangoTheme.CARD_HOVER;
            if (pulse > 0.0F) {
                bg = MangoMotion.color(bg, MangoTheme.ACCENT, pulse * PULSE_BACKGROUND_INTENSITY, ALPHA);
            }
            graphics.fill(x, y, x + width, y + height, MangoTheme.withAlpha(bg, (int) (ALPHA * fillAmount) & 0xFF));
        }

        if (this.selected) {
            graphics.fill(x, y, x + ACCENT_BAR_WIDTH, y + height, MangoTheme.withAlpha(MangoTheme.ACCENT, ALPHA));
        }

        Component message = this.getMessage();
        int textX = x + TEXT_LEFT_PADDING;
        int textY = y + (height - FONT_HEIGHT) / 2;
        float textBlend = this.selected ? 1.0F : this.hoverProgress;
        int textColor = MangoMotion.color(MangoTheme.SIDEBAR_TEXT_SECONDARY, MangoTheme.SIDEBAR_TEXT, textBlend, ALPHA);
        graphics.text(Minecraft.getInstance().font, message, textX, textY, textColor);
    }
}
