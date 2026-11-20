package com.etka.lune.waypoint;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PortalLinkTest {

    @Test
    void vanillaPairsTheNetherWithEverywhereButItself() {
        assertEquals("minecraft:the_nether", PortalLink.vanillaOtherEnd("minecraft:overworld"));
        assertEquals("minecraft:overworld", PortalLink.vanillaOtherEnd("minecraft:the_nether"));
        // The pairing is hardcoded in vanilla: a Nether portal in a modded world still aims at
        // the Nether when it works at all.
        assertEquals("minecraft:the_nether", PortalLink.vanillaOtherEnd("twilightforest:twilight_forest"));
    }
}
