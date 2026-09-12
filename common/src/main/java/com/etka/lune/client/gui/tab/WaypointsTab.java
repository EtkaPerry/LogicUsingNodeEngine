package com.etka.lune.client.gui.tab;

import com.etka.lune.util.Lang;
import com.etka.lune.bot.BotEngine;
import com.etka.lune.bot.path.Goals;
import com.etka.lune.bot.task.GotoTask;
import com.etka.lune.client.gui.LuneScreen;
import com.etka.lune.client.gui.LuneTab;
import com.etka.lune.client.gui.widget.ListPanel;
import com.etka.lune.util.Coordinates;
import com.etka.lune.waypoint.Waypoint;
import com.etka.lune.waypoint.WaypointStore;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;

/**
 * Saved locations: name them here, then refer to them by name from the Tasks palette instead of
 * retyping coordinates.
 * <p>
 * Leaving the coordinate box empty saves wherever you're standing; filling it in saves a spot you
 * haven't been to yet.
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

    private final ListPanel<Waypoint> list;
    private final EditBox nameBox;
    private final EditBox coordBox;
    private final Button saveButton;
    private final Button deleteButton;
    private final Button goButton;

    public WaypointsTab() {
        super(Component.literal(Lang.get("lune.gui.waypoints.title")));

        list = add(new ListPanel<>(0, 0, 10, 10, Waypoint::describe, this::onSelect));
        list.setItems(WaypointStore.get().all());

        nameBox = add(new EditBox(Minecraft.getInstance().font, 0, 0, 120, 18, Component.literal(Lang.get("lune.gui.tasks.name"))));
        nameBox.setHint(Component.literal(Lang.get("lune.gui.waypoints.name_2")));
        nameBox.setMaxLength(32);

        coordBox = add(new EditBox(Minecraft.getInstance().font, 0, 0, 120, 18,
                Component.literal(Lang.get("lune.param.target.label"))));
        coordBox.setHint(Component.literal(Lang.get("lune.gui.waypoints.x_y_z_blank_here")));
        coordBox.setMaxLength(48);

        saveButton = add(Button.builder(Component.literal(Lang.get("lune.gui.waypoints.save")), b -> save()).size(64, 20).build());
        deleteButton = add(Button.builder(Component.literal(Lang.get("lune.gui.tasks.delete_2")), b -> delete()).size(64, 20).build());
        goButton = add(Button.builder(Component.literal(Lang.get("lune.gui.waypoints.go")), b -> go()).size(64, 20).build());
    }

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
        Waypoint selected = list.getSelected();
        if (selected != null) {
            WaypointStore.get().remove(selected.name());
            refresh();
        }
    }

    private void go() {
        Waypoint selected = list.getSelected();
        if (selected == null) {
            return;
        }
        if (!WaypointStore.isInCurrentDimension(selected)) {
            Minecraft.getInstance().player.sendOverlayMessage(
                    Component.literal(Lang.get("lune.gui.waypoints.lune_waypoint_another_dimension")));
            return;
        }
        BotEngine.get().runNow(new GotoTask(new Goals.Near(selected.pos(), 2), true, false));
        Minecraft.getInstance().setScreen(null);
    }

    private void refresh() {
        list.setItems(WaypointStore.get().all());
    }

    /**
     * The split between the saved list and the edit form, derived once for the widgets, the panel
     * behind the list and the labels drawn beside the boxes.
     */
    private record Frame(int left, int top, int height, int listWidth, int formX, int formWidth,
                         int buttonY, int buttonWidth, boolean showNotes) {

        static Frame of(ScreenRectangle area) {
            int content = Math.max(60, area.width() - MARGIN * 2);
            int minList = Math.min(MIN_LIST, content);
            int maxList = Math.max(minList, content - GAP - MIN_FORM);
            int listWidth = Math.clamp(content / LIST_WIDTH_FRACTION, minList, maxList);
            int formWidth = Math.max(60, content - listWidth - GAP);
            int top = area.top() + MARGIN;
            return new Frame(area.left() + MARGIN, top, Math.max(40, area.height() - MARGIN * 2),
                    listWidth, area.left() + MARGIN + listWidth + GAP, formWidth, top + 92,
                    Math.min(BUTTON_W, Math.max(28, (formWidth - BUTTON_GAP * 2) / 3)),
                    top + NOTES_BOTTOM <= area.bottom());
        }
    }

    @Override
    protected void layout(ScreenRectangle area) {
        Frame frame = Frame.of(area);
        list.setPosition(frame.left(), frame.top());
        list.setSize(frame.listWidth(), frame.height());

        nameBox.setPosition(frame.formX(), frame.top() + 18);
        nameBox.setWidth(frame.formWidth());
        coordBox.setPosition(frame.formX(), frame.top() + 58);
        coordBox.setWidth(frame.formWidth());

        int step = frame.buttonWidth() + BUTTON_GAP;
        place(saveButton, frame.formX(), frame.buttonY(), frame.buttonWidth());
        place(deleteButton, frame.formX() + step, frame.buttonY(), frame.buttonWidth());
        place(goButton, frame.formX() + step * 2, frame.buttonY(), frame.buttonWidth());
    }

    private static void place(Button button, int x, int y, int width) {
        button.setPosition(x, y);
        button.setSize(width, BUTTON_H);
    }

    @Override
    public void extractTabBackground(GuiGraphicsExtractor extractor) {
        Frame frame = Frame.of(area);
        LuneScreen.panel(extractor, frame.left(), frame.top(), frame.listWidth(), frame.height());
    }

    @Override
    public void extractTabRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        Frame frame = Frame.of(area);
        var text = extractor.textRenderer();

        text.accept(frame.formX(), frame.top() + 6,
                Component.literal(Lang.get("lune.gui.tasks.name")).withColor(LuneScreen.TEXT));
        text.accept(frame.formX(), frame.top() + 46,
                Component.literal(Lang.get("lune.param.target.label")).withColor(LuneScreen.TEXT));
        if (frame.showNotes()) {
            text.accept(frame.formX(), frame.top() + 118,
                    Component.literal(Lang.get("lune.gui.waypoints.saved", WaypointStore.currentDimension()))
                            .withColor(LuneScreen.TEXT_DIM));
            text.accept(frame.formX(), frame.top() + 131,
                    Component.literal(Lang.get("lune.gui.waypoints.use_these_by_name_from_task_go_waypoint"))
                            .withColor(LuneScreen.TEXT_DIM));
        }
    }
}
