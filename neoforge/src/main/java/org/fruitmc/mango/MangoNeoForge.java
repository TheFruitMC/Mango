package org.fruitmc.mango;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import org.fruitmc.mango.client.gui.screens.MangoConfigScreen;
import org.fruitmc.mango.config.MangoConfig;

@Mod(Constants.MOD_ID)
public class MangoNeoForge {

    public MangoNeoForge(IEventBus eventBus, ModContainer modContainer) {
        if (FMLEnvironment.getDist().isClient()) {
            MangoConfig.INSTANCE.load(FMLPaths.CONFIGDIR.get());
            modContainer.registerExtensionPoint(IConfigScreenFactory.class, (mc, parent) -> MangoConfigScreen.create(parent));
        }
    }
}
