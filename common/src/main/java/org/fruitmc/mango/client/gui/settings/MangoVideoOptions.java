package org.fruitmc.mango.client.gui.settings;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.network.chat.Component;
import org.fruitmc.mango.config.MangoConfig;
import org.fruitmc.mango.client.gui.style.MangoTheme;
import org.fruitmc.mango.client.gui.widgets.MangoSlider;
import org.fruitmc.mango.client.gui.widgets.MangoToggleButton;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class MangoVideoOptions {

    private static final int WIDGET_HEIGHT = 24;
    private static final int HEADER_HEIGHT = 28;
    private static final int MIN_CHUNK_BUFFER_SCALE = 1;
    private static final int MAX_CHUNK_BUFFER_SCALE = 4;
    private static final int MIN_FRAME_GENERATION_MULTIPLIER = 2;
    private static final int MAX_FRAME_GENERATION_MULTIPLIER = 4;

    private MangoVideoOptions() {
    }

    public static int appendSection(GridLayout grid, int row, int widgetWidth, @Nullable MangoUndoStack undoStack) {
        return appendSection(grid, row, widgetWidth, undoStack, null);
    }

    public static int appendSection(GridLayout grid, int row, int widgetWidth, @Nullable MangoUndoStack undoStack, @Nullable String searchQuery) {
        MangoConfig config = MangoConfig.INSTANCE;

        boolean hasHeader = searchQuery == null || searchQuery.isBlank();
        if (hasHeader) {
            grid.addChild(sectionHeader("mango.config.section.mango"), row++, 0);
        }

        if (matchesSearch("mango.config.chunkBufferScale", searchQuery)) {
            grid.addChild(createChunkBufferSlider(config, widgetWidth, undoStack), row++, 0);
        }
        if (matchesSearch("mango.config.enableFrameGeneration", searchQuery)) {
            grid.addChild(createFrameGenerationToggle(config, widgetWidth, undoStack), row++, 0);
        }
        if (matchesSearch("mango.config.frameGenerationMultiplier", searchQuery)) {
            grid.addChild(createFrameGenerationMultiplierSlider(config, widgetWidth, undoStack), row++, 0);
        }
        if (matchesSearch("mango.config.enableHiZCulling", searchQuery)) {
            grid.addChild(createHiZCullingToggle(config, widgetWidth, undoStack), row++, 0);
        }
        return row;
    }

    private static boolean matchesSearch(String translationKey, @Nullable String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String caption = Component.translatable(translationKey).getString().toLowerCase(Locale.ROOT);
        return caption.contains(query.toLowerCase(Locale.ROOT));
    }

    private static StringWidget sectionHeader(String key) {
        StringWidget header = new StringWidget(
            Component.translatable(key).withColor(MangoTheme.SIDEBAR_TEXT_SECONDARY),
            Minecraft.getInstance().font
        );
        header.setHeight(HEADER_HEIGHT);
        return header;
    }

    private static MangoSlider createChunkBufferSlider(MangoConfig config, int width, @Nullable MangoUndoStack undoStack) {
        Component caption = Component.translatable("mango.config.chunkBufferScale");
        MangoSlider slider = new MangoSlider(
            0, 0, width, WIDGET_HEIGHT, caption, config.chunkBufferScale,
            MIN_CHUNK_BUFFER_SCALE, MAX_CHUNK_BUFFER_SCALE,
            (int value) -> {
                if (undoStack != null) {
                    int previous = config.chunkBufferScale;
                    undoStack.push(() -> config.chunkBufferScale = previous);
                }
                config.chunkBufferScale = value;
            }
        );
        slider.setTooltip(Tooltip.create(Component.translatable("mango.config.chunkBufferScale.tooltip")));
        return slider;
    }

    private static MangoToggleButton createFrameGenerationToggle(MangoConfig config, int width, @Nullable MangoUndoStack undoStack) {
        Component caption = Component.translatable("mango.config.enableFrameGeneration");
        Supplier<Boolean> getter = () -> config.enableFrameGeneration;
        Consumer<Boolean> setter = (Boolean value) -> {
            if (undoStack != null) {
                boolean previous = config.enableFrameGeneration;
                undoStack.push(() -> config.enableFrameGeneration = previous);
            }
            config.enableFrameGeneration = value;
        };
        MangoToggleButton toggle = new MangoToggleButton(0, 0, width, WIDGET_HEIGHT, caption, getter, setter);
        toggle.setTooltip(Tooltip.create(Component.translatable("mango.config.enableFrameGeneration.tooltip")));
        return toggle;
    }

    private static MangoSlider createFrameGenerationMultiplierSlider(MangoConfig config, int width, @Nullable MangoUndoStack undoStack) {
        Component caption = Component.translatable("mango.config.frameGenerationMultiplier");
        MangoSlider slider = new MangoSlider(
            0, 0, width, WIDGET_HEIGHT, caption, config.frameGenerationMultiplier,
            MIN_FRAME_GENERATION_MULTIPLIER, MAX_FRAME_GENERATION_MULTIPLIER,
            (int value) -> {
                if (undoStack != null) {
                    int previous = config.frameGenerationMultiplier;
                    undoStack.push(() -> config.frameGenerationMultiplier = previous);
                }
                config.frameGenerationMultiplier = value;
            }
        );
        slider.setTooltip(Tooltip.create(Component.translatable("mango.config.frameGenerationMultiplier.tooltip")));
        return slider;
    }

    private static MangoToggleButton createHiZCullingToggle(MangoConfig config, int width, @Nullable MangoUndoStack undoStack) {
        Component caption = Component.translatable("mango.config.enableHiZCulling");
        Supplier<Boolean> getter = () -> config.enableHiZCulling;
        Consumer<Boolean> setter = (Boolean value) -> {
            if (undoStack != null) {
                boolean previous = config.enableHiZCulling;
                undoStack.push(() -> config.enableHiZCulling = previous);
            }
            config.enableHiZCulling = value;
        };
        MangoToggleButton toggle = new MangoToggleButton(0, 0, width, WIDGET_HEIGHT, caption, getter, setter);
        toggle.setTooltip(Tooltip.create(Component.translatable("mango.config.enableHiZCulling.tooltip")));
        return toggle;
    }

}
