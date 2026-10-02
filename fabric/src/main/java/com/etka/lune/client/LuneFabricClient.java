package com.etka.lune.client;

import com.etka.lune.Constants;
import com.etka.lune.bot.util.ServerAccess;
import com.etka.lune.client.command.LuneChatCommand;
import com.etka.lune.client.gui.DebugOverlay;
import com.etka.lune.net.RulesPayload;
import com.etka.lune.platform.BuildFeatures;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client-only Fabric setup - registers the keybinds and {@code /lune}, pumps the bot's tick, draws
 * the debug overlay, and lets the client hear a server's rules. All of them delegate straight into
 * {@code common}.
 */
public class LuneFabricClient implements ClientModInitializer {

    /** Fabric's client source has its own feedback methods rather than vanilla's. */
    private static final LuneChatCommand.Reply<FabricClientCommandSource> REPLY =
            new LuneChatCommand.Reply<>() {
                @Override
                public void success(FabricClientCommandSource source, String message) {
                    source.sendFeedback(Component.literal(message));
                }

                @Override
                public void failure(FabricClientCommandSource source, String message) {
                    source.sendError(Component.literal(message));
                }
            };

    @Override
    public void onInitializeClient() {
        KeyMappingHelper.registerKeyMapping(LuneKeybinds.OPEN);
        KeyMappingHelper.registerKeyMapping(LuneKeybinds.PAUSE);
        KeyMappingHelper.registerKeyMapping(LuneKeybinds.TOGGLE_DEBUG);
        if (BuildFeatures.approvalFeedback()) {
            KeyMappingHelper.registerKeyMapping(LuneKeybinds.LEARNING_GOOD);
            KeyMappingHelper.registerKeyMapping(LuneKeybinds.LEARNING_BAD);
        }

        // Start as well as end: a run beside the player keeps the player's clicks from the game while
        // Lune has the controls, and the game reads them before the end of the tick.
        ClientTickEvents.START_CLIENT_TICK.register(LuneKeybinds::clientTickStart);
        ClientTickEvents.END_CLIENT_TICK.register(LuneKeybinds::clientTick);

        // A server's answer, and the way to ask it: see ServerAccess. The payload types themselves
        // are registered by LuneFabric, which runs first on both sides.
        ClientPlayNetworking.registerGlobalReceiver(RulesPayload.TYPE, (payload, context) ->
                context.client().execute(() -> ServerAccess.receive(payload)));
        ServerAccess.install(new ServerAccess.Link() {
            @Override
            public boolean canSend(CustomPacketPayload.Type<?> type) {
                return ClientPlayNetworking.canSend(type);
            }

            @Override
            public void send(CustomPacketPayload payload) {
                ClientPlayNetworking.send(payload);
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
                dispatcher.register(LuneChatCommand.build(REPLY)));

        HudElementRegistry.addLast(Constants.id("debug_overlay"),
                (extractor, deltaTracker) -> DebugOverlay.render(extractor));

        // Submits rather than a buffer source: the collector and the render state are what every
        // Minecraft version Lune builds for has in common (MultiBufferSource left in 26.2, and the
        // camera accessor on GameRenderer was renamed in the same release).
        LevelRenderEvents.COLLECT_SUBMITS.register(context ->
                WorldActionOverlay.render(context.poseStack(), context.levelState(),
                        context.submitNodeCollector()));
    }
}
