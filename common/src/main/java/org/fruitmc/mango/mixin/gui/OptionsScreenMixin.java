package org.fruitmc.mango.mixin.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import org.fruitmc.mango.client.gui.screens.MangoVideoSettingsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.function.Supplier;

@Mixin(OptionsScreen.class)
public abstract class OptionsScreenMixin {

    @ModifyArg(
        method = "init",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/options/OptionsScreen;openScreenButton(Lnet/minecraft/network/chat/Component;Ljava/util/function/Supplier;)Lnet/minecraft/client/gui/components/Button;",
            ordinal = 2
        ),
        index = 1
    )
    private Supplier<Screen> mango$redirectVideoSettings(Supplier<Screen> original) {
        OptionsScreen self = (OptionsScreen) (Object) this;
        Minecraft minecraft = Minecraft.getInstance();
        return () -> new MangoVideoSettingsScreen(self, minecraft, minecraft.options);
    }
}
