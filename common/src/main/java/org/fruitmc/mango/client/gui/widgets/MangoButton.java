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

public final class MangoButton extends AbstractButton {

    private static final int FONT_HEIGHT = 9;
    private static final float PULSE_INTENSITY = 0.12F;
    private static final int PRIMARY_TEXT_COLOR = 0xFFFFFFFF;
    private static final int ALPHA = 0xFF;

    private final Runnable onPress;
    private final boolean primary;
    private float pressProgress = 1.0F;

    public MangoButton(int x, int y, int width, int height, Component label, Runnable onPress) {
        this(x, y, width, height, label, onPress, false);
    }

    public MangoButton(int x, int y, int width, int height, Component label, Runnable onPress, boolean primary) {
        super(x, y, width, height, label);
        this.onPress = onPress;
        this.primary = primary;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        this.pressProgress = 0.0F;
        this.onPress.run();
    }

    @Override
    public void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, this.createNarrationMessage());
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float deltaTicks) {
        this.pressProgress = MangoMotion.advanceProgress(this.pressProgress, deltaTicks);

        int x = this.getX();
        int y = this.getY();
        int width = this.getWidth();
        int height = this.getHeight();

        boolean hovered = this.isHoveredOrFocused();
        int bg;
        int textColor;
        if (!this.active) {
            bg = MangoTheme.CARD_DISABLED;
            textColor = MangoTheme.TEXT_SECONDARY;
        } else if (this.primary) {
            bg = hovered ? MangoTheme.ACCENT_HOVER : MangoTheme.ACCENT;
            textColor = PRIMARY_TEXT_COLOR;
        } else {
            bg = hovered ? MangoTheme.CARD_HOVER : MangoTheme.CARD;
            textColor = MangoTheme.TEXT;
        }

        float pulse = MangoMotion.pulse(this.pressProgress);
        if (pulse > 0.0F && this.active) {
            bg = MangoMotion.color(bg, MangoTheme.TEXT, pulse * PULSE_INTENSITY, ALPHA);
        }
        graphics.fill(x, y, x + width, y + height, MangoTheme.withAlpha(bg, ALPHA));

        Component message = this.getMessage();
        int textX = x + (width - Minecraft.getInstance().font.width(message)) / 2;
        int textY = y + (height - FONT_HEIGHT) / 2;
        graphics.text(Minecraft.getInstance().font, message, textX, textY, MangoTheme.withAlpha(textColor, ALPHA));
        this.handleCursor(graphics);
    }
}
