package com.etka.lune.bot.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapTargetPolicyTest {

    /**
     * The game's own forward transform, written out so the inverse can be checked against it rather
     * than against my reading of it. A marker index is the offset from the centre in blocks, divided
     * by the blocks-per-pixel for the scale, doubled and rounded to a byte.
     */
    private static int markerFor(int centre, int scale, int world) {
        float pixels = (float) (world - centre) / MapTargetPolicy.blocksPerPixel(scale);
        return (byte) (int) (pixels * 2.0F + 0.5F);
    }

    @Test
    void aMarkerAtTheCentreIsTheCentre() {
        assertEquals(1000, MapTargetPolicy.worldCoordinate(1000, 0, 0));
        assertEquals(-64, MapTargetPolicy.worldCoordinate(-64, 3, 0));
    }

    @Test
    void scaleDecidesHowManyBlocksAMarkerStepIsWorth() {
        // One marker step is half a pixel, so it is worth half the blocks-per-pixel.
        assertEquals(1, MapTargetPolicy.worldCoordinate(0, 0, 2) - MapTargetPolicy.worldCoordinate(0, 0, 0));
        assertEquals(8, MapTargetPolicy.worldCoordinate(0, 3, 2) - MapTargetPolicy.worldCoordinate(0, 3, 0));
        assertEquals(16, MapTargetPolicy.worldCoordinate(0, 4, 2) - MapTargetPolicy.worldCoordinate(0, 4, 0));
    }

    /**
     * Every marker decodes to the block nearest the position it actually denotes.
     * <p>
     * A marker is half-pixel resolution, so on a scale-0 map two markers share a block and an exact
     * marker round trip is impossible - the information is not there. What must hold is that the
     * block we walk to is the nearest one to where the marker really points.
     */
    @Test
    void everyMarkerDecodesToTheNearestBlockToWhereItPoints() {
        for (int scale = 0; scale <= MapTargetPolicy.MAX_SCALE; scale++) {
            for (int centre : new int[] {0, 1024, -2048, 30_000_000}) {
                for (int marker = -127; marker <= 126; marker++) {
                    double exact = centre + marker * MapTargetPolicy.blocksPerPixel(scale) / 2.0;
                    int decoded = MapTargetPolicy.worldCoordinate(centre, scale, marker);
                    assertTrue(Math.abs(decoded - exact) <= 0.5,
                            "scale " + scale + " centre " + centre + " marker " + marker
                                    + ": points at " + exact + " but decoded to " + decoded);
                }
            }
        }
    }

    /** And the other direction, for anything genuinely drawn on the picture. */
    @Test
    void aPlaceOnTheMapSurvivesTheRoundTrip() {
        for (int scale = 0; scale <= MapTargetPolicy.MAX_SCALE; scale++) {
            int centre = -2048;
            int reach = MapTargetPolicy.coverage(scale) / 2 - MapTargetPolicy.blocksPerPixel(scale);
            int slack = MapTargetPolicy.accuracy(scale);
            for (int world = centre - reach; world <= centre + reach;
                    world += Math.max(1, reach / 20)) {
                int marker = markerFor(centre, scale, world);
                if (!MapTargetPolicy.isOnTheMap(marker)) {
                    continue; // pinned to the border; the map genuinely does not know where it is
                }
                int decoded = MapTargetPolicy.worldCoordinate(centre, scale, marker);
                assertTrue(Math.abs(decoded - world) <= slack,
                        "scale " + scale + ": " + world + " encoded to " + marker
                                + " and decoded to " + decoded + ", off by more than " + slack);
            }
        }
    }

    @Test
    void aClampedMarkerIsRecognisedAsPointingOffTheMap() {
        assertFalse(MapTargetPolicy.isOnTheMap(-128), "pinned to the low edge");
        assertFalse(MapTargetPolicy.isOnTheMap(127), "pinned to the high edge");
        assertTrue(MapTargetPolicy.isOnTheMap(0));
        assertTrue(MapTargetPolicy.isOnTheMap(-127));
        assertTrue(MapTargetPolicy.isOnTheMap(126));
    }

    @Test
    void accuracyIsHonestAboutWhatAMarkerCanSay() {
        assertEquals(1, MapTargetPolicy.accuracy(0), "a level-0 map is good to a block");
        assertEquals(8, MapTargetPolicy.accuracy(3));
        assertEquals(16, MapTargetPolicy.accuracy(4), "the widest map is only good to sixteen blocks");
    }

    /**
     * The number {@link MapTargetPolicy#accuracy} promises has to cover the worst the game's own
     * rounding can do, or the job walks confidently to the wrong square. Swept exhaustively rather
     * than argued from the formula, because the formula is what was wrong the first time.
     */
    @Test
    void accuracyCoversTheWorstTheGamesRoundingProduces() {
        for (int scale = 0; scale <= MapTargetPolicy.MAX_SCALE; scale++) {
            int centre = -2048;
            int reach = MapTargetPolicy.coverage(scale) / 2 - MapTargetPolicy.blocksPerPixel(scale);
            int worst = 0;
            for (int world = centre - reach; world <= centre + reach; world++) {
                int marker = markerFor(centre, scale, world);
                if (!MapTargetPolicy.isOnTheMap(marker)) {
                    continue;
                }
                worst = Math.max(worst,
                        Math.abs(MapTargetPolicy.worldCoordinate(centre, scale, marker) - world));
            }
            assertTrue(worst <= MapTargetPolicy.accuracy(scale),
                    "scale " + scale + ": worst real error was " + worst
                            + " but accuracy() promises " + MapTargetPolicy.accuracy(scale));
        }
    }

    @Test
    void coverageMatchesTheAreaAMapDraws() {
        assertEquals(128, MapTargetPolicy.coverage(0));
        assertEquals(1024, MapTargetPolicy.coverage(3));
        assertEquals(2048, MapTargetPolicy.coverage(4));
    }

    @Test
    void anAbsurdScaleIsClampedRatherThanOverflowing() {
        assertEquals(MapTargetPolicy.blocksPerPixel(MapTargetPolicy.MAX_SCALE),
                MapTargetPolicy.blocksPerPixel(99));
        assertEquals(1, MapTargetPolicy.blocksPerPixel(-3));
    }
}
