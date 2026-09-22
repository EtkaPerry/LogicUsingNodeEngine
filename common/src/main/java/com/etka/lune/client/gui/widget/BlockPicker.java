package com.etka.lune.client.gui.widget;

import com.etka.lune.compat.Screens;
import com.etka.lune.util.Lang;
import com.etka.lune.bot.catalog.BlockCatalog;
import com.etka.lune.bot.catalog.BlockCategories;
import com.etka.lune.bot.catalog.BlockTarget;
import com.etka.lune.bot.command.Param;
import com.etka.lune.client.gui.LuneScreen;
import com.etka.lune.client.gui.UiScale;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Util;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A popup block/tag picker that lets the player choose specific blocks or whole tags with a live
 * search. Each entry is drawn with the block's item icon so modded blocks are instantly recognisable.
 * <p>
 * Browsing is organised by {@link BlockCategories}, which is to say by the game's own creative tabs
 * - and therefore by each mod's own tabs too. Without that, a picker offering the whole registry is
 * a single scroll of thousands of blocks, so the command's own suggestions were the only usable
 * entry point and anything else (a flower, for "find") was effectively unreachable.
 */
public class BlockPicker extends AbstractWidget {

    private static final int PADDING = 6;
    private static final int TITLE_H = 14;
    private static final int TAB_H = 14;
    private static final int SEARCH_H = 16;
    private static final int CELL_SIZE = 48;
    private static final int BUTTON_W = 64;
    private static final int BUTTON_H = 18;
    private static final int ICON_SIZE = 16;
    private static final int SIDEBAR_W = 124;
    private static final int CATEGORY_H = 18;
    private static final int SCROLLBAR_W = 3;
    private static final String ELLIPSIS = "…";

    private static final int POPUP_BORDER = LuneScreen.ACCENT;
    private static final int DIM = 0xB0000000;
    private static final int TAB_ACTIVE = 0xFF3A3A42;
    private static final int ROW_HOVER = 0x28FFFFFF;
    private static final int ROW_SELECTED = LuneScreen.ACCENT_SELECTION;
    private static final int SCROLL_TRACK = 0xFF15151A;

    /** The synthetic groups Lune adds either side of the game's own categories. */
    private static final String SUGGESTED_ID = "lune:suggested";
    private static final String EVERYTHING_ID = "lune:everything";

    private Param.BlockSet target;
    private Consumer<BlockTarget> onApply;
    private BlockTarget working = BlockTarget.empty();

    private boolean tagsTab;
    private String filter = "";
    private int scrollRow;
    private int categoryScroll;
    private int categoryIndex;
    private final List<Group> groups = new ArrayList<>();
    private final List<Entry> entries = new ArrayList<>();
    /** Everything the current tab can offer, flat - the pool a search runs over. */
    private List<Entry> searchPool = List.of();

    private record Entry(String name, String id, ItemStack icon, boolean isTag, Object value) {
        Block block() {
            return isTag ? null : (Block) value;
        }

        @SuppressWarnings("unchecked")
        TagKey<Block> tag() {
            return isTag ? (TagKey<Block>) value : null;
        }
    }

    /** One row of the category sidebar, with its contents already resolved. */
    private record Group(String id, String name, ItemStack icon, List<Entry> entries) {}

    /**
     * Every rectangle the popup is made of. Computed once per event so rendering, clicking and
     * scrolling cannot disagree about where anything is.
     */
    private record Layout(int popupX, int popupY, int popupW, int popupH,
                          int tabY, int tabW, int leftTabX, int rightTabX,
                          int searchX, int searchY, int searchW,
                          int sidebarX, int sidebarW,
                          int contentY, int contentH,
                          int gridX, int gridW, int cols, int rows,
                          int buttonY, int declineX, int acceptX) {}

    public BlockPicker() {
        super(-1000, -1000, 10, 10, Component.literal(Lang.get("lune.gui.block.select_blocks")));
        this.visible = false;
        this.active = false;
    }

    public boolean isOpen() {
        return visible;
    }

    public void open(Param.BlockSet target, Consumer<BlockTarget> onApply) {
        this.target = target;
        this.onApply = onApply;
        this.working = target.get();
        this.filter = "";
        this.tagsTab = false;
        this.scrollRow = 0;
        this.categoryScroll = 0;
        this.categoryIndex = 0;
        refreshCategories();
        rebuildGroups();
        visible = true;
        active = true;
        // There is nothing else to type into, so the search is live from the moment it opens.
        setFocused(true);
    }

    public void close(boolean save) {
        if (save && onApply != null) {
            onApply.accept(working);
        }
        visible = false;
        active = false;
        setFocused(false);
        setPosition(-1000, -1000);
        target = null;
        onApply = null;
        groups.clear();
        entries.clear();
        searchPool = List.of();
    }

    @Override
    public void setPosition(int x, int y) {
        setX(x);
        setY(y);
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        // This is required by AbstractWidget but we use render() instead
    }

    public void render(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        if (!visible) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        Layout layout = layout();

        // Dim the rest of the screen behind the popup.
        extractor.fill(0, 0, Screens.current(mc).width, Screens.current(mc).height, DIM);
        LuneScreen.panel(extractor, layout.popupX(), layout.popupY(), layout.popupW(), layout.popupH());
        extractor.fill(layout.popupX() + 1, layout.buttonY() - PADDING, layout.popupX() + layout.popupW() - 1,
                layout.buttonY() - PADDING + 1, LuneScreen.PANEL_BORDER);

        var text = extractor.textRenderer();
        text.accept(layout.popupX() + PADDING, layout.popupY() + PADDING,
                Component.literal(Lang.get("lune.gui.block.select_blocks_or_tags")).withColor(LuneScreen.TEXT));
        String summary = summary();
        text.accept(layout.popupX() + layout.popupW() - PADDING - font.width(summary), layout.popupY() + PADDING,
                Component.literal(summary).withColor(LuneScreen.TEXT_DIM));

        renderTabs(extractor, text, layout);
        renderSearch(extractor, text, font, layout);
        renderSidebar(extractor, text, font, layout, mouseX, mouseY);
        renderGrid(extractor, text, font, layout, mouseX, mouseY);

        drawButton(extractor, text, font, layout.declineX(), layout.buttonY(), Lang.get("lune.gui.block.decline"), mouseX, mouseY);
        drawButton(extractor, text, font, layout.acceptX(), layout.buttonY(), Lang.get("lune.gui.block.accept"), mouseX, mouseY);
    }

    private void renderTabs(GuiGraphicsExtractor extractor, net.minecraft.client.gui.ActiveTextCollector text, Layout layout) {
        extractor.fill(layout.leftTabX(), layout.tabY(), layout.leftTabX() + layout.tabW(), layout.tabY() + TAB_H,
                !tagsTab ? TAB_ACTIVE : LuneScreen.PANEL_BG);
        extractor.fill(layout.rightTabX(), layout.tabY(), layout.rightTabX() + layout.tabW(), layout.tabY() + TAB_H,
                tagsTab ? TAB_ACTIVE : LuneScreen.PANEL_BG);
        text.accept(layout.leftTabX() + 6, layout.tabY() + 3,
                Component.literal(Lang.get("lune.gui.block.blocks")).withColor(!tagsTab ? LuneScreen.ACCENT : LuneScreen.TEXT_DIM));
        text.accept(layout.rightTabX() + 6, layout.tabY() + 3,
                Component.literal(Lang.get("lune.gui.block.tags")).withColor(tagsTab ? LuneScreen.ACCENT : LuneScreen.TEXT_DIM));
    }

    private void renderSearch(GuiGraphicsExtractor extractor, net.minecraft.client.gui.ActiveTextCollector text,
                              Font font, Layout layout) {
        extractor.fill(layout.searchX(), layout.searchY(), layout.searchX() + layout.searchW(),
                layout.searchY() + SEARCH_H, 0xFF2A2A35);
        extractor.fill(layout.searchX(), layout.searchY() + SEARCH_H - 1, layout.searchX() + layout.searchW(),
                layout.searchY() + SEARCH_H, LuneScreen.ACCENT);

        // Two whole sentences rather than one with a noun slot: the English reads the same either
        // way, but a language that inflects the noun cannot get there by substitution.
        String prompt = filter.isEmpty()
                ? Lang.get(tagsTab ? "lune.gui.block.search_tags" : "lune.gui.block.search_blocks")
                : filter;
        text.accept(layout.searchX() + 5, layout.searchY() + 4,
                Component.literal(prompt).withColor(filter.isEmpty() ? LuneScreen.TEXT_DIM : LuneScreen.TEXT));
        if ((Util.getMillis() / 500) % 2 == 0) {
            text.accept(layout.searchX() + 5 + (filter.isEmpty() ? 0 : font.width(filter)), layout.searchY() + 4,
                    Component.literal("_").withColor(LuneScreen.ACCENT));
        }
    }

    private void renderSidebar(GuiGraphicsExtractor extractor, net.minecraft.client.gui.ActiveTextCollector text,
                               Font font, Layout layout, int mouseX, int mouseY) {
        int x = layout.sidebarX();
        int y = layout.contentY();
        int w = layout.sidebarW();
        extractor.fill(x, y, x + w, y + layout.contentH(), LuneScreen.PANEL_BG);

        int visible = Math.max(1, layout.contentH() / CATEGORY_H);
        categoryScroll = Math.clamp(categoryScroll, 0, Math.max(0, groups.size() - visible));
        boolean searching = !filter.isEmpty();

        for (int i = 0; i < visible; i++) {
            int index = categoryScroll + i;
            if (index >= groups.size()) {
                break;
            }
            Group group = groups.get(index);
            int rowY = y + i * CATEGORY_H;
            boolean selected = !searching && index == categoryIndex;
            boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= rowY && mouseY < rowY + CATEGORY_H;
            if (selected) {
                extractor.fill(x, rowY, x + w, rowY + CATEGORY_H, TAB_ACTIVE);
                extractor.fill(x, rowY, x + 2, rowY + CATEGORY_H, LuneScreen.ACCENT);
            } else if (hovered) {
                extractor.fill(x, rowY, x + w, rowY + CATEGORY_H, ROW_HOVER);
            }

            if (!group.icon().isEmpty()) {
                extractor.item(group.icon(), x + 4, rowY + 1);
            }
            int labelX = x + 4 + ICON_SIZE + 4;
            int colour = searching ? LuneScreen.TEXT_DIM
                    : selected ? LuneScreen.ACCENT
                    : isSynthetic(group) ? LuneScreen.TEXT : LuneScreen.TEXT_DIM;
            text.accept(labelX, rowY + 5,
                    Component.literal(clip(font, group.name(), x + w - SCROLLBAR_W - 3 - labelX)).withColor(colour));
        }

        scrollbar(extractor, x + w - SCROLLBAR_W, y, layout.contentH(), categoryScroll, visible, groups.size());
    }

    private void renderGrid(GuiGraphicsExtractor extractor, net.minecraft.client.gui.ActiveTextCollector text,
                            Font font, Layout layout, int mouseX, int mouseY) {
        int x = layout.gridX();
        int y = layout.contentY();
        extractor.fill(x, y, x + layout.gridW(), y + layout.contentH(), LuneScreen.PANEL_BG);

        int cols = layout.cols();
        int rows = layout.rows();
        scrollRow = Math.clamp(scrollRow, 0, Math.max(0, rowCount(cols) - rows));

        if (entries.isEmpty()) {
            text.accept(x + 6, y + 6, filter.isEmpty() ? Component.literal(Lang.get("lune.gui.block.category_empty"))
                : Component.literal(Lang.get("lune.gui.block.no_matches", filter)).withColor(LuneScreen.TEXT_DIM));
            return;
        }

        Entry hoveredEntry = null;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int index = (scrollRow + row) * cols + col;
                if (index >= entries.size()) {
                    break;
                }
                int cellX = x + col * CELL_SIZE;
                int cellY = y + row * CELL_SIZE;
                Entry entry = entries.get(index);
                boolean hovered = mouseX >= cellX && mouseX < cellX + CELL_SIZE
                        && mouseY >= cellY && mouseY < cellY + CELL_SIZE;
                if (hovered) {
                    hoveredEntry = entry;
                }
                renderCell(extractor, text, font, entry, cellX, cellY, hovered);
            }
        }

        scrollbar(extractor, x + layout.gridW() - SCROLLBAR_W, y, layout.contentH(), scrollRow, rows, rowCount(cols));

        if (hoveredEntry != null) {
            // Tooltips are deferred to the end of the frame, after the menu's scale transform has
            // been popped, so the anchor has to be handed over in the game's coordinates.
            extractor.setComponentTooltipForNextFrame(font, List.of(
                            Component.literal(hoveredEntry.name()).withColor(LuneScreen.TEXT),
                            Component.literal(hoveredEntry.id()).withColor(LuneScreen.TEXT_DIM)),
                    UiScale.toGamePixels(mouseX), UiScale.toGamePixels(mouseY));
        }
    }

    private void renderCell(GuiGraphicsExtractor extractor, net.minecraft.client.gui.ActiveTextCollector text,
                            Font font, Entry entry, int cellX, int cellY, boolean hovered) {
        boolean selected = isSelected(entry);
        int bg = selected ? ROW_SELECTED : (hovered ? ROW_HOVER : 0);
        if (bg != 0) {
            extractor.fill(cellX + 1, cellY + 1, cellX + CELL_SIZE - 1, cellY + CELL_SIZE - 1, bg);
        }

        extractor.fill(cellX, cellY, cellX + CELL_SIZE, cellY + 1, LuneScreen.PANEL_BORDER);
        extractor.fill(cellX, cellY + CELL_SIZE - 1, cellX + CELL_SIZE, cellY + CELL_SIZE, LuneScreen.PANEL_BORDER);
        extractor.fill(cellX, cellY, cellX + 1, cellY + CELL_SIZE, LuneScreen.PANEL_BORDER);
        extractor.fill(cellX + CELL_SIZE - 1, cellY, cellX + CELL_SIZE, cellY + CELL_SIZE, LuneScreen.PANEL_BORDER);

        if (!entry.icon().isEmpty()) {
            extractor.item(entry.icon(), cellX + (CELL_SIZE - ICON_SIZE) / 2, cellY + 6);
        }

        String label = clip(font, entry.name(), CELL_SIZE - 6);
        text.accept(cellX + (CELL_SIZE - font.width(label)) / 2, cellY + ICON_SIZE + 12,
                Component.literal(label).withColor(selected ? LuneScreen.ACCENT : LuneScreen.TEXT));

        if (selected) {
            extractor.fill(cellX, cellY, cellX + CELL_SIZE, cellY + 2, POPUP_BORDER);
            extractor.fill(cellX, cellY + CELL_SIZE - 2, cellX + CELL_SIZE, cellY + CELL_SIZE, POPUP_BORDER);
            extractor.fill(cellX, cellY, cellX + 2, cellY + CELL_SIZE, POPUP_BORDER);
            extractor.fill(cellX + CELL_SIZE - 2, cellY, cellX + CELL_SIZE, cellY + CELL_SIZE, POPUP_BORDER);
        }
    }

    /** A thin proportional indicator; with thousands of entries there is no other sense of place. */
    private void scrollbar(GuiGraphicsExtractor extractor, int x, int y, int height, int first, int visible, int total) {
        if (total <= visible) {
            return;
        }
        extractor.fill(x, y, x + SCROLLBAR_W, y + height, SCROLL_TRACK);
        int thumbH = Math.max(8, height * visible / total);
        int thumbY = y + (height - thumbH) * first / Math.max(1, total - visible);
        extractor.fill(x, thumbY, x + SCROLLBAR_W, thumbY + thumbH, LuneScreen.ACCENT);
    }

    private void drawButton(GuiGraphicsExtractor extractor, net.minecraft.client.gui.ActiveTextCollector text,
                            Font font, int x, int y, String label, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX < x + BUTTON_W && mouseY >= y && mouseY < y + BUTTON_H;
        extractor.fill(x, y, x + BUTTON_W, y + BUTTON_H, hovered ? LuneScreen.ACCENT : LuneScreen.PANEL_BG);
        extractor.fill(x, y, x + BUTTON_W, y + 1, LuneScreen.PANEL_BORDER);
        extractor.fill(x, y + BUTTON_H - 1, x + BUTTON_W, y + BUTTON_H, LuneScreen.PANEL_BORDER);
        text.accept(x + (BUTTON_W - font.width(label)) / 2, y + 5,
                Component.literal(label).withColor(hovered ? 0xFF000000 : LuneScreen.TEXT));
    }

    private String summary() {
        int chosen = working.blocks().size() + working.tags().size();
        String where = filter.isEmpty()
                ? (categoryIndex < groups.size() ? groups.get(categoryIndex).name() : "")
                : Lang.get("lune.gui.block.search_results");
        return where + " (" + entries.size() + ")  ·  " + chosen + Lang.get("lune.gui.block.chosen");
    }

    private Layout layout() {
        Minecraft mc = Minecraft.getInstance();
        int screenW = Screens.current(mc).width;
        int screenH = Screens.current(mc).height;
        int popupW = (int) (screenW * 0.85);
        int popupH = (int) (screenH * 0.85);
        int popupX = (screenW - popupW) / 2;
        int popupY = (screenH - popupH) / 2;

        int tabY = popupY + PADDING + TITLE_H;
        int tabW = (popupW - PADDING * 3) / 2;
        int searchY = tabY + TAB_H + PADDING;
        int contentY = searchY + SEARCH_H + PADDING;
        int contentH = popupH - (contentY - popupY) - PADDING * 2 - BUTTON_H;

        int sidebarW = Math.min(SIDEBAR_W, popupW / 3);
        int gridX = popupX + PADDING + sidebarW + PADDING;
        int gridW = popupX + popupW - PADDING - gridX;

        return new Layout(popupX, popupY, popupW, popupH,
                tabY, tabW, popupX + PADDING, popupX + PADDING * 2 + tabW,
                popupX + PADDING, searchY, popupW - PADDING * 2,
                popupX + PADDING, sidebarW,
                contentY, contentH,
                gridX, gridW,
                Math.max(1, (gridW - SCROLLBAR_W) / CELL_SIZE), Math.max(1, contentH / CELL_SIZE),
                popupY + popupH - PADDING - BUTTON_H,
                popupX + popupW - PADDING - BUTTON_W * 2 - 6,
                popupX + popupW - PADDING - BUTTON_W);
    }

    private int rowCount(int cols) {
        return (entries.size() + cols - 1) / cols;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return false;
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!visible) {
            return false;
        }
        Layout layout = layout();
        int step = (int) Math.signum(scrollY);
        if (mouseY >= layout.contentY() && mouseY < layout.contentY() + layout.contentH()
                && mouseX >= layout.sidebarX() && mouseX < layout.sidebarX() + layout.sidebarW()) {
            int visible = Math.max(1, layout.contentH() / CATEGORY_H);
            categoryScroll = Math.clamp(categoryScroll - step, 0, Math.max(0, groups.size() - visible));
        } else {
            scrollRow = Math.clamp(scrollRow - step, 0, Math.max(0, rowCount(layout.cols()) - layout.rows()));
        }
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (!visible) {
            return false;
        }
        if (event.isAllowedChatCharacter()) {
            type(event.codepointAsString());
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

    public void handleScreenMouseClick(double mouseX, double mouseY, int button) {
        if (!visible) {
            return;
        }
        Layout layout = layout();

        // Outside the popup: cancel.
        if (mouseX < layout.popupX() || mouseX >= layout.popupX() + layout.popupW()
                || mouseY < layout.popupY() || mouseY >= layout.popupY() + layout.popupH()) {
            close(false);
            return;
        }

        if (mouseY >= layout.tabY() && mouseY < layout.tabY() + TAB_H) {
            if (mouseX >= layout.leftTabX() && mouseX < layout.leftTabX() + layout.tabW()) {
                selectTab(false);
                return;
            }
            if (mouseX >= layout.rightTabX() && mouseX < layout.rightTabX() + layout.tabW()) {
                selectTab(true);
                return;
            }
        }

        if (mouseY >= layout.contentY() && mouseY < layout.contentY() + layout.contentH()) {
            if (mouseX >= layout.sidebarX() && mouseX < layout.sidebarX() + layout.sidebarW()) {
                int index = categoryScroll + (int) ((mouseY - layout.contentY()) / CATEGORY_H);
                if (index >= 0 && index < groups.size()) {
                    categoryIndex = index;
                    // A category click is a browsing action; leaving the search on would hide it.
                    filter = "";
                    scrollRow = 0;
                    rebuildEntries();
                }
                return;
            }
            if (mouseX >= layout.gridX() && mouseX < layout.gridX() + layout.gridW()) {
                int col = (int) ((mouseX - layout.gridX()) / CELL_SIZE);
                int row = (int) ((mouseY - layout.contentY()) / CELL_SIZE);
                if (col >= layout.cols() || row >= layout.rows()) {
                    return;
                }
                int index = (scrollRow + row) * layout.cols() + col;
                if (index >= 0 && index < entries.size()) {
                    toggle(entries.get(index));
                }
                return;
            }
        }

        if (mouseY >= layout.buttonY() && mouseY < layout.buttonY() + BUTTON_H) {
            if (mouseX >= layout.declineX() && mouseX < layout.declineX() + BUTTON_W) {
                close(false);
            } else if (mouseX >= layout.acceptX() && mouseX < layout.acceptX() + BUTTON_W) {
                close(true);
            }
        }
    }

    public void handleScreenCharTyped(int codePoint) {
        if (visible) {
            type(Character.toString(codePoint));
        }
    }

    public void handleScreenKeyPressed(int keyCode, int scanCode, int modifiers) {
        if (!visible) {
            return;
        }
        switch (keyCode) {
            case InputConstants.KEY_BACKSPACE -> {
                if (!filter.isEmpty()) {
                    setFilter(filter.substring(0, filter.length() - 1));
                }
            }
            case InputConstants.KEY_DELETE -> setFilter("");
            case InputConstants.KEY_ESCAPE -> close(false);
            case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> close(true);
        }
    }

    private void type(String typed) {
        setFilter(filter + typed);
    }

    private void setFilter(String value) {
        filter = value;
        scrollRow = 0;
        rebuildEntries();
    }

    private void selectTab(boolean tags) {
        if (tagsTab == tags) {
            return;
        }
        tagsTab = tags;
        filter = "";
        scrollRow = 0;
        categoryScroll = 0;
        categoryIndex = 0;
        rebuildGroups();
    }

    /**
     * Asks the game to build its creative tab contents. They are populated lazily on the client, so
     * a player who has not opened the creative inventory this session would otherwise see every
     * category empty and fall through to the per-mod buckets.
     */
    private void refreshCategories() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.getConnection() == null) {
            return;
        }
        BlockCategories.refresh(mc.getConnection().enabledFeatures(),
                mc.player.canUseGameMasterBlocks() && mc.options.operatorItemsTab().get(),
                mc.level.registryAccess());
    }

    private void rebuildGroups() {
        groups.clear();
        if (tagsTab) {
            buildTagGroups();
        } else {
            buildBlockGroups();
        }
        categoryIndex = Math.clamp(categoryIndex, 0, Math.max(0, groups.size() - 1));
        rebuildEntries();
    }

    private void buildBlockGroups() {
        Map<Block, Entry> byBlock = new LinkedHashMap<>();
        for (Block block : BlockCatalog.all()) {
            if (!block.defaultBlockState().isAir()) {
                byBlock.put(block, entryOf(block));
            }
        }
        searchPool = List.copyOf(byBlock.values());

        // The command's own candidates stay first: they are the answer most of the time, and for a
        // picker that already offers everything they would otherwise be lost in the pile.
        List<Entry> suggested = resolve(target.candidates(), byBlock);
        if (!suggested.isEmpty() && suggested.size() < searchPool.size()) {
            groups.add(new Group(SUGGESTED_ID, Lang.get("lune.gui.block.suggested"), suggested.get(0).icon(), suggested));
        }
        groups.add(new Group(EVERYTHING_ID, Lang.get("lune.gui.block.all_blocks"), new ItemStack(Blocks.BEDROCK.asItem()), searchPool));
        for (BlockCategories.Category category : BlockCategories.categories()) {
            List<Entry> resolved = resolve(category.blocks(), byBlock);
            if (!resolved.isEmpty()) {
                groups.add(new Group(category.id(), category.name(), category.icon(), resolved));
            }
        }
    }

    private void buildTagGroups() {
        Map<String, List<Entry>> byNamespace = new LinkedHashMap<>();
        List<Entry> all = new ArrayList<>();
        for (TagKey<Block> tag : target.tagCandidates()) {
            Entry entry = entryOf(tag);
            all.add(entry);
            byNamespace.computeIfAbsent(tag.location().getNamespace(), namespace -> new ArrayList<>()).add(entry);
        }
        searchPool = List.copyOf(all);

        groups.add(new Group(EVERYTHING_ID, Lang.get("lune.gui.block.all_tags"),
                all.isEmpty() ? ItemStack.EMPTY : all.get(0).icon(), searchPool));
        List<String> namespaces = new ArrayList<>(byNamespace.keySet());
        namespaces.sort(Comparator.comparing(namespace -> namespace.equals("minecraft") ? "" : namespace));
        for (String namespace : namespaces) {
            List<Entry> tags = byNamespace.get(namespace);
            groups.add(new Group("lune:tags/" + namespace, namespace, tags.get(0).icon(), List.copyOf(tags)));
        }
    }

    private List<Entry> resolve(List<Block> blocks, Map<Block, Entry> byBlock) {
        List<Entry> resolved = new ArrayList<>(blocks.size());
        for (Block block : blocks) {
            Entry entry = byBlock.get(block);
            if (entry != null) {
                resolved.add(entry);
            }
        }
        return List.copyOf(resolved);
    }

    /** A search spans everything the tab offers; browsing stays inside the selected category. */
    private void rebuildEntries() {
        entries.clear();
        if (filter.isEmpty()) {
            if (categoryIndex < groups.size()) {
                entries.addAll(groups.get(categoryIndex).entries());
            }
            return;
        }
        String needle = filter.toLowerCase(Locale.ROOT);
        for (Entry entry : searchPool) {
            if (entry.name().toLowerCase(Locale.ROOT).contains(needle)
                    || entry.id().toLowerCase(Locale.ROOT).contains(needle)
                    || stripNamespace(entry.id()).contains(needle)) {
                entries.add(entry);
            }
        }
    }

    private static String stripNamespace(String id) {
        int colon = id.indexOf(':');
        return (colon < 0 ? id : id.substring(colon + 1)).toLowerCase(Locale.ROOT);
    }

    private Entry entryOf(Block block) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(block);
        return new Entry(block.getName().getString(), id.toString(), blockIcon(block), false, block);
    }

    private Entry entryOf(TagKey<Block> tag) {
        return new Entry("#" + tag.location().getPath(), "#" + tag.location(), firstBlockIcon(tag), true, tag);
    }

    private boolean isSynthetic(Group group) {
        return SUGGESTED_ID.equals(group.id()) || EVERYTHING_ID.equals(group.id());
    }

    private boolean isSelected(Entry entry) {
        if (entry.isTag()) {
            TagKey<Block> tag = entry.tag();
            return tag != null && working.tags().contains(tag.location());
        }
        Block block = entry.block();
        return block != null && working.blocks().contains(block);
    }

    private void toggle(Entry entry) {
        if (entry.isTag()) {
            TagKey<Block> tag = entry.tag();
            if (tag == null) {
                return;
            }
            Identifier id = tag.location();
            working = working.tags().contains(id) ? working.withoutTag(id) : working.withTag(id);
        } else {
            Block block = entry.block();
            if (block == null) {
                return;
            }
            working = working.blocks().contains(block) ? working.withoutBlock(block) : working.withBlock(block);
        }
    }

    private String clip(Font font, String value, int maxWidth) {
        if (font.width(value) <= maxWidth) {
            return value;
        }
        String fit = font.plainSubstrByWidth(value, Math.max(0, maxWidth - font.width(ELLIPSIS)), false);
        return fit.isEmpty() ? ELLIPSIS : fit + ELLIPSIS;
    }

    private ItemStack blockIcon(Block block) {
        Item item = block.asItem();
        // Crops, fluids and other blocks with no placeable item still belong in the picker.
        return new ItemStack(item == Items.AIR ? Blocks.BARRIER.asItem() : item);
    }

    private ItemStack firstBlockIcon(TagKey<Block> tag) {
        for (Holder<Block> holder : BuiltInRegistries.BLOCK.getTagOrEmpty(tag)) {
            return blockIcon(holder.value());
        }
        return new ItemStack(Blocks.BARRIER.asItem());
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {}

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return visible;
    }
}
