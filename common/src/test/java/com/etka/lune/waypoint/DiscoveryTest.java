package com.etka.lune.waypoint;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DiscoveryTest {

    @Test
    void aDiscoveryIsLookedUpByWhatItIsNotWhereItIs() {
        Discovery first = new Discovery(Discovery.BIOME, "minecraft:jungle", "minecraft:overworld", 100, 200, 1L);
        Discovery moved = new Discovery(Discovery.BIOME, "minecraft:jungle", "minecraft:overworld", -5, 7, 2L);
        Discovery elsewhere = new Discovery(Discovery.BIOME, "minecraft:jungle", "minecraft:the_nether", 100, 200, 1L);
        assertEquals(first.key(), moved.key());
        assertEquals("biome|minecraft:jungle|minecraft:overworld", first.key());
        assertEquals(false, first.key().equals(elsewhere.key()));
    }

    @Test
    void aRegistryPathReadsAsATitle() {
        assertEquals("Ancient City", Discovery.humanize("minecraft:ancient_city"));
        assertEquals("Trail Ruins", Discovery.humanize("trail_ruins"));
        assertEquals("Village Plains", Discovery.humanize("somemod:village/plains"));
        assertEquals("", Discovery.humanize(""));
    }
}
