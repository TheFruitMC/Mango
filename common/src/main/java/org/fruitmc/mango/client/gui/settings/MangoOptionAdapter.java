package org.fruitmc.mango.client.gui.settings;

import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import org.fruitmc.mango.mixin.accessor.OptionInstanceAccessor;
import org.fruitmc.mango.client.gui.widgets.MangoCycleButton;
import org.fruitmc.mango.client.gui.widgets.MangoDoubleSlider;
import org.fruitmc.mango.client.gui.widgets.MangoSlider;
import org.fruitmc.mango.client.gui.widgets.MangoToggleButton;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public final class MangoOptionAdapter {

    private static final int WIDGET_WIDTH = 310;
    private static final int WIDGET_HEIGHT = 24;

    private MangoOptionAdapter() {
    }

    public static AbstractWidget from(OptionInstance<?> option, Options options) {
        return from(option, options, null);
    }

    public static AbstractWidget from(OptionInstance<?> option, Options options, @Nullable MangoUndoStack undoStack) {
        Component caption = ((OptionInstanceAccessor) (Object) option).mango$getCaption();
        Function<Object, Component> toString = ((OptionInstanceAccessor) (Object) option).mango$getToString();
        Object rawValue = option.get();
        OptionInstance.ValueSet<?> valueSet = option.values();

        if (rawValue instanceof Boolean) {
            OptionInstance<Boolean> boolOption = (OptionInstance<Boolean>) option;
            return booleanOption(boolOption, caption, options, undoStack);
        }
        if (valueSet instanceof OptionInstance.SliderableValueSet) {
            OptionInstance.SliderableValueSet sliderable = (OptionInstance.SliderableValueSet) valueSet;
            return sliderOption(option, sliderable, caption, toString, options, undoStack);
        }
        if (valueSet instanceof OptionInstance.CycleableValueSet) {
            OptionInstance.CycleableValueSet cycleable = (OptionInstance.CycleableValueSet) valueSet;
            return cycleOption(option, cycleable, caption, toString, options, undoStack);
        }

        return option.createButton(options, 0, 0, WIDGET_WIDTH);
    }

    private static MangoToggleButton booleanOption(OptionInstance<Boolean> option, Component caption, Options options, @Nullable MangoUndoStack undoStack) {
        Supplier<Boolean> getter = option::get;
        Consumer<Boolean> setter = (Boolean value) -> {
            if (undoStack != null) {
                boolean previous = option.get();
                undoStack.push(() -> option.set(previous));
            }
            option.set(value);
            options.save();
        };
        return new MangoToggleButton(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, caption, getter, setter);
    }

    private static AbstractWidget sliderOption(OptionInstance option, OptionInstance.SliderableValueSet sliderable, Component caption, Function<Object, Component> toString, Options options, @Nullable MangoUndoStack undoStack) {
        if (option.get() instanceof Integer && sliderable instanceof OptionInstance.IntRangeBase) {
            OptionInstance.IntRangeBase range = (OptionInstance.IntRangeBase) sliderable;
            int current = (Integer) option.get();
            int min = range.minInclusive();
            int max = range.maxInclusive();
            return new MangoSlider(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, caption, current, min, max,
                (int value) -> {
                    if (undoStack != null) {
                        Object previous = option.get();
                        undoStack.push(() -> option.set(previous));
                    }
                    option.set(Integer.valueOf(value));
                    options.save();
                },
                (int value) -> toString.apply(Integer.valueOf(value))
            );
        }
        return new MangoDoubleSlider(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, caption,
            sliderable.toSliderValue(option.get()),
            (Double value) -> toString.apply(sliderable.fromSliderValue(value)),
            (Double value) -> {
                if (undoStack != null) {
                    Object previous = option.get();
                    undoStack.push(() -> option.set(previous));
                }
                option.set(sliderable.fromSliderValue(value));
                options.save();
            }
        );
    }

    private static MangoCycleButton cycleOption(OptionInstance option, OptionInstance.CycleableValueSet cycleable, Component caption, Function<Object, Component> toString, Options options, @Nullable MangoUndoStack undoStack) {
        List values = cycleable.valueListSupplier().getSelectedList();
        Supplier getter = option::get;
        Consumer setter = (Object value) -> {
            if (undoStack != null) {
                Object previous = option.get();
                undoStack.push(() -> option.set(previous));
            }
            option.set(value);
            options.save();
        };
        return new MangoCycleButton(0, 0, WIDGET_WIDTH, WIDGET_HEIGHT, caption, values, getter, setter,
            (Object value) -> toString.apply(value)
        );
    }
}
