package com.etka.lune.client.gui.mascot;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoulAnimationTest {

    @Test
    void everyMoodHasItsOwnRowOfTheAtlas() {
        Set<Integer> rows = new HashSet<>();
        for (MascotAdvisor.Mood mood : MascotAdvisor.Mood.values()) {
            int row = SoulAnimation.row(mood);
            assertTrue(row >= 0 && row < SoulAnimation.ROWS, mood + " row " + row);
            assertTrue(rows.add(row), mood + " shares row " + row);
        }
        assertEquals(SoulAnimation.ROWS, rows.size());
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
