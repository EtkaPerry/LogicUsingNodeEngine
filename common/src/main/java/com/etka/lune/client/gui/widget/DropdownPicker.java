package com.etka.lune.client.gui.widget;

import com.etka.lune.client.gui.LuneScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A compact dropdown button. Shows the selected item and, when open, a scrollable list underneath it.
 * <p>
 * Used for the command picker and blueprint picker in the Task tab: one click to open,
 * one click to choose, no painful cycling through dozens of options.
 *
 * @param <T> the type of item being picked
 */
public class DropdownPicker<T> extends AbstractWidget {

    private static final int ROW_HEIGHT = 13;
    private static final int MAX_VISIBLE = 5;
    private static final int DROPDOWN_BG = 0xFF1A1A20;
    private static final int ROW_HOVER = 0x30FFFFFF;
    private static final int ROW_SELECTED = 0x504C9EFF;

    private final List<T> items;
    private final Function<T, String> labeller;
    private final Consumer<T> onSelect;
    private final String title;

    private int selected;
    private boolean open = false;
    private int scroll;

    public DropdownPicker(int x, int y, int width, int height, String title,
                          List<T> items, Function<T, String> labeller, Consumer<T> onSelect, int initial) {
        super(x, y, width, height, Component.literal(title));
        this.title = title;
        this.items = List.copyOf(items);
        this.labeller = labeller;
        this.onSelect = onSelect;
        this.selected = Math.clamp(initial, 0, Math.max(0, this.items.size() - 1));
    }

    public T getSelected() {
        return items.isEmpty() ? null : items.get(selected);
    }

    public void setSelected(int index) {
        if (index >= 0 && index < items.size()) {
            this.selected = index;
        }
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        var text = extractor.textRenderer();
        var font = Minecraft.getInstance().font;
        int border = LuneScreen.PANEL_BORDER;
        int bg = LuneScreen.PANEL_BG;

        // Button background and border.
        extractor.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), bg);
        extractor.fill(getX(), getY(), getX() + getWidth(), getY() + 1, border);
        extractor.fill(getX(), getY() + getHeight() - 1, getX() + getWidth(), getY() + getHeight(), border);
        extractor.fill(getX(), getY(), getX() + 1, getY() + getHeight(), border);
        extractor.fill(getX() + getWidth() - 1, getY(), getX() + getWidth(), getY() + getHeight(), border);

        // Selected value + dropdown arrow.
        String display = items.isEmpty() ? "-" : labeller.apply(items.get(selected));
        int displayWidth = Math.max(8, getWidth() - 28);
        if (font.width(display) > displayWidth) {
            display = font.plainSubstrByWidth(display,
                    Math.max(1, displayWidth - font.width("…")), false) + "…";
        }
        int displayX = getX() + Math.max(4, (getWidth() - font.width(display)) / 2);
        text.accept(displayX, getY() + 3,
                Component.literal(display).withColor(LuneScreen.TEXT));
        text.accept(getX() + getWidth() - 12, getY() + 3,
                Component.literal(open ? "▾" : "▸").withColor(LuneScreen.TEXT_DIM));

        if (!open || items.isEmpty()) {
            return;
        }

        int visible = Math.min(items.size(), MAX_VISIBLE);
        int dropdownH = visible * ROW_HEIGHT + 4;
        int dropY = getY() + getHeight();

        // Dropdown panel.
        extractor.fill(getX(), dropY, getX() + getWidth(), dropY + dropdownH, DROPDOWN_BG);
        extractor.fill(getX(), dropY, getX() + getWidth(), dropY + 1, border);
        extractor.fill(getX(), dropY + dropdownH - 1, getX() + getWidth(), dropY + dropdownH, border);
        extractor.fill(getX(), dropY, getX() + 1, dropY + dropdownH, border);
        extractor.fill(getX() + getWidth() - 1, dropY, getX() + getWidth(), dropY + dropdownH, border);

        clampScroll();
        for (int i = 0; i < visible; i++) {
            int index = scroll + i;
            if (index >= items.size()) {
                break;
            }
            int rowY = dropY + 2 + i * ROW_HEIGHT;
            boolean hovered = mouseX >= getX() && mouseX < getX() + getWidth()
                    && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
            if (index == selected) {
                extractor.fill(getX() + 1, rowY, getX() + getWidth() - 1, rowY + ROW_HEIGHT, ROW_SELECTED);
            } else if (hovered) {
                extractor.fill(getX() + 1, rowY, getX() + getWidth() - 1, rowY + ROW_HEIGHT, ROW_HOVER);
            }

            String label = labeller.apply(items.get(index));
            int optionWidth = Math.max(8, getWidth() - 10);
            if (font.width(label) > optionWidth) {
                label = font.plainSubstrByWidth(label,
                        Math.max(1, optionWidth - font.width("…")), false) + "…";
            }
            int optionX = getX() + Math.max(4, (getWidth() - font.width(label)) / 2);
            text.accept(optionX, rowY + 2,
                    Component.literal(label).withColor(index == selected ? LuneScreen.ACCENT : LuneScreen.TEXT));
        }

        if (items.size() > visible) {
            text.accept(getX() + getWidth() - 14, dropY + dropdownH - 11,
                    Component.literal("▾").withColor(LuneScreen.TEXT_DIM));
        }
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        if (super.isMouseOver(mouseX, mouseY)) {
            return true;
        }
        if (!open || items.isEmpty()) {
            return false;
        }
        int visible = Math.min(items.size(), MAX_VISIBLE);
        int dropdownH = visible * ROW_HEIGHT + 4;
        int dropY = getY() + getHeight();
        return mouseX >= getX() && mouseX < getX() + getWidth()
                && mouseY >= dropY && mouseY < dropY + dropdownH;
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (items.isEmpty()) {
            return;
        }
        if (!open) {
            open = true;
            return;
        }

        int visible = Math.min(items.size(), MAX_VISIBLE);
        int dropY = getY() + getHeight();
        int relativeY = (int) (event.y() - dropY - 2);
        int row = relativeY / ROW_HEIGHT;
        if (row >= 0 && row < visible) {
            int index = scroll + row;
            if (index < items.size()) {
                selected = index;
                open = false;
                onSelect.accept(items.get(selected));
            }
        } else {
            // Clicked outside the rows: just close.
            open = false;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!open) {
            return false;
        }
        if (mouseX < getX() || mouseX >= getX() + getWidth()
                || mouseY < getY() + getHeight() || mouseY >= getY() + getHeight() + (MAX_VISIBLE * ROW_HEIGHT + 4)) {
            return false;
        }
        scroll -= (int) Math.signum(scrollY);
        clampScroll();
        return true;
    }

    private void clampScroll() {
        int maxScroll = Math.max(0, items.size() - MAX_VISIBLE);
        scroll = Math.clamp(scroll, 0, maxScroll);
    }

    /** Closes the dropdown without selecting; useful when the user clicks elsewhere. */
    public void close() {
        open = false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE,
                Component.literal(title + ": " + (items.isEmpty() ? "empty" : labeller.apply(items.get(selected)))));
    }
}
