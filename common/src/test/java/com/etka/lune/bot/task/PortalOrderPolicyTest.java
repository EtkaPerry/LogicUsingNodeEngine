package com.etka.lune.bot.task;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalOrderPolicyTest {

    @Test
    void everyCompleteFrameOrderContainsEachBlockOnce() {
        HashSet<PortalOrderPolicy.Offset> expected = new HashSet<>(
                PortalOrderPolicy.offsets(true, PortalOrderPolicy.ALTERNATING, 0.0));
        for (String action : PortalOrderPolicy.ACTIONS) {
            List<PortalOrderPolicy.Offset> order = PortalOrderPolicy.offsets(true, action, -2.0);
            assertEquals(14, order.size());
            assertEquals(14, new HashSet<>(order).size());
            assertEquals(expected, new HashSet<>(order));
        }
    }

    @Test
    void nearestColumnStartsOnPlayersSideAndKeepsSupportedSequence() {
        List<PortalOrderPolicy.Offset> order = PortalOrderPolicy.offsets(
                true, PortalOrderPolicy.NEAREST_COLUMN, -1.0);
        assertEquals(new PortalOrderPolicy.Offset(0, 1), order.get(4));

        HashSet<PortalOrderPolicy.Offset> placed = new HashSet<>();
        for (PortalOrderPolicy.Offset target : order) {
            boolean floor = target.y() == 0;
            boolean adjacent = placed.stream().anyMatch(pos ->
                    Math.abs(pos.x() - target.x()) + Math.abs(pos.y() - target.y()) == 1);
            assertTrue(floor || adjacent, "frame target must have earlier support: " + target);
            placed.add(target);
        }
    }

    @Test
    void openCornerFrameRetainsKnownOrder() {
        assertEquals(PortalOrderPolicy.offsets(false, PortalOrderPolicy.ALTERNATING, 0.0),
                PortalOrderPolicy.offsets(false, PortalOrderPolicy.NEAREST_COLUMN, 0.0));
    }
}
