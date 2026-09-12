package com.etka.lune.client.gui.widget;

import com.etka.lune.util.Lang;
import com.etka.lune.bot.command.CommandDef;
import com.etka.lune.bot.command.Param;
import com.etka.lune.client.gui.LuneScreen;
import com.etka.lune.client.gui.UiScale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The right-hand pane: an editor built automatically from whatever parameters the selected command
 * declares.
 * <p>
 * Choosing "Mine" opens the {@link BlockPicker} popup, while simpler multi-selects like mob
 * targets still expand inline.
 */
public class ParamPanel extends AbstractWidget {

    private static final int ROW_HEIGHT = 13;
    private static final int PADDING = 4;
    private static final int LOGIC_HEIGHT = 43;
    private static final int LOGIC_LINE_HEIGHT = 9;
    private static final int LOGIC_MAX_LINES = 3;
    private static final int LOGIC_HOVER_ROW = -2;
    private static final int VALUE_WIDTH = 90;
    private static final int PORT_SIZE = 8;
    private static final int PORT_GAP = 4;
    private static final int ICON_SIZE = 8;
    private static final int TOOLTIP_DELAY_MS = 500;
    private static final int CHOICE_MAX_VISIBLE = 5;
    private static final String ELLIPSIS = "…";
    /** How far a shift-click on a coordinate row will look for the spot being pointed at. */
    private static final double PICK_RANGE = 64.0;

    private static final Identifier HEART_SPRITE = Identifier.withDefaultNamespace("hud/heart/full");
    private static final Identifier AIR_SPRITE = Identifier.withDefaultNamespace("hud/air");
    private static final Identifier CREEPER_TEXTURE = Identifier.parse("minecraft:textures/entity/creeper/creeper.png");

    private static final Map<String, Icon> ICONS = Map.of(
            "protect_air", Icon.AIR,
            "air_compare", Icon.AIR,
            "air_value", Icon.AIR,
            "protect_health", Icon.HEART,
            "health_compare", Icon.HEART,
            "health_value", Icon.HEART,
            "protect_monsters", Icon.CREEPER,
            "monster_compare", Icon.CREEPER,
            "monster_distance", Icon.CREEPER
    );

    private static final int ROW_HOVER = 0x28FFFFFF;
    private static final int VALUE_BG = 0xFF1E1E24;
    private static final int CHECK_ON = LuneScreen.ACCENT;
    private static final int OUTPUT_ON = 0xFFF2C14E;
    private static final int CHOICE_BG = 0xFF17171D;
    private static final int CHOICE_HOVER = 0xFF303846;
    private static final int CHOICE_SELECTED = 0xFF29394E;

    /** One line in the pane: either a parameter, or one option of an expanded multi-select. */
    private interface Row {}

    private record SectionRow(String title) implements Row {}

    private record ParamRow(Param<?> param) implements Row {}

    private record EntityRow(Param.EntitySet param, EntityType<?> type) implements Row {}

    public enum PortSide { INPUT, OUTPUT }

    private final Set<String> expanded = new HashSet<>();
    private final List<Row> rows = new ArrayList<>();
    private CommandDef command;
    private String sourceDescription;
    private int repeat = 1;
    private boolean compactMode;
    private Map<String, String> sections = Map.of();
    private int scrollRows;
    private Consumer<Param.BlockSet> onOpenBlockPicker;
    private Consumer<Param.ItemChoice> onOpenItemPicker;
    private Consumer<Param.Recipe> onOpenRecipePicker;
    private Consumer<Param.Text> onOpenNamePrompt;
    private Set<String> exposedInputs = Set.of();
    private Set<String> exposedOutputs = Set.of();
    private BiConsumer<String, PortSide> onTogglePort;
    private Predicate<String> parameterEnabled = ignored -> true;

    private int hoverRow = -1;
    private long hoverStart;
    private List<Component> hoverTooltip = List.of();
    private Param.Choice openChoice;
    private int choiceScroll;
    private int choiceDropX;
    private int choiceDropY;
    private int choiceDropHeight;

    private enum Icon { NONE, HEART, AIR, CREEPER }


    public ParamPanel(int x, int y, int width, int height) {
        super(x, y, width, height, Component.empty());
    }

    public void setOpenBlockPicker(Consumer<Param.BlockSet> onOpenBlockPicker) {
        this.onOpenBlockPicker = onOpenBlockPicker;
    }

    public void setOpenItemPicker(Consumer<Param.ItemChoice> onOpenItemPicker) {
        this.onOpenItemPicker = onOpenItemPicker;
    }

    public void setOpenRecipePicker(Consumer<Param.Recipe> onOpenRecipePicker) {
        this.onOpenRecipePicker = onOpenRecipePicker;
    }

    public void setOpenNamePrompt(Consumer<Param.Text> onOpenNamePrompt) {
        this.onOpenNamePrompt = onOpenNamePrompt;
    }

    public void setCommand(CommandDef command) {
        if (this.command != command) {
            this.command = command;
            sourceDescription = null;
            expanded.clear();
            closeChoice();
            scrollRows = 0;
            rebuild();
        }
    }

    /** Rebuilds the visible rows after the current command values changed in place. */
    public void refresh() {
        closeChoice();
        rebuild();
    }

    /** Uses the compact, settings-style renderer instead of the task editor renderer. */
    public void setCompactMode(boolean compactMode) {
        if (this.compactMode == compactMode) {
            return;
        }
        this.compactMode = compactMode;
        closeChoice();
        scrollRows = 0;
        rebuild();
    }

    /**
     * What a section header is called on screen.
     *
     * <p>The name a section is grouped by is an identifier - rows are batched by comparing it -
     * so it stays as it is and only the drawing is looked up. A section nobody has written a line
     * for reads as its own name, which is what it did before there were lines at all.</p>
     */
    private static String sectionTitle(String name) {
        return Lang.getOr("lune.gui.section."
                + name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                        .replaceAll("^_+|_+$", ""), name);
    }

    /** Adds lightweight section headers while retaining the command's normal parameter model. */
    public void setSections(Map<String, String> sections) {
        this.sections = sections == null ? Map.of() : Map.copyOf(sections);
        rebuild();
    }

    public CommandDef getCommand() {
        return command;
    }

    /** Shows the explanation for a graph source such as Always, which has no CommandDef. */
    public void setSourceDescription(String sourceDescription) {
        if (java.util.Objects.equals(this.sourceDescription, sourceDescription)) {
            return;
        }
        this.sourceDescription = sourceDescription;
        if (sourceDescription != null) {
            this.command = null;
            rows.clear();
            closeChoice();
        }
    }

    /** Keeps monitor wording in sync with the selected node's x1/x∞ control. */
    public void setRepeat(int repeat) {
        this.repeat = Math.max(0, repeat);
    }

    /** Marks parameter rows that can be exposed on either side of the selected blueprint node. */
    public void setPortExposure(Set<String> exposedInputs, Set<String> exposedOutputs,
                                BiConsumer<String, PortSide> onTogglePort) {
        this.exposedInputs = exposedInputs == null
                ? Set.of() : new java.util.LinkedHashSet<>(exposedInputs);
        this.exposedOutputs = exposedOutputs == null
                ? Set.of() : new java.util.LinkedHashSet<>(exposedOutputs);
        this.onTogglePort = onTogglePort;
    }

    /**
     * Sets a live guard for parameter editing. Disabled parameters remain visible so the user can
     * understand why they are unavailable, but their values cannot be changed by pointer input.
     */
    public void setParameterEnabled(Predicate<String> parameterEnabled) {
        this.parameterEnabled = parameterEnabled == null ? ignored -> true : parameterEnabled;
    }

    private void rebuild() {
        rows.clear();
        if (command == null) {
            return;
        }
        String previousSection = null;
        for (Param<?> param : command.params()) {
            // A parameter the current values make meaningless is left out rather than greyed: the
            // shortest card is the one that only asks what it will actually read.
            if (!command.isRelevant(param.id())) {
                continue;
            }
            String section = sections.get(param.id());
            if (section != null && !section.equals(previousSection)) {
                rows.add(new SectionRow(section));
            }
            previousSection = section;
            rows.add(new ParamRow(param));
            if (!expanded.contains(param.id())) {
                continue;
            }
            if (param instanceof Param.EntitySet entities) {
                for (EntityType<?> type : entities.candidates()) {
                    rows.add(new EntityRow(entities, type));
                }
            }
        }
        clampScroll();
    }

    private int visibleRows() {
        int reserved = compactMode ? 0 : LOGIC_HEIGHT;
        return Math.max(1, (getHeight() - PADDING * 2 - reserved) / ROW_HEIGHT);
    }

    private void clampScroll() {
        scrollRows = Math.clamp(scrollRows, 0, Math.max(0, rows.size() - visibleRows()));
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        var text = extractor.textRenderer();
        if (command == null) {
            if (sourceDescription == null) {
                text.accept(getX() + 6, getY() + 6,
                        Component.literal(Lang.get("lune.gui.param.pick_command_left")).withColor(LuneScreen.TEXT_DIM));
            } else {
                text.accept(getX() + 6, getY() + 6,
                        Component.literal(Lang.get("lune.gui.param.logic")).withColor(LuneScreen.ACCENT));
                List<String> lines = wrapText(Minecraft.getInstance().font, sourceDescription,
                        Math.max(20, getWidth() - 12), 8);
                for (int i = 0; i < lines.size(); i++) {
                    text.accept(getX() + 6, getY() + 16 + i * LOGIC_LINE_HEIGHT,
                            Component.literal(lines.get(i)).withColor(LuneScreen.TEXT_DIM));
                }
            }
            return;
        }

        clampScroll();
        int visible = visibleRows();
        if (!compactMode) {
            renderLogic(extractor, text);
        }

        int newHoverRow = -1;
        long now = Util.getMillis();
        hoverTooltip = new java.util.ArrayList<>();

        boolean logicHovered = !compactMode && mouseX >= getX() && mouseX < getX() + getWidth()
                && mouseY >= getY() && mouseY < rowsTop();
        if (logicHovered) {
            extractor.fill(getX() + 1, getY() + 1, getX() + getWidth() - 1,
                    rowsTop() - 1, ROW_HOVER);
            newHoverRow = LOGIC_HOVER_ROW;
            hoverTooltip.add(Component.literal(Lang.get("lune.gui.param.logic_2", command.logicDescription(repeat)))
                    .withColor(LuneScreen.TEXT));
        }

        for (int i = 0; i < visible; i++) {
            int index = scrollRows + i;
            if (index >= rows.size()) {
                break;
            }
            int rowY = rowsTop() + i * ROW_HEIGHT;
            boolean hovered = mouseY >= rowY && mouseY < rowY + ROW_HEIGHT
                    && mouseX >= getX() && mouseX < getX() + getWidth();
            if (hovered) {
                extractor.fill(getX() + 1, rowY, getX() + getWidth() - 1, rowY + ROW_HEIGHT, ROW_HOVER);
                newHoverRow = index;
            }
            renderRow(extractor, text, rows.get(index), rowY, hovered);
        }

        renderChoiceDropdown(extractor, text, mouseX, mouseY);
        if (openChoice != null && mouseX >= choiceDropX && mouseX < choiceDropX + VALUE_WIDTH
                && mouseY >= choiceDropY && mouseY < choiceDropY + choiceDropHeight) {
            newHoverRow = -1;
            hoverTooltip = List.of();
        }

        if (newHoverRow != hoverRow) {
            hoverRow = newHoverRow;
            hoverStart = now;
        }

        if (hoverRow != -1 && now - hoverStart >= TOOLTIP_DELAY_MS && !hoverTooltip.isEmpty()) {
            // Anchored in game pixels: the tooltip is drawn after the menu's scale transform ends.
            extractor.setComponentTooltipForNextFrame(Minecraft.getInstance().font,
                    hoverTooltip, UiScale.toGamePixels(mouseX), UiScale.toGamePixels(mouseY));
        }

        if (rows.size() > visible) {
            text.accept(getX() + getWidth() - 14, getY() + getHeight() - 11,
                    Component.literal("▾").withColor(LuneScreen.TEXT_DIM));
        }
    }

    private void renderLogic(GuiGraphicsExtractor extractor,
                             net.minecraft.client.gui.ActiveTextCollector text) {
        int left = getX() + 6;
        int width = Math.max(20, getWidth() - 12);
        text.accept(left, getY() + 4, Component.literal(Lang.get("lune.gui.param.logic")).withColor(LuneScreen.ACCENT));
        List<String> lines = wrapText(Minecraft.getInstance().font,
                command.logicDescription(repeat), width, LOGIC_MAX_LINES);
        for (int i = 0; i < lines.size(); i++) {
            text.accept(left, getY() + 14 + i * LOGIC_LINE_HEIGHT,
                    Component.literal(lines.get(i)).withColor(LuneScreen.TEXT_DIM));
        }
    }

    private void renderRow(GuiGraphicsExtractor extractor, net.minecraft.client.gui.ActiveTextCollector text,
                           Row row, int rowY, boolean hovered) {
        switch (row) {
            case SectionRow(String title) -> text.accept(getX() + 6, rowY + 3,
                    Component.literal(sectionTitle(title)).withColor(LuneScreen.ACCENT));
            case ParamRow(Param<?> param) -> {
                boolean enabled = isParameterEnabled(param);
                Icon icon = ICONS.getOrDefault(param.id(), Icon.NONE);
                int labelX = getX() + 6;
                int valueBoxX = valueBoxX();
                int maxLabelWidth = valueBoxX - 8 - labelX;

                if (canExpose(param)) {
                    extractor.fill(inputPortX(), rowY + 2, inputPortX() + PORT_SIZE, rowY + PORT_SIZE,
                            exposedInputs.contains(param.id()) ? CHECK_ON : VALUE_BG);
                    labelX += 12;
                    maxLabelWidth -= 12;
                }

                if (icon != Icon.NONE) {
                    drawIcon(extractor, icon, labelX, rowY + 2);
                    labelX += ICON_SIZE + 2;
                    maxLabelWidth -= ICON_SIZE + 2;
                }

                Font font = Minecraft.getInstance().font;
                String full = param.label();
                String clipped = clipToWidth(font, full, Math.max(12, maxLabelWidth));
                text.accept(labelX, rowY + 3, Component.literal(clipped).withColor(
                        enabled ? LuneScreen.TEXT : LuneScreen.TEXT_DIM));

                if (hovered) {
                    if (!clipped.equals(full)) {
                        hoverTooltip.add(Component.literal(full).withColor(LuneScreen.TEXT));
                    }
                    String description = param.tooltip();
                    if (description != null && !description.isBlank()) {
                        hoverTooltip.add(Component.literal(description).withColor(LuneScreen.TEXT_DIM));
                    }
                    if (!enabled) {
                        hoverTooltip.add(Component.literal(Lang.get("lune.gui.param.unavailable_world_or_player"))
                                .withColor(LuneScreen.TEXT_DIM));
                    }
                }

                if (!compactMode) {
                    extractor.fill(valueBoxX, rowY + 1, valueBoxX + VALUE_WIDTH,
                            rowY + ROW_HEIGHT - 1, VALUE_BG);
                }
                if (canExpose(param)) {
                    extractor.fill(outputPortX(), rowY + 2, outputPortX() + PORT_SIZE, rowY + PORT_SIZE,
                            exposedOutputs.contains(param.id()) ? OUTPUT_ON : VALUE_BG);
                }

                String display = param.displayValue();
                if (param instanceof Param.BlockSet || param instanceof Param.EntitySet
                        || param instanceof Param.Recipe || param instanceof Param.Text) {
                    boolean expanded = this.expanded.contains(param.id());
                    if (!(param instanceof Param.EntitySet)) {
                        // The picker opens from this row; the arrow is a hint that it is a picker.
                        display = "▸ " + display;
                    } else {
                        display = (expanded ? "▾ " : "▸ ") + display;
                    }
                    String clippedDisplay = clipToWidth(font, display, VALUE_WIDTH - 8);
                    text.accept(centredValueX(valueBoxX, font, clippedDisplay), rowY + 3,
                            Component.literal(clippedDisplay).withColor(
                                    enabled ? LuneScreen.ACCENT : LuneScreen.TEXT_DIM));
                } else if (param instanceof Param.Ints) {
                    int signSpace = 12;
                    int valueW = font.width(display);
                    int available = VALUE_WIDTH - signSpace * 2;
                    int valueXPos = valueBoxX + signSpace + Math.max(0, (available - valueW) / 2);
                    int valueColor = enabled ? LuneScreen.ACCENT : LuneScreen.TEXT_DIM;
                    text.accept(valueBoxX + 4, rowY + 3, Component.literal("−").withColor(valueColor));
                    text.accept(valueXPos, rowY + 3, Component.literal(display).withColor(valueColor));
                    text.accept(valueBoxX + VALUE_WIDTH - 4 - font.width("+"), rowY + 3, Component.literal("+").withColor(valueColor));
                } else if (param instanceof Param.Choice choice) {
                    int choiceTextWidth = VALUE_WIDTH - 24;
                    String clippedValue = clipToWidth(font, display, choiceTextWidth);
                    int centredX = centredValueX(valueBoxX, font, clippedValue);
                    text.accept(centredX, rowY + 3,
                            Component.literal(clippedValue).withColor(
                                    enabled ? LuneScreen.ACCENT : LuneScreen.TEXT_DIM));
                    text.accept(valueBoxX + VALUE_WIDTH - 11, rowY + 3,
                            Component.literal(openChoice == choice ? "▾" : "▸")
                                    .withColor(LuneScreen.TEXT_DIM));
                } else {
                    String clippedDisplay = clipToWidth(font, display, VALUE_WIDTH - 8);
                    text.accept(centredValueX(valueBoxX, font, clippedDisplay), rowY + 3,
                            Component.literal(clippedDisplay).withColor(
                                    enabled ? LuneScreen.ACCENT : LuneScreen.TEXT_DIM));
                }
            }
            case EntityRow(Param.EntitySet param, EntityType<?> type) ->
                    renderCheck(extractor, text, rowY, param.isSelected(type), type.getDescription().getString());
            default -> {
            }
        }
    }

    private void renderChoiceDropdown(GuiGraphicsExtractor extractor,
                                      net.minecraft.client.gui.ActiveTextCollector text,
                                      int mouseX, int mouseY) {
        if (openChoice == null) {
            return;
        }
        int openIndex = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i) instanceof ParamRow(Param<?> param) && param == openChoice) {
                openIndex = i;
                break;
            }
        }
        if (openIndex < scrollRows || openIndex >= scrollRows + visibleRows()) {
            closeChoice();
            return;
        }

        List<String> options = openChoice.options();
        if (options.isEmpty()) {
            closeChoice();
            return;
        }
        int visible = Math.min(options.size(), CHOICE_MAX_VISIBLE);
        choiceDropHeight = visible * ROW_HEIGHT + 2;
        choiceDropX = valueBoxX();
        int sourceY = rowsTop() + (openIndex - scrollRows) * ROW_HEIGHT;
        int below = sourceY + ROW_HEIGHT;
        choiceDropY = below + choiceDropHeight <= getY() + getHeight()
                ? below : sourceY - choiceDropHeight;

        extractor.fill(choiceDropX - 1, choiceDropY - 1,
                choiceDropX + VALUE_WIDTH + 1, choiceDropY + choiceDropHeight + 1,
                LuneScreen.PANEL_BORDER);
        extractor.fill(choiceDropX, choiceDropY,
                choiceDropX + VALUE_WIDTH, choiceDropY + choiceDropHeight, CHOICE_BG);

        clampChoiceScroll(options.size());
        Font font = Minecraft.getInstance().font;
        for (int row = 0; row < visible; row++) {
            int optionIndex = choiceScroll + row;
            if (optionIndex >= options.size()) {
                break;
            }
            int optionY = choiceDropY + 1 + row * ROW_HEIGHT;
            boolean hovered = mouseX >= choiceDropX && mouseX < choiceDropX + VALUE_WIDTH
                    && mouseY >= optionY && mouseY < optionY + ROW_HEIGHT;
            String option = options.get(optionIndex);
            boolean selected = option.equals(openChoice.get());
            if (selected || hovered) {
                extractor.fill(choiceDropX, optionY, choiceDropX + VALUE_WIDTH,
                        optionY + ROW_HEIGHT, selected ? CHOICE_SELECTED : CHOICE_HOVER);
            }
            String clipped = clipToWidth(font, openChoice.label(option),
                    VALUE_WIDTH - 8);
            int centredX = choiceDropX + Math.max(4,
                    (VALUE_WIDTH - font.width(clipped)) / 2);
            text.accept(centredX, optionY + 3,
                    Component.literal(clipped).withColor(selected
                            ? LuneScreen.ACCENT : LuneScreen.TEXT));
        }
    }

    private String clipToWidth(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        int ellipsisWidth = font.width(ELLIPSIS);
        String fit = font.plainSubstrByWidth(text, Math.max(0, maxWidth - ellipsisWidth), false);
        return fit.isEmpty() ? ELLIPSIS : fit + ELLIPSIS;
    }

    private List<String> wrapText(Font font, String text, int maxWidth, int maxLines) {
        List<String> lines = new ArrayList<>();
        String remaining = text == null ? "" : text.trim();
        while (!remaining.isEmpty() && lines.size() < maxLines) {
            String fit = font.plainSubstrByWidth(remaining, maxWidth, false);
            if (fit.isEmpty()) {
                break;
            }
            int cut = fit.length();
            if (cut < remaining.length()) {
                int space = fit.lastIndexOf(' ');
                if (space > 0) {
                    cut = space;
                }
            }
            lines.add(remaining.substring(0, cut).trim());
            remaining = remaining.substring(cut).trim();
        }
        if (!remaining.isEmpty() && !lines.isEmpty()) {
            String last = lines.get(lines.size() - 1);
            lines.set(lines.size() - 1, clipToWidth(font, last, Math.max(8, maxWidth - font.width(ELLIPSIS)))
                    + ELLIPSIS);
        }
        return lines;
    }

    private void drawIcon(GuiGraphicsExtractor extractor, Icon icon, int x, int y) {
        switch (icon) {
            case HEART -> extractor.blitSprite(RenderPipelines.GUI_TEXTURED, HEART_SPRITE, x, y, ICON_SIZE, ICON_SIZE, -1);
            case AIR -> extractor.blitSprite(RenderPipelines.GUI_TEXTURED, AIR_SPRITE, x, y, ICON_SIZE, ICON_SIZE, -1);
            case CREEPER -> extractor.blit(RenderPipelines.GUI_TEXTURED, CREEPER_TEXTURE, x, y, 8, 8, 8, 8, 8, 8, 64, 32);
            default -> {
            }
        }
    }

    private void renderCheck(GuiGraphicsExtractor extractor, net.minecraft.client.gui.ActiveTextCollector text,
                             int rowY, boolean checked, String label) {
        int boxX = getX() + 18;
        int boxY = rowY + 2;
        extractor.fill(boxX, boxY, boxX + 8, boxY + 8, checked ? CHECK_ON : VALUE_BG);
        text.accept(boxX + 14, rowY + 3,
                Component.literal(label).withColor(checked ? LuneScreen.TEXT : LuneScreen.TEXT_DIM));
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (openChoice != null) {
            if (event.x() >= choiceDropX && event.x() < choiceDropX + VALUE_WIDTH
                    && event.y() >= choiceDropY + 1
                    && event.y() < choiceDropY + choiceDropHeight - 1) {
                int row = (int) (event.y() - choiceDropY - 1) / ROW_HEIGHT;
                List<String> options = openChoice.options();
                int selected = choiceScroll + row;
                if (row >= 0 && selected >= 0 && selected < options.size()) {
                    selectChoice(openChoice, options.get(selected));
                }
                closeChoice();
                return;
            }
            closeChoice();
            return;
        }
        if (event.y() < rowsTop()) {
            return;
        }
        int index = scrollRows + (int) ((event.y() - rowsTop()) / ROW_HEIGHT);
        if (index < 0 || index >= rows.size()) {
            return;
        }
        boolean shift = (event.modifiers() & org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT) != 0;

        switch (rows.get(index)) {
            case ParamRow(Param<?> param) -> {
                if (!isParameterEnabled(param)) {
                    return;
                }
                if (canExpose(param) && event.x() >= inputPortX() - 3
                        && event.x() <= inputPortX() + PORT_SIZE + 3) {
                    if (onTogglePort != null) {
                        onTogglePort.accept(param.id(), PortSide.INPUT);
                    }
                } else if (canExpose(param) && event.x() >= outputPortX() - 3
                        && event.x() <= outputPortX() + PORT_SIZE + 3) {
                    if (onTogglePort != null) {
                        onTogglePort.accept(param.id(), PortSide.OUTPUT);
                    }
                } else {
                    clickParam(param, event.x(), shift);
                }
            }
            case EntityRow(Param.EntitySet param, EntityType<?> type) -> param.toggle(type);
            default -> {
            }
        }
    }

    private void clickParam(Param<?> param, double clickX, boolean shift) {
        switch (param) {
            case Param.Ints ints -> {
                int valueX = valueBoxX();
                int step = shift ? 10 : 1;
                int direction = clickX < valueX + VALUE_WIDTH / 2.0 ? -step : step;
                ints.set(ints.get() + direction);
            }
            case Param.Bool bool -> bool.toggle();
            case Param.Choice choice -> openChoice(choice);
            case Param.Pos pos -> {
                net.minecraft.world.entity.player.Player player = Minecraft.getInstance().player;
                if (player == null) {
                    return;
                }
                // Plain click: where you are standing. Shift-click: where you are pointing - which
                // is the only way to say "that spot over there" about a place you are not in, and
                // the answer to how a build card is aimed at all. The view angles do not move while
                // this screen is open, so the crosshair is still where you left it.
                BlockPos aimed = shift ? aimedSpot(player) : null;
                pos.set(aimed != null ? aimed : player.blockPosition());
            }
            case Param.BlockSet blocks -> {
                if (onOpenBlockPicker != null) {
                    onOpenBlockPicker.accept(blocks);
                }
            }
            case Param.EntitySet entities -> toggleExpanded(entities.id());
            case Param.Text typed -> {
                if (onOpenNamePrompt != null) {
                    onOpenNamePrompt.accept(typed);
                }
            }
            case Param.Recipe recipe -> {
                // Naming an item and drawing a grid are two answers to one question, so they are
                // two tabs of one editor rather than two rows of the card.
                if (onOpenRecipePicker != null) {
                    onOpenRecipePicker.accept(recipe);
                }
            }
            case Param.ItemChoice item -> {
                // Cycling one at a time through every registered item is hopeless on a modded
                // instance; the picker shows what the player is actually carrying instead.
                if (onOpenItemPicker != null) {
                    onOpenItemPicker.accept(item);
                } else {
                    item.cycle();
                }
            }
            default -> {
            }
        }
    }

    /**
     * The spot the crosshair is pointing at: the empty space against the face being aimed at, which
     * is where a block would appear - not the block being looked at. It reads the same way for a
     * destination, since that empty space is exactly where you would stand.
     */
    private static BlockPos aimedSpot(net.minecraft.world.entity.player.Player player) {
        net.minecraft.world.phys.Vec3 eye = player.getEyePosition();
        net.minecraft.world.phys.Vec3 end = eye.add(player.getViewVector(1.0F).scale(PICK_RANGE));
        net.minecraft.world.phys.BlockHitResult hit = player.level().clip(
                new net.minecraft.world.level.ClipContext(eye, end,
                        net.minecraft.world.level.ClipContext.Block.OUTLINE,
                        net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
            return null;
        }
        return hit.getBlockPos().relative(hit.getDirection());
    }

    private void openChoice(Param.Choice choice) {
        openChoice = choice;
        int selected = choice.options().indexOf(choice.get());
        choiceScroll = Math.max(0, selected - CHOICE_MAX_VISIBLE / 2);
        clampChoiceScroll(choice.options().size());
    }

    private void selectChoice(Param.Choice choice, String value) {
        String before = choice.get();
        choice.set(value);
        if (command != null && ("walk".equals(command.id()) || "run".equals(command.id()))
                && "direction".equals(choice.id()) && "Coordinates".equals(before)
                && !"Coordinates".equals(choice.get())) {
            for (Param<?> sibling : command.params()) {
                if (sibling instanceof Param.Ints ints && "tolerance".equals(ints.id())) {
                    ints.set(1);
                    break;
                }
            }
        }
    }

    private void closeChoice() {
        openChoice = null;
        choiceScroll = 0;
        choiceDropHeight = 0;
    }

    private void clampChoiceScroll(int optionCount) {
        choiceScroll = Math.clamp(choiceScroll, 0,
                Math.max(0, optionCount - CHOICE_MAX_VISIBLE));
    }

    private boolean canExpose(Param<?> param) {
        return !compactMode && param != null;
    }

    private boolean isParameterEnabled(Param<?> param) {
        return param == null || parameterEnabled.test(param.id());
    }

    private int valueBoxX() {
        if (compactMode) {
            return getX() + getWidth() - VALUE_WIDTH - 6;
        }
        return getX() + getWidth() - VALUE_WIDTH - PORT_SIZE - PORT_GAP - 6;
    }

    private int rowsTop() {
        return getY() + PADDING + (compactMode ? 0 : LOGIC_HEIGHT);
    }

    private int centredValueX(int valueBoxX, Font font, String value) {
        return valueBoxX + Math.max(4, (VALUE_WIDTH - font.width(value)) / 2);
    }

    private int inputPortX() {
        return getX() + 4;
    }

    private int outputPortX() {
        return valueBoxX() + VALUE_WIDTH + PORT_GAP;
    }

    private void toggleExpanded(String paramId) {
        if (!expanded.remove(paramId)) {
            expanded.add(paramId);
        }
        rebuild();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (openChoice != null && mouseX >= choiceDropX && mouseX < choiceDropX + VALUE_WIDTH
                && mouseY >= choiceDropY && mouseY < choiceDropY + choiceDropHeight) {
            choiceScroll -= (int) Math.signum(scrollY);
            clampChoiceScroll(openChoice.options().size());
            return true;
        }
        if (!isMouseOver(mouseX, mouseY)) {
            return false;
        }
        scrollRows -= (int) Math.signum(scrollY);
        closeChoice();
        clampScroll();
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {}
}
