package com.etka.lune.client.gui;

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
import com.etka.lune.client.gui.widget.InventoryPicker;
import com.etka.lune.client.gui.widget.TrainingScreen;
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

    /** Scale the menu draws at, and the two conversions derived from it. */
    private int menuScale = 1;
    /** Size of one Lune pixel in game GUI pixels; the transform every draw call goes through. */
    private float menuPixelSize = 1.0F;
    /** Inverse of the above, for turning the game's mouse coordinates into Lune's. */
    private double menuPixelsPerGamePixel = 1.0;

    public LuneScreen() {
        super(Component.literal(Lang.get("lune.gui.lune.title")));
    }

    @Override
    protected void init() {
        applyMenuScale();
        // Register first for pointer priority; rendering is manual so Lune still appears above tabs.
        addWidget(mascot);
        navBar = addRenderableWidget(TabNavigationBar.builder(tabManager, this.width)
                .addTabs(mainTab, tasksTab, waypointsTab, configTab, aboutTab)
                .build());
        mainTab.setTaskEditorOpener(this::openTaskEditor);
        navBar.selectTab(0, false);
        addRenderableWidget(blockPicker);
        addRenderableWidget(inventoryPicker);
        addRenderableWidget(recipePicker);
        addRenderableWidget(namePrompt);
        addRenderableWidget(trainingScreen);
        tasksTab.setBlockPicker(blockPicker);
        tasksTab.setInventoryPicker(inventoryPicker);
        tasksTab.setRecipePicker(recipePicker);
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
        // A popup owns the pointer. Everything under it is drawn as though the mouse were nowhere,
        // because hover is recomputed from the coordinates every frame: hand the panel behind the
        // real ones and its rows light up under the popup, and its tooltips draw on top of it.
        boolean covered = popupOpen();
        int behindX = covered ? POINTER_AWAY : menuMouseX;
        int behindY = covered ? POINTER_AWAY : menuMouseY;
        var pose = extractor.pose();
        pose.pushMatrix();
        pose.scale(menuPixelSize, menuPixelSize);
        super.extractRenderState(extractor, behindX, behindY, partialTick);
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
                || namePrompt.isOpen() || trainingScreen.isOpen();
    }

    /** Settings and tasks are edited live; persisting on close avoids writing every tick. */
    @Override
    public void onClose() {
        configTab.save();
        tasksTab.save();
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
        return super.mouseClicked(menuEvent, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        MouseButtonEvent menuEvent = toMenu(event);
        // The recipe grid is filled by dragging, so it needs the other half of the click.
        if (recipePicker.isOpen()) {
            recipePicker.handleScreenMouseRelease(menuEvent.x(), menuEvent.y(), menuEvent.button());
            return true;
        }
        return super.mouseReleased(menuEvent);
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
        return super.mouseScrolled(menuMouseX, menuMouseY, deltaX, deltaY);
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
