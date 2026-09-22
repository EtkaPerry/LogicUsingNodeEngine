package com.etka.lune.bot.util;

import com.etka.lune.util.Lang;
import net.minecraft.client.Minecraft;

import java.util.EnumSet;
import java.util.Locale;

/**
 * The omniscient modes, and the only place they are switched on.
 *
 * <p>These are cheats: they let the bot act on blocks it has no way of seeing. They are not
 * settings, so they are deliberately <em>not</em> in {@link com.etka.lune.config.BotConfig} and
 * never reach {@code config/lune.json}. A setting is a preference a player keeps; a cheat is a
 * decision the owner of a world makes for one session, and a file is the wrong shape for it - it
 * outlives the session, it can be hand-edited into a session it was never granted for, and it puts
 * an X-ray switch on the settings screen of every player on a server.</p>
 *
 * <p>So they live here instead: in memory, for the life of one connection, reachable only through
 * {@code /lune omniscient}, and only where {@link OmniscientAccess} says the world allows it.
 * Changing worlds clears them - the grant belonged to the world that gave it.</p>
 */
public final class Cheats {

    /**
     * One switch per cheat.
     *
     * <p>The command tree is built from these values, so a new cheat is a constant here plus two
     * lines in {@code en_us.json} - never a new branch in the command.</p>
     */
    public enum Mode {
        /** Targets any matching block in a loaded chunk, seen or not. */
        MINING,
        /** Targets any mature crop in a loaded chunk, seen or not. */
        HARVEST;

        /** The word typed after {@code /lune omniscient}. English on purpose: it is an identifier. */
        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The mode's name, as it reads in a chat reply. */
        public String label() {
            return Lang.get("lune.cheat." + id() + ".name");
        }

        /** What the mode lets the bot do, for the status listing. */
        public String about() {
            return Lang.get("lune.cheat." + id() + ".about");
        }
    }

    /** Switched on for this connection only; never read from or written to disk. */
    private static final EnumSet<Mode> ENABLED = EnumSet.noneOf(Mode.class);

    /**
     * The connection the switches were granted on, held only for identity.
     *
     * <p>Compared, never dereferenced - and cleared along with the modes rather than kept, because
     * a dead packet listener has no business being pinned by a static field.</p>
     */
    private static Object session;

    private Cheats() {}

    /** True when the switch is on, whatever the current world thinks of it. */
    public static synchronized boolean isOn(Mode mode) {
        return ENABLED.contains(mode);
    }

    /** True when {@code mode} is switched on <em>and</em> the current world allows it. */
    public static boolean isActive(Mode mode, Minecraft mc) {
        return isActive(mode, OmniscientAccess.isAllowed(mc));
    }

    /** The same decision with the authority already resolved, so it can be tested headless. */
    public static synchronized boolean isActive(Mode mode, boolean allowed) {
        return allowed && ENABLED.contains(mode);
    }

    /**
     * Switches a mode on or off.
     *
     * @return false when the world refused it, which only ever happens for {@code on}
     */
    public static synchronized boolean set(Mode mode, boolean on, boolean allowed) {
        if (!on) {
            ENABLED.remove(mode);
            return true;
        }
        if (!allowed) {
            return false;
        }
        ENABLED.add(mode);
        return true;
    }

    /** Switches everything off. */
    public static synchronized void clear() {
        ENABLED.clear();
    }

    /**
     * Ties the switches to the connection that granted them; called once per client tick.
     *
     * <p>Leaving a world clears them, and so does joining another one. Without this, a mode turned
     * on in the player's own world would still be on when they joined a server, and would be
     * waiting the moment an operator handed them permission - which is not what anybody granted.</p>
     */
    public static void enforce(Minecraft mc) {
        bind(mc == null ? null : mc.getConnection());
        if (!OmniscientAccess.isAllowed(mc)) {
            // Belt and braces. A server can withdraw the permission without the connection
            // changing, and every reader asks OmniscientAccess anyway - this only keeps the
            // switches themselves honest, so a status listing never claims more than is true.
            clear();
        }
    }

    /** Clears the switches when the connection behind them is gone or replaced. */
    static synchronized void bind(Object connection) {
        if (connection == session) {
            return;
        }
        session = connection;
        ENABLED.clear();
    }
}
