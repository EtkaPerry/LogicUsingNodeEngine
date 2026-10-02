package com.etka.lune.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.inventory.tooltip.BelowOrAboveWidgetTooltipPositioner;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.client.gui.screens.inventory.tooltip.MenuTooltipPositioner;
import net.minecraft.network.chat.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Widget tooltips on a page that draws at its own scale.
 *
 * <p>The game draws a widget's tooltip at the very end of the frame, after {@link ScaledScreen}
 * has undone its scale, and anchors it with the coordinates the widget was drawn with: Lune's
 * pixels, read as the game's. Wherever the page's scale is not the game's - Lune's Auto takes a
 * 1080p screen from the game's 4 down to 3 - the tooltip landed away from its button by the ratio
 * of the two, and it went unnoticed because a window small enough to match the scales shows
 * nothing wrong.</p>
 *
 * <p>So a widget on such a page is given its tooltip here rather than through
 * {@code setTooltip}. The widget still holds it, which is what a screen reader reads, but its own
 * drawing of it is switched off; the page draws it instead, once its scale is undone, anchored in
 * the game's pixels, and placed the way the game places a button's - beside the pointer and clear
 * of the button. Anything drawn by hand rather than by a widget converts its own anchor with
 * {@link UiScale#toGamePixels}.</p>
 */
public final class ScaledTooltips {

    /**
     * The game waits this long before drawing a tooltip a widget holds, which is to say it never
     * does. The tooltip stays on the widget because narration reads it from there regardless.
     */
    private static final Duration NEVER = Duration.ofDays(365);

    /** Each widget's tooltip, forgotten with the widget. */
    private static final Map<AbstractWidget, Component> TIPS = new WeakHashMap<>();

    private ScaledTooltips() {}

    /**
     * Gives a widget its tooltip, or takes it away with null. Returns the widget, so it can wrap a
     * builder's {@code build()}.
     */
    public static <T extends AbstractWidget> T set(T widget, Component text) {
        if (text == null) {
            TIPS.remove(widget);
            widget.setTooltip(null);
            return widget;
        }
        TIPS.put(widget, text);
        widget.setTooltip(Tooltip.create(text));
        widget.setTooltipDelay(NEVER);
        return widget;
    }

    /**
     * Draws the tooltip of whichever widget on the page wants one: under the pointer, or holding
     * the keyboard's focus, the two cases the game shows one for. Called by the page once its
     * scale is undone, with the pointer in the game's pixels.
     */
    static void extract(GuiGraphicsExtractor extractor, List<? extends GuiEventListener> children,
                        float menuPixelSize, int mouseX, int mouseY) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean keyboard = minecraft.getLastInputType().isKeyboard();
        for (GuiEventListener child : children) {
            if (!(child instanceof AbstractWidget widget) || !widget.visible) {
                continue;
            }
            Component text = TIPS.get(widget);
            if (text == null) {
                continue;
            }
            boolean hovered = widget.isHovered();
            boolean focused = !hovered && keyboard && widget.isFocused();
            if (!hovered && !focused) {
                continue;
            }
            ScreenRectangle area = toGame(widget.getRectangle(), menuPixelSize);
            // The game's own choice between the two, made against the button where it really is.
            ClientTooltipPositioner positioner = focused
                    ? new BelowOrAboveWidgetTooltipPositioner(area)
                    : new MenuTooltipPositioner(area);
            extractor.setTooltipForNextFrame(minecraft.font, Tooltip.splitTooltip(minecraft, text),
                    positioner, mouseX, mouseY, focused);
            return;
        }
    }

    /** A rectangle in the page's pixels, in the game's. */
    static ScreenRectangle toGame(ScreenRectangle menu, float menuPixelSize) {
        int left = Math.round(menu.left() * menuPixelSize);
        int top = Math.round(menu.top() * menuPixelSize);
        int right = Math.round(menu.right() * menuPixelSize);
        int bottom = Math.round(menu.bottom() * menuPixelSize);
        return new ScreenRectangle(left, top, right - left, bottom - top);
    }
}
