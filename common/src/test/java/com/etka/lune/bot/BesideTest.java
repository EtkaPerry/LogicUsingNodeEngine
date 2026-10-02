package com.etka.lune.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The two rules every card that waits on sight follows beside the player. */
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
}
