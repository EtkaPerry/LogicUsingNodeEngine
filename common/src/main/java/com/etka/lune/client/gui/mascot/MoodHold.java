package com.etka.lune.client.gui.mascot;

/**
 * Keeps one face of ordinary work on screen until the next has lasted long enough to be worth a
 * change.
 *
 * <p>A job steps between kinds of work every few ticks. Mine breaks a block, sweeps its drop,
 * checks whether it landed and looks at the next one; the lines in between wear no face of their
 * own, so taken tick by tick she would wipe from digging to working and back several times a
 * second, faster than a wipe can finish. A face of ordinary work therefore replaces another only
 * once it has been asked for {@link #HOLD_TICKS} ticks running.</p>
 *
 * <p>Only ordinary work is held. Trouble - danger, a blockage, a full bag, being paused - is on
 * screen the tick it happens, and so is the first face after it.</p>
 */
final class MoodHold {

    /** A quarter of a second: longer than the steps inside a job, shorter than a wipe is slow. */
    static final int HOLD_TICKS = 5;

    private MascotAdvisor.Mood pending;
    private int ticks;

    /** The face to show this tick, given the one on screen and the one the state asks for. */
    MascotAdvisor.Mood next(MascotAdvisor.Mood shown, MascotAdvisor.Mood wanted) {
        if (wanted == shown || shown == null
                || !MascotAdvisor.ordinaryWork(shown) || !MascotAdvisor.ordinaryWork(wanted)) {
            pending = null;
            ticks = 0;
            return wanted;
        }
        if (wanted != pending) {
            pending = wanted;
            ticks = 0;
        }
        if (++ticks < HOLD_TICKS) {
            return shown;
        }
        pending = null;
        ticks = 0;
        return wanted;
    }

    void reset() {
        pending = null;
        ticks = 0;
    }
}
