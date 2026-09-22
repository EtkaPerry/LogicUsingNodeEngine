package com.etka.lune.client.gui.widget;

import com.etka.lune.client.gui.LuneScreen;
import com.etka.lune.util.Lang;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * A narrow vertical strip that sits between two panels and lets the player drag it to change their
 * relative widths. It is only a few pixels wide for easy grabbing, but draws a single subtle line
 * so it does not dominate the UI.
 *
 * <p>Dragging a 5px strip is a pointer gesture with no keyboard equivalent, so the arrows are
 * given one: focus a divider and Left and Right move it a step at a time, Shift a larger one. The
 * alternative considered was making it unfocusable, which removes the silent stops from the tab
 * order but also removes the only way to resize a column without a mouse - and the dashboard has
 * six of these, so that is six columns a keyboard could never adjust.</p>
 */
public class VerticalSplitter extends AbstractWidget {

    public static final int WIDTH = 5;
    private static final int LINE = 0xFF5A5A64;
    private static final int LINE_HOVER = 0xFF8E8E9A;
    private static final int HIT_GLOW = LuneScreen.ACCENT_GLOW;
    /** One arrow press, and one with Shift held. */
    private static final int NUDGE = 4;
    private static final int NUDGE_FAST = 16;

    private final int direction;
    private final Consumer<Integer> onDrag;

    public VerticalSplitter(int x, int y, int height, int direction, Consumer<Integer> onDrag) {
        super(x, y, WIDTH, height, Component.empty());
        this.direction = direction;
        this.onDrag = onDrag;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        boolean hovered = isMouseOver(mouseX, mouseY);
        if (hovered) {
            extractor.requestCursor(CursorTypes.RESIZE_EW);
        }

        int lineX = getX() + WIDTH / 2;
        boolean lit = hovered || isFocused();
        int lineColor = isFocused() ? LuneScreen.ACCENT : hovered ? LINE_HOVER : LINE;
        if (lit) {
            extractor.fill(getX(), getY(), getX() + WIDTH, getY() + getHeight(), HIT_GLOW);
        }
        extractor.fill(lineX, getY(), lineX + 1, getY() + getHeight(), lineColor);
    }

    /**
     * Left and right move the divider; nothing else here answers to the keyboard.
     *
     * <p>Shift is read off the client rather than out of the event's modifier bits, which would
     * mean naming a GLFW constant - and {@code org.lwjgl.glfw} is not on the compile classpath on
     * every version this one source tree builds for.</p>
     */
    @Override
    public boolean keyPressed(KeyEvent event) {
        int step = Minecraft.getInstance().hasShiftDown() ? NUDGE_FAST : NUDGE;
        if (event.key() == InputConstants.KEY_LEFT) {
            onDrag.accept(-step * direction);
            return true;
        }
        if (event.key() == InputConstants.KEY_RIGHT) {
            onDrag.accept(step * direction);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void playDownSound(net.minecraft.client.sounds.SoundManager soundManager) {
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dx, double dy) {
        onDrag.accept((int) Math.round(dx * direction));
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
    }

    /** Says what it is and how to work it; an empty narration is a stop that announces nothing. */
    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE,
                Component.literal(Lang.get("lune.gui.splitter.narration")));
    }
}
