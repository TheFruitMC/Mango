package org.fruitmc.mango.mixin.accessor;

import net.minecraft.client.gui.components.AbstractScrollArea;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(AbstractScrollArea.class)
public interface AbstractScrollAreaAccessor {

    @Invoker("scrollAmount")
    double mango$getScrollAmount();

    @Invoker("scrollerHeight")
    int mango$getScrollerHeight();

    @Invoker("scrollBarX")
    int mango$getScrollBarX();

    @Invoker("scrollBarY")
    int mango$getScrollBarY();

    @Invoker("scrollable")
    boolean mango$isScrollable();
}
