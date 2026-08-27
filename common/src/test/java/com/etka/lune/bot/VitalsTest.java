package com.etka.lune.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VitalsTest {

    @Test
    void reportsTheStateThatExplainsADeath() {
        assertEquals("hp=6.50/20.00;food=3;sat=0.00;air=140/300;armor=5;dmg=mob (Zombie)",
                Vitals.describe(6.5F, 20.0F, 3, 0.0F, 140, 300, 5, "mob (Zombie)"));
    }

    @Test
    void saysSoWhenNothingHasHurtThePlayerYet() {
        assertTrue(Vitals.describe(20.0F, 20.0F, 20, 5.0F, 300, 300, 0, "").endsWith(";dmg=-"));
        assertTrue(Vitals.describe(20.0F, 20.0F, 20, 5.0F, 300, 300, 0, null).endsWith(";dmg=-"));
    }

    @Test
    void clampsAirThatWentNegativeWhileDrowning() {
        assertTrue(Vitals.describe(1.0F, 20.0F, 0, 0.0F, -20, 300, 0, "drown").contains("air=0/300"));
    }

    @Test
    void signsWholeHeartsSoRegenerationDoesNotFillTheJournal() {
        assertEquals(Vitals.signature(11.2F, 20, 300, 300), Vitals.signature(11.4F, 20, 300, 300));
        assertNotEquals(Vitals.signature(11.2F, 20, 300, 300), Vitals.signature(13.0F, 20, 300, 300));
    }

    @Test
    void signsAirInTenthsSoOneDiveIsNotThreeHundredLines() {
        // A second and a half of held breath is one line, not thirty.
        assertEquals(Vitals.signature(20.0F, 20, 299, 300), Vitals.signature(20.0F, 20, 271, 300));
        assertNotEquals(Vitals.signature(20.0F, 20, 299, 300), Vitals.signature(20.0F, 20, 150, 300));
    }

    @Test
    void marksGoingUnderwaterImmediately() {
        // A full bar is its own bucket, so "started losing air" is always the first line of a dive.
        assertNotEquals(Vitals.signature(20.0F, 20, 300, 300), Vitals.signature(20.0F, 20, 299, 300));
    }

    @Test
    void survivesAnEntityWithNoBreathAtAll() {
        assertEquals("20|20|0", Vitals.signature(20.0F, 20, 0, 0));
    }

    @Test
    void separatesHungerFromHealthSoAStarvingRunIsVisible() {
        assertNotEquals(Vitals.signature(20.0F, 20, 300, 300), Vitals.signature(20.0F, 6, 300, 300));
    }
}
