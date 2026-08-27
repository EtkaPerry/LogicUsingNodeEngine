package com.etka.lune.bot.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoodSearchPolicyTest {

    @Test
    void rejectsMountainClimbsFromFoodProspecting() {
        assertTrue(FoodSearchPolicy.staysOnSurfaceBand(70, 78));
        assertFalse(FoodSearchPolicy.staysOnSurfaceBand(70, 79));
    }

    @Test
    void prefersLevelClearingOverAHighDistantOne() {
        assertTrue(FoodSearchPolicy.surfaceTargetScore(70, 72, 40)
                < FoodSearchPolicy.surfaceTargetScore(70, 78, 20));
    }

    @Test
    void overworldChecksSettlementsBeforeAnimalFallback() {
        assertFalse(FoodSearchPolicy.shouldStartWithAnimalFallback(false));
        assertTrue(FoodSearchPolicy.shouldStartWithAnimalFallback(true));
    }

    @Test
    void overworldFoodNeverRoamsAwayFromTheCurrentRoute() {
        assertFalse(FoodSearchPolicy.allowSourceRoam(true));
        assertFalse(FoodSearchPolicy.allowSourceRoam(false));
        assertEquals(3, FoodSearchPolicy.maxSourceRoams(true));
        assertEquals(8, FoodSearchPolicy.maxSourceRoams(false));
        assertEquals(24, FoodSearchPolicy.sourceRoamMinDistance(true));
        assertEquals(48, FoodSearchPolicy.sourceRoamMaxDistance(true));
    }

    @Test
    void everyThirdSourceSweepLooksForAshoreline() {
        assertFalse(FoodSearchPolicy.shouldProspectShoreline(1));
        assertFalse(FoodSearchPolicy.shouldProspectShoreline(2));
        assertTrue(FoodSearchPolicy.shouldProspectShoreline(3));
        assertFalse(FoodSearchPolicy.shouldProspectShoreline(4));
        assertTrue(FoodSearchPolicy.shouldProspectShoreline(6));
    }

    @Test
    void shipwreckSearchNeedsAVisibleClueAndIsNotRepeatedInTheSameArea() {
        assertTrue(FoodSearchPolicy.shouldProbeVisibleShipwreck(true, false));
        assertFalse(FoodSearchPolicy.shouldProbeVisibleShipwreck(false, false));
        assertFalse(FoodSearchPolicy.shouldProbeVisibleShipwreck(true, true));
    }

    @Test
    void onlyNetherUsesTheMobFallback() {
        assertFalse(FoodSearchPolicy.shouldStartWithAnimalFallback(false));
        assertTrue(FoodSearchPolicy.shouldStartWithAnimalFallback(true));
    }

    @Test
    void overworldFoodSearchPreparesAnUndergroundStart() {
        assertTrue(FoodSearchPolicy.shouldPrepareSurface(true, false));
        assertFalse(FoodSearchPolicy.shouldPrepareSurface(true, true));
        assertFalse(FoodSearchPolicy.shouldPrepareSurface(false, false));
    }

    @Test
    void lowHungerShortensAnInProgressNormalOverworldSearch() {
        assertTrue(FoodSearchPolicy.shouldShortenSourceSearch(false, true, 12, 12));
        assertFalse(FoodSearchPolicy.shouldShortenSourceSearch(true, true, 12, 12));
        assertFalse(FoodSearchPolicy.shouldShortenSourceSearch(false, false, 12, 12));
        assertFalse(FoodSearchPolicy.shouldShortenSourceSearch(false, true, 13, 12));
    }

    @Test
    void takesFoodOnlyWhenTheCurrentViewOffersIt() {
        assertTrue(FoodSearchPolicy.shouldTakeLocalOpportunity(true, false));
        assertFalse(FoodSearchPolicy.shouldTakeLocalOpportunity(false, false));
        assertFalse(FoodSearchPolicy.shouldTakeLocalOpportunity(true, true));
    }

    @Test
    void recognizesAFlatPartlySubmergedHullWithoutConfusingAHouseForOne() {
        assertTrue(FoodSearchPolicy.looksLikeVisibleShipwreck(4, 2, 2, 1, 0));
        assertFalse(FoodSearchPolicy.looksLikeVisibleShipwreck(4, 2, 0, 1, 0));
        assertFalse(FoodSearchPolicy.looksLikeVisibleShipwreck(4, 2, 2, 0, 0));
        assertFalse(FoodSearchPolicy.looksLikeVisibleShipwreck(4, 2, 2, 1, 1));
        assertFalse(FoodSearchPolicy.looksLikeVisibleShipwreck(2, 1, 2, 1, 0));
    }
}
