package com.etka.lune.server;

import com.google.gson.JsonElement;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Rules that a mod built on Lune keeps beside Lune's own: Lumi's, for who may command a Lumi and
 * how many Lumis a player may have.
 *
 * <p>They live where Lune's live - in {@code config/lune-server.json}, on the Server tab, in the
 * notice every player with Lune is sent - so a server owner finds every rule in one place, and an
 * add-on does not grow a second copy of the machinery that keeps, watches, sends and edits them.
 * Lune never knows what a rule is for. It keeps the value, works out each player's verdict on a
 * who-rule the way it works out its own, and answers the add-on's questions through
 * {@link LuneServer}.</p>
 *
 * <p>An add-on registers its rules from its common entry point, on both sides, before a world is
 * loaded; the list is read-only after that. Read on both sides, so nothing here touches a class
 * only one of them has.</p>
 */
public final class AddonRules {

    /** What a rule's value is. */
    public enum Kind {
        /** Who it lets through: everyone, operators or nobody, as Lune's own rules are. */
        WHO,
        /** A number, where 0 means no limit at all. */
        LIMIT
    }

    /**
     * One rule.
     *
     * @param id       the rule's name in the file and on the wire, the add-on's namespace first:
     *                 {@code lumi:command}. A colon can never be one of Lune's own field names.
     * @param labelKey the line naming it on the Server tab, from the add-on's own language file
     * @param dedicated where a dedicated server starts
     * @param ownWorld  where the host's own world starts, opened to LAN or not
     * @param presets   for a limit, the answers the Server tab offers; any other number is the
     *                  file's to say
     * @param max       for a limit, the largest number it may be
     */
    public record Rule(String id, Kind kind, String labelKey, String dedicated, String ownWorld,
                       List<Integer> presets, int max) {

        public Rule {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(kind, "kind");
            if (!id.contains(":") || !id.equals(id.trim()) || id.length() > 64) {
                throw new IllegalArgumentException("An add-on rule is named namespace:rule, not '" + id + "'");
            }
            presets = presets == null ? List.of() : List.copyOf(presets);
        }

        public static Rule who(String id, String labelKey, ServerRules.Who dedicated, ServerRules.Who ownWorld) {
            return new Rule(id, Kind.WHO, labelKey, dedicated.id(), ownWorld.id(), List.of(), 0);
        }

        public static Rule limit(String id, String labelKey, int dedicated, int ownWorld, int max,
                                 List<Integer> presets) {
            return new Rule(id, Kind.LIMIT, labelKey, String.valueOf(dedicated), String.valueOf(ownWorld),
                    presets, max);
        }

        /** Where this rule starts on one kind of server. */
        public String start(boolean dedicatedServer) {
            return dedicatedServer ? dedicated : ownWorld;
        }

        /**
         * A written value as this rule keeps it, or null when it says nothing the rule accepts:
         * a word Lune does not know, a number out of range, something that is not a number.
         */
        public String accept(JsonElement value) {
            if (value == null || !value.isJsonPrimitive()) {
                return null;
            }
            return accept(value.getAsString());
        }

        /** {@link #accept(JsonElement)}, for a value already read as text. */
        public String accept(String value) {
            if (value == null) {
                return null;
            }
            if (kind == Kind.WHO) {
                ServerRules.Who who = ServerRules.Who.byId(value);
                return who == null ? null : who.id();
            }
            try {
                int number = Integer.parseInt(value.trim());
                return number < 0 || number > max ? null : String.valueOf(number);
            } catch (NumberFormatException notANumber) {
                return null;
            }
        }
    }

    private static final List<Rule> RULES = new CopyOnWriteArrayList<>();

    private AddonRules() {}

    /**
     * Adds a rule. Registering the same rule again is nothing - a loader may run a common entry
     * point twice - and another rule under a name already taken is refused.
     */
    public static synchronized void register(Rule rule) {
        Rule existing = byId(rule.id());
        if (existing != null) {
            if (!existing.equals(rule)) {
                throw new IllegalArgumentException("Another add-on rule is already called " + rule.id());
            }
            return;
        }
        if (rule.accept(rule.dedicated()) == null || rule.accept(rule.ownWorld()) == null) {
            throw new IllegalArgumentException("Rule " + rule.id() + " starts somewhere it would refuse");
        }
        RULES.add(rule);
    }

    /** Takes a rule away again. For tests only: an add-on's rules stay for as long as the game runs. */
    static synchronized void unregister(String id) {
        RULES.removeIf(rule -> rule.id().equals(id));
    }

    /** Every rule, in the order they were registered: the order the file and the Server tab use. */
    public static List<Rule> all() {
        return List.copyOf(RULES);
    }

    public static Rule byId(String id) {
        for (Rule rule : RULES) {
            if (rule.id().equals(id)) {
                return rule;
            }
        }
        return null;
    }

    /** Where every rule starts on one kind of server. */
    public static Map<String, String> starts(boolean dedicated) {
        Map<String, String> starts = new LinkedHashMap<>();
        for (Rule rule : RULES) {
            starts.put(rule.id(), rule.start(dedicated));
        }
        return starts;
    }
}
