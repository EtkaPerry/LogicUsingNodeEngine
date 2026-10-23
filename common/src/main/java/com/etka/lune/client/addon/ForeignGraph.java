package com.etka.lune.client.addon;

import com.etka.lune.task.TaskGraph;

import java.util.Set;

/**
 * A graph the Task tab edits that is not one of the player's tasks: a Lumi's own, which lives on
 * her, on the server, and reaches her only when it is sent.
 *
 * <p>The editor treats it as it treats a training lesson's practice task - the real editor, with
 * the player's own work and undo history parked until it is done - so everything the editor can do
 * to a card it can do here. What it adds is where the graph goes: the Run button sends it, and
 * leaving hands it back, saying whether it changed. Whatever reaches the body is the mod's to
 * decide; the editor only ever hands over a copy.</p>
 */
public interface ForeignGraph {

    /** The graph to edit. A copy: the editor changes it freely, and hands it back changed. */
    TaskGraph graph();

    /** What the editor's pane is titled with: "Pip's nodes". */
    String title();

    /** The lines under the title, in the player's language: whose graph this is, and how it reaches them. */
    String about();

    /** The cards the palette offers: the body's own, and the runner's own that it accepts. */
    Set<String> cards();

    /** The folder the body's own cards are filed under, which the palette opens. */
    String folder();

    /** What the Run button says: "Send to Pip". */
    String sendLabel();

    /** What the way back says: "Back to Lumis". */
    String leaveLabel();

    /** The Run button: the graph as it is now goes to the body. Returns the line the editor shows. */
    String send(TaskGraph graph);

    /** Whether {@link #create} would make another graph beside this one: the editor offers New only then. */
    default boolean canCreate() {
        return false;
    }

    /**
     * Another graph beside this one, for the editor to open in this one's place once this one has
     * been handed back; null when the body offers none.
     */
    default ForeignGraph create() {
        return null;
    }

    /**
     * The editor is done with the graph: the way back, another task opened, the panel closed.
     *
     * @param changed whether it differs from what was opened or last sent
     * @return the line the editor shows, or null for none
     */
    String left(TaskGraph graph, boolean changed);
}
