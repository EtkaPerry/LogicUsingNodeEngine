package com.etka.lune;

import com.etka.lune.net.EditRulesPayload;
import com.etka.lune.net.HelloPayload;
import com.etka.lune.net.RulesPayload;
import com.etka.lune.server.LuneServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fabric entry point, run on both sides: registers Lune's payloads and wires the server half
 * ({@link LuneServer}) - which a dedicated server with Lune installed runs, and so does the
 * integrated server of a player's own world. Everything client-only is in {@code LuneFabricClient}.
 *
 * <p>Runs on dedicated servers, so nothing here may reach a client class.</p>
 */
public class LuneFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        Constants.LOG.info("{} loading on Fabric", Constants.MOD_NAME);

        // Both directions are registered on both sides: a codec is how either end reads the other.
        PayloadTypeRegistry.serverboundPlay().register(HelloPayload.TYPE, HelloPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(EditRulesPayload.TYPE, EditRulesPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(RulesPayload.TYPE, RulesPayload.CODEC);

        // Handed to the server thread whichever thread they arrive on: the server half keeps its
        // state on that thread alone.
        ServerPlayNetworking.registerGlobalReceiver(HelloPayload.TYPE, (payload, context) ->
                context.server().execute(() -> LuneServer.hello(context.player(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(EditRulesPayload.TYPE, (payload, context) ->
                context.server().execute(() -> LuneServer.edit(context.player(), payload)));

        LuneServer.install(new LuneServer.Link() {
            @Override
            public boolean canSend(ServerPlayer player) {
                return ServerPlayNetworking.canSend(player, RulesPayload.TYPE);
            }

            @Override
            public void send(ServerPlayer player, CustomPacketPayload payload) {
                ServerPlayNetworking.send(player, payload);
            }
        });
        ServerLifecycleEvents.SERVER_STARTED.register(LuneServer::started);
        ServerLifecycleEvents.SERVER_STOPPED.register(LuneServer::stopped);
        ServerTickEvents.END_SERVER_TICK.register(LuneServer::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((listener, server) ->
                LuneServer.left(listener.player));
    }
}
