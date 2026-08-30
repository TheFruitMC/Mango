package org.fruitmc.mango.client.gui.screens;

import net.minecraft.client.Minecraft;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.TextAlignment;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.fruitmc.mango.Constants;
import org.fruitmc.mango.client.gui.style.MangoTheme;
import org.fruitmc.mango.client.gui.widgets.MangoButton;

public final class MangoBackendWarningScreen extends Screen {

    private static final Component TITLE = Component.translatable("mango.backendWarning.title");
    private static final Component MESSAGE_LINE_1 = Component.translatable("mango.backendWarning.message.line1");
    private static final Component MESSAGE_LINE_2 = Component.translatable("mango.backendWarning.message.line2");
    private static final Component MESSAGE_LINE_3 = Component.translatable("mango.backendWarning.message.line3");
    private static final Component BUTTON_SWITCH = Component.translatable("mango.backendWarning.switchAndQuit");
    private static final Component BUTTON_CANCEL = Component.translatable("mango.backendWarning.cancel");

    private static final int LINE_HEIGHT = 9;
    private static final int TITLE_GAP = 14;
    private static final int MESSAGE_GAP = 24;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_WIDTH = 200;
    private static final int BUTTON_SPACING = 8;
    private static final int CONTENT_MAX_WIDTH = 380;
    private static final int HORIZONTAL_MARGIN = 20;

    private final Runnable nextStep;
    private MultiLineLabel messageLines = MultiLineLabel.EMPTY;
    private int contentTop;

    public MangoBackendWarningScreen(Runnable nextStep) {
        super(TITLE);
        this.nextStep = nextStep;
    }

    @Override
    protected void init() {
        int maxWidth = Math.min(CONTENT_MAX_WIDTH, this.width - 2 * HORIZONTAL_MARGIN);
        this.messageLines = MultiLineLabel.create(this.font, maxWidth, MESSAGE_LINE_1, MESSAGE_LINE_2, MESSAGE_LINE_3);

        int messageHeight = this.messageLines.getLineCount() * LINE_HEIGHT;
        this.contentTop = (int) (this.height / 2.0 - messageHeight / 2.0);
        int buttonTop = this.contentTop + messageHeight + MESSAGE_GAP;

        int totalButtonWidth = 2 * BUTTON_WIDTH + BUTTON_SPACING;
        int buttonX = (int) (this.width / 2.0 - totalButtonWidth / 2.0);

        this.addRenderableWidget(new MangoButton(
            buttonX, buttonTop, BUTTON_WIDTH, BUTTON_HEIGHT, BUTTON_SWITCH, this::onSwitchAndQuit, true
        ));
        this.addRenderableWidget(new MangoButton(
            buttonX + BUTTON_WIDTH + BUTTON_SPACING, buttonTop, BUTTON_WIDTH, BUTTON_HEIGHT, BUTTON_CANCEL, this::onCancel, false
        ));
    }

    private void onSwitchAndQuit() {
        Minecraft minecraft = this.minecraft;
        minecraft.options.preferredGraphicsBackend().set(PreferredGraphicsApi.VULKAN);
        minecraft.options.save();
        Constants.LOG.info("User requested switch to Vulkan backend; quitting Minecraft for restart");
        minecraft.stop();
    }

    private void onCancel() {
        this.onClose();
    }

    @Override
    public void onClose() {
        this.nextStep.run();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float deltaTicks) {
        int titleY = this.contentTop - LINE_HEIGHT - TITLE_GAP;
        graphics.centeredText(this.font, this.title, this.width / 2, titleY, MangoTheme.ACCENT);

        this.messageLines.visitLines(
            TextAlignment.CENTER,
            this.width / 2,
            this.contentTop,
            LINE_HEIGHT,
            graphics.textRenderer()
        );

        super.extractRenderState(graphics, mouseX, mouseY, deltaTicks);
    }
}
