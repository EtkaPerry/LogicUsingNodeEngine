package com.etka.lune.bot.task;

import com.etka.lune.bot.knowledge.StrongholdKnowledge;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One eye, read the way the game flies it.
 *
 * <p>The flight below is vanilla's own: {@code EyeOfEnder.signalTo} and
 * {@code updateDeltaMovement} step for step, reported every fourth tick and rounded to the
 * 4096ths a move packet carries, which is all a client ever gets to see of an eye.</p>
 */
class EnderEyePolicyTest {

    /** Vanilla's eye, as the server flies it, sampled the way the client hears about it. */
    private static List<Vec3> fly(double x, double y, double z, double targetX, double targetZ) {
        double dx = targetX - x;
        double dz = targetZ - z;
        double horizontal = Math.hypot(dx, dz);
        double[] aim = horizontal > 12.0
                ? new double[]{x + dx / horizontal * 12.0, y + 8.0, z + dz / horizontal * 12.0}
                : new double[]{targetX, 0.0, targetZ};
        double mx = 0.0;
        double my = 0.0;
        double mz = 0.0;
        double px = x;
        double py = y;
        double pz = z;
        List<Vec3> heard = new ArrayList<>();
        for (int life = 1; life <= 81; life++) {
            double nx = px + mx;
            double ny = py + my;
            double nz = pz + mz;
            double hx = aim[0] - nx;
            double hz = aim[2] - nz;
            double length = Math.hypot(hx, hz);
            double speed = Math.hypot(mx, mz) + 0.0025 * (length - Math.hypot(mx, mz));
            double vertical = my;
            if (length < 1.0) {
                speed *= 0.8;
                vertical *= 0.8;
            }
            double wanted = ny - my < aim[1] ? 1.0 : -1.0;
            mx = hx * speed / length;
            my = vertical + (wanted - vertical) * 0.015;
            mz = hz * speed / length;
            px = nx;
            py = ny;
            pz = nz;
            if (life % 4 == 0) {
                heard.add(new Vec3(round(px), round(py), round(pz)));
            }
        }
        return heard;
    }

    private static double round(double coordinate) {
        return Math.round(coordinate * 4096.0) / 4096.0;
    }

    private static int corner(double coordinate) {
        return (int) Math.round(coordinate / 16.0) * 16;
    }

    @Test
    void oneFarFlightNamesTheCornerItWasAimedAt() {
        Random random = new Random(20260930L);
        int trials = 300;
        int unique = 0;
        int bestRight = 0;
        for (int trial = 0; trial < trials; trial++) {
            double x = random.nextDouble() * 200.0 - 100.0;
            double z = random.nextDouble() * 200.0 - 100.0;
            double y = 64.0 + random.nextDouble() * 30.0;
            double bearing = random.nextDouble() * Math.PI * 2.0;
            double radius = 1408.0 + random.nextDouble() * 1280.0;
            int targetX = corner(Math.cos(bearing) * radius);
            int targetZ = corner(Math.sin(bearing) * radius);

            EnderEyePolicy.Reading reading = EnderEyePolicy.read(x, y, z, fly(x, y, z, targetX, targetZ));
            assertEquals(EnderEyePolicy.Kind.FAR, reading.kind());
            List<EnderEyePolicy.Candidate> candidates = EnderEyePolicy.candidates(reading,
                    StrongholdKnowledge.nearestAtMost(x, z) + 16.0);
            assertTrue(candidates.stream().anyMatch(c -> c.x() == targetX && c.z() == targetZ),
                    "the corner the eye flew at has to be among the candidates, trial " + trial);
            if (candidates.size() == 1) {
                unique++;
            }
            EnderEyePolicy.Candidate best = EnderEyePolicy.bestGuess(candidates);
            if (best.x() == targetX && best.z() == targetZ) {
                bestRight++;
            }
        }
        // From near spawn one throw is enough most of the time, and the best guess nearly always.
        assertTrue(unique > trials * 0.8, "unique readings: " + unique + " of " + trials);
        assertTrue(bestRight > trials * 0.95, "right first guesses: " + bestRight + " of " + trials);
    }

    @Test
    void aStrongholdCloserThanTwelveIsWhereTheEyeDove() {
        EnderEyePolicy.Reading reading = EnderEyePolicy.read(9.3, 70.9, -7.3, fly(9.3, 70.9, -7.3, 16, -16));
        assertEquals(EnderEyePolicy.Kind.NEAR, reading.kind());
        assertEquals(16, reading.nearX());
        assertEquals(-16, reading.nearZ());
    }

    @Test
    void aCornerAlmostTwelveAwayIsToldApartByWhichWayTheEyeGoes() {
        // 11.95 blocks off: it settles barely short of twelve, so only the dive gives it away.
        double x = 16.0 - 11.95;
        EnderEyePolicy.Reading near = EnderEyePolicy.read(x, 70.9, 0.0, fly(x, 70.9, 0.0, 16, 0));
        assertEquals(EnderEyePolicy.Kind.NEAR, near.kind());
        assertEquals(16, near.nearX());
        assertEquals(0, near.nearZ());

        double past = 16.0 - 12.05;
        EnderEyePolicy.Reading far = EnderEyePolicy.read(past, 70.9, 0.0, fly(past, 70.9, 0.0, 16, 0));
        assertEquals(EnderEyePolicy.Kind.FAR, far.kind());
    }

    @Test
    void theBearingOfAFarFlightIsGoodToMillionthsOfARadian() {
        EnderEyePolicy.Reading reading = EnderEyePolicy.read(0.5, 70.9, 0.5, fly(0.5, 70.9, 0.5, 1504, 1200));
        double truth = Math.atan2(1200 - 0.5, 1504 - 0.5);
        double read = Math.atan2(reading.dirZ(), reading.dirX());
        assertTrue(Math.abs(truth - read) < 2.0E-5, "bearing off by " + Math.abs(truth - read));
        assertTrue(reading.angularError() < 2.0E-5, "claimed error " + reading.angularError());
    }

    @Test
    void aWatchCutShortIsNotReadAsAStop() {
        List<Vec3> path = fly(0.5, 70.9, 0.5, 2000, 0);
        EnderEyePolicy.Reading reading = EnderEyePolicy.read(0.5, 70.9, 0.5, path.subList(0, 6));
        assertEquals(EnderEyePolicy.Kind.UNREADABLE, reading.kind());
    }

    @Test
    void aFlightThatIsNotOneLineIsNotRead() {
        List<Vec3> path = new ArrayList<>();
        for (int step = 1; step <= 20; step++) {
            double t = step * 0.6;
            path.add(new Vec3(t, 71.0 + step * 0.3, Math.sin(t) * 2.0));
        }
        assertEquals(EnderEyePolicy.Kind.UNREADABLE, EnderEyePolicy.read(0.0, 70.9, 0.0, path).kind());
    }

    @Test
    void aFlightWithNothingOnTheRingsFitsNoCorner() {
        // Aimed at a corner no ring could hold: the reading is good, and nothing fits it.
        EnderEyePolicy.Reading reading = EnderEyePolicy.read(0.5, 70.9, 0.5, fly(0.5, 70.9, 0.5, 400, 32));
        assertEquals(EnderEyePolicy.Kind.FAR, reading.kind());
        List<EnderEyePolicy.Candidate> candidates = EnderEyePolicy.candidates(reading, 1000.0);
        assertTrue(candidates.isEmpty(), "candidates: " + candidates);
        double[] ahead = reading.pointAlong(100.0);
        assertNotNull(ahead);
        assertTrue(ahead[0] > 90.0, "walking on follows the line");
    }
}
