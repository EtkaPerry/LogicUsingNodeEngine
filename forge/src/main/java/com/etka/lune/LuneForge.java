package com.etka.lune;

import com.etka.lune.client.LuneForgeClient;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Forge entry point, run on both sides. It wires Lune's payload channel and the server half, which
 * a dedicated server with Lune installed runs (see {@code LuneForgeNetwork}). Unlike NeoForge,
 * Forge's {@code @Mod} carries no {@code dist} attribute, so the client-only half is gated here
 * instead, and this check is what keeps its classes off a dedicated server.
 */
@Mod(Constants.MOD_ID)
public class LuneForge {

    public LuneForge() {
        Constants.LOG.info("{} loading on Forge", Constants.MOD_NAME);
        LuneForgeNetwork.init();
        if (FMLEnvironment.dist == Dist.CLIENT) {
            LuneForgeClient.init();
        }
    }
}
