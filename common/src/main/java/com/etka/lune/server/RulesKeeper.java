package com.etka.lune.server;

import com.etka.lune.Constants;
import com.etka.lune.net.EditRulesPayload;
import com.etka.lune.net.HelloPayload;
import com.etka.lune.net.LuneNet;
import com.etka.lune.net.RulesPayload;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * The server half's decisions: which players have Lune, what the rules are, and what each of them
 * has to be told.
 *
 * <p>Kept apart from the running server so it can be tested without one. {@link LuneServer} turns
 * the game's players into {@link Member}s and calls in; everything decided is decided here.</p>
 *
 * <p>One idea carries it: every player with Lune is told where they stand whenever that changes,
 * and never otherwise. What they were last told is kept beside them, so a rule edited, a player
 * opped, or another Lune player arriving all come down to one question - is this player's notice
 * different now - and {@link #refresh} is the only place that answers it.</p>
 */
public final class RulesKeeper {

    /** A player, as far as the rules need one: who, what they hold, and a way to tell them. */
    public interface Member {
        UUID id();

        String name();

        /** Vanilla's gamemaster permission, the same one the omniscient modes have always asked for. */
        boolean operator();

        /** The owner of the integrated world being played, who is never held to its rules. */
        boolean host();

        /**
         * Sends the notice, if this player's client can hear it yet.
         *
         * @return whether it went. One that did not is sent again on the next look rather than
         *         counted as told: the loaders do not all learn what a client listens for before
         *         its hello arrives.
         */
        boolean tell(RulesPayload notice);
    }

    /** A player whose Lune has said hello, and the last notice they were sent. */
    private record Known(String name, String version, int protocol, RulesPayload told) {
        Known told(RulesPayload notice) {
            return new Known(name, version, protocol, notice);
        }
    }

    private final String version;
    private ServerRules rules;
    /** In the order they said hello; listed by name. */
    private final Map<UUID, Known> known = new LinkedHashMap<>();

    /** @param version this server's own Lune version, which every notice carries */
    public RulesKeeper(ServerRules rules, String version) {
        this.rules = rules;
        this.version = version;
    }

    public ServerRules rules() {
        return rules;
    }

    /** How many players with Lune are known to be on the server. */
    public int count() {
        return known.size();
    }

    /** Operators change the rules, and the owner of the world does. Nobody else does. */
    public static boolean mayEdit(Member member) {
        return member.host() || member.operator();
    }

    /** Every player whose Lune has said hello, by name. */
    public List<RulesPayload.LunePlayer> players() {
        List<RulesPayload.LunePlayer> players = new ArrayList<>();
        for (Known entry : known.values()) {
            players.add(new RulesPayload.LunePlayer(entry.name(), entry.version()));
        }
        players.sort(Comparator.comparing(player -> player.name().toLowerCase(Locale.ROOT)));
        return players;
    }

    /** What {@code member} would be told now. */
    public RulesPayload noticeFor(Member member) {
        boolean editor = mayEdit(member);
        return new RulesPayload(LuneNet.PROTOCOL, version, rules,
                rules.mayRun(member.operator(), member.host()),
                rules.mayCheat(member.operator(), member.host()),
                editor, editor ? players() : List.of());
    }

    /**
     * A player's Lune said hello. They are told the rules whatever they were told before - a
     * client that says hello has started over - and the operators' lists gain a name.
     */
    public void hello(Member member, HelloPayload hello, Function<UUID, Member> online) {
        known.put(member.id(), new Known(member.name(), hello.version(), hello.protocol(), null));
        Constants.LOG.info("{} joined with Lune {} (protocol {}); the server rules {} them run tasks",
                member.name(), hello.version(), hello.protocol(),
                rules.mayRun(member.operator(), member.host()) ? "let" : "do not let");
        tellAgain(member);
        refresh(online);
    }

    /**
     * An operator asked for a change. Anybody else is refused and sent the rules again, which puts
     * the tab they pressed back to the truth.
     *
     * @return whether the rules changed, which is when they are worth writing down
     */
    public boolean edit(Member member, EditRulesPayload edit, Function<UUID, Member> online) {
        if (!mayEdit(member)) {
            Constants.LOG.warn("{} asked to change Lune's server rules without operator permission;"
                    + " nothing changed", member.name());
            tellAgain(member);
            return false;
        }
        ServerRules proposed = edit.applyTo(rules);
        if (proposed.equals(rules)) {
            tellAgain(member);
            return false;
        }
        rules = proposed;
        Constants.LOG.info("{} changed Lune's server rules: who may run tasks: {}; who may use the"
                + " omniscient modes: {}", member.name(), rules.run().id(), rules.cheats().id());
        refresh(online);
        return true;
    }

    /** The rules changed somewhere else - the file was edited by hand. Everybody hears. */
    public boolean replace(ServerRules replacement, Function<UUID, Member> online) {
        if (replacement.equals(rules)) {
            return false;
        }
        rules = replacement;
        refresh(online);
        return true;
    }

    /** A player left. The operators' list loses them. */
    public void left(UUID id, Function<UUID, Member> online) {
        if (known.remove(id) != null) {
            refresh(online);
        }
    }

    /**
     * Tells every player with Lune whose notice has changed since the last one they got, and
     * forgets any who are no longer online.
     *
     * <p>Also called now and then with nothing having happened here, because some changes never
     * pass through this class: an operator made or unmade with {@code /op}, which changes that
     * player's answer and whether they see the list.</p>
     */
    public void refresh(Function<UUID, Member> online) {
        Iterator<Map.Entry<UUID, Known>> entries = known.entrySet().iterator();
        List<Member> changed = new ArrayList<>();
        while (entries.hasNext()) {
            Map.Entry<UUID, Known> entry = entries.next();
            Member member = online.apply(entry.getKey());
            if (member == null) {
                entries.remove();
                continue;
            }
            if (!noticeFor(member).equals(entry.getValue().told())) {
                changed.add(member);
            }
        }
        for (Member member : changed) {
            // Worked out after the loop, so a player who dropped out of the list above is not still
            // on it in the notice the others get.
            tellAgain(member);
        }
    }

    private void tellAgain(Member member) {
        RulesPayload notice = noticeFor(member);
        if (member.tell(notice)) {
            known.computeIfPresent(member.id(), (id, entry) -> entry.told(notice));
        }
    }
}
