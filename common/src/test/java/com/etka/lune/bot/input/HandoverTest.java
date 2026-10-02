package com.etka.lune.bot.input;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Who holds the controls in a run beside the player, decided one tick at a time. */
class HandoverTest {

    @Test
    void takingIsImmediateAndGivingBackWaitsOutTheGapBetweenCards() {
        Handover hands = new Handover();
        assertEquals(Handover.Change.NONE, hands.update(false), "nothing wanted, nothing to hand over");
        assertFalse(hands.lune());

        assertEquals(Handover.Change.TAKE, hands.update(true),
                "a card with work in sight has the keys on the tick it finds it");
        assertTrue(hands.lune());
        assertEquals(Handover.Change.NONE, hands.update(true), "and keeps them while it works");

        // The tick between one card finishing and the next one starting has no card at all.
        for (int tick = 1; tick < Handover.RELEASE_TICKS; tick++) {
            assertEquals(Handover.Change.NONE, hands.update(false));
            assertTrue(hands.lune(), "a gap of " + tick + " ticks is not the end of the work");
        }
        assertEquals(Handover.Change.GIVE, hands.update(false));
        assertFalse(hands.lune());
    }

    @Test
    void aCardAskingAgainInsideTheGapIsNotASecondHandover() {
        Handover hands = new Handover();
        hands.update(true);
        hands.update(false);
        assertEquals(Handover.Change.NONE, hands.update(true),
                "the keys never went back, so they are not taken again");
        for (int tick = 1; tick < Handover.RELEASE_TICKS; tick++) {
            hands.update(false);
        }
        assertTrue(hands.lune(), "the gap is counted from the last tick that wanted them");
        assertEquals(Handover.Change.GIVE, hands.update(false));
    }

    @Test
    void forgettingDropsTheKeysWithoutAHandover() {
        Handover hands = new Handover();
        hands.update(true);
        hands.forget();
        assertFalse(hands.lune());
        assertEquals(Handover.Change.NONE, hands.update(false),
                "a run that ended has nothing left to give back");
        assertEquals(Handover.Change.TAKE, hands.update(true), "the next one starts from the player");
    }
}
