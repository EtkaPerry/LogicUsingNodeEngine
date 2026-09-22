package com.etka.lune.client.gui.tab;

import com.etka.lune.bot.BotEngine;
import com.etka.lune.bot.path.Goal;
import com.etka.lune.bot.path.Goals;
import com.etka.lune.bot.task.GotoTask;
import com.etka.lune.client.gui.LuneScreen;
import com.etka.lune.client.gui.LuneTab;
import com.etka.lune.client.gui.widget.GuiIcons;
import com.etka.lune.client.gui.widget.ListPanel;
import com.etka.lune.compat.Screens;
import com.etka.lune.util.Coordinates;
import com.etka.lune.util.Lang;
import com.etka.lune.waypoint.Waypoint;
import com.etka.lune.waypoint.WaypointStore;
import com.etka.lune.waypoint.external.ExternalWaypoint;
import com.etka.lune.waypoint.external.ExternalWaypointSource;
import com.etka.lune.waypoint.external.ExternalWaypointSources;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Saved locations: name them here, then refer to them by name from the Tasks palette instead of
 * retyping coordinates.
 * <p>
 * Leaving the coordinate box empty saves wherever you're standing; filling it in saves a spot you
 * haven't been to yet.
 * <p>
 * When Waystones, JourneyMap or Xaero's Minimap is installed, a row of buttons above the panes
 * switches the list to that mod's places, nearest first, each of which can be walked to or copied
 * into Lune's own list. The other mods are only ever read; see
 * {@link com.etka.lune.waypoint.external.ExternalWaypointSource}.
 */
public class WaypointsTab extends LuneTab {

    private static final int MARGIN = 10;
    private static final int LIST_WIDTH_FRACTION = 2;
    private static final int GAP = 8;
    /** The form is the working half; the list gives up its share of the width before the form does. */
    private static final int MIN_LIST = 90;
    private static final int MIN_FORM = 150;
    private static final int BUTTON_W = 64;
    private static final int BUTTON_H = 20;
    private static final int BUTTON_GAP = 4;
    /** Bottom of the last explanatory line, measured from the top of the form. */
    private static final int NOTES_BOTTOM = 140;
    /** The row of source buttons above the panes, only there when another mod has a list to show. */
    private static final int TOOLBAR_H = 20;
    private static final int TOOLBAR_GAP = 6;
    private static final int SOURCE_BUTTON_MAX = 110;
    /** How often another mod's list is read again while it is on screen. */
    private static final int REFRESH_TICKS = 20;

    private final ListPanel<Waypoint> savedList;
    private final ListPanel<ExternalWaypoint> importList;
    private final Button savedButton;
    private final Map<ExternalWaypointSource, Button> sourceButtons = new LinkedHashMap<>();
    private final EditBox nameBox;
    private final EditBox coordBox;
    private final Button saveButton;
    private final Button deleteButton;
    private final Button goButton;
    private final Button addButton;
    private final Button goToItButton;

    /** The mod whose list is on screen, or null for Lune's own. */
    private ExternalWaypointSource showing;
    private List<ExternalWaypoint> imported = List.of();
    private List<Waypoint> saved = List.of();
    /** Buttons on the source row, counting the one for Lune's own list; zero hides the row. */
    private int sourcesShown;
    private int ticks;

    public WaypointsTab() {
        super(Component.literal(Lang.get("lune.gui.waypoints.title")));

        savedList = add(new ListPanel<>(0, 0, 10, 10, Waypoint::describe, this::onSelect));
        importList = add(new ListPanel<>(0, 0, 10, 10, this::describe, this::onSelectImport));
        importList.setActions(List.of(
                new ListPanel.RowAction<>(GuiIcons.Icon.ADD,
                        Lang.get("lune.gui.waypoints.add_to_waypoints"),
                        waypoint -> addToWaypoints(waypoint, waypoint.name()),
                        waypoint -> savedAs(waypoint) == null),
                new ListPanel.RowAction<>(GuiIcons.Icon.PLAY,
                        Lang.get("lune.gui.waypoints.go_to_it"), this::goTo,
                        waypoint -> waypoint.isIn(WaypointStore.currentDimension()))));
        refresh();

        savedButton = add(Button.builder(Component.literal(Lang.get("lune.gui.waypoints.source.saved")),
                b -> show(null)).size(BUTTON_W, TOOLBAR_H).build());
        for (ExternalWaypointSource source : ExternalWaypointSources.all()) {
            sourceButtons.put(source, add(Button.builder(Component.literal(source.label()),
                    b -> show(source)).size(BUTTON_W, TOOLBAR_H).build()));
        }

        nameBox = add(new EditBox(Minecraft.getInstance().font, 0, 0, 120, 18, Component.literal(Lang.get("lune.gui.tasks.name"))));
        nameBox.setHint(Component.literal(Lang.get("lune.gui.waypoints.name_2")));
        nameBox.setMaxLength(ExternalWaypointSources.NAME_LIMIT);

        coordBox = add(new EditBox(Minecraft.getInstance().font, 0, 0, 120, 18,
                Component.literal(Lang.get("lune.param.target.label"))));
        coordBox.setHint(Component.literal(Lang.get("lune.gui.waypoints.x_y_z_blank_here")));
        coordBox.setMaxLength(48);

        saveButton = add(Button.builder(Component.literal(Lang.get("lune.gui.waypoints.save")), b -> save()).size(64, 20).build());
        deleteButton = add(Button.builder(Component.literal(Lang.get("lune.gui.tasks.delete_2")), b -> delete()).size(64, 20).build());
        goButton = add(Button.builder(Component.literal(Lang.get("lune.gui.waypoints.go")), b -> go()).size(64, 20).build());
        addButton = add(Button.builder(Component.literal(Lang.get("lune.gui.waypoints.add_to_waypoints")),
                b -> addSelected()).size(64, 20).build());
        goToItButton = add(Button.builder(Component.literal(Lang.get("lune.gui.waypoints.go_to_it")),
                b -> goToSelected()).size(64, 20).build());
        applyMode();
    }

    // --- Lune's own list ---------------------------------------------------------------------

    private void onSelect(Waypoint waypoint) {
        nameBox.setValue(waypoint.name());
        coordBox.setValue(Coordinates.format(waypoint.pos()));
    }

    private void save() {
        String name = nameBox.getValue().trim();
        if (name.isEmpty()) {
            return;
        }
        BlockPos typed = Coordinates.parse(coordBox.getValue());
        if (typed != null) {
            WaypointStore.get().put(Waypoint.of(name, typed, WaypointStore.currentDimension()));
        } else {
            WaypointStore.get().captureHere(name);
        }
        refresh();
    }

    private void delete() {
        Waypoint selected = savedList.getSelected();
        if (selected != null) {
            WaypointStore.get().remove(selected.name());
            refresh();
        }
    }

    private void go() {
        Waypoint selected = savedList.getSelected();
        if (selected == null) {
            return;
        }
        if (!WaypointStore.isInCurrentDimension(selected)) {
            overlay(Lang.get("lune.gui.waypoints.lune_waypoint_another_dimension"));
            return;
        }
        BotEngine.get().runNow(new GotoTask(new Goals.Near(selected.pos(), 2), true, false));
        Screens.open(Minecraft.getInstance(), null);
    }

    private void refresh() {
        saved = WaypointStore.get().all();
        savedList.setItems(saved);
    }

    // --- another mod's list ------------------------------------------------------------------

    private void show(ExternalWaypointSource source) {
        showing = source;
        nameBox.setValue("");
        coordBox.setValue("");
        if (source == null) {
            refresh();
        } else {
            imported = List.of();
            importList.setItems(imported);
            importList.setSelected(null);
            reread();
        }
        applyMode();
    }

    /**
     * Reads the mod's list again. It is only re-sorted when something appeared or went away, so
     * the rows keep still under the pointer while the bot walks and the distances change; the
     * distance on each row is worked out fresh every frame regardless.
     */
    private void reread() {
        List<ExternalWaypoint> fresh = showing.list();
        if (new HashSet<>(fresh).equals(new HashSet<>(imported))) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        imported = mc.player == null ? List.copyOf(fresh)
                : ExternalWaypointSources.nearestFirst(fresh, mc.player.position(),
                        WaypointStore.currentDimension());
        importList.setItems(imported);
    }

    private void applyMode() {
        boolean importing = showing != null;
        savedList.visible = !importing;
        savedList.active = !importing;
        importList.visible = importing;
        importList.active = importing;
        saveButton.visible = !importing;
        deleteButton.visible = !importing;
        goButton.visible = !importing;
        addButton.visible = importing;
        goToItButton.visible = importing;
        // A copied entry keeps the other mod's coordinates; only the name is the player's to choose.
        coordBox.setEditable(!importing);
        // The pressed button is the one that cannot be pressed again.
        savedButton.active = importing;
        sourceButtons.forEach((source, button) -> button.active = source != showing);
    }

    private String describe(ExternalWaypoint waypoint) {
        if (!waypoint.isIn(WaypointStore.currentDimension())) {
            return waypoint.name() + "  (" + shortDimension(waypoint.dimension()) + ")";
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return waypoint.name();
        }
        long blocks = Math.round(waypoint.distanceFrom(mc.player.position()));
        return waypoint.name() + "  " + Lang.get("lune.gui.waypoints.distance_blocks", blocks);
    }

    private static String shortDimension(String dimension) {
        int colon = dimension.indexOf(':');
        return colon < 0 ? dimension : dimension.substring(colon + 1);
    }

    private void onSelectImport(ExternalWaypoint waypoint) {
        nameBox.setValue(ExternalWaypointSources.fitName(waypoint.name()));
        coordBox.setValue(Coordinates.format(resolvedPos(waypoint)));
    }

    private void addSelected() {
        ExternalWaypoint selected = importList.getSelected();
        if (selected == null) {
            return;
        }
        String typed = nameBox.getValue().strip();
        addToWaypoints(selected, typed.isEmpty() ? selected.name() : typed);
    }

    private void addToWaypoints(ExternalWaypoint waypoint, String wanted) {
        Waypoint already = savedAs(waypoint);
        if (already != null) {
            overlay(Lang.get("lune.gui.waypoints.lune_already_saved", already.name()));
            return;
        }
        WaypointStore store = WaypointStore.get();
        String name = ExternalWaypointSources.uniqueName(wanted, store.names());
        store.put(Waypoint.of(name, resolvedPos(waypoint), waypoint.dimension()));
        refresh();
        overlay(Lang.get("lune.gui.waypoints.lune_added", name));
    }

    /** The saved waypoint already standing on this spot, if there is one. */
    private Waypoint savedAs(ExternalWaypoint waypoint) {
        for (Waypoint candidate : saved) {
            if (candidate.dimension().equals(waypoint.dimension())
                    && candidate.x() == waypoint.x() && candidate.z() == waypoint.z()
                    && (!waypoint.hasY() || candidate.y() == waypoint.y())) {
                return candidate;
            }
        }
        return null;
    }

    /** Where an entry is, with the ground's height chosen for one recorded without a height. */
    private static BlockPos resolvedPos(ExternalWaypoint waypoint) {
        if (waypoint.hasY()) {
            return waypoint.pos();
        }
        Minecraft mc = Minecraft.getInstance();
        int fallback = mc.player == null ? waypoint.y() : mc.player.blockPosition().getY();
        return ExternalWaypointSources.groundPos(waypoint.x(), waypoint.z(), fallback);
    }

    private void goToSelected() {
        ExternalWaypoint selected = importList.getSelected();
        if (selected != null) {
            goTo(selected);
        }
    }

    private void goTo(ExternalWaypoint waypoint) {
        if (!waypoint.isIn(WaypointStore.currentDimension())) {
            overlay(Lang.get("lune.gui.waypoints.lune_waypoint_another_dimension"));
            return;
        }
        // A column without a height is a column: arrive anywhere in it, not at a made-up floor.
        Goal goal = waypoint.hasY()
                ? new Goals.Near(waypoint.pos(), 2)
                : new Goals.NearXZ(waypoint.x(), waypoint.z(), 2);
        BotEngine.get().runNow(new GotoTask(goal, true, false));
        Screens.open(Minecraft.getInstance(), null);
    }

    private static void overlay(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendOverlayMessage(Component.literal(message));
        }
    }

    @Override
    public void tick() {
        if (showing == null) {
            return;
        }
        if (++ticks % REFRESH_TICKS == 0) {
            reread();
        }
    }

    // --- layout and drawing ------------------------------------------------------------------

    /**
     * The split between the list and the edit form, derived once for the widgets, the panel
     * behind the list and the labels drawn beside the boxes. The source row above them only
     * exists while another mod has a list to offer, and everything else moves down to make room.
     */
    private record Frame(int left, int top, int height, int listWidth, int formX, int formWidth,
                         int buttonY, int buttonWidth, boolean showNotes, int toolbarY,
                         int sourceWidth) {

        static Frame of(ScreenRectangle area, int sources) {
            int content = Math.max(60, area.width() - MARGIN * 2);
            int top = area.top() + MARGIN;
            int toolbarY = -1;
            int sourceWidth = 0;
            if (sources > 0) {
                toolbarY = top;
                sourceWidth = Math.clamp((content - BUTTON_GAP * (sources - 1)) / sources,
                        28, SOURCE_BUTTON_MAX);
                top += TOOLBAR_H + TOOLBAR_GAP;
            }
            int minList = Math.min(MIN_LIST, content);
            int maxList = Math.max(minList, content - GAP - MIN_FORM);
            int listWidth = Math.clamp(content / LIST_WIDTH_FRACTION, minList, maxList);
            int formWidth = Math.max(60, content - listWidth - GAP);
            return new Frame(area.left() + MARGIN, top, Math.max(40, area.bottom() - MARGIN - top),
                    listWidth, area.left() + MARGIN + listWidth + GAP, formWidth, top + 92,
                    Math.min(BUTTON_W, Math.max(28, (formWidth - BUTTON_GAP * 2) / 3)),
                    top + NOTES_BOTTOM <= area.bottom(), toolbarY, sourceWidth);
        }
    }

    @Override
    protected void layout(ScreenRectangle area) {
        List<ExternalWaypointSource> sources = ExternalWaypointSources.available();
        sourcesShown = sources.isEmpty() ? 0 : sources.size() + 1;
        Frame frame = Frame.of(area, sourcesShown);

        savedButton.visible = sourcesShown > 0;
        int sourceX = frame.left();
        if (sourcesShown > 0) {
            place(savedButton, sourceX, frame.toolbarY(), frame.sourceWidth());
            sourceX += frame.sourceWidth() + BUTTON_GAP;
        }
        for (Map.Entry<ExternalWaypointSource, Button> entry : sourceButtons.entrySet()) {
            boolean shown = sources.contains(entry.getKey());
            entry.getValue().visible = shown;
            if (shown) {
                place(entry.getValue(), sourceX, frame.toolbarY(), frame.sourceWidth());
                sourceX += frame.sourceWidth() + BUTTON_GAP;
            }
        }

        savedList.setPosition(frame.left(), frame.top());
        savedList.setSize(frame.listWidth(), frame.height());
        importList.setPosition(frame.left(), frame.top());
        importList.setSize(frame.listWidth(), frame.height());

        nameBox.setPosition(frame.formX(), frame.top() + 18);
        nameBox.setWidth(frame.formWidth());
        coordBox.setPosition(frame.formX(), frame.top() + 58);
        coordBox.setWidth(frame.formWidth());

        int step = frame.buttonWidth() + BUTTON_GAP;
        place(saveButton, frame.formX(), frame.buttonY(), frame.buttonWidth());
        place(deleteButton, frame.formX() + step, frame.buttonY(), frame.buttonWidth());
        place(goButton, frame.formX() + step * 2, frame.buttonY(), frame.buttonWidth());
        // Two buttons on the row the other mode fills with three, so each gets the extra room.
        int wide = Math.max(frame.buttonWidth(),
                Math.min(BUTTON_W * 2, (frame.formWidth() - BUTTON_GAP) / 2));
        place(addButton, frame.formX(), frame.buttonY(), wide);
        place(goToItButton, frame.formX() + wide + BUTTON_GAP, frame.buttonY(), wide);
    }

    private static void place(Button button, int x, int y, int width) {
        button.setPosition(x, y);
        button.setSize(width, BUTTON_H);
    }

    @Override
    public void extractTabBackground(GuiGraphicsExtractor extractor) {
        Frame frame = Frame.of(area, sourcesShown);
        LuneScreen.panel(extractor, frame.left(), frame.top(), frame.listWidth(), frame.height());
    }

    @Override
    public void extractTabRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        Frame frame = Frame.of(area, sourcesShown);
        var text = extractor.textRenderer();

        text.accept(frame.formX(), frame.top() + 6,
                Component.literal(Lang.get("lune.gui.tasks.name")).withColor(LuneScreen.TEXT));
        text.accept(frame.formX(), frame.top() + 46,
                Component.literal(Lang.get("lune.param.target.label")).withColor(LuneScreen.TEXT));
        if (showing != null && imported.isEmpty()) {
            String empty = Minecraft.getInstance().font.plainSubstrByWidth(
                    Lang.get("lune.gui.waypoints.external_empty"), frame.listWidth() - 12, false);
            text.accept(frame.left() + 6, frame.top() + 6,
                    Component.literal(empty).withColor(LuneScreen.TEXT_DIM));
        }
        if (frame.showNotes()) {
            String first = showing == null
                    ? Lang.get("lune.gui.waypoints.saved", WaypointStore.currentDimension())
                    : Lang.get("lune.gui.waypoints.external_hint");
            text.accept(frame.formX(), frame.top() + 118,
                    Component.literal(first).withColor(LuneScreen.TEXT_DIM));
            text.accept(frame.formX(), frame.top() + 131,
                    Component.literal(Lang.get("lune.gui.waypoints.use_these_by_name_from_task_go_waypoint"))
                            .withColor(LuneScreen.TEXT_DIM));
        }
        drawActionTooltip(extractor, mouseX, mouseY);
    }

    /** Names the row icon under the pointer, the same way the Main tab does for its task list. */
    private void drawActionTooltip(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
        String label = importList.hoveredActionLabel(mouseX, mouseY);
        if (label == null) {
            return;
        }
        var font = Minecraft.getInstance().font;
        int width = font.width(label) + 10;
        int maxX = Math.max(area.left(), area.right() - width);
        int maxY = Math.max(area.top(), area.bottom() - 12);
        int x = Math.clamp(mouseX + 8, area.left(), maxX);
        int y = Math.clamp(mouseY - 16, area.top(), maxY);
        extractor.fill(x, y, x + width, y + 12, 0xF0101014);
        extractor.fill(x, y, x + width, y + 1, LuneScreen.ACCENT);
        extractor.textRenderer().accept(x + 5, y + 2,
                Component.literal(label).withColor(LuneScreen.TEXT));
    }
}
