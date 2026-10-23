package com.etka.lune.client.gui.widget;

import com.etka.lune.bot.BotEngine;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.task.TaskRunner;
import com.etka.lune.client.gui.Accessibility;
import com.etka.lune.client.gui.LuneScreen;
import com.etka.lune.compat.Screens;
import com.etka.lune.config.BotConfig;
import com.etka.lune.task.FastTasks;
import com.etka.lune.task.TaskGraph;
import com.etka.lune.util.Lang;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Lune's fast tasks to pick from: the small jobs of {@link FastTasks} in two columns, what the
 * chosen one does under them, and what can be done with it.
 *
 * <p>The buttons follow what the bot is doing. With nothing running, <b>Run</b> starts it. With a
 * run going, <b>Add to queue</b> starts it once that run and anything already waiting are done -
 * the engine's own queue, which the Main tab shows and clears - and <b>Run now</b> stops the run
 * to start this one, as every Run button does. The popup stays open after a queueing, so a row of
 * them can be queued one after another, and closes after anything else. <b>Add to my tasks</b> is
 * the only way a fast task reaches the player's list: as a copy that is theirs to change, opened
 * on the Task tab.</p>
 *
 * <p>Drawn and routed by {@code LuneScreen} like {@link BesidePrompt}: while it is up it owns the
 * pointer and the keyboard.</p>
 */
public class FastTaskPicker extends AbstractWidget {

    private enum Action { ADD, CLOSE, RUN_NOW, RUN, QUEUE }

    private record Hit(Action action, int x, int y, int width, String label) {}

    /** A fast task as the popup holds it: built once as it opens, its icon looked up once. */
    private record Entry(FastTasks.Fast fast, TaskGraph graph, ItemStack icon) {}

    private static final int PADDING = 10;
    private static final int POPUP_W = 360;
    private static final int LINE_H = 10;
    private static final int TITLE_GAP = 6;
    private static final int SECTION_GAP = 8;
    private static final int COLUMNS = 2;
    private static final int ROW_H = 20;
    /** The rows' height when the screen is too short for the whole popup. */
    private static final int COMPACT_ROW_H = 18;
    private static final int COLUMN_GAP = 4;
    private static final int ICON = 16;
    private static final int BUTTON_H = 18;
    private static final int BUTTON_GAP = 6;
    private static final int BUTTON_PAD = 14;
    private static final int BUTTON_MIN_W = 48;
    /** Lines kept for the line about the run that is going, or about what a button just did. */
    private static final int STATUS_LINES = 2;
    private static final int DIM = 0xB0000000;
    private static final int ROW_HOVER = 0x30FFFFFF;
    private static final int REFUSED = 0xFFFF7777;

    /** The one chosen last, so the picker opens where the player left it. */
    private static int lastChosen;

    private List<Entry> entries = List.of();
    private int chosen;
    /** What the last button did, or why it could not: shown until another is pressed. */
    private String said = "";
    private boolean refusal;
    private Consumer<TaskGraph> onAdd = graph -> {};

    public FastTaskPicker() {
        super(-1000, -1000, 10, 10, Component.literal(Lang.get("lune.fast.title")));
        this.visible = false;
        this.active = false;
    }

    public boolean isOpen() {
        return visible;
    }

    /**
     * Opens the picker; {@code onAdd} is handed the copy Add to my tasks makes, already named in
     * the player's language, for the Task tab to take in.
     */
    public void open(Consumer<TaskGraph> onAdd) {
        List<Entry> built = new ArrayList<>();
        for (FastTasks.Fast fast : FastTasks.all()) {
            built.add(new Entry(fast, fast.graph(),
                    new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(fast.icon())))));
        }
        this.entries = List.copyOf(built);
        this.chosen = Math.clamp(lastChosen, 0, Math.max(0, entries.size() - 1));
        this.onAdd = onAdd == null ? graph -> {} : onAdd;
        this.said = "";
        this.refusal = false;
        this.visible = true;
        this.active = true;
        setFocused(true);
    }

    public void close() {
        visible = false;
        active = false;
        setFocused(false);
        setPosition(-1000, -1000);
        onAdd = graph -> {};
    }

    @Override
    public void setPosition(int x, int y) {
        setX(x);
        setY(y);
    }

    // --- what the buttons do -------------------------------------------------------------------

    private void act(Action action) {
        Entry entry = chosenEntry();
        if (entry == null) {
            close();
            return;
        }
        said = "";
        refusal = false;
        switch (action) {
            case CLOSE -> close();
            case ADD -> {
                Consumer<TaskGraph> add = onAdd;
                close();
                add.accept(FastTasks.forTaskList(entry.fast()));
            }
            case RUN, RUN_NOW -> start(entry);
            case QUEUE -> queue(entry);
        }
    }

    /** Starts it now, stopping whatever runs, exactly as the Run buttons do. */
    private void start(Entry entry) {
        BotEngine engine = BotEngine.get();
        if (!engine.runNow(new TaskRunner(entry.fast().graph()))) {
            // Refused - the terms, or this server's rules. Said here, where the button was.
            refused(engine.getLastMessage());
            return;
        }
        close();
        if (BotConfig.get().closePanelOnRun) {
            Screens.open(Minecraft.getInstance(), null);
        }
    }

    /** Behind everything already waiting; the popup stays, for the next one. */
    private void queue(Entry entry) {
        BotEngine engine = BotEngine.get();
        if (!engine.enqueue(new TaskRunner(entry.fast().graph()))) {
            refused(engine.getLastMessage());
            return;
        }
        said = Lang.get("lune.fast.queued", entry.graph().displayName(), engine.getQueue().size());
    }

    private void refused(String why) {
        said = why == null ? "" : why;
        refusal = true;
    }

    private void choose(int index) {
        if (entries.isEmpty()) {
            return;
        }
        chosen = Math.clamp(index, 0, entries.size() - 1);
        lastChosen = chosen;
    }

    private Entry chosenEntry() {
        return chosen >= 0 && chosen < entries.size() ? entries.get(chosen) : null;
    }

    /** Whether a run is going, which is what decides between Run and Add to queue. */
    private static boolean busy() {
        return BotEngine.get().getCurrent() != null;
    }

    private static Action primary(boolean busy) {
        return busy ? Action.QUEUE : Action.RUN;
    }

    /** The buttons at the right, the main one last, where the eye ends up. */
    private static List<Action> closing(boolean busy) {
        return busy ? List.of(Action.CLOSE, Action.RUN_NOW, Action.QUEUE)
                : List.of(Action.CLOSE, Action.RUN);
    }

    // --- what it says ----------------------------------------------------------------------------

    private static String label(Action action) {
        return Lang.get(switch (action) {
            case ADD -> "lune.fast.add";
            case CLOSE -> "lune.gui.recipe.close";
            case RUN_NOW -> "lune.fast.run_now";
            case RUN -> "lune.gui.tasks.run";
            case QUEUE -> "lune.fast.queue";
        });
    }

    /** The line over the buttons: what was just done, why not, or what is running. */
    private String statusLine() {
        if (!said.isEmpty()) {
            return said;
        }
        BotEngine engine = BotEngine.get();
        Task current = engine.getCurrent();
        if (current == null) {
            return "";
        }
        String running = current instanceof TaskRunner runner && runner.currentTask() != null
                ? runner.currentTask().displayName() : current.name();
        int waiting = engine.getQueue().size();
        return waiting == 0 ? Lang.get("lune.fast.busy", running)
                : Lang.get("lune.fast.busy_waiting", running, waiting);
    }

    private int statusColour() {
        if (said.isEmpty()) {
            return Accessibility.dim();
        }
        return refusal ? REFUSED : Accessibility.colour(Accessibility.Mark.GOOD);
    }

    // --- drawing --------------------------------------------------------------------------------

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                            float partialTick) {
        // Required by AbstractWidget; drawn through render() so LuneScreen can order it on top.
    }

    public void render(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        if (!visible) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        boolean busy = busy();
        Layout layout = layout(font, busy);
        extractor.fill(0, 0, Screens.current(mc).width, Screens.current(mc).height, DIM);
        LuneScreen.panel(extractor, layout.x, layout.y, layout.width, layout.height);

        var text = extractor.textRenderer();
        int inner = layout.width - PADDING * 2;
        int left = layout.x + PADDING;
        text.accept(left, layout.y + PADDING, Component.literal(clip(font,
                Lang.get("lune.fast.title"), inner)).withColor(LuneScreen.TEXT));
        drawLines(text, left, layout.y + PADDING + LINE_H + TITLE_GAP, layout.about, LuneScreen.TEXT_DIM);

        int columnWidth = columnWidth(layout.width);
        for (int index = 0; index < entries.size(); index++) {
            Entry entry = entries.get(index);
            int rowX = rowX(layout, index, columnWidth);
            int rowY = rowY(layout, index);
            boolean hovered = mouseX >= rowX && mouseX < rowX + columnWidth
                    && mouseY >= rowY && mouseY < rowY + layout.rowHeight;
            if (index == chosen) {
                extractor.fill(rowX, rowY, rowX + columnWidth, rowY + layout.rowHeight,
                        LuneScreen.ACCENT_SELECTION);
                outline(extractor, rowX, rowY, columnWidth, layout.rowHeight, LuneScreen.ACCENT);
            } else if (hovered) {
                extractor.fill(rowX, rowY, rowX + columnWidth, rowY + layout.rowHeight, ROW_HOVER);
            }
            if (!entry.icon().isEmpty()) {
                extractor.item(entry.icon(), rowX + 2, rowY + (layout.rowHeight - ICON) / 2);
            }
            int nameX = rowX + 2 + ICON + 4;
            text.accept(nameX, rowY + (layout.rowHeight - 8) / 2, Component.literal(clip(font,
                            entry.graph().displayName(), rowX + columnWidth - 3 - nameX))
                    .withColor(index == chosen ? LuneScreen.ACCENT_HOVER : LuneScreen.TEXT));
        }

        Entry entry = chosenEntry();
        if (entry != null) {
            text.accept(left, layout.detailY, Component.literal(clip(font,
                    entry.graph().displayName(), inner)).withColor(LuneScreen.TEXT));
            drawLines(text, left, layout.detailY + LINE_H + 2,
                    capped(wrap(font, FastTasks.about(entry.graph()), inner), layout.detailLines, font, inner),
                    LuneScreen.TEXT_DIM);
        }
        drawLines(text, left, layout.statusY,
                capped(wrap(font, statusLine(), inner), STATUS_LINES, font, inner), statusColour());

        Action primary = primary(busy);
        for (Hit hit : layout.buttons) {
            boolean hovered = mouseX >= hit.x() && mouseX < hit.x() + hit.width()
                    && mouseY >= hit.y() && mouseY < hit.y() + BUTTON_H;
            boolean main = hit.action() == primary;
            int fill = hovered ? LuneScreen.ACCENT_HOVER : main ? LuneScreen.ACCENT : LuneScreen.PANEL_BG;
            boolean dark = hovered || main;
            extractor.fill(hit.x(), hit.y(), hit.x() + hit.width(), hit.y() + BUTTON_H, fill);
            extractor.fill(hit.x(), hit.y(), hit.x() + hit.width(), hit.y() + 1, LuneScreen.PANEL_BORDER);
            extractor.fill(hit.x(), hit.y() + BUTTON_H - 1, hit.x() + hit.width(), hit.y() + BUTTON_H,
                    LuneScreen.PANEL_BORDER);
            int ink = dark ? 0xFF000000 : LuneScreen.TEXT;
            text.accept(hit.x() + (hit.width() - font.width(hit.label())) / 2, hit.y() + 5,
                    Component.literal(hit.label()).withColor(ink));
        }
    }

    private static void outline(GuiGraphicsExtractor extractor, int x, int y, int width, int height,
                                int colour) {
        extractor.fill(x, y, x + width, y + 1, colour);
        extractor.fill(x, y + height - 1, x + width, y + height, colour);
        extractor.fill(x, y, x + 1, y + height, colour);
        extractor.fill(x + width - 1, y, x + width, y + height, colour);
    }

    private static void drawLines(net.minecraft.client.gui.ActiveTextCollector text, int x, int y,
                                  List<String> lines, int colour) {
        for (int i = 0; i < lines.size(); i++) {
            text.accept(x, y + i * LINE_H, Component.literal(lines.get(i)).withColor(colour));
        }
    }

    private record Layout(int x, int y, int width, int height, List<String> about, int gridY,
                          int rowHeight, int detailY, int detailLines, int statusY,
                          List<Hit> buttons) {}

    /**
     * Where everything goes this frame. Measured from the words rather than fixed, because every
     * label is a different length in every language: the popup is as wide as the longest names
     * and the busy row of buttons need, never narrower than {@link #POPUP_W}, and as tall as the
     * longest description, so choosing another task never moves the buttons. A screen too short
     * for all of it loses the opening paragraph and a little of each row first.
     */
    private Layout layout(Font font, boolean busy) {
        Minecraft mc = Minecraft.getInstance();
        int screenWidth = Screens.current(mc).width;
        int screenHeight = Screens.current(mc).height;

        int widestName = 0;
        for (Entry entry : entries) {
            widestName = Math.max(widestName, font.width(entry.graph().displayName()));
        }
        int gridNeeded = COLUMNS * (2 + ICON + 4 + widestName + 6) + COLUMN_GAP * (COLUMNS - 1);
        // Sized for the busy row whether or not a run is going, so the popup does not change width
        // the moment a run starts or ends behind it.
        int buttonsNeeded = buttonWidth(font, Action.ADD) + BUTTON_GAP * 3
                + rowWidth(font, closing(true));
        int width = Math.min(Math.max(POPUP_W, Math.max(gridNeeded, buttonsNeeded) + PADDING * 2),
                screenWidth - 8);
        int inner = width - PADDING * 2;

        int detailLines = 1;
        for (Entry entry : entries) {
            detailLines = Math.max(detailLines, wrap(font, FastTasks.about(entry.graph()), inner).size());
        }
        int rows = (entries.size() + COLUMNS - 1) / COLUMNS;
        List<String> about = wrap(font, Lang.get("lune.fast.about"), inner);

        Layout full = measure(width, screenWidth, screenHeight, about, rows, ROW_H, detailLines, busy, font);
        if (full.height <= screenHeight - 8) {
            return full;
        }
        return measure(width, screenWidth, screenHeight, List.of(), rows, COMPACT_ROW_H,
                Math.min(detailLines, 3), busy, font);
    }

    private Layout measure(int width, int screenWidth, int screenHeight, List<String> about, int rows,
                           int rowHeight, int detailLines, boolean busy, Font font) {
        int gridTop = PADDING + LINE_H + TITLE_GAP + (about.isEmpty() ? 0 : about.size() * LINE_H + SECTION_GAP);
        int detailTop = gridTop + rows * rowHeight + SECTION_GAP;
        int statusTop = detailTop + LINE_H + 2 + detailLines * LINE_H + SECTION_GAP;
        int height = statusTop + STATUS_LINES * LINE_H + 6 + BUTTON_H + PADDING;

        int x = (screenWidth - width) / 2;
        int y = Math.max(4, (screenHeight - height) / 2);
        List<Hit> buttons = new ArrayList<>();
        int buttonY = y + height - PADDING - BUTTON_H;
        // Adding to the list stands at the left, apart from the buttons that do something now.
        buttons.add(new Hit(Action.ADD, x + PADDING, buttonY, buttonWidth(font, Action.ADD),
                label(Action.ADD)));
        int right = x + width - PADDING;
        List<Action> closing = closing(busy);
        for (int i = closing.size() - 1; i >= 0; i--) {
            Action action = closing.get(i);
            int w = buttonWidth(font, action);
            right -= w;
            buttons.add(new Hit(action, right, buttonY, w, label(action)));
            right -= BUTTON_GAP;
        }
        return new Layout(x, y, width, height, about, y + gridTop, rowHeight, y + detailTop,
                detailLines, y + statusTop, buttons);
    }

    private static int columnWidth(int popupWidth) {
        return (popupWidth - PADDING * 2 - COLUMN_GAP * (COLUMNS - 1)) / COLUMNS;
    }

    private static int rowX(Layout layout, int index, int columnWidth) {
        return layout.x + PADDING + (index % COLUMNS) * (columnWidth + COLUMN_GAP);
    }

    private static int rowY(Layout layout, int index) {
        return layout.gridY + (index / COLUMNS) * layout.rowHeight;
    }

    private static int rowWidth(Font font, List<Action> row) {
        int total = 0;
        for (Action action : row) {
            total += buttonWidth(font, action) + BUTTON_GAP;
        }
        return total - BUTTON_GAP;
    }

    private static int buttonWidth(Font font, Action action) {
        return Math.max(BUTTON_MIN_W, font.width(label(action)) + BUTTON_PAD);
    }

    /** The lines that fit in {@code max}, the last one closing on an ellipsis if any were left out. */
    private static List<String> capped(List<String> lines, int max, Font font, int width) {
        if (lines.size() <= max) {
            return lines;
        }
        List<String> kept = new ArrayList<>(lines.subList(0, max));
        String last = kept.get(max - 1);
        kept.set(max - 1, clip(font, last + " " + lines.get(max), width));
        return kept;
    }

    private static List<String> wrap(Font font, String value, int width) {
        List<String> lines = new ArrayList<>();
        if (value == null || value.isEmpty()) {
            return lines;
        }
        for (String paragraph : value.split("\n", -1)) {
            String rest = paragraph;
            if (rest.isEmpty()) {
                lines.add("");
                continue;
            }
            while (!rest.isEmpty()) {
                String head = font.plainSubstrByWidth(rest, width);
                if (head.isEmpty()) {
                    head = rest.substring(0, 1);
                }
                if (head.length() < rest.length()) {
                    int space = head.lastIndexOf(' ');
                    if (space > head.length() / 3) {
                        head = head.substring(0, space);
                    }
                }
                lines.add(head);
                rest = rest.substring(head.length()).stripLeading();
            }
        }
        return lines;
    }

    private static String clip(Font font, String value, int maxWidth) {
        if (font.width(value) <= maxWidth) {
            return value;
        }
        String fit = font.plainSubstrByWidth(value, Math.max(0, maxWidth - font.width("…")), false);
        return fit.isEmpty() ? "…" : fit + "…";
    }

    // --- input ----------------------------------------------------------------------------------

    /**
     * A row picks that fast task; a second click on the row already picked - a double click -
     * does what the main button does. A click outside closes the popup, having done nothing.
     */
    public void handleScreenMouseClick(double mouseX, double mouseY, int button, boolean doubleClick) {
        if (!visible || button != InputConstants.MOUSE_BUTTON_LEFT) {
            return;
        }
        boolean busy = busy();
        Layout layout = layout(Minecraft.getInstance().font, busy);
        if (mouseX < layout.x || mouseX >= layout.x + layout.width
                || mouseY < layout.y || mouseY >= layout.y + layout.height) {
            close();
            return;
        }
        for (Hit hit : layout.buttons) {
            if (mouseX >= hit.x() && mouseX < hit.x() + hit.width()
                    && mouseY >= hit.y() && mouseY < hit.y() + BUTTON_H) {
                act(hit.action());
                return;
            }
        }
        int columnWidth = columnWidth(layout.width);
        for (int index = 0; index < entries.size(); index++) {
            int rowX = rowX(layout, index, columnWidth);
            int rowY = rowY(layout, index);
            if (mouseX >= rowX && mouseX < rowX + columnWidth
                    && mouseY >= rowY && mouseY < rowY + layout.rowHeight) {
                boolean again = index == chosen;
                choose(index);
                said = "";
                refusal = false;
                if (again && doubleClick) {
                    act(primary(busy));
                }
                return;
            }
        }
    }

    /** Escape closes, Enter does what the main button does, and the arrows move through the rows. */
    public void handleScreenKeyPressed(int keyCode) {
        if (!visible) {
            return;
        }
        if (keyCode == InputConstants.KEY_ESCAPE) {
            close();
        } else if (keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER) {
            act(primary(busy()));
        } else if (keyCode == InputConstants.KEY_LEFT) {
            choose(chosen - 1);
        } else if (keyCode == InputConstants.KEY_RIGHT) {
            choose(chosen + 1);
        } else if (keyCode == InputConstants.KEY_UP) {
            choose(chosen - COLUMNS);
        } else if (keyCode == InputConstants.KEY_DOWN) {
            choose(chosen + COLUMNS);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return false;
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {}

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return visible;
    }
}
