package com.etka.lune.client.addon;

import com.etka.lune.client.gui.LuneTab;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Where a mod built on Lune - Lumi - adds itself to Lune's panel. Lune never depends on such a mod:
 * the mod calls in here from its client entry point, before any panel opens, and the panel asks
 * this class as it is built.
 *
 * <p>A tab is the add-on's own, and it is built afresh every time the panel opens, as Lune's tabs
 * are. Its cards go in {@code CommandRegistry.registerAddon}, and its rules in
 * {@code AddonRules}; the editor opens its graphs through the {@link LunePanel} the tab is handed.</p>
 *
 * <p>Lune keeps six tabs of its own. A tab added here is the exception the user asked for, on
 * 2026-10-04, for the Lumis: it is there only while the mod that adds it is installed.</p>
 */
public final class LuneAddons {

    /**
     * A tab a mod built on Lune adds, and where it goes among Lune's. Main and Task stay first
     * whatever is asked, because other parts of the panel go to them by place.
     *
     * @param position the place in the bar, counted from zero; clamped to after Task
     */
    public record Tab(int position, Function<LunePanel, LuneTab> factory) {}

    private static final List<Tab> TABS = new CopyOnWriteArrayList<>();

    private LuneAddons() {}

    public static void addTab(int position, Function<LunePanel, LuneTab> factory) {
        TABS.add(new Tab(position, factory));
    }

    /** Every tab added, in the order they were added. */
    public static List<Tab> tabs() {
        return List.copyOf(TABS);
    }
}
