package com.etka.lune.server;

import com.etka.lune.Constants;
import com.etka.lune.net.EditRulesPayload;
import com.etka.lune.net.HelloPayload;
import com.etka.lune.net.RulesPayload;
import com.etka.lune.platform.BuildInfo;
import com.etka.lune.platform.Services;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

import java.util.UUID;

/**
 * Lune on a server: the half that lets the people who run a server decide what the Lune clients
 * on it may do.
 *
 * <p>Lune is a client mod, and a server does not need this to let anyone play. What installing it
 * adds is a voice: the server learns which players have Lune, and tells each of them the rules in
 * {@link ServerRules} - who may run tasks, who may use the omniscient modes. The same jar is the
 * server half, so a server owner installs exactly what their players do.</p>
 *
 * <p>It does nothing in the world and it takes nothing away by force. A server cannot stop a
 * client mod from running; it can only say what it allows, and every build of Lune listens and
 * holds itself to the answer ({@code ServerAccess} on the client). A rebuilt Lune with that taken
 * out would not listen, which is true of anything that asks a client to behave, and the README
 * says so rather than promising more.</p>
 *
 * <p>Every loader calls the same few methods here from its own events. The integrated server of a
 * world opened to LAN runs this too, which is how the host sets rules for the friends who join.</p>
 *
 * <p>Loaded on dedicated servers, so nothing here - or in anything it reaches - may touch a client
 * class. {@code ServerSideClassesTest} holds the whole {@code server} and {@code net} packages to
 * that.</p>
 */
public final class LuneServer {

    /** How the loader sends a payload to one player. */
    public interface Link {
        /** Whether this player's client listens for Lune's payloads, i.e. has Lune. */
        boolean canSend(ServerPlayer player);

        void send(ServerPlayer player, CustomPacketPayload payload);
    }

    private static final Link NONE = new Link() {
        @Override
        public boolean canSend(ServerPlayer player) {
            return false;
        }

        @Override
        public void send(ServerPlayer player, CustomPacketPayload payload) {
        }
    };

    /** How often, in ticks, the players with Lune are checked for a changed standing - an /op, say. */
    private static final int RECHECK_TICKS = 20;
    /** How often, in ticks, the rules file is checked for an edit made by hand. */
    private static final int RELOAD_TICKS = 40;

    private static Link link = NONE;
    private static MinecraftServer server;
    private static RulesKeeper keeper;
    private static ServerRulesFile file;
    private static long ticks;

    private LuneServer() {}

    /** Called once by the loader's common entry point. */
    public static void install(Link link) {
        LuneServer.link = link == null ? NONE : link;
    }

    /** The server is up: read the rules, and say where they came from. */
    public static void started(MinecraftServer current) {
        server = current;
        ticks = 0;
        boolean dedicated = current.isDedicatedServer();
        file = new ServerRulesFile(Services.PLATFORM.getConfigDir().resolve(ServerRulesFile.NAME));
        boolean existed = file.exists();
        ServerRules rules = file.load(ServerRules.defaults(dedicated), dedicated);
        keeper = new RulesKeeper(rules, BuildInfo.version());
        if (dedicated) {
            Constants.LOG.info("Lune {} is on this server. Who may run tasks: {}; who may use the"
                            + " omniscient modes: {}. {} {} (everyone, operators or nobody), or from"
                            + " Lune's Server tab as an operator.",
                    BuildInfo.version(), rules.run().id(), rules.cheats().id(),
                    existed ? "Change them in" : "Wrote the defaults to", file.path());
        }
    }

    public static void stopped(MinecraftServer current) {
        if (current != server) {
            return;
        }
        server = null;
        keeper = null;
        file = null;
    }

    public static void tick(MinecraftServer current) {
        if (keeperFor(current) == null) {
            return;
        }
        ticks++;
        if (ticks % RELOAD_TICKS == 0) {
            ServerRules edited = file.changedSince(keeper.rules());
            if (edited != null && keeper.replace(edited, LuneServer::online)) {
                Constants.LOG.info("Lune's server rules changed in {}: who may run tasks: {}; who may use"
                        + " the omniscient modes: {}", file.path(), edited.run().id(), edited.cheats().id());
            }
        }
        if (ticks % RECHECK_TICKS == 0 && keeper.count() > 0) {
            keeper.refresh(LuneServer::online);
        }
    }

    /** A player's Lune said hello. */
    public static void hello(ServerPlayer player, HelloPayload hello) {
        if (player != null && keeperFor(player.level().getServer()) != null) {
            keeper.hello(member(player), hello, LuneServer::online);
        }
    }

    /** A player's Lune asked for the rules to change; the keeper decides whether it may. */
    public static void edit(ServerPlayer player, EditRulesPayload edit) {
        if (player != null && keeperFor(player.level().getServer()) != null
                && keeper.edit(member(player), edit, LuneServer::online)) {
            file.save(keeper.rules());
        }
    }

    public static void left(ServerPlayer player) {
        if (keeper != null && player != null) {
            keeper.left(player.getUUID(), LuneServer::online);
        }
    }

    /**
     * The keeper for {@code current}, started now if the loader's started event was missed.
     *
     * <p>A belt to go with the braces. A client that has said hello refuses to run until it hears
     * back, so a server half that never started would lock every Lune player on the server out;
     * the first tick or hello starts it instead.</p>
     */
    private static RulesKeeper keeperFor(MinecraftServer current) {
        if (current == null) {
            return null;
        }
        if (keeper == null || server != current) {
            started(current);
        }
        return keeper;
    }

    private static RulesKeeper.Member online(UUID id) {
        ServerPlayer player = server == null ? null : server.getPlayerList().getPlayer(id);
        return player == null ? null : member(player);
    }

    private static RulesKeeper.Member member(ServerPlayer player) {
        return new RulesKeeper.Member() {
            @Override
            public UUID id() {
                return player.getUUID();
            }

            @Override
            public String name() {
                return player.getScoreboardName();
            }

            @Override
            public boolean operator() {
                return player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
            }

            @Override
            public boolean host() {
                return server != null && server.isSingleplayerOwner(player.nameAndId());
            }

            @Override
            public boolean tell(RulesPayload notice) {
                if (!link.canSend(player)) {
                    return false;
                }
                link.send(player, notice);
                return true;
            }
        };
    }
}
