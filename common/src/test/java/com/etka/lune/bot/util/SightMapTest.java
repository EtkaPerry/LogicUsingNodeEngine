package com.etka.lune.bot.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a sight line writes down: the cells it crossed, and the one it stopped at. */
class SightMapTest {

    @Test
    void aLineOpensWhatItCrossesAndStopsWhereItStopped() {
        SightMap map = new SightMap();
        List<Long> opened = new ArrayList<>();
        map.trace(new Vec3(0.5, 64.5, 0.5), new Vec3(5.0, 64.5, 0.5), new BlockPos(5, 64, 0), opened::add);
        for (int x = 0; x < 5; x++) {
            assertEquals(SightMap.OPEN, map.state(new BlockPos(x, 64, 0)), "cell " + x);
        }
        assertEquals(SightMap.STOPPED, map.state(new BlockPos(5, 64, 0)));
        assertEquals(SightMap.UNSEEN, map.state(new BlockPos(6, 64, 0)));
        assertEquals(5, opened.size());
    }

    @Test
    void aSlantedLineWalksAConnectedRunOfCells() {
        SightMap map = new SightMap();
        List<Long> opened = new ArrayList<>();
        map.trace(new Vec3(0.2, 64.7, 0.4), new Vec3(9.7, 60.1, 6.9), null, opened::add);
        BlockPos previous = null;
        for (long key : opened) {
            BlockPos cell = BlockPos.of(key);
            if (previous != null) {
                int steps = Math.abs(cell.getX() - previous.getX()) + Math.abs(cell.getY() - previous.getY())
                        + Math.abs(cell.getZ() - previous.getZ());
                assertEquals(1, steps, previous + " to " + cell);
            }
            previous = cell;
        }
        assertEquals(new BlockPos(0, 64, 0), BlockPos.of(opened.get(0)));
        assertEquals(new BlockPos(9, 60, 6), previous);
    }

    @Test
    void lookingAgainOnlyReportsWhatIsNew() {
        SightMap map = new SightMap();
        map.trace(new Vec3(0.5, 64.5, 0.5), new Vec3(4.0, 64.5, 0.5), new BlockPos(4, 64, 0), null);
        List<Long> opened = new ArrayList<>();
        map.trace(new Vec3(0.5, 64.5, 0.5), new Vec3(4.0, 64.5, 0.5), new BlockPos(4, 64, 0), opened::add);
        assertTrue(opened.isEmpty(), "nothing new: " + opened);
    }

    @Test
    void aWallDugThroughReadsAsOpenTheNextTime() {
        SightMap map = new SightMap();
        map.trace(new Vec3(0.5, 64.5, 0.5), new Vec3(3.0, 64.5, 0.5), new BlockPos(3, 64, 0), null);
        assertEquals(SightMap.STOPPED, map.state(new BlockPos(3, 64, 0)));
        List<Long> opened = new ArrayList<>();
        map.trace(new Vec3(0.5, 64.5, 0.5), new Vec3(6.0, 64.5, 0.5), new BlockPos(6, 64, 0), opened::add);
        assertEquals(SightMap.OPEN, map.state(new BlockPos(3, 64, 0)));
        assertTrue(opened.contains(new BlockPos(3, 64, 0).asLong()));
    }
}
