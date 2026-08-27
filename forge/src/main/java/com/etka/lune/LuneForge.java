package com.etka.lune;

import com.etka.lune.client.LuneForgeClient;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Forge entry point. Unlike NeoForge, Forge's {@code @Mod} carries no {@code dist} attribute, so the
 * client-only half is gated here instead - {@code clientSideOnly} in mods.toml keeps the mod off
 * dedicated servers, and this check keeps the client classes from being loaded if it ever isn't.
 */
@Mod(Constants.MOD_ID)
public class LuneForge {

    public LuneForge() {
        Constants.LOG.info("{} loading on Forge", Constants.MOD_NAME);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            LuneForgeClient.init();
        }
    }
}
