package com.etka.lune.bot.learning;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LearningStoreTest {

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
}
