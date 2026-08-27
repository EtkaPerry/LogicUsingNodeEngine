package com.etka.lune.bot.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeedrunIronPolicyTest {

    @Test
    void portalIronIsReadyAtTheMinimalFourIngotBudget() {
        assertFalse(SpeedrunIronPolicy.suppliesReady(3, 4));
        assertTrue(SpeedrunIronPolicy.suppliesReady(4, 4));
    }

    @Test
    void toolBootstrapOnlyRunsWhenIronOreCannotBeHarvested() {
        assertTrue(SpeedrunIronPolicy.needsMiningTool(false));
        assertFalse(SpeedrunIronPolicy.needsMiningTool(true));
    }

    @Test
    void anEmptyMineSearchMustScoutBeforeItIsRebuilt() {
        assertTrue(SpeedrunIronPolicy.shouldScoutAfterMine(false, false));
        assertFalse(SpeedrunIronPolicy.shouldScoutAfterMine(true, false));
        assertFalse(SpeedrunIronPolicy.shouldScoutAfterMine(false, true));
    }

    @Test
    void afreshRunPaysForBothPortalTools() {
        assertEquals(4, SpeedrunIronPolicy.ironNeeded(false, false, false));
    }

    @Test
    void aLootedFlintAndSteelRemovesItsOwnCost() {
        assertEquals(3, SpeedrunIronPolicy.ironNeeded(false, true, false));
    }

    @Test
    void aLootedBucketRemovesItsOwnCost() {
        assertEquals(1, SpeedrunIronPolicy.ironNeeded(true, false, false));
    }

    @Test
    void carryingBothToolsNeedsNoIron() {
        assertEquals(0, SpeedrunIronPolicy.ironNeeded(true, true, false));
    }

    @Test
    void theObsidianRouteSkipsTheIronPhaseEntirely() {
        assertEquals(0, SpeedrunIronPolicy.ironNeeded(false, false, true));
    }

    @Test
    void anObsidianFrameNeedsItsCornersAndSomethingToLightIt() {
        assertEquals(14, SpeedrunIronPolicy.PORTAL_OBSIDIAN);
        assertTrue(SpeedrunIronPolicy.obsidianRouteReady(14, true));
        assertFalse(SpeedrunIronPolicy.obsidianRouteReady(13, true));
        assertFalse(SpeedrunIronPolicy.obsidianRouteReady(64, false));
    }

    @Test
    void anEmptyPocketHasADeepButBoundedProspectingBudget() {
        assertEquals(6, SpeedrunIronPolicy.PROSPECT_STAIR_STEPS);
        assertEquals(8, SpeedrunIronPolicy.PROSPECT_MAX_ATTEMPTS);
        assertEquals(48, SpeedrunIronPolicy.prospectStepBudget());
    }
}
