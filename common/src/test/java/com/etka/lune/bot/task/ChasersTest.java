package com.etka.lune.bot.task;

import com.etka.lune.bot.path.AStarPathfinder;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChasersTest {

    /** The berths Self Preservation gives them: a Zombie's reach and room, a Creeper's blast. */
    private static final double ZOMBIE = 5.0;
    private static final double CREEPER = 7.0;
    /** A Zombie's pace against a sprinting bot. */
    private static final double ZOMBIE_PACE = Chasers.pace(0.23, true);

    private static Chasers.Chaser at(double x, double z, double berth) {
        return new Chasers.Chaser(x, 64.0, z, berth, berth == CREEPER ? 3.5 : 2.5,
                berth == CREEPER ? Chasers.pace(0.25, true) : ZOMBIE_PACE);
    }

    @Test
    void awayFromOneIsStraightAwayFromIt() {
        double[] away = new Chasers(List.of(at(-3, 0, ZOMBIE))).away(0, 0);
        assertEquals(1.0, away[0], 1.0E-9);
        assertEquals(0.0, away[1], 1.0E-9);
    }

    @Test
    void theNearerOfTwoOnEitherSideDecides() {
        // A corridor along x: a Zombie five blocks east and another eight west.
        double[] away = new Chasers(List.of(at(5, 0, ZOMBIE), at(-8, 0, ZOMBIE))).away(0, 0);
        assertTrue(away[0] < -0.99, "west, away from the nearer: " + away[0]);
    }

    @Test
    void aCreeperPushesHarderThanAZombieAsNear() {
        // One north, one east, four blocks each: the way away leans further from the Creeper.
        double[] away = new Chasers(List.of(at(0, -4, CREEPER), at(4, 0, ZOMBIE))).away(0, 0);
        assertTrue(away[1] > -away[0], "more south than west: " + away[0] + ", " + away[1]);
    }

    @Test
    void twoAsFarOffOnEitherSideCancelOut() {
        assertNull(new Chasers(List.of(at(4, 0, ZOMBIE), at(-4, 0, ZOMBIE))).away(0, 0));
    }

    @Test
    void nobodyChasingHasNoAwayAndNoNearest() {
        Chasers none = new Chasers(List.of());
        assertNull(none.away(0, 0));
        assertEquals(Double.POSITIVE_INFINITY, none.nearest(0, 64, 0));
    }

    /** Vanilla's sums: a mob is pushed by its speed squared, a player by its speed. */
    @Test
    void aPaceIsAMobsWalkAgainstTheBotsRun() {
        // A Zombie walks 0.114 a tick and the bot sprints 0.281: two fifths, and a quarter more spare.
        assertEquals(1.25 * 0.1142 / 0.2806, Chasers.pace(0.23, true), 0.002);
        assertTrue(Chasers.pace(0.23, false) > Chasers.pace(0.23, true), "a walking bot is slower");
        // A baby Zombie, half as fast again, outwalks a sprinting bot once the margin is counted.
        assertTrue(Chasers.pace(0.345, true) > 1.0, "baby zombie: " + Chasers.pace(0.345, true));
    }

    /** The walk to strike a block, as the crow flies, standing in for a walking field. */
    private static double[] straight(Chasers chasers, double x, double z) {
        double[] walks = new double[chasers.all().size()];
        for (int i = 0; i < walks.length; i++) {
            Chasers.Chaser chaser = chasers.all().get(i);
            walks[i] = Math.max(0.0, Math.hypot(x - chaser.x(), z - chaser.z()) - chaser.strike());
        }
        return walks;
    }

    /** Whether a straight way from the origin to {@code (x, z)}, a block at a time, is cut off. */
    private static boolean cutOff(Chasers chasers, double x, double z) {
        int blocks = (int) Math.ceil(Math.hypot(x, z));
        double[] run = new double[blocks + 1];
        double[][] along = new double[blocks + 1][];
        for (int k = 0; k <= blocks; k++) {
            double share = (double) k / blocks;
            run[k] = Math.hypot(x, z) * share;
            along[k] = straight(chasers, x * share, z * share);
        }
        return chasers.cutOff(run, along);
    }

    @Test
    void aWayPastAChaserIsCutOff() {
        Chasers one = new Chasers(List.of(at(-8, 0, ZOMBIE)));
        assertTrue(cutOff(one, -14, 0), "the way there runs through it");
        assertFalse(cutOff(one, 14, 0), "straight away");
    }

    /** A chaser beside the bot is beside it whichever way it goes; the first steps are not held against a way. */
    @Test
    void aChaserBesideTheBotCutsOffOnlyTheWaysTowardIt() {
        Chasers beside = new Chasers(List.of(at(-1.5, 0, ZOMBIE)));
        assertFalse(cutOff(beside, 12, 0), "away from it");
        assertTrue(cutOff(beside, -12, 0), "past it");
    }

    /**
     * What the user showed on the maze (2026-10-18): a spot across a gap the chasers cannot walk is
     * worth more than one much further off that they can.
     */
    @Test
    void aSpotNoChaserCanWalkToBeatsAFurtherOneTheyCan() {
        Chasers one = new Chasers(List.of(at(-6, 0, ZOMBIE)));
        double across = one.lead(new double[] {Double.POSITIVE_INFINITY}, 4.0);
        double far = one.lead(new double[] {20.0}, 14.0);
        assertEquals(Chasers.OUT_OF_REACH - ZOMBIE_PACE * 4.0, across, 1.0E-9);
        assertTrue(across > far, across + " against " + far);
    }

    /** The sixty-six block route of 2026-10-18: the same spot is worth less the further the bot runs to it. */
    @Test
    void aLongWayRoundLeavesLessRoomThanAShortOne() {
        Chasers one = new Chasers(List.of(at(-6, 0, ZOMBIE)));
        assertTrue(one.lead(new double[] {20.0}, 14.0) > one.lead(new double[] {20.0}, 30.0));
        assertTrue(one.lead(new double[] {10.0}, 30.0) < 0.0, "the Zombie is there first");
    }

    /**
     * The dead end of 2026-10-18, with a way out to the north: a Zombie five blocks east and another
     * eight west. Each end of the corridor is a run into one of them; the side is the way out.
     */
    @Test
    void withAChaserAtEachEndOfACorridorTheWayOutIsToTheSide() {
        Chasers both = new Chasers(List.of(at(5, 0, ZOMBIE), at(-8, 0, ZOMBIE)));
        assertTrue(cutOff(both, 14, 0), "east runs into the nearer");
        assertTrue(cutOff(both, -14, 0), "west runs into the other");
        assertFalse(cutOff(both, 0, -12), "north gets clear of both");
    }

    /** A faster chaser cuts off a way a slow one does not. */
    @Test
    void aChaserAsFastAsTheBotCutsOffWhatAZombieDoesNot() {
        double[] run = {0.0, 4.0, 8.0, 12.0};
        double[][] along = {{7.0}, {7.0}, {7.0}, {7.0}};
        Chasers zombie = new Chasers(List.of(new Chasers.Chaser(0, 64, 0, ZOMBIE, 2.5, ZOMBIE_PACE)));
        Chasers quick = new Chasers(List.of(new Chasers.Chaser(0, 64, 0, ZOMBIE, 2.5, Chasers.pace(0.345, true))));
        assertFalse(zombie.cutOff(run, along));
        assertTrue(quick.cutOff(run, along));
    }

    /**
     * Pinned in a corridor with every way cut off (2026-10-18), the bot took none and was struck
     * where it stood. The way the chasers come nearest to striking last is the least bad.
     */
    @Test
    void ofWaysAllCutOffTheOneReachedLastIsTheLeastBad() {
        Chasers one = new Chasers(List.of(at(-8, 0, ZOMBIE)));
        double[] run = {0.0, 3.0, 6.0};
        double[][] intoIt = {{0.0}, {0.0}, {1.0}};
        double[][] pastIt = {{0.0}, {2.0}, {3.0}};
        assertTrue(one.cutOff(run, intoIt) && one.cutOff(run, pastIt), "both cut off");
        assertTrue(one.worstLead(run, pastIt) > one.worstLead(run, intoIt),
                one.worstLead(run, pastIt) + " against " + one.worstLead(run, intoIt));
        assertEquals(Double.POSITIVE_INFINITY, one.worstLead(new double[] {0.0, 2.0}, new double[][] {{0.0}, {0.0}}),
                "nothing past the first steps to judge");
    }

    /**
     * The bastion of 2026-10-18: a run pressed into a Zombie standing a step ahead on its way. One
     * behind it, off to the side, further off or on another level is not in the way.
     */
    @Test
    void aChaserAheadOnTheWayIsInIt() {
        assertTrue(new Chasers(List.of(at(1.5, 0, ZOMBIE))).inTheWay(0, 64, 0, 3, 0), "a step ahead");
        assertFalse(new Chasers(List.of(at(1.5, 0, ZOMBIE))).inTheWay(0, 64, 0, -3, 0), "behind");
        assertFalse(new Chasers(List.of(at(0, 2, ZOMBIE))).inTheWay(0, 64, 0, 3, 0), "off to the side");
        assertFalse(new Chasers(List.of(at(4, 0, ZOMBIE))).inTheWay(0, 64, 0, 6, 0), "past a step and an arm");
        Chasers below = new Chasers(List.of(new Chasers.Chaser(1.5, 61.0, 0, ZOMBIE, 2.5, ZOMBIE_PACE)));
        assertFalse(below.inTheWay(0, 64, 0, 3, 0), "on the floor below");
    }

    @Test
    void aBlockFarFromEveryChaserIsClearWithoutAField() {
        Chasers one = new Chasers(List.of(at(-8, 0, ZOMBIE)));
        assertTrue(one.clearByDistance(10, 0, 6.0), "eighteen blocks off, six run");
        assertFalse(one.clearByDistance(-4, 0, 6.0), "four blocks from it");
    }

    @Test
    void theRouteKeepsClearOfEveryChaserByItsBerth() {
        Chasers chasers = new Chasers(List.of(at(0.5, 0.5, ZOMBIE), at(20.5, 0.5, CREEPER)));
        AStarPathfinder.Avoid avoid = chasers.avoid(30.0, 0.2);
        assertEquals(2, avoid.zones().size());
        assertEquals(30.0, avoid.penalty(new BlockPos(0, 64, 0)), 1.0E-9, "on the Zombie");
        assertEquals(0.0, avoid.penalty(new BlockPos(6, 64, 0)), 1.0E-9, "past the Zombie's five blocks");
        assertTrue(avoid.penalty(new BlockPos(14, 64, 0)) > 0.0, "inside the Creeper's seven");
    }

    /** A Creeper's core goes with it into the ground a route keeps clear of, and costs a long way round. */
    @Test
    void aChasersCoreIsTheRoutesToo() {
        Chasers creeper = new Chasers(List.of(new Chasers.Chaser(0.5, 64.0, 0.5, CREEPER, 3.5, 0.6, 4.0)));
        AStarPathfinder.Avoid avoid = creeper.avoid(30.0, 0.2);
        assertEquals(4.0, avoid.zones().get(0).core(), 1.0E-9);
        assertTrue(avoid.penalty(new BlockPos(3, 64, 0)) >= AStarPathfinder.Avoid.CORE_COST, "three blocks off");
        assertTrue(avoid.penalty(new BlockPos(5, 64, 0)) < AStarPathfinder.Avoid.CORE_COST, "five blocks off");
    }

    /** Lava's edge and a gap over it cost a run a fifth, except where a hit from a chaser could knock it in. */
    @Test
    void aRunPaysLessForLavaAwayFromItsChasers() {
        AStarPathfinder.Avoid avoid = new Chasers(List.of(at(0.5, 0.5, ZOMBIE))).avoid(30.0, 0.2);
        assertEquals(40.0, avoid.hazard(new BlockPos(2, 64, 0), 40.0), 1.0E-9, "beside the Zombie");
        assertEquals(8.0, avoid.hazard(new BlockPos(9, 64, 0), 40.0), 1.0E-9, "away from it");
    }
}
