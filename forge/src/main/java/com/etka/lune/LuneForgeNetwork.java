package com.etka.lune;

import com.etka.lune.net.EditRulesPayload;
import com.etka.lune.net.HelloPayload;
import com.etka.lune.net.RulesPayload;
import com.etka.lune.server.LuneServer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.network.Channel;
import net.minecraftforge.network.ChannelBuilder;

import java.util.function.Consumer;

/**
 * Lune's payload channel on Forge, and the server half's wiring ({@link LuneServer}).
 *
 * <p>Forge wants every payload a channel carries named when the channel is built, the one the
 * client handles included, so the channel is built here on both sides and the client's handler is
 * reached through {@link #onClient}: on a dedicated server nothing ever arrives on it, and nothing
 * here reaches a client class.</p>
 */
public final class LuneForgeNetwork {

    /** Where a server's rules go on arrival; nowhere until the client entry point says. */
    private static Consumer<RulesPayload> clientReceiver = payload -> {};

    /**
     * Optional both ways, so a server without Lune or a player without it still connects. The
     * protocol version stays at its default for good: Forge refuses a connection whose versions
     * disagree, and the version that moves is the one inside the payloads.
     */
    static final Channel<CustomPacketPayload> CHANNEL = ChannelBuilder.named(Constants.id("net"))
            .optional()
            .payloadChannel()
            .play()
            .serverbound()
            .addMain(HelloPayload.TYPE, HelloPayload.CODEC, (payload, context) ->
                    LuneServer.hello(context.getSender(), payload))
            .addMain(EditRulesPayload.TYPE, EditRulesPayload.CODEC, (payload, context) ->
                    LuneServer.edit(context.getSender(), payload))
            .clientbound()
            .addMain(RulesPayload.TYPE, RulesPayload.CODEC, (payload, context) -> clientReceiver.accept(payload))
            .build();

    private LuneForgeNetwork() {}

    /** Called from the common entry point, on both sides. */
    static void init() {
        LuneServer.install(new LuneServer.Link() {
            @Override
            public boolean canSend(ServerPlayer player) {
                return player.connection != null && CHANNEL.isRemotePresent(player.connection.getConnection());
            }

            @Override
            public void send(ServerPlayer player, CustomPacketPayload payload) {
                CHANNEL.send(payload, player.connection.getConnection());
            }
        });
        ServerStartedEvent.BUS.addListener(event -> LuneServer.started(event.getServer()));
        ServerStoppedEvent.BUS.addListener(event -> LuneServer.stopped(event.getServer()));
        TickEvent.ServerTickEvent.Post.BUS.addListener(event -> LuneServer.tick(event.server()));
        PlayerEvent.PlayerLoggedOutEvent.BUS.addListener(event -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                LuneServer.left(player);
            }
        });
    }

    /** Where a server's rules go when they arrive: the client entry point says. */
    public static void onClient(Consumer<RulesPayload> receiver) {
        clientReceiver = receiver == null ? payload -> {} : receiver;
    }

    /** Whether the server at the other end of {@code connection} has Lune. */
    public static boolean serverHasLune(Connection connection) {
        return connection != null && CHANNEL.isRemotePresent(connection);
    }

    public static void sendToServer(Connection connection, CustomPacketPayload payload) {
        if (connection != null) {
            CHANNEL.send(payload, connection);
        }
    }
}
