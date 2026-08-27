package com.etka.lune.client;

import com.etka.lune.Constants;
import com.etka.lune.client.gui.DebugOverlay;
import com.etka.lune.platform.BuildFeatures;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;

/**
 * Client-only Fabric setup - registers the keybinds, pumps the bot's tick, and draws the debug
 * overlay. All three delegate straight into {@code common}.
 */
public class LuneFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        KeyMappingHelper.registerKeyMapping(LuneKeybinds.OPEN);
        KeyMappingHelper.registerKeyMapping(LuneKeybinds.PAUSE);
        KeyMappingHelper.registerKeyMapping(LuneKeybinds.TOGGLE_DEBUG);
        if (BuildFeatures.approvalFeedback()) {
            KeyMappingHelper.registerKeyMapping(LuneKeybinds.LEARNING_GOOD);
            KeyMappingHelper.registerKeyMapping(LuneKeybinds.LEARNING_BAD);
        }

        ClientTickEvents.END_CLIENT_TICK.register(LuneKeybinds::clientTick);

        HudElementRegistry.addLast(Constants.id("debug_overlay"),
                (extractor, deltaTracker) -> DebugOverlay.render(extractor));

        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(context ->
                WorldActionOverlay.render(context.poseStack(),
                        Minecraft.getInstance().gameRenderer.getMainCamera(), context.bufferSource()));
    }
}
