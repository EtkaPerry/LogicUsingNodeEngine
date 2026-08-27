package com.etka.lune.bot.learning;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileBudgetTest {

    private static String state(String job, String phase) {
        return "skill|" + job + "|minecraft:overworld|" + phase;
    }

    @Test
    void takesRoomFromTheJobUsingTheMostRows() {
        Map<String, Integer> table = new LinkedHashMap<>();
        table.put(state("movement", "near"), 4000);
        table.put(state("movement", "medium"), 900);
        table.put(state("movement", "far"), 120);
        // One row, and far less evidence than any walking route - exactly the job a plain
        // least-visited rule would delete first.
        table.put(state("bridging", "gap=wide"), 3);

        assertTrue(ProfileBudget.victim(table, visits -> visits).startsWith("skill|movement|"));
    }

    @Test
    void withinTheCrowdedJobTheThinnestRowGoesFirst() {
        Map<String, Integer> table = new LinkedHashMap<>();
        table.put(state("movement", "near"), 4000);
        table.put(state("movement", "far"), 12);
        table.put(state("bridging", "gap=wide"), 3);

        assertEquals(state("movement", "far"), ProfileBudget.victim(table, visits -> visits));
    }

    @Test
    void anEmptyTableHasNothingToForget() {
        assertNull(ProfileBudget.victim(new LinkedHashMap<String, Integer>(), visits -> visits));
    }

    @Test
    void readsTheJobOutOfAContextKey() {
        assertEquals("movement", ProfileBudget.job(
                LearningContext.of("skill", "movement", "minecraft:overworld").key()));
        assertEquals("unknown", ProfileBudget.job(null));
        assertEquals("bare", ProfileBudget.job("bare"));
    }
}
