package org.fruitmc.mango.mixin.gui;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.GpuBackend;
import net.minecraft.client.GameLoadCookie;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import org.fruitmc.mango.client.gui.screens.MangoBackendWarningScreen;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Gui.class)
public abstract class GuiMixin {

    private static final String OPENGL_BACKEND_NAME = "OpenGL";

    @Inject(
        method = "buildInitialScreens",
        at = @At(value = "RETURN"),
        cancellable = true,
        require = 1
    )
    private void mango$showBackendWarning(
        @Nullable GameLoadCookie cookie, CallbackInfoReturnable<Runnable> cir
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        Window window = minecraft.getWindow();
        GpuBackend backend = window.backend();
        if (!OPENGL_BACKEND_NAME.equalsIgnoreCase(backend.getName())) {
            return;
        }

        Runnable original = cir.getReturnValue();
        cir.setReturnValue(() -> minecraft.gui.setScreen(new MangoBackendWarningScreen(original)));
    }
}
