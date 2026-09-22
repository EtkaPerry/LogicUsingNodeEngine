package com.etka.lune.client.gui.widget;

import com.etka.lune.util.Lang;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A right-hand palette of "puzzle pieces" the player can click to add to the task.
 * <p>
 * Commands are grouped into collapsible folders and blueprints have their own folder. This keeps
 * the palette usable as the command library grows without changing how a node is inserted.
 * <p>
 * While a training puzzle is open the whole library is replaced by that lesson's three cards. The
 * restriction is the puzzle: offered everything, "which card is missing" is a search problem.
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
    /**
     * The one folder shown while a puzzle is open.
     *
     * <p>A method rather than a constant: a constant is built at class-load, before the language
     * file exists, and would freeze whatever it read then. Everything that names this folder and
     * everything that recognises it go through here, so the two always agree.</p>
     */
    /**
     * What a folder is called on screen.
     *
     * <p>Folders are grouped by their name and the registry hands that name back in English, so
     * the name stays as it is and only the drawing is looked up. A folder nobody has written a
     * line for reads as its own name.</p>
     */
    private static String sectionTitle(String name) {
        return Lang.getOr("lune.gui.section."
                + name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                        .replaceAll("^_+|_+$", ""), name);
    }

    private static String puzzleSection() {
        return Lang.get("lune.gui.palette.puzzle_section");
    }

    public interface Item {}

    public record Command(String id, String name, String description) implements Item {}
    public record General(String id, String name, String description) implements Item {}
    public record Blueprint(String name, List<TaskNode> nodes) implements Item {}

    private record Section(String name, List<Item> items) {}

    /**
     * One drawn row - a folder header or a tile - with the rectangle it occupies.
     *
     * <p>Drawing and hit-testing both walk this. They used to each compute the same running y from
     * the same four constants, which meant any change to the layout had to be made twice and a
     * missed one would land every click in the folder on the row above it.</p>
     */
    private record Row(Section section, Item item, int top, int height) {}

    private final List<Section> sections = new ArrayList<>();
    private final List<Section> filteredSections = new ArrayList<>();
    private final Map<String, Boolean> expanded = new LinkedHashMap<>();
    private final Consumer<Item> onSelect;
    /** Called when a tile is dropped outside the palette, with the screen point it landed on. */
    private final DropTarget onDrop;
    /** While a puzzle is open, the only command ids the palette will offer. Null the rest of the time. */
    private List<String> puzzleChoices;
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

    /**
     * Narrows the palette to one lesson's three cards, or restores the full library when given null.
     *
     * <p>The restriction is the puzzle. Offered the whole library, "which card is missing" is a
     * search problem; offered three, it is the question the lesson meant to ask.</p>
     */
    public void setPuzzleChoices(List<String> commandIds) {
        puzzleChoices = commandIds == null ? null : List.copyOf(commandIds);
        scroll = 0;
        applyFilter();
    }

    public boolean inPuzzle() {
        return puzzleChoices != null;
    }

    private void rebuild() {
        sections.clear();
        Map<String, List<Item>> grouped = new LinkedHashMap<>();
        // available(), not all(): the cards that drive another mod are not offered when that mod
        // is not installed, because a Deposit to Backpack in a pack with no backpack mod is a card
        // that can do nothing but fail. A task built elsewhere still opens with its card intact -
        // the canvas looks the node up by id, and that does not filter.
        for (CommandDef def : CommandRegistry.available()) {
            grouped.computeIfAbsent(CommandRegistry.categoryFor(def.id()), ignored -> new ArrayList<>())
                    .add(new Command(def.id(), def.name(), def.description()));
        }

        // The first useful folder starts open. The accordion behavior below keeps every other
        // folder closed when the player chooses another one.
        expanded.putIfAbsent("General", true);

        for (String name : List.of("General", "Movement", "Gathering", "Logic & Conditions", "Mining & Building",
                "Combat", "Items & Storage", "Tasks & Missions", "Other")) {
            // Taken out of the map either way, or the leftovers loop below adds a second folder
            // with the same name. General is written by hand because its pulse nodes are not
            // registry commands at all - but anything else the registry files under General is
            // appended to it rather than dropped. Dropping the lot used to be safe, on the
            // grounds that the registry's General entries "are the same three cards"; the first
            // card that was not one of those three registered fine, translated fine, and could
            // not be found in the palette or by searching for it.
            List<Item> fromRegistry = grouped.remove(name);
            List<Item> handWritten = "General".equals(name)
                    ? List.<Item>of(
                    new General(TaskNode.START_COMMAND, Lang.get("lune.gui.tasks.start"),
                            Lang.get("lune.gui.palette.explicit_task_entry_connect_first_action")),
                    new General(TaskNode.ALWAYS_COMMAND, Lang.get("lune.gui.tasks.always"),
                            Lang.get("lune.gui.palette.keep_connected_branch_powered_every_tick")),
                    new General(TaskNode.PULSE_COMMAND, Lang.get("lune.gui.tasks.pulse"),
                            Lang.get("lune.gui.palette.send_one_pulse_every_few_seconds_like")),
                    new General(TaskNode.SIGNAL_RELAY_COMMAND, Lang.get("lune.gui.tasks.signal_relay"),
                            Lang.get("lune.gui.palette.forward_incoming_pulse_through")),
                    new General(TaskNode.OBSERVER_COMMAND, Lang.get("lune.gui.palette.observer"),
                            Lang.get("lune.gui.palette.watch_card_pulse_whenever_power_changes")),
                    new General(TaskNode.BUTTON_COMMAND, Lang.get("lune.gui.tasks.button"),
                            Lang.get("lune.gui.palette.send_one_manual_pulse_from_editor")),
                    new General(TaskNode.END_COMMAND, Lang.get("lune.gui.palette.end"),
                            Lang.get("lune.gui.palette.consume_pulse_finish_circuit")))
                    : null;
            List<Item> groupedItems = handWritten == null ? fromRegistry
                    : withRegistryExtras(handWritten, fromRegistry);
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
        // Locale.ROOT, not the platform default: Turkish lower-cases I to a dotless ı, so a bare
        // toLowerCase makes typing "i" stop matching a card labelled with "I" - on exactly the
        // locale this mod ships a translation for.
        filter = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        scroll = 0;
        applyFilter();
    }

    private void applyFilter() {
        filteredSections.clear();

        if (puzzleChoices != null) {
            // The lesson's own order is kept: it is deliberately not "answer first".
            List<Item> offered = new ArrayList<>();
            for (String id : puzzleChoices) {
                Item item = itemById(id);
                if (item != null) {
                    offered.add(item);
                }
            }
            if (!offered.isEmpty()) {
                filteredSections.add(new Section(puzzleSection(), offered));
            }
            return;
        }

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
                filteredSections.add(new Section(Lang.get("lune.gui.block.search_results"), matchingItems));
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

    /** Finds an already-built tile by its command id, so a puzzle reuses the library's own wording. */
    private Item itemById(String id) {
        for (Section section : sections) {
            for (Item item : section.items()) {
                if (item instanceof Command command && command.id().equals(id)
                        || item instanceof General general && general.id().equals(id)) {
                    return item;
                }
            }
        }
        return null;
    }

    /**
     * A hand-written folder, followed by whatever else the registry filed under it.
     *
     * <p>The three pulse cards the General folder writes out by hand - End, Observer, Button - are
     * in the registry too, so they are matched by id and not added twice. Everything else is
     * appended, which is what keeps a new card from vanishing because somebody gave it a category
     * that happened to be spoken for.</p>
     */
    static List<Item> withRegistryExtras(List<Item> handWritten, List<Item> fromRegistry) {
        if (fromRegistry == null || fromRegistry.isEmpty()) {
            return handWritten;
        }
        Set<String> already = new HashSet<>();
        for (Item item : handWritten) {
            already.add(itemId(item));
        }
        List<Item> all = new ArrayList<>(handWritten);
        for (Item item : fromRegistry) {
            if (!already.contains(itemId(item))) {
                all.add(item);
            }
        }
        return all;
    }

    static String itemId(Item item) {
        return switch (item) {
            case Command command -> command.id();
            case General general -> general.id();
            case Blueprint blueprint -> blueprint.name();
            default -> "";
        };
    }

    private boolean matches(Item item) {
        String label = item instanceof Command c
                ? c.name() + " " + c.id() + " " + c.description()
                : item instanceof General g
                ? g.name() + " " + g.id() + " " + g.description()
                : ((Blueprint) item).name();
        return label.toLowerCase(java.util.Locale.ROOT).contains(filter);
    }

    /**
     * Two folders open themselves: the one holding search results, and the one holding a puzzle's
     * three candidate cards.
     *
     * <p>Compared against the same expression that named them rather than against the English
     * those expressions happen to produce. A literal here would quietly stop matching the first
     * time somebody played in another language, and the folder would open closed.</p>
     */
    private boolean isExpanded(Section section) {
        return expanded.getOrDefault(section.name(),
                Lang.get("lune.gui.block.search_results").equals(section.name())
                        || puzzleSection().equals(section.name()));
    }

    // --- layout --------------------------------------------------------------

    /** The y the folder list stops at. */
    private int contentBottom() {
        return getY() + getHeight() - PADDING;
    }

    /** Every folder header and tile, in draw order, positioned for the current scroll. */
    private List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        int y = getY() + PADDING + 14 - scroll;
        for (Section section : filteredSections) {
            rows.add(new Row(section, null, y, FOLDER_H));
            y += FOLDER_H + GAP;
            if (!isExpanded(section)) {
                continue;
            }
            for (Item item : section.items()) {
                rows.add(new Row(section, item, y, TILE_H));
                y += TILE_H + GAP;
            }
        }
        return rows;
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

    // --- drawing -------------------------------------------------------------

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        extractor.enableScissor(getX(), getY(), getX() + getWidth(), getY() + getHeight());
        var text = extractor.textRenderer();
        text.accept(getX() + PADDING, getY() + PADDING,
                Component.literal(Lang.get(inPuzzle() ? "lune.gui.palette.puzzle_hint" : "lune.gui.palette.add_hint"))
                        .withColor(LuneScreen.ACCENT));

        int left = getX() + PADDING;
        int right = getX() + getWidth() - PADDING;
        for (Row row : rows()) {
            int top = Math.max(row.top(), getY() + PADDING);
            int bottom = Math.min(row.top() + row.height(), contentBottom());
            if (top >= bottom) {
                continue;
            }
            boolean hovered = mouseX >= left && mouseX < right
                    && mouseY >= row.top() && mouseY < row.top() + row.height()
                    && mouseY >= getY() + PADDING && mouseY < contentBottom();
            if (row.item() == null) {
                drawFolder(extractor, text, row, left, right, top, bottom, hovered);
            } else {
                drawTile(extractor, text, row, left, right, top, bottom, hovered);
            }
        }

        extractor.disableScissor();
    }

    private void drawFolder(GuiGraphicsExtractor extractor, net.minecraft.client.gui.ActiveTextCollector text,
                            Row row, int left, int right, int top, int bottom, boolean hovered) {
        extractor.fill(left, top, right, bottom, hovered ? TILE_HOVER : FOLDER_BG);
        extractor.fill(left, top, right, top + 1, LuneScreen.PANEL_BORDER);
        String marker = isExpanded(row.section()) ? "v " : "> ";
        int textY = row.top() + 6;
        if (textY >= getY() + PADDING && textY + 8 <= contentBottom()) {
            text.accept(left + 5, textY,
                    Component.literal(marker + sectionTitle(row.section().name())
                            + " (" + row.section().items().size() + ")").withColor(LuneScreen.ACCENT));
        }
    }

    private void drawTile(GuiGraphicsExtractor extractor, net.minecraft.client.gui.ActiveTextCollector text,
                          Row row, int left, int right, int top, int bottom, boolean hovered) {
        Item item = row.item();
        int bg = item instanceof Blueprint ? BLUEPRINT_BG : COMMAND_BG;
        if (item instanceof General) {
            bg = 0xFF51431E;
        }
        if (hovered) {
            bg = TILE_HOVER;
        }
        extractor.fill(left, top, right, bottom, bg);
        extractor.fill(left, top, right, top + 1, LuneScreen.PANEL_BORDER);
        extractor.fill(left, bottom - 1, right, bottom, LuneScreen.PANEL_BORDER);

        String label = item instanceof Command c ? c.name()
                : item instanceof General g ? g.name() : ((Blueprint) item).name();
        int colour = item instanceof Blueprint ? 0xFFFF9DE7
                : item instanceof General ? 0xFFFFD15C : LuneScreen.TEXT;
        int textY = row.top() + 6;
        if (textY >= getY() + PADDING && textY + 8 <= contentBottom()) {
            text.accept(left + 5, textY, Component.literal(label).withColor(colour));
        }
    }

    // --- input ---------------------------------------------------------------

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        int left = getX() + PADDING;
        int right = getX() + getWidth() - PADDING;
        if (event.x() < left || event.x() >= right) {
            return;
        }
        if (event.y() < getY() + PADDING || event.y() >= contentBottom()) {
            return;
        }

        for (Row row : rows()) {
            if (event.y() < row.top() || event.y() >= row.top() + row.height()) {
                continue;
            }
            if (row.item() == null) {
                if (filter.isEmpty() && !inPuzzle()) {
                    // Accordion behavior: selecting a folder makes it the only open folder. The
                    // selected folder stays open even when it was already open.
                    expanded.replaceAll((name, ignored) -> false);
                    expanded.put(row.section().name(), true);
                    scroll = Math.clamp(scroll, 0, maxScroll());
                }
                // Search results and the puzzle folder are intentionally kept open.
                return;
            }
            // Picked up rather than added straight away: whether this is a click or a drag
            // is not known until the button comes back up.
            dragging = row.item();
            dragX = (int) event.x();
            dragY = (int) event.y();
            dragMoved = false;
            return;
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
