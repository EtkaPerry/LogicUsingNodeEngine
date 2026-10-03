package com.etka.lune.bot;

import com.etka.lune.bot.input.Controls;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rules every card that waits on sight follows beside the player. */
class BesideTest {

    private static final String WATCHING = "lune.status.mine.watching_beside";

    @Test
    void inThePlayersPlaceACardIsNeverOnlyWatching() {
        Beside beside = new Beside();
        StatusText status = new StatusText();
        beside.tick();
        assertEquals(TaskStatus.RUNNING, beside.watch(status, WATCHING));
        assertFalse(beside.watching(), "a run in the player's place holds the keys throughout");
        assertTrue(beside.holdsControls());
    }

    @Test
    void besideThePlayerATickIsWorkUntilItSaysItIsOnlyWatching() {
        Beside beside = new Beside();
        beside.enable();
        StatusText status = new StatusText();

        beside.tick();
        assertTrue(beside.holdsControls(), "a tick that never said it was waiting was work");

        assertEquals(TaskStatus.RUNNING, beside.watch(status, WATCHING),
                "nothing in sight is a wait, never a finish");
        assertTrue(beside.watching());
        assertFalse(beside.holdsControls());
        assertEquals(WATCHING, status.key(), "and the card says what it is waiting for");

        beside.tick();
        assertTrue(beside.holdsControls(), "every tick starts as work again");
    }

    @Test
    void withThePlayerFirstAndTheirHandsOnTheControlsACardLetsGo() {
        Beside beside = new Beside();
        assertFalse(beside.yielding(context(true, true, true)),
                "in the player's place nobody's hands come first");
        beside.enable();
        assertTrue(beside.yielding(context(true, true, true)));
        assertFalse(beside.yielding(context(true, true, false)), "hands off, the card works");
    }

    @Test
    void aCardTakesOnlyWorkTheDevicesItMayTakeCanFinish() {
        Beside inPlace = new Beside();
        assertTrue(inPlace.mayWalk(context(false, true, false)), "in the player's place, everything");
        assertTrue(inPlace.mayUseHands(context(true, false, false)));

        Beside beside = new Beside();
        beside.enable();
        BotContext mouseOnly = context(true, false, false);
        assertTrue(beside.mayUseHands(mouseOnly));
        assertFalse(beside.mayWalk(mouseOnly), "without the keyboard there is no walking to it");
        BotContext keyboardOnly = context(false, true, false);
        assertTrue(beside.mayWalk(keyboardOnly));
        assertFalse(beside.mayUseHands(keyboardOnly), "without the mouse there is no swing");
    }

    @Test
    void aCardWhoseWholeJobIsTheHandSaysWhyItCannot() {
        StatusText status = new StatusText();
        assertNull(Beside.handsOnly(context(true, true, false), status), "the mouse is hers");

        assertEquals(TaskStatus.FAILED, Beside.handsOnly(context(false, true, false), status),
                "a task that never lets her take the mouse would wait for ever");
        assertEquals("lune.status.beside.needs_mouse", status.key());

        assertEquals(TaskStatus.RUNNING, Beside.handsOnly(context(true, true, true), status),
                "a player whose hands are on it is waited for");
        assertEquals("lune.status.beside.waiting_for_hands", status.key());
    }

    /** What a card asks of its context, without a game behind it. */
    private static BotContext context(boolean mouse, boolean keyboard, boolean yielding) {
        Controls controls = new Controls() {
            @Override
            public boolean mayTakeMouse() {
                return mouse;
            }

            @Override
            public boolean mayTakeKeyboard() {
                return keyboard;
            }

            @Override
            public boolean yielding() {
                return yielding;
            }
        };
        return new BotContext(null, null, null, null, controls, null, null, null, null, null, null,
                null, null);
    }
}
