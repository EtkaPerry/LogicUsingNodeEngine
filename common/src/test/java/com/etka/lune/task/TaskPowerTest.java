package com.etka.lune.task;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TaskPowerTest {

    @Test
    void tellsLiveCardsAndWiresApartFromTheirNeighbours() {
        TaskNode from = new TaskNode("condition");
        TaskNode live = new TaskNode("self_preservation");
        TaskNode idle = new TaskNode("stopgame");

        TaskPower power = new TaskPower(
                Set.of(from.id, live.id),
                Set.of(TaskPower.wire(from.id, TaskPower.SUCCESS, 0, live.id)));

        assertTrue(power.isLive(from));
        assertTrue(power.isLive(live));
        assertFalse(power.isLive(idle));
        assertTrue(power.isLiveWire(from, TaskPower.SUCCESS, 0, live));

        // The same pair of cards, but the other pin: a running Success branch must not light the
        // Fail wire beside it.
        assertFalse(power.isLiveWire(from, TaskPower.FAILURE, 0, live));
        assertFalse(power.isLiveWire(from, TaskPower.SUCCESS, 0, idle));
        assertFalse(power.isLiveWire(live, TaskPower.SUCCESS, 0, from));
    }

    @Test
    void relayOutputsAreNamedSeparatelyBecauseTwoCanShareATarget() {
        TaskNode relay = new TaskNode(TaskNode.SIGNAL_RELAY_COMMAND);
        TaskNode target = new TaskNode("mine");

        TaskPower power = new TaskPower(Set.of(target.id),
                Set.of(TaskPower.wire(relay.id, TaskPower.SIGNAL, 1, target.id)));

        assertTrue(power.isLiveWire(relay, TaskPower.SIGNAL, 1, target));
        assertFalse(power.isLiveWire(relay, TaskPower.SIGNAL, 0, target));
    }

    @Test
    void nothingIsLiveWhenNoTaskIsRunning() {
        assertTrue(TaskPower.NONE.isEmpty());
        assertFalse(TaskPower.NONE.isLive(new TaskNode("mine")));
        assertFalse(TaskPower.NONE.isLive(null));
    }
}
