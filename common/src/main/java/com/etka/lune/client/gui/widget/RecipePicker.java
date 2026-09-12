package com.etka.lune.client.gui.widget;

import com.etka.lune.util.Lang;
import com.etka.lune.bot.catalog.CraftPattern;
import com.etka.lune.bot.catalog.CraftRecipe;
import com.etka.lune.client.gui.LuneScreen;
import com.etka.lune.client.gui.UiScale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * One editor for "what should Lune craft?", with the two ways of answering it as two tabs.
 *
 * <p><b>Pick an item</b> names something and lets the recipe book do the work. <b>Draw a grid</b>
 * puts the ingredients in the cells they belong in, which is how a recipe the book has never heard
 * of gets crafted at all. They are tabs rather than separate parameters because they are one
 * question: splitting them across a mode switch, an item row and a grid row made the card six rows
 * long and left the grid - the part worth seeing - as the row nobody found.</p>
 *
 * <p>The catalog offers what the player is carrying first, then every registered item, because a
 * recipe is usually written before the ingredients have been gathered. That is rather the point of
 * putting it on a card that runs later.</p>
 *
 * <p>It doubles as the "search every item" escape hatch behind {@link InventoryPicker}, in which
 * case only the catalog is shown and a click is the answer.</p>
 */
public class RecipePicker extends AbstractWidget {

    private static final int PADDING = 6;
    private static final int TITLE_H = 14;
    private static final int TAB_H = 15;
    private static final int SEARCH_H = 16;
    private static final int SLOT = 20;
    private static final int SLOT_GAP = 2;
    private static final int CELL = 30;
    private static final int CELL_GAP = 3;
    private static final int BUTTON_W = 64;
    private static final int BUTTON_H = 18;
    private static final int SCROLLBAR_W = 3;
    private static final int SECTION_H = 12;
    private static final String ELLIPSIS = "…";

    private static final int DIM = 0xB0000000;
    private static final int SLOT_BG = 0xFF2A2B33;
    private static final int SLOT_HOVER = 0xFF3E5F86;
    private static final int CELL_BG = 0xFF17171D;
    private static final int SELECTED = LuneScreen.ACCENT;
    private static final int TAB_ACTIVE = 0xFF3A3A42;
    private static final int SCROLL_TRACK = 0xFF15151A;

    /** Why the picker is open: to answer a whole Craft recipe, or just to name one item. */
    private enum Purpose { RECIPE, ITEM }

    /** One offered item, with the name and id a search runs over. */
    private record Entry(Item item, String name, String id, ItemStack icon, boolean carried) {}

    /** Every rectangle the popup is made of, computed once per event so nothing can disagree. */
    private record Layout(int popupX, int popupY, int popupW, int popupH,
                          int tabY, int tabW, int itemTabX, int gridTabX,
                          int searchX, int searchY, int searchW,
                          int listX, int listY, int listW, int listH, int columns,
                          int gridX, int gridY,
                          int sizeButtonX, int clearButtonX, int toolsY,
                          int buttonY, int declineX, int acceptX) {}

    private final List<Entry> catalog = new ArrayList<>();
    /** The catalog after the search box, which is what the list actually draws. */
    private final List<Entry> shown = new ArrayList<>();

    private Purpose purpose = Purpose.RECIPE;
    private Consumer<CraftRecipe> onApplyRecipe;
    private Consumer<Item> onChooseItem;
    private CraftRecipe working = CraftRecipe.empty();
    /** Which tab is open. Also what decides which half of the recipe Accept keeps. */
    private boolean drawing;
    /** The item picked up in the grid tab, waiting for a cell to be clicked. */
    private Item held;
    /** The item currently under the cursor mid-drag, or null when nothing is being dragged. */
    private Item dragItem;
    /** Where a drag started, when it started in the grid: cells are moved, not only placed. */
    private int dragFromCell = -1;
    private String filter = "";
    private int scrollRow;

    public RecipePicker() {
        super(-1000, -1000, 10, 10, Component.literal(Lang.get("lune.gui.recipe.choose_what_craft")));
        this.visible = false;
        this.active = false;
    }

    public boolean isOpen() {
        return visible;
    }

    /** Opens on the tab the recipe is already using, so nothing has to be found twice. */
    public void open(CraftRecipe current, Consumer<CraftRecipe> onApply) {
        this.purpose = Purpose.RECIPE;
        this.onApplyRecipe = onApply;
        this.onChooseItem = null;
        this.working = current == null ? CraftRecipe.empty() : current;
        this.drawing = working.isDrawn();
        start();
    }

    /** Opens as a plain item catalog: one click is the answer. */
    public void openItem(Item current, Consumer<Item> onChosen) {
        this.purpose = Purpose.ITEM;
        this.onApplyRecipe = null;
        this.onChooseItem = onChosen;
        this.working = CraftRecipe.ofItem(current);
        this.drawing = false;
        start();
    }

    private void start() {
        this.held = null;
        this.dragItem = null;
        this.dragFromCell = -1;
        this.filter = "";
        this.scrollRow = 0;
        rebuildCatalog();
        this.visible = true;
        this.active = true;
        setFocused(true);
    }

    public void close(boolean save) {
        if (save && onApplyRecipe != null) {
            onApplyRecipe.accept(drawing
                    ? working.withPattern(working.pattern()) : working.withItem(working.item()));
        }
        visible = false;
        active = false;
        setFocused(false);
        setPosition(-1000, -1000);
        onApplyRecipe = null;
        onChooseItem = null;
        held = null;
        dragItem = null;
        dragFromCell = -1;
        catalog.clear();
        shown.clear();
    }

    @Override
    public void setPosition(int x, int y) {
        setX(x);
        setY(y);
    }

    // --- contents ------------------------------------------------------------

    /**
     * Carried items first, then the whole registry.
     *
     * <p>Built once per open rather than per keystroke: the registry is thousands of entries on a
     * modded instance, and every one of them has to make an ItemStack to know its name.</p>
     */
    private void rebuildCatalog() {
        catalog.clear();
        Set<Item> seen = new LinkedHashSet<>();
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            Inventory inventory = player.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (!stack.isEmpty() && seen.add(stack.getItem())) {
                    catalog.add(entryOf(stack.getItem(), true));
                }
                collectContainer(stack, seen, 0);
            }
        }
        for (Item item : BuiltInRegistries.ITEM) {
            if (item != Items.AIR && seen.add(item)) {
                catalog.add(entryOf(item, false));
            }
        }
        rebuildVisible();
    }

    /** Unfolds a carried shulker box or bundle, so a stashed ingredient is still offered early. */
    private void collectContainer(ItemStack stack, Set<Item> seen, int depth) {
        if (depth >= 2) {
            return;
        }
        var contents = stack.get(DataComponents.CONTAINER);
        if (contents == null) {
            return;
        }
        contents.nonEmptyItemCopyStream().forEach(inner -> {
            if (!inner.isEmpty() && seen.add(inner.getItem())) {
                catalog.add(entryOf(inner.getItem(), true));
            }
            collectContainer(inner, seen, depth + 1);
        });
    }

    private Entry entryOf(Item item, boolean carried) {
        ItemStack icon = new ItemStack(item);
        return new Entry(item, icon.getHoverName().getString(),
                BuiltInRegistries.ITEM.getKey(item).toString(), icon, carried);
    }

    private void rebuildVisible() {
        shown.clear();
        if (filter.isEmpty()) {
            shown.addAll(catalog);
            return;
        }
        String needle = filter.toLowerCase(Locale.ROOT);
        for (Entry entry : catalog) {
            if (entry.name().toLowerCase(Locale.ROOT).contains(needle)
                    || entry.id().toLowerCase(Locale.ROOT).contains(needle)) {
                shown.add(entry);
            }
        }
    }

    // --- rendering -----------------------------------------------------------

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                            float partialTick) {
        // Required by AbstractWidget; the popup is drawn through render() so LuneScreen can order
        // it above the tab it was opened from.
    }

    public void render(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        if (!visible) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        Layout layout = layout();

        extractor.fill(0, 0, mc.screen.width, mc.screen.height, DIM);
        LuneScreen.panel(extractor, layout.popupX(), layout.popupY(), layout.popupW(), layout.popupH());

        var text = extractor.textRenderer();
        String title = purpose == Purpose.ITEM ? Lang.get("lune.gui.recipe.choose_item") : Lang.get("lune.gui.recipe.what_should_lune_craft");
        text.accept(layout.popupX() + PADDING, layout.popupY() + PADDING,
                Component.literal(title).withColor(LuneScreen.TEXT));
        String hint = clip(font, hintText(), layout.popupW() - PADDING * 3 - font.width(title));
        text.accept(layout.popupX() + layout.popupW() - PADDING - font.width(hint),
                layout.popupY() + PADDING, Component.literal(hint).withColor(LuneScreen.TEXT_DIM));

        if (purpose == Purpose.RECIPE) {
            renderTabs(extractor, text, layout);
        }
        renderSearch(extractor, text, font, layout);
        renderCatalog(extractor, text, font, layout, mouseX, mouseY);

        if (drawing) {
            renderGrid(extractor, text, font, layout, mouseX, mouseY);
            drawButton(extractor, text, font, layout.sizeButtonX(), layout.toolsY(),
                    working.pattern().size() + "x" + working.pattern().size(), mouseX, mouseY);
            drawButton(extractor, text, font, layout.clearButtonX(), layout.toolsY(), Lang.get("lune.gui.recipe.clear"),
                    mouseX, mouseY);
        }

        drawButton(extractor, text, font, layout.declineX(), layout.buttonY(),
                purpose == Purpose.ITEM ? Lang.get("lune.gui.recipe.close") : Lang.get("lune.gui.block.decline"), mouseX, mouseY);
        if (purpose == Purpose.RECIPE) {
            drawButton(extractor, text, font, layout.acceptX(), layout.buttonY(), Lang.get("lune.gui.block.accept"),
                    mouseX, mouseY);
        }

        // Last, and under the cursor: what is being dragged has to be on top of everything it is
        // being dragged over, including the grid it is heading for.
        if (dragItem != null) {
            extractor.item(new ItemStack(dragItem), mouseX - 8, mouseY - 8);
        }
    }

    private String hintText() {
        if (purpose == Purpose.ITEM) {
            return Lang.get("lune.gui.recipe.click_item_choose");
        }
        if (!drawing) {
            return Lang.get("lune.gui.recipe.click_item_lune_crafts_from_recipe_book");
        }
        if (dragItem != null) {
            return Lang.get("lune.gui.recipe.drop_cell_dropping_anywhere_else_throws");
        }
        return held == null
                ? Lang.get("lune.gui.recipe.drag_item_into_cell_drag_cell_move_right")
                : Lang.get("lune.gui.recipe.holding_click_cell_place", new ItemStack(held).getHoverName().getString());
    }

    private void renderTabs(GuiGraphicsExtractor extractor,
                            net.minecraft.client.gui.ActiveTextCollector text, Layout layout) {
        extractor.fill(layout.itemTabX(), layout.tabY(), layout.itemTabX() + layout.tabW(),
                layout.tabY() + TAB_H, drawing ? LuneScreen.PANEL_BG : TAB_ACTIVE);
        extractor.fill(layout.gridTabX(), layout.tabY(), layout.gridTabX() + layout.tabW(),
                layout.tabY() + TAB_H, drawing ? TAB_ACTIVE : LuneScreen.PANEL_BG);
        text.accept(layout.itemTabX() + 8, layout.tabY() + 4,
                Component.literal(Lang.get("lune.gui.recipe.pick_item"))
                        .withColor(drawing ? LuneScreen.TEXT_DIM : LuneScreen.ACCENT));
        text.accept(layout.gridTabX() + 8, layout.tabY() + 4,
                Component.literal(Lang.get("lune.gui.recipe.draw_grid"))
                        .withColor(drawing ? LuneScreen.ACCENT : LuneScreen.TEXT_DIM));
    }

    private void renderSearch(GuiGraphicsExtractor extractor,
                              net.minecraft.client.gui.ActiveTextCollector text,
                              Font font, Layout layout) {
        extractor.fill(layout.searchX(), layout.searchY(), layout.searchX() + layout.searchW(),
                layout.searchY() + SEARCH_H, 0xFF2A2A35);
        extractor.fill(layout.searchX(), layout.searchY() + SEARCH_H - 1,
                layout.searchX() + layout.searchW(), layout.searchY() + SEARCH_H, LuneScreen.ACCENT);
        String prompt = filter.isEmpty() ? Lang.get("lune.gui.recipe.search_every_item_by_name_or_id") : filter;
        text.accept(layout.searchX() + 5, layout.searchY() + 4,
                Component.literal(prompt).withColor(filter.isEmpty() ? LuneScreen.TEXT_DIM : LuneScreen.TEXT));
        if ((Util.getMillis() / 500) % 2 == 0) {
            text.accept(layout.searchX() + 5 + (filter.isEmpty() ? 0 : font.width(filter)),
                    layout.searchY() + 4, Component.literal("_").withColor(LuneScreen.ACCENT));
        }
    }

    private void renderCatalog(GuiGraphicsExtractor extractor,
                               net.minecraft.client.gui.ActiveTextCollector text,
                               Font font, Layout layout, int mouseX, int mouseY) {
        text.accept(layout.listX(), layout.listY() - SECTION_H + 3,
                filter.isEmpty() ? Component.literal(Lang.get("lune.gui.recipe.carried_first"))
                : Component.literal(Lang.get("lune.gui.recipe.matching", shown.size())).withColor(LuneScreen.TEXT_DIM));

        int rows = Math.max(1, layout.listH() / (SLOT + SLOT_GAP));
        scrollRow = Math.clamp(scrollRow, 0, Math.max(0, rowCount(layout.columns()) - rows));
        Item chosen = drawing ? held : working.item();

        Entry hovered = null;
        extractor.enableScissor(layout.listX(), layout.listY(),
                layout.listX() + layout.listW(), layout.listY() + layout.listH());
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < layout.columns(); column++) {
                int index = (scrollRow + row) * layout.columns() + column;
                if (index >= shown.size()) {
                    break;
                }
                Entry entry = shown.get(index);
                int slotX = layout.listX() + column * (SLOT + SLOT_GAP);
                int slotY = layout.listY() + row * (SLOT + SLOT_GAP);
                boolean over = mouseX >= slotX && mouseX < slotX + SLOT
                        && mouseY >= slotY && mouseY < slotY + SLOT;
                extractor.fill(slotX, slotY, slotX + SLOT, slotY + SLOT, over ? SLOT_HOVER : SLOT_BG);
                if (entry.item() == chosen) {
                    extractor.outline(slotX, slotY, SLOT, SLOT, SELECTED);
                } else if (entry.carried()) {
                    // A quiet mark rather than a second grid: what you own is worth finding fast.
                    extractor.fill(slotX, slotY + SLOT - 1, slotX + SLOT, slotY + SLOT, LuneScreen.ACCENT);
                }
                extractor.item(entry.icon(), slotX + 2, slotY + 2);
                if (over) {
                    hovered = entry;
                }
            }
        }
        extractor.disableScissor();

        scrollbar(extractor, layout.listX() + layout.listW() - SCROLLBAR_W, layout.listY(),
                layout.listH(), scrollRow, rows, rowCount(layout.columns()));

        if (hovered != null) {
            extractor.setComponentTooltipForNextFrame(font, List.of(
                            Component.literal(hovered.name()).withColor(LuneScreen.TEXT),
                            hovered.carried() ? Component.literal(Lang.get("lune.gui.recipe.carried"))
                : Component.literal(hovered.id())
                                    .withColor(LuneScreen.TEXT_DIM)),
                    UiScale.toGamePixels(mouseX), UiScale.toGamePixels(mouseY));
        }
    }

    private void renderGrid(GuiGraphicsExtractor extractor,
                            net.minecraft.client.gui.ActiveTextCollector text,
                            Font font, Layout layout, int mouseX, int mouseY) {
        CraftPattern pattern = working.pattern();
        int size = pattern.size();
        int columnW = layout.popupX() + layout.popupW() - PADDING - layout.gridX();
        text.accept(layout.gridX(), layout.gridY() - SECTION_H + 3,
                Component.literal(clip(font, pattern.isEmpty() ? Lang.get("lune.gui.recipe.grid") : pattern.fitsIn(CraftPattern.SMALL) ? Lang.get("lune.gui.recipe.fits_2x2_grid") : Lang.get("lune.gui.recipe.needs_table"), columnW))
                        .withColor(LuneScreen.TEXT_DIM));

        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) {
                int cellX = layout.gridX() + column * (CELL + CELL_GAP);
                int cellY = layout.gridY() + row * (CELL + CELL_GAP);
                boolean over = mouseX >= cellX && mouseX < cellX + CELL
                        && mouseY >= cellY && mouseY < cellY + CELL;
                extractor.fill(cellX, cellY, cellX + CELL, cellY + CELL, over ? SLOT_HOVER : CELL_BG);
                extractor.outline(cellX, cellY, CELL, CELL, LuneScreen.PANEL_BORDER);
                Item item = pattern.cell(row, column);
                if (item != null) {
                    extractor.item(new ItemStack(item), cellX + (CELL - 16) / 2, cellY + (CELL - 16) / 2);
                }
            }
        }

        String summary = pattern.isEmpty()
                ? Lang.get("lune.gui.recipe.empty_nothing_craft")
                : pattern.filledCells() + Lang.get("lune.gui.recipe.label") + pattern.cellCount() + Lang.get("lune.gui.recipe.filled");
        text.accept(layout.gridX(), layout.gridY() + size * (CELL + CELL_GAP) + 4,
                Component.literal(clip(font, summary, columnW)).withColor(LuneScreen.TEXT_DIM));
    }

    private void scrollbar(GuiGraphicsExtractor extractor, int x, int y, int height,
                           int first, int shownRows, int total) {
        if (total <= shownRows) {
            return;
        }
        extractor.fill(x, y, x + SCROLLBAR_W, y + height, SCROLL_TRACK);
        int thumbH = Math.max(8, height * shownRows / total);
        int thumbY = y + (height - thumbH) * first / Math.max(1, total - shownRows);
        extractor.fill(x, thumbY, x + SCROLLBAR_W, thumbY + thumbH, LuneScreen.ACCENT);
    }

    private void drawButton(GuiGraphicsExtractor extractor,
                            net.minecraft.client.gui.ActiveTextCollector text,
                            Font font, int x, int y, String label, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX < x + BUTTON_W && mouseY >= y && mouseY < y + BUTTON_H;
        extractor.fill(x, y, x + BUTTON_W, y + BUTTON_H, hovered ? LuneScreen.ACCENT : LuneScreen.PANEL_BG);
        extractor.fill(x, y, x + BUTTON_W, y + 1, LuneScreen.PANEL_BORDER);
        extractor.fill(x, y + BUTTON_H - 1, x + BUTTON_W, y + BUTTON_H, LuneScreen.PANEL_BORDER);
        text.accept(x + (BUTTON_W - font.width(label)) / 2, y + 5,
                Component.literal(label).withColor(hovered ? 0xFF000000 : LuneScreen.TEXT));
    }

    private String clip(Font font, String value, int maxWidth) {
        if (font.width(value) <= maxWidth) {
            return value;
        }
        String fit = font.plainSubstrByWidth(value, Math.max(0, maxWidth - font.width(ELLIPSIS)), false);
        return fit.isEmpty() ? ELLIPSIS : fit + ELLIPSIS;
    }

    private int rowCount(int columns) {
        return (shown.size() + columns - 1) / columns;
    }

    private Layout layout() {
        Minecraft mc = Minecraft.getInstance();
        int screenW = mc.screen.width;
        int screenH = mc.screen.height;
        int popupW = Math.min(screenW - 20, (int) (screenW * 0.8));
        int popupH = Math.min(screenH - 20, (int) (screenH * 0.8));
        int popupX = (screenW - popupW) / 2;
        int popupY = (screenH - popupH) / 2;

        int tabY = popupY + PADDING + TITLE_H;
        int tabW = Math.min(120, (popupW - PADDING * 3) / 2);
        int searchY = purpose == Purpose.RECIPE ? tabY + TAB_H + PADDING : tabY;
        int contentY = searchY + SEARCH_H + PADDING + SECTION_H;
        int buttonY = popupY + popupH - PADDING - BUTTON_H;
        int contentH = Math.max(SLOT, buttonY - PADDING - contentY);

        // The grid column is a fixed width - three cells and two buttons wide, whatever the screen
        // is - and the catalog takes everything left over rather than the two splitting the popup.
        int columnW = Math.max(CraftPattern.LARGE * (CELL + CELL_GAP), BUTTON_W * 2 + PADDING);
        int gridX = drawing ? popupX + popupW - PADDING - columnW : popupX + popupW - PADDING;
        int listRight = drawing ? gridX - PADDING : gridX;
        int listW = Math.max(SLOT + SCROLLBAR_W, listRight - (popupX + PADDING));
        int columns = Math.max(1, (listW - SCROLLBAR_W + SLOT_GAP) / (SLOT + SLOT_GAP));

        return new Layout(popupX, popupY, popupW, popupH,
                tabY, tabW, popupX + PADDING, popupX + PADDING * 2 + tabW,
                popupX + PADDING, searchY, popupW - PADDING * 2,
                popupX + PADDING, contentY, listW, contentH, columns,
                gridX, contentY,
                gridX, gridX + BUTTON_W + PADDING, buttonY - BUTTON_H - PADDING,
                buttonY,
                popupX + popupW - PADDING - BUTTON_W * 2 - 6,
                popupX + popupW - PADDING - BUTTON_W);
    }

    // --- input ---------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return false;
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
    }

    public void handleScreenMouseClick(double mouseX, double mouseY, int button) {
        if (!visible) {
            return;
        }
        Layout layout = layout();

        if (mouseX < layout.popupX() || mouseX >= layout.popupX() + layout.popupW()
                || mouseY < layout.popupY() || mouseY >= layout.popupY() + layout.popupH()) {
            close(false);
            return;
        }

        if (purpose == Purpose.RECIPE && mouseY >= layout.tabY() && mouseY < layout.tabY() + TAB_H) {
            if (mouseX >= layout.itemTabX() && mouseX < layout.itemTabX() + layout.tabW()) {
                selectTab(false);
            } else if (mouseX >= layout.gridTabX() && mouseX < layout.gridTabX() + layout.tabW()) {
                selectTab(true);
            }
            return;
        }

        if (drawing && mouseY >= layout.toolsY() && mouseY < layout.toolsY() + BUTTON_H) {
            if (hit(mouseX, layout.sizeButtonX())) {
                CraftPattern pattern = working.pattern();
                working = working.withPattern(pattern.withSize(
                        pattern.size() == CraftPattern.LARGE ? CraftPattern.SMALL : CraftPattern.LARGE));
                return;
            }
            if (hit(mouseX, layout.clearButtonX())) {
                working = working.withPattern(working.pattern().cleared());
                return;
            }
        }

        if (mouseY >= layout.buttonY() && mouseY < layout.buttonY() + BUTTON_H) {
            if (hit(mouseX, layout.declineX())) {
                close(false);
            } else if (purpose == Purpose.RECIPE && hit(mouseX, layout.acceptX())) {
                close(true);
            }
            return;
        }

        int cell = cellAt(layout, mouseX, mouseY);
        if (cell >= 0) {
            CraftPattern pattern = working.pattern();
            int size = pattern.size();
            int row = cell / size;
            int column = cell % size;
            if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                working = working.withPattern(pattern.withCell(row, column, null));
                return;
            }
            Item inCell = pattern.cell(row, column);
            if (inCell != null) {
                // Lifted out of the cell. Where it lands - another cell, or nowhere - is decided
                // when the button comes back up, which is what makes it a move rather than a
                // clear-then-place.
                dragItem = inCell;
                dragFromCell = cell;
                working = working.withPattern(pattern.withCell(row, column, null));
            } else if (held != null) {
                working = working.withPattern(pattern.withCell(row, column, held));
            }
            return;
        }

        int index = entryAt(layout, mouseX, mouseY);
        if (index >= 0) {
            Item picked = shown.get(index).item();
            if (drawing && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                // Held rather than chosen: releasing over a cell drops it there, releasing over
                // the catalog picks it up the way a plain click always did.
                dragItem = picked;
                dragFromCell = -1;
            } else {
                chooseItem(picked);
            }
        }
    }

    /**
     * Finishes a drag: an item goes where it was let go of.
     *
     * <p>Dropped on a cell it lands there, and stays in hand so the next cell is one click away.
     * Dropped anywhere else it is thrown out, which is how a cell is emptied by dragging - and an
     * item that never left the catalog is simply picked up, so a click still behaves like a click.</p>
     */
    public void handleScreenMouseRelease(double mouseX, double mouseY, int button) {
        if (!visible || dragItem == null) {
            return;
        }
        Item dropped = dragItem;
        int from = dragFromCell;
        dragItem = null;
        dragFromCell = -1;

        Layout layout = layout();
        int cell = cellAt(layout, mouseX, mouseY);
        if (cell >= 0) {
            Item place = dropped;
            if (cell == from && held != null && held != dropped) {
                // Pressed and released on the same filled cell while something is in hand: that is
                // a replacement, not a move that went nowhere.
                place = held;
            }
            int size = working.pattern().size();
            working = working.withPattern(working.pattern().withCell(cell / size, cell % size, place));
            if (from < 0) {
                // Dragged in from the catalog, so keep it in hand: the next cell is one click away.
                held = place;
            }
            return;
        }
        if (from >= 0) {
            // Dragged out of the grid and let go: it was already lifted, so it stays gone.
            return;
        }
        held = dropped == held ? null : dropped;
    }

    /** The grid cell under a point, as a row-major index, or -1. */
    private int cellAt(Layout layout, double mouseX, double mouseY) {
        if (!drawing) {
            return -1;
        }
        int size = working.pattern().size();
        int span = size * (CELL + CELL_GAP);
        if (mouseX < layout.gridX() || mouseX >= layout.gridX() + span
                || mouseY < layout.gridY() || mouseY >= layout.gridY() + span) {
            return -1;
        }
        int column = (int) ((mouseX - layout.gridX()) / (CELL + CELL_GAP));
        int row = (int) ((mouseY - layout.gridY()) / (CELL + CELL_GAP));
        return row < size && column < size ? row * size + column : -1;
    }

    /** The catalog entry under a point, or -1. */
    private int entryAt(Layout layout, double mouseX, double mouseY) {
        if (mouseX < layout.listX() || mouseX >= layout.listX() + layout.listW()
                || mouseY < layout.listY() || mouseY >= layout.listY() + layout.listH()) {
            return -1;
        }
        int column = (int) ((mouseX - layout.listX()) / (SLOT + SLOT_GAP));
        int row = (int) ((mouseY - layout.listY()) / (SLOT + SLOT_GAP));
        if (column < 0 || column >= layout.columns() || row < 0) {
            return -1;
        }
        int index = (scrollRow + row) * layout.columns() + column;
        return index >= 0 && index < shown.size() ? index : -1;
    }

    /**
     * What clicking an item means depends on which question is being asked. In the grid it is
     * picked up, to be placed into cells; anywhere else it is the answer, and answering closes.
     */
    private void chooseItem(Item picked) {
        if (drawing) {
            held = picked == held ? null : picked;
            return;
        }
        working = working.withItem(picked);
        if (purpose == Purpose.ITEM) {
            Consumer<Item> chosen = onChooseItem;
            close(false);
            if (chosen != null) {
                chosen.accept(picked);
            }
            return;
        }
        close(true);
    }

    private void selectTab(boolean toGrid) {
        if (drawing == toGrid) {
            return;
        }
        drawing = toGrid;
        held = null;
        scrollRow = 0;
    }

    private boolean hit(double mouseX, int buttonX) {
        return mouseX >= buttonX && mouseX < buttonX + BUTTON_W;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!visible) {
            return false;
        }
        Layout layout = layout();
        int rows = Math.max(1, layout.listH() / (SLOT + SLOT_GAP));
        scrollRow = Math.clamp(scrollRow - (int) Math.signum(scrollY), 0,
                Math.max(0, rowCount(layout.columns()) - rows));
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (!visible) {
            return false;
        }
        if (event.isAllowedChatCharacter()) {
            setFilter(filter + event.codepointAsString());
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!visible) {
            return false;
        }
        handleScreenKeyPressed(event.key(), 0, event.modifiers());
        return true;
    }

    public void handleScreenCharTyped(int codePoint) {
        if (visible) {
            setFilter(filter + Character.toString(codePoint));
        }
    }

    public void handleScreenKeyPressed(int keyCode, int scanCode, int modifiers) {
        if (!visible) {
            return;
        }
        switch (keyCode) {
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (!filter.isEmpty()) {
                    setFilter(filter.substring(0, filter.length() - 1));
                }
            }
            case GLFW.GLFW_KEY_DELETE -> setFilter("");
            case GLFW.GLFW_KEY_ESCAPE -> close(false);
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> close(purpose == Purpose.RECIPE);
        }
    }

    private void setFilter(String value) {
        filter = value;
        scrollRow = 0;
        rebuildVisible();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {}

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return visible;
    }
}
