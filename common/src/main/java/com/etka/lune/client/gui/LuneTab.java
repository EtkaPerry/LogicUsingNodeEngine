package com.etka.lune.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Base class for Lune's tabs.
 * <p>
 * Vanilla's {@link net.minecraft.client.gui.components.tabs.GridLayoutTab} assumes a simple grid,
 * but the Task tab needs a hand-placed multi-pane layout, so Lune places widgets itself.
 * <p>
 * Widgets are created <em>once</em>, in the subclass constructor, and {@link #doLayout} only moves
 * them. That mirrors how vanilla's own tabs behave, and it matters:
 * {@link net.minecraft.client.gui.components.tabs.TabManager} adds a tab's children to the screen
 * when the tab is selected, so rebuilding the widget list on every layout pass would leave the
 * previous generation of widgets registered on the screen - invisible, but still eating clicks.
 * Anything whose <em>contents</em> change at runtime (the command list, the parameter editor)
 * belongs inside a self-managing widget rather than in a rebuilt widget list.
 */
public abstract class LuneTab implements Tab {

    private final Component title;
    protected final List<AbstractWidget> widgets = new ArrayList<>();
    protected ScreenRectangle area = ScreenRectangle.empty();

    protected LuneTab(Component title) {
        this.title = title;
    }

    @Override
    public Component getTabTitle() {
        return title;
    }

    @Override
    public Component getTabExtraNarration() {
        return title;
    }

    @Override
    public void visitChildren(Consumer<AbstractWidget> consumer) {
        widgets.forEach(consumer);
    }

    @Override
    public void doLayout(ScreenRectangle area) {
        this.area = area;
        layout(area);
    }

    /** Positions this tab's (already-created) widgets inside {@code area}. */
    protected abstract void layout(ScreenRectangle area);

    /** Registers a widget owned by this tab. Call from the subclass constructor only. */
    protected <T extends AbstractWidget> T add(T widget) {
        widgets.add(widget);
        return widget;
    }

    /**
     * Panel backgrounds and dividers. Drawn <em>before</em> widgets, so it sits behind them.
     * Called by {@link LuneScreen} for the active tab only.
     */
    public void extractTabBackground(GuiGraphicsExtractor extractor) {}

    /**
     * Foreground content the tab draws itself rather than through a widget - status text, labels,
     * hover highlights. Drawn <em>after</em> widgets. Active tab only.
     */
    public void extractTabRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {}

    /** Per-client-tick hook, used by tabs that mirror live bot state. */
    public void tick() {}
}
