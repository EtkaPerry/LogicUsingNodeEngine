package com.etka.lune.bot.task;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetherPortalFrameTest {

    private static final BlockPos BASE = new BlockPos(10, 64, -3);

    @Test
    void openingIsTheTwoByThreeInsideTheFrameBottomRowFirst() {
        // Bottom row first is what makes the first lit cell the one a player's feet go into.
        assertEquals(List.of(
                BASE.offset(1, 1, 0), BASE.offset(2, 1, 0),
                BASE.offset(1, 2, 0), BASE.offset(2, 2, 0),
                BASE.offset(1, 3, 0), BASE.offset(2, 3, 0)), NetherPortalFrame.opening(BASE));
    }

    @Test
    void frameAndOpeningFillTheFourByFiveWithoutSharingACell() {
        Set<BlockPos> frame = new HashSet<>(NetherPortalFrame.positions(BASE, true));
        Set<BlockPos> opening = new HashSet<>(NetherPortalFrame.opening(BASE));
        Set<BlockPos> both = new HashSet<>(frame);
        both.addAll(opening);

        assertEquals(14, frame.size());
        assertEquals(6, opening.size());
        assertEquals(20, both.size(), "no frame block may stand in the opening");
        for (BlockPos cell : both) {
            int x = cell.getX() - BASE.getX();
            int y = cell.getY() - BASE.getY();
            assertTrue(x >= 0 && x < 4 && y >= 0 && y < 5 && cell.getZ() == BASE.getZ(),
                    "outside the frame's rectangle: " + cell);
        }
    }

    @Test
    void theOpenCornerFrameClosesRoundTheSameOpening() {
        Set<BlockPos> frame = new HashSet<>(NetherPortalFrame.positions(BASE, false));
        for (BlockPos cell : NetherPortalFrame.opening(BASE)) {
            assertTrue(!frame.contains(cell), "frame block in the opening: " + cell);
        }
        // The ten blocks vanilla needs: two below, two above, three up each side.
        assertEquals(10, frame.size());
    }
}
