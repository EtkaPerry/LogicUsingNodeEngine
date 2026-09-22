package com.etka.lune.waypoint.external;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalWaypointSourcesTest {

    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    private static ExternalWaypoint at(String name, int x, int y, int z, String dimension) {
        return new ExternalWaypoint("test", name, x, y, z, dimension, true);
    }

    @Test
    void nearestFirstWithOtherDimensionsAfterEverythingHere() {
        List<ExternalWaypoint> sorted = ExternalWaypointSources.nearestFirst(List.of(
                at("far", 100, 64, 0, OVERWORLD),
                at("nether b", 5, 64, 5, NETHER),
                at("near", 3, 64, 0, OVERWORLD),
                at("nether a", 1, 64, 1, NETHER),
                at("middle", 40, 64, 0, OVERWORLD)),
                new Vec3(0, 64, 0), OVERWORLD);

        assertEquals(List.of("near", "middle", "far", "nether a", "nether b"),
                sorted.stream().map(ExternalWaypoint::name).toList());
    }

    @Test
    void aColumnWithoutAHeightIsMeasuredFlat() {
        ExternalWaypoint column = new ExternalWaypoint("test", "column", 3, 0, 4, OVERWORLD, false);
        // 3-4-5: the height difference of sixty-four blocks must not count.
        assertEquals(5.0, column.distanceFrom(new Vec3(0.5, 64, 0.5)), 1e-9);
    }

    @Test
    void uniqueNameNumbersACollisionAndSkipsTakenNumbers() {
        List<String> taken = List.of("Base", "base 2", "Mine");
        assertEquals("Base 3", ExternalWaypointSources.uniqueName("Base", taken));
        assertEquals("Farm", ExternalWaypointSources.uniqueName("Farm", taken));
    }

    @Test
    void uniqueNameKeepsTheNumberInsideTheLimit() {
        String longName = "A".repeat(ExternalWaypointSources.NAME_LIMIT + 10);
        String first = ExternalWaypointSources.uniqueName(longName, List.of());
        assertEquals(ExternalWaypointSources.NAME_LIMIT, first.length());

        String second = ExternalWaypointSources.uniqueName(longName, List.of(first));
        assertTrue(second.length() <= ExternalWaypointSources.NAME_LIMIT, second);
        assertTrue(second.endsWith(" 2"), second);
        assertFalse(second.equalsIgnoreCase(first));
    }

    @Test
    void fitNameCollapsesWhitespaceAndCuts() {
        assertEquals("Old mine", ExternalWaypointSources.fitName("  Old \t mine "));
        assertEquals("", ExternalWaypointSources.fitName(null));
        assertEquals(ExternalWaypointSources.NAME_LIMIT,
                ExternalWaypointSources.fitName("x".repeat(100)).length());
    }

    @Test
    void aBlankRequestGetsANameRatherThanNothing() {
        String chosen = ExternalWaypointSources.uniqueName("   ", List.of());
        assertFalse(chosen.isBlank());
    }
}
