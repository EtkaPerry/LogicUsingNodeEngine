package com.etka.lune.task;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TaskCableRouteTest {
    @Test
    void returnWiresStayOutsideCardsInsteadOfRetracingTheForwardWire() {
        for (int lane : new int[] {0, 14}) {
            int y = lane == 0 ? 55 : 70;
            TaskCableRoute route = TaskCableRoute.returning(564, y, 232, 55, 98, lane);
            TaskCablePath path = TaskCablePath.of(564, y, 232, 55, route);
            boolean passedBelow = false;
            for (int i = 1; i < 1000; i++) {
                var point = path.at(i / 1000.0);
                boolean inTarget = point.x() > 232 && point.x() < 356;
                boolean inSource = point.x() > 440 && point.x() < 564;
                assertFalse((inTarget || inSource) && point.y() >= 26 && point.y() <= 98,
                        "return wire crosses a card at " + point);
                passedBelow |= point.y() > 98;
            }
            assertTrue(passedBelow);
        }
    }

    @Test
    void returnLanesAreSeparatedAndCanBeEditedWithoutChangingTheDefault() {
        TaskCableRoute success = TaskCableRoute.returning(564, 55, 232, 55, 98, 0);
        TaskCableRoute failure = TaskCableRoute.returning(564, 70, 232, 55, 98, 14);
        assertTrue(failure.points.get(1).y > success.points.get(1).y);
        TaskCableRoute edited = success.copy();
        edited.insertPoint(2, new TaskCableAnchor(400, 180));
        assertEquals(4, success.points.size());
        assertEquals(5, edited.points.size());
    }

    @Test
    void differentPinsApproachOneInputFromDifferentAngles() {
        TaskCableRoute success = TaskCableRoute.returning(564, 55, 232, 55, 98, 0, 0);
        TaskCableRoute failure = TaskCableRoute.returning(564, 70, 232, 55, 98, 14, 9);
        assertNotEquals(success.points.get(3).y, failure.points.get(3).y);
        assertEquals(55, success.points.get(3).y);
        assertEquals(64, failure.points.get(3).y);
    }
}
