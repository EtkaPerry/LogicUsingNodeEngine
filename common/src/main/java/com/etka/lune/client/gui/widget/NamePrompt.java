package com.etka.lune.client.gui.widget;

import com.etka.lune.util.Lang;
import com.etka.lune.client.gui.LuneScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

/**
 * A one-line text prompt, for the only kind of value the palette cannot offer as a list: a name for
 * something that does not exist yet.
 *
 * <p>Written as a popup rather than an inline edit box because the parameter pane is a drawn list of
 * rows, not a container of widgets - and because the same prompt is wanted from anywhere. It is
 * routed like the other popups: while it is up, it owns the keyboard and the pointer.</p>
 */
public class NamePrompt extends AbstractWidget {

    private static final int PADDING = 8;
    private static final int TITLE_H = 16;
    private static final int FIELD_H = 20;
    private static final int BUTTON_W = 64;
    private static final int BUTTON_H = 18;
    private static final int POPUP_W = 260;
    private static final int POPUP_H = 104;

    private static final int DIM = 0xB0000000;
    private static final int FIELD_BG = 0xFF2A2A35;

    private String title = "";
    private String value = "";
    private String hint = "";
    private int maxLength = 32;
    private Consumer<String> onAccept;

    public NamePrompt() {
        super(-1000, -1000, 10, 10, Component.literal(Lang.get("lune.gui.name_prompt.type_name")));
        this.visible = false;
        this.active = false;
    }

    public boolean isOpen() {
        return visible;
    }

    public void open(String title, String hint, String initial, int maxLength,
                     Consumer<String> onAccept) {
        this.title = title == null ? "" : title;
        this.hint = hint == null ? "" : hint;
        this.value = initial == null ? "" : initial;
        this.maxLength = Math.max(1, maxLength);
        this.onAccept = onAccept;
        this.visible = true;
        this.active = true;
        setFocused(true);
    }

    public void close(boolean save) {
        Consumer<String> accept = onAccept;
        String typed = value.strip();
        visible = false;
        active = false;
        setFocused(false);
        setPosition(-1000, -1000);
        onAccept = null;
        value = "";
        if (save && accept != null) {
            accept.accept(typed);
        }
    }

    @Override
    public void setPosition(int x, int y) {
        setX(x);
        setY(y);
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                            float partialTick) {
        // Required by AbstractWidget; drawn through render() so LuneScreen can order it on top.
    }

    public void render(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        if (!visible) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int popupX = (mc.screen.width - POPUP_W) / 2;
        int popupY = (mc.screen.height - POPUP_H) / 2;

        extractor.fill(0, 0, mc.screen.width, mc.screen.height, DIM);
        LuneScreen.panel(extractor, popupX, popupY, POPUP_W, POPUP_H);

        int lineWidth = POPUP_W - PADDING * 2 - 10;
        var text = extractor.textRenderer();
        text.accept(popupX + PADDING, popupY + PADDING,
                Component.literal(clip(font, title, lineWidth)).withColor(LuneScreen.TEXT));

        int fieldY = popupY + PADDING + TITLE_H;
        extractor.fill(popupX + PADDING, fieldY, popupX + POPUP_W - PADDING, fieldY + FIELD_H, FIELD_BG);
        extractor.fill(popupX + PADDING, fieldY + FIELD_H - 1, popupX + POPUP_W - PADDING,
                fieldY + FIELD_H, LuneScreen.ACCENT);
        String shown = clip(font, value.isEmpty() ? hint : value, lineWidth);
        text.accept(popupX + PADDING + 5, fieldY + 6,
                Component.literal(shown).withColor(value.isEmpty() ? LuneScreen.TEXT_DIM : LuneScreen.TEXT));
        if ((Util.getMillis() / 500) % 2 == 0) {
            text.accept(popupX + PADDING + 5 + (value.isEmpty() ? 0 : font.width(value)), fieldY + 6,
                    Component.literal("_").withColor(LuneScreen.ACCENT));
        }

        text.accept(popupX + PADDING, fieldY + FIELD_H + 6,
                Component.literal(Lang.get("lune.gui.name_prompt.enter_save_escape_cancel")).withColor(LuneScreen.TEXT_DIM));

        drawButton(extractor, text, font, cancelX(popupX), buttonY(popupY), Lang.get("lune.gui.name_prompt.cancel"), mouseX, mouseY);
        drawButton(extractor, text, font, saveX(popupX), buttonY(popupY), Lang.get("lune.gui.waypoints.save"), mouseX, mouseY);
    }

    private void drawButton(GuiGraphicsExtractor extractor,
                            net.minecraft.client.gui.ActiveTextCollector text,
                            Font font, int x, int y, String label, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX < x + BUTTON_W && mouseY >= y && mouseY < y + BUTTON_H;
        extractor.fill(x, y, x + BUTTON_W, y + BUTTON_H, hovered ? LuneScreen.ACCENT : LuneScreen.PANEL_BG);
        extractor.fill(x, y, x + BUTTON_W, y + 1, LuneScreen.PANEL_BORDER);
        extractor.fill(x, y + BUTTON_H - 1, x + BUTTON_W, y + BUTTON_H, LuneScreen.PANEL_BORDER);
        text.accept(x + (BUTTON_W - font.width(label)) / 2, y + 5,
                Component.literal(label).withColor(hovered ? 0xFF000000 : LuneScreen.TEXT));
    }

    private String clip(Font font, String value, int maxWidth) {
        if (font.width(value) <= maxWidth) {
            return value;
        }
        String fit = font.plainSubstrByWidth(value, Math.max(0, maxWidth - font.width("…")), false);
        return fit.isEmpty() ? "…" : fit + "…";
    }

    private int buttonY(int popupY) {
        return popupY + POPUP_H - PADDING - BUTTON_H;
    }

    private int cancelX(int popupX) {
        return popupX + POPUP_W - PADDING - BUTTON_W * 2 - 6;
    }

    private int saveX(int popupX) {
        return popupX + POPUP_W - PADDING - BUTTON_W;
    }

    public void handleScreenMouseClick(double mouseX, double mouseY, int button) {
        if (!visible) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        int popupX = (mc.screen.width - POPUP_W) / 2;
        int popupY = (mc.screen.height - POPUP_H) / 2;
        if (mouseX < popupX || mouseX >= popupX + POPUP_W
                || mouseY < popupY || mouseY >= popupY + POPUP_H) {
            close(false);
            return;
        }
        if (mouseY >= buttonY(popupY) && mouseY < buttonY(popupY) + BUTTON_H) {
            if (mouseX >= cancelX(popupX) && mouseX < cancelX(popupX) + BUTTON_W) {
                close(false);
            } else if (mouseX >= saveX(popupX) && mouseX < saveX(popupX) + BUTTON_W) {
                close(true);
            }
        }
    }

    public void handleScreenCharTyped(int codePoint) {
        if (visible && value.length() < maxLength) {
            value += Character.toString(codePoint);
        }
    }

    public void handleScreenKeyPressed(int keyCode, int scanCode, int modifiers) {
        if (!visible) {
            return;
        }
        switch (keyCode) {
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (!value.isEmpty()) {
                    value = value.substring(0, value.length() - 1);
                }
            }
            case GLFW.GLFW_KEY_DELETE -> value = "";
            case GLFW.GLFW_KEY_ESCAPE -> close(false);
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> close(true);
            default -> {
            }
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return false;
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (!visible) {
            return false;
        }
        if (event.isAllowedChatCharacter()) {
            handleScreenCharTyped(event.codepoint());
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!visible) {
            return false;
        }
        handleScreenKeyPressed(event.key(), 0, event.modifiers());
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {}

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return visible;
    }
}
