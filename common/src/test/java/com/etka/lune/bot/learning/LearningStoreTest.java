package com.etka.lune.bot.learning;

import com.etka.lune.util.Lang;
import com.etka.lune.util.LuneLanguages;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LearningStoreTest {

    @AfterEach
    void restoreLanguage() {
        Lang.select(LuneLanguages.GAME_DEFAULT);
    }

    @Test
    void persistsOutcomesAndUserFeedback(@TempDir Path temp) {
        Path file = temp.resolve("lune-learning.json");
        LearningContext context = LearningContext.of("mission", "task", "overworld");

        LearningStore first = new LearningStore(file, new Random(0));
        LearningStore.SkillChoice compact = first.chooseSkill(context,
                List.of("compact", "conservative"), "compact");
        assertEquals("compact", compact.action());
        first.recordSkillOutcome(compact, false, 0, 1, 10);
        first.recordUserFeedback(context, "conservative", true);
        LearningSession session = LearningSession.start("mission");
        session.taskOutcome("task", -10.0);
        first.finishSession(session, "failed", false);

        // Writes are coalesced now, so persistence is what flush() promises.
        first.flush();

        LearningStore reloaded = new LearningStore(file, new Random(0));
        assertEquals("conservative", reloaded.chooseSkill(context,
                List.of("compact", "conservative"), "compact").action());
        assertEquals(1, reloaded.sessionCount());
        assertTrue(reloaded.summary().contains("feedback=1"));
    }

    @Test
    void automaticVerdictLearnsMissionTimingAndPersistsIt(@TempDir Path temp) {
        Path file = temp.resolve("lune-learning.json");
        LearningContext context = LearningContext.of("mission", "task", "overworld");
        LearningStore first = new LearningStore(file, new Random(0));

        AutomaticApproval.Decision baseline = first.recordMissionOutcome(context, true, 100);
        assertTrue(baseline.approved());
        assertEquals(0, baseline.usualTicks());

        AutomaticApproval.Decision slower = first.recordMissionOutcome(context, true, 120);
        assertFalse(slower.approved());
        assertEquals(100, slower.usualTicks());

        AutomaticApproval.Decision faster = first.recordMissionOutcome(context, true, 80);
        assertTrue(faster.approved());
        assertEquals(80, first.bestMissionTicks(context));

        AutomaticApproval.Decision failed = first.recordMissionOutcome(context, false, 1);
        assertFalse(failed.approved());

        // Writes are coalesced now, so persistence is what flush() promises.
        first.flush();

        LearningStore reloaded = new LearningStore(file, new Random(0));
        AutomaticApproval.Decision persisted = reloaded.recordMissionOutcome(context, true, 100);
        assertTrue(persisted.approved());
        assertEquals(100, persisted.usualTicks());
        assertTrue(reloaded.summary().contains("auto=3/2"));
        assertEquals(0, reloaded.updateCount(), "mission timing must not reward a route action");
    }

    @Test
    void skillOutcomeRewardsRateAndSwitchesARejectedLiveTactic(@TempDir Path temp) {
        Path file = temp.resolve("lune-learning.json");
        LearningContext context = new LearningContext("skill", "tree-chopping", "overworld",
                "size=small;tool=hand;approach=near");
        LearningStore store = new LearningStore(file, new Random(0));

        LearningStore.SkillChoice first = store.chooseSkill(context,
                List.of("trunk-first", "nearest-cut", "outer-first"), "trunk-first");
        SkillOutcome baseline = store.recordSkillOutcome(first, true, 5, 5, 100);
        assertEquals(10.0, baseline.reward());
        assertTrue(baseline.bestTime());

        LearningStore.SkillChoice fasterChoice = store.chooseSkill(context,
                List.of("trunk-first", "nearest-cut", "outer-first"), "trunk-first");
        SkillOutcome faster = store.recordSkillOutcome(fasterChoice, true, 5, 5, 50);
        assertTrue(faster.reward() > baseline.reward());
        assertTrue(faster.bestTime());
        assertEquals(10.0, store.bestSkillTicksPerUnit(context));

        LearningStore.SkillChoice rejected = store.chooseSkill(context,
                List.of("trunk-first", "nearest-cut", "outer-first"), "trunk-first");
        String oldAction = rejected.action();
        store.recordUserFeedback(rejected.context(), oldAction, false);
        String replacement = store.switchRejectedSkill(rejected);
        assertFalse(oldAction.equals(replacement));
        assertEquals(replacement, rejected.action());
        assertTrue(rejected.active());

        // Writes are coalesced now, so persistence is what flush() promises.
        store.flush();

        LearningStore reloaded = new LearningStore(file, new Random(0));
        assertTrue(reloaded.summary().contains("skills=2"));
    }

    /**
     * What a card shows is the rows its job writes, pooled, and "last time" is a thing the
     * profile now remembers per row - it did not, and a card that could only say "average" and
     * "best" had no way to tell a player whether the run they just watched was any good.
     */
    @Test
    void skillStatsPoolTheRowsACardOwnsAndRememberLastTime(@TempDir Path temp) {
        Path file = temp.resolve("lune-learning.json");
        LearningStore store = new LearningStore(file, new Random(0));
        List<String> tactics = List.of("trunk-first", "nearest-cut", "outer-first");
        LearningContext hand = new LearningContext("skill", "tree-chopping", "minecraft:overworld",
                "size=small;tool=hand;approach=walk");
        LearningContext axe = new LearningContext("skill", "tree-chopping", "minecraft:overworld",
                "size=small;tool=axe;approach=near");
        LearningContext stone = new LearningContext("skill", "block-mining", "minecraft:overworld",
                "targets=minecraft:stone;amount=few;radius=local;prospect=true");

        store.recordSkillOutcome(store.chooseSkill(hand, tactics, "trunk-first"), true, 5, 5, 100);
        store.recordSkillOutcome(store.chooseSkill(hand, tactics, "trunk-first"), true, 6, 6, 60);
        // A failure that produced nothing counts as a run and changes none of the paces.
        store.recordSkillOutcome(store.chooseSkill(hand, tactics, "trunk-first"), false, 0, 6, 40);
        store.recordSkillOutcome(store.chooseSkill(axe, tactics, "trunk-first"), true, 8, 8, 40);
        store.recordSkillOutcome(store.chooseSkill(stone, List.of("default"), "default"), true, 4, 4, 80);
        // A second tactic with evidence behind it is what makes "preferred" a comparison.
        store.recordUserFeedback(hand, "nearest-cut", true);

        SkillStats chopping = store.skillStats(LearningScope.of("tree-chopping", "lune.unit.logs"));
        assertEquals(4, chopping.runs());
        assertEquals(3, chopping.completedRuns());
        assertEquals(19, chopping.totalUnits());
        assertEquals(200, chopping.totalTicks());
        assertEquals(5.0, chopping.bestTicksPerUnit(), "the axe tree, at five ticks a log");
        assertEquals(8, chopping.lastUnits(), "the axe tree was the last to finish");
        assertEquals(40, chopping.lastTicks());
        assertEquals(2, chopping.rows().size());
        SkillStats.Row byHand = chopping.rows().get(0);
        SkillStats.Row byAxe = chopping.rows().get(1);
        assertEquals("size=small;tool=hand;approach=walk", byHand.context().phase(), "busiest first");
        assertTrue(tactics.contains(byHand.leadingTactic()), byHand.leadingTactic());
        assertEquals("", byAxe.leadingTactic(),
                "one tactic tried once is not a preference over the others");

        SkillStats mining = store.skillStats(LearningScope.of("block-mining", "lune.unit.blocks",
                "targets=minecraft:stone", "prospect=true"));
        assertEquals(1, mining.runs());
        assertEquals("", mining.rows().get(0).leadingTactic(), "a fixed job has no tactic to prefer");
        assertSame(SkillStats.EMPTY, store.skillStats(LearningScope.of("block-mining", null,
                "targets=minecraft:iron_ore")), "iron was never mined");
        assertSame(SkillStats.EMPTY, store.skillStats(null));

        // Last time survives the file the same as every other measurement does.
        store.flush();
        SkillStats reloaded = new LearningStore(file, new Random(0))
                .skillStats(LearningScope.of("tree-chopping"));
        assertEquals(8, reloaded.lastUnits());
        assertEquals(40, reloaded.lastTicks());
        assertEquals(chopping.bestTicksPerUnit(), reloaded.bestTicksPerUnit());
    }

    @Test
    void anEpisodeTooShortToTimeIsNotRecorded(@TempDir Path temp) {
        LearningStore store = new LearningStore(temp.resolve("lune-learning.json"), new Random(0));
        LearningContext context = LearningContext.of("skill", "item-collection", "overworld");

        LearningStore.SkillChoice instant = store.chooseSkill(context,
                List.of("local-then-worksite", "nearest-next-target"), "local-then-worksite");
        SkillOutcome dropped = store.recordSkillOutcome(instant, true, 1, 1, 1);

        assertEquals(0, store.updateCount(), "one tick is not a measurement");
        assertEquals(0.0, store.bestSkillTicksPerUnit(context),
                "a one-tick episode must not become a best time nothing can beat");
        // The caller scores the run from what it gets back, so the refusal has to reach it too.
        assertFalse(dropped.scored());
        assertEquals(0.0, dropped.reward());

        LearningStore.SkillChoice real = store.chooseSkill(context,
                List.of("local-then-worksite", "nearest-next-target"), "local-then-worksite");
        store.recordSkillOutcome(real, true, 1, 1, 40);
        assertEquals(1, store.updateCount());
    }

    /**
     * The bot standing under a tree it never chops. Every tick it reopened a movement episode that
     * was already on its goal, closed it a tick later, and was paid full completion for it: one
     * recorded run reached a run reward of 9,210 with no logs mined and the policy - correctly -
     * never updating once.
     */
    @Test
    void aLoopOfInstantEpisodesEarnsTheRunNothing(@TempDir Path temp) {
        LearningStore store = new LearningStore(temp.resolve("lune-learning.json"), new Random(0));
        LearningContext context = LearningContext.of("skill", "movement", "overworld");
        LearningSession session = LearningSession.start("woodland-cleanup");

        for (int tick = 0; tick < 500; tick++) {
            LearningStore.SkillChoice choice = store.chooseSkill(context,
                    List.of("balanced-search", "quick-search", "thorough-search"),
                    "balanced-search");
            SkillOutcome outcome = store.recordSkillOutcome(choice, true, 1, 1, 1);
            session.skillOutcome("movement", choice.action(), outcome,
                    outcome.scored() && choice.actions().size() > 1);
        }

        assertEquals(0.0, session.reward(), "a stuck run must not out-earn a working one");
        assertEquals(0, store.updateCount());
    }

    /**
     * The file a release build plays from. A merge that wrote the wrong schema would be discarded
     * silently at load time, and the shipped bot would start from nothing while every report said
     * the profile was full.
     */
    @Test
    void theBundledProfileIsReadableByTheVersionThatShipsIt() throws Exception {
        try (java.io.InputStream stream =
                     LearningStore.class.getResourceAsStream("/lune-learning.json")) {
            assertTrue(stream != null, "the bundled profile must be on the classpath");
            LearningStore.Data bundled = new com.google.gson.Gson().fromJson(
                    new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8),
                    LearningStore.Data.class);
            assertTrue(bundled != null, "the bundled profile must parse");
            assertEquals(2, bundled.schemaVersion);
            assertTrue(bundled.qValues != null && bundled.skillPerformance != null);
            assertTrue(bundled.recentSessions == null || bundled.recentSessions.isEmpty(),
                    "a release must not ship the developer's session diary");
        }
    }

    @Test
    void fixedMetricJobDoesNotHideLastChangeableTacticFromFeedback(@TempDir Path temp) {
        LearningStore store = new LearningStore(temp.resolve("lune-learning.json"), new Random(0));
        LearningContext movement = LearningContext.of("skill", "movement", "overworld");
        LearningContext crafting = LearningContext.of("skill", "crafting", "overworld");

        LearningStore.SkillChoice tactic = store.chooseSkill(movement,
                List.of("balanced-search", "quick-search"), "balanced-search");
        store.recordSkillOutcome(tactic, true, 1, 1, 20);
        LearningStore.SkillChoice fixed = store.chooseSkill(crafting, List.of("default"), "default");
        store.activateSkill(fixed);

        assertEquals(tactic, store.feedbackSkillChoice());
        assertEquals(fixed, store.lastSkillChoice());
        int tacticUpdates = store.updateCount();
        store.recordSkillOutcome(fixed, true, 1, 1, 10);
        assertEquals(tacticUpdates, store.updateCount(),
                "a fixed job records time without awarding a fake default action");
    }

    /**
     * A profile written while units were words. English rows already carry the identifiers and
     * are left alone; a row written under the Turkish word is renamed to the same identifier and,
     * where the English row exists, folded into it the way merge-learning.mjs folds snapshots:
     * counts add, the best pace is the lower one, and last time is the newer one.
     */
    @Test
    void rowsKeyedOnAWordedUnitAreReadAsTheIdentifierRow(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("lune-learning.json");
        Files.writeString(file, """
                {
                  "schemaVersion": 2,
                  "lastContext": "skill|Tunnel|minecraft:overworld|size=few;unit=blok",
                  "qValues": {
                    "skill|Tunnel|minecraft:overworld|size=few;unit=blok": {"default": {"value": 2.0, "visits": 1}},
                    "skill|Tunnel|minecraft:overworld|size=few;unit=blocks": {"default": {"value": 4.0, "visits": 3}}
                  },
                  "preferences": {
                    "skill|Tunnel|minecraft:overworld|size=few;unit=blok::default": {"positive": 1, "negative": 0, "lastFeedback": "positive"}
                  },
                  "skillPerformance": {
                    "skill|Tunnel|minecraft:overworld|size=few;unit=blocks": {"runs": 2, "completedRuns": 2, "totalUnits": 8, "totalTicks": 80, "bestTicksPerUnit": 9.0, "lastUnits": 4, "lastTicks": 40, "lastOutcome": 2},
                    "skill|Tunnel|minecraft:overworld|size=few;unit=blok": {"runs": 3, "completedRuns": 1, "totalUnits": 4, "totalTicks": 20, "bestTicksPerUnit": 5.0, "lastUnits": 4, "lastTicks": 20, "lastOutcome": 5},
                    "skill|Bridge|minecraft:overworld|size=large;unit=blok": {"runs": 1, "completedRuns": 1, "totalUnits": 40, "totalTicks": 400, "bestTicksPerUnit": 10.0, "lastUnits": 40, "lastTicks": 400, "lastOutcome": 1},
                    "skill|Hunt Sheep|minecraft:overworld|size=batch;unit=wool": {"runs": 1, "completedRuns": 1, "totalUnits": 2, "totalTicks": 20, "bestTicksPerUnit": 10.0}
                  }
                }
                """, StandardCharsets.UTF_8);

        // Headless there is no resource manager to list the shipped languages, so the store knows
        // the words of English and of the pinned language only; in the game it reads every file.
        Lang.select("tr_tr");
        LearningStore store = new LearningStore(file, new Random(0));

        SkillStats tunnel = store.skillStats(LearningScope.of("Tunnel", "lune.unit.blocks"));
        assertEquals(1, tunnel.rows().size(), "one row, whichever language wrote it");
        SkillStats.Row row = tunnel.rows().get(0);
        assertEquals("size=few;unit=blocks", row.context().phase());
        assertEquals(5, row.runs());
        assertEquals(3, row.completedRuns());
        assertEquals(12, row.totalUnits());
        assertEquals(100, row.totalTicks());
        assertEquals(5.0, row.bestTicksPerUnit());
        assertEquals(20, row.lastTicks(), "the Turkish row was the newer measurement");

        assertEquals("size=large;unit=blocks",
                store.skillStats(LearningScope.of("Bridge", "lune.unit.blocks")).rows().get(0).context().phase());
        // A word Lune has no unit for - a hunt used to name its drop - is not guessed at.
        assertEquals("size=batch;unit=wool",
                store.skillStats(LearningScope.of("Hunt Sheep", "lune.unit.items")).rows().get(0).context().phase());

        // Reading it under the old keys is enough for it to be written back under the new ones.
        store.flush();
        JsonObject written = JsonParser.parseString(
                Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("skill|Tunnel|minecraft:overworld|size=few;unit=blocks",
                written.get("lastContext").getAsString());
        assertEquals(Set.of("skill|Tunnel|minecraft:overworld|size=few;unit=blocks",
                        "skill|Bridge|minecraft:overworld|size=large;unit=blocks",
                        "skill|Hunt Sheep|minecraft:overworld|size=batch;unit=wool"),
                written.getAsJsonObject("skillPerformance").keySet());
        assertEquals(Set.of("skill|Tunnel|minecraft:overworld|size=few;unit=blocks::default"),
                written.getAsJsonObject("preferences").keySet());
        // The policy cell is the visit-weighted mean of the two: (4 * 3 + 2 * 1) / 4.
        JsonObject values = written.getAsJsonObject("qValues");
        assertEquals(Set.of("skill|Tunnel|minecraft:overworld|size=few;unit=blocks"), values.keySet());
        JsonObject cell = values.getAsJsonObject("skill|Tunnel|minecraft:overworld|size=few;unit=blocks")
                .getAsJsonObject("default");
        assertEquals(3.5, cell.get("value").getAsDouble());
        assertEquals(4, cell.get("visits").getAsInt());
    }

    /** A profile that needs no renaming is not rewritten just for having been read. */
    @Test
    void aProfileAlreadyKeyedOnIdentifiersIsNotRewrittenOnRead(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("lune-learning.json");
        String original = """
                {"schemaVersion": 2, "skillPerformance": {
                  "skill|Tunnel|minecraft:overworld|size=few;unit=blocks": {"runs": 1, "completedRuns": 1, "totalUnits": 4, "totalTicks": 40, "bestTicksPerUnit": 10.0}
                }}
                """;
        Files.writeString(file, original, StandardCharsets.UTF_8);
        Lang.select("tr_tr");

        new LearningStore(file, new Random(0)).flush();

        assertEquals(original, Files.readString(file, StandardCharsets.UTF_8));
    }

    /** The rename touches the unit entry alone, and only where the word is one of a unit. */
    @Test
    void onlyTheUnitEntryIsRenamedAndOnlyForAKnownWord() {
        Map<String, String> words = Map.of("blok", "blocks", "blocks", "blocks");
        assertEquals("skill|Tunnel|minecraft:overworld|size=few;unit=blocks",
                LearningStore.renamedKey("skill|Tunnel|minecraft:overworld|size=few;unit=blok", words));
        assertEquals("skill|Tunnel|minecraft:overworld|size=few;unit=blocks",
                LearningStore.renamedKey("skill|Tunnel|minecraft:overworld|size=few;unit=blocks", words));
        assertEquals("skill|Hunt Sheep|minecraft:overworld|size=batch;unit=wool",
                LearningStore.renamedKey("skill|Hunt Sheep|minecraft:overworld|size=batch;unit=wool", words));
        assertEquals("skill|tree-chopping|minecraft:overworld|size=small;tool=hand;approach=walk",
                LearningStore.renamedKey("skill|tree-chopping|minecraft:overworld|size=small;tool=hand;approach=walk", words));
        assertEquals("not a key", LearningStore.renamedKey("not a key", words));
        assertEquals(null, LearningStore.renamedKey(null, words));
    }
}
