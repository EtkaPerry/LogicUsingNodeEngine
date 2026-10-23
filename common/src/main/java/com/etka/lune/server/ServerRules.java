package com.etka.lune.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * What a server that runs Lune lets the Lune players on it do.
 *
 * <p>Two rules of Lune's own, each naming who it lets through: who may run tasks at all, and who
 * may switch on the omniscient modes. Beside them, whatever rules a mod built on Lune keeps there
 * ({@link AddonRules}), by name. They are the server's to set - its operators change them on
 * Lune's Server tab, or its owner edits {@code config/lune-server.json} - and every Lune client
 * on the server is told them as it joins and again whenever they change.</p>
 *
 * <p>Read on both sides of the connection, so this class touches nothing that only one side has:
 * no client class and no server class, only the rules and the way they are written down.</p>
 *
 * @param addons the add-on rules' values, by rule name, as {@link AddonRules.Rule#accept} keeps
 *               them: a who-rule's word, or a limit's number
 */
public record ServerRules(Who run, Who cheats, Map<String, String> addons) {

    /** Who a rule lets through. */
    public enum Who {
        EVERYONE,
        /** Players with vanilla's gamemaster permission: the level {@code /gamerule} asks for. */
        OPERATORS,
        NOBODY;

        /** The word in the file and on the wire. English on purpose: it is an identifier. */
        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The rule a written word names, or null when it names none. */
        public static Who byId(String id) {
            if (id == null) {
                return null;
            }
            String word = id.trim().toLowerCase(Locale.ROOT);
            for (Who who : values()) {
                if (who.id().equals(word)) {
                    return who;
                }
            }
            return null;
        }

        /** Whether this rule lets a player through: anybody at all, or an operator. */
        public boolean admits(boolean operator) {
            return this == EVERYONE || this == OPERATORS && operator;
        }
    }

    /**
     * Where a dedicated server starts the moment Lune is dropped into its mods folder: the people
     * who run the server keep the bot, and nobody else has it until they say so. An owner who
     * installs the server half is usually doing it to decide this, and a default that let every
     * player through would make installing it change nothing.
     */
    public static final ServerRules DEDICATED = new ServerRules(Who.OPERATORS, Who.OPERATORS);

    /**
     * Where the host's own world starts when it is opened to LAN. The people who join it are the
     * friends it was opened for, and Lune worked for them before there were any rules at all.
     */
    public static final ServerRules OWN_WORLD = new ServerRules(Who.EVERYONE, Who.OPERATORS);

    /** What a client makes of rules it cannot read: nothing is let through. */
    public static final ServerRules CLOSED = new ServerRules(Who.NOBODY, Who.NOBODY);

    /**
     * A rule that was missing, or said something Lune does not accept, and what was used instead.
     *
     * @param found     what the rule said, or null when it was not there at all
     * @param used      the answer used instead, for one of Lune's own rules; null for an add-on's
     * @param usedValue the answer used instead, as written
     */
    public record Problem(String field, String found, Who used, String usedValue) {
        public Problem(String field, String found, Who used) {
            this(field, found, used, used.id());
        }
    }

    /** The two rules' names, in the file and on the wire. */
    public static final String RUN = "run";
    public static final String CHEATS = "cheats";

    public ServerRules {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(cheats, "cheats");
        addons = addons == null ? Map.of() : Map.copyOf(addons);
    }

    public ServerRules(Who run, Who cheats) {
        this(run, cheats, Map.of());
    }

    /** Where one kind of server starts: Lune's rules, and every add-on rule registered. */
    public static ServerRules defaults(boolean dedicated) {
        ServerRules lune = dedicated ? DEDICATED : OWN_WORLD;
        return new ServerRules(lune.run, lune.cheats, AddonRules.starts(dedicated));
    }

    /** Whether a player may start tasks. The owner of the world being played is never held to it. */
    public boolean mayRun(boolean operator, boolean host) {
        return host || run.admits(operator);
    }

    /** Whether a player may switch the omniscient modes on. The same exception for the owner. */
    public boolean mayCheat(boolean operator, boolean host) {
        return host || cheats.admits(operator);
    }

    /** An add-on who-rule's answer, or null when there is no such rule here. */
    public Who who(String rule) {
        return Who.byId(addons.get(rule));
    }

    /**
     * Whether an add-on who-rule lets a player through - the owner of the world always, as with
     * Lune's own. A rule this server does not have lets nobody through.
     */
    public boolean allows(String rule, boolean operator, boolean host) {
        Who who = who(rule);
        return host || who != null && who.admits(operator);
    }

    /** An add-on limit, where 0 is none; a limit this server does not have is none too. */
    public int limit(String rule) {
        try {
            String value = addons.get(rule);
            return value == null ? 0 : Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    /** These rules with one add-on rule's value changed. */
    public ServerRules with(String rule, String value) {
        Map<String, String> changed = new LinkedHashMap<>(addons);
        changed.put(rule, value);
        return new ServerRules(run, cheats, changed);
    }

    /** Writes every rule into {@code json}, which may carry other fields beside them. */
    public void write(JsonObject json) {
        json.addProperty(RUN, run.id());
        json.addProperty(CHEATS, cheats.id());
        for (AddonRules.Rule rule : AddonRules.all()) {
            String value = addons.get(rule.id());
            if (value == null) {
                continue;
            }
            if (rule.kind() == AddonRules.Kind.LIMIT) {
                json.addProperty(rule.id(), limit(rule.id()));
            } else {
                json.addProperty(rule.id(), value);
            }
        }
    }

    /**
     * Reads rules written by {@link #write}. A rule that is missing, or says something Lune does not
     * accept, keeps {@code fallback}'s, and goes into {@code problems} - except an add-on rule that is
     * simply not there yet, which a file written before its add-on was installed cannot have said.
     * Nothing here throws: this reads a file an owner edits by hand and bytes another version of
     * Lune sent.
     */
    public static ServerRules read(JsonObject json, ServerRules fallback, List<Problem> problems) {
        Who run = readWho(json, RUN, fallback.run, problems);
        Who cheats = readWho(json, CHEATS, fallback.cheats, problems);
        Map<String, String> addons = new LinkedHashMap<>();
        for (AddonRules.Rule rule : AddonRules.all()) {
            JsonElement value = json == null ? null : json.get(rule.id());
            String kept = fallback.addons.get(rule.id());
            if (value == null || value.isJsonNull()) {
                if (kept != null) {
                    addons.put(rule.id(), kept);
                }
                continue;
            }
            String accepted = rule.accept(value);
            if (accepted == null) {
                problems.add(new Problem(rule.id(), value.toString(), null, kept));
                if (kept != null) {
                    addons.put(rule.id(), kept);
                }
                continue;
            }
            addons.put(rule.id(), accepted);
        }
        return new ServerRules(run, cheats, addons);
    }

    private static Who readWho(JsonObject json, String field, Who fallback, List<Problem> problems) {
        JsonElement value = json == null ? null : json.get(field);
        if (value == null || value.isJsonNull()) {
            problems.add(new Problem(field, null, fallback));
            return fallback;
        }
        Who who = value.isJsonPrimitive() ? Who.byId(value.getAsString()) : null;
        if (who == null) {
            problems.add(new Problem(field, value.toString(), fallback));
            return fallback;
        }
        return who;
    }
}
