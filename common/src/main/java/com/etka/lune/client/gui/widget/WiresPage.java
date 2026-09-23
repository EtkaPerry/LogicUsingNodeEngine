package com.etka.lune.client.gui.widget;

import com.etka.lune.client.gui.Accessibility;
import com.etka.lune.client.gui.LuneScreen;
import com.etka.lune.client.gui.mascot.MascotAdvisor;
import com.etka.lune.compat.Redstone;
import com.etka.lune.games.WiresBoard;
import com.etka.lune.games.WiresGame;
import com.etka.lune.games.WiresRecords;
import com.etka.lune.games.WiresSize;
import com.etka.lune.util.Lang;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Redstone Wires, the first game on the Games shelf: turn the dust until the block of redstone in
 * the middle powers every lamp on the board.
 *
 * <p>Everything on the board is the game's own: smooth stone, the two dust textures and the dot,
 * the lamp lit and unlit, the block of redstone. They are read from the game's resources, so a
 * resource pack's redstone is the redstone the player sees, and the dust is tinted with the colour
 * the game gives it at power 15 and at power 0 - lit and dark dust look exactly as they do in the
 * world. The sounds are the game's too, and deliberately none of the ones Lune's alerts use: from
 * another window "you turned a tile" must never sound like "the run finished".</p>
 *
 * <p>Left click turns a tile clockwise and right click turns it back; a middle click or a Shift
 * click locks a tile the player is sure of. The board, the clock and the size in play belong to
 * {@link WiresGame}, which outlives the panel; only what the eye is easing through - a tile half
 * way round a turn, the flash as the last lamp lights - lives here.</p>
 */
final class WiresPage {

    private static final Identifier STONE = block("smooth_stone");
    private static final Identifier DOT = block("redstone_dust_dot");
    /** The game draws dust running north and south from one texture and east and west from the other. */
    private static final Identifier LINE_NS = block("redstone_dust_line0");
    private static final Identifier LINE_EW = block("redstone_dust_line1");
    private static final Identifier LAMP = block("redstone_lamp");
    private static final Identifier LAMP_ON = block("redstone_lamp_on");
    private static final Identifier SOURCE = block("redstone_block");

    /** The shelf's picture of the game: a block of redstone lighting four lamps. */
    private static final WiresBoard PICTURE = WiresBoard.fixed(3, 3, 4, new int[]{
            0, WiresBoard.SOUTH, 0,
            WiresBoard.EAST, WiresBoard.NORTH | WiresBoard.EAST | WiresBoard.SOUTH | WiresBoard.WEST, WiresBoard.WEST,
            0, WiresBoard.NORTH, 0});

    private static final WiresSize[] SIZES = WiresSize.values();
    private static final int FULL_POWER = 15;
    private static final float QUARTER = (float) (Math.PI / 2.0);

    /** Room under the title and the line below it, where the board and the side column start. */
    private static final int BODY_TOP = 38;
    private static final int SIDE_W = 120;
    private static final int SIDE_GAP = 12;
    private static final int CHIP_H = 16;
    private static final int CHIP_GAP = 4;
    private static final int BUTTON_H = 20;
    private static final int STATS_ROW = 11;
    private static final int STATS_ROWS = 4;
    /** Beside a size's label: a gap, the tick, and a gap before the count. */
    static final int TICK_ROOM = 3 + 7 + 1;
    private static final int MAX_TILE = 48;
    /** A lamp and the source take five eighths of their tile, leaving the dust around them visible. */
    private static final int CORE_EIGHTHS = 5;
    private static final int TURN_MILLIS = 110;
    private static final int FLASH_MILLIS = 700;
    /** How long a first click on a new board waits for the second while a game is in progress. */
    private static final int ARM_MILLIS = 3000;

    private static final int LOCKED_TINT = 0xFF9AA4B4;
    private static final int LOCK_EDGE = 0xE0E6EAF2;
    private static final int HOVER_TILE = 0x30FFFFFF;
    private static final int CHIP = 0x24FFFFFF;
    private static final int CHIP_HOVER_EDGE = 0x90FFFFFF;

    private final Runnable back;
    /** The board the easing below was recorded on; a new board starts with nothing turning. */
    private WiresBoard animated;
    private long[] turnedAt = new long[0];
    private boolean[] turnedClockwise = new boolean[0];
    private float[] angles = new float[0];
    /** The size a second click would start a new board of, while a game is in progress; null otherwise. */
    private WiresSize armed;
    private long armedAt;

    private record Layout(LuneMenu.Area back, int titleY, int lineY, int tile, int boardX, int boardY,
                          LuneMenu.Area side, List<LuneMenu.Area> chips, LuneMenu.Area newBoard,
                          LuneMenu.Area stats, int hintY) {}

    /** {@code back} returns the page to the shelf. */
    WiresPage(Runnable back) {
        this.back = back;
    }

    /** A block texture, read straight from the game's resources. */
    private static Identifier block(String name) {
        return Identifier.withDefaultNamespace("textures/block/" + name + ".png");
    }

    static String title() {
        return Lang.get("lune.gui.games.wires");
    }

    // --- what Lune says --------------------------------------------------------

    String luneLine() {
        WiresGame game = WiresGame.current();
        WiresBoard board = game.board();
        String clock = WiresGame.clock(game.elapsedMillis());
        if (game.solved()) {
            return Lang.get(game.newBest() ? "lune.mascot.corner.wires_best" : "lune.mascot.corner.wires_done", clock);
        }
        if (!game.started()) {
            return Lang.get("lune.mascot.corner.wires_start");
        }
        int dark = board.lampCount() - board.litLamps();
        if (dark == 0) {
            return Lang.get("lune.mascot.corner.wires_dust");
        }
        return dark == 1 ? Lang.get("lune.mascot.corner.wires_last") : Lang.get("lune.mascot.corner.wires_dark", dark);
    }

    MascotAdvisor.Mood luneMood() {
        WiresGame game = WiresGame.current();
        if (game.solved()) {
            return MascotAdvisor.Mood.SUCCESS;
        }
        return game.started() ? MascotAdvisor.Mood.THINKING : MascotAdvisor.Mood.IDLE;
    }

    // --- the page --------------------------------------------------------------

    /** The board is back in sight: the clock picks up from here, not from when it was last drawn. */
    void shown() {
        WiresGame.current().unwatched();
        armed = null;
    }

    private static Layout layout(LuneMenu.Area area, Font font, WiresBoard board) {
        int titleY = area.y() + 2;
        int top = area.y() + BODY_TOP;
        int height = Math.max(0, area.bottom() - top);
        int sideX = area.right() - SIDE_W;
        LuneMenu.Area side = new LuneMenu.Area(sideX, top, SIDE_W, height);
        int boxWidth = Math.max(0, sideX - SIDE_GAP - area.x());
        int tile = tileSize(boxWidth, height, board.columns(), board.rows());
        int boardX = area.x() + (boxWidth - tile * board.columns()) / 2;
        int boardY = top + Math.max(0, (height - tile * board.rows()) / 2);

        int chipWidth = (SIDE_W - CHIP_GAP) / 2;
        List<LuneMenu.Area> chips = new ArrayList<>(SIZES.length);
        for (int i = 0; i < SIZES.length; i++) {
            chips.add(new LuneMenu.Area(sideX + (i % 2) * (chipWidth + CHIP_GAP),
                    top + (i / 2) * (CHIP_H + CHIP_GAP), chipWidth, CHIP_H));
        }
        int chipRows = (SIZES.length + 1) / 2;
        int chipsBottom = top + chipRows * CHIP_H + (chipRows - 1) * CHIP_GAP;
        LuneMenu.Area newBoard = new LuneMenu.Area(sideX, chipsBottom + 8, SIDE_W, BUTTON_H);
        LuneMenu.Area stats = new LuneMenu.Area(sideX, newBoard.bottom() + 8, SIDE_W, STATS_ROWS * STATS_ROW + 8);
        // The chevron and the title are one control: a big target for the way back.
        LuneMenu.Area backArea = new LuneMenu.Area(area.x(), titleY - 1, 14 + font.width(title()) * 2, 19);
        return new Layout(backArea, titleY, area.y() + 24, tile, boardX, boardY, side, chips, newBoard, stats,
                stats.bottom() + 10);
    }

    /**
     * The tile size a board of {@code columns} by {@code rows} gets in a box that size.
     *
     * <p>From sixteen up it is snapped down to a multiple of eight, so a texture is drawn at a whole
     * or a half-and-whole scale and its pixels stay even; below that the board is cramped anyway and
     * is given every pixel it can have, kept even so a tile's two halves meet in the middle.</p>
     */
    static int tileSize(int width, int height, int columns, int rows) {
        int fit = Math.min(width / Math.max(1, columns), height / Math.max(1, rows));
        if (fit >= 16) {
            return Math.min(MAX_TILE, fit - fit % 8);
        }
        return Math.max(4, fit - fit % 2);
    }

    void render(GuiGraphicsExtractor extractor, LuneMenu.Area area, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        long now = Util.getMillis();
        WiresGame game = WiresGame.current();
        WiresBoard board = game.board();
        game.watched(now);
        if (armed != null && now - armedAt > ARM_MILLIS) {
            armed = null;
        }
        Layout layout = layout(area, font, board);

        drawHeader(extractor, font, area, layout, game, mouseX, mouseY);
        int hovered = game.solved() ? -1 : tileAt(layout, board, mouseX, mouseY);
        float flash = game.solved()
                ? Math.clamp(1.0F - (now - game.solvedAt()) / (float) FLASH_MILLIS, 0.0F, 1.0F) : 0.0F;
        drawBoard(extractor, board, layout.boardX(), layout.boardY(), layout.tile(), hovered,
                angles(board, now), flash);
        drawSide(extractor, font, layout, game, mouseX, mouseY);
    }

    private static void drawHeader(GuiGraphicsExtractor extractor, Font font, LuneMenu.Area area, Layout layout,
                                   WiresGame game, int mouseX, int mouseY) {
        WiresBoard board = game.board();
        int good = Accessibility.colour(Accessibility.Mark.GOOD);
        boolean backHot = layout.back().contains(mouseX, mouseY);
        MenuPaint.chevron(extractor, area.x() + 2, layout.titleY() + 3, false,
                backHot ? MenuPaint.WHITE : LuneScreen.TEXT);
        MenuPaint.bigText(extractor, title(), area.x() + 14, layout.titleY(), MenuPaint.WHITE);

        // The lamps lit, with a lamp beside the count that lights once they all are.
        String lamps = board.litLamps() + "/" + board.lampCount();
        boolean allLit = board.litLamps() == board.lampCount();
        int lampsX = area.right() - 2 - font.width(lamps);
        extractor.blit(RenderPipelines.GUI_TEXTURED, allLit ? LAMP_ON : LAMP, lampsX - 13, layout.titleY() + 4,
                0.0F, 0.0F, 10, 10, 16, 16, 16, 16);
        MenuPaint.text(extractor, lamps, lampsX, layout.titleY() + 5, allLit ? good : LuneScreen.TEXT);

        String line;
        int colour;
        if (game.solved()) {
            String clock = WiresGame.clock(game.elapsedMillis());
            line = board.turns() == 1 ? Lang.get("lune.gui.games.solved_one", clock)
                    : Lang.get("lune.gui.games.solved", clock, board.turns());
            if (game.newBest()) {
                line += "  " + Lang.get("lune.gui.games.new_best");
            }
            colour = good;
        } else {
            line = Lang.get("lune.gui.games.wires_about");
            colour = Accessibility.dim();
        }
        MenuPaint.text(extractor, MenuPaint.clip(font, line, area.width() - 4), area.x() + 2, layout.lineY(), colour);
    }

    /**
     * A board, every tile {@code tile} pixels square, from the top left at {@code x, y}.
     *
     * <p>Drawn a texture at a time rather than a tile at a time - all the stone, then all the dust,
     * then the dots, the lamps and the source - because the renderer merges neighbouring draws of
     * one texture and a tile at a time would switch texture six times a tile.</p>
     *
     * @param hovered the tile under the pointer, or -1
     * @param turning per tile, the angle still to ease through, or null when nothing is turning
     * @param flash   how bright the lamps flash as the board is solved, 0 for not at all
     */
    static void drawBoard(GuiGraphicsExtractor extractor, WiresBoard board, int x, int y, int tile, int hovered,
                          float[] turning, float flash) {
        int columns = board.columns();
        int half = tile / 2;
        int core = tile * CORE_EIGHTHS / 8;
        int inset = (tile - core) / 2;
        int lit = Redstone.dustColour(FULL_POWER);
        int dark = Redstone.dustColour(0);

        for (int i = 0; i < board.size(); i++) {
            extractor.blit(RenderPipelines.GUI_TEXTURED, STONE, tileX(x, i, columns, tile), tileY(y, i, columns, tile),
                    0.0F, 0.0F, tile, tile, 16, 16, 16, 16, board.isLocked(i) ? LOCKED_TINT : -1);
        }
        // North and south, the line's own way round.
        for (int i = 0; i < board.size(); i++) {
            int links = board.links(i);
            if ((links & (WiresBoard.NORTH | WiresBoard.SOUTH)) == 0) {
                continue;
            }
            int tx = tileX(x, i, columns, tile);
            int ty = tileY(y, i, columns, tile);
            int colour = board.isPowered(i) ? lit : dark;
            turn(extractor, tx, ty, tile, angle(turning, i));
            if ((links & WiresBoard.NORTH) != 0) {
                halfLine(extractor, LINE_NS, tx, ty, tile, half, true, colour);
            }
            if ((links & WiresBoard.SOUTH) != 0) {
                halfLine(extractor, LINE_NS, tx, ty, tile, half, false, colour);
            }
            extractor.pose().popMatrix();
        }
        // East and west: the same kind of line a quarter turn round, as the game turns its model.
        for (int i = 0; i < board.size(); i++) {
            int links = board.links(i);
            if ((links & (WiresBoard.EAST | WiresBoard.WEST)) == 0) {
                continue;
            }
            int tx = tileX(x, i, columns, tile);
            int ty = tileY(y, i, columns, tile);
            int colour = board.isPowered(i) ? lit : dark;
            turn(extractor, tx, ty, tile, angle(turning, i) + QUARTER);
            // A quarter turn clockwise carries the top half to the east and the bottom half west.
            if ((links & WiresBoard.EAST) != 0) {
                halfLine(extractor, LINE_EW, tx, ty, tile, half, true, colour);
            }
            if ((links & WiresBoard.WEST) != 0) {
                halfLine(extractor, LINE_EW, tx, ty, tile, half, false, colour);
            }
            extractor.pose().popMatrix();
        }
        // The dot where dust bends or branches; straight dust has none, as in the world.
        for (int i = 0; i < board.size(); i++) {
            if (board.links(i) == 0 || board.isStraight(i) || board.isLamp(i) || i == board.source()) {
                continue;
            }
            int tx = tileX(x, i, columns, tile);
            int ty = tileY(y, i, columns, tile);
            turn(extractor, tx, ty, tile, angle(turning, i));
            extractor.blit(RenderPipelines.GUI_TEXTURED, DOT, tx, ty, 0.0F, 0.0F, tile, tile, 16, 16, 16, 16,
                    board.isPowered(i) ? lit : dark);
            extractor.pose().popMatrix();
        }
        // Lamps, dark ones and lit ones apart so each texture is one run of draws.
        for (int pass = 0; pass < 2; pass++) {
            boolean litPass = pass == 1;
            for (int i = 0; i < board.size(); i++) {
                if (!board.isLamp(i) || board.isPowered(i) != litPass) {
                    continue;
                }
                core(extractor, litPass ? LAMP_ON : LAMP, tileX(x, i, columns, tile), tileY(y, i, columns, tile),
                        tile, core, inset, angle(turning, i));
            }
        }
        int source = board.source();
        core(extractor, SOURCE, tileX(x, source, columns, tile), tileY(y, source, columns, tile), tile, core, inset,
                angle(turning, source));

        for (int i = 0; i < board.size(); i++) {
            int tx = tileX(x, i, columns, tile);
            int ty = tileY(y, i, columns, tile);
            if (board.isLocked(i)) {
                extractor.outline(tx, ty, tile, tile, LOCK_EDGE);
            }
            if (flash > 0.0F && board.isLamp(i) && board.isPowered(i)) {
                extractor.fill(tx + inset, ty + inset, tx + inset + core, ty + inset + core,
                        MenuPaint.fade(MenuPaint.WHITE, flash * 0.7F));
            }
        }
        if (hovered >= 0 && hovered < board.size()) {
            int tx = tileX(x, hovered, columns, tile);
            int ty = tileY(y, hovered, columns, tile);
            extractor.fill(tx, ty, tx + tile, ty + tile, HOVER_TILE);
        }
    }

    /** The shelf's picture of this game, at {@code tile} pixels a tile: three tiles square. */
    static void drawPicture(GuiGraphicsExtractor extractor, int x, int y, int tile) {
        drawBoard(extractor, PICTURE, x, y, tile, -1, null, 0.0F);
    }

    /** The left edge of tile number {@code index} on a board drawn from {@code x}, {@code size} pixels a tile. */
    private static int tileX(int x, int index, int columns, int size) {
        return x + (index % columns) * size;
    }

    private static int tileY(int y, int index, int columns, int size) {
        return y + (index / columns) * size;
    }

    private static float angle(float[] turning, int tile) {
        return turning == null || tile >= turning.length ? 0.0F : turning[tile];
    }

    /** Pushes a turn of {@code angle} about the tile's centre; the caller pops it. */
    private static void turn(GuiGraphicsExtractor extractor, int tx, int ty, int tile, float angle) {
        var pose = extractor.pose();
        pose.pushMatrix();
        if (angle != 0.0F) {
            float centreX = tx + tile / 2.0F;
            float centreY = ty + tile / 2.0F;
            pose.translate(centreX, centreY);
            pose.rotate(angle);
            pose.translate(-centreX, -centreY);
        }
    }

    /** One half of a dust line, from the middle of the tile to its top edge or its bottom edge. */
    private static void halfLine(GuiGraphicsExtractor extractor, Identifier line, int tx, int ty, int tile, int half,
                                 boolean top, int colour) {
        if (top) {
            extractor.blit(RenderPipelines.GUI_TEXTURED, line, tx, ty, 0.0F, 0.0F, tile, half, 16, 8, 16, 16, colour);
        } else {
            extractor.blit(RenderPipelines.GUI_TEXTURED, line, tx, ty + half, 0.0F, 8.0F, tile, tile - half,
                    16, 8, 16, 16, colour);
        }
    }

    /** A lamp or the source, in the middle of its tile with the dust showing round it. */
    private static void core(GuiGraphicsExtractor extractor, Identifier texture, int tx, int ty, int tile, int core,
                             int inset, float angle) {
        turn(extractor, tx, ty, tile, angle);
        extractor.blit(RenderPipelines.GUI_TEXTURED, texture, tx + inset, ty + inset, 0.0F, 0.0F, core, core,
                16, 16, 16, 16);
        extractor.pose().popMatrix();
    }

    private void drawSide(GuiGraphicsExtractor extractor, Font font, Layout layout, WiresGame game,
                          int mouseX, int mouseY) {
        LuneMenu.Area side = layout.side();
        int good = Accessibility.colour(Accessibility.Mark.GOOD);
        int warn = Accessibility.colour(Accessibility.Mark.WARN);
        String sure = Lang.get("lune.gui.tasks.sure");

        for (int i = 0; i < SIZES.length; i++) {
            WiresSize size = SIZES[i];
            LuneMenu.Area chip = layout.chips().get(i);
            if (chip.bottom() > side.bottom()) {
                return;
            }
            boolean chosen = size == game.size();
            boolean hot = chip.contains(mouseX, mouseY);
            boolean asking = armed == size;
            MenuPaint.roundedFill(extractor, chip.x(), chip.y(), chip.width(), chip.height(),
                    chosen ? LuneScreen.ACCENT_SELECTION : hot ? MenuPaint.HOVER : CHIP);
            MenuPaint.roundedOutline(extractor, chip.x(), chip.y(), chip.width(), chip.height(),
                    asking ? warn : chosen ? LuneScreen.ACCENT : hot ? CHIP_HOVER_EDGE : MenuPaint.CARD_EDGE);
            // A tick and a count for a size cleared: how many boards of it the player has solved.
            String label = asking ? sure : size.label();
            int solved = asking ? 0 : WiresRecords.cleared(size);
            String count = solvedCount(font::width, font.width(label), chip.width() - 4, solved);
            int markWidth = solved <= 0 ? 0 : count.isEmpty() ? TICK_ROOM - 1 : TICK_ROOM + font.width(count);
            label = MenuPaint.clip(font, label, chip.width() - 4 - markWidth);
            int labelX = chip.x() + (chip.width() - font.width(label) - markWidth) / 2;
            MenuPaint.text(extractor, label, labelX, chip.y() + 4,
                    asking ? warn : chosen ? MenuPaint.WHITE : LuneScreen.TEXT);
            if (solved > 0) {
                int tickX = labelX + font.width(label) + 3;
                MenuPaint.check(extractor, tickX, chip.y() + 5, good);
                MenuPaint.text(extractor, count, tickX + 8, chip.y() + 4, good);
            }
        }

        LuneMenu.Area button = layout.newBoard();
        if (button.bottom() > side.bottom()) {
            return;
        }
        boolean asking = armed == game.size();
        MenuPaint.button(extractor, font, button.x(), button.y(), button.width(), button.height(),
                MenuPaint.clip(font, asking ? sure : Lang.get("lune.gui.games.new_board"), button.width() - 12),
                game.solved() ? MenuPaint.Button.PRIMARY : MenuPaint.Button.SECONDARY,
                button.contains(mouseX, mouseY));

        LuneMenu.Area stats = layout.stats();
        if (stats.bottom() > side.bottom()) {
            return;
        }
        MenuPaint.card(extractor, stats.x(), stats.y(), stats.width(), stats.height());
        long best = WiresRecords.best(game.size());
        statsRow(extractor, font, stats, 0, Lang.get("lune.gui.games.turns"),
                String.valueOf(game.board().turns()), MenuPaint.WHITE);
        statsRow(extractor, font, stats, 1, Lang.get("lune.gui.games.time"),
                WiresGame.clock(game.elapsedMillis()), game.solved() ? good : MenuPaint.WHITE);
        statsRow(extractor, font, stats, 2, Lang.get("lune.gui.games.best"),
                best < 0 ? "-" : WiresGame.clock(best), game.newBest() ? good : MenuPaint.WHITE);
        int solved = WiresRecords.cleared(game.size());
        statsRow(extractor, font, stats, 3, Lang.get("lune.gui.games.solved_label"), String.valueOf(solved),
                solved > 0 ? good : MenuPaint.WHITE);

        // The controls, when at least two lines of them fit; one clipped line would say nothing.
        int lines = Math.min(5, (side.bottom() - layout.hintY()) / 10);
        List<String> hint = lines < 2 ? List.of()
                : MenuPaint.wrap(font, Lang.get("lune.gui.games.wires_controls"), SIDE_W - 2, lines);
        for (int i = 0; i < hint.size(); i++) {
            MenuPaint.text(extractor, hint.get(i), side.x() + 1, layout.hintY() + i * 10, Accessibility.dim());
        }
    }

    /**
     * The count a chip shows beside its tick: the number of boards solved, "99+" once that no
     * longer fits beside the size, and blank - the tick on its own - when even that does not.
     */
    static String solvedCount(ToIntFunction<String> width, int labelWidth, int room, int solved) {
        if (solved <= 0) {
            return "";
        }
        for (String count : new String[]{String.valueOf(solved), "99+"}) {
            if (labelWidth + TICK_ROOM + width.applyAsInt(count) <= room) {
                return count;
            }
        }
        return "";
    }

    private static void statsRow(GuiGraphicsExtractor extractor, Font font, LuneMenu.Area card, int row,
                                 String label, String value, int valueColour) {
        int y = card.y() + 5 + row * STATS_ROW;
        int valueWidth = font.width(value);
        MenuPaint.text(extractor, MenuPaint.clip(font, label, card.width() - 18 - valueWidth), card.x() + 6, y,
                Accessibility.dim());
        MenuPaint.text(extractor, value, card.right() - 6 - valueWidth, y, valueColour);
    }

    // --- input -----------------------------------------------------------------

    void click(LuneMenu.Area area, double mouseX, double mouseY, int button) {
        long now = Util.getMillis();
        WiresGame game = WiresGame.current();
        Layout layout = layout(area, Minecraft.getInstance().font, game.board());
        boolean left = button == InputConstants.MOUSE_BUTTON_LEFT;
        WiresSize wasArmed = armed;
        armed = null;

        int tile = tileAt(layout, game.board(), mouseX, mouseY);
        if (tile >= 0) {
            if (button == InputConstants.MOUSE_BUTTON_MIDDLE || left && Minecraft.getInstance().hasShiftDown()) {
                if (game.toggleLock(tile)) {
                    // The lever's own click, higher as it goes on than as it comes off.
                    play(SoundEvents.LEVER_CLICK, game.board().isLocked(tile) ? 0.6F : 0.5F, 0.3F);
                }
                return;
            }
            if (!left && button != InputConstants.MOUSE_BUTTON_RIGHT) {
                return;
            }
            WiresGame.Outcome outcome = game.turn(tile, left, now);
            if (outcome == WiresGame.Outcome.REFUSED) {
                return;
            }
            eased(game.board(), tile, left, now);
            if (outcome == WiresGame.Outcome.SOLVED) {
                if (WiresRecords.record(game.size(), game.elapsedMillis())) {
                    game.markNewBest();
                }
                play(SoundEvents.PLAYER_LEVELUP, 1.0F, 0.6F);
            } else {
                play(SoundEvents.COMPARATOR_CLICK, left ? 0.55F : 0.5F, 0.35F);
            }
            return;
        }
        if (!left) {
            return;
        }
        if (layout.back().contains(mouseX, mouseY)) {
            MenuPaint.click();
            back.run();
            return;
        }
        for (int i = 0; i < SIZES.length; i++) {
            if (layout.chips().get(i).contains(mouseX, mouseY)) {
                request(SIZES[i], wasArmed, now);
                return;
            }
        }
        if (layout.newBoard().contains(mouseX, mouseY)) {
            request(game.size(), wasArmed, now);
        }
    }

    /**
     * A new board of {@code size}. While a game is in progress it takes a second click, because the
     * half-lit board it throws away has no undo.
     */
    private void request(WiresSize size, WiresSize wasArmed, long now) {
        MenuPaint.click();
        if (WiresGame.current().inProgress() && wasArmed != size) {
            armed = size;
            armedAt = now;
            return;
        }
        WiresGame.startNew(size);
    }

    private static int tileAt(Layout layout, WiresBoard board, double mouseX, double mouseY) {
        double dx = mouseX - layout.boardX();
        double dy = mouseY - layout.boardY();
        if (dx < 0 || dy < 0 || layout.tile() <= 0) {
            return -1;
        }
        int column = (int) (dx / layout.tile());
        int row = (int) (dy / layout.tile());
        return column >= board.columns() || row >= board.rows() ? -1 : row * board.columns() + column;
    }

    /** Starts {@code tile} easing through the quarter turn it has just made. */
    private void eased(WiresBoard board, int tile, boolean clockwise, long now) {
        if (animated != board) {
            animated = board;
            turnedAt = new long[board.size()];
            turnedClockwise = new boolean[board.size()];
            angles = new float[board.size()];
        }
        turnedAt[tile] = now;
        turnedClockwise[tile] = clockwise;
    }

    /**
     * Per tile, the angle it still has to turn through, or null when none is turning.
     *
     * <p>A turned tile's links change at once; it is drawn starting from where it was and easing
     * into where it now is, so the eye can follow which way it went.</p>
     */
    private float[] angles(WiresBoard board, long now) {
        if (animated != board) {
            return null;
        }
        boolean any = false;
        for (int i = 0; i < angles.length; i++) {
            long age = now - turnedAt[i];
            if (turnedAt[i] > 0 && age >= 0 && age < TURN_MILLIS) {
                float remaining = 1.0F - age / (float) TURN_MILLIS;
                // Eased out: quick off the mark, settling gently into place.
                float left = remaining * remaining * remaining;
                angles[i] = (turnedClockwise[i] ? -QUARTER : QUARTER) * left;
                any = true;
            } else {
                angles[i] = 0.0F;
            }
        }
        return any ? angles : null;
    }

    private static void play(SoundEvent sound, float pitch, float volume) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }
}
