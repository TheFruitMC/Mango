package org.fruitmc.mango.client.gui.screens;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.VideoMode;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.UnsupportedGraphicsWarningScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.GpuWarnlistManager;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.fruitmc.mango.config.MangoConfig;
import org.fruitmc.mango.client.gui.settings.MangoOptionAdapter;
import org.fruitmc.mango.client.gui.settings.MangoUndoStack;
import org.fruitmc.mango.client.gui.settings.MangoVideoOptions;
import org.fruitmc.mango.client.gui.style.MangoMotion;
import org.fruitmc.mango.client.gui.style.MangoTheme;
import org.fruitmc.mango.client.gui.widgets.MangoButton;
import org.fruitmc.mango.client.gui.widgets.MangoTabButton;
import org.fruitmc.mango.client.gui.widgets.MangoToggleButton;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class MangoVideoSettingsScreen extends Screen {

    private static final Component TITLE = Component.translatable("options.videoTitle");
    private static final Component RESTART_REQUIRED = Component.translatable("options.restartRequired").withColor(-2142128);
    private static final Component IMPROVED_TRANSPARENCY = Component.translatable("options.improvedTransparency").withStyle(ChatFormatting.ITALIC);
    private static final Component WARNING_MESSAGE = Component.translatable("options.graphics.warning.message", IMPROVED_TRANSPARENCY, IMPROVED_TRANSPARENCY);
    private static final Component WARNING_TITLE = Component.translatable("options.graphics.warning.title").withStyle(ChatFormatting.RED);
    private static final Component BUTTON_ACCEPT = Component.translatable("options.graphics.warning.accept");
    private static final Component BUTTON_CANCEL = Component.translatable("options.graphics.warning.cancel");
    private static final Component BUTTON_DONE = Component.translatable("mango.videoSettings.done");
    private static final Component BUTTON_UNDO_LAST = Component.translatable("mango.videoSettings.undoLast");
    private static final Component BUTTON_UNDO_ALL = Component.translatable("mango.videoSettings.undoAll");
    private static final Component SEARCH_HINT = Component.translatable("mango.videoSettings.search").withStyle(ChatFormatting.GRAY);
    private static final Component SECTION_MINECRAFT = Component.translatable("mango.videoSection.minecraft");
    private static final Component SECTION_MANGO = Component.translatable("mango.videoSection.mango");

    private static final int TOP_BAR_HEIGHT = 36;
    private static final int SIDEBAR_WIDTH = 150;
    private static final int FOOTER_HEIGHT = 40;
    private static final int CONTENT_PADDING = 20;
    private static final int SIDEBAR_TOP_PADDING = 8;
    private static final int SIDEBAR_PADDING_X = 10;
    private static final int SIDEBAR_ITEM_HEIGHT = 28;
    private static final int SIDEBAR_ITEM_SPACING = 2;
    private static final int ROW_HEIGHT = 24;
    private static final int ROW_SPACING = 1;
    private static final int SECTION_GAP = 12;
    private static final int SECTION_HEADER_HEIGHT = 24;
    private static final int SEARCH_HEIGHT = 18;
    private static final int SEARCH_WIDTH_OFFSET = 2 * SIDEBAR_PADDING_X;
    private static final int ICON_SIZE = 16;
    private static final int ICON_TEXT_GAP = 6;
    private static final int TAB_TEXT_X = SIDEBAR_PADDING_X + ICON_SIZE + ICON_TEXT_GAP - 3;
    private static final int DONE_BUTTON_WIDTH = 70;
    private static final int DONE_BUTTON_HEIGHT = 24;
    private static final int FOOTER_BUTTON_WIDTH = 90;
    private static final int FOOTER_BUTTON_HEIGHT = 24;
    private static final int FOOTER_BUTTON_SPACING = 8;
    private static final int TITLE_X = 20;
    private static final int TITLE_Y_OFFSET = 14;
    private static final float CONTENT_FADE_SPEED = 16.0F;

    private static final Identifier MANGO_ICON = Identifier.fromNamespaceAndPath("mango", "textures/gui/icon.png");
    private static final Identifier GRASS_ICON = Identifier.fromNamespaceAndPath("minecraft", "textures/block/grass_block_side.png");

    private final Screen lastScreen;
    private final Options options;
    private final GpuWarnlistManager gpuWarnlistManager;
    private final MangoUndoStack undoStack = new MangoUndoStack();
    private final int oldMipmaps;
    private final int oldAnisotropyBit;
    private final TextureFilteringMethod oldTextureFiltering;

    private final List<MangoTabButton> tabButtons = new ArrayList<>();
    private final List<Tab> tabs = List.of(Tab.values());

    private StringWidget titleWidget;
    private @Nullable StringWidget restartWarning;
    private MangoButton doneButton;
    private MangoButton undoLastButton;
    private MangoButton undoAllButton;
    private EditBox searchBox;
    private ScrollableLayout contentScroll;
    private GridLayout contentGrid;
    private @Nullable AbstractWidget contentContainer;

    private @Nullable AbstractWidget fullscreenWidget;
    private @Nullable AbstractWidget transparencyWidget;
    private @Nullable AbstractWidget anisotropyWidget;

    private Tab currentTab = Tab.GENERAL;
    private String searchQuery = "";
    private boolean searching = false;
    private float contentFade = 1.0F;

    private int searchY;
    private int mcHeaderY;
    private int generalTabY;
    private int qualityTabY;
    private int mangoHeaderY;
    private int performanceTabY;
    private int contentWidth;

    public MangoVideoSettingsScreen(Screen lastScreen, Minecraft minecraft, Options options) {
        super(minecraft, minecraft.font, TITLE);
        this.lastScreen = lastScreen;
        this.options = options;
        this.gpuWarnlistManager = minecraft.getGpuWarnlistManager();
        this.gpuWarnlistManager.resetWarnings();
        if (options.improvedTransparency().get()) {
            this.gpuWarnlistManager.dismissWarning();
        }

        this.oldMipmaps = options.mipmapLevels().get();
        this.oldAnisotropyBit = options.maxAnisotropyBit().get();
        this.oldTextureFiltering = options.textureFiltering().get();

        this.registerTextures();
    }

    private void registerTextures() {
        minecraft.getTextureManager().registerAndLoad(MANGO_ICON, new SimpleTexture(MANGO_ICON));
        minecraft.getTextureManager().registerAndLoad(GRASS_ICON, new SimpleTexture(GRASS_ICON));
    }

    @Override
    protected void init() {
        this.titleWidget = new StringWidget(TITLE.copy().withColor(MangoTheme.SIDEBAR_TEXT), this.font);
        this.addRenderableWidget(this.titleWidget);

        this.doneButton = new MangoButton(0, 0, DONE_BUTTON_WIDTH, DONE_BUTTON_HEIGHT, BUTTON_DONE, this::onClose, true);
        this.addRenderableWidget(this.doneButton);

        this.undoLastButton = new MangoButton(0, 0, FOOTER_BUTTON_WIDTH, FOOTER_BUTTON_HEIGHT, BUTTON_UNDO_LAST, this::undoLast);
        this.undoAllButton = new MangoButton(0, 0, FOOTER_BUTTON_WIDTH, FOOTER_BUTTON_HEIGHT, BUTTON_UNDO_ALL, this::undoAll);
        this.addRenderableWidget(this.undoLastButton);
        this.addRenderableWidget(this.undoAllButton);

        this.searchBox = new EditBox(this.font, 0, 0, SIDEBAR_WIDTH - SEARCH_WIDTH_OFFSET, SEARCH_HEIGHT, Component.translatable("mango.videoSettings.search"));
        this.searchBox.setMaxLength(50);
        this.searchBox.setHint(SEARCH_HINT);
        this.searchBox.setTextColor(MangoTheme.TEXT);
        this.searchBox.setTextColorUneditable(MangoTheme.TEXT_SECONDARY);
        this.searchBox.setResponder(this::onSearchChanged);
        this.addRenderableWidget(this.searchBox);

        this.buildSidebar();
        this.buildContentPanel();
        this.selectTab(this.currentTab, false);

        this.repositionElements();
    }

    @Override
    protected void repositionElements() {
        this.titleWidget.setX(TITLE_X);
        this.titleWidget.setY(TITLE_Y_OFFSET);

        this.doneButton.setX(this.width - CONTENT_PADDING - DONE_BUTTON_WIDTH);
        this.doneButton.setY((TOP_BAR_HEIGHT - DONE_BUTTON_HEIGHT) / 2);

        if (this.restartWarning != null) {
            this.restartWarning.setX(TITLE_X + this.titleWidget.getWidth() + 8);
            this.restartWarning.setY(TITLE_Y_OFFSET + 2);
        }

        this.contentWidth = this.width - SIDEBAR_WIDTH - 2 * CONTENT_PADDING;

        int y = TOP_BAR_HEIGHT + SIDEBAR_TOP_PADDING;

        this.searchY = y;
        this.searchBox.setX(SIDEBAR_PADDING_X);
        this.searchBox.setY(this.searchY);
        this.searchBox.setWidth(SIDEBAR_WIDTH - SEARCH_WIDTH_OFFSET);
        y += SEARCH_HEIGHT + SECTION_GAP;

        this.mcHeaderY = y;
        y += SECTION_HEADER_HEIGHT;

        this.generalTabY = y;
        y += SIDEBAR_ITEM_HEIGHT + SIDEBAR_ITEM_SPACING;

        this.qualityTabY = y;
        y += SIDEBAR_ITEM_HEIGHT + SIDEBAR_ITEM_SPACING + SECTION_GAP;

        this.mangoHeaderY = y;
        y += SECTION_HEADER_HEIGHT;

        this.performanceTabY = y;

        int[] tabYs = {this.generalTabY, this.qualityTabY, this.performanceTabY};
        for (int i = 0; i < this.tabButtons.size(); i++) {
            MangoTabButton button = this.tabButtons.get(i);
            button.setX(0);
            button.setY(tabYs[i]);
            button.setWidth(SIDEBAR_WIDTH);
            button.setHeight(SIDEBAR_ITEM_HEIGHT);
        }

        int contentX = SIDEBAR_WIDTH + CONTENT_PADDING;
        int contentY = TOP_BAR_HEIGHT + CONTENT_PADDING;
        int contentHeight = this.height - TOP_BAR_HEIGHT - FOOTER_HEIGHT - 2 * CONTENT_PADDING;

        if (this.contentScroll != null) {
            this.contentScroll.setX(contentX);
            this.contentScroll.setY(contentY);
            this.contentGrid.visitWidgets((AbstractWidget w) -> w.setWidth(this.contentWidth));
            this.contentGrid.arrangeElements();
            this.contentScroll.setMinWidth(this.contentWidth);
            this.contentScroll.setMaxHeight(contentHeight);
            this.contentScroll.arrangeElements();
        }

        int footerY = this.height - FOOTER_HEIGHT + (FOOTER_HEIGHT - FOOTER_BUTTON_HEIGHT) / 2;
        int footerX = SIDEBAR_WIDTH + CONTENT_PADDING;
        this.undoLastButton.setX(footerX);
        this.undoLastButton.setY(footerY);
        this.undoAllButton.setX(footerX + FOOTER_BUTTON_WIDTH + FOOTER_BUTTON_SPACING);
        this.undoAllButton.setY(footerY);
    }

    private void buildSidebar() {
        for (Tab tab : this.tabs) {
            Component label = Component.translatable(tab.translationKey);
            MangoTabButton button = new MangoTabButton(0, 0, SIDEBAR_WIDTH, SIDEBAR_ITEM_HEIGHT, label, () -> {
                this.clearSearch();
                this.selectTab(tab, true);
            });
            this.tabButtons.add(button);
            this.addRenderableWidget(button);
        }
    }

    private void buildContentPanel() {
        this.contentGrid = new GridLayout();
        this.contentGrid.columnSpacing(0).rowSpacing(ROW_SPACING);
        this.contentScroll = new ScrollableLayout(this.minecraft, this.contentGrid,
            this.height - TOP_BAR_HEIGHT - FOOTER_HEIGHT - 2 * CONTENT_PADDING,
            ScrollableLayout.ReserveStrategy.RIGHT);
        this.contentScroll.visitChildren(child -> {
            if (child instanceof AbstractWidget) {
                AbstractWidget widget = (AbstractWidget) child;
                this.contentContainer = widget;
                this.addWidget(widget);
            }
        });
    }

    private void onSearchChanged(String query) {
        this.searchQuery = query == null ? "" : query;
        boolean wasSearching = this.searching;
        this.searching = !this.searchQuery.isBlank();

        if (this.searching != wasSearching) {
            if (this.searching) {
                for (MangoTabButton button : this.tabButtons) {
                    button.setSelected(false);
                }
            } else {
                this.selectTab(this.currentTab, false);
            }
        }
        this.rebuildTabContent();
    }

    private void clearSearch() {
        if (!this.searchQuery.isEmpty()) {
            this.searchBox.setValue("");
        }
    }

    private void selectTab(Tab tab, boolean animate) {
        this.currentTab = tab;
        this.searching = false;

        for (int i = 0; i < this.tabButtons.size(); i++) {
            this.tabButtons.get(i).setSelected(this.tabs.get(i) == tab);
        }

        if (animate) {
            this.contentFade = 0.0F;
        }
        this.rebuildTabContent();
        this.resetContentScroll();

        if (animate && this.minecraft.getLastInputType().isKeyboard() && !this.tabButtons.isEmpty()) {
            this.setFocused(this.tabButtons.get(this.tabs.indexOf(tab)));
        }
    }

    private void resetContentScroll() {
        if (this.contentContainer instanceof AbstractScrollArea) {
            AbstractScrollArea scrollArea = (AbstractScrollArea) this.contentContainer;
            scrollArea.setScrollAmount(0.0);
        }
    }

    private void undoLast() {
        if (this.undoStack.canUndo()) {
            this.undoStack.undo();
            this.rebuildTabContent();
        }
    }

    private void undoAll() {
        this.undoStack.undoAll();
        this.rebuildTabContent();
    }

    private void rebuildTabContent() {
        this.fullscreenWidget = null;
        this.transparencyWidget = null;
        this.anisotropyWidget = null;
        this.contentGrid.removeChildren();

        int row;
        if (this.searching) {
            row = this.buildSearchResults();
        } else {
            row = switch (this.currentTab) {
                case GENERAL -> this.buildGeneralTab(0);
                case QUALITY -> this.buildQualityTab(0);
                case PERFORMANCE -> this.buildPerformanceTab(0);
            };
        }

        this.contentGrid.addChild(new SpacerElement(0, 4), row, 0);
        this.repositionElements();
    }

    private int buildSearchResults() {
        int row = 0;
        row = this.buildGeneralTab(row);
        row = this.buildQualityTab(row);
        row = this.buildPerformanceTab(row);
        return row;
    }

    private int buildGeneralTab(int row) {
        row = this.addSectionHeader(row, "options.video.display.header");
        row = this.addDisplaySection(row);
        row = this.addSectionGap(row);
        row = this.addSectionHeader(row, "options.video.preferences.header");
        row = this.addPreferencesSection(row);
        return row;
    }

    private int buildQualityTab(int row) {
        row = this.addSectionHeader(row, "options.video.quality.header");

        row = this.addRow(row, this.options.graphicsPreset());
        row = this.addRow(row, this.options.biomeBlendRadius());
        row = this.addRow(row, this.options.renderDistance());
        row = this.addRow(row, this.options.prioritizeChunkUpdates());
        row = this.addRow(row, this.options.simulationDistance());
        row = this.addRow(row, this.options.ambientOcclusion());
        row = this.addRow(row, this.options.cloudStatus());
        row = this.addRow(row, this.options.particles());
        row = this.addRow(row, this.options.mipmapLevels());
        row = this.addRow(row, this.options.entityShadows());
        row = this.addRow(row, this.options.entityDistanceScaling());
        row = this.addRow(row, this.options.menuBackgroundBlurriness());
        row = this.addRow(row, this.options.cloudRange());
        row = this.addRow(row, this.options.cutoutLeaves());
        row = this.addRow(row, this.options.improvedTransparency());
        row = this.addRow(row, this.options.textureFiltering());
        row = this.addRow(row, this.options.maxAnisotropyBit());
        row = this.addRow(row, this.options.weatherRadius());

        return row;
    }

    private int buildPerformanceTab(int row) {
        row = MangoVideoOptions.appendSection(this.contentGrid, row, this.contentWidth, this.undoStack, this.searching ? this.searchQuery : null);
        return row;
    }

    private int addDisplaySection(int row) {
        Window window = this.minecraft.getWindow();
        Monitor monitor = window.findBestMonitor();
        int initialValue = monitor == null ? -1 : window.getPreferredFullscreenVideoMode().map(monitor::indexOfMode).orElse(-1);

        OptionInstance<Integer> fullscreenOption = new OptionInstance<>(
            "options.fullscreen.resolution",
            OptionInstance.noTooltip(),
            (Component caption, Integer value) -> {
                if (monitor == null) {
                    return Component.translatable("options.fullscreen.unavailable");
                }
                if (value == -1) {
                    return Options.genericValueLabel(caption, Component.translatable("options.fullscreen.current"));
                }
                VideoMode mode = monitor.mode(value);
                return Options.genericValueLabel(
                    caption,
                    Component.translatable(
                        "options.fullscreen.entry",
                        mode.getWidth(), mode.getHeight(), mode.getRefreshRate(),
                        mode.getRedBits() + mode.getGreenBits() + mode.getBlueBits()
                    )
                );
            },
            new OptionInstance.IntRange(-1, monitor != null ? monitor.modeCount() - 1 : -1),
            initialValue,
            (Integer value) -> {
                if (monitor != null) {
                    Optional<VideoMode> previous = window.getPreferredFullscreenVideoMode();
                    this.undoStack.push(() -> window.setPreferredFullscreenVideoMode(previous));
                    window.setPreferredFullscreenVideoMode(value == -1 ? Optional.empty() : Optional.of(monitor.mode(value)));
                }
            }
        );

        row = this.addRow(row, fullscreenOption);
        row = this.addRow(row, this.options.framerateLimit());
        row = this.addRow(row, this.options.enableVsync());
        row = this.addRow(row, this.options.inactivityFpsLimit());
        row = this.addRow(row, this.options.guiScale());
        row = this.addRow(row, this.options.fullscreen());
        row = this.addRow(row, this.options.exclusiveFullscreen());
        row = this.addRow(row, this.options.gamma());
        row = this.addRow(row, this.options.preferredGraphicsBackend());

        return row;
    }

    private int addPreferencesSection(int row) {
        row = this.addRow(row, this.options.showAutosaveIndicator());
        row = this.addRow(row, this.options.vignette());
        row = this.addRow(row, this.options.attackIndicator());
        row = this.addRow(row, this.options.chunkSectionFadeInTime());
        return row;
    }

    private int addSectionHeader(int row, String translationKey) {
        if (this.searching) {
            return row;
        }
        StringWidget header = new StringWidget(
            Component.translatable(translationKey).withColor(MangoTheme.SIDEBAR_TEXT_SECONDARY),
            this.font
        );
        header.setHeight(SECTION_HEADER_HEIGHT);
        this.contentGrid.addChild(header, row, 0);
        return row + 1;
    }

    private int addSectionGap(int row) {
        if (this.searching) {
            return row;
        }
        this.contentGrid.addChild(new SpacerElement(0, SECTION_GAP), row, 0);
        return row + 1;
    }

    private int addRow(int row, OptionInstance<?> option) {
        if (this.searching && !matchesSearch(option)) {
            return row;
        }
        AbstractWidget widget = MangoOptionAdapter.from(option, this.options, this.undoStack);
        if (option == this.options.improvedTransparency()) {
            this.transparencyWidget = widget;
        } else if (option == this.options.maxAnisotropyBit()) {
            this.anisotropyWidget = widget;
        } else if (option == this.options.fullscreen()) {
            this.fullscreenWidget = widget;
        }
        widget.setHeight(ROW_HEIGHT);
        this.contentGrid.addChild(widget, row, 0);
        return row + 1;
    }

    private boolean matchesSearch(OptionInstance<?> option) {
        if (this.searchQuery.isBlank()) {
            return true;
        }
        Component caption = ((org.fruitmc.mango.mixin.accessor.OptionInstanceAccessor) (Object) option).mango$getCaption();
        return caption.getString().toLowerCase(Locale.ROOT).contains(this.searchQuery.toLowerCase(Locale.ROOT));
    }

    @Override
    public void tick() {
        if (this.undoLastButton != null) {
            this.undoLastButton.active = this.undoStack.canUndo();
        }
        if (this.undoAllButton != null) {
            this.undoAllButton.active = this.undoStack.canUndo();
        }

        if (this.anisotropyWidget != null) {
            this.anisotropyWidget.active = this.options.textureFiltering().get() == TextureFilteringMethod.ANISOTROPIC;
        }

        boolean restartRequired = this.options.isRestartRequiredToApplyVideoSettings();
        if (restartRequired && (this.restartWarning == null || !this.restartWarning.visible)) {
            if (this.restartWarning == null) {
                this.restartWarning = new StringWidget(RESTART_REQUIRED, this.font);
                this.addRenderableWidget(this.restartWarning);
            }
            this.restartWarning.visible = true;
            this.repositionElements();
        } else if (!restartRequired && this.restartWarning != null && this.restartWarning.visible) {
            this.restartWarning.visible = false;
            this.repositionElements();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float deltaTicks) {
        graphics.fill(0, TOP_BAR_HEIGHT, SIDEBAR_WIDTH, this.height, MangoTheme.withAlpha(MangoTheme.SIDEBAR, 0xE6));
        graphics.fill(SIDEBAR_WIDTH, TOP_BAR_HEIGHT, SIDEBAR_WIDTH + 1, this.height, MangoTheme.withAlpha(MangoTheme.SEPARATOR, 0xFF));
        graphics.fill(0, TOP_BAR_HEIGHT, this.width, TOP_BAR_HEIGHT + 1, MangoTheme.withAlpha(MangoTheme.SEPARATOR, 0xFF));

        int footerTop = this.height - FOOTER_HEIGHT;
        graphics.fill(SIDEBAR_WIDTH, footerTop, this.width, footerTop + 1, MangoTheme.withAlpha(MangoTheme.SEPARATOR, 0xFF));

        this.drawSidebarHeaders(graphics);

        super.extractRenderState(graphics, mouseX, mouseY, deltaTicks);
        if (this.contentContainer instanceof AbstractScrollArea) {
            AbstractScrollArea scrollArea = (AbstractScrollArea) this.contentContainer;
            scrollArea.extractRenderState(graphics, mouseX, mouseY, deltaTicks);
            this.drawCustomScrollbar(graphics, scrollArea);
        }

        this.contentFade = MangoMotion.lerp(this.contentFade, 1.0F, CONTENT_FADE_SPEED, deltaTicks);
        if (this.contentFade < 0.99F) {
            int overlayAlpha = (int) ((1.0F - this.contentFade) * 200) & 0xFF;
            int contentX = SIDEBAR_WIDTH + CONTENT_PADDING;
            int contentY = TOP_BAR_HEIGHT + CONTENT_PADDING;
            int contentHeight = this.height - TOP_BAR_HEIGHT - FOOTER_HEIGHT - 2 * CONTENT_PADDING;
            graphics.fill(contentX, contentY, contentX + this.contentWidth, contentY + contentHeight, MangoTheme.withAlpha(MangoTheme.BG, overlayAlpha));
        }
    }

    private void drawSidebarHeaders(GuiGraphicsExtractor graphics) {
        int iconX = SIDEBAR_PADDING_X;
        int iconY = this.mcHeaderY + (SECTION_HEADER_HEIGHT - ICON_SIZE) / 2;
        drawGrassIcon(graphics, iconX, iconY);
        graphics.text(this.font, SECTION_MINECRAFT, SIDEBAR_PADDING_X + ICON_SIZE + ICON_TEXT_GAP, this.mcHeaderY + (SECTION_HEADER_HEIGHT - 9) / 2, MangoTheme.withAlpha(MangoTheme.SIDEBAR_TEXT, 0xFF));

        iconY = this.mangoHeaderY + (SECTION_HEADER_HEIGHT - ICON_SIZE) / 2;
        drawMangoIcon(graphics, iconX, iconY);
        graphics.text(this.font, SECTION_MANGO, SIDEBAR_PADDING_X + ICON_SIZE + ICON_TEXT_GAP, this.mangoHeaderY + (SECTION_HEADER_HEIGHT - 9) / 2, MangoTheme.withAlpha(MangoTheme.SIDEBAR_TEXT, 0xFF));
    }

    private static void drawGrassIcon(GuiGraphicsExtractor graphics, int x, int y) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, GRASS_ICON, x, y, 0.0F, 0.0F, ICON_SIZE, ICON_SIZE, 16, 16, 16, 16, -1);
    }

    private static void drawMangoIcon(GuiGraphicsExtractor graphics, int x, int y) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, MANGO_ICON, x, y, 0.0F, 0.0F, ICON_SIZE, ICON_SIZE, 128, 128, 128, 128, -1);
    }

    private void drawCustomScrollbar(GuiGraphicsExtractor graphics, AbstractScrollArea scrollArea) {
        org.fruitmc.mango.mixin.accessor.AbstractScrollAreaAccessor accessor =
            (org.fruitmc.mango.mixin.accessor.AbstractScrollAreaAccessor) scrollArea;
        int barX = accessor.mango$getScrollBarX();
        int barY = scrollArea.getY();
        int barH = scrollArea.getHeight();

        graphics.fill(barX - 1, barY, barX + 8, barY + barH, MangoTheme.withAlpha(MangoTheme.BG, 0xFF));

        if (accessor.mango$isScrollable()) {
            int thumbH = accessor.mango$getScrollerHeight();
            int thumbY = accessor.mango$getScrollBarY();
            graphics.fill(barX, barY, barX + 6, barY + barH, MangoTheme.withAlpha(MangoTheme.TRACK, 0xFF));
            graphics.fill(barX + 1, thumbY, barX + 5, thumbY + thumbH, MangoTheme.withAlpha(MangoTheme.THUMB, 0xFF));
        }
    }

    @Override
    public void onClose() {
        this.minecraft.getWindow().changeFullscreenVideoMode();
        this.minecraft.gui.setScreen(this.lastScreen);
    }

    @Override
    public void removed() {
        this.options.save();
        if (this.options.mipmapLevels().get() != this.oldMipmaps
            || this.options.maxAnisotropyBit().get() != this.oldAnisotropyBit
            || this.options.textureFiltering().get() != this.oldTextureFiltering) {
            this.minecraft.updateMaxMipLevel(this.options.mipmapLevels().get());
            this.minecraft.delayTextureReload();
        }

        MangoConfig.INSTANCE.save(this.minecraft.gameDirectory.toPath().resolve("config"));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            if (this.gpuWarnlistManager.isShowingWarning()) {
                List<Component> warningMessage = Lists.newArrayList(WARNING_MESSAGE, CommonComponents.NEW_LINE);
                String rendererWarnings = this.gpuWarnlistManager.getRendererWarnings();
                if (rendererWarnings != null) {
                    warningMessage.add(CommonComponents.NEW_LINE);
                    warningMessage.add(Component.translatable("options.graphics.warning.renderer", rendererWarnings).withStyle(ChatFormatting.GRAY));
                }

                String vendorWarnings = this.gpuWarnlistManager.getVendorWarnings();
                if (vendorWarnings != null) {
                    warningMessage.add(CommonComponents.NEW_LINE);
                    warningMessage.add(Component.translatable("options.graphics.warning.vendor", vendorWarnings).withStyle(ChatFormatting.GRAY));
                }

                String versionWarnings = this.gpuWarnlistManager.getVersionWarnings();
                if (versionWarnings != null) {
                    warningMessage.add(CommonComponents.NEW_LINE);
                    warningMessage.add(Component.translatable("options.graphics.warning.version", versionWarnings).withStyle(ChatFormatting.GRAY));
                }

                this.minecraft.gui.setScreen(
                    new UnsupportedGraphicsWarningScreen(
                        WARNING_TITLE, warningMessage, ImmutableList.of(
                            new UnsupportedGraphicsWarningScreen.ButtonOption(BUTTON_ACCEPT, btn -> {
                                this.options.improvedTransparency().set(true);
                                this.minecraft.levelExtractor.allChanged();
                                this.gpuWarnlistManager.dismissWarning();
                                this.minecraft.gui.setScreen(this);
                            }),
                            new UnsupportedGraphicsWarningScreen.ButtonOption(BUTTON_CANCEL, btn -> {
                                this.gpuWarnlistManager.dismissWarning();
                                this.options.improvedTransparency().set(false);
                                this.updateTransparencyButton();
                                this.minecraft.gui.setScreen(this);
                            })
                        )
                    ) {
                    }
                );
            }

            return true;
        }

        return false;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (this.minecraft.hasControlDown()) {
            OptionInstance<Integer> guiScale = this.options.guiScale();
            if (guiScale.values() instanceof OptionInstance.ClampingLazyMaxIntRange) {
                OptionInstance.ClampingLazyMaxIntRange clampingLazyMaxIntRange = (OptionInstance.ClampingLazyMaxIntRange) guiScale.values();
                int oldValue = guiScale.get();
                int adjustedOldValue = oldValue == 0 ? clampingLazyMaxIntRange.maxInclusive() + 1 : oldValue;
                int newValue = adjustedOldValue + (int) Math.signum(scrollY);
                if (newValue != 0 && newValue <= clampingLazyMaxIntRange.maxInclusive() && newValue >= clampingLazyMaxIntRange.minInclusive()) {
                    int previous = guiScale.get();
                    this.undoStack.push(() -> guiScale.set(previous));
                    guiScale.set(newValue);
                    this.rebuildTabContent();
                    return true;
                }
            }

            return false;
        }

        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    public void updateFullscreenButton(boolean fullscreen) {
        if (this.fullscreenWidget instanceof MangoToggleButton) {
            MangoToggleButton button = (MangoToggleButton) this.fullscreenWidget;
            button.refresh();
        }
    }

    public void updateTransparencyButton() {
        if (this.transparencyWidget instanceof MangoToggleButton) {
            MangoToggleButton button = (MangoToggleButton) this.transparencyWidget;
            button.refresh();
        }
    }

    private enum Tab {
        GENERAL("mango.videoTab.general"),
        QUALITY("mango.videoTab.quality"),
        PERFORMANCE("mango.videoTab.performance");

        private final String translationKey;

        Tab(String translationKey) {
            this.translationKey = translationKey;
        }
    }
}
