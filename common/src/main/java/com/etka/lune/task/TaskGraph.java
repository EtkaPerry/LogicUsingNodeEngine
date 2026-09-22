package com.etka.lune.task;

import com.etka.lune.util.Lang;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A named graph of {@link TaskNode}s - the user's own "go mine" or "harvest" job.
 * <p>
 * START sends one signal into the primary circuit. Always periodically sends new signals into its
 * own target circuits. Nodes within a circuit run in list order by default; a node's
 * {@code onSuccess} / {@code onFailure} edges override that, which is how branches and loops are
 * expressed.
 */
public final class TaskGraph {

    public String name = "New Task";
    /**
     * Which starter job this is, for the six the mod ships with. Null for anything the player made.
     *
     * <p>The name cannot do this job. It is the task's identity on disk, what other tasks point at
     * from a Run Task card, and what {@code uniqueName} keeps distinct - so it has to stay the same
     * string in every language. A seeded job still needs a title a Turkish player can read, and
     * those are two different requirements wearing one field.</p>
     *
     * <p>So the id identifies and {@link #displayName()} reads. Rename a seeded job and this clears:
     * it is your task then, and your name for it is the right one to show.</p>
     */
    public String seededId;
    public List<TaskNode> nodes = new ArrayList<>();
    /** Optional monitor node id active for every step in the task. */
    public String onWhile;
    /** Editor-only cable routing points, keyed by the stable endpoints and ports of each cable. */
    public Map<String, TaskCableRoute> cableAnchors = new LinkedHashMap<>();
    /**
     * Editor-only sticky notes and frames.
     *
     * <p>They travel with the task through save, share and import, because the reason a task is
     * wired the way it is goes stale the moment it is separated from the wiring. Execution never
     * reads either list - see {@link TaskNote} for why neither is a card.</p>
     */
    public List<TaskNote> notes = new ArrayList<>();
    public List<TaskGroup> groups = new ArrayList<>();

    public TaskGraph() {}

    public TaskGraph(String name) {
        this.name = name;
    }

    public TaskNote noteById(String id) {
        if (id == null || notes == null) {
            return null;
        }
        for (TaskNote note : notes) {
            if (note != null && id.equals(note.id)) {
                return note;
            }
        }
        return null;
    }

    public TaskGroup groupById(String id) {
        if (id == null || groups == null) {
            return null;
        }
        for (TaskGroup group : groups) {
            if (group != null && id.equals(group.id)) {
                return group;
            }
        }
        return null;
    }

    public TaskNode nodeById(String id) {
        if (id == null) {
            return null;
        }
        for (TaskNode node : nodes) {
            if (node != null && id.equals(node.id)) {
                return node;
            }
        }
        return null;
    }

    public int indexOf(TaskNode node) {
        return nodes.indexOf(node);
    }

    /** Moves a node up or down the list, for reordering in the editor. */
    public void move(TaskNode node, int delta) {
        int from = nodes.indexOf(node);
        int to = from + delta;
        if (from < 0 || to < 0 || to >= nodes.size()) {
            return;
        }
        nodes.remove(from);
        nodes.add(to, node);
    }

    /**
     * The title to put on screen - translated for a starter job, the player's own words otherwise.
     *
     * <p>Falls back to {@link #name} when there is no line for the id, so a job seeded by a version
     * that knew about it still reads correctly after a downgrade.</p>
     */
    public String displayName() {
        if (seededId == null || seededId.isBlank()) {
            return name;
        }
        return Lang.getOr("lune.task.seeded." + seededId, name);
    }

    public String describe() {
        return Lang.get(nodes.size() == 1 ? "lune.task.describe_step" : "lune.task.describe_steps",
                displayName(), nodes.size());
    }
}
