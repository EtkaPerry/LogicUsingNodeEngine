package com.etka.lune.routine;

import java.util.ArrayList;
import java.util.List;

/**
 * A named graph of {@link RoutineNode}s - the user's own "go mine" or "harvest" job.
 * <p>
 * START sends one signal into the primary circuit. Always periodically sends new signals into its
 * own target circuits. Nodes within a circuit run in list order by default; a node's
 * {@code onSuccess} / {@code onFailure} edges override that, which is how branches and loops are
 * expressed.
 */
public final class Routine {

    public String name = "New Task";
    public List<RoutineNode> nodes = new ArrayList<>();
    /** Optional monitor node id active for every step in the routine. */
    public String onWhile;

    public Routine() {}

    public Routine(String name) {
        this.name = name;
    }

    public RoutineNode nodeById(String id) {
        if (id == null) {
            return null;
        }
        for (RoutineNode node : nodes) {
            if (node != null && id.equals(node.id)) {
                return node;
            }
        }
        return null;
    }

    public int indexOf(RoutineNode node) {
        return nodes.indexOf(node);
    }

    /** Moves a node up or down the list, for reordering in the editor. */
    public void move(RoutineNode node, int delta) {
        int from = nodes.indexOf(node);
        int to = from + delta;
        if (from < 0 || to < 0 || to >= nodes.size()) {
            return;
        }
        nodes.remove(from);
        nodes.add(to, node);
    }

    public String describe() {
        return name + "  (" + nodes.size() + (nodes.size() == 1 ? " step)" : " steps)");
    }
}
