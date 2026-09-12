package com.etka.lune.bot.task;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelfPreservationPolicyTest {

    @Test
    void meleeCanLearnRetreatCoverPillarOrCounterattack() {
        List<String> actions = SelfPreservationPolicy.monsterActions(
                false, false, false, true, true);
        assertTrue(actions.contains(SelfPreservationPolicy.RETREAT_FIRST));
        assertTrue(actions.contains(SelfPreservationPolicy.COVER_FIRST));
        assertTrue(actions.contains(SelfPreservationPolicy.PILLAR_FIRST));
        assertTrue(actions.contains(SelfPreservationPolicy.COUNTERATTACK_FIRST));
    }

    @Test
    void unsafeMonsterStrategiesAreNotOffered() {
        List<String> creeper = SelfPreservationPolicy.monsterActions(
                true, false, false, true, true);
        assertFalse(creeper.contains(SelfPreservationPolicy.PILLAR_FIRST));
        assertFalse(creeper.contains(SelfPreservationPolicy.COUNTERATTACK_FIRST));

        assertEquals(List.of(SelfPreservationPolicy.SHELTER_FIRST),
                SelfPreservationPolicy.monsterActions(false, true, false, true, true));
    }

    @Test
    void impossibleBoatIsExcludedWhenACushionCanSaveTheFall() {
        List<String> actions = SelfPreservationPolicy.fallActions(false, true, true, false);
        assertEquals(List.of(SelfPreservationPolicy.CUSHION_CLUTCH), actions);
    }

    @Test
    void aFireballCanBeBattedBackOrSteppedOutOf() {
        assertEquals(List.of(SelfPreservationPolicy.DEFLECT_FIRST, SelfPreservationPolicy.DODGE_FIRST),
                SelfPreservationPolicy.fireballActions(true, true));
        // Batting is offered first, because it is the one that ends the Ghast rather than the shot.
        assertEquals(SelfPreservationPolicy.DEFLECT_FIRST,
                SelfPreservationPolicy.fireballActions(true, true).get(0));
    }

    @Test
    void walledInWithNothingToSwingWithStillLeavesSomethingToDo() {
        // No hand that the attack packet survives and nowhere safe to step. The episode must still
        // get a vocabulary: an empty action list would leave the learner nothing to rank.
        assertEquals(List.of(SelfPreservationPolicy.DIRECT),
                SelfPreservationPolicy.fireballActions(false, false));
        assertEquals(List.of(SelfPreservationPolicy.DODGE_FIRST),
                SelfPreservationPolicy.fireballActions(false, true));
    }
}
