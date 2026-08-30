package org.fruitmc.mango.client.gui.screens;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

public final class MangoConfigScreen {

    private MangoConfigScreen() {
    }

    public static Screen create(Screen parent) {
        Minecraft minecraft = Minecraft.getInstance();
        return new MangoVideoSettingsScreen(parent, minecraft, minecraft.options);
    }
}
