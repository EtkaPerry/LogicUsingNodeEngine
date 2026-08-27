package com.etka.lune.bot.learning;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TabularPolicyTest {

    @Test
    void usesTheSafeDefaultBeforeItHasEvidence() {
        TabularPolicy policy = new TabularPolicy(new LinkedHashMap<>(), new Random(0));

        assertEquals("compact", policy.choose("food", List.of("compact", "conservative"), "compact"));
    }

    @Test
    void failedActionMakesTheAlternativeEligibleWithoutInventingOne() {
        TabularPolicy policy = new TabularPolicy(new LinkedHashMap<>(), new Random(0));

        policy.observe("food", "compact", -10.0, null, List.of(), true);

        assertEquals("conservative", policy.choose("food",
                List.of("compact", "conservative"), "compact"));
    }

    @Test
    void balancedSamplingTriesEveryVariantBeforeRepeatingOne() {
        TabularPolicy policy = new TabularPolicy(new LinkedHashMap<>(), new Random(0),
                TabularPolicy.Exploration.BALANCED);
        List<String> actions = List.of("compact", "conservative", "greedy");

        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < actions.size(); i++) {
            String chosen = policy.choose("food", actions, "compact");
            seen.add(chosen);
            policy.observe("food", chosen, 1.0, null, List.of(), true);
        }

        assertEquals(Set.copyOf(actions), seen);
    }

    @Test
    void balancedSamplingKeepsGoingBackToTheThinnestVariant() {
        TabularPolicy policy = new TabularPolicy(new LinkedHashMap<>(), new Random(0),
                TabularPolicy.Exploration.BALANCED);
        List<String> actions = List.of("compact", "conservative");
        // A tactic that already looks terrible still has to be sampled; a shipped profile that
        // only ever measured the winner cannot prove it was the winner.
        policy.observe("food", "compact", 9.0, null, List.of(), true);
        policy.observe("food", "compact", 9.0, null, List.of(), true);

        assertEquals("conservative", policy.choose("food", actions, "compact"));
    }

    @Test
    void explorationDefaultsToShipBehaviour() {
        assertEquals(TabularPolicy.Exploration.OFF,
                new TabularPolicy(new LinkedHashMap<>(), new Random(0)).exploration());
    }

    @Test
    void policyTableIsBounded() {
        TabularPolicy policy = new TabularPolicy(new LinkedHashMap<>(), new Random(0));
        for (int i = 0; i < 700; i++) {
            policy.observe("state-" + i, "default", 1.0, null, List.of(), true);
        }

        assertTrue(policy.stateCount() <= TabularPolicy.MAX_STATES);
        assertTrue(policy.updateCount() <= TabularPolicy.MAX_STATES);
    }
}
