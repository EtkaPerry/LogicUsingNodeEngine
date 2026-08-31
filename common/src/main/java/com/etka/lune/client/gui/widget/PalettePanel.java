package com.etka.lune.client.gui.widget;

import com.etka.lune.bot.command.CommandDef;
import com.etka.lune.bot.command.CommandRegistry;
import com.etka.lune.client.gui.LuneScreen;
import com.etka.lune.task.TaskBlueprints;
import com.etka.lune.task.TaskNode;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A right-hand palette of "puzzle pieces" the player can click to add to the task.
 * <p>
 * Commands are grouped into collapsible folders and blueprints have their own folder. This keeps
 * the palette usable as the command library grows without changing how a node is inserted.
 */
public class PalettePanel extends AbstractWidget {

    private static final int FOLDER_H = 20;
    private static final int TILE_H = 22;
    private static final int GAP = 4;
    private static final int PADDING = 5;
    private static final int TILE_HOVER = 0x30FFFFFF;
    private static final int FOLDER_BG = 0xFF343440;
    private static final int COMMAND_BG = 0xFF1E1E24;
    private static final int BLUEPRINT_BG = 0xFF2A2A35;

    public interface Item {}

    public record Command(String id, String name, String description) implements Item {}
    public record General(String id, String name, String description) implements Item {}
    public record Blueprint(String name, List<TaskNode> nodes) implements Item {}

    private record Section(String name, List<Item> items) {}

    private final List<Section> sections = new ArrayList<>();
    private final List<Section> filteredSections = new ArrayList<>();
    private final Map<String, Boolean> expanded = new LinkedHashMap<>();
    private final Consumer<Item> onSelect;
    /** Called when a tile is dropped outside the palette, with the screen point it landed on. */
    private final DropTarget onDrop;
    private int scroll;
    private String filter = "";
    /** The tile the pointer picked up, kept until the button comes back up. */
    private Item dragging;
    private int dragX;
    private int dragY;
    private boolean dragMoved;

    /** Where a dragged palette tile was released. */
    public interface DropTarget {
        boolean accept(Item item, int screenX, int screenY);
    }

    public PalettePanel(int x, int y, int width, int height, Consumer<Item> onSelect,
                        DropTarget onDrop) {
        super(x, y, width, height, Component.empty());
        this.onSelect = onSelect;
        this.onDrop = onDrop;
        rebuild();
    }

    private void rebuild() {
        sections.clear();
        Map<String, List<Item>> grouped = new LinkedHashMap<>();
        for (CommandDef def : CommandRegistry.all()) {
            grouped.computeIfAbsent(CommandRegistry.categoryFor(def.id()), ignored -> new ArrayList<>())
                    .add(new Command(def.id(), def.name(), def.description()));
        }

        // The first useful folder starts open. The accordion behavior below keeps every other
        // folder closed when the player chooses another one.
        expanded.putIfAbsent("General", true);

        for (String name : List.of("General", "Movement", "Gathering", "Logic & Conditions", "Mining & Building",
                "Combat", "Items & Storage", "Tasks & Missions", "Other")) {
            // Taken out of the map either way. The General folder is written by hand below, and
            // the registry's own General entries are the same three cards - leaving them behind
            // meant the leftovers loop added a second folder with the same name.
            List<Item> fromRegistry = grouped.remove(name);
            List<Item> groupedItems = "General".equals(name)
                    ? List.<Item>of(
                    new General(TaskNode.START_COMMAND, "START",
                            "Explicit task entry; connect it to the first action"),
                    new General(TaskNode.ALWAYS_COMMAND, "Always",
                            "Keep the connected branch powered, every tick"),
                    new General(TaskNode.PULSE_COMMAND, "Pulse",
                            "Send one pulse every few seconds, like a clock"),
                    new General(TaskNode.SIGNAL_RELAY_COMMAND, "Signal Relay",
                            "Forward an incoming pulse through configurable numbered outputs"),
                    new General(TaskNode.OBSERVER_COMMAND, "Observer",
                            "Watch a card and pulse whenever its power changes"),
                    new General(TaskNode.BUTTON_COMMAND, "Button",
                            "Send one manual pulse from the editor"),
                    new General(TaskNode.END_COMMAND, "End",
                            "Consume a pulse and finish that circuit"))
                    : fromRegistry;
            if (groupedItems != null && !groupedItems.isEmpty()) {
                sections.add(new Section(name, groupedItems));
            }
        }

        List<Item> blueprints = new ArrayList<>();
        for (var entry : TaskBlueprints.all().entrySet()) {
            List<TaskNode> copies = new ArrayList<>();
            for (TaskNode node : entry.getValue().nodes) {
                copies.add(node.copy());
            }
            blueprints.add(new Blueprint(entry.getKey(), copies));
        }
        if (!blueprints.isEmpty()) {
            sections.add(new Section("Blueprints", blueprints));
            expanded.putIfAbsent("Blueprints", false);
        }

        for (Map.Entry<String, List<Item>> entry : grouped.entrySet()) {
            sections.add(new Section(entry.getKey(), entry.getValue()));
            expanded.putIfAbsent(entry.getKey(), false);
        }
        applyFilter();
    }

    public void setFilter(String value) {
        filter = value == null ? "" : value.trim().toLowerCase();
        scroll = 0;
        applyFilter();
    }

    private void applyFilter() {
        filteredSections.clear();

        if (!filter.isEmpty()) {
            // Search is represented as one folder so the accordion rule remains true even when
            // matching nodes belong to several normal folders.
            List<Item> matchingItems = new ArrayList<>();
            for (Section section : sections) {
                for (Item item : section.items()) {
                    if (matches(item)) {
                        matchingItems.add(item);
                    }
                }
            }
            if (!matchingItems.isEmpty()) {
                filteredSections.add(new Section("Search results", matchingItems));
            }
            return;
        }

        for (Section section : sections) {
            List<Item> matchingItems = new ArrayList<>();
            for (Item item : section.items()) {
                matchingItems.add(item);
            }
            if (!matchingItems.isEmpty()) {
                filteredSections.add(new Section(section.name(), matchingItems));
            }
        }
    }

    private boolean matches(Item item) {
        String label = item instanceof Command c
                ? c.name() + " " + c.id() + " " + c.description()
                : item instanceof General g
                ? g.name() + " " + g.id() + " " + g.description()
                : ((Blueprint) item).name();
        return label.toLowerCase().contains(filter);
    }

    private boolean isExpanded(Section section) {
        return expanded.getOrDefault(section.name(), "Search results".equals(section.name()));
    }

    private int contentHeight() {
        int rowCount = 0;
        int content = 0;
        for (Section section : filteredSections) {
            rowCount++;
            content += FOLDER_H;
            if (isExpanded(section)) {
                rowCount += section.items().size();
                content += section.items().size() * TILE_H;
            }
        }
        if (rowCount > 1) {
            content += (rowCount - 1) * GAP;
        }
        return content + PADDING * 2 + 14;
    }

    private int maxScroll() {
        return Math.max(0, contentHeight() - getHeight());
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        extractor.enableScissor(getX(), getY(), getX() + getWidth(), getY() + getHeight());
        var text = extractor.textRenderer();
        text.accept(getX() + PADDING, getY() + PADDING,
                Component.literal("Click a node to add").withColor(LuneScreen.ACCENT));

        int contentTop = getY() + PADDING + 14;
        int y = contentTop - scroll;
        for (Section section : filteredSections) {
            int folderTop = Math.max(y, getY() + PADDING);
            int folderBottom = Math.min(y + FOLDER_H, getY() + getHeight() - PADDING);
            if (folderTop < folderBottom) {
                boolean hovered = mouseX >= getX() + PADDING && mouseX < getX() + getWidth() - PADDING
                        && mouseY >= y && mouseY < y + FOLDER_H;
                int bg = hovered ? TILE_HOVER : FOLDER_BG;
                extractor.fill(getX() + PADDING, folderTop, getX() + getWidth() - PADDING, folderBottom, bg);
                extractor.fill(getX() + PADDING, folderTop, getX() + getWidth() - PADDING,
                        folderTop + 1, LuneScreen.PANEL_BORDER);
                String marker = isExpanded(section) ? "v " : "> ";
                text.accept(getX() + PADDING + 5, y + 6,
                        Component.literal(marker + section.name() + " (" + section.items().size() + ")")
                                .withColor(LuneScreen.ACCENT));
            }
            y += FOLDER_H + GAP;

            if (!isExpanded(section)) {
                continue;
            }
            for (Item item : section.items()) {
                int tileTop = Math.max(y, getY() + PADDING);
                int tileBottom = Math.min(y + TILE_H, getY() + getHeight() - PADDING);
                if (tileTop < tileBottom) {
                    boolean hovered = mouseX >= getX() + PADDING && mouseX < getX() + getWidth() - PADDING
                            && mouseY >= y && mouseY < y + TILE_H;
                    int bg = item instanceof Blueprint ? BLUEPRINT_BG : COMMAND_BG;
                    if (item instanceof General) {
                        bg = 0xFF51431E;
                    }
                    if (hovered) {
                        bg = TILE_HOVER;
                    }

                    extractor.fill(getX() + PADDING, tileTop, getX() + getWidth() - PADDING, tileBottom, bg);
                    extractor.fill(getX() + PADDING, tileTop, getX() + getWidth() - PADDING,
                            tileTop + 1, LuneScreen.PANEL_BORDER);
                    extractor.fill(getX() + PADDING, tileBottom - 1, getX() + getWidth() - PADDING,
                            tileBottom, LuneScreen.PANEL_BORDER);

                    String label = item instanceof Command c ? c.name()
                            : item instanceof General g ? g.name() : ((Blueprint) item).name();
                    int colour = item instanceof Blueprint ? 0xFFFF9DE7
                            : item instanceof General ? 0xFFFFD15C : LuneScreen.TEXT;
                    int textY = y + 6;
                    if (textY >= getY() + PADDING && textY + 8 <= getY() + getHeight() - PADDING) {
                        text.accept(getX() + PADDING + 5, textY,
                                Component.literal(label).withColor(colour));
                    }
                }
                y += TILE_H + GAP;
                if (y - scroll > getY() + getHeight()) {
                    break;
                }
            }
            if (y - scroll > getY() + getHeight()) {
                break;
            }
        }
        extractor.disableScissor();
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        int y = getY() + PADDING + 14 - scroll;
        for (Section section : filteredSections) {
            if (event.y() >= y && event.y() < y + FOLDER_H
                    && event.x() >= getX() + PADDING && event.x() < getX() + getWidth() - PADDING) {
                if (filter.isEmpty()) {
                    // Accordion behavior: selecting a folder makes it the only open folder. The
                    // selected folder stays open even when it was already open.
                    expanded.replaceAll((name, ignored) -> false);
                    expanded.put(section.name(), true);
                    scroll = Math.clamp(scroll, 0, maxScroll());
                    return;
                }
                // Search results are intentionally kept in one open folder.
                return;
            }
            y += FOLDER_H + GAP;
            if (!isExpanded(section)) {
                continue;
            }
            for (Item item : section.items()) {
                if (event.y() >= y && event.y() < y + TILE_H
                        && event.x() >= getX() + PADDING && event.x() < getX() + getWidth() - PADDING) {
                    // Picked up rather than added straight away: whether this is a click or a drag
                    // is not known until the button comes back up.
                    dragging = item;
                    dragX = (int) event.x();
                    dragY = (int) event.y();
                    dragMoved = false;
                    return;
                }
                y += TILE_H + GAP;
            }
        }
    }

    @Override
    public void onDrag(MouseButtonEvent event, double dragX2, double dragY2) {
        if (dragging == null) {
            return;
        }
        dragX = (int) event.x();
        dragY = (int) event.y();
        dragMoved |= !isMouseOver(dragX, dragY);
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        Item item = dragging;
        dragging = null;
        if (item == null) {
            return;
        }
        int x = (int) event.x();
        int y = (int) event.y();
        if ((dragMoved || !isMouseOver(x, y)) && onDrop != null && onDrop.accept(item, x, y)) {
            return;
        }
        // Released over the palette: an ordinary click, which drops the card into the middle of
        // whatever the player is looking at.
        onSelect.accept(item);
    }

    /** The tile being dragged, so the tab can draw it under the cursor. */
    public Item draggingItem() {
        return dragging;
    }

    public int dragPointerX() {
        return dragX;
    }

    public int dragPointerY() {
        return dragY;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!isMouseOver(mouseX, mouseY) || filteredSections.isEmpty()) {
            return false;
        }
        scroll -= (int) (Math.signum(scrollY) * (TILE_H + GAP));
        scroll = Math.clamp(scroll, 0, maxScroll());
        return true;
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        // The bounds test is the panel's own - it fills its rectangle - but a hidden panel must
        // still refuse clicks, or a collapsed pane keeps swallowing them.
        return isActive() && mouseX >= getX() && mouseX < getX() + getWidth()
                && mouseY >= getY() && mouseY < getY() + getHeight();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
    }
}
