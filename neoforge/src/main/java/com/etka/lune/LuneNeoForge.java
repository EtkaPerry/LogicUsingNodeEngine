package com.etka.lune;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

/**
 * NeoForge entry point. Lune is a client-side automation mod, so the common entry point has no
 * server-side registration to do - all the real wiring happens in {@code LuneNeoForgeClient}.
 */
@Mod(Constants.MOD_ID)
public class LuneNeoForge {

    public LuneNeoForge(IEventBus modBus) {
        Constants.LOG.info("{} loading on NeoForge", Constants.MOD_NAME);
    }
}
