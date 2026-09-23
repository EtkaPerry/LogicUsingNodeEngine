package com.etka.lune.client.gui.widget;

import com.etka.lune.client.gui.Accessibility;
import com.etka.lune.client.gui.LuneScreen;
import com.etka.lune.client.gui.mascot.MascotAdvisor;
import com.etka.lune.games.RiddleDeck;
import com.etka.lune.games.RiddleGame;
import com.etka.lune.games.RiddleMatcher;
import com.etka.lune.games.RiddleRecipe;
import com.etka.lune.games.RiddleRecords;
import com.etka.lune.util.Lang;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Recipe Riddle, the second game on the Games shelf: an item on the left, a crafting table on the
 * right, and twenty stacks below - the recipe's own ingredients among decoys - to craft it from.
 *
 * <p>The table is the game's own, cut from its crafting table texture, slots, arrow, result square
 * and all, with the game's own word for it over the grid; the pool sits in a panel of the same
 * frame, and a slot lights up under the pointer the way a slot does in the game. Whatever the grid
 * would make appears in the result square, by any recipe the game knows, so a wrong answer still
 * says what it is.</p>
 */
final class RiddlePage {

    private static final Identifier TABLE = Identifier.withDefaultNamespace("textures/gui/container/crafting_table.png");
    private static final Identifier SLOT = Identifier.withDefaultNamespace("container/slot");
    private static final Identifier HIGHLIGHT_BACK = Identifier.withDefaultNamespace("container/slot_highlight_back");
    private static final Identifier HIGHLIGHT_FRONT = Identifier.withDefaultNamespace("container/slot_highlight_front");
    private static final int TEXTURE = 256;

    /** The table's window: the top 76 rows of its texture, grid, arrow and result, and the bottom edge. */
    private static final int WINDOW_W = 176;
    private static final int WINDOW_TOP = 76;
    private static final int WINDOW_EDGE = 4;
    private static final int WINDOW_H = WINDOW_TOP + WINDOW_EDGE;
    /** Where the table's own slots put their items, from the window's corner. */
    private static final int GRID_X = 30;
    private static final int GRID_Y = 17;
    private static final int RESULT_X = 124;
    private static final int RESULT_Y = 35;
    private static final int TITLE_X = 28;
    private static final int TITLE_Y = 6;
    /** The dark grey the game writes a container's title in. */
    private static final int TITLE_INK = 0xFF404040;
    /** The frame's corner, cut from the same texture for the pool's panel. */
    private static final int CORNER = 4;
    private static final int SLOT_SIZE = 18;
    private static final int POOL_COLUMNS = 10;
    private static final int POOL_ROWS = RiddleGame.POOL / POOL_COLUMNS;
    private static final int PANEL_PAD = 7;
    private static final int POOL_W = POOL_COLUMNS * SLOT_SIZE + PANEL_PAD * 2;
    private static final int POOL_H = POOL_ROWS * SLOT_SIZE + PANEL_PAD * 2;

    private static final int BODY_TOP = 38;
    private static final int GAP = 12;
    /** The item asked about is drawn twice size, in a slot to match. */
    private static final int TARGET = SLOT_SIZE * 2;
    private static final int HINT_H = 16;
    private static final int HINT_GAP = 2;
    private static final int HINT_W = 150;
    /** Between the item's column and the table, when the page is wide enough to spare it. */
    private static final int COLUMN_GAP = 24;
    private static final int BUTTON_H = 16;
    private static final int HINTED = 0x5055CC55;
    /** How far apart the points of a drag are tested, so a quick sweep misses no square. */
    private static final double DRAG_STEP = 4.0;
    private static final int ARM_MILLIS = 3000;
    private static final RiddleGame.Hint[] HINTS = RiddleGame.Hint.values();

    private final Runnable back;
    /** A new riddle waits on a second click while one is in progress; this is when the first came. */
    private boolean armed;
    private long armedAt;
    /** The button a drag across the grid is being made with, or -1; and where it last was. */
    private int dragButton = -1;
    private double dragX;
    private double dragY;
    /** What the grid made when last asked, and for which riddle and version of it. */
    private RiddleGame craftedFor;
    private int craftedVersion = -1;
    private RiddleRecipe crafted;

    private record Layout(LuneMenu.Area back, int titleY, int lineY, int windowX, int windowY, int targetX,
                          int targetY, int textX, int textWidth, List<LuneMenu.Area> hints, LuneMenu.Area newRiddle,
                          int poolX, int poolY, int controlsY) {}

    /** {@code back} returns the page to the shelf. */
    RiddlePage(Runnable back) {
        this.back = back;
    }

    static String title() {
        return Lang.get("lune.gui.games.riddle");
    }

    // --- what Lune says --------------------------------------------------------

    String luneLine() {
        RiddleGame game = RiddleGame.current();
        if (game == null) {
            return Lang.get("lune.mascot.corner.riddle_empty");
        }
        if (game.solved()) {
            return game.hints() == 0 ? Lang.get("lune.mascot.corner.riddle_clean")
                    : Lang.get("lune.mascot.corner.riddle_done", RiddleRecords.solved());
        }
        if (crafted(game) != null) {
            return Lang.get("lune.mascot.corner.riddle_other");
        }
        return game.hints() > 0 ? Lang.get("lune.mascot.corner.riddle_hinted") : Lang.get("lune.mascot.corner.riddle_start");
    }

    MascotAdvisor.Mood luneMood() {
        RiddleGame game = RiddleGame.current();
        if (game == null) {
            return MascotAdvisor.Mood.WAITING;
        }
        if (game.solved()) {
            return MascotAdvisor.Mood.SUCCESS;
        }
        return game.inProgress() ? MascotAdvisor.Mood.THINKING : MascotAdvisor.Mood.ASKING;
    }

    // --- the page --------------------------------------------------------------

    /**
     * Opens on the riddle in play, dealing the first one if there is none yet. Anything left in
     * hand has gone back to its stack, as the game returns the cursor's stack when a menu closes.
     */
    void shown() {
        armed = false;
        dragButton = -1;
        if (RiddleGame.current() == null) {
            deal();
        } else {
            RiddleGame.current().returnHeld();
        }
    }

    /** A riddle not asked before: the record starts over once every recipe the game knows has been. */
    private static void deal() {
        RiddleCatalog.Catalog catalog = RiddleCatalog.refresh();
        if (RiddleDeck.exhausted(catalog.recipes(), RiddleRecords::dealtBefore)) {
            RiddleRecords.forgetDealt();
        }
        RiddleGame game = RiddleDeck.deal(catalog.recipes(), catalog.universe(), RiddleRecords::dealtBefore,
                new Random(ThreadLocalRandom.current().nextLong()));
        if (game != null) {
            RiddleRecords.rememberDealt(game.recipe().fingerprint());
        }
        RiddleGame.setCurrent(game);
    }

    private static Layout layout(LuneMenu.Area area, Font font) {
        int titleY = area.y() + 2;
        int top = area.y() + BODY_TOP;
        // The item and its hints on the left and the table on the right, as one block in the middle,
        // with the pool straight underneath: what is picked up is never far from where it goes.
        int columnWidth = Math.max(TARGET, Math.min(HINT_W, area.width() - WINDOW_W - GAP));
        int gap = Math.clamp(area.width() - WINDOW_W - columnWidth, 0, COLUMN_GAP);
        int left = area.x() + (area.width() - columnWidth - gap - WINDOW_W) / 2;
        int windowX = left + columnWidth + gap;
        int targetX = left;
        int textX = targetX + TARGET + 8;
        List<LuneMenu.Area> hints = new ArrayList<>(HINTS.length);
        for (int i = 0; i < HINTS.length; i++) {
            hints.add(new LuneMenu.Area(left, top + TARGET + 8 + i * (HINT_H + HINT_GAP), columnWidth, HINT_H));
        }
        int blockBottom = Math.max(top + WINDOW_H, hints.get(HINTS.length - 1).bottom());
        int poolY = Math.min(blockBottom + GAP, area.bottom() - POOL_H);
        // Wide enough for either label, and for the arrow it carries once the riddle is solved.
        int buttonWidth = Math.max(MenuPaint.buttonWidth(font, newRiddleLabel(false), MenuPaint.Button.PRIMARY),
                MenuPaint.buttonWidth(font, newRiddleLabel(true), MenuPaint.Button.PRIMARY));
        LuneMenu.Area newRiddle = new LuneMenu.Area(area.right() - buttonWidth, titleY, buttonWidth, BUTTON_H);
        LuneMenu.Area backArea = new LuneMenu.Area(area.x(), titleY - 1, 14 + font.width(title()) * 2, 19);
        return new Layout(backArea, titleY, area.y() + 24, windowX, top, targetX, top, textX,
                Math.max(10, left + columnWidth - textX), hints, newRiddle,
                area.x() + (area.width() - POOL_W) / 2, poolY, poolY + POOL_H + 8);
    }

    private static String newRiddleLabel(boolean asking) {
        return Lang.get(asking ? "lune.gui.tasks.sure" : "lune.gui.games.new_riddle");
    }

    void render(GuiGraphicsExtractor extractor, LuneMenu.Area area, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        long now = Util.getMillis();
        if (armed && now - armedAt > ARM_MILLIS) {
            armed = false;
        }
        Layout layout = layout(area, font);
        RiddleGame game = RiddleGame.current();
        drawHeader(extractor, font, area, layout, game, mouseX, mouseY);
        drawWindow(extractor, font, layout);
        drawPoolPanel(extractor, layout);
        if (game == null) {
            return;
        }

        ItemStack hovered = null;
        drawTarget(extractor, font, layout, game);
        if (MenuPaint.hits(layout.targetX(), layout.targetY(), TARGET, TARGET, mouseX, mouseY)) {
            hovered = RiddleCatalog.stack(game.recipe().result());
        }
        drawHints(extractor, font, layout, game, mouseX, mouseY);

        int hoverCell = game.solved() ? -1 : cellAt(layout, mouseX, mouseY);
        int hoverSlot = game.solved() ? -1 : slotAt(layout, mouseX, mouseY);
        List<Set<String>> shape = game.shapeShown() ? game.layout() : null;
        for (int cell = 0; cell < RiddleMatcher.CELLS; cell++) {
            int x = cellX(layout, cell);
            int y = cellY(layout, cell);
            if (game.hinted(cell)) {
                extractor.fill(x, y, x + 16, y + 16, HINTED);
            }
            if (shape != null && shape.get(cell) != null) {
                extractor.outline(x - 1, y - 1, SLOT_SIZE, SLOT_SIZE, LuneScreen.ACCENT);
            }
            if (cell == hoverCell) {
                highlight(extractor, HIGHLIGHT_BACK, x, y);
            }
            String item = game.grid(cell);
            if (item != null) {
                ItemStack stack = RiddleCatalog.stack(item);
                extractor.item(stack, x, y);
                if (cell == hoverCell) {
                    hovered = stack;
                }
            }
            if (cell == hoverCell) {
                highlight(extractor, HIGHLIGHT_FRONT, x, y);
            }
        }

        RiddleRecipe makes = game.solved() ? game.recipe() : crafted(game);
        if (makes != null) {
            ItemStack result = RiddleCatalog.stack(makes.result());
            int x = layout.windowX() + RESULT_X;
            int y = layout.windowY() + RESULT_Y;
            extractor.item(result, x, y);
            extractor.itemDecorations(font, result, x, y, makes.count() > 1 ? String.valueOf(makes.count()) : null);
            if (MenuPaint.hits(x, y, 16, 16, mouseX, mouseY)) {
                hovered = result;
            }
        }

        for (int slot = 0; slot < RiddleGame.POOL; slot++) {
            int x = poolX(layout, slot);
            int y = poolY(layout, slot);
            if (slot == hoverSlot) {
                highlight(extractor, HIGHLIGHT_BACK, x, y);
            }
            String item = game.item(slot);
            if (item != null && game.count(slot) > 0) {
                ItemStack stack = RiddleCatalog.stack(item);
                extractor.item(stack, x, y);
                extractor.itemDecorations(font, stack, x, y, game.count(slot) > 1 ? String.valueOf(game.count(slot)) : null);
                if (slot == hoverSlot) {
                    hovered = stack;
                }
            }
            if (slot == hoverSlot) {
                highlight(extractor, HIGHLIGHT_FRONT, x, y);
            }
        }

        drawControls(extractor, font, area, layout);

        // What is in hand rides on the pointer with its count, and tooltips wait for an empty
        // hand, both as in the game's own inventories.
        String held = game.heldItem();
        if (held != null) {
            ItemStack stack = RiddleCatalog.stack(held);
            extractor.item(stack, mouseX - 8, mouseY - 8);
            extractor.itemDecorations(font, stack, mouseX - 8, mouseY - 8,
                    game.heldCount() > 1 ? String.valueOf(game.heldCount()) : null);
        } else if (hovered != null) {
            extractor.setTooltipForNextFrame(font, hovered, mouseX, mouseY);
        }
    }

    private void drawHeader(GuiGraphicsExtractor extractor, Font font, LuneMenu.Area area, Layout layout,
                            RiddleGame game, int mouseX, int mouseY) {
        int good = Accessibility.colour(Accessibility.Mark.GOOD);
        boolean backHot = layout.back().contains(mouseX, mouseY);
        MenuPaint.chevron(extractor, area.x() + 2, layout.titleY() + 3, false, backHot ? MenuPaint.WHITE : LuneScreen.TEXT);
        MenuPaint.bigText(extractor, title(), area.x() + 14, layout.titleY(), MenuPaint.WHITE);

        LuneMenu.Area button = layout.newRiddle();
        String label = newRiddleLabel(armed);
        MenuPaint.button(extractor, font, button.x(), button.y(), button.width(), button.height(),
                MenuPaint.clip(font, label, button.width() - 8),
                game == null || game.solved() ? MenuPaint.Button.PRIMARY : MenuPaint.Button.SECONDARY,
                button.contains(mouseX, mouseY));
        int solved = RiddleRecords.solved();
        if (solved > 0) {
            String count = String.valueOf(solved);
            int countX = button.x() - 8 - font.width(count);
            MenuPaint.check(extractor, countX - 10, layout.titleY() + 5, good);
            MenuPaint.text(extractor, count, countX, layout.titleY() + 4, good);
        }

        String line;
        int colour;
        RiddleRecipe other = game == null ? null : crafted(game);
        if (game == null) {
            line = Lang.get("lune.gui.games.riddle_none");
            colour = Accessibility.dim();
        } else if (game.solved()) {
            line = Lang.get(game.hints() == 0 ? "lune.gui.games.riddle_crafted_clean" : "lune.gui.games.riddle_crafted");
            colour = good;
        } else if (other != null) {
            line = Lang.get("lune.gui.games.riddle_makes", RiddleCatalog.stack(other.result()).getHoverName().getString());
            colour = Accessibility.colour(Accessibility.Mark.WARN);
        } else if (game.shapeShown() && game.recipe().shapeless()) {
            line = Lang.get("lune.gui.games.riddle_any_order", game.recipe().filled());
            colour = LuneScreen.ACCENT_HOVER;
        } else {
            line = Lang.get("lune.gui.games.riddle_about");
            colour = Accessibility.dim();
        }
        MenuPaint.text(extractor, MenuPaint.clip(font, line, area.width() - 4), area.x() + 2, layout.lineY(), colour);
    }

    /** The crafting table, cut from its own texture, with the game's own name for it. */
    private static void drawWindow(GuiGraphicsExtractor extractor, Font font, Layout layout) {
        int x = layout.windowX();
        int y = layout.windowY();
        extractor.blit(RenderPipelines.GUI_TEXTURED, TABLE, x, y, 0.0F, 0.0F, WINDOW_W, WINDOW_TOP, TEXTURE, TEXTURE);
        extractor.blit(RenderPipelines.GUI_TEXTURED, TABLE, x, y + WINDOW_TOP, 0.0F, 162.0F, WINDOW_W, WINDOW_EDGE,
                TEXTURE, TEXTURE);
        extractor.text(font, Component.translatable("container.crafting"), x + TITLE_X, y + TITLE_Y, TITLE_INK, false);
    }

    /**
     * The pool's panel: the table's frame cut into corners and edges and stretched round twenty of
     * the game's own slots, so a resource pack that repaints the one repaints the other.
     */
    private static void drawPoolPanel(GuiGraphicsExtractor extractor, Layout layout) {
        int x = layout.poolX();
        int y = layout.poolY();
        int w = POOL_W;
        int h = POOL_H;
        int inner = WINDOW_W - CORNER * 2;
        // Corners, then edges, then the middle from a plain patch of the frame's grey.
        piece(extractor, x, y, CORNER, CORNER, 0, 0, CORNER, CORNER);
        piece(extractor, x + w - CORNER, y, CORNER, CORNER, WINDOW_W - CORNER, 0, CORNER, CORNER);
        piece(extractor, x, y + h - CORNER, CORNER, CORNER, 0, 162, CORNER, CORNER);
        piece(extractor, x + w - CORNER, y + h - CORNER, CORNER, CORNER, WINDOW_W - CORNER, 162, CORNER, CORNER);
        piece(extractor, x + CORNER, y, w - CORNER * 2, CORNER, CORNER, 0, inner, CORNER);
        piece(extractor, x + CORNER, y + h - CORNER, w - CORNER * 2, CORNER, CORNER, 162, inner, CORNER);
        piece(extractor, x, y + CORNER, CORNER, h - CORNER * 2, 0, CORNER, CORNER, 12);
        piece(extractor, x + w - CORNER, y + CORNER, CORNER, h - CORNER * 2, WINDOW_W - CORNER, CORNER, CORNER, 12);
        piece(extractor, x + CORNER, y + CORNER, w - CORNER * 2, h - CORNER * 2, 8, 8, 4, 4);
        for (int slot = 0; slot < RiddleGame.POOL; slot++) {
            extractor.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT, poolX(layout, slot) - 1, poolY(layout, slot) - 1,
                    SLOT_SIZE, SLOT_SIZE);
        }
    }

    /** A {@code u, v, uw, vh} patch of the table's texture stretched over {@code w} by {@code h}. */
    private static void piece(GuiGraphicsExtractor extractor, int x, int y, int w, int h, int u, int v, int uw, int vh) {
        extractor.blit(RenderPipelines.GUI_TEXTURED, TABLE, x, y, u, v, w, h, uw, vh, TEXTURE, TEXTURE);
    }

    private static void highlight(GuiGraphicsExtractor extractor, Identifier sprite, int x, int y) {
        // The game's own hover sprites are 24 square, centred on the slot.
        extractor.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x - 4, y - 4, 24, 24);
    }

    /** The item asked about, twice size in a slot to match, with its name and how many it makes. */
    private static void drawTarget(GuiGraphicsExtractor extractor, Font font, Layout layout, RiddleGame game) {
        RiddleRecipe recipe = game.recipe();
        ItemStack target = RiddleCatalog.stack(recipe.result());
        var pose = extractor.pose();
        pose.pushMatrix();
        pose.translate(layout.targetX(), layout.targetY());
        pose.scale(2.0F, 2.0F);
        extractor.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT, 0, 0, SLOT_SIZE, SLOT_SIZE);
        extractor.item(target, 1, 1);
        pose.popMatrix();

        int y = layout.targetY() + 6;
        MenuPaint.text(extractor, MenuPaint.clip(font, target.getHoverName().getString(), layout.textWidth()),
                layout.textX(), y, MenuPaint.WHITE);
        if (recipe.count() > 1) {
            y += 12;
            MenuPaint.text(extractor, MenuPaint.clip(font, Lang.get("lune.gui.games.riddle_count", recipe.count()),
                    layout.textWidth()), layout.textX(), y, Accessibility.dim());
        }
        if (game.hints() > 0) {
            MenuPaint.text(extractor, MenuPaint.clip(font, Lang.get("lune.gui.games.hints_used", game.hints()),
                    layout.textWidth()), layout.textX(), y + 12, Accessibility.dim());
        }
    }

    private static void drawHints(GuiGraphicsExtractor extractor, Font font, Layout layout, RiddleGame game,
                                  int mouseX, int mouseY) {
        for (int i = 0; i < HINTS.length; i++) {
            LuneMenu.Area button = layout.hints().get(i);
            if (button.bottom() > layout.poolY() - 2) {
                return;
            }
            boolean offered = game.canHint(HINTS[i]);
            MenuPaint.button(extractor, font, button.x(), button.y(), button.width(), button.height(),
                    MenuPaint.clip(font, Lang.get(hintKey(HINTS[i])), button.width() - 8),
                    offered ? MenuPaint.Button.SECONDARY : MenuPaint.Button.DISABLED,
                    offered && button.contains(mouseX, mouseY));
        }
    }

    private static String hintKey(RiddleGame.Hint hint) {
        return switch (hint) {
            case PLACE -> "lune.gui.games.hint_place";
            case SHAPE -> "lune.gui.games.hint_shape";
            case DECOYS -> "lune.gui.games.hint_decoys";
        };
    }

    /** How to play, under the pool and as wide as it, where there is room for two lines of it. */
    private static void drawControls(GuiGraphicsExtractor extractor, Font font, LuneMenu.Area area, Layout layout) {
        int lines = Math.min(4, (area.bottom() - layout.controlsY()) / 10);
        if (lines < 2) {
            return;
        }
        List<String> text = MenuPaint.wrap(font, Lang.get("lune.gui.games.riddle_controls"), POOL_W, lines);
        for (int i = 0; i < text.size(); i++) {
            String row = text.get(i);
            MenuPaint.text(extractor, row, layout.poolX() + (POOL_W - font.width(row)) / 2, layout.controlsY() + i * 10,
                    Accessibility.dim());
        }
    }

    // --- where things are ------------------------------------------------------

    private static int cellX(Layout layout, int cell) {
        return layout.windowX() + GRID_X + (cell % RiddleMatcher.SIDE) * SLOT_SIZE;
    }

    private static int cellY(Layout layout, int cell) {
        return layout.windowY() + GRID_Y + (cell / RiddleMatcher.SIDE) * SLOT_SIZE;
    }

    private static int poolX(Layout layout, int slot) {
        return layout.poolX() + PANEL_PAD + 1 + (slot % POOL_COLUMNS) * SLOT_SIZE;
    }

    private static int poolY(Layout layout, int slot) {
        return layout.poolY() + PANEL_PAD + 1 + (slot / POOL_COLUMNS) * SLOT_SIZE;
    }

    /** The grid square under the pointer, a slot's full eighteen pixels, or -1. */
    private static int cellAt(Layout layout, double mouseX, double mouseY) {
        for (int cell = 0; cell < RiddleMatcher.CELLS; cell++) {
            if (MenuPaint.hits(cellX(layout, cell) - 1, cellY(layout, cell) - 1, SLOT_SIZE, SLOT_SIZE, mouseX, mouseY)) {
                return cell;
            }
        }
        return -1;
    }

    private static int slotAt(Layout layout, double mouseX, double mouseY) {
        for (int slot = 0; slot < RiddleGame.POOL; slot++) {
            if (MenuPaint.hits(poolX(layout, slot) - 1, poolY(layout, slot) - 1, SLOT_SIZE, SLOT_SIZE, mouseX, mouseY)) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * What the grid makes by some recipe other than the one asked about, or null. Asked again only
     * when the grid has changed; the catalog holds every crafting recipe the game knows.
     */
    private RiddleRecipe crafted(RiddleGame game) {
        if (craftedFor != game || craftedVersion != game.version()) {
            craftedFor = game;
            craftedVersion = game.version();
            RiddleRecipe made = RiddleCatalog.crafts(game.gridItems());
            crafted = made == null || RiddleMatcher.matches(game.recipe(), game.gridItems()) ? null : made;
        }
        return crafted;
    }

    // --- input -----------------------------------------------------------------

    void click(LuneMenu.Area area, double mouseX, double mouseY, int button) {
        long now = Util.getMillis();
        Layout layout = layout(area, Minecraft.getInstance().font);
        boolean left = button == InputConstants.MOUSE_BUTTON_LEFT;
        boolean right = button == InputConstants.MOUSE_BUTTON_RIGHT;
        boolean wasArmed = armed;
        armed = false;
        RiddleGame game = RiddleGame.current();

        if (left && layout.back().contains(mouseX, mouseY)) {
            MenuPaint.click();
            back.run();
            return;
        }
        if (left && layout.newRiddle().contains(mouseX, mouseY)) {
            MenuPaint.click();
            if (game != null && game.inProgress() && !wasArmed) {
                // The half-built answer it throws away has no undo.
                armed = true;
                armedAt = now;
                return;
            }
            deal();
            return;
        }
        if (game == null) {
            return;
        }
        for (int i = 0; i < HINTS.length; i++) {
            LuneMenu.Area hint = layout.hints().get(i);
            if (left && hint.bottom() <= layout.poolY() - 2 && hint.contains(mouseX, mouseY)) {
                RiddleGame.Outcome outcome = game.hint(HINTS[i]);
                if (outcome != RiddleGame.Outcome.REFUSED) {
                    play(SoundEvents.BOOK_PAGE_TURN, 1.0F, 0.8F);
                    finished(game, outcome);
                }
                return;
            }
        }
        if (!left && !right) {
            return;
        }
        int cell = cellAt(layout, mouseX, mouseY);
        if (cell >= 0) {
            RiddleGame.Outcome outcome;
            SoundEvent sound;
            if (Minecraft.getInstance().hasShiftDown()) {
                // A shift-click sends it home, as the game moves a stack across on one.
                outcome = game.take(cell);
                sound = SoundEvents.BUNDLE_REMOVE_ONE;
            } else if (game.heldCount() > 0) {
                // Pressed on an empty square with something in hand, the press starts a drag:
                // one goes here, and one in every empty square the pointer passes after it.
                if (game.grid(cell) == null) {
                    dragButton = button;
                    dragX = mouseX;
                    dragY = mouseY;
                }
                outcome = game.place(cell);
                sound = SoundEvents.BUNDLE_INSERT;
            } else {
                outcome = game.pickUp(cell);
                sound = SoundEvents.BUNDLE_REMOVE_ONE;
            }
            played(game, outcome, sound);
            return;
        }
        int slot = slotAt(layout, mouseX, mouseY);
        if (slot >= 0 && Minecraft.getInstance().hasShiftDown()) {
            // And the other way: the stack goes across into the empty squares.
            played(game, game.quickMove(slot), SoundEvents.BUNDLE_INSERT);
        } else if (slot >= 0) {
            game.clickPool(slot, right);
        }
        // A click on nothing does nothing, as a click on a menu's own background does in the game.
    }

    /** One more square for a drag in progress: every one between the last point and this one. */
    void drag(LuneMenu.Area area, double mouseX, double mouseY, int button) {
        RiddleGame game = RiddleGame.current();
        if (game == null || button != dragButton) {
            return;
        }
        Layout layout = layout(area, Minecraft.getInstance().font);
        double dx = mouseX - dragX;
        double dy = mouseY - dragY;
        int steps = Math.max(1, (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dy)) / DRAG_STEP));
        for (int step = 1; step <= steps && game.heldCount() > 0; step++) {
            int cell = cellAt(layout, dragX + dx * step / steps, dragY + dy * step / steps);
            if (cell >= 0) {
                played(game, game.spread(cell), SoundEvents.BUNDLE_INSERT);
            }
        }
        dragX = mouseX;
        dragY = mouseY;
    }

    void release(LuneMenu.Area area, double mouseX, double mouseY, int button) {
        if (button == dragButton) {
            dragButton = -1;
        }
    }

    private static void played(RiddleGame game, RiddleGame.Outcome outcome, SoundEvent sound) {
        if (outcome != RiddleGame.Outcome.REFUSED) {
            play(sound, 0.8F + ThreadLocalRandom.current().nextFloat() * 0.4F, 0.6F);
            finished(game, outcome);
        }
    }

    /** The riddle was solved by that action: counted, and cheered. */
    private static void finished(RiddleGame game, RiddleGame.Outcome outcome) {
        if (outcome == RiddleGame.Outcome.SOLVED) {
            RiddleRecords.recordSolved(game.hints() == 0);
            play(SoundEvents.PLAYER_LEVELUP, 1.0F, 0.6F);
        }
    }

    private static void play(SoundEvent sound, float pitch, float volume) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }
}
