package com.etka.lune.client.gui.widget;

import com.etka.lune.bot.command.CommandDef;
import com.etka.lune.bot.command.CommandRegistry;
import com.etka.lune.client.gui.LuneScreen;
import com.etka.lune.routine.RoutineBlueprints;
import com.etka.lune.routine.RoutineNode;
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
    public record Blueprint(String name, List<RoutineNode> nodes) implements Item {}

    private record Section(String name, List<Item> items) {}

    private final List<Section> sections = new ArrayList<>();
    private final List<Section> filteredSections = new ArrayList<>();
    private final Map<String, Boolean> expanded = new LinkedHashMap<>();
    private final Consumer<Item> onSelect;
    private int scroll;
    private String filter = "";

    public PalettePanel(int x, int y, int width, int height, Consumer<Item> onSelect) {
        super(x, y, width, height, Component.empty());
        this.onSelect = onSelect;
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
            List<Item> groupedItems = "General".equals(name)
                    ? List.<Item>of(
                    new General(RoutineNode.START_COMMAND, "START",
                            "Explicit task entry; connect it to the first action"),
                    new General(RoutineNode.ALWAYS_COMMAND, "Always",
                            "Send new pulses continuously or at a selected interval"),
                    new General(RoutineNode.SIGNAL_RELAY_COMMAND, "Signal Relay",
                            "Forward an incoming pulse through configurable numbered outputs"),
                    new General(RoutineNode.OBSERVER_COMMAND, "Observer",
                            "Watch a world event and create a pulse when it happens"),
                    new General(RoutineNode.BUTTON_COMMAND, "Button",
                            "Send one manual pulse from the editor"),
                    new General(RoutineNode.END_COMMAND, "End",
                            "Consume a pulse and finish that circuit"))
                    : grouped.remove(name);
            if (groupedItems != null && !groupedItems.isEmpty()) {
                sections.add(new Section(name, groupedItems));
            }
        }

        List<Item> blueprints = new ArrayList<>();
        for (var entry : RoutineBlueprints.all().entrySet()) {
            List<RoutineNode> copies = new ArrayList<>();
            for (RoutineNode node : entry.getValue().nodes) {
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
                    onSelect.accept(item);
                    return;
                }
                y += TILE_H + GAP;
            }
        }
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
