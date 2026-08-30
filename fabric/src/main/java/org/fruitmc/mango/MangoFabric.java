package org.fruitmc.mango;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.fruitmc.mango.config.MangoConfig;

public class MangoFabric implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        MangoConfig.INSTANCE.load(FabricLoader.getInstance().getConfigDir());
    }
}
