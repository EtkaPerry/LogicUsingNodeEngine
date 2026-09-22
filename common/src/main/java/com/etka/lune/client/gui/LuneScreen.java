package com.etka.lune.client.gui;

import com.etka.lune.compat.NavBars;
import com.etka.lune.util.Lang;
import com.etka.lune.client.gui.tab.AboutTab;
import com.etka.lune.client.gui.tab.ConfigTab;
import com.etka.lune.client.gui.tab.MainTab;
import com.etka.lune.client.gui.tab.TasksTab;
import com.etka.lune.client.gui.tab.WaypointsTab;
import com.etka.lune.client.gui.mascot.MascotAdvisor;
import com.etka.lune.client.gui.mascot.MascotWidget;
import com.etka.lune.client.gui.widget.BlockPicker;
import com.etka.lune.client.gui.widget.NamePrompt;
import com.etka.lune.client.gui.widget.RecipePicker;
import com.etka.lune.client.gui.widget.SoundPicker;
import com.etka.lune.client.gui.widget.InventoryPicker;
import com.etka.lune.client.gui.widget.TrainingScreen;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.navigation.ScreenRectangle;
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
 * The transform and the pointer conversion that make that true live in {@link ScaledScreen}, which
 * the terms page shares, so the two cannot drift apart.
 */
public class LuneScreen extends ScaledScreen {

    public static final int PANEL_BG = 0xC0101014;
    public static final int PANEL_BORDER = 0xFF3A3A42;
    public static final int TEXT = 0xFFE0E0E0;
    public static final int TEXT_DIM = 0xFF8A8A94;
    /** The default Lune primary colour: #ff8811. */
    public static final int ACCENT = 0xFFFF8811;
    /** A lighter primary shade for selected outlines and high-emphasis states. */
    public static final int ACCENT_HOVER = 0xFFFFB866;
    /** A dark primary shade for hover surfaces that need contrast with the accent. */
    public static final int ACCENT_DARK = 0xFFC86400;
    /** Transparent primary shades used for selected rows and splitter glows. */
    public static final int ACCENT_SELECTION = 0x50FF8811;
    public static final int ACCENT_GLOW = 0x18FF8811;

    private final TabManager tabManager = new TabManager(this::addRenderableWidget, this::removeWidget);

    private final MainTab mainTab = new MainTab();
    private final TasksTab tasksTab = new TasksTab();
    private final ConfigTab configTab = new ConfigTab();
    private final WaypointsTab waypointsTab = new WaypointsTab();
    private final AboutTab aboutTab = new AboutTab();

    private final BlockPicker blockPicker = new BlockPicker();
    private final InventoryPicker inventoryPicker = new InventoryPicker(0, 0, 10, 10);
    private final RecipePicker recipePicker = new RecipePicker();
    private final SoundPicker soundPicker = new SoundPicker(0, 0, 10, 10);
    private final NamePrompt namePrompt = new NamePrompt();
    private final TrainingScreen trainingScreen = new TrainingScreen();
    private final MascotWidget mascot = new MascotWidget();

    private TabNavigationBar navBar;

    /**
     * Where the pointer is said to be while a popup covers the panel. Far enough outside any
     * widget that every bounds check answers no, rather than a flag each of them would have to
     * remember to consult.
     */
    private static final int POINTER_AWAY = -10_000;

    public LuneScreen() {
        super(Component.literal(Lang.get("lune.gui.lune.title")));
    }

    /** The panel honours both the layout preference and the player's text size. */
    @Override
    protected int scaleFor(Window window) {
        return UiScale.menuScaleWithTextSize(window);
    }

    @Override
    protected void init() {
        applyMenuScale();
        // Register first for pointer priority; rendering is manual so Lune still appears above tabs.
        addWidget(mascot);
        navBar = addRenderableWidget(NavBars.build(tabManager, this.width,
                mainTab, tasksTab, waypointsTab, configTab, aboutTab));
        mainTab.setTaskEditorOpener(this::openTaskEditor);
        navBar.selectTab(0, false);
        addRenderableWidget(blockPicker);
        addRenderableWidget(inventoryPicker);
        addRenderableWidget(recipePicker);
        addRenderableWidget(soundPicker);
        addRenderableWidget(namePrompt);
        addRenderableWidget(trainingScreen);
        tasksTab.setBlockPicker(blockPicker);
        tasksTab.setInventoryPicker(inventoryPicker);
        tasksTab.setRecipePicker(recipePicker);
        tasksTab.setSoundPicker(soundPicker);
        tasksTab.setNamePrompt(namePrompt);
        tasksTab.setTrainingScreen(trainingScreen);
        repositionElements();
    }

    private void openTaskEditor(com.etka.lune.task.TaskGraph task, boolean focusName) {
        navBar.selectTab(1, false);
        tasksTab.openTask(task, focusName);
    }

    @Override
    protected void repositionElements() {
        applyMenuScale();
        if (navBar == null) {
            return;
        }
        NavBars.resize(navBar, this.width);
        int top = navBar.getRectangle().bottom();
        ScreenRectangle contentArea = new ScreenRectangle(0, top, this.width, this.height - top);
        tabManager.setTabArea(contentArea);
        mascot.setScreenArea(contentArea);
    }

    @Override
    protected void scaledBackground(GuiGraphicsExtractor extractor) {
        if (tabManager.getCurrentTab() instanceof LuneTab tab) {
            tab.extractTabBackground(extractor);
        }
    }

    @Override
    protected void scaledRender(GuiGraphicsExtractor extractor, int menuMouseX, int menuMouseY,
                                float partialTick) {
        // A popup owns the pointer. Everything under it is drawn as though the mouse were nowhere,
        // because hover is recomputed from the coordinates every frame: hand the panel behind the
        // real ones and its rows light up under the popup, and its tooltips draw on top of it.
        boolean covered = popupOpen();
        int behindX = covered ? POINTER_AWAY : menuMouseX;
        int behindY = covered ? POINTER_AWAY : menuMouseY;
        super.scaledRender(extractor, behindX, behindY, partialTick);
        if (tabManager.getCurrentTab() instanceof LuneTab tab) {
            tab.extractTabRenderState(extractor, behindX, behindY, partialTick);
        }
        mascot.renderOverlay(extractor, behindX, behindY, partialTick);
        if (blockPicker.isOpen()) {
            blockPicker.render(extractor, menuMouseX, menuMouseY, partialTick);
        }
        if (inventoryPicker.isOpen()) {
            inventoryPicker.render(extractor, menuMouseX, menuMouseY, partialTick);
        }
        if (soundPicker.isOpen()) {
            soundPicker.render(extractor, menuMouseX, menuMouseY, partialTick);
        }
        if (recipePicker.isOpen()) {
            recipePicker.render(extractor, menuMouseX, menuMouseY, partialTick);
        }
        if (namePrompt.isOpen()) {
            namePrompt.render(extractor, menuMouseX, menuMouseY, partialTick);
        }
        // Last, so the course map covers the tab and the mascot rather than sharing the screen
        // with them. It never coexists with a picker: opening one closes the panel it was opened
        // from.
        if (trainingScreen.isOpen()) {
            trainingScreen.render(extractor, menuMouseX, menuMouseY, partialTick);
        }
    }

    @Override
    public void tick() {
        super.tick();
        // Picks up a change to the Config tab's menu size or text size without waiting for the
        // screen to reopen - both are edited live, a tab away from the panel they resize.
        if (scaleIsStale()) {
            repositionElements();
        }
        if (tabManager.getCurrentTab() instanceof LuneTab tab) {
            tab.tick();
        }
        MascotAdvisor.Surface surface = tabManager.getCurrentTab() == tasksTab
                ? tasksTab.inTraining() ? MascotAdvisor.Surface.TRAINING : MascotAdvisor.Surface.TASKS
                : tabManager.getCurrentTab() == waypointsTab
                ? MascotAdvisor.Surface.WAYPOINTS
                : tabManager.getCurrentTab() == configTab
                ? MascotAdvisor.Surface.CONFIG
                : tabManager.getCurrentTab() == aboutTab
                ? MascotAdvisor.Surface.ABOUT
                : MascotAdvisor.Surface.MAIN;
        mascot.setSurface(surface);
        mascot.setTrainingLine(tasksTab.trainingLine());
        mascot.setTrainingHelp(surface == MascotAdvisor.Surface.TRAINING
                        && tasksTab.trainingHelpVisible() ? tasksTab::showHint : null,
                surface == MascotAdvisor.Surface.TRAINING
                        && tasksTab.trainingHelpVisible() ? tasksTab::showAnswer : null);
        mascot.tick();
    }

    /** The bot keeps running while the panel is open, so this must not pause a singleplayer world. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** True while one of the modal popups is up and owns the pointer. */
    private boolean popupOpen() {
        return blockPicker.isOpen() || inventoryPicker.isOpen() || recipePicker.isOpen()
                || soundPicker.isOpen() || namePrompt.isOpen() || trainingScreen.isOpen();
    }

    /** Settings and tasks are edited live; persisting on close avoids writing every tick. */
    @Override
    public void onClose() {
        configTab.save();
        tasksTab.save();
        super.onClose();
    }

    /**
     * Every mouse event arrives already converted into the panel's own pixels by
     * {@link ScaledScreen}, so widgets hit-test against the coordinates they were laid out in.
     * What is left here is routing: a popup that is open owns the pointer.
     */
    @Override
    protected boolean scaledMouseClicked(MouseButtonEvent menuEvent, boolean doubleClick) {
        if (trainingScreen.isOpen()) {
            trainingScreen.handleScreenMouseClick(menuEvent.x(), menuEvent.y(), menuEvent.button());
            return true;
        }
        if (blockPicker.isOpen()) {
            blockPicker.handleScreenMouseClick(menuEvent.x(), menuEvent.y(), menuEvent.button());
            return true;
        }
        if (namePrompt.isOpen()) {
            namePrompt.handleScreenMouseClick(menuEvent.x(), menuEvent.y(), menuEvent.button());
            return true;
        }
        if (recipePicker.isOpen()) {
            recipePicker.handleScreenMouseClick(menuEvent.x(), menuEvent.y(), menuEvent.button());
            return true;
        }
        if (soundPicker.isOpen()) {
            soundPicker.handleScreenMouseClick(menuEvent.x(), menuEvent.y(), menuEvent.button());
            return true;
        }
        // The inventory picker used to rely on ordinary widget dispatch, which only covers clicks
        // inside its own rectangle - so a click beside it reached the panel it was covering.
        if (inventoryPicker.isOpen()) {
            if (inventoryPicker.isMouseOver(menuEvent.x(), menuEvent.y())) {
                inventoryPicker.onClick(menuEvent, doubleClick);
            } else {
                inventoryPicker.close();
            }
            return true;
        }
        return super.scaledMouseClicked(menuEvent, doubleClick);
    }

    @Override
    protected boolean scaledMouseReleased(MouseButtonEvent menuEvent) {
        // The recipe grid is filled by dragging, so it needs the other half of the click.
        if (recipePicker.isOpen()) {
            recipePicker.handleScreenMouseRelease(menuEvent.x(), menuEvent.y(), menuEvent.button());
            return true;
        }
        return super.scaledMouseReleased(menuEvent);
    }

    @Override
    protected boolean scaledMouseScrolled(double menuMouseX, double menuMouseY, double deltaX,
                                          double deltaY) {
        if (trainingScreen.isOpen()) {
            return trainingScreen.mouseScrolled(menuMouseX, menuMouseY, deltaX, deltaY);
        }
        if (blockPicker.isOpen()) {
            return blockPicker.mouseScrolled(menuMouseX, menuMouseY, deltaX, deltaY);
        }
        if (recipePicker.isOpen()) {
            return recipePicker.mouseScrolled(menuMouseX, menuMouseY, deltaX, deltaY);
        }
        if (inventoryPicker.isOpen()) {
            return inventoryPicker.mouseScrolled(menuMouseX, menuMouseY, deltaX, deltaY);
        }
        return super.scaledMouseScrolled(menuMouseX, menuMouseY, deltaX, deltaY);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (namePrompt.isOpen()) {
            namePrompt.handleScreenCharTyped(event.codepoint());
            return true;
        }
        if (blockPicker.isOpen()) {
            blockPicker.handleScreenCharTyped(event.codepoint());
            return true;
        }
        if (recipePicker.isOpen()) {
            recipePicker.handleScreenCharTyped(event.codepoint());
            return true;
        }
        if (soundPicker.isOpen()) {
            soundPicker.handleScreenCharTyped(event.codepoint());
            return true;
        }
        return super.charTyped(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (trainingScreen.isOpen()) {
            trainingScreen.handleScreenKeyPressed(event.key(), 0, event.modifiers());
            return true;
        }
        if (namePrompt.isOpen()) {
            namePrompt.handleScreenKeyPressed(event.key(), 0, event.modifiers());
            return true;
        }
        if (blockPicker.isOpen()) {
            blockPicker.handleScreenKeyPressed(event.key(), 0, event.modifiers());
            return true;
        }
        if (soundPicker.isOpen()) {
            soundPicker.handleScreenKeyPressed(event.key(), 0, event.modifiers());
            return true;
        }
        if (recipePicker.isOpen()) {
            recipePicker.handleScreenKeyPressed(event.key(), 0, event.modifiers());
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
