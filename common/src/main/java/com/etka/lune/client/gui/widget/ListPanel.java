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

    private final List<T> items = new ArrayList<>();
    private final Function<T, String> labeller;
    private final Consumer<T> onSelect;

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
            text.accept(getX() + 5, rowY + 3,
                    Component.literal(labeller.apply(item)).withColor(colour));
        }

        // Scroll hint, so it's obvious there's more below.
        if (items.size() > visible) {
            text.accept(getX() + getWidth() - 14, getY() + getHeight() - 11,
                    Component.literal("▾").withColor(LuneScreen.TEXT_DIM));
        }
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        int row = (int) ((event.y() - getY() - PADDING) / ROW_HEIGHT);
        int index = scrollRows + row;
        if (row < 0 || index < 0 || index >= items.size()) {
            return;
        }
        selected = items.get(index);
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
}
