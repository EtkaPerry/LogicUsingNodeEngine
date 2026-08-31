package com.etka.lune.client.gui.widget;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Small pixel icons used by Lune's hand-drawn controls. */
public final class GuiIcons {

    public enum Icon {
        PLAY,
        PAUSE,
        RENAME,
        VIEW,
        STOP
    }

    private GuiIcons() {}

    /** Draws one 10px icon into the supplied top-left cell. */
    public static void draw(GuiGraphicsExtractor extractor, Icon icon, int x, int y, int colour) {
        switch (icon) {
            case PLAY -> play(extractor, x, y, colour);
            case PAUSE -> pause(extractor, x, y, colour);
            case RENAME -> rename(extractor, x, y, colour);
            case VIEW -> view(extractor, x, y, colour);
            case STOP -> stop(extractor, x, y, colour);
        }
    }

    private static void play(GuiGraphicsExtractor extractor, int x, int y, int colour) {
        fill(extractor, x + 3, y + 1, x + 4, y + 9, colour);
        fill(extractor, x + 4, y + 2, x + 5, y + 8, colour);
        fill(extractor, x + 5, y + 3, x + 6, y + 7, colour);
        fill(extractor, x + 6, y + 4, x + 7, y + 6, colour);
        fill(extractor, x + 7, y + 5, x + 8, y + 6, colour);
    }

    private static void rename(GuiGraphicsExtractor extractor, int x, int y, int colour) {
        // A diagonal pen, kept intentionally simple so it stays legible at Lune's small UI scale.
        for (int i = 0; i < 7; i++) {
            fill(extractor, x + 1 + i, y + 7 - i, x + 3 + i, y + 9 - i, colour);
        }
        fill(extractor, x + 7, y + 1, x + 9, y + 3, colour);
        fill(extractor, x + 8, y + 2, x + 9, y + 4, colour);
    }

    private static void pause(GuiGraphicsExtractor extractor, int x, int y, int colour) {
        fill(extractor, x + 2, y + 2, x + 4, y + 8, colour);
        fill(extractor, x + 6, y + 2, x + 8, y + 8, colour);
    }

    private static void view(GuiGraphicsExtractor extractor, int x, int y, int colour) {
        // Pixel almond with a clear pupil; this reads better than a text glyph at small sizes.
        fill(extractor, x + 4, y + 1, x + 6, y + 2, colour);
        fill(extractor, x + 2, y + 2, x + 8, y + 3, colour);
        fill(extractor, x + 1, y + 3, x + 9, y + 4, colour);
        fill(extractor, x + 2, y + 4, x + 8, y + 5, colour);
        fill(extractor, x + 4, y + 5, x + 6, y + 6, colour);
        fill(extractor, x + 4, y + 2, x + 6, y + 5, colour);
    }

    private static void stop(GuiGraphicsExtractor extractor, int x, int y, int colour) {
        fill(extractor, x + 2, y + 2, x + 8, y + 8, colour);
    }

    private static void fill(GuiGraphicsExtractor extractor, int left, int top,
                             int right, int bottom, int colour) {
        extractor.fill(left, top, right + 1, bottom + 1, colour);
    }
}
