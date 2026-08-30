package org.fruitmc.mango.mixin.accessor;

import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.function.Function;

@Mixin(OptionInstance.class)
public interface OptionInstanceAccessor {

    @Accessor("caption")
    Component mango$getCaption();

    @Accessor("toString")
    <T> Function<T, Component> mango$getToString();
}
