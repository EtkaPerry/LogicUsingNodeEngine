package com.etka.lune.client.gui.widget;

import com.etka.lune.client.gui.Accessibility;
import com.etka.lune.client.gui.LuneScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * A key drawn as a key: a small cap with rounded corners, a darker lip under its face, and the
 * key's name on top.
 *
 * <p>Shared by the shortcut button on the Tasks tab and the badge a task wears in a list, so the
 * key a player sets and the key they later see are visibly the same object.</p>
 */
public final class KeyCap {

    /** Fits inside a 13px list row with a pixel to spare above and below. */
    public static final int BADGE_HEIGHT = 11;
    /** The widest a cap grows. A longer name is cut short, and its tooltip says it in full. */
    public static final int MAX_WIDTH = 48;
    /** Room each side of the label: the outline and three pixels of face. */
    private static final int PAD = 4;
    private static final String ELLIPSIS = "…";

    private static final int EDGE = 0xFF4E4E5A;
    private static final int EDGE_HOVER = 0xFF7A7A8C;
    private static final int FACE = 0xFF26262E;
    /** A warm face while listening, so the cap waiting for a key is the one that looks lit. */
    private static final int FACE_LISTENING = 0xFF3A2814;
    private static final int LIP = 0xFF141418;

    /** At rest, under the pointer or keyboard focus, or waiting for a key to be pressed. */
    public enum Look { REST, HOVER, LISTENING }

    private KeyCap() {}

    /** The width a cap wants for this label, never more than {@link #MAX_WIDTH}. */
    public static int width(String label) {
        return Math.min(MAX_WIDTH, font().width(label) + PAD * 2);
    }

    /** The colour a label or an icon on the cap is drawn in. */
    public static int ink(Look look) {
        return switch (look) {
            case REST -> Accessibility.dim();
            case HOVER -> LuneScreen.TEXT;
            case LISTENING -> LuneScreen.ACCENT;
        };
    }

    /** A cap with a label on it, centred, and cut short if it does not fit. */
    public static void draw(GuiGraphicsExtractor extractor, int x, int y, int width, int height,
                            String label, Look look) {
        drawBlank(extractor, x, y, width, height, look);
        if (label == null || label.isEmpty()) {
            return;
        }
        Font font = font();
        String shown = fit(font, label, width - PAD * 2 + 1);
        // The font leaves a pixel of advance after the last glyph; centring on the ink rather than
        // on the advance keeps the name from sitting a pixel left of middle.
        int textX = x + (width - font.width(shown) + 1) / 2;
        // Level with a list row's own text in a badge, and with the name box's in the button.
        int textY = y + (height - lip(height)) / 2 - 3;
        extractor.textRenderer().accept(textX, textY, Component.literal(shown).withColor(ink(look)));
    }

    /** The cap with nothing on it, for a caller that draws an icon there instead. */
    public static void drawBlank(GuiGraphicsExtractor extractor, int x, int y, int width, int height,
                                 Look look) {
        int edge = switch (look) {
            case REST -> EDGE;
            case HOVER -> EDGE_HOVER;
            case LISTENING -> LuneScreen.ACCENT;
        };
        drawBlank(extractor, x, y, width, height, edge,
                look == Look.LISTENING ? FACE_LISTENING : FACE);
    }

    /** A blank cap in colours of the caller's own, for a cap lit by what it stands for. */
    public static void drawBlank(GuiGraphicsExtractor extractor, int x, int y, int width, int height,
                                 int edge, int face) {
        int right = x + width;
        int bottom = y + height;
        int lipTop = bottom - 1 - lip(height);
        // The corners are left out of the outline, which is all it takes for a cap this small to
        // read as rounded.
        extractor.fill(x + 1, y, right - 1, y + 1, edge);
        extractor.fill(x + 1, bottom - 1, right - 1, bottom, edge);
        extractor.fill(x, y + 1, x + 1, bottom - 1, edge);
        extractor.fill(right - 1, y + 1, right, bottom - 1, edge);
        extractor.fill(x + 1, y + 1, right - 1, lipTop, face);
        extractor.fill(x + 1, lipTop, right - 1, bottom - 1, LIP);
    }

    /** One pixel of lip on a badge, two on anything taller. */
    private static int lip(int height) {
        return height >= 14 ? 2 : 1;
    }

    private static String fit(Font font, String label, int room) {
        if (font.width(label) <= room) {
            return label;
        }
        String head = font.plainSubstrByWidth(label, Math.max(0, room - font.width(ELLIPSIS)), false);
        return head.isEmpty() ? ELLIPSIS : head + ELLIPSIS;
    }

    private static Font font() {
        return Minecraft.getInstance().font;
    }
}
