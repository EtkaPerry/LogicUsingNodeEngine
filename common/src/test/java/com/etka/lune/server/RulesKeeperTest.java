package com.etka.lune.server;

import com.etka.lune.net.EditRulesPayload;
import com.etka.lune.net.HelloPayload;
import com.etka.lune.net.RulesPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server half's decisions, with made-up players: who is told what, who may change the rules,
 * and that every player hears a change that concerns them - once, and only then.
 */
class RulesKeeperTest {

    /** A player the test controls: an operator or not, and everything they were told. */
    private static final class Player implements RulesKeeper.Member {
        private final UUID id = UUID.randomUUID();
        private final String name;
        private boolean operator;
        private final boolean host;
        /** Whether this player's client can hear Lune's payloads yet. */
        private boolean listening = true;
        final List<RulesPayload> told = new ArrayList<>();

        Player(String name, boolean operator, boolean host) {
            this.name = name;
            this.operator = operator;
            this.host = host;
        }

        @Override
        public UUID id() {
            return id;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean operator() {
            return operator;
        }

        @Override
        public boolean host() {
            return host;
        }

        @Override
        public boolean tell(RulesPayload notice) {
            if (listening) {
                told.add(notice);
            }
            return listening;
        }

        RulesPayload last() {
            return told.getLast();
        }
    }

    private final Map<UUID, Player> online = new LinkedHashMap<>();
    private final Function<UUID, RulesKeeper.Member> lookup = online::get;
    private RulesKeeper keeper;

    @BeforeEach
    void startAServer() {
        keeper = new RulesKeeper(ServerRules.DEDICATED, "9.9");
    }

    private Player join(String name, boolean operator) {
        Player player = new Player(name, operator, false);
        online.put(player.id(), player);
        keeper.hello(player, new HelloPayload(1, "1.0"), lookup);
        return player;
    }

    @Test
    void aPlayerWhoSaysHelloIsToldWhereTheyStand() {
        Player steve = join("Steve", false);
        assertEquals(1, steve.told.size());
        RulesPayload notice = steve.last();
        assertFalse(notice.mayRun(), "a fresh server keeps the bot to its operators");
        assertFalse(notice.mayEdit());
        assertEquals("9.9", notice.version());
        assertTrue(notice.players().isEmpty(), "who has Lune is the operators' business");
    }

    @Test
    void operatorsAreToldWhoHasLuneAndHearWhenSomebodyArrives() {
        Player op = join("Op", true);
        assertTrue(op.last().mayRun());
        assertTrue(op.last().mayEdit());
        assertEquals(List.of(new RulesPayload.LunePlayer("Op", "1.0")), op.last().players());

        Player alex = join("alex", false);
        assertEquals(List.of("alex", "Op"), op.last().players().stream().map(RulesPayload.LunePlayer::name).toList(),
                "listed by name, whatever the case");
        assertEquals(1, alex.told.size(), "the newcomer hears once, not once more for the list");
    }

    @Test
    void anOperatorsChangeReachesEveryoneItConcerns() {
        Player op = join("Op", true);
        Player steve = join("Steve", false);
        int opHeard = op.told.size();

        assertTrue(keeper.edit(op, new EditRulesPayload(ServerRules.Who.EVERYONE, null), lookup));
        assertTrue(steve.last().mayRun(), "Steve may run tasks now");
        assertEquals(ServerRules.Who.OPERATORS, keeper.rules().cheats(), "the rule nobody touched stays put");
        assertEquals(opHeard + 1, op.told.size());
    }

    @Test
    void somebodyWhoIsNotAnOperatorChangesNothing() {
        join("Op", true);
        Player steve = join("Steve", false);
        int heard = steve.told.size();

        assertFalse(keeper.edit(steve, new EditRulesPayload(ServerRules.Who.EVERYONE, ServerRules.Who.EVERYONE), lookup));
        assertEquals(ServerRules.DEDICATED, keeper.rules());
        assertEquals(heard + 1, steve.told.size(), "told the rules again, so their page shows the truth");
        assertFalse(steve.last().mayRun());
    }

    /** The owner of an integrated world is never held to its rules and may always change them. */
    @Test
    void theHostMayAlwaysRunAndEdit() {
        keeper = new RulesKeeper(ServerRules.CLOSED, "9.9");
        Player host = new Player("Host", false, true);
        online.put(host.id(), host);
        keeper.hello(host, new HelloPayload(1, "1.0"), lookup);
        assertTrue(host.last().mayRun());
        assertTrue(host.last().mayCheat());
        assertTrue(host.last().mayEdit());
        assertTrue(keeper.edit(host, new EditRulesPayload(ServerRules.Who.EVERYONE, null), lookup));
    }

    /** An /op changes a player's answer without passing through the keeper; the next look catches it. */
    @Test
    void anOpMadeElsewhereIsNoticedOnTheNextLook() {
        Player steve = join("Steve", false);
        int heard = steve.told.size();

        keeper.refresh(lookup);
        assertEquals(heard, steve.told.size(), "nothing changed, so nothing is sent");

        steve.operator = true;
        keeper.refresh(lookup);
        assertEquals(heard + 1, steve.told.size());
        assertTrue(steve.last().mayRun());
        assertTrue(steve.last().mayEdit());
    }

    /**
     * A client can say hello before its loader has told the server what it listens for. The notice
     * that could not go is not counted as told, so the next look sends it.
     */
    @Test
    void aNoticeThatCouldNotBeSentIsSentOnTheNextLook() {
        Player steve = new Player("Steve", false, false);
        steve.listening = false;
        online.put(steve.id(), steve);
        keeper.hello(steve, new HelloPayload(1, "1.0"), lookup);
        assertTrue(steve.told.isEmpty());

        steve.listening = true;
        keeper.refresh(lookup);
        assertEquals(1, steve.told.size());
        keeper.refresh(lookup);
        assertEquals(1, steve.told.size(), "and only once");
    }

    @Test
    void aPlayerWhoLeavesDropsOffTheList() {
        Player op = join("Op", true);
        Player steve = join("Steve", false);
        online.remove(steve.id());
        keeper.left(steve.id(), lookup);
        assertEquals(1, keeper.count());
        assertEquals(List.of("Op"), op.last().players().stream().map(RulesPayload.LunePlayer::name).toList());
    }

    /** A hand-edited file is the same news as an operator's click, and reaches the same people. */
    @Test
    void rulesReplacedFromTheFileAreHeardByEveryone() {
        Player steve = join("Steve", false);
        assertTrue(keeper.replace(new ServerRules(ServerRules.Who.EVERYONE, ServerRules.Who.NOBODY), lookup));
        assertTrue(steve.last().mayRun());
        assertFalse(keeper.replace(keeper.rules(), lookup), "the same rules again are no news");
    }

    @Test
    void anEditThatChangesNothingIsNotSaved() {
        Player op = join("Op", true);
        assertFalse(keeper.edit(op, new EditRulesPayload(ServerRules.Who.OPERATORS, null), lookup));
        assertFalse(keeper.edit(op, new EditRulesPayload(null, null), lookup));
    }
}
