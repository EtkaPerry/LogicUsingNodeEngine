package com.etka.lune;

import net.fabricmc.api.ModInitializer;

/**
 * Fabric entry point. Lune is client-side, so this only logs; see {@code LuneFabricClient}.
 */
public class LuneFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        Constants.LOG.info("{} loading on Fabric", Constants.MOD_NAME);
    }
}
