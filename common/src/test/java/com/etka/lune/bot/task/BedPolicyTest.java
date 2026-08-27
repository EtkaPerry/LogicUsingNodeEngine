package com.etka.lune.bot.task;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedPolicyTest {

    @Test
    void threeSheepInViewIsWorthLeavingTheRouteFor() {
        assertTrue(BedPolicy.worthDivertingForSheep(3, 0, false));
        assertFalse(BedPolicy.worthDivertingForSheep(2, 0, false));
    }

    @Test
    void carriedWoolLowersHowManySheepAreWorthStoppingFor() {
        assertTrue(BedPolicy.worthDivertingForSheep(2, 1, false));
        assertTrue(BedPolicy.worthDivertingForSheep(1, 2, false));
    }

    @Test
    void neverDivertsWhenTheBedIsAlreadyMadeOrTheWoolIsAlreadyCut() {
        assertFalse(BedPolicy.worthDivertingForSheep(8, 0, true));
        assertFalse(BedPolicy.worthDivertingForSheep(8, 3, false));
    }

    @Test
    void threeMatchingWoolNeedsNoDyeAtAll() {
        BedPolicy.Plan plan = BedPolicy.plan(Map.of("white", 3), Map.of("yellow", 9));

        assertEquals(BedPolicy.Step.READY, plan.step());
        assertEquals("white", plan.colour());
        assertEquals(0, plan.woolToDye());
    }

    @Test
    void asksForMoreSheepBeforeItAsksForFlowers() {
        BedPolicy.Plan plan = BedPolicy.plan(Map.of("white", 2), Map.of("yellow", 9));

        assertEquals(BedPolicy.Step.NEED_WOOL, plan.step());
        assertEquals(1, plan.woolStillNeeded());
    }

    @Test
    void dyesTheOddOneOutWhenTheMajorityColourIsGrowingNearby() {
        // Two white and a brown, with lily of the valley in sight: one dye, not three.
        BedPolicy.Plan plan = BedPolicy.plan(Map.of("white", 2, "brown", 1), Map.of("white", 2));

        assertEquals(BedPolicy.Step.DYE, plan.step());
        assertEquals("white", plan.colour());
        assertEquals(1, plan.woolToDye());
    }

    @Test
    void dyesTheWholeFlockWhenOnlyAnUnrelatedFlowerIsInReach() {
        // Two white and a brown with only dandelions: white dye cannot be made, so everything
        // becomes yellow rather than the flock being abandoned.
        BedPolicy.Plan plan = BedPolicy.plan(Map.of("white", 2, "brown", 1), Map.of("yellow", 4));

        assertEquals(BedPolicy.Step.DYE, plan.step());
        assertEquals("yellow", plan.colour());
        assertEquals(3, plan.woolToDye());
    }

    @Test
    void willNotPlanAColourItCannotMakeEnoughDyeFor() {
        // One dandelion is one yellow dye, and three mismatched wool need three.
        BedPolicy.Plan plan = BedPolicy.plan(Map.of("white", 1, "brown", 1, "black", 1),
                Map.of("yellow", 1));

        assertEquals(BedPolicy.Step.NO_MATCHING_DYE, plan.step());
    }

    @Test
    void prefersTheColourThatCostsTheFewestDyes() {
        BedPolicy.Plan plan = BedPolicy.plan(Map.of("white", 2, "brown", 1),
                Map.of("white", 3, "yellow", 3));

        assertEquals("white", plan.colour());
        assertEquals(1, plan.woolToDye());
    }

    @Test
    void countsTallFlowersAsTwoDyes() {
        assertEquals(3, BedPolicy.flowersNeeded(3, 1));
        assertEquals(2, BedPolicy.flowersNeeded(3, 2));
        assertEquals(0, BedPolicy.flowersNeeded(0, 2));
    }

    @Test
    void surplusWoolOfOneColourStillCountsAsReady() {
        assertEquals(BedPolicy.Step.READY, BedPolicy.plan(Map.of("white", 9), Map.of()).step());
    }

    @Test
    void noFlowersAndNoMatchIsAnHonestFailureRatherThanAWildGuess() {
        assertEquals(BedPolicy.Step.NO_MATCHING_DYE,
                BedPolicy.plan(Map.of("white", 1, "brown", 1, "pink", 1), Map.of()).step());
    }
}
