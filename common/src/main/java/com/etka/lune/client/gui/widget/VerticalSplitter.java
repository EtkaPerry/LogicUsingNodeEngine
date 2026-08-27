package com.etka.lune.client.gui.widget;

import com.etka.lune.client.gui.LuneScreen;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * A narrow vertical strip that sits between two panels and lets the player drag it to change their
 * relative widths. It is only a few pixels wide for easy grabbing, but draws a single subtle line
 * so it does not dominate the UI.
 */
public class VerticalSplitter extends AbstractWidget {

    public static final int WIDTH = 5;
    private static final int LINE = 0xFF5A5A64;
    private static final int LINE_HOVER = 0xFF8E8E9A;
    private static final int HIT_GLOW = 0x184C9EFF;

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
        int lineColor = hovered ? LINE_HOVER : LINE;
        if (hovered) {
            extractor.fill(getX(), getY(), getX() + WIDTH, getY() + getHeight(), HIT_GLOW);
        }
        extractor.fill(lineX, getY(), lineX + 1, getY() + getHeight(), lineColor);
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

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
    }
}
