package com.etka.lune.client.gui.widget;

import com.etka.lune.util.Lang;
import com.etka.lune.client.gui.LuneScreen;
import com.etka.lune.client.gui.UiScale;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * A scrollable list with one open row, and optionally more selected beside it
 * ({@link #setMultiSelect}).
 * <p>
 * Written rather than reusing {@link net.minecraft.client.gui.components.ObjectSelectionList}
 * because vanilla's list wants one widget per entry and owns its own layout, which fights the
 * fixed two-pane arrangement here. This draws rows directly and clips by simply not drawing rows
 * that fall outside its bounds, so it needs no scissor handling.
 *
 * <h2>Reaching it without a mouse</h2>
 *
 * <p>Drawing rows rather than making them widgets is what costs this list its keyboard, so it has
 * to grow one by hand. Tab reaches the list; the arrows move the selection inside it, scrolling to
 * follow; Enter runs the row's first available action, which on the dashboard is Start. Without
 * that, the panel's whole reason for existing - pressing Start - was a thing only a pointer could
 * do, and the row actions were 14px cells you had to hover to discover.
 *
 * <p>The narration says the row and what can be done to it, because a name on its own tells a
 * screen reader user that something is selected and nothing about what that buys them.</p>
 *
 * <h2>Moving a row</h2>
 *
 * <p>Where the order is the player's to keep ({@link #setOnMove}), a row held still for a second
 * lifts and can be carried to another place, the rows in between moving along to make room.
 * {@link ListReorder} says why it is held first rather than simply dragged.</p>
 *
 * @param <T> the row model type
 */
public class ListPanel<T> extends AbstractWidget {

    public static final int ROW_HEIGHT = 13;
    private static final int PADDING = 3;

    private static final int ROW_HOVER = 0x30FFFFFF;
    private static final int ROW_SELECTED = LuneScreen.ACCENT_SELECTION;
    private static final int ACTION_CELL = 14;
    /** The width a row's mark takes before its label: one 10px icon. */
    private static final int MARK_CELL = 10;
    private static final int ACTION_HOVER = 0x405C6B80;
    private static final int ACTION_DISABLED = 0xFF5A5A64;
    /** How often a row carried past the first or last one on show scrolls the list a row. */
    private static final long EDGE_SCROLL_MILLIS = 100L;

    public record RowAction<T>(GuiIcons.Icon icon, String label, Consumer<T> handler,
                               Predicate<T> enabled, Predicate<T> visible) {
        public RowAction(GuiIcons.Icon icon, String label, Consumer<T> handler) {
            this(icon, label, handler, item -> true, item -> true);
        }

        public RowAction(GuiIcons.Icon icon, String label, Consumer<T> handler,
                         Predicate<T> enabled) {
            this(icon, label, handler, enabled, item -> true);
        }
    }

    private final List<T> items = new ArrayList<>();
    private final Function<T, String> labeller;
    private final Consumer<T> onSelect;
    private List<RowAction<T>> actions = List.of();
    /** The key cap a row wears, or null for none; see {@link #setBadge}. */
    private Function<T, String> badge = item -> null;
    private Function<T, String> badgeTip = item -> null;
    /** What a badged row says when the badge leaves too little room for its whole label. */
    private Function<T, String> badgedLabeller;
    /** Which rows wear a mark before their label, and what it looks like; see {@link #setMark}. */
    private Predicate<T> marked = item -> false;
    private GuiIcons.Icon markIcon;
    private int markColour;
    private Function<T, String> markTip = item -> null;

    private final ListSelection<T> selection = new ListSelection<>();
    private boolean multiSelect;
    private Runnable onDeleteKey;
    private int scrollRows;
    /** What moving a lifted row does; null leaves rows where they are. */
    private BiConsumer<T, List<T>> onMove;
    private final ListReorder<T> reorder = new ListReorder<>();
    private long lastEdgeScroll;
    /** Where the hold's time comes from. The tests hand it a clock of their own. */
    LongSupplier clock = Util::getMillis;

    public ListPanel(int x, int y, int width, int height, Function<T, String> labeller, Consumer<T> onSelect) {
        super(x, y, width, height, Component.empty());
        this.labeller = labeller;
        this.onSelect = onSelect;
    }

    public void setItems(List<T> newItems) {
        items.clear();
        items.addAll(newItems);
        // Keep the selection only if it survived the update (e.g. after filtering by search).
        selection.retain(items);
        reorder.retain(items);
        clampScroll();
    }

    /** The open row: the one the rest of the screen is showing. */
    public T getSelected() {
        return selection.open();
    }

    /** Opens one row on its own, dropping any others that were selected beside it. */
    public void setSelected(T item) {
        selection.only(item);
    }

    /** Every selected row in list order - the open one and any chosen beside it. */
    public List<T> getSelection() {
        return selection.inOrder(items);
    }

    /**
     * Lets Ctrl+click add or remove a row, Shift+click take a run of rows and Ctrl+A take them all.
     *
     * <p>Off unless asked for: on a list whose rows are started or paused one at a time, a second
     * highlighted row would promise an action that nothing performs.</p>
     */
    public void setMultiSelect(boolean multiSelect) {
        this.multiSelect = multiSelect;
        if (!multiSelect) {
            selection.only(selection.open());
        }
    }

    /** What Delete or Backspace does while the list has the keyboard; nothing when unset. */
    public void setOnDeleteKey(Runnable onDeleteKey) {
        this.onDeleteKey = onDeleteKey;
    }

    /**
     * Lets a row be held until it lifts and carried to another place, and says what the move
     * does: it is handed the row that moved and every row in its new order.
     *
     * <p>The list moves the row on screen at once, and the owner makes it stick - on the task
     * lists, in the store whose order both of them show. Off unless asked for: a list whose order
     * is not the player's, like another mod's waypoints sorted nearest first, would only be put back
     * the way it was on its next refresh.</p>
     */
    public void setOnMove(BiConsumer<T, List<T>> onMove) {
        this.onMove = onMove;
    }

    /** Adds small per-row controls without changing the normal click-to-select behavior. */
    public void setActions(List<RowAction<T>> actions) {
        this.actions = actions == null ? List.of() : List.copyOf(actions);
    }

    /**
     * A small key cap at the right end of a row, before its actions, and what hovering it says.
     *
     * <p>Only a row with something to show wears one, so a list where nothing has a key looks
     * exactly as it did before there were keys.</p>
     *
     * <p>{@code shortLabel} is what a badged row says when the badge leaves too little room for
     * the whole label - a task's name without its step count. The badge takes the width of a word
     * or two, and a row reading "1. Chop Wood  (1" beside it looks broken where "1. Chop Wood"
     * looks finished. The whole label is still the row's tooltip.</p>
     */
    public void setBadge(Function<T, String> badge, Function<T, String> badgeTip,
                         Function<T, String> shortLabel) {
        this.badge = badge == null ? item -> null : badge;
        this.badgeTip = badgeTip == null ? item -> null : badgeTip;
        this.badgedLabeller = shortLabel;
    }

    /**
     * A small icon before the label of every row that is a different kind of thing from the rest,
     * the label in the icon's colour, and what hovering the icon says.
     *
     * <p>A kind, not a state: on the task lists it marks the tasks that run beside the player,
     * which is a fact about the task whether or not it is running. Only marked rows move their
     * label over, so a list with none looks exactly as it did.</p>
     */
    public void setMark(Predicate<T> marked, GuiIcons.Icon icon, int colour, Function<T, String> tip) {
        this.marked = marked == null ? item -> false : marked;
        this.markIcon = icon;
        this.markColour = colour;
        this.markTip = tip == null ? item -> null : tip;
    }

    private int visibleRows() {
        return Math.max(1, (getHeight() - PADDING * 2) / ROW_HEIGHT);
    }

    private void clampScroll() {
        int maxScroll = Math.max(0, items.size() - visibleRows());
        scrollRows = Math.clamp(scrollRows, 0, maxScroll);
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        clampScroll();
        long now = clock.getAsLong();
        followHold(mouseX, mouseY, now);
        T holding = reorder.holding();
        T lifted = reorder.lifted();
        // Carrying a row, the list reads as it will once the row is let go: the row already in
        // the place it is going and the rows between moved along, so the drop holds no surprise.
        List<T> shown = reorder.preview(items);
        int visible = visibleRows();
        var text = extractor.textRenderer();
        // What the row under the pointer has to say, if anything: its whole label when it was cut
        // short, or what its badge means. Shown once every row is drawn; see showTooltip.
        String tip = null;
        // Where the keyboard is. Without it, tabbing to the list moves the focus somewhere the
        // player cannot see, which is the same as it having gone nowhere.
        if (isFocused()) {
            extractor.outline(getX(), getY(), getWidth(), getHeight(), LuneScreen.ACCENT);
        }

        for (int row = 0; row < visible; row++) {
            int index = scrollRows + row;
            if (index >= shown.size()) {
                break;
            }
            T item = shown.get(index);
            int rowY = getY() + PADDING + row * ROW_HEIGHT;

            // While a row is carried nothing is pointed at but the place it is going.
            boolean hovered = lifted == null && mouseX >= getX() && mouseX < getX() + getWidth()
                    && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
            if (selection.contains(item)) {
                extractor.fill(getX() + 1, rowY, getX() + getWidth() - 1, rowY + ROW_HEIGHT, ROW_SELECTED);
            } else if (hovered) {
                extractor.fill(getX() + 1, rowY, getX() + getWidth() - 1, rowY + ROW_HEIGHT, ROW_HOVER);
            }

            // Every selected row is filled; only the open one - the one on screen - is lit too. A
            // marked row is its mark's colour otherwise, so the kind shows down the whole list.
            boolean isMarked = markIcon != null && marked.test(item);
            int colour = item.equals(selection.open()) ? LuneScreen.ACCENT
                    : isMarked ? markColour : LuneScreen.TEXT;
            int labelX = getX() + 5;
            boolean markHovered = false;
            if (isMarked) {
                int markX = getX() + 3;
                GuiIcons.draw(extractor, markIcon, markX, rowY + 1, markColour);
                markHovered = hovered && mouseX >= markX && mouseX < markX + MARK_CELL;
                labelX = markX + MARK_CELL + 1;
            }
            List<RowAction<T>> visibleActions = visibleActions(item);
            int actionStart = actionStart(visibleActions.size());
            int textRight = actionStart - 5;
            // Where the label's own stretch of the row ends: past it are the badge and the actions,
            // which have things of their own to say.
            int labelRight = actionStart;
            String badgeLabel = badge.apply(item);
            if (badgeLabel != null) {
                int badgeRight = actionStart - (visibleActions.isEmpty() ? 0 : 2);
                int badgeX = badgeRight - KeyCap.width(badgeLabel);
                boolean badgeHovered = hovered && mouseX >= badgeX && mouseX < badgeRight;
                KeyCap.draw(extractor, badgeX, rowY + 1, badgeRight - badgeX, KeyCap.BADGE_HEIGHT,
                        badgeLabel, badgeHovered ? KeyCap.Look.HOVER : KeyCap.Look.REST);
                if (badgeHovered) {
                    tip = badgeTip.apply(item);
                }
                textRight = badgeX - 4;
                labelRight = badgeX;
            }
            String label = labeller.apply(item);
            int available = Math.max(0, textRight - labelX);
            if (markHovered) {
                tip = markTip.apply(item);
            }
            if (MinecraftFont.width(label) > available) {
                // A pane narrow enough to cut a name is a pane the player chose the width of, so
                // the answer is not to widen it - but a row reading "Woodland Cleanup  (8 s" still
                // has to be identifiable without dragging the splitter and back again.
                if (hovered && !markHovered && mouseX < labelRight) {
                    tip = label;
                }
                if (badgeLabel != null && badgedLabeller != null) {
                    label = badgedLabeller.apply(item);
                }
                if (MinecraftFont.width(label) > available) {
                    label = MinecraftFont.plainSubstrByWidth(label, available);
                }
            }
            text.accept(labelX, rowY + 3, Component.literal(label).withColor(colour));

            for (int actionIndex = 0; actionIndex < visibleActions.size(); actionIndex++) {
                RowAction<T> action = visibleActions.get(actionIndex);
                int actionX = actionStart + actionIndex * ACTION_CELL;
                boolean actionHovered = lifted == null
                        && mouseX >= actionX && mouseX < actionX + ACTION_CELL
                        && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
                if (actionHovered) {
                    extractor.fill(actionX, rowY + 1, actionX + ACTION_CELL - 1,
                            rowY + ROW_HEIGHT - 1, ACTION_HOVER);
                }
                int iconColour = action.enabled().test(item)
                        ? actionHovered ? LuneScreen.TEXT : LuneScreen.ACCENT
                        : ACTION_DISABLED;
                GuiIcons.draw(extractor, action.icon(), actionX + 2, rowY + 1, iconColour);
            }

            if (item.equals(holding)) {
                // The hold filling up along the row's foot, so a press held on purpose is seen to
                // be doing something before the row lifts.
                float progress = reorder.progress(now);
                if (progress > 0.0F) {
                    extractor.fill(getX() + 1, rowY + ROW_HEIGHT - 1,
                            getX() + 1 + Math.round((getWidth() - 2) * progress), rowY + ROW_HEIGHT,
                            LuneScreen.ACCENT);
                }
            } else if (item.equals(lifted)) {
                extractor.outline(getX() + 1, rowY, getWidth() - 2, ROW_HEIGHT, LuneScreen.ACCENT);
            }
        }

        if (lifted != null) {
            extractor.requestCursor(CursorTypes.RESIZE_NS);
        }
        // Scroll hint, so it's obvious there's more below.
        if (items.size() > visible) {
            text.accept(getX() + getWidth() - 14, getY() + getHeight() - 11,
                    Component.literal("▾").withColor(LuneScreen.TEXT_DIM));
        }
        // Not over a row being held either: that press is no longer asking what the row is.
        if (tip != null && holding == null) {
            showTooltip(extractor, tip, mouseX, mouseY);
        }
    }

    /**
     * Brings a hold up to date with the pointer, every frame.
     *
     * <p>Every frame and not only when the mouse moves, because holding means keeping the mouse
     * still, and a mouse kept still sends nothing: the row has to lift without being told to. The
     * frames also choose where a drop lands; {@link ListReorder} says why.</p>
     *
     * <p>It asks nothing of the game. It used to ask {@code MouseHandler.isLeftPressed()} whether
     * the button was still down, and let go of the hold when it said no - which it always did,
     * because vanilla only keeps that flag while no screen is open. Every hold ended on the frame
     * after the press. A release that never reaches the list is caught by {@link #setFocused}
     * instead. Package-private so the tests can play frames, which is the path a real hold takes.</p>
     */
    void followHold(int mouseX, int mouseY, long now) {
        if (reorder.lifted() != null) {
            scrollAtEdge(mouseX, mouseY, now);
        }
        // Where the pointer is in the rows rather than on the screen; see ListReorder.frame.
        reorder.frame(mouseX, mouseY + scrollRows * ROW_HEIGHT, placeAt(mouseX, mouseY), now);
    }

    /**
     * Losing the focus lets go of a hold. The button then comes up over whatever has the focus now
     * - Tab moved it mid-hold, or the tab was changed under the pointer - so the list would never
     * hear the release, and a lifted row would stay stuck to the pointer.
     */
    @Override
    public void setFocused(boolean focused) {
        super.setFocused(focused);
        if (!focused) {
            reorder.cancel();
        }
    }

    /**
     * Carried above the first row on show or below the last, the list scrolls a row at a time that
     * way, so a task can be taken to one scrolled out of sight.
     *
     * <p>Only past the rows, never over them: a list that scrolled under the row the player was
     * aiming at would move it away just as they reached it.</p>
     */
    private void scrollAtEdge(int mouseX, int mouseY, long now) {
        if (mouseX < getX() || mouseX >= getX() + getWidth()
                || now - lastEdgeScroll < EDGE_SCROLL_MILLIS) {
            return;
        }
        int rowsTop = getY() + PADDING;
        int step = mouseY < rowsTop ? -1 : mouseY >= rowsTop + visibleRows() * ROW_HEIGHT ? 1 : 0;
        if (step != 0) {
            scrollRows += step;
            clampScroll();
            lastEdgeScroll = now;
        }
    }

    /**
     * The place in the list a carried row takes with the pointer at a point: the row under it, the
     * first or last row on show while it is above or below them, and -1 beside the list.
     *
     * <p>Above or below rather than nowhere, because that is where the list scrolls from
     * ({@link #scrollAtEdge}): a row carried there rides the edge as the list moves, and reaches the
     * very top or bottom when it stops. Beside the list is the way to let go of it unmoved.</p>
     */
    private int placeAt(double x, double y) {
        if (items.isEmpty() || x < getX() || x >= getX() + getWidth()) {
            return -1;
        }
        int row = (int) Math.floor((y - getY() - PADDING) / ROW_HEIGHT);
        int lastOnShow = Math.min(visibleRows(), items.size() - scrollRows) - 1;
        return scrollRows + Math.clamp(row, 0, Math.max(0, lastOnShow));
    }

    /**
     * A row's tooltip, beside the pointer.
     *
     * <p>Not the widget's own tooltip, which is what this used to set. Vanilla places a widget's
     * tooltip clear of the whole widget - right for a button, and for a list as tall as its pane it
     * meant a row halfway down had its tooltip thrown up to the top of the screen, nowhere near the
     * row. It is also drawn at the end of the frame, after the panel's scale has been undone, so
     * the anchor is handed over in the game's pixels rather than Lune's, as every other tooltip on
     * the panel does.</p>
     */
    private static void showTooltip(GuiGraphicsExtractor extractor, String line, int mouseX,
                                    int mouseY) {
        Minecraft minecraft = Minecraft.getInstance();
        extractor.setTooltipForNextFrame(minecraft.font,
                Tooltip.splitTooltip(minecraft, Component.literal(line)),
                UiScale.toGamePixels(mouseX), UiScale.toGamePixels(mouseY));
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        // Whatever this press turns out to be, it is not part of a hold that came before it.
        reorder.cancel();
        int row = (int) Math.floor((event.y() - getY() - PADDING) / ROW_HEIGHT);
        int index = scrollRows + row;
        if (row < 0 || index < 0 || index >= items.size()) {
            return;
        }
        T item = items.get(index);
        List<RowAction<T>> visibleActions = visibleActions(item);
        int actionIndex = actionAt(event.x(), event.y(), getY() + PADDING + row * ROW_HEIGHT,
                visibleActions.size());
        if (actionIndex >= 0) {
            RowAction<T> action = visibleActions.get(actionIndex);
            selection.only(item);
            if (action.enabled().test(item)) {
                action.handler().accept(item);
            }
            return;
        }
        if (multiSelect && event.hasShiftDown()) {
            if (selection.range(items, item)) {
                onSelect.accept(selection.open());
            }
            return;
        }
        // With the quirk: Cmd on a Mac, where Ctrl+click is already the right button.
        if (multiSelect && event.hasControlDownWithQuirk()) {
            if (selection.toggle(item)) {
                onSelect.accept(selection.open());
            }
            return;
        }
        selection.only(item);
        onSelect.accept(item);
        if (onMove != null) {
            // Kept still from here, the row lifts and can be carried; see ListReorder.
            reorder.press(item, clock.getAsLong());
        }
    }

    /**
     * Lets go of a held row. A lifted one stays in the place the last frame showed it in - what
     * was on screen as the button came up - and not the place under the release itself, which
     * near a row's edge can be the one beside it.
     */
    @Override
    public void onRelease(MouseButtonEvent event) {
        ListReorder.Move<T> move = reorder.release(items);
        if (move == null || onMove == null || !isActive()) {
            return;
        }
        // Moved here as well as by the owner, so the frames before its next refresh do not show
        // the old order for a moment after the drop.
        items.add(move.to(), items.remove(items.indexOf(move.lifted())));
        onMove.accept(move.lifted(), List.copyOf(items));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!isMouseOver(mouseX, mouseY)) {
            return false;
        }
        scrollRows -= (int) Math.signum(scrollY);
        clampScroll();
        return true;
    }

    /**
     * Arrows move the selection, Enter runs the row's first available action.
     *
     * <p>First rather than a chosen one: the actions are written most-useful-first, so Enter on a
     * saved task starts it and Enter on one already running pauses it - which is what the two
     * leading actions are in both cases. A row whose actions are all unavailable does nothing
     * rather than guessing.</p>
     *
     * <p>Where several rows can be chosen, Ctrl+A chooses them all; and where rows can be deleted,
     * Delete or Backspace asks to, the same two keys that delete cards on the canvas.</p>
     */
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (items.isEmpty()) {
            return false;
        }
        int key = event.key();
        if (multiSelect && event.isSelectAll()) {
            selection.all(items);
            return true;
        }
        if (onDeleteKey != null
                && (key == InputConstants.KEY_DELETE || key == InputConstants.KEY_BACKSPACE)) {
            onDeleteKey.run();
            return true;
        }
        if (key == InputConstants.KEY_DOWN || key == InputConstants.KEY_UP) {
            move(key == InputConstants.KEY_DOWN ? 1 : -1);
            return true;
        }
        if (key == InputConstants.KEY_HOME || key == InputConstants.KEY_END) {
            selection.only(items.get(key == InputConstants.KEY_HOME ? 0 : items.size() - 1));
            onSelect.accept(selection.open());
            revealSelected();
            return true;
        }
        if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
            return runFirstAction();
        }
        return super.keyPressed(event);
    }

    private void move(int delta) {
        T selected = selection.open();
        int at = selected == null ? -1 : items.indexOf(selected);
        // No selection yet: down lands on the first row and up on the last, so one key press from
        // focusing the list always puts you somewhere.
        int next = at < 0 ? (delta > 0 ? 0 : items.size() - 1) : Math.clamp(at + delta, 0, items.size() - 1);
        selection.only(items.get(next));
        onSelect.accept(selection.open());
        revealSelected();
    }

    /** Scrolls the least amount that brings the selected row inside the visible window. */
    private void revealSelected() {
        T selected = selection.open();
        int at = selected == null ? -1 : items.indexOf(selected);
        if (at < 0) {
            return;
        }
        int visible = visibleRows();
        if (at < scrollRows) {
            scrollRows = at;
        } else if (at >= scrollRows + visible) {
            scrollRows = at - visible + 1;
        }
        clampScroll();
    }

    private boolean runFirstAction() {
        T selected = selection.open();
        if (selected == null) {
            return false;
        }
        for (RowAction<T> action : visibleActions(selected)) {
            if (action.enabled().test(selected)) {
                action.handler().accept(selected);
                return true;
            }
        }
        return false;
    }

    /**
     * What the list is and what Enter would do, rather than only which row is selected.
     *
     * <p>A name on its own tells somebody that a thing is selected; it does not tell them the row
     * can be started, renamed or opened, which is the part they cannot see.</p>
     */
    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        T selected = selection.open();
        if (selected == null) {
            return;
        }
        List<RowAction<T>> available = visibleActions(selected).stream()
                .filter(action -> action.enabled().test(selected))
                .toList();
        String actions = available.isEmpty()
                ? Lang.get("lune.gui.list.no_actions")
                : available.stream().map(RowAction::label).collect(Collectors.joining(", "));
        output.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE,
                Component.literal(Lang.get("lune.gui.list.keyboard_hint",
                        labeller.apply(selected), actions)));
    }

    private int actionStart(int actionCount) {
        return getX() + getWidth() - PADDING - actionCount * ACTION_CELL;
    }

    private int actionAt(double mouseX, double mouseY, int rowY, int actionCount) {
        if (mouseY < rowY || mouseY >= rowY + ROW_HEIGHT) {
            return -1;
        }
        int start = actionStart(actionCount);
        int index = (int) ((mouseX - start) / ACTION_CELL);
        return index >= 0 && index < actionCount ? index : -1;
    }

    /** Returns the action name for the cell under the pointer, for a small screen tooltip. */
    public String hoveredActionLabel(double mouseX, double mouseY) {
        if (!visible || actions.isEmpty() || !isMouseOver(mouseX, mouseY)) {
            return null;
        }
        int row = (int) Math.floor((mouseY - getY() - PADDING) / ROW_HEIGHT);
        int index = scrollRows + row;
        if (row < 0 || index < 0 || index >= items.size()) {
            return null;
        }
        List<RowAction<T>> visibleActions = visibleActions(items.get(index));
        int actionIndex = actionAt(mouseX, mouseY, getY() + PADDING + row * ROW_HEIGHT,
                visibleActions.size());
        if (actionIndex < 0) {
            return null;
        }
        RowAction<T> action = visibleActions.get(actionIndex);
        return action.enabled().test(items.get(index)) ? action.label() : Lang.get("lune.gui.list.action_unavailable", action.label());
    }

    private List<RowAction<T>> visibleActions(T item) {
        return actions.stream().filter(action -> action.visible().test(item)).toList();
    }

    /** Avoids carrying a Minecraft Font through the widget's public API. */
    private static final class MinecraftFont {
        private static int width(String value) {
            return net.minecraft.client.Minecraft.getInstance().font.width(value);
        }

        private static String plainSubstrByWidth(String value, int maxWidth) {
            return net.minecraft.client.Minecraft.getInstance().font
                    .plainSubstrByWidth(value, maxWidth, false);
        }
    }
}
