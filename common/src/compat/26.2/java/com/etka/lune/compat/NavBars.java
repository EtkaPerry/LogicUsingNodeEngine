package com.etka.lune.compat;

import net.minecraft.client.gui.components.tabs.MenuTabBar;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;

/**
 * The row of tabs across the top of a screen, on Minecraft 26.2.
 *
 * <p>26.1.2 builds it straight from {@code TabNavigationBar}; 26.2 turned that class into a bare
 * container and moved the menu-style bar, the one that sizes and centres its tabs, into
 * {@code MenuTabBar}. This file exists once per folder under {@code common/src/compat}; the build compiles the one
 * named by {@code compat_variant} in the target's gradle/versions file. Keep the copies in step.</p>
 */
public final class NavBars {

    private NavBars() {}

    /** A tab bar {@code width} wide with these tabs, in this order. */
    public static TabNavigationBar build(TabManager tabManager, int width, Tab... tabs) {
        return MenuTabBar.builder(tabManager, width).addTabs(tabs).build();
    }

    /** Lays the bar out again for a new screen width. */
    public static void resize(TabNavigationBar bar, int width) {
        bar.arrangeElements(width);
    }
}
