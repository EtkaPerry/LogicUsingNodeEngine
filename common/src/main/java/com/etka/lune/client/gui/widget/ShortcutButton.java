package com.etka.lune.client.gui.widget;

import com.etka.lune.client.TaskShortcutKeys;
import com.etka.lune.client.gui.UiScale;
import com.etka.lune.util.Lang;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

/**
 * The key a task starts with, drawn as a key and set by pressing one.
 *
 * <p>Click it and it listens: the next key pressed becomes the task's. A right click takes the key
 * away. The button only shows and reports - what a pressed key means is the Tasks tab's to decide,
 * because only the tab knows which task is open and what else already answers to that key.</p>
 *
 * <p>With no key it is a small blank cap with a plus on it, so a player who never wants a
 * shortcut gives up a corner of the name box and nothing else.</p>
 */
public class ShortcutButton extends AbstractWidget {

    public static final int HEIGHT = 16;
    /** Square, while there is no key to name. */
    private static final int BARE_WIDTH = 16;
    /** How long the waiting caret stays on, and then off. */
    private static final long BLINK_MS = 500L;

    private final Runnable onPress;
    private final Runnable onClear;
    /** The game's name for the key, or null for none. */
    private String key;
    private boolean listening;

    public ShortcutButton(Runnable onPress, Runnable onClear) {
        super(0, 0, BARE_WIDTH, HEIGHT, Component.empty());
        this.onPress = onPress;
        this.onClear = onClear;
    }

    /** Shows this key, by the game's name for it, or none. */
    public void show(String key) {
        this.key = key;
    }

    public boolean isListening() {
        return listening;
    }

    public void setListening(boolean listening) {
        this.listening = listening;
    }

    /** Square with no key, or as wide as the key's name needs. */
    public int preferredWidth() {
        return key == null ? BARE_WIDTH
                : Math.max(BARE_WIDTH, KeyCap.width(TaskShortcutKeys.shortLabel(key)));
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                            float partialTick) {
        // Drawn beside the pointer in the game's pixels, as every tooltip on the panel is: the
        // widget's own would be placed in Lune's pixels after Lune's scale is undone. None while
        // listening - the line under the canvas already says what to do, and a tooltip there would
        // cover the name of the very task the key is for.
        if (isHovered() && !listening) {
            Minecraft minecraft = Minecraft.getInstance();
            extractor.setTooltipForNextFrame(minecraft.font, Tooltip.splitTooltip(minecraft,
                            Component.literal(key == null ? Lang.get("lune.shortcut.tip_none")
                                    : Lang.get("lune.shortcut.tip_set", TaskShortcutKeys.label(key)))),
                    UiScale.toGamePixels(mouseX), UiScale.toGamePixels(mouseY));
        }
        KeyCap.Look look = listening ? KeyCap.Look.LISTENING
                : isHovered() || isFocused() ? KeyCap.Look.HOVER : KeyCap.Look.REST;
        int x = getX();
        int y = getY();
        if (listening) {
            // A caret, the way a text box waits for typing: the same promise, made to one key.
            boolean on = (Util.getMillis() / BLINK_MS) % 2 == 0;
            KeyCap.draw(extractor, x, y, getWidth(), getHeight(), on ? "_" : "", look);
        } else if (key == null) {
            KeyCap.drawBlank(extractor, x, y, getWidth(), getHeight(), look);
            // A small plus on the face: there is no key yet, and one can be added here.
            int cx = x + getWidth() / 2 - 1;
            int cy = y + 6;
            int ink = KeyCap.ink(look);
            extractor.fill(cx - 2, cy, cx + 3, cy + 1, ink);
            extractor.fill(cx, cy - 2, cx + 1, cy + 3, ink);
        } else {
            KeyCap.draw(extractor, x, y, getWidth(), getHeight(), TaskShortcutKeys.shortLabel(key),
                    look);
        }
    }

    /** The right button too, which takes the key away. */
    @Override
    protected boolean isValidClickButton(MouseButtonInfo buttonInfo) {
        return buttonInfo.button() == InputConstants.MOUSE_BUTTON_LEFT
                || buttonInfo.button() == InputConstants.MOUSE_BUTTON_RIGHT;
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            onClear.run();
        } else {
            onPress.run();
        }
    }

    /** Enter or Space on the focused button starts listening, for a player without a mouse. */
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (active && visible && !listening && (event.isSelection() || event.isConfirmation())) {
            onPress.run();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, Component.literal(Lang.get("lune.shortcut.narration",
                key == null ? Lang.get("key.keyboard.unknown") : TaskShortcutKeys.label(key))));
    }
}
