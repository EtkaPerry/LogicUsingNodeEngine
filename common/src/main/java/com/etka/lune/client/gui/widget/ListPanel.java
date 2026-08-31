package com.etka.lune.client.gui.widget;

import com.etka.lune.client.gui.LuneScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A scrollable, single-selection list.
 * <p>
 * Written rather than reusing {@link net.minecraft.client.gui.components.ObjectSelectionList}
 * because vanilla's list wants one widget per entry and owns its own layout, which fights the
 * fixed two-pane arrangement here. This draws rows directly and clips by simply not drawing rows
 * that fall outside its bounds, so it needs no scissor handling.
 *
 * @param <T> the row model type
 */
public class ListPanel<T> extends AbstractWidget {

    public static final int ROW_HEIGHT = 13;
    private static final int PADDING = 3;

    private static final int ROW_HOVER = 0x30FFFFFF;
    private static final int ROW_SELECTED = 0x504C9EFF;
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

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        if (selected != null) {
            output.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE,
                    Component.literal(labeller.apply(selected)));
        }
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
        return action.enabled().test(items.get(index)) ? action.label() : action.label() + " unavailable";
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
