package com.etka.lune.waypoint;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeathTest {

    @Test
    void aDeathKeepsTheHeightItHappenedAt() {
        Death death = Death.at(new BlockPos(12, -40, -7), "minecraft:overworld", 1_000L);
        assertEquals(new BlockPos(12, -40, -7), death.pos());
        assertEquals("minecraft:overworld", death.dimension());
    }

    @Test
    void ageIsNeverNegative() {
        Death death = Death.at(BlockPos.ZERO, "minecraft:overworld", 5_000L);
        assertEquals(3_000L, death.ageMillis(8_000L));
        // A clock that went backwards - a resynced system clock, a world reloaded from a backup -
        // must read as "just now" rather than as a negative duration nobody can render.
        assertEquals(0L, death.ageMillis(1_000L));
    }
}
