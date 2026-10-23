package com.etka.lune.bot.path;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where the head goes on a walk: one node ahead, unless the waypoint is behind (PathExecutor.behind). */
class PathExecutorLookTest {

    /**
     * The bastion of 2026-10-18: sprinted a block past a step down whose route then turns north, the
     * bot backed onto it with the eyes on the north and slid into the hole beside it.
     */
    @Test
    void aWaypointCarriedPastIsLookedAt() {
        assertTrue(PathExecutor.behind(9.4, 18.4, new Vec3(8.5, 82.5, 19.5), new Vec3(8.5, 82.5, 10.5)));
    }

    @Test
    void aWaypointAheadIsLookedPast() {
        assertFalse(PathExecutor.behind(0.5, 0.5, new Vec3(0.5, 64.5, 2.5), new Vec3(0.5, 64.5, 3.5)), "straight on");
        assertFalse(PathExecutor.behind(0.5, 0.5, new Vec3(0.5, 64.5, 2.5), new Vec3(2.5, 64.5, 2.5)),
                "a corner ahead leads the turn");
    }

    @Test
    void aRouteThatTurnsBackIsFollowedRound() {
        assertFalse(PathExecutor.behind(0.5, 0.5, new Vec3(0.5, 64.5, -1.5), new Vec3(0.5, 64.5, -3.5)));
    }

    /** A side-step onto a waypoint just missed is a side-step, not a turn round. */
    @Test
    void aWaypointUnderfootIsNotBehind() {
        assertFalse(PathExecutor.behind(0.9, 0.9, new Vec3(0.5, 64.5, 0.5), new Vec3(0.5, 64.5, 5.5)));
    }
}
