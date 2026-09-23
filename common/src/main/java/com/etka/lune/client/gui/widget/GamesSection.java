package com.etka.lune.client.gui.widget;

import com.etka.lune.client.gui.Accessibility;
import com.etka.lune.client.gui.LuneScreen;
import com.etka.lune.client.gui.mascot.MascotAdvisor;
import com.etka.lune.games.RiddleGame;
import com.etka.lune.games.RiddleRecords;
import com.etka.lune.games.WiresGame;
import com.etka.lune.games.WiresRecords;
import com.etka.lune.games.WiresSize;
import com.etka.lune.util.Lang;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The Games shelf of Lune's corner: something to play while Lune works.
 *
 * <p>The page is the shelf - a card for each game, and one saying more are on their way - until a
 * game is opened, and then it is that game, with the way back in its title. Which was open is
 * remembered, so the corner reopens on the game the player left rather than on the shelf: a page
 * is where things are started from, not a detour on the way to them.</p>
 */
public final class GamesSection implements LuneMenu.Section {

    private static final int BODY = 0xFF6E5A8C;
    private static final int SHINE = 0xFF8C78AA;
    private static final int PAD = 0xFFE6DEF2;
    private static final int GRIP = 0xFF5A4974;
    /** The controller's size in its own pixels, before any scaling. */
    private static final int ART_W = 18;
    private static final int ART_H = 11;

    private static final int CARD_H = 64;
    private static final int CARD_GAP = 8;
    private static final int MORE_H = 40;
    private static final int BUTTON_H = 20;
    /** A card's picture is three tiles of the game itself, at the game's own size. */
    private static final int PICTURE_TILE = 16;
    private static final int PICTURE = PICTURE_TILE * 3;
    private static final int ICON_TILE = 24;
    private static final int ICON_TILE_FILL = 0xFF1B1E26;
    private static final int ICON_TILE_EDGE = 0xFF363A47;
    /** The grey of a slot and the dark of its inner edge, for the riddle's little crafting grid. */
    private static final int SLOT_GREY = 0xFF8B8B8B;
    private static final int SLOT_EDGE = 0xFF373737;

    /** The games on the shelf, in the order their cards are drawn. */
    private enum Game { WIRES, RIDDLE }

    /**
     * The game open instead of the shelf, or null for the shelf.
     *
     * <p>Static for the reason the games themselves are: the panel is rebuilt every time it opens.</p>
     */
    private static Game open;

    private final WiresPage wires = new WiresPage(() -> open = null);
    private final RiddlePage riddle = new RiddlePage(() -> open = null);

    @Override
    public String title() {
        return Lang.get("lune.gui.menu.games");
    }

    /** How many games have been solved in all, once there are any; how many games there are until then. */
    @Override
    public String caption() {
        int solved = WiresRecords.clearedTotal() + RiddleRecords.solved();
        if (solved == 0) {
            return Lang.get("lune.gui.games.count", Game.values().length);
        }
        return solved == 1 ? Lang.get("lune.gui.games.solved_once") : Lang.get("lune.gui.games.solved_times", solved);
    }

    /** Until the first game is solved: a game nobody has found is a game nobody plays. */
    @Override
    public boolean hasNews() {
        return WiresRecords.clearedTotal() == 0 && RiddleRecords.solved() == 0;
    }

    @Override
    public String luneLine() {
        if (open == Game.WIRES) {
            return wires.luneLine();
        }
        return open == Game.RIDDLE ? riddle.luneLine() : Lang.get("lune.mascot.corner.games_pick");
    }

    @Override
    public MascotAdvisor.Mood luneMood() {
        if (open == Game.WIRES) {
            return wires.luneMood();
        }
        return open == Game.RIDDLE ? riddle.luneMood() : MascotAdvisor.Mood.IDLE;
    }

    @Override
    public void drawIcon(GuiGraphicsExtractor extractor, int x, int y, int size) {
        controller(extractor, x + (size - ART_W) / 2, y + (size - ART_H) / 2);
    }

    /** A game controller, 18 by 11: a body with grips, a pad on the left and two buttons on the right. */
    private static void controller(GuiGraphicsExtractor extractor, int x, int y) {
        extractor.fill(x + 1, y, x + 17, y + 8, BODY);
        extractor.fill(x, y + 1, x + 18, y + 7, BODY);
        extractor.fill(x + 2, y + 1, x + 16, y + 2, SHINE);
        extractor.fill(x + 1, y + 8, x + 6, y + 10, GRIP);
        extractor.fill(x + 12, y + 8, x + 17, y + 10, GRIP);
        extractor.fill(x + 2, y + 10, x + 5, y + 11, GRIP);
        extractor.fill(x + 13, y + 10, x + 16, y + 11, GRIP);
        extractor.fill(x + 3, y + 4, x + 8, y + 5, PAD);
        extractor.fill(x + 5, y + 2, x + 6, y + 7, PAD);
        extractor.fill(x + 12, y + 2, x + 14, y + 4, LuneScreen.ACCENT);
        extractor.fill(x + 14, y + 4, x + 16, y + 6, Accessibility.colour(Accessibility.Mark.GOOD));
    }

    @Override
    public void shown() {
        if (open == Game.WIRES) {
            wires.shown();
        } else if (open == Game.RIDDLE) {
            riddle.shown();
        }
    }

    @Override
    public void render(GuiGraphicsExtractor extractor, LuneMenu.Area area, int mouseX, int mouseY) {
        if (open == Game.WIRES) {
            wires.render(extractor, area, mouseX, mouseY);
            return;
        }
        if (open == Game.RIDDLE) {
            riddle.render(extractor, area, mouseX, mouseY);
            return;
        }
        Font font = Minecraft.getInstance().font;
        MenuPaint.bigText(extractor, title(), area.x() + 2, area.y() + 2, MenuPaint.WHITE);
        MenuPaint.text(extractor, MenuPaint.clip(font, Lang.get("lune.gui.menu.games_about"), area.width() - 4),
                area.x() + 2, area.y() + 24, Accessibility.dim());

        for (Game game : Game.values()) {
            LuneMenu.Area card = card(area, game);
            if (card.bottom() > area.bottom()) {
                return;
            }
            drawCard(extractor, font, card, game, mouseX, mouseY);
        }
        LuneMenu.Area more = new LuneMenu.Area(area.x(), card(area, Game.RIDDLE).bottom() + CARD_GAP, area.width(), MORE_H);
        if (more.bottom() <= area.bottom()) {
            drawMore(extractor, font, more);
        }
    }

    private static LuneMenu.Area card(LuneMenu.Area area, Game game) {
        return new LuneMenu.Area(area.x(), area.y() + 40 + game.ordinal() * (CARD_H + CARD_GAP), area.width(), CARD_H);
    }

    /** What a card's button says: carry on with a game in progress, or play. */
    private static String action(Game game) {
        boolean inProgress = game == Game.WIRES
                ? WiresGame.exists() && WiresGame.current().inProgress()
                : RiddleGame.current() != null && RiddleGame.current().inProgress();
        return Lang.get(inProgress ? "lune.gui.tasks.debug_continue" : "lune.gui.games.play");
    }

    private static LuneMenu.Area button(LuneMenu.Area card, Font font, String action) {
        int width = MenuPaint.buttonWidth(font, action, MenuPaint.Button.PRIMARY);
        return new LuneMenu.Area(card.right() - 10 - width, card.y() + (CARD_H - BUTTON_H) / 2, width, BUTTON_H);
    }

    private void drawCard(GuiGraphicsExtractor extractor, Font font, LuneMenu.Area card, Game game,
                          int mouseX, int mouseY) {
        MenuPaint.card(extractor, card.x(), card.y(), card.width(), card.height());
        if (card.contains(mouseX, mouseY)) {
            MenuPaint.roundedFill(extractor, card.x(), card.y(), card.width(), card.height(), MenuPaint.HOVER);
        }
        int pictureX = card.x() + 8;
        int pictureY = card.y() + (CARD_H - PICTURE) / 2;
        if (game == Game.WIRES) {
            WiresPage.drawPicture(extractor, pictureX, pictureY, PICTURE_TILE);
        } else {
            drawRiddlePicture(extractor, pictureX, pictureY);
        }
        extractor.outline(pictureX - 1, pictureY - 1, PICTURE + 2, PICTURE + 2, MenuPaint.CARD_EDGE);

        String action = action(game);
        LuneMenu.Area button = button(card, font, action);
        int textX = pictureX + PICTURE + 10;
        int textWidth = Math.max(10, button.x() - 8 - textX);
        String name = game == Game.WIRES ? WiresPage.title() : RiddlePage.title();
        String about = Lang.get(game == Game.WIRES ? "lune.gui.games.wires_about" : "lune.gui.games.riddle_card");
        MenuPaint.text(extractor, MenuPaint.clip(font, name, textWidth), textX, card.y() + 12, MenuPaint.WHITE);
        MenuPaint.text(extractor, MenuPaint.clip(font, about, textWidth), textX, card.y() + 26, Accessibility.dim());
        if (game == Game.WIRES) {
            drawWiresRecords(extractor, font, textX, card.y() + 40, textWidth);
        } else {
            drawRiddleRecords(extractor, font, textX, card.y() + 40, textWidth);
        }
        MenuPaint.button(extractor, font, button.x(), button.y(), button.width(), button.height(), action,
                MenuPaint.Button.PRIMARY, button.contains(mouseX, mouseY));
    }

    /**
     * The riddle's picture: a crafting grid three items square, a stone pickaxe laid out in it, the
     * game's own items drawn the way it draws them.
     */
    private static void drawRiddlePicture(GuiGraphicsExtractor extractor, int x, int y) {
        extractor.fill(x, y, x + PICTURE, y + PICTURE, SLOT_GREY);
        for (int line = 1; line < 3; line++) {
            extractor.fill(x + line * PICTURE_TILE - 1, y, x + line * PICTURE_TILE, y + PICTURE, SLOT_EDGE);
            extractor.fill(x, y + line * PICTURE_TILE - 1, x + PICTURE, y + line * PICTURE_TILE, SLOT_EDGE);
        }
        ItemStack stone = new ItemStack(Items.COBBLESTONE);
        ItemStack stick = new ItemStack(Items.STICK);
        for (int column = 0; column < 3; column++) {
            extractor.item(stone, x + column * PICTURE_TILE, y);
        }
        extractor.item(stick, x + PICTURE_TILE, y + PICTURE_TILE);
        extractor.item(stick, x + PICTURE_TILE, y + PICTURE_TILE * 2);
    }

    private static void drawMore(GuiGraphicsExtractor extractor, Font font, LuneMenu.Area more) {
        MenuPaint.card(extractor, more.x(), more.y(), more.width(), more.height());
        int iconX = more.x() + 8;
        int iconY = more.y() + (MORE_H - ICON_TILE) / 2;
        MenuPaint.roundedFill(extractor, iconX, iconY, ICON_TILE, ICON_TILE, ICON_TILE_FILL);
        MenuPaint.roundedOutline(extractor, iconX, iconY, ICON_TILE, ICON_TILE, ICON_TILE_EDGE);
        controller(extractor, iconX + (ICON_TILE - ART_W) / 2, iconY + (ICON_TILE - ART_H) / 2);
        // Not here yet: the picture under a veil, as a waiting row is drawn in the side menu.
        MenuPaint.roundedFill(extractor, iconX + 1, iconY + 1, ICON_TILE - 2, ICON_TILE - 2, 0x70000000);
        int textX = iconX + ICON_TILE + 10;
        int width = Math.max(10, more.right() - 8 - textX);
        MenuPaint.text(extractor, MenuPaint.clip(font, Lang.get("lune.gui.menu.soon"), width), textX, more.y() + 9,
                LuneScreen.TEXT);
        MenuPaint.text(extractor, MenuPaint.clip(font, Lang.get("lune.gui.games.more"), width), textX, more.y() + 22,
                Accessibility.dim());
    }

    /**
     * Every size solved at least once: the size, a tick and how many times, then the fastest -
     * "5×5 ✓3 0:42 · 7×7 ✓1 1:31". A size that does not fit is left for the game's own page, which
     * shows all four, rather than cut in half.
     */
    private static void drawWiresRecords(GuiGraphicsExtractor extractor, Font font, int x, int y, int width) {
        int good = Accessibility.colour(Accessibility.Mark.GOOD);
        int dim = Accessibility.dim();
        String separator = " · ";
        int cursor = x;
        boolean any = false;
        for (WiresSize size : WiresSize.values()) {
            int solved = WiresRecords.cleared(size);
            if (solved <= 0) {
                continue;
            }
            String label = size.label();
            String count = String.valueOf(solved);
            long best = WiresRecords.best(size);
            String clock = best < 0 ? "" : WiresGame.clock(best);
            int entry = (any ? font.width(separator) : 0) + font.width(label) + WiresPage.TICK_ROOM
                    + font.width(count) + (clock.isEmpty() ? 0 : 4 + font.width(clock));
            if (cursor + entry > x + width) {
                break;
            }
            if (any) {
                MenuPaint.text(extractor, separator, cursor, y, dim);
                cursor += font.width(separator);
            }
            MenuPaint.text(extractor, label, cursor, y, dim);
            cursor += font.width(label) + 3;
            MenuPaint.check(extractor, cursor, y + 1, good);
            cursor += 8;
            MenuPaint.text(extractor, count, cursor, y, good);
            cursor += font.width(count);
            if (!clock.isEmpty()) {
                MenuPaint.text(extractor, clock, cursor + 4, y, dim);
                cursor += 4 + font.width(clock);
            }
            any = true;
        }
        if (!any) {
            MenuPaint.text(extractor, MenuPaint.clip(font, Lang.get("lune.gui.games.no_best"), width), x, y, dim);
        }
    }

    /** A tick and how many riddles were solved, and how many of those without a hint. */
    private static void drawRiddleRecords(GuiGraphicsExtractor extractor, Font font, int x, int y, int width) {
        int good = Accessibility.colour(Accessibility.Mark.GOOD);
        int dim = Accessibility.dim();
        int solved = RiddleRecords.solved();
        if (solved <= 0) {
            MenuPaint.text(extractor, MenuPaint.clip(font, Lang.get("lune.gui.games.riddle_no_solve"), width), x, y, dim);
            return;
        }
        String count = String.valueOf(solved);
        MenuPaint.check(extractor, x, y + 1, good);
        MenuPaint.text(extractor, count, x + 8, y, good);
        int after = x + 8 + font.width(count);
        String clean = " · " + Lang.get("lune.gui.games.riddle_clean_count", RiddleRecords.solvedClean());
        MenuPaint.text(extractor, MenuPaint.clip(font, clean, x + width - after), after, y, dim);
    }

    @Override
    public void drag(LuneMenu.Area area, double mouseX, double mouseY, int button) {
        if (open == Game.RIDDLE) {
            riddle.drag(area, mouseX, mouseY, button);
        }
    }

    @Override
    public void release(LuneMenu.Area area, double mouseX, double mouseY, int button) {
        if (open == Game.RIDDLE) {
            riddle.release(area, mouseX, mouseY, button);
        }
    }

    @Override
    public void click(LuneMenu.Area area, double mouseX, double mouseY, int button) {
        if (open == Game.WIRES) {
            wires.click(area, mouseX, mouseY, button);
            return;
        }
        if (open == Game.RIDDLE) {
            riddle.click(area, mouseX, mouseY, button);
            return;
        }
        if (button != InputConstants.MOUSE_BUTTON_LEFT) {
            return;
        }
        // The whole card opens its game, its button included, as a step card starts its step.
        for (Game game : Game.values()) {
            if (card(area, game).contains(mouseX, mouseY) && card(area, game).bottom() <= area.bottom()) {
                MenuPaint.click();
                open = game;
                shown();
                return;
            }
        }
    }
}
