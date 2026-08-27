package com.etka.lune.bot.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatPolicyTest {

    @Test
    void bowOnlyFightDoesNotPretendMeleeOpenersAreRealChoices() {
        assertEquals(1, CombatPolicy.actions(false).size());
    }

    @Test
    void meleeCanCompareGroundedAndCriticalOpeners() {
        assertEquals(2, CombatPolicy.actions(true).size());
        assertTrue(CombatPolicy.usesCritical(CombatPolicy.CRITICAL_OPENER));
        assertFalse(CombatPolicy.usesCritical(CombatPolicy.GROUNDED_OPENER));
    }
}
