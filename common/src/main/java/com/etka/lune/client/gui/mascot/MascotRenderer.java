package com.etka.lune.client.gui.mascot;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** Shared mascot composition for the dashboard and the in-world status card. */
public final class MascotRenderer {

    private static final Identifier BACK = Identifier.parse("lune:textures/gui/mascot/back.png");
    private static final Identifier SOUL = Identifier.parse("lune:textures/gui/mascot/soul.png");
    private static final Identifier FRONT = Identifier.parse("lune:textures/gui/mascot/front.png");

    private static final int FRAME_SIZE = 96;
    private static final int ATLAS_WIDTH = FRAME_SIZE * SoulAnimation.FRAMES;
    private static final int ATLAS_HEIGHT = FRAME_SIZE * SoulAnimation.ROWS;
    /** Rows of the 96 px cell that the head occupies; mood wipes run between them. */
    private static final int ORB_TOP = 17;
    private static final int ORB_BOTTOM = 84;
    /** The head spans 68 of the 96 px cell; the rest is clear room for her to drift in. */
    private static final int HEAD_SPAN = 68;
    private static final int SOUL_COLOUR = 0xFFF4C95D;
    private static final int HEAD_COLOUR = 0xFF252033;
    private static final int DANGER_FLICKER = 0xFFFFD8C8;
    private static final int EYE_DARK = 0xFF3D2B12;

    private final SoulAnimation soul = new SoulAnimation();

    /**
     * Draws her with the head itself measuring {@code head} px, from {@code x, y}.
     *
     * <p>A caller that sizes her by the cell gets a head a third smaller than it asked for, because
     * the cell holds the drift room as well. This is the call for a tight box, where the size asked
     * for is the size she should look. The drift room still bleeds outside the box, but it is clear
     * pixels, so only a caller with less than {@code (head * 96 / 68 - head) / 2} px to spare on any
     * side need care.</p>
     */
    public void drawHead(GuiGraphicsExtractor extractor, MascotAdvisor.Mood mood,
                         int x, int y, int head, long now) {
        int cell = Math.round(head * (float) FRAME_SIZE / HEAD_SPAN);
        int bleed = (cell - head) / 2;
        draw(extractor, mood, x - bleed, y - bleed, cell, now);
    }

    public void draw(GuiGraphicsExtractor extractor, MascotAdvisor.Mood mood,
                            int x, int y, int size, long now) {
        extractor.blit(RenderPipelines.GUI_TEXTURED, BACK, x, y,
                0, 0, size, size, FRAME_SIZE, FRAME_SIZE, FRAME_SIZE, FRAME_SIZE);

        // The soul is the whole expression. Each mood is a row of the atlas, and a change of mood
        // wipes one form into the next inside the head, so nothing is ever drawn around Lune.
        int orbTop = y + size * ORB_TOP / FRAME_SIZE;
        int orbBottom = y + size * ORB_BOTTOM / FRAME_SIZE;
        int tint = mood == MascotAdvisor.Mood.DANGER && (now / 90L) % 2L == 0L
                ? DANGER_FLICKER : -1;
        for (SoulAnimation.Layer layer : soul.layers(mood, now)) {
            boolean clipped = layer.clipped();
            if (clipped) {
                int from = orbTop + Math.round((orbBottom - orbTop) * layer.from());
                int to = orbTop + Math.round((orbBottom - orbTop) * layer.to());
                extractor.enableScissor(x, from, x + size, to);
            }
            extractor.blit(RenderPipelines.GUI_TEXTURED, SOUL, x, y,
                    layer.frame() * FRAME_SIZE, layer.row() * FRAME_SIZE, size, size,
                    FRAME_SIZE, FRAME_SIZE, ATLAS_WIDTH, ATLAS_HEIGHT, tint);
            if (clipped) {
                extractor.disableScissor();
            }
        }

        extractor.blit(RenderPipelines.GUI_TEXTURED, FRONT, x, y,
                0, 0, size, size, FRAME_SIZE, FRAME_SIZE, FRAME_SIZE, FRAME_SIZE);

        if (SoulAnimation.eyeShut(mood)) {
            drawShutEye(extractor, x, y, size, mood != MascotAdvisor.Mood.DEAD);
        }
    }

    /**
     * The lid, painted over the front layer once her eye is closed.
     *
     * <p>A ring of head colour holds the lid off the soul: asleep the pool has risen over the eye,
     * and a soul-coloured lid on a soul-coloured pool is no eye at all. When she is dead the lid is
     * unlit as well, so nothing of her is left in the head.</p>
     */
    private static void drawShutEye(GuiGraphicsExtractor extractor, int x, int y, int size,
                                    boolean lit) {
        int centreX = x + size / 2;
        int centreY = y + size / 2;
        int radiusX = Math.max(3, Math.round(size * 13f / FRAME_SIZE));
        int radiusY = Math.max(4, Math.round(size * 17f / FRAME_SIZE));
        int ring = Math.max(1, Math.round(size * 2.5f / FRAME_SIZE));
        fillEllipse(extractor, centreX, centreY, radiusX + ring, radiusY + ring, HEAD_COLOUR);
        fillEllipse(extractor, centreX, centreY, radiusX, radiusY, lit ? SOUL_COLOUR : HEAD_COLOUR);
        int halfWidth = Math.max(4, size / 9);
        int thickness = Math.max(2, size / 36);
        int third = Math.max(1, halfWidth * 2 / 3);
        // A gentle arch: the middle of the lid sits one pixel above its corners.
        extractor.fill(centreX - halfWidth, centreY + 1, centreX - halfWidth + third,
                centreY + 1 + thickness, EYE_DARK);
        extractor.fill(centreX - halfWidth + third, centreY, centreX + halfWidth - third,
                centreY + thickness, EYE_DARK);
        extractor.fill(centreX + halfWidth - third, centreY + 1, centreX + halfWidth,
                centreY + 1 + thickness, EYE_DARK);
    }

    private static void fillEllipse(GuiGraphicsExtractor extractor, int centreX, int centreY,
                                    int radiusX, int radiusY, int colour) {
        for (int row = -radiusY; row <= radiusY; row++) {
            double reach = radiusX * Math.sqrt(Math.max(0.0,
                    1.0 - (double) (row * row) / (radiusY * radiusY)));
            int half = (int) Math.round(reach);
            extractor.fill(centreX - half, centreY + row, centreX + half + 1, centreY + row + 1,
                    colour);
        }
    }

}
