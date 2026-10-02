package com.etka.lune.bot.util;

import com.etka.lune.net.RulesPayload;
import com.etka.lune.server.ServerRules;
import com.etka.lune.util.Lang;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client's side of a server's rules: its own world is never held to anything, a server with no
 * Lune changes nothing, and a server with Lune decides - nothing being allowed until it has.
 */
class ServerAccessTest {

    private static final Object ONE_SERVER = new Object();
    private static final Object ANOTHER_SERVER = new Object();

    private static RulesPayload verdict(ServerRules rules, boolean mayRun, boolean mayCheat) {
        return new RulesPayload(1, "1.0", rules, mayRun, mayCheat, false, List.of());
    }

    @BeforeEach
    void joinAServer() {
        ServerAccess.bind(null);
        ServerAccess.bind(ONE_SERVER);
    }

    @Test
    void aServerWithoutLuneChangesNothing() {
        assertEquals(ServerAccess.State.NO_LUNE, ServerAccess.state());
        assertTrue(ServerAccess.mayRun(false, ServerAccess.State.NO_LUNE, null));
        assertFalse(ServerAccess.mayCheat(false, false, ServerAccess.State.NO_LUNE, null),
                "the cheats still need an operator, as they always did");
        assertTrue(ServerAccess.mayCheat(false, true, ServerAccess.State.NO_LUNE, null));
    }

    /** The server runs Lune and has not answered: nothing runs until it does. */
    @Test
    void nothingIsAllowedWhileTheAnswerIsOnItsWay() {
        assertFalse(ServerAccess.mayRun(false, ServerAccess.State.WAITING, null));
        assertFalse(ServerAccess.mayCheat(false, true, ServerAccess.State.WAITING, null),
                "not even for an operator: the server may have closed the cheats to everyone");
    }

    @Test
    void theServersAnswerDecides() {
        RulesPayload yes = verdict(ServerRules.OWN_WORLD, true, false);
        RulesPayload no = verdict(ServerRules.DEDICATED, false, false);
        assertTrue(ServerAccess.mayRun(false, ServerAccess.State.HEARD, yes));
        assertFalse(ServerAccess.mayRun(false, ServerAccess.State.HEARD, no));
        assertFalse(ServerAccess.mayCheat(false, true, ServerAccess.State.HEARD, no),
                "being an operator is the server's to weigh, and it already has");
    }

    /** The player's own world, open to LAN or not, is never held to the rules it sets for guests. */
    @Test
    void theOwnWorldIsNeverRefused() {
        RulesPayload closed = verdict(ServerRules.CLOSED, false, false);
        for (ServerAccess.State state : ServerAccess.State.values()) {
            assertTrue(ServerAccess.mayRun(true, state, closed), state.name());
            assertTrue(ServerAccess.mayCheat(true, false, state, closed), state.name());
        }
    }

    @Test
    void anAnswerIsKeptForTheServerThatGaveIt() {
        ServerAccess.receive(verdict(ServerRules.OWN_WORLD, true, false));
        assertEquals(ServerAccess.State.HEARD, ServerAccess.state());
        ServerAccess.bind(ONE_SERVER);
        assertEquals(ServerAccess.State.HEARD, ServerAccess.state(), "the same connection, tick after tick");
    }

    /** Another server's answer is not this one's: joining elsewhere starts from nothing. */
    @Test
    void changingServersForgetsTheAnswer() {
        ServerAccess.receive(verdict(ServerRules.OWN_WORLD, true, true));
        ServerAccess.bind(ANOTHER_SERVER);
        assertNull(ServerAccess.heard());
        assertEquals(ServerAccess.State.NO_LUNE, ServerAccess.state());
    }

    @Test
    void leavingForgetsTheAnswer() {
        ServerAccess.receive(verdict(ServerRules.OWN_WORLD, true, true));
        ServerAccess.bind(null);
        assertNull(ServerAccess.heard());
    }

    /** The refusal names the rule that refused, so the player knows who to ask. */
    @Test
    void aRefusalSaysWhichRuleRefused() {
        String operators = ServerAccess.refusal(ServerAccess.State.HEARD, verdict(ServerRules.DEDICATED, false, false));
        String nobody = ServerAccess.refusal(ServerAccess.State.HEARD, verdict(ServerRules.CLOSED, false, false));
        String waiting = ServerAccess.refusal(ServerAccess.State.WAITING, null);
        assertEquals(Lang.get("lune.server.refusal.operators"), operators);
        assertEquals(Lang.get("lune.server.refusal.nobody"), nobody);
        assertEquals(Lang.get("lune.server.refusal.waiting"), waiting);
    }

    /** Only a server that closed the cheats to everyone gets the blame for them being locked. */
    @Test
    void theCheatsAreBlamedOnTheServerOnlyWhenItClosedThemToEveryone() {
        assertFalse(ServerAccess.serverClosesCheats());
        ServerAccess.receive(verdict(ServerRules.DEDICATED, false, false));
        assertFalse(ServerAccess.serverClosesCheats(), "operators only: the old message is the right one");
        ServerAccess.receive(verdict(ServerRules.CLOSED, false, false));
        assertTrue(ServerAccess.serverClosesCheats());
    }

    /** The five things the Server tab and Lune can say about where the player stands. */
    @Test
    void theStandingFollowsTheAnswer() {
        assertEquals(ServerAccess.Standing.OWN_WORLD,
                ServerAccess.standing(true, ServerAccess.State.HEARD, verdict(ServerRules.CLOSED, false, false)));
        assertEquals(ServerAccess.Standing.NO_LUNE, ServerAccess.standing(false, ServerAccess.State.NO_LUNE, null));
        assertEquals(ServerAccess.Standing.WAITING, ServerAccess.standing(false, ServerAccess.State.WAITING, null));
        assertEquals(ServerAccess.Standing.ALLOWED,
                ServerAccess.standing(false, ServerAccess.State.HEARD, verdict(ServerRules.OWN_WORLD, true, false)));
        assertEquals(ServerAccess.Standing.REFUSED,
                ServerAccess.standing(false, ServerAccess.State.HEARD, verdict(ServerRules.DEDICATED, false, false)));
    }

    @Test
    void nobodyMayProposeWithoutAnAnswerThatSaysTheyMay() {
        assertFalse(ServerAccess.propose(ServerRules.Who.EVERYONE, null));
        ServerAccess.receive(verdict(ServerRules.DEDICATED, false, false));
        assertFalse(ServerAccess.propose(ServerRules.Who.EVERYONE, null));
    }
}
