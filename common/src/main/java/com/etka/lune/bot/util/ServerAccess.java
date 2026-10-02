package com.etka.lune.bot.util;

import com.etka.lune.Constants;
import com.etka.lune.net.EditRulesPayload;
import com.etka.lune.net.HelloPayload;
import com.etka.lune.net.RulesPayload;
import com.etka.lune.server.ServerRules;
import com.etka.lune.util.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * What the server this client is playing on has said about Lune, and the lock that holds Lune to
 * it.
 *
 * <p>A server that runs Lune's server half has rules for the Lune players on it
 * ({@link ServerRules}): who may run tasks, and who may use the omniscient modes. This is where
 * the client keeps the server's answer and asks it. {@code BotEngine} will not start a task the
 * answer does not allow and ends one it stops allowing; {@link OmniscientAccess} asks it before
 * the cheats. It adds nothing the bot does - it is a lock, like the terms, and a lock is not a
 * behaviour.</p>
 *
 * <p>Like {@link Cheats}, it lives in memory for one connection and never on disk. The answer
 * belonged to the server that gave it; joining another one starts from nothing, and a file could
 * only ever be a way to pretend a server said something it did not.</p>
 *
 * <p>Three cases, and each is decided the cautious way:</p>
 * <ul>
 *   <li><b>The player's own world</b>, including one opened to LAN: never held to anything. Its
 *       rules are for the friends who join it.</li>
 *   <li><b>A server without Lune</b>: nothing was said, so Lune goes by its own rules, as it did
 *       before servers could say anything - including the old operator check for the cheats.</li>
 *   <li><b>A server with Lune</b>: its answer decides, and until it arrives nothing is allowed.</li>
 * </ul>
 */
public final class ServerAccess {

    /** How the loader sends a payload to the server. */
    public interface Link {
        /** Whether the server listens for this payload, which for the hello means it runs Lune. */
        boolean canSend(CustomPacketPayload.Type<?> type);

        void send(CustomPacketPayload payload);
    }

    /**
     * Where the player stands on the server they are on, in the five ways the Server tab and Lune
     * put it.
     */
    public enum Standing {
        /** Their own world, open to LAN or not. */
        OWN_WORLD,
        /** A server without Lune, or no server at all. */
        NO_LUNE,
        /** A server with Lune that has not answered yet. */
        WAITING,
        ALLOWED,
        REFUSED
    }

    /** What this client knows about the server it is on. */
    public enum State {
        /** Not connected, or connected to a server that has no Lune: nothing was said. */
        NO_LUNE,
        /** The server runs Lune and has not answered yet. Nothing is allowed meanwhile. */
        WAITING,
        /** The server has said where this player stands. */
        HEARD
    }

    private static final Link NONE = new Link() {
        @Override
        public boolean canSend(CustomPacketPayload.Type<?> type) {
            return false;
        }

        @Override
        public void send(CustomPacketPayload payload) {
        }
    };

    private static Link link = NONE;
    /** The connection everything below belongs to, held only for identity, as {@link Cheats} does. */
    private static Object session;
    private static boolean greeted;
    private static RulesPayload heard;

    private ServerAccess() {}

    /** Called once by the loader's client entry point. */
    public static void install(Link link) {
        ServerAccess.link = link == null ? NONE : link;
    }

    /**
     * Once a client tick: forgets a server that is gone, and says hello to one that listens for it.
     *
     * <p>Asked every tick rather than once on joining because the loaders learn what the server
     * listens for at different moments, and a hello sent before the server has said it listens
     * would be refused by some of them.</p>
     */
    public static void tick(Minecraft mc) {
        Object connection = mc == null ? null : mc.getConnection();
        boolean greet;
        synchronized (ServerAccess.class) {
            bind(connection);
            greet = connection != null && !greeted && canSend(HelloPayload.TYPE);
            if (greet) {
                greeted = true;
            }
        }
        if (greet) {
            send(HelloPayload.current());
        }
    }

    /** The server's answer, for the loader's handler to hand over on the client thread. */
    public static synchronized void receive(RulesPayload notice) {
        heard = notice;
    }

    /** Clears everything when the connection it belonged to is gone or replaced. */
    static synchronized void bind(Object connection) {
        if (connection == session) {
            return;
        }
        session = connection;
        greeted = false;
        heard = null;
    }

    public static synchronized State state() {
        if (heard != null) {
            return State.HEARD;
        }
        return session != null && (greeted || canSend(HelloPayload.TYPE)) ? State.WAITING : State.NO_LUNE;
    }

    /** The server's last answer, or null when there is none. */
    public static synchronized RulesPayload heard() {
        return heard;
    }

    /** Where the player stands on the server in hand. */
    public static Standing standing(Minecraft mc) {
        if (mc == null) {
            return Standing.NO_LUNE;
        }
        synchronized (ServerAccess.class) {
            bind(mc.getConnection());
            return standing(mc.hasSingleplayerServer(), state(), heard);
        }
    }

    static Standing standing(boolean ownWorld, State state, RulesPayload heard) {
        if (ownWorld) {
            return Standing.OWN_WORLD;
        }
        return switch (state) {
            case NO_LUNE -> Standing.NO_LUNE;
            case WAITING -> Standing.WAITING;
            case HEARD -> mayRun(false, state, heard) ? Standing.ALLOWED : Standing.REFUSED;
        };
    }

    /** Whether the player may start a task here. */
    public static boolean mayRun(Minecraft mc) {
        if (mc == null) {
            return true;
        }
        synchronized (ServerAccess.class) {
            // Asked of the connection in hand, so an answer from the last server is never used on this one.
            bind(mc.getConnection());
            return mayRun(mc.hasSingleplayerServer(), state(), heard);
        }
    }

    /** The decision itself, apart from where its inputs come from, so it can be tested headless. */
    static boolean mayRun(boolean ownWorld, State state, RulesPayload heard) {
        if (ownWorld) {
            return true;
        }
        return switch (state) {
            case NO_LUNE -> true;
            case WAITING -> false;
            case HEARD -> heard != null && heard.mayRun();
        };
    }

    /**
     * Whether the omniscient modes may be switched on. Where the server says nothing, the answer
     * is the one Lune always gave, which {@link OmniscientAccess} keeps.
     */
    static boolean mayCheat(boolean ownWorld, boolean operator, State state, RulesPayload heard) {
        return switch (state) {
            case NO_LUNE -> OmniscientAccess.isAllowed(ownWorld, operator);
            case WAITING -> ownWorld;
            case HEARD -> ownWorld || heard != null && heard.mayCheat();
        };
    }

    /** {@link #mayCheat(boolean, boolean, State, RulesPayload)} for the connection in hand. */
    static boolean mayCheat(Minecraft mc, boolean ownWorld, boolean operator) {
        synchronized (ServerAccess.class) {
            bind(mc.getConnection());
            return mayCheat(ownWorld, operator, state(), heard);
        }
    }

    /** Whether the reason the cheats are locked is this server's rules closing them for everybody. */
    public static synchronized boolean serverClosesCheats() {
        return heard != null && !heard.mayCheat() && heard.rules().cheats() == ServerRules.Who.NOBODY;
    }

    /** Why a task may not start here, in the player's language, or null when it may. */
    public static String refusal(Minecraft mc) {
        if (mayRun(mc)) {
            return null;
        }
        synchronized (ServerAccess.class) {
            return refusal(state(), heard);
        }
    }

    static String refusal(State state, RulesPayload heard) {
        if (state == State.WAITING || heard == null) {
            return Lang.get("lune.server.refusal.waiting");
        }
        return switch (heard.rules().run()) {
            case OPERATORS -> Lang.get("lune.server.refusal.operators");
            case NOBODY -> Lang.get("lune.server.refusal.nobody");
            case EVERYONE -> Lang.get("lune.server.refusal.other");
        };
    }

    /**
     * Asks the server to change one rule or both; null leaves a rule as it is. Nothing changes here:
     * the server decides, and its answer arrives like any other.
     *
     * @return whether the request was sent, which it is not for a player who may not edit
     */
    public static boolean propose(ServerRules.Who run, ServerRules.Who cheats) {
        synchronized (ServerAccess.class) {
            if (heard == null || !heard.mayEdit() || !canSend(EditRulesPayload.TYPE)) {
                return false;
            }
        }
        send(new EditRulesPayload(run, cheats));
        return true;
    }

    private static boolean canSend(CustomPacketPayload.Type<?> type) {
        try {
            return link.canSend(type);
        } catch (RuntimeException notConnected) {
            return false;
        }
    }

    private static void send(CustomPacketPayload payload) {
        try {
            link.send(payload);
        } catch (RuntimeException e) {
            Constants.LOG.debug("Could not send {} to the server", payload.type().id(), e);
        }
    }
}
