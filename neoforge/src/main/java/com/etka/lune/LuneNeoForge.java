package com.etka.lune;

import com.etka.lune.net.EditRulesPayload;
import com.etka.lune.net.HelloPayload;
import com.etka.lune.net.LuneNet;
import com.etka.lune.net.RulesPayload;
import com.etka.lune.server.LuneServer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * NeoForge entry point, run on both sides: registers Lune's payloads and wires the server half
 * ({@link LuneServer}), which a dedicated server with Lune installed runs, and so does the
 * integrated server of a player's own world. The client's own wiring is in
 * {@code LuneNeoForgeClient}.
 *
 * <p>Runs on dedicated servers, so nothing here may reach a client class.</p>
 */
@Mod(Constants.MOD_ID)
public class LuneNeoForge {

    public LuneNeoForge(IEventBus modBus) {
        Constants.LOG.info("{} loading on NeoForge", Constants.MOD_NAME);
        modBus.addListener(LuneNeoForge::registerPayloads);

        LuneServer.install(new LuneServer.Link() {
            @Override
            public boolean canSend(ServerPlayer player) {
                return player.connection != null && player.connection.hasChannel(RulesPayload.TYPE);
            }

            @Override
            public void send(ServerPlayer player, CustomPacketPayload payload) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        });
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> LuneServer.started(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> LuneServer.stopped(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> LuneServer.tick(event.getServer()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                LuneServer.left(player);
            }
        });
    }

    /**
     * Optional in both directions: a server without Lune, or a player without it, still connects,
     * and each side simply finds the other's channels missing. The rules' own handler is added by
     * {@code LuneNeoForgeClient}, because it reaches client classes.
     */
    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(LuneNet.CHANNEL_VERSION).optional();
        registrar.playToServer(HelloPayload.TYPE, HelloPayload.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                LuneServer.hello(player, payload);
            }
        });
        registrar.playToServer(EditRulesPayload.TYPE, EditRulesPayload.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                LuneServer.edit(player, payload);
            }
        });
        registrar.playToClient(RulesPayload.TYPE, RulesPayload.CODEC);
    }
}
