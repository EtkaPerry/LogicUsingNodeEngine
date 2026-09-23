package com.etka.lune.client.gui.widget;

import com.etka.lune.client.gui.LuneScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The drawing kit the menu and its pages share: rounded boxes, big titles, buttons and a handful of
 * pixel glyphs.
 *
 * <p>Text goes through a fresh collector on every call. A collector keeps the transform it was made
 * under, so one made before a scale or a slide would draw its words where the boxes used to be.</p>
 */
final class MenuPaint {

    static final int WHITE = 0xFFFFFFFF;
    /** Words on an accent-filled button: the accent's own darkest shade, never plain black. */
    static final int INK_ON_ACCENT = 0xFF2A1604;
    static final int CARD = 0x38000000;
    static final int CARD_EDGE = 0xFF2B2E37;
    static final int HOVER = 0x1CFFFFFF;

    enum Button { PRIMARY, SECONDARY, DISABLED }

    private MenuPaint() {}

    // --- boxes ---------------------------------------------------------------

    /** A filled box with its corner pixels left out, which is as round as a 1px grid gets. */
    static void roundedFill(GuiGraphicsExtractor extractor, int x, int y, int width, int height,
                            int colour) {
        extractor.fill(x + 1, y, x + width - 1, y + height, colour);
        extractor.fill(x, y + 1, x + 1, y + height - 1, colour);
        extractor.fill(x + width - 1, y + 1, x + width, y + height - 1, colour);
    }

    static void roundedOutline(GuiGraphicsExtractor extractor, int x, int y, int width, int height,
                               int colour) {
        extractor.fill(x + 1, y, x + width - 1, y + 1, colour);
        extractor.fill(x + 1, y + height - 1, x + width - 1, y + height, colour);
        extractor.fill(x, y + 1, x + 1, y + height - 1, colour);
        extractor.fill(x + width - 1, y + 1, x + width, y + height - 1, colour);
    }

    /** The quiet box a page groups things in. */
    static void card(GuiGraphicsExtractor extractor, int x, int y, int width, int height) {
        roundedFill(extractor, x, y, width, height, CARD);
        roundedOutline(extractor, x, y, width, height, CARD_EDGE);
    }

    static boolean hits(int x, int y, int width, int height, double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    /** The click every vanilla button makes, for the things on a page that act like one. */
    static void click() {
        AbstractWidget.playButtonClickSound(Minecraft.getInstance().getSoundManager());
    }

    // --- words ---------------------------------------------------------------

    static void text(GuiGraphicsExtractor extractor, String value, int x, int y, int colour) {
        extractor.textRenderer().accept(x, y, Component.literal(value).withColor(colour));
    }

    /** Twice the size, at a whole scale, so the font's pixels stay square and sharp. */
    static void bigText(GuiGraphicsExtractor extractor, String value, int x, int y, int colour) {
        var pose = extractor.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        pose.scale(2.0F, 2.0F);
        extractor.textRenderer().accept(0, 0, Component.literal(value).withColor(colour));
        pose.popMatrix();
    }

    static String clip(Font font, String value, int maxWidth) {
        if (font.width(value) <= maxWidth) {
            return value;
        }
        String fit = font.plainSubstrByWidth(value, Math.max(0, maxWidth - font.width("…")), false);
        return fit.isEmpty() ? "…" : fit + "…";
    }

    /** Greedy word wrap, capped; what does not fit is clipped into the last line, not dropped. */
    static List<String> wrap(Font font, String value, int maxWidth, int maxLines) {
        List<String> lines = new ArrayList<>(maxLines);
        if (maxLines <= 0 || value == null || value.isBlank()) {
            return lines;
        }
        String[] words = value.split(" ");
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String candidate = current.isEmpty() ? words[i] : current + " " + words[i];
            if (font.width(candidate) <= maxWidth) {
                current.setLength(0);
                current.append(candidate);
                continue;
            }
            if (lines.size() == maxLines - 1) {
                String tail = String.join(" ", List.of(words).subList(i, words.length));
                lines.add(clip(font, current.isEmpty() ? tail : current + " " + tail, maxWidth));
                return lines;
            }
            if (!current.isEmpty()) {
                lines.add(clip(font, current.toString(), maxWidth));
            }
            current.setLength(0);
            current.append(words[i]);
        }
        if (!current.isEmpty() && lines.size() < maxLines) {
            lines.add(clip(font, current.toString(), maxWidth));
        }
        return lines;
    }

    // --- buttons -------------------------------------------------------------

    private static final int BUTTON_PAD = 12;
    /** The play arrow a primary button carries after its label: a gap and the arrow itself. */
    private static final int ARROW_ROOM = 9;

    /** How wide a button is for its label, with room for the play arrow a primary one carries. */
    static int buttonWidth(Font font, String label, Button style) {
        return font.width(label) + BUTTON_PAD * 2 + (style == Button.PRIMARY ? ARROW_ROOM : 0);
    }

    static void button(GuiGraphicsExtractor extractor, Font font, int x, int y, int width, int height,
                       String label, Button style, boolean hovered) {
        int fill;
        int edge;
        int ink;
        switch (style) {
            case PRIMARY -> {
                fill = hovered ? LuneScreen.ACCENT_HOVER : LuneScreen.ACCENT;
                edge = fill;
                ink = INK_ON_ACCENT;
            }
            case SECONDARY -> {
                fill = hovered ? LuneScreen.ACCENT_SELECTION : 0x00000000;
                edge = LuneScreen.ACCENT;
                ink = hovered ? WHITE : LuneScreen.ACCENT_HOVER;
            }
            default -> {
                fill = 0x24FFFFFF;
                edge = 0x30FFFFFF;
                ink = LuneScreen.TEXT_DIM;
            }
        }
        roundedFill(extractor, x, y, width, height, fill);
        roundedOutline(extractor, x, y, width, height, edge);
        int labelWidth = font.width(label) + (style == Button.PRIMARY ? ARROW_ROOM : 0);
        int textX = x + (width - labelWidth) / 2;
        int textY = y + (height - 8) / 2;
        text(extractor, label, textX, textY, ink);
        if (style == Button.PRIMARY) {
            play(extractor, textX + font.width(label) + ARROW_ROOM - 4, textY, ink);
        }
    }

    // --- glyphs --------------------------------------------------------------

    /** A solid arrow pointing right, 4 by 7. */
    static void play(GuiGraphicsExtractor extractor, int x, int y, int colour) {
        extractor.fill(x, y, x + 1, y + 7, colour);
        extractor.fill(x + 1, y + 1, x + 2, y + 6, colour);
        extractor.fill(x + 2, y + 2, x + 3, y + 5, colour);
        extractor.fill(x + 3, y + 3, x + 4, y + 4, colour);
    }

    /** A chevron two pixels thick, 6 by 9, pointing left or right. */
    static void chevron(GuiGraphicsExtractor extractor, int x, int y, boolean right, int colour) {
        for (int row = 0; row < 9; row++) {
            int step = row < 5 ? row : 8 - row;
            int column = right ? step : 4 - step;
            extractor.fill(x + column, y + row, x + column + 2, y + row + 1, colour);
        }
    }

    /** A tick, 7 by 6, two pixels thick. */
    static void check(GuiGraphicsExtractor extractor, int x, int y, int colour) {
        int[][] pixels = {{0, 2}, {1, 3}, {2, 4}, {3, 3}, {4, 2}, {5, 1}, {6, 0}};
        for (int[] pixel : pixels) {
            extractor.fill(x + pixel[0], y + pixel[1], x + pixel[0] + 1, y + pixel[1] + 2, colour);
        }
    }

    /** A padlock, 7 by 9: a shackle over a body with the keyhole left dark. */
    static void lock(GuiGraphicsExtractor extractor, int x, int y, int colour, int hole) {
        extractor.fill(x + 2, y, x + 5, y + 1, colour);
        extractor.fill(x + 1, y + 1, x + 2, y + 4, colour);
        extractor.fill(x + 5, y + 1, x + 6, y + 4, colour);
        extractor.fill(x, y + 4, x + 7, y + 9, colour);
        extractor.fill(x + 3, y + 5, x + 4, y + 7, hole);
    }

    /** A cross for closing, 7 by 7. */
    static void cross(GuiGraphicsExtractor extractor, int x, int y, int colour) {
        for (int i = 0; i < 7; i++) {
            extractor.fill(x + i, y + i, x + i + 1, y + i + 1, colour);
            extractor.fill(x + 6 - i, y + i, x + 7 - i, y + i + 1, colour);
        }
    }

    /** A crescent moon opening to the right, 5 by 7. Lune is French for moon. */
    static void moon(GuiGraphicsExtractor extractor, int x, int y, int colour) {
        int[][] rows = {{2, 5}, {1, 3}, {0, 2}, {0, 2}, {0, 2}, {1, 3}, {2, 5}};
        for (int row = 0; row < rows.length; row++) {
            extractor.fill(x + rows[row][0], y + row, x + rows[row][1], y + row + 1, colour);
        }
    }

    /** {@code colour} with its alpha multiplied by {@code amount}, for things that fade. */
    static int fade(int colour, float amount) {
        int alpha = Math.round(((colour >>> 24) & 0xFF) * Math.clamp(amount, 0.0F, 1.0F));
        return (alpha << 24) | (colour & 0x00FFFFFF);
    }
}
