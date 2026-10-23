package com.etka.lune.server;

import com.etka.lune.net.EditRulesPayload;
import com.etka.lune.net.HelloPayload;
import com.etka.lune.net.RulesPayload;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules a mod built on Lune keeps beside Lune's own: kept in the same file, sent in the same
 * notice, changed by the same request, and judged the same way.
 */
class AddonRulesTest {

    private static final String WHO = "test:command";
    private static final String LIMIT = "test:per_player";

    @TempDir
    Path config;

    @BeforeEach
    void register() {
        AddonRules.register(AddonRules.Rule.who(WHO, "test.rule.command",
                ServerRules.Who.EVERYONE, ServerRules.Who.EVERYONE));
        AddonRules.register(AddonRules.Rule.limit(LIMIT, "test.rule.per_player", 3, 3, 64,
                List.of(1, 3, 5, 10, 0)));
    }

    @AfterEach
    void forget() {
        AddonRules.unregister(WHO);
        AddonRules.unregister(LIMIT);
    }

    @Test
    void everyKindOfServerStartsWhereTheRuleSays() {
        ServerRules dedicated = ServerRules.defaults(true);
        assertEquals(ServerRules.Who.OPERATORS, dedicated.run(), "Lune's own rules are as they were");
        assertEquals(ServerRules.Who.EVERYONE, dedicated.who(WHO));
        assertEquals(3, dedicated.limit(LIMIT));
    }

    @Test
    void registeringTheSameRuleTwiceIsNothingAndAnotherUnderItsNameIsRefused() {
        AddonRules.register(AddonRules.Rule.who(WHO, "test.rule.command",
                ServerRules.Who.EVERYONE, ServerRules.Who.EVERYONE));
        assertThrows(IllegalArgumentException.class, () -> AddonRules.register(AddonRules.Rule.who(WHO,
                "test.rule.command", ServerRules.Who.NOBODY, ServerRules.Who.EVERYONE)));
        assertThrows(IllegalArgumentException.class, () -> AddonRules.Rule.who("command", "x",
                ServerRules.Who.EVERYONE, ServerRules.Who.EVERYONE), "a rule is named by its add-on");
    }

    @Test
    void aRuleIsWrittenAsItsKindAndReadBack() {
        ServerRules rules = ServerRules.defaults(true).with(WHO, "operators").with(LIMIT, "7");
        JsonObject json = new JsonObject();
        rules.write(json);
        assertEquals("operators", json.get(WHO).getAsString());
        assertTrue(json.get(LIMIT).getAsJsonPrimitive().isNumber(), "a limit is a number an owner can edit");
        assertEquals(rules, ServerRules.read(json, ServerRules.CLOSED, new ArrayList<>()));
    }

    @Test
    void aValueTheRuleRefusesKeepsWhatWasThereAndSaysSo() {
        JsonObject json = JsonParser.parseString("{\"run\":\"everyone\",\"cheats\":\"nobody\","
                + "\"test:command\":\"admins\",\"test:per_player\":-2}").getAsJsonObject();
        List<ServerRules.Problem> problems = new ArrayList<>();
        ServerRules read = ServerRules.read(json, ServerRules.defaults(true), problems);
        assertEquals(ServerRules.Who.EVERYONE, read.who(WHO));
        assertEquals(3, read.limit(LIMIT));
        assertEquals(2, problems.size());
        assertNull(problems.get(0).used(), "an add-on rule's answer is not one of Lune's");
        assertEquals("everyone", problems.get(0).usedValue());
    }

    @Test
    void aRuleMissingFromAnOlderFileIsNoProblem() {
        JsonObject json = JsonParser.parseString("{\"run\":\"everyone\",\"cheats\":\"nobody\"}").getAsJsonObject();
        List<ServerRules.Problem> problems = new ArrayList<>();
        ServerRules read = ServerRules.read(json, ServerRules.defaults(true), problems);
        assertTrue(problems.isEmpty(), problems.toString());
        assertEquals(3, read.limit(LIMIT));
    }

    @Test
    void theOwnerOfTheWorldIsAlwaysLetThroughAndAMissingRuleLetsNobodyElse() {
        ServerRules nobody = ServerRules.defaults(true).with(WHO, "nobody");
        assertTrue(nobody.allows(WHO, false, true));
        assertFalse(nobody.allows(WHO, true, false));
        ServerRules operators = ServerRules.defaults(true).with(WHO, "operators");
        assertTrue(operators.allows(WHO, true, false));
        assertFalse(operators.allows(WHO, false, false));
        assertFalse(ServerRules.CLOSED.allows(WHO, true, false));
        assertEquals(0, ServerRules.CLOSED.limit(LIMIT), "a limit a server does not have is none");
    }

    @Test
    void anEditChangesTheAddonRuleItNamesAndNothingElse() {
        ServerRules before = ServerRules.defaults(true);
        ServerRules after = new EditRulesPayload(null, null, Map.of(LIMIT, "5")).applyTo(before);
        assertEquals(5, after.limit(LIMIT));
        assertEquals(before.run(), after.run());
        assertEquals(before.who(WHO), after.who(WHO));
        assertEquals(before, new EditRulesPayload(null, null, Map.of(LIMIT, "lots")).applyTo(before),
                "a value the rule refuses changes nothing");
        assertEquals(before, new EditRulesPayload(null, null, Map.of("other:rule", "nobody")).applyTo(before),
                "a rule this side does not have changes nothing");
    }

    @Test
    void theNoticeCarriesEachPlayersVerdictOnEveryWhoRule() {
        RulesKeeper keeper = new RulesKeeper(ServerRules.defaults(true).with(WHO, "operators"), "1.0");
        Member op = new Member("Op", true);
        Member guest = new Member("Guest", false);
        assertTrue(keeper.noticeFor(op).may(WHO));
        assertFalse(keeper.noticeFor(guest).may(WHO));
        assertFalse(keeper.noticeFor(guest).may(LIMIT), "a limit is a number, not a verdict");

        RulesPayload sent = keeper.noticeFor(op);
        keeper.hello(op, new HelloPayload(1, "1.0"), id -> op);
        assertEquals(sent.addonsMay(), op.told.addonsMay());
    }

    @Test
    void aFileWrittenBeforeTheAddonGainsItsRulesAndKeepsLinesLuneDoesNotKnow() throws IOException {
        Path path = config.resolve(ServerRulesFile.NAME);
        Files.writeString(path, "{ \"run\": \"everyone\", \"cheats\": \"nobody\", \"gone:rule\": 9 }",
                StandardCharsets.UTF_8);
        ServerRules loaded = new ServerRulesFile(path).load(ServerRules.defaults(true), true);
        assertEquals(ServerRules.Who.EVERYONE, loaded.run());
        assertEquals(3, loaded.limit(LIMIT));
        JsonObject written = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("everyone", written.get(WHO).getAsString());
        assertEquals(3, written.get(LIMIT).getAsInt());
        assertEquals(9, written.get("gone:rule").getAsInt(), "a rule of a mod taken out for now stays put");
    }

    /** A player, as far as the rules need one. */
    private static final class Member implements RulesKeeper.Member {
        private final UUID id = UUID.randomUUID();
        private final String name;
        private final boolean operator;
        private RulesPayload told;

        Member(String name, boolean operator) {
            this.name = name;
            this.operator = operator;
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
            return false;
        }

        @Override
        public boolean tell(RulesPayload notice) {
            told = notice;
            return true;
        }
    }
}
