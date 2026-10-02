package com.etka.lune.client.gui.mascot;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoulAnimationTest {

    @Test
    void everyRowOfTheAtlasBelongsToExactlyOneMood() {
        Set<Integer> rows = new HashSet<>();
        for (MascotAdvisor.Mood mood : MascotAdvisor.Mood.values()) {
            int[] forms = SoulAnimation.rows(mood);
            assertTrue(forms.length >= 1, mood + " has no row");
            assertEquals(SoulAnimation.row(mood), forms[0], mood + " starts from another row");
            for (int row : forms) {
                assertTrue(row >= 0 && row < SoulAnimation.ROWS, mood + " row " + row);
                assertTrue(rows.add(row), mood + " shares row " + row);
            }
        }
        assertEquals(SoulAnimation.ROWS, rows.size(), "a row of the atlas no mood ever shows");
    }

    @Test
    void aJobWithSeveralFormsKeepsTheOneItStartedWith() {
        SoulAnimation soul = new SoulAnimation(new Random(7L));
        int shown = soul.layers(MascotAdvisor.Mood.FARMING, 0L).get(0).row();
        assertTrue(contains(SoulAnimation.rows(MascotAdvisor.Mood.FARMING), shown));
        for (long now = 100L; now < 5_000L; now += 100L) {
            List<SoulAnimation.Layer> layers = soul.layers(MascotAdvisor.Mood.FARMING, now);
            assertEquals(1, layers.size(), "no wipe while the mood lasts");
            assertEquals(shown, layers.get(0).row(), "the form changed while she was farming");
        }
    }

    @Test
    void comingBackToAJobCanBringAnotherOfItsForms() {
        SoulAnimation soul = new SoulAnimation(new Random(11L));
        Set<Integer> seen = new HashSet<>();
        long now = 0L;
        for (int visit = 0; visit < 40; visit++) {
            List<SoulAnimation.Layer> layers = soul.layers(MascotAdvisor.Mood.CRAFTING, now);
            seen.add(layers.get(layers.size() - 1).row());
            now += 1_000L;
            soul.layers(MascotAdvisor.Mood.TRAVEL, now);
            now += 1_000L;
        }
        assertEquals(SoulAnimation.rows(MascotAdvisor.Mood.CRAFTING).length, seen.size(),
                "forty visits never showed every form of crafting: " + seen);
    }

    @Test
    void theJobFacesAreOrdinaryWorkAndNotTrouble() {
        for (MascotAdvisor.Mood mood : List.of(MascotAdvisor.Mood.DIGGING,
                MascotAdvisor.Mood.FARMING, MascotAdvisor.Mood.CRAFTING,
                MascotAdvisor.Mood.SMELTING, MascotAdvisor.Mood.COLLECTING)) {
            assertTrue(MascotAdvisor.ordinaryWork(mood), mood + " would stop her suggestions");
            for (int row : SoulAnimation.rows(mood)) {
                assertTrue(SoulAnimation.frameMillis(row) >= 200,
                        mood + " row " + row + " is paced like an alarm");
            }
        }
    }

    @Test
    void whatHappensToHerBodyIsNotTroubleEither() {
        // A geyser throwing her up, or gliding on elytra, is shown like swimming: it replaces the
        // job's face while it lasts and does not stop her suggestions.
        assertTrue(MascotAdvisor.ordinaryWork(MascotAdvisor.Mood.GEYSER));
        assertTrue(MascotAdvisor.ordinaryWork(MascotAdvisor.Mood.GLIDING));
        assertTrue(SoulAnimation.pool(SoulAnimation.ROW_GEYSER));
        assertFalse(SoulAnimation.pool(SoulAnimation.ROW_GLIDING));
        assertTrue(SoulAnimation.frameMillis(SoulAnimation.ROW_GLIDING)
                * SoulAnimation.FRAMES * 3 > 4_000, "the rings pass too fast for calm flight");
    }

    private static boolean contains(int[] rows, int row) {
        for (int candidate : rows) {
            if (candidate == row) {
                return true;
            }
        }
        return false;
    }

    @Test
    void firstFormAppearsWithoutAWipe() {
        SoulAnimation soul = new SoulAnimation();
        List<SoulAnimation.Layer> layers = soul.layers(MascotAdvisor.Mood.IDLE, 1_000L);
        assertEquals(1, layers.size());
        assertEquals(SoulAnimation.ROW_IDLE, layers.get(0).row());
        assertFalse(layers.get(0).clipped());
    }

    @Test
    void poolDrainsFromTheTopToRevealAShape() {
        SoulAnimation soul = new SoulAnimation();
        soul.layers(MascotAdvisor.Mood.IDLE, 0L);

        List<SoulAnimation.Layer> start = soul.layers(MascotAdvisor.Mood.BLOCKED, 0L);
        assertEquals(2, start.size());
        assertEquals(SoulAnimation.ROW_IDLE, start.get(0).row());
        assertEquals(SoulAnimation.ROW_BLOCKED, start.get(1).row());

        List<SoulAnimation.Layer> halfway =
                soul.layers(MascotAdvisor.Mood.BLOCKED, SoulAnimation.WIPE_MILLIS / 2);
        SoulAnimation.Layer old = halfway.get(0);
        SoulAnimation.Layer fresh = halfway.get(1);
        assertEquals(0.5f, old.from(), 0.01f);
        assertEquals(1f, old.to());
        assertEquals(0f, fresh.from());
        assertEquals(0.5f, fresh.to(), 0.01f);

        List<SoulAnimation.Layer> done =
                soul.layers(MascotAdvisor.Mood.BLOCKED, SoulAnimation.WIPE_MILLIS);
        assertEquals(1, done.size());
        assertEquals(SoulAnimation.ROW_BLOCKED, done.get(0).row());
        assertFalse(done.get(0).clipped());
    }

    @Test
    void poolRisesFromTheBottomToSwallowAShape() {
        SoulAnimation soul = new SoulAnimation();
        soul.layers(MascotAdvisor.Mood.BLOCKED, 0L);
        soul.layers(MascotAdvisor.Mood.WORKING, 1_000L);

        List<SoulAnimation.Layer> halfway =
                soul.layers(MascotAdvisor.Mood.WORKING, 1_000L + SoulAnimation.WIPE_MILLIS / 2);
        SoulAnimation.Layer old = halfway.get(0);
        SoulAnimation.Layer fresh = halfway.get(1);
        assertEquals(SoulAnimation.ROW_BLOCKED, old.row());
        assertEquals(0f, old.from());
        assertEquals(0.5f, old.to(), 0.01f);
        assertEquals(SoulAnimation.ROW_WORKING, fresh.row());
        assertEquals(0.5f, fresh.from(), 0.01f);
        assertEquals(1f, fresh.to());
    }

    @Test
    void poolSinksToALowerPoolInsteadOfBeingEatenFromBelow() {
        SoulAnimation soul = new SoulAnimation();
        soul.layers(MascotAdvisor.Mood.WORKING, 0L);
        soul.layers(MascotAdvisor.Mood.PAUSED, 1_000L);

        List<SoulAnimation.Layer> halfway =
                soul.layers(MascotAdvisor.Mood.PAUSED, 1_000L + SoulAnimation.WIPE_MILLIS / 2);
        SoulAnimation.Layer old = halfway.get(0);
        SoulAnimation.Layer fresh = halfway.get(1);
        assertEquals(SoulAnimation.ROW_WORKING, old.row());
        assertEquals(0.5f, old.from(), 0.01f);
        assertEquals(1f, old.to());
        assertEquals(SoulAnimation.ROW_PAUSED, fresh.row());
        assertEquals(0f, fresh.from());
        assertEquals(0.5f, fresh.to(), 0.01f);
    }

    @Test
    void poolClimbsToAHigherPool() {
        SoulAnimation soul = new SoulAnimation();
        soul.layers(MascotAdvisor.Mood.PAUSED, 0L);
        soul.layers(MascotAdvisor.Mood.WORKING, 1_000L);

        List<SoulAnimation.Layer> halfway =
                soul.layers(MascotAdvisor.Mood.WORKING, 1_000L + SoulAnimation.WIPE_MILLIS / 2);
        SoulAnimation.Layer old = halfway.get(0);
        SoulAnimation.Layer fresh = halfway.get(1);
        assertEquals(SoulAnimation.ROW_PAUSED, old.row());
        assertEquals(0f, old.from());
        assertEquals(0.5f, old.to(), 0.01f);
        assertEquals(SoulAnimation.ROW_WORKING, fresh.row());
        assertEquals(0.5f, fresh.from(), 0.01f);
        assertEquals(1f, fresh.to());
    }

    @Test
    void everyPoolHasASurfaceInsideTheCell() {
        for (int row = 0; row < SoulAnimation.ROWS; row++) {
            if (SoulAnimation.pool(row)) {
                int surface = SoulAnimation.surface(row);
                assertTrue(surface > 0 && surface < 96, "row " + row + " surface " + surface);
            }
        }
        assertTrue(SoulAnimation.surface(SoulAnimation.ROW_PAUSED)
                > SoulAnimation.surface(SoulAnimation.ROW_WORKING));
    }

    @Test
    void aMoodChangeMidWipeStartsFromTheFormOnScreen() {
        SoulAnimation soul = new SoulAnimation();
        soul.layers(MascotAdvisor.Mood.IDLE, 0L);
        soul.layers(MascotAdvisor.Mood.PAUSED, 0L);
        List<SoulAnimation.Layer> layers = soul.layers(MascotAdvisor.Mood.DANGER, 100L);
        assertEquals(SoulAnimation.ROW_PAUSED, layers.get(0).row());
        assertEquals(SoulAnimation.ROW_DANGER, layers.get(1).row());
    }

    @Test
    void framesAdvanceAtEachRowsOwnPace() {
        assertEquals(0, SoulAnimation.frame(SoulAnimation.ROW_IDLE, 0L));
        assertEquals(1, SoulAnimation.frame(SoulAnimation.ROW_IDLE, 720L));
        assertEquals(0, SoulAnimation.frame(SoulAnimation.ROW_IDLE, 720L * SoulAnimation.FRAMES));
        assertTrue(SoulAnimation.frameMillis(SoulAnimation.ROW_DANGER)
                < SoulAnimation.frameMillis(SoulAnimation.ROW_IDLE));
    }
}
