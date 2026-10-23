package com.etka.lune.client.addon;

import com.etka.lune.client.gui.LuneTab;

/** What a tab a mod built on Lune adds may ask of the panel it is in. */
public interface LunePanel {

    /**
     * Opens a graph that is not one of the player's tasks - a Lumi's own - in the Task tab, in Lune's
     * real editor, and comes back to {@code from} when the player leaves it.
     */
    void editGraph(ForeignGraph graph, LuneTab from);
}
