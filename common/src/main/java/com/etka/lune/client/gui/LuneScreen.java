package com.etka.lune.client.gui;

import com.etka.lune.client.gui.tab.ConfigTab;
import com.etka.lune.client.gui.tab.MainTab;
import com.etka.lune.client.gui.tab.RoutinesTab;
import com.etka.lune.client.gui.tab.WaypointsTab;
import com.etka.lune.client.gui.mascot.MascotAdvisor;
import com.etka.lune.client.gui.mascot.MascotWidget;
import com.etka.lune.client.gui.widget.BlockPicker;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Lune's control panel: a vanilla tab bar across the top, and one full-width content area below
 * it that the active {@link LuneTab} owns.
 * <p>
 * The panel draws itself at its own GUI scale (see {@link UiScale}) rather than the game's, so a
 * large game scale cannot squeeze the layout into a space it does not fit. That means
 * {@link #width} and {@link #height} are in <em>Lune</em> pixels, not the game's: everything below
 * this class - tabs, widgets, the mascot - works in that space and never has to think about it.
 * The two places the difference leaks out are handled here: drawing is wrapped in a scale
 * transform, and incoming mouse coordinates are converted on the way in.
 */
public class LuneScreen extends Screen {

    public static final int PANEL_BG = 0xC0101014;
    public static final int PANEL_BORDER = 0xFF3A3A42;
    public static final int TEXT = 0xFFE0E0E0;
    public static final int TEXT_DIM = 0xFF8A8A94;
    public static final int ACCENT = 0xFF4C9EFF;

    private final TabManager tabManager = new TabManager(this::addRenderableWidget, this::removeWidget);

    private final MainTab mainTab = new MainTab();
    private final RoutinesTab routinesTab = new RoutinesTab();
    private final ConfigTab configTab = new ConfigTab();
    private final WaypointsTab waypointsTab = new WaypointsTab();

    private final BlockPicker blockPicker = new BlockPicker();
    private final MascotWidget mascot = new MascotWidget();

    private TabNavigationBar navBar;

    /** Scale the menu draws at, and the two conversions derived from it. */
    private int menuScale = 1;
    /** Size of one Lune pixel in game GUI pixels; the transform every draw call goes through. */
    private float menuPixelSize = 1.0F;
    /** Inverse of the above, for turning the game's mouse coordinates into Lune's. */
    private double menuPixelsPerGamePixel = 1.0;

    public LuneScreen() {
        super(Component.literal("Lune"));
    }

    @Override
    protected void init() {
        applyMenuScale();
        // Register first for pointer priority; rendering is manual so Lune still appears above tabs.
        addWidget(mascot);
        navBar = addRenderableWidget(TabNavigationBar.builder(tabManager, this.width)
                .addTabs(mainTab, routinesTab, waypointsTab, configTab)
                .build());
        navBar.selectTab(0, false);
        addRenderableWidget(blockPicker);
        routinesTab.setBlockPicker(blockPicker);
        repositionElements();
    }

    @Override
    protected void repositionElements() {
        applyMenuScale();
        if (navBar == null) {
            return;
        }
        navBar.updateWidth(this.width);
        navBar.arrangeElements();
        int top = navBar.getRectangle().bottom();
        ScreenRectangle contentArea = new ScreenRectangle(0, top, this.width, this.height - top);
        tabManager.setTabArea(contentArea);
        mascot.setScreenArea(contentArea);
    }

    /**
     * Resizes the screen into Lune pixels. Called from both entry points vanilla uses - {@code
     * init()} the first time the screen opens, {@code repositionElements()} on every resize and
     * GUI scale change - because {@code Screen.init(int, int)} is final and sets the game's own
     * dimensions just before calling them.
     */
    private void applyMenuScale() {
        Window window = Minecraft.getInstance().getWindow();
        int gameScale = UiScale.gameScale(window);
        menuScale = UiScale.menuScale(window);
        menuPixelSize = menuScale / (float) gameScale;
        menuPixelsPerGamePixel = gameScale / (double) menuScale;
        // Rounded down: a Lune pixel that only partly exists would be drawn off the screen edge.
        this.width = Math.max(1, (int) (window.getWidth() / (double) menuScale));
        this.height = Math.max(1, (int) (window.getHeight() / (double) menuScale));
    }

    private double toMenu(double gameCoordinate) {
        return gameCoordinate * menuPixelsPerGamePixel;
    }

    private MouseButtonEvent toMenu(MouseButtonEvent event) {
        return new MouseButtonEvent(toMenu(event.x()), toMenu(event.y()), event.buttonInfo());
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        // Deliberately outside the transform: vanilla's backdrop covers whatever it is given, and
        // this call also flushes the subtitle overlay, which belongs at the game's own scale.
        super.extractBackground(extractor, mouseX, mouseY, partialTick);
        if (tabManager.getCurrentTab() instanceof LuneTab tab) {
            var pose = extractor.pose();
            pose.pushMatrix();
            pose.scale(menuPixelSize, menuPixelSize);
            tab.extractTabBackground(extractor);
            pose.popMatrix();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        int menuMouseX = (int) toMenu(mouseX);
        int menuMouseY = (int) toMenu(mouseY);
        var pose = extractor.pose();
        pose.pushMatrix();
        pose.scale(menuPixelSize, menuPixelSize);
        super.extractRenderState(extractor, menuMouseX, menuMouseY, partialTick);
        if (tabManager.getCurrentTab() instanceof LuneTab tab) {
            tab.extractTabRenderState(extractor, menuMouseX, menuMouseY, partialTick);
        }
        mascot.renderOverlay(extractor, menuMouseX, menuMouseY, partialTick);
        if (blockPicker.isOpen()) {
            blockPicker.render(extractor, menuMouseX, menuMouseY, partialTick);
        }
        pose.popMatrix();
    }

    @Override
    public void tick() {
        super.tick();
        // Picks up a change to the Config tab's menu size without waiting for the screen to reopen.
        if (menuScale != UiScale.menuScale(Minecraft.getInstance().getWindow())) {
            repositionElements();
        }
        if (tabManager.getCurrentTab() instanceof LuneTab tab) {
            tab.tick();
        }
        MascotAdvisor.Surface surface = tabManager.getCurrentTab() == routinesTab
                ? MascotAdvisor.Surface.ROUTINES
                : tabManager.getCurrentTab() == waypointsTab
                ? MascotAdvisor.Surface.WAYPOINTS
                : tabManager.getCurrentTab() == configTab
                ? MascotAdvisor.Surface.CONFIG
                : MascotAdvisor.Surface.MAIN;
        mascot.setSurface(surface);
        mascot.tick();
    }

    /** The bot keeps running while the panel is open, so this must not pause a singleplayer world. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Settings and tasks are edited live; persisting on close avoids writing every tick. */
    @Override
    public void onClose() {
        configTab.save();
        routinesTab.save();
        super.onClose();
    }

    /**
     * Every mouse event arrives in the game's GUI pixels and is converted here, once, so widgets
     * hit-test against the same coordinates they were laid out in. Route events to BlockPicker
     * when it's open.
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        MouseButtonEvent menuEvent = toMenu(event);
        if (blockPicker.isOpen()) {
            blockPicker.handleScreenMouseClick(menuEvent.x(), menuEvent.y(), menuEvent.button());
            return true;
        }
        return super.mouseClicked(menuEvent, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return super.mouseReleased(toMenu(event));
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        return super.mouseDragged(toMenu(event), toMenu(dragX), toMenu(dragY));
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        super.mouseMoved(toMenu(mouseX), toMenu(mouseY));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        double menuMouseX = toMenu(mouseX);
        double menuMouseY = toMenu(mouseY);
        if (blockPicker.isOpen()) {
            return blockPicker.mouseScrolled(menuMouseX, menuMouseY, deltaX, deltaY);
        }
        return super.mouseScrolled(menuMouseX, menuMouseY, deltaX, deltaY);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (blockPicker.isOpen()) {
            blockPicker.handleScreenCharTyped(event.codepoint());
            return true;
        }
        return super.charTyped(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (blockPicker.isOpen()) {
            blockPicker.handleScreenKeyPressed(event.key(), 0, event.modifiers());
            return true;
        }
        return super.keyPressed(event);
    }

    /** Draws a filled panel with a 1px border. Shared by every tab. */
    public static void panel(GuiGraphicsExtractor extractor, int x, int y, int width, int height) {
        extractor.fill(x, y, x + width, y + height, PANEL_BG);
        extractor.fill(x, y, x + width, y + 1, PANEL_BORDER);
        extractor.fill(x, y + height - 1, x + width, y + height, PANEL_BORDER);
        extractor.fill(x, y, x + 1, y + height, PANEL_BORDER);
        extractor.fill(x + width - 1, y, x + width, y + height, PANEL_BORDER);
    }
}
