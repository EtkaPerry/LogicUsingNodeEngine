package com.etka.lune.client.gui.widget;

import com.etka.lune.util.Lang;
import com.etka.lune.client.gui.LuneScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * A scrollable, single-selection list.
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
 * @param <T> the row model type
 */
public class ListPanel<T> extends AbstractWidget {

    public static final int ROW_HEIGHT = 13;
    private static final int PADDING = 3;

    private static final int ROW_HOVER = 0x30FFFFFF;
    private static final int ROW_SELECTED = LuneScreen.ACCENT_SELECTION;
    private static final int ACTION_CELL = 14;
    private static final int ACTION_HOVER = 0x405C6B80;
    private static final int ACTION_DISABLED = 0xFF5A5A64;

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

    private T selected;
    private int scrollRows;

    public ListPanel(int x, int y, int width, int height, Function<T, String> labeller, Consumer<T> onSelect) {
        super(x, y, width, height, Component.empty());
        this.labeller = labeller;
        this.onSelect = onSelect;
    }

    public void setItems(List<T> newItems) {
        items.clear();
        items.addAll(newItems);
        // Keep the selection only if it survived the update (e.g. after filtering by search).
        if (selected != null && !items.contains(selected)) {
            selected = null;
        }
        clampScroll();
    }

    public T getSelected() {
        return selected;
    }

    public void setSelected(T item) {
        this.selected = item;
    }

    /** Adds small per-row controls without changing the normal click-to-select behavior. */
    public void setActions(List<RowAction<T>> actions) {
        this.actions = actions == null ? List.of() : List.copyOf(actions);
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
        int visible = visibleRows();
        var text = extractor.textRenderer();
        // Rebuilt each frame from whichever row is under the pointer; see the trimmed labels below.
        setTooltip(null);
        // Where the keyboard is. Without it, tabbing to the list moves the focus somewhere the
        // player cannot see, which is the same as it having gone nowhere.
        if (isFocused()) {
            extractor.outline(getX(), getY(), getWidth(), getHeight(), LuneScreen.ACCENT);
        }

        for (int row = 0; row < visible; row++) {
            int index = scrollRows + row;
            if (index >= items.size()) {
                break;
            }
            T item = items.get(index);
            int rowY = getY() + PADDING + row * ROW_HEIGHT;

            boolean hovered = mouseX >= getX() && mouseX < getX() + getWidth()
                    && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
            if (item.equals(selected)) {
                extractor.fill(getX() + 1, rowY, getX() + getWidth() - 1, rowY + ROW_HEIGHT, ROW_SELECTED);
            } else if (hovered) {
                extractor.fill(getX() + 1, rowY, getX() + getWidth() - 1, rowY + ROW_HEIGHT, ROW_HOVER);
            }

            int colour = item.equals(selected) ? LuneScreen.ACCENT : LuneScreen.TEXT;
            List<RowAction<T>> visibleActions = visibleActions(item);
            int textRight = actionStart(visibleActions.size()) - 5;
            String label = labeller.apply(item);
            int available = Math.max(0, textRight - (getX() + 5));
            if (MinecraftFont.width(label) > available) {
                // A pane narrow enough to cut a name is a pane the player chose the width of, so
                // the answer is not to widen it - but a row reading "Woodland Cleanup  (8 s" still
                // has to be identifiable without dragging the splitter and back again.
                if (hovered) {
                    setTooltip(Tooltip.create(Component.literal(label)));
                }
                label = MinecraftFont.plainSubstrByWidth(label, available);
            }
            text.accept(getX() + 5, rowY + 3, Component.literal(label).withColor(colour));

            for (int actionIndex = 0; actionIndex < visibleActions.size(); actionIndex++) {
                RowAction<T> action = visibleActions.get(actionIndex);
                int actionX = actionStart(visibleActions.size()) + actionIndex * ACTION_CELL;
                boolean actionHovered = mouseX >= actionX && mouseX < actionX + ACTION_CELL
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
        }

        // Scroll hint, so it's obvious there's more below.
        if (items.size() > visible) {
            text.accept(getX() + getWidth() - 14, getY() + getHeight() - 11,
                    Component.literal("▾").withColor(LuneScreen.TEXT_DIM));
        }
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
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
            selected = item;
            if (action.enabled().test(item)) {
                action.handler().accept(item);
            }
            return;
        }
        selected = item;
        onSelect.accept(selected);
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
     */
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (items.isEmpty()) {
            return false;
        }
        int key = event.key();
        if (key == InputConstants.KEY_DOWN || key == InputConstants.KEY_UP) {
            move(key == InputConstants.KEY_DOWN ? 1 : -1);
            return true;
        }
        if (key == InputConstants.KEY_HOME || key == InputConstants.KEY_END) {
            selected = items.get(key == InputConstants.KEY_HOME ? 0 : items.size() - 1);
            onSelect.accept(selected);
            revealSelected();
            return true;
        }
        if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
            return runFirstAction();
        }
        return super.keyPressed(event);
    }

    private void move(int delta) {
        int at = selected == null ? -1 : items.indexOf(selected);
        // No selection yet: down lands on the first row and up on the last, so one key press from
        // focusing the list always puts you somewhere.
        int next = at < 0 ? (delta > 0 ? 0 : items.size() - 1) : Math.clamp(at + delta, 0, items.size() - 1);
        selected = items.get(next);
        onSelect.accept(selected);
        revealSelected();
    }

    /** Scrolls the least amount that brings the selected row inside the visible window. */
    private void revealSelected() {
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
