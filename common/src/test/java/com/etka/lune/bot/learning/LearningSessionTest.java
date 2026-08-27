package com.etka.lune.bot.learning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LearningSessionTest {

    @Test
    void keepsACompactRecentMemoryAndReward() {
        LearningSession session = LearningSession.start("complete-game");
        for (int i = 0; i < 40; i++) {
            session.remember("event-" + i);
        }
        session.taskOutcome("speedrun", 12.0);

        assertEquals(32, session.memories().size());
        assertTrue(session.summary().contains("reward=12.00"));
        assertTrue(session.summary().contains("event-39"));
    }

    @Test
    void fixedJobTimeIsTelemetryWhileRealTacticEarnsPoints() {
        LearningSession session = LearningSession.start("category");
        SkillOutcome outcome = SkillOutcome.evaluate(true, 1, 1, 20, 0.0);

        session.skillOutcome("crafting", "default", outcome, false);
        assertEquals(0.0, session.reward());
        assertTrue(session.memories().get(0).startsWith("metric crafting"));

        session.skillOutcome("movement", "quick-search", outcome, true);
        assertEquals(outcome.reward(), session.reward());
    }
}
