package com.etka.lune.client.gui.widget;

import com.etka.lune.bot.command.Param;
import com.etka.lune.bot.util.InventoryHelper;
import com.etka.lune.client.gui.LuneScreen;
import com.etka.lune.client.gui.UiScale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Picks an item by showing the player what they are actually carrying.
 *
 * <p>The alternative is a list of every item in the game, cycled one at a time. That is unusable
 * with a modded item registry, and it asks the wrong question: the player is not looking for "an
 * item", they are looking for <em>that</em> pickaxe, the one in their pack. Showing the real
 * inventory turns a search into a glance.</p>
 *
 * <p>Containers carried in the inventory - shulker boxes, bundles, and any modded backpack that
 * stores its contents in the vanilla container component - are unfolded into a second section, so
 * an item stashed in a backpack is still one click away. A backpack that keeps its contents
 * somewhere else entirely simply contributes nothing, which is why the whole registry stays
 * reachable through the Search everything button.</p>
 */
public class InventoryPicker extends AbstractWidget {

    private static final int HEADER_H = 22;
    private static final int SLOT = 20;
    private static final int SLOT_GAP = 2;
    private static final int PADDING = 8;
    private static final int SECTION_H = 14;
    private static final int FOOTER_H = 22;

    private static final int PANEL_BG = 0xF01A1B20;
    private static final int SLOT_BG = 0xFF2A2B33;
    private static final int SLOT_HOVER = 0xFF3E5F86;
    private static final int SLOT_SELECTED = 0xFF4C9EFF;

    /** One offered item, and where it was found. */
    private record Entry(ItemStack stack, String origin) {}

    private final List<Entry> entries = new ArrayList<>();
    private Param.ItemChoice param;
    private Consumer<Item> onChosen;
    private Runnable onSearchEverything;
    private boolean open;
    private int scroll;
    private int pointerX;
    private int pointerY;

    public InventoryPicker(int x, int y, int width, int height) {
        super(x, y, width, height, Component.literal("Inventory picker"));
        visible = false;
        active = false;
    }

    public boolean isOpen() {
        return open;
    }

    public void open(Param.ItemChoice param, Consumer<Item> onChosen, Runnable onSearchEverything) {
        this.param = param;
        this.onChosen = onChosen;
        this.onSearchEverything = onSearchEverything;
        this.open = true;
        this.scroll = 0;
        visible = true;
        active = true;
        refresh();
    }

    public void close() {
        open = false;
        visible = false;
        active = false;
        param = null;
        onChosen = null;
        onSearchEverything = null;
        entries.clear();
    }

    /** Rebuilt on open rather than cached: what is carried changes while the panel is closed. */
    private void refresh() {
        entries.clear();
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        Inventory inventory = player.getInventory();
        Set<Item> seen = new LinkedHashSet<>();
        List<Entry> nested = new ArrayList<>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (seen.add(stack.getItem())) {
                entries.add(new Entry(stack, "Carried"));
            }
            collectContainer(stack, seen, nested, 0);
        }
        entries.addAll(nested);
    }

    /**
     * Unfolds a carried container one level at a time.
     *
     * <p>Depth-limited because a shulker box can hold a shulker box: without a bound, a player
     * carrying a nested stack would have the picker walk it all the way down while the GUI waits.</p>
     */
    private void collectContainer(ItemStack stack, Set<Item> seen, List<Entry> out, int depth) {
        if (depth >= 2) {
            return;
        }
        var contents = stack.get(DataComponents.CONTAINER);
        if (contents == null) {
            return;
        }
        String origin = "In " + InventoryHelper.itemName(stack.getItem());
        contents.nonEmptyItemCopyStream().forEach(inner -> {
            if (!inner.isEmpty() && seen.add(inner.getItem())) {
                out.add(new Entry(inner, origin));
            }
            collectContainer(inner, seen, out, depth + 1);
        });
    }

    private int columns() {
        return Math.max(1, (getWidth() - PADDING * 2 + SLOT_GAP) / (SLOT + SLOT_GAP));
    }

    private int gridTop() {
        return getY() + HEADER_H + SECTION_H;
    }

    private int gridHeight() {
        return Math.max(SLOT, getHeight() - HEADER_H - SECTION_H - FOOTER_H);
    }

    private int rows() {
        return (entries.size() + columns() - 1) / columns();
    }

    private int maxScroll() {
        return Math.max(0, rows() * (SLOT + SLOT_GAP) - gridHeight());
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                            float partialTick) {
        if (!open) {
            return;
        }
        pointerX = mouseX;
        pointerY = mouseY;
        extractor.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), PANEL_BG);
        extractor.outline(getX(), getY(), getWidth(), getHeight(), LuneScreen.PANEL_BORDER);

        var text = extractor.textRenderer();
        text.accept(getX() + PADDING, getY() + 7,
                Component.literal("Choose from your inventory").withColor(LuneScreen.TEXT));
        text.accept(getX() + getWidth() - 20, getY() + 7,
                Component.literal("X").withColor(LuneScreen.TEXT_DIM));

        if (entries.isEmpty()) {
            text.accept(getX() + PADDING, gridTop(),
                    Component.literal("You are not carrying anything.").withColor(LuneScreen.TEXT_DIM));
            drawFooter(extractor);
            return;
        }

        String hoveredOrigin = null;
        ItemStack hoveredStack = null;
        extractor.enableScissor(getX(), gridTop(), getX() + getWidth(), gridTop() + gridHeight());
        int columns = columns();
        for (int i = 0; i < entries.size(); i++) {
            int column = i % columns;
            int row = i / columns;
            int slotX = getX() + PADDING + column * (SLOT + SLOT_GAP);
            int slotY = gridTop() + row * (SLOT + SLOT_GAP) - scroll;
            if (slotY + SLOT < gridTop() || slotY > gridTop() + gridHeight()) {
                continue;
            }
            Entry entry = entries.get(i);
            boolean hovered = mouseX >= slotX && mouseX < slotX + SLOT
                    && mouseY >= slotY && mouseY < slotY + SLOT
                    && mouseY >= gridTop() && mouseY < gridTop() + gridHeight();
            boolean selected = param != null && entry.stack().getItem() == param.get();
            extractor.fill(slotX, slotY, slotX + SLOT, slotY + SLOT,
                    hovered ? SLOT_HOVER : SLOT_BG);
            if (selected) {
                extractor.outline(slotX, slotY, SLOT, SLOT, SLOT_SELECTED);
            }
            extractor.item(entry.stack(), slotX + 2, slotY + 2);
            extractor.itemDecorations(Minecraft.getInstance().font, entry.stack(),
                    slotX + 2, slotY + 2);
            if (hovered) {
                hoveredOrigin = entry.origin();
                hoveredStack = entry.stack();
            }
        }
        extractor.disableScissor();

        if (hoveredStack != null) {
            extractor.setComponentTooltipForNextFrame(Minecraft.getInstance().font,
                    List.of(hoveredStack.getHoverName(),
                            Component.literal(hoveredOrigin).withColor(LuneScreen.TEXT_DIM)),
                    UiScale.toGamePixels(pointerX), UiScale.toGamePixels(pointerY));
        }
        drawFooter(extractor);
    }

    private void drawFooter(GuiGraphicsExtractor extractor) {
        var text = extractor.textRenderer();
        int footerY = getY() + getHeight() - FOOTER_H + 6;
        text.accept(getX() + PADDING, footerY,
                Component.literal("Search every item instead").withColor(LuneScreen.ACCENT));
    }

    private boolean footerHit(double x, double y) {
        return y >= getY() + getHeight() - FOOTER_H && y < getY() + getHeight()
                && x >= getX() && x < getX() + getWidth();
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (!open) {
            return;
        }
        double x = event.x();
        double y = event.y();
        if (y < getY() + HEADER_H) {
            close();
            return;
        }
        if (footerHit(x, y)) {
            Runnable search = onSearchEverything;
            close();
            if (search != null) {
                search.run();
            }
            return;
        }
        if (y < gridTop() || y >= gridTop() + gridHeight()) {
            return;
        }
        int columns = columns();
        int column = (int) ((x - getX() - PADDING) / (SLOT + SLOT_GAP));
        int row = (int) ((y - gridTop() + scroll) / (SLOT + SLOT_GAP));
        if (column < 0 || column >= columns || row < 0) {
            return;
        }
        int index = row * columns + column;
        if (index < 0 || index >= entries.size()) {
            return;
        }
        Consumer<Item> chosen = onChosen;
        Item item = entries.get(index).stack().getItem();
        close();
        if (chosen != null) {
            chosen.accept(item);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!open) {
            return false;
        }
        scroll = Math.clamp(scroll - (int) (Math.signum(scrollY) * (SLOT + SLOT_GAP)), 0, maxScroll());
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {}
}
