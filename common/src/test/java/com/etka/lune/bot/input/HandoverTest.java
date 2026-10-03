package com.etka.lune.bot.input;

import com.etka.lune.task.BesideOptions;
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

    @Test
    void withLuneFirstATouchIsUndoneRatherThanObeyed() {
        Handover hands = new Handover();
        hands.start(new BesideOptions(false, true, true));
        hands.update(true);
        assertFalse(hands.observe(true), "her card is not done, so the touch hands nothing back");
        assertFalse(hands.yielding(), "and nobody waits for anybody");
        assertTrue(hands.lune());
        assertEquals(Handover.Change.NONE, hands.update(true));
    }

    @Test
    void withThePlayerFirstATouchHandsEverythingBackAtOnce() {
        Handover hands = new Handover();
        hands.start(new BesideOptions(true, true, true));
        assertEquals(Handover.Change.TAKE, hands.update(true),
                "hands that have been off the controls since the run began are no reason to wait");

        assertTrue(hands.observe(true), "a touch while she holds them is the player taking them back");
        assertTrue(hands.yielding());
    }

    @Test
    void withThePlayerFirstNothingIsTakenUntilTheirHandsHaveBeenOffForAMoment() {
        Handover hands = new Handover();
        hands.start(new BesideOptions(true, true, true));
        assertFalse(hands.observe(true), "she held nothing, so there was nothing to give back");
        assertTrue(hands.yielding());
        for (int tick = 1; tick < Handover.QUIET_TICKS; tick++) {
            hands.observe(false);
            assertEquals(Handover.Change.NONE, hands.update(true),
                    tick + " ticks after a touch is too soon to take the controls");
        }
        hands.observe(false);
        assertFalse(hands.yielding(), "a second with the hands off is an opening");
        assertEquals(Handover.Change.TAKE, hands.update(true));

        hands.observe(true);
        hands.observe(false);
        assertTrue(hands.yielding(), "any touch starts the wait over");
    }

    @Test
    void aCardStillWantingTheControlsAfterATouchWaitsForThePlayer() {
        Handover hands = new Handover();
        hands.start(new BesideOptions(true, true, true));
        hands.update(true);
        hands.observe(true);
        // The engine hands back on the touch itself, without the release gap a quiet card gets.
        hands.forget();
        assertFalse(hands.lune());
        assertEquals(Handover.Change.NONE, hands.update(true), "wanted, but the player comes first");
    }

    @Test
    void aRunTakesOnlyWhatItsTaskAllows() {
        Handover hands = new Handover();
        hands.start(new BesideOptions(false, true, false));
        assertTrue(hands.takesMouse());
        assertFalse(hands.takesKeyboard());

        hands.start(new BesideOptions(false, false, true));
        assertFalse(hands.takesMouse());
        assertTrue(hands.takesKeyboard());

        hands.start(null);
        assertTrue(hands.takesMouse(), "a task with no choice saved ran with everything, and still does");
        assertTrue(hands.takesKeyboard());
        assertFalse(hands.playerFirst());
    }

    @Test
    void startingAgainForgetsTheLastRunsHandsAndItsWait() {
        Handover hands = new Handover();
        hands.start(new BesideOptions(true, true, true));
        hands.update(true);
        hands.observe(true);
        hands.start(new BesideOptions(true, true, true));
        assertFalse(hands.lune());
        assertFalse(hands.yielding(), "a fresh run does not inherit the last one's wait");
    }
}
