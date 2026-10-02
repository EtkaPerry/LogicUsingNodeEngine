package com.etka.lune.server;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What each rule lets through, and how the rules are written down and read back. */
class ServerRulesTest {

    @Test
    void eachAnswerLetsThroughWhoItNames() {
        assertTrue(ServerRules.Who.EVERYONE.admits(false));
        assertTrue(ServerRules.Who.EVERYONE.admits(true));
        assertFalse(ServerRules.Who.OPERATORS.admits(false));
        assertTrue(ServerRules.Who.OPERATORS.admits(true));
        assertFalse(ServerRules.Who.NOBODY.admits(false));
        assertFalse(ServerRules.Who.NOBODY.admits(true), "nobody means operators too");
    }

    /** The world's owner set the rules for the friends who join it, never for themselves. */
    @Test
    void theHostIsNeverHeldToTheRules() {
        assertTrue(ServerRules.CLOSED.mayRun(false, true));
        assertTrue(ServerRules.CLOSED.mayCheat(false, true));
        assertFalse(ServerRules.CLOSED.mayRun(true, false));
    }

    /**
     * Dropping Lune into a dedicated server's mods folder is the owner choosing to decide, so it
     * starts by keeping the bot to the people who run the server. A world opened to LAN starts
     * where Lune always was for the friends who join it.
     */
    @Test
    void aServerStartsClosedToAllButItsOperatorsAndAnOwnWorldStartsOpen() {
        ServerRules dedicated = ServerRules.defaults(true);
        assertFalse(dedicated.mayRun(false, false));
        assertTrue(dedicated.mayRun(true, false));
        assertFalse(dedicated.mayCheat(false, false));

        ServerRules ownWorld = ServerRules.defaults(false);
        assertTrue(ownWorld.mayRun(false, false));
        assertFalse(ownWorld.mayCheat(false, false), "the cheats stay where they always were: operators");
        assertTrue(ownWorld.mayCheat(true, false));
    }

    /** The words are identifiers in a file an owner types into, so case and spaces are forgiven. */
    @Test
    void theWordsAreReadTheWayAnOwnerMightTypeThem() {
        assertEquals(ServerRules.Who.OPERATORS, ServerRules.Who.byId("operators"));
        assertEquals(ServerRules.Who.NOBODY, ServerRules.Who.byId("  NOBODY "));
        assertEquals(ServerRules.Who.EVERYONE, ServerRules.Who.byId("Everyone"));
        assertNull(ServerRules.Who.byId("operator"), "a near miss names nothing, rather than guessing");
        assertNull(ServerRules.Who.byId(null));
    }

    /**
     * Written in English whatever the machine's language: a Turkish lower case turns OPERATORS'
     * I into a dotless one, and the file would then name no rule on any other machine.
     */
    @Test
    void theWordsAreTheSameOnEveryMachine() {
        assertEquals("everyone", ServerRules.Who.EVERYONE.id());
        assertEquals("operators", ServerRules.Who.OPERATORS.id());
        assertEquals("nobody", ServerRules.Who.NOBODY.id());
    }

    @Test
    void rulesSurviveBeingWrittenDownAndReadBack() {
        ServerRules rules = new ServerRules(ServerRules.Who.NOBODY, ServerRules.Who.EVERYONE);
        JsonObject json = new JsonObject();
        rules.write(json);
        List<ServerRules.Problem> problems = new ArrayList<>();
        assertEquals(rules, ServerRules.read(json, ServerRules.DEDICATED, problems));
        assertTrue(problems.isEmpty(), problems.toString());
    }

    /** A rule that is missing or misspelt keeps the fallback's, and says which and why. */
    @Test
    void aBadOrMissingRuleKeepsTheFallbackAndSaysSo() {
        JsonObject json = new JsonObject();
        json.addProperty(ServerRules.RUN, "operator");
        List<ServerRules.Problem> problems = new ArrayList<>();
        ServerRules read = ServerRules.read(json, ServerRules.DEDICATED, problems);
        assertEquals(ServerRules.DEDICATED, read);
        assertEquals(List.of(
                new ServerRules.Problem(ServerRules.RUN, "\"operator\"", ServerRules.Who.OPERATORS),
                new ServerRules.Problem(ServerRules.CHEATS, null, ServerRules.Who.OPERATORS)), problems);
    }
}
