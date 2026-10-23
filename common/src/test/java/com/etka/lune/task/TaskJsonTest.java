package com.etka.lune.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The rules a task is written and read by, wherever it is read - the panel or a server. */
class TaskJsonTest {

    @Test
    void aTaskReadsBackAsItWasWritten() {
        TaskGraph task = new TaskGraph("Round trip");
        TaskNode start = new TaskNode(TaskNode.START_COMMAND);
        TaskNode follow = new TaskNode("follow_me");
        follow.params.put("distance", "4");
        follow.repeat = 0;
        start.onSuccess = follow.id;
        task.nodes.add(start);
        task.nodes.add(follow);

        String once = TaskJson.compact(task);
        assertEquals(once, TaskJson.compact(TaskJson.read(once)));
        assertEquals(once, TaskStore.exportCompact(task), "the store writes by the same rules");
    }

    @Test
    void aCableRouteSavedAsOneAnchorReadsAsAOnePointRoute() {
        TaskGraph task = TaskJson.read("{\"name\":\"Old\",\"nodes\":[],"
                + "\"cableAnchors\":{\"a>b\":{\"x\":12,\"y\":34}}}");
        TaskCableRoute route = task.cableAnchors.get("a>b");
        assertEquals(1, route.points.size());
    }
}
