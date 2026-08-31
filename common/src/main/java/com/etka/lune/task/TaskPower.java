package com.etka.lune.task;

import java.util.Set;

/**
 * Which cards hold a signal right now, and which wires are carrying one.
 *
 * <p>The canvas draws the task; the runner decides what is happening inside it. Both sides have
 * to name a wire identically or the glow lands on the wrong one, so the name is built here and
 * nowhere else.</p>
 *
 * <p>A task has more than one circuit at a time - START drives one, every Always, Observer and
 * Button drives its own - so this is a set rather than a single "current node". Showing only the
 * primary lane is what made a working Always branch look dead.</p>
 */
public final class TaskPower {

    /** Wire kinds, matching the pin a wire leaves from. */
    public static final int SUCCESS = 0;
    public static final int FAILURE = 1;
    public static final int WHILE = 2;
    public static final int SIGNAL = 3;
    public static final int ALWAYS = 4;

    public static final TaskPower NONE = new TaskPower(Set.of(), Set.of());

    private final Set<String> nodes;
    private final Set<String> wires;

    public TaskPower(Set<String> nodes, Set<String> wires) {
        this.nodes = nodes == null ? Set.of() : Set.copyOf(nodes);
        this.wires = wires == null ? Set.of() : Set.copyOf(wires);
    }

    /**
     * Names one wire.
     *
     * <p>{@code outputPort} only distinguishes the several outputs of a Signal Relay, which are the
     * only pins that can carry two wires to the same card. Success, Fail, While and Always each
     * have a single pin, so they pass zero.</p>
     */
    public static String wire(String fromId, int kind, int outputPort, String toId) {
        return fromId + '#' + kind + '.' + outputPort + '>' + toId;
    }

    public boolean isLive(TaskNode node) {
        return node != null && node.id != null && nodes.contains(node.id);
    }

    public boolean isLiveWire(TaskNode from, int kind, int outputPort, TaskNode to) {
        return from != null && to != null && from.id != null && to.id != null
                && wires.contains(wire(from.id, kind, outputPort, to.id));
    }

    public boolean isEmpty() {
        return nodes.isEmpty() && wires.isEmpty();
    }
}
