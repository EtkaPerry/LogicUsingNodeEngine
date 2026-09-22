package com.etka.lune.client.gui;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * A screen that draws at its own GUI scale rather than the game's.
 *
 * <p>Lune's pages are laid out in fixed pixels. The player's GUI scale decides how many of those
 * pixels exist, and the generous default leaves far fewer than the layouts need - so a page that
 * simply inherited it would collide, ellipsise, and drop its bottom row off the screen. Each page
 * therefore picks a whole scale of its own (see {@link UiScale}) and everything below this class
 * works in <em>that</em> space, never having to think about the difference.</p>
 *
 * <p>Two places the difference leaks out, and both are handled here so no subclass repeats them:
 * drawing is wrapped in a scale transform, and incoming pointer coordinates are converted on the
 * way in. Subclasses override the {@code scaled} hooks and receive coordinates already in their
 * own pixels; the raw vanilla entry points are final so there is no way to accidentally take the
 * unconverted ones.</p>
 *
 * <p>Whole scales only. Drawing at 3 inside a game running at 4 means every Lune pixel is exactly
 * 3 real pixels, so the 1px panel borders stay crisp; a fractional scale would smear them.</p>
 */
public abstract class ScaledScreen extends Screen {

    /** Scale the page draws at, and the two conversions derived from it. */
    private int menuScale = 1;
    /** Size of one Lune pixel in game GUI pixels; the transform every draw call goes through. */
    private float menuPixelSize = 1.0F;
    /** Inverse of the above, for turning the game's pointer coordinates into Lune's. */
    private double menuPixelsPerGamePixel = 1.0;

    protected ScaledScreen(Component title) {
        super(title);
    }

    /** The whole-number GUI scale this page wants, given the window it is being drawn into. */
    protected abstract int scaleFor(Window window);

    /** The scale in force right now, for a subclass that needs to notice it changing. */
    protected final int menuScale() {
        return menuScale;
    }

    /** True when the setting has moved away from what this page is currently drawn at. */
    protected final boolean scaleIsStale() {
        return menuScale != Math.max(1, scaleFor(Minecraft.getInstance().getWindow()));
    }

    /**
     * Resizes the screen into Lune pixels.
     *
     * <p>Called from both entry points vanilla uses - {@code init()} the first time the page opens,
     * {@code repositionElements()} on every resize and GUI scale change - because
     * {@code Screen.init(int, int)} is final and sets the game's own dimensions just before
     * calling them.</p>
     */
    protected final void applyMenuScale() {
        Window window = Minecraft.getInstance().getWindow();
        int gameScale = UiScale.gameScale(window);
        menuScale = Math.max(1, scaleFor(window));
        menuPixelSize = menuScale / (float) gameScale;
        menuPixelsPerGamePixel = gameScale / (double) menuScale;
        // Rounded down: a Lune pixel that only partly exists would be drawn off the screen edge.
        this.width = Math.max(1, (int) (window.getWidth() / (double) menuScale));
        this.height = Math.max(1, (int) (window.getHeight() / (double) menuScale));
    }

    protected final double toMenu(double gameCoordinate) {
        return gameCoordinate * menuPixelsPerGamePixel;
    }

    protected final MouseButtonEvent toMenu(MouseButtonEvent event) {
        return new MouseButtonEvent(toMenu(event.x()), toMenu(event.y()), event.buttonInfo());
    }

    // --- drawing -------------------------------------------------------------

    @Override
    public final void extractBackground(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                        float partialTick) {
        // Deliberately outside the transform: vanilla's backdrop covers whatever it is given, and
        // this call also flushes the subtitle overlay, which belongs at the game's own scale.
        super.extractBackground(extractor, mouseX, mouseY, partialTick);
        var pose = extractor.pose();
        pose.pushMatrix();
        pose.scale(menuPixelSize, menuPixelSize);
        scaledBackground(extractor);
        pose.popMatrix();
    }

    @Override
    public final void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                         float partialTick) {
        var pose = extractor.pose();
        pose.pushMatrix();
        pose.scale(menuPixelSize, menuPixelSize);
        scaledRender(extractor, (int) toMenu(mouseX), (int) toMenu(mouseY), partialTick);
        pose.popMatrix();
    }

    /** Panels and dividers, drawn behind the widgets, in this page's own pixels. */
    protected void scaledBackground(GuiGraphicsExtractor extractor) {}

    /**
     * Everything else, in this page's own pixels.
     *
     * <p>The default draws the page's registered widgets; a subclass that needs to draw around
     * them calls {@code super} at the point in its own order where the widgets belong.</p>
     */
    protected void scaledRender(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                float partialTick) {
        super.extractRenderState(extractor, mouseX, mouseY, partialTick);
    }

    // --- pointer -------------------------------------------------------------

    @Override
    public final boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return scaledMouseClicked(toMenu(event), doubleClick);
    }

    @Override
    public final boolean mouseReleased(MouseButtonEvent event) {
        return scaledMouseReleased(toMenu(event));
    }

    @Override
    public final boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        return scaledMouseDragged(toMenu(event), toMenu(dragX), toMenu(dragY));
    }

    @Override
    public final void mouseMoved(double mouseX, double mouseY) {
        scaledMouseMoved(toMenu(mouseX), toMenu(mouseY));
    }

    @Override
    public final boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        return scaledMouseScrolled(toMenu(mouseX), toMenu(mouseY), deltaX, deltaY);
    }

    protected boolean scaledMouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return super.mouseClicked(event, doubleClick);
    }

    protected boolean scaledMouseReleased(MouseButtonEvent event) {
        return super.mouseReleased(event);
    }

    protected boolean scaledMouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        return super.mouseDragged(event, dragX, dragY);
    }

    protected void scaledMouseMoved(double mouseX, double mouseY) {
        super.mouseMoved(mouseX, mouseY);
    }

    protected boolean scaledMouseScrolled(double mouseX, double mouseY, double deltaX,
                                          double deltaY) {
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }
}
