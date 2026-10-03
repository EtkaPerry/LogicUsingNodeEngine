package com.etka.lune.client.gui.widget;

import com.etka.lune.client.gui.LuneScreen;
import com.etka.lune.client.gui.UiScale;
import com.etka.lune.task.BesideOptions;
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

/**
 * The switch that makes the open task run beside the player, drawn as a cap beside its key.
 *
 * <p>Next to the key on purpose: both say what the task is to the player - how it is started,
 * and whose hands are on the controls while it runs - and both are read before Run is pressed. Lit
 * in the same blue that marks such a task in every list, so the switch and the mark are visibly the
 * same thing. Pressing it never flips it: it opens {@link BesidePrompt}, where the player turns it
 * on or off and chooses how it shares the controls, and where Cancel leaves it as it was. Either
 * mouse button, Enter or Space opens it. The button only shows and reports; what pressing it changes
 * is the Tasks tab's.</p>
 */
public class BesideButton extends AbstractWidget {

    public static final int SIZE = 16;
    /** The blue's own dark side, for the face of a cap that is on. */
    private static final int FACE_ON = 0xFF15303C;

    private final Runnable onPress;
    private boolean on;
    private BesideOptions options;

    public BesideButton(Runnable onPress) {
        super(0, 0, SIZE, ShortcutButton.HEIGHT, Component.empty());
        this.onPress = onPress;
    }

    /** Shows whether the open task runs beside the player, and how it shares the controls. */
    public void show(boolean on, BesideOptions options) {
        this.on = on;
        this.options = options;
    }

    /** What the tooltip and the narration say: the state, and with it on, the choices made. */
    private String describe() {
        return on ? Lang.get("lune.beside.tip_on") + " " + BesidePrompt.summary(options)
                : Lang.get("lune.beside.tip_off");
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                            float partialTick) {
        // Beside the pointer in the game's pixels, as the key's own tooltip is.
        if (isHovered()) {
            Minecraft minecraft = Minecraft.getInstance();
            extractor.setTooltipForNextFrame(minecraft.font, Tooltip.splitTooltip(minecraft,
                            Component.literal(describe())),
                    UiScale.toGamePixels(mouseX), UiScale.toGamePixels(mouseY));
        }
        int x = getX();
        int y = getY();
        int ink;
        if (on) {
            KeyCap.drawBlank(extractor, x, y, getWidth(), getHeight(), LuneScreen.BESIDE, FACE_ON);
            ink = LuneScreen.BESIDE;
        } else {
            KeyCap.Look look = isHovered() || isFocused() ? KeyCap.Look.HOVER : KeyCap.Look.REST;
            KeyCap.drawBlank(extractor, x, y, getWidth(), getHeight(), look);
            ink = KeyCap.ink(look);
        }
        GuiIcons.draw(extractor, GuiIcons.Icon.BESIDE, x + (getWidth() - 10) / 2, y + 2, ink);
    }

    /** The right button too: a right click was how the choices opened before the left one did. */
    @Override
    protected boolean isValidClickButton(MouseButtonInfo buttonInfo) {
        return buttonInfo.button() == InputConstants.MOUSE_BUTTON_LEFT
                || buttonInfo.button() == InputConstants.MOUSE_BUTTON_RIGHT;
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        onPress.run();
    }

    /** Enter or Space on the focused switch opens it too, for a player without a mouse. */
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (active && visible && (event.isSelection() || event.isConfirmation())) {
            onPress.run();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, Component.literal(describe()));
    }
}
