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
import com.etka.lune.client.gui.widget.GamesSection;
import com.etka.lune.client.gui.widget.LuneMenu;
import com.etka.lune.client.gui.widget.NamePrompt;
import com.etka.lune.client.gui.widget.RecipePicker;
import com.etka.lune.client.gui.widget.SoundPicker;
import com.etka.lune.client.gui.widget.InventoryPicker;
import com.etka.lune.client.gui.widget.TrainingSection;
import com.etka.lune.training.TrainingLesson;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Lune's control panel: a vanilla tab bar across the top, and one full-width content area below
 * it that the active {@link LuneTab} owns.
 * <p>
 * The {@link LuneMenu} button shares the bar with the tabs, at its right end, and opens Lune's
 * corner over the tab below. Whatever is not a tab's own work - the training course now, games to
 * play while Lune works later - has a page there, so the tab bar stays five tabs long and no tab
 * carries a button that is not about it.
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
    private final LuneMenu menu = new LuneMenu();
    private final TrainingSection trainingSection = new TrainingSection(this::openLesson);
    private final GamesSection gamesSection = new GamesSection();
    private final MascotWidget mascot = new MascotWidget();

    private TabNavigationBar navBar;

    /**
     * Where the pointer is said to be while a popup covers the panel. Far enough outside any
     * widget that every bounds check answers no, rather than a flag each of them would have to
     * remember to consult.
     */
    private static final int POINTER_AWAY = -10_000;
    /** Clear space kept either side of the menu button: from the screen edge, and from the last tab. */
    private static final int MENU_MARGIN = 6;
    /** The narrowest the tab strip is squeezed to make room for the button, on a very small screen. */
    private static final int MIN_TAB_BAR = 200;

    /** The widget a middle or right press landed on; it is owed the drag and release after it. */
    private GuiEventListener otherButtonTarget;

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
        // Registered, though it draws itself, so Tab reaches it after the tabs like any button.
        addWidget(menu);
        mainTab.setTaskEditorOpener(this::openTaskEditor);
        navBar.selectTab(0, false);
        addRenderableWidget(blockPicker);
        addRenderableWidget(inventoryPicker);
        addRenderableWidget(recipePicker);
        addRenderableWidget(soundPicker);
        addRenderableWidget(namePrompt);
        tasksTab.setBlockPicker(blockPicker);
        tasksTab.setInventoryPicker(inventoryPicker);
        tasksTab.setRecipePicker(recipePicker);
        tasksTab.setSoundPicker(soundPicker);
        tasksTab.setNamePrompt(namePrompt);
        tasksTab.setCourseMapOpener(() -> menu.open(trainingSection));
        // The pages of Lune's corner, in the order the side menu lists them.
        menu.setSections(List.of(trainingSection, gamesSection));
        repositionElements();
    }

    private void openTaskEditor(com.etka.lune.task.TaskGraph task, boolean focusName) {
        navBar.selectTab(1, false);
        tasksTab.openTask(task, focusName);
    }

    /**
     * A lesson chosen in Lune's corner. The corner opens over any tab, and a lesson is always
     * solved in the task editor, so choosing one closes the corner and goes to the Tasks tab.
     */
    private void openLesson(TrainingLesson lesson) {
        menu.close();
        navBar.selectTab(1, false);
        tasksTab.openLesson(lesson);
    }

    @Override
    protected void repositionElements() {
        applyMenuScale();
        if (navBar == null) {
            return;
        }
        layoutTabBar();
        int top = navBar.getRectangle().bottom();
        ScreenRectangle contentArea = new ScreenRectangle(0, top, this.width, this.height - top);
        tabManager.setTabArea(contentArea);
        mascot.setScreenArea(contentArea);
    }

    /**
     * Fits the tabs and the menu button onto one row.
     *
     * <p>Vanilla centres the tabs in at most 400 pixels, and every screen the panel is laid out for
     * is far wider, so the button normally sits in the empty end of the bar with its word beside
     * it and the tabs never move. Only a very small screen runs short: the word goes first, and if
     * the glyph alone still does not fit, the strip the tabs are centred in is narrowed until it
     * does. Tabs a few pixels off centre are a better trade than a button drawn over the last
     * one.</p>
     */
    private void layoutTabBar() {
        int barWidth = this.width;
        NavBars.resize(navBar, barWidth);
        boolean compact = this.width - navBar.getRectangle().right()
                < menu.buttonWidth(false) + MENU_MARGIN * 2;
        if (compact) {
            int room = menu.buttonWidth(true) + MENU_MARGIN * 2;
            while (barWidth > MIN_TAB_BAR && this.width - navBar.getRectangle().right() < room) {
                barWidth -= 2;
                NavBars.resize(navBar, barWidth);
            }
        }
        menu.place(this.width - MENU_MARGIN, navBar.getRectangle().bottom(), compact,
                this.width, this.height);
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
        // Lune's corner is the one popup that leaves the tab bar alone, so the tabs still light up
        // under the pointer while it is open: pressing one is how the player leaves it.
        boolean covered = popupOpen()
                && !(menu.isOpen() && menuMouseY < navBar.getRectangle().bottom());
        int behindX = covered ? POINTER_AWAY : menuMouseX;
        int behindY = covered ? POINTER_AWAY : menuMouseY;
        super.scaledRender(extractor, behindX, behindY, partialTick);
        // Part of the bar, so it is drawn with it and lit only when nothing covers the panel.
        menu.renderButton(extractor, behindX, behindY);
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
        // Last, so the corner covers the tab and the mascot rather than sharing the screen with
        // them. It never coexists with a picker: it only opens when nothing else is up.
        menu.renderCorner(extractor, menuMouseX, menuMouseY);
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
                || soundPicker.isOpen() || namePrompt.isOpen() || menu.isOpen();
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
        // A click in the tab bar closes the corner and comes back unhandled, and carries on to
        // the tab it landed on.
        if (menu.isOpen() && menu.handleClick(menuEvent.x(), menuEvent.y(), menuEvent.button())) {
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
        // Only reached with nothing open, so the button cannot open the menu over a picker.
        if (menu.handleClick(menuEvent.x(), menuEvent.y(), menuEvent.button())) {
            return true;
        }
        if (menuEvent.button() != InputConstants.MOUSE_BUTTON_LEFT) {
            otherButtonTarget = getChildAt(menuEvent.x(), menuEvent.y()).orElse(null);
        }
        return super.scaledMouseClicked(menuEvent, doubleClick);
    }

    @Override
    protected boolean scaledMouseReleased(MouseButtonEvent menuEvent) {
        // The corner hears every release, so a drag it started ends there; the rest carries on
        // as before, which with the corner open finds nothing pressed below it.
        if (menu.isOpen()) {
            menu.handleRelease(menuEvent.x(), menuEvent.y(), menuEvent.button());
        }
        GuiEventListener pressedOn = null;
        if (menuEvent.button() != InputConstants.MOUSE_BUTTON_LEFT) {
            pressedOn = otherButtonTarget;
            otherButtonTarget = null;
        }
        // The recipe grid is filled by dragging, so it needs the other half of the click.
        if (recipePicker.isOpen()) {
            recipePicker.handleScreenMouseRelease(menuEvent.x(), menuEvent.y(), menuEvent.button());
            return true;
        }
        if (pressedOn != null && pressedOn.mouseReleased(menuEvent)) {
            return true;
        }
        return super.scaledMouseReleased(menuEvent);
    }

    /**
     * Hands a middle or right drag to the widget that button was pressed on.
     *
     * <p>Vanilla only carries a left press on into a drag and a release; every other button is
     * forgotten the moment it lands. That is why a middle-button pan on the task canvas started
     * and never moved. Nothing else on the panel drags with those buttons, and a widget that does
     * not want the drag says so by returning false, which is what vanilla answered anyway.</p>
     */
    @Override
    protected boolean scaledMouseDragged(MouseButtonEvent menuEvent, double dragX, double dragY) {
        // Open, the corner owns the pointer below the bar, drags with every button included.
        if (menu.isOpen()) {
            menu.handleDrag(menuEvent.x(), menuEvent.y(), menuEvent.button());
            return true;
        }
        if (menuEvent.button() != InputConstants.MOUSE_BUTTON_LEFT) {
            return otherButtonTarget != null
                    && otherButtonTarget.mouseDragged(menuEvent, dragX, dragY);
        }
        return super.scaledMouseDragged(menuEvent, dragX, dragY);
    }

    @Override
    protected boolean scaledMouseScrolled(double menuMouseX, double menuMouseY, double deltaX,
                                          double deltaY) {
        if (menu.isOpen()) {
            // The page scrolls if it has anything to scroll; the canvas under it never does.
            menu.handleScroll(menuMouseX, menuMouseY, deltaY != 0 ? deltaY : deltaX);
            return true;
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
        if (menu.isOpen()) {
            // A name box left focused behind the corner would otherwise take the typing.
            return true;
        }
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
        if (menu.isOpen()) {
            // Escape closes the corner, not the panel under it.
            menu.handleKey(event.key());
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
