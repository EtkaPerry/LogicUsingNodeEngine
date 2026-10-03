package com.etka.lune.client.gui.widget;

import com.etka.lune.client.gui.LuneScreen;
import com.etka.lune.compat.Screens;
import com.etka.lune.task.BesideOptions;
import com.etka.lune.util.Lang;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Whether the open task runs beside the player, and how it shares the controls when it does: who
 * comes first, and whether Lune may take the mouse, the keyboard or both ({@link BesideOptions}).
 *
 * <p>The two-figure switch opens this rather than flipping on its own. The user asked for exactly
 * that (2026-10-02): a click on the switch, on or off, brings up the choice, and Cancel changes
 * nothing - the switch included. So everything on screen is a draft until a button says otherwise:
 * <b>Run beside you</b> on a task that runs in the player's place turns it on with these choices,
 * <b>Save</b> on one that already runs beside them keeps it on with these, and <b>Run in your
 * place</b> turns it off. Cancel, Escape and a click outside leave the task exactly as it was.</p>
 *
 * <p>The mouse and the keyboard are two toggles rather than three choices, and the last one on
 * cannot be turned off: a run that may take nothing does nothing. Under each row a line says what
 * the choice means in play - including what Lune will not take on, because a card that needs the
 * other device waits rather than half-doing its job.</p>
 *
 * <p>Drawn and routed by {@code LuneScreen} like {@link SharePrompt}: while it is up it owns the
 * pointer and the keyboard.</p>
 */
public class BesidePrompt extends AbstractWidget {

    /** What a button commits: whether the task runs beside the player, and how. */
    public record Choice(boolean beside, BesideOptions options) {}

    private enum Action { YOU_FIRST, LUNE_FIRST, MOUSE, KEYBOARD, TURN_OFF, CANCEL, SAVE }

    private record Hit(Action action, int x, int y, int width, String label) {}

    private static final int PADDING = 10;
    private static final int POPUP_W = 300;
    private static final int LINE_H = 10;
    private static final int TITLE_GAP = 6;
    private static final int LABEL_GAP = 4;
    private static final int BUTTON_H = 18;
    private static final int BUTTON_GAP = 6;
    private static final int BUTTON_PAD = 14;
    private static final int BUTTON_MIN_W = 48;
    /** The tick box drawn on a toggle, and the room it takes beside the word. */
    private static final int BOX = 7;
    private static final int BOX_ROOM = BOX + 5;
    private static final int SECTION_GAP = 9;
    private static final int DIM = 0xB0000000;
    private static final int REFUSED = 0xFFFF7777;

    private String taskName = "";
    /** Whether the task ran beside the player when this opened: which buttons it offers. */
    private boolean wasOn;
    private BesideOptions draft = new BesideOptions();
    /** Set when the last toggle asked to turn off the only device left; cleared by the next click. */
    private boolean refused;
    private Consumer<Choice> onChoice = choice -> {};

    public BesidePrompt() {
        super(-1000, -1000, 10, 10, Component.literal(Lang.get("lune.beside.state")));
        this.visible = false;
        this.active = false;
    }

    public boolean isOpen() {
        return visible;
    }

    /**
     * Opens on one task as it stands; {@code onChoice} hears what a button commits, and nothing is
     * heard on a cancel.
     */
    public void open(String taskName, boolean beside, BesideOptions current, Consumer<Choice> onChoice) {
        this.taskName = taskName == null ? "" : taskName;
        this.wasOn = beside;
        this.draft = current == null ? new BesideOptions() : current.copy();
        this.onChoice = onChoice == null ? choice -> {} : onChoice;
        this.refused = false;
        this.visible = true;
        this.active = true;
        setFocused(true);
    }

    public void close() {
        visible = false;
        active = false;
        setFocused(false);
        setPosition(-1000, -1000);
        onChoice = choice -> {};
    }

    @Override
    public void setPosition(int x, int y) {
        setX(x);
        setY(y);
    }

    // --- what the buttons do -------------------------------------------------------------------

    private void run(Action action) {
        refused = false;
        switch (action) {
            case YOU_FIRST -> draft.playerFirst = true;
            case LUNE_FIRST -> draft.playerFirst = false;
            case MOUSE -> {
                if (draft.mouse && !draft.keyboard) {
                    refused = true;
                } else {
                    draft.mouse = !draft.mouse;
                }
            }
            case KEYBOARD -> {
                if (draft.keyboard && !draft.mouse) {
                    refused = true;
                } else {
                    draft.keyboard = !draft.keyboard;
                }
            }
            // Both commit what is on screen; the switch is the only thing between them.
            case SAVE -> commit(true);
            case TURN_OFF -> commit(false);
            case CANCEL -> close();
        }
    }

    private void commit(boolean beside) {
        Consumer<Choice> choice = onChoice;
        BesideOptions chosen = draft.copy();
        close();
        choice.accept(new Choice(beside, chosen));
    }

    private boolean chosen(Action action) {
        return switch (action) {
            case YOU_FIRST -> draft.playerFirst;
            case LUNE_FIRST -> !draft.playerFirst;
            case MOUSE -> draft.mouse;
            case KEYBOARD -> draft.keyboard;
            case TURN_OFF, CANCEL, SAVE -> false;
        };
    }

    private static boolean toggle(Action action) {
        return action == Action.MOUSE || action == Action.KEYBOARD;
    }

    /** The buttons along the foot: turning it off only where it is on, then Cancel and the main one. */
    private List<Action> closing() {
        return wasOn ? List.of(Action.TURN_OFF, Action.CANCEL, Action.SAVE)
                : List.of(Action.CANCEL, Action.SAVE);
    }

    // --- what it says ----------------------------------------------------------------------------

    private String label(Action action) {
        return Lang.get(switch (action) {
            case YOU_FIRST -> "lune.beside.first.you";
            case LUNE_FIRST -> "lune.gui.lune.title";
            case MOUSE -> "lune.beside.takes.mouse";
            case KEYBOARD -> "lune.beside.takes.keyboard";
            case TURN_OFF -> "lune.beside.prompt.turn_off";
            case CANCEL -> "lune.gui.name_prompt.cancel";
            // On a task that runs in the player's place, the main button is the one that turns
            // it on - so the switch never moves on a click that only meant to look.
            case SAVE -> wasOn ? "lune.gui.waypoints.save" : "lune.beside.prompt.turn_on";
        });
    }

    /** Under the first row: what the choice made means in play. */
    private String firstAbout() {
        return Lang.get(draft.playerFirst ? "lune.beside.first.you_about" : "lune.beside.first.lune_about");
    }

    /** Under the second row: what she may take, and what she will therefore leave alone. */
    private String takesAbout() {
        if (refused) {
            return Lang.get("lune.beside.takes.need_one");
        }
        return Lang.get(draft.mouse && draft.keyboard ? "lune.beside.takes.both_about"
                : draft.mouse ? "lune.beside.takes.mouse_about" : "lune.beside.takes.keyboard_about");
    }

    /**
     * Who comes first and what she may take, as two sentences, for the switch's tooltip, the task
     * lists' mark and the line the Tasks tab says after a change.
     */
    public static String summary(BesideOptions options) {
        BesideOptions shown = options == null ? new BesideOptions() : options;
        return Lang.get(shown.playerFirst ? "lune.beside.summary.you" : "lune.beside.summary.lune")
                + " " + Lang.get(shown.mouse && shown.keyboard ? "lune.beside.summary.both"
                : shown.mouse ? "lune.beside.summary.mouse" : "lune.beside.summary.keyboard");
    }

    // --- drawing --------------------------------------------------------------------------------

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
        Layout layout = layout(font);
        extractor.fill(0, 0, Screens.current(mc).width, Screens.current(mc).height, DIM);
        LuneScreen.panel(extractor, layout.x, layout.y, layout.width, layout.height);

        var text = extractor.textRenderer();
        int inner = layout.width - PADDING * 2;
        int left = layout.x + PADDING;
        text.accept(left, layout.y + PADDING, Component.literal(clip(font,
                Lang.get("lune.beside.prompt.title", taskName), inner)).withColor(LuneScreen.TEXT));
        int y = layout.y + PADDING + LINE_H + TITLE_GAP;
        for (String line : layout.about) {
            text.accept(left, y, Component.literal(line).withColor(LuneScreen.TEXT_DIM));
            y += LINE_H;
        }
        text.accept(left, layout.firstLabelY, Component.literal(clip(font,
                Lang.get("lune.beside.prompt.first"), inner)).withColor(LuneScreen.TEXT));
        drawLines(text, left, layout.firstRowY + BUTTON_H + 5, layout.firstAbout, LuneScreen.TEXT);
        text.accept(left, layout.takesLabelY, Component.literal(clip(font,
                Lang.get("lune.beside.prompt.takes"), inner)).withColor(LuneScreen.TEXT));
        drawLines(text, left, layout.takesRowY + BUTTON_H + 5, layout.takesAbout,
                refused ? REFUSED : LuneScreen.TEXT);

        for (Hit hit : layout.buttons) {
            boolean on = chosen(hit.action());
            boolean primary = hit.action() == Action.SAVE;
            boolean hovered = mouseX >= hit.x() && mouseX < hit.x() + hit.width()
                    && mouseY >= hit.y() && mouseY < hit.y() + BUTTON_H;
            int fill = hovered ? LuneScreen.ACCENT_HOVER
                    : primary ? LuneScreen.ACCENT : on ? LuneScreen.BESIDE : LuneScreen.PANEL_BG;
            boolean dark = hovered || primary || on;
            extractor.fill(hit.x(), hit.y(), hit.x() + hit.width(), hit.y() + BUTTON_H, fill);
            extractor.fill(hit.x(), hit.y(), hit.x() + hit.width(), hit.y() + 1, LuneScreen.PANEL_BORDER);
            extractor.fill(hit.x(), hit.y() + BUTTON_H - 1, hit.x() + hit.width(), hit.y() + BUTTON_H,
                    LuneScreen.PANEL_BORDER);
            int ink = dark ? 0xFF000000 : LuneScreen.TEXT;
            int labelX = hit.x() + (hit.width() - font.width(hit.label())) / 2;
            if (toggle(hit.action())) {
                // A tick box, so the two read as switches that can both be on rather than a choice
                // of one, like the row above.
                int boxX = hit.x() + (hit.width() - BOX_ROOM - font.width(hit.label())) / 2;
                int boxY = hit.y() + (BUTTON_H - BOX) / 2;
                extractor.fill(boxX, boxY, boxX + BOX, boxY + BOX, ink);
                extractor.fill(boxX + 1, boxY + 1, boxX + BOX - 1, boxY + BOX - 1, fill);
                if (on) {
                    extractor.fill(boxX + 2, boxY + 2, boxX + BOX - 2, boxY + BOX - 2, ink);
                }
                labelX = boxX + BOX_ROOM;
            }
            text.accept(labelX, hit.y() + 5, Component.literal(hit.label()).withColor(ink));
        }
    }

    private static void drawLines(net.minecraft.client.gui.ActiveTextCollector text, int x, int y,
                                  List<String> lines, int colour) {
        for (int i = 0; i < lines.size(); i++) {
            text.accept(x, y + i * LINE_H, Component.literal(lines.get(i)).withColor(colour));
        }
    }

    private record Layout(int x, int y, int width, int height, List<String> about,
                          int firstLabelY, int firstRowY, List<String> firstAbout,
                          int takesLabelY, int takesRowY, List<String> takesAbout,
                          List<Hit> buttons) {}

    /**
     * Where everything goes this frame. Measured from the words rather than fixed, because every
     * label is a different length in every language: the popup is as wide as its widest row of
     * buttons needs, and never narrower than {@link #POPUP_W}.
     */
    private Layout layout(Font font) {
        Minecraft mc = Minecraft.getInstance();
        List<Action> first = List.of(Action.YOU_FIRST, Action.LUNE_FIRST);
        List<Action> takes = List.of(Action.MOUSE, Action.KEYBOARD);
        List<Action> closing = closing();
        // Turning it off stands at the left, apart from the two that close at the right.
        int closingWidth = rowWidth(font, closing) + (wasOn ? BUTTON_GAP * 3 : 0);
        int needed = Math.max(rowWidth(font, first), Math.max(rowWidth(font, takes), closingWidth))
                + PADDING * 2;
        int width = Math.min(Math.max(POPUP_W, needed), Screens.current(mc).width - 8);
        int inner = width - PADDING * 2;

        List<String> about = wrap(font, Lang.get("lune.beside.prompt.about"), inner);
        List<String> firstAbout = wrap(font, firstAbout(), inner);
        List<String> takesAbout = wrap(font, takesAbout(), inner);
        // The second row's lines are measured for every case it can show, so the popup does not
        // jump in height as the toggles are clicked.
        int takesLines = Math.max(takesAbout.size(), Math.max(
                wrap(font, Lang.get("lune.beside.takes.mouse_about"), inner).size(),
                wrap(font, Lang.get("lune.beside.takes.keyboard_about"), inner).size()));
        int firstLines = Math.max(wrap(font, Lang.get("lune.beside.first.you_about"), inner).size(),
                wrap(font, Lang.get("lune.beside.first.lune_about"), inner).size());

        int firstLabel = PADDING + LINE_H + TITLE_GAP + about.size() * LINE_H + SECTION_GAP;
        int firstRow = firstLabel + LINE_H + LABEL_GAP;
        int takesLabel = firstRow + BUTTON_H + 5 + firstLines * LINE_H + SECTION_GAP;
        int takesRow = takesLabel + LINE_H + LABEL_GAP;
        int height = takesRow + BUTTON_H + 5 + takesLines * LINE_H + 10 + BUTTON_H + PADDING;

        int x = (Screens.current(mc).width - width) / 2;
        int y = (Screens.current(mc).height - height) / 2;
        List<Hit> buttons = new ArrayList<>();
        addRow(font, buttons, first, x + PADDING, y + firstRow);
        addRow(font, buttons, takes, x + PADDING, y + takesRow);
        int buttonY = y + height - PADDING - BUTTON_H;
        // Right to left from the corner, so the main button sits where the eye ends up, and
        // turning it off from the other corner, where it is not hit on the way to Save.
        int right = x + width - PADDING;
        for (int i = closing.size() - 1; i >= 0; i--) {
            Action action = closing.get(i);
            int w = buttonWidth(font, action);
            if (action == Action.TURN_OFF) {
                buttons.add(new Hit(action, x + PADDING, buttonY, w, label(action)));
                continue;
            }
            right -= w;
            buttons.add(new Hit(action, right, buttonY, w, label(action)));
            right -= BUTTON_GAP;
        }
        return new Layout(x, y, width, height, about, y + firstLabel, y + firstRow, firstAbout,
                y + takesLabel, y + takesRow, takesAbout, buttons);
    }

    private void addRow(Font font, List<Hit> buttons, List<Action> row, int x, int y) {
        for (Action action : row) {
            int w = buttonWidth(font, action);
            buttons.add(new Hit(action, x, y, w, label(action)));
            x += w + BUTTON_GAP;
        }
    }

    private int rowWidth(Font font, List<Action> row) {
        int total = 0;
        for (Action action : row) {
            total += buttonWidth(font, action) + BUTTON_GAP;
        }
        return total - BUTTON_GAP;
    }

    private int buttonWidth(Font font, Action action) {
        return Math.max(BUTTON_MIN_W, font.width(label(action)) + BUTTON_PAD
                + (toggle(action) ? BOX_ROOM : 0));
    }

    private static List<String> wrap(Font font, String value, int width) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : value.split("\n", -1)) {
            String rest = paragraph;
            if (rest.isEmpty()) {
                lines.add("");
                continue;
            }
            while (!rest.isEmpty()) {
                String head = font.plainSubstrByWidth(rest, width);
                if (head.isEmpty()) {
                    head = rest.substring(0, 1);
                }
                if (head.length() < rest.length()) {
                    int space = head.lastIndexOf(' ');
                    if (space > head.length() / 3) {
                        head = head.substring(0, space);
                    }
                }
                lines.add(head);
                rest = rest.substring(head.length()).stripLeading();
            }
        }
        return lines;
    }

    private static String clip(Font font, String value, int maxWidth) {
        if (font.width(value) <= maxWidth) {
            return value;
        }
        String fit = font.plainSubstrByWidth(value, Math.max(0, maxWidth - font.width("…")), false);
        return fit.isEmpty() ? "…" : fit + "…";
    }

    // --- input ----------------------------------------------------------------------------------

    public void handleScreenMouseClick(double mouseX, double mouseY, int button) {
        if (!visible || button != InputConstants.MOUSE_BUTTON_LEFT) {
            return;
        }
        Layout layout = layout(Minecraft.getInstance().font);
        if (mouseX < layout.x || mouseX >= layout.x + layout.width
                || mouseY < layout.y || mouseY >= layout.y + layout.height) {
            // A click outside is a Cancel: nothing is changed until a button says so.
            close();
            return;
        }
        for (Hit hit : layout.buttons) {
            if (mouseX >= hit.x() && mouseX < hit.x() + hit.width()
                    && mouseY >= hit.y() && mouseY < hit.y() + BUTTON_H) {
                run(hit.action());
                return;
            }
        }
    }

    public void handleScreenKeyPressed(int keyCode) {
        if (!visible) {
            return;
        }
        if (keyCode == InputConstants.KEY_ESCAPE) {
            close();
        } else if (keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER) {
            run(Action.SAVE);
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
    protected void updateWidgetNarration(NarrationElementOutput output) {}

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return visible;
    }
}
