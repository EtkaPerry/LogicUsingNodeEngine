package com.etka.lune.client.gui.widget;

import com.etka.lune.compat.Screens;
import com.etka.lune.util.Lang;
import com.etka.lune.client.gui.LuneScreen;
import com.etka.lune.config.BotConfig;
import com.etka.lune.task.TaskNode;
import com.etka.lune.training.TrainingCourse;
import com.etka.lune.training.TrainingLesson;
import com.etka.lune.training.TrainingProgress;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ActiveTextCollector;
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
 * The course map: the training lessons drawn as a graph of cards wired end to end.
 *
 * <p>A list would have been less code. It would also have been the wrong shape to teach with - the
 * whole subject here is reading a wired graph, and a player who has to read one to find the lessons
 * has started the first lesson before they click anything. So the map is a canvas: cards with a
 * header, a state, an accent in the colour of the card that lesson is about, and a cable running
 * from each into the next.</p>
 *
 * <p>The course is one horizontal track. Paging and scrolling keep every step at the same height.</p>
 *
 * <p>It is an overlay rather than a real {@link net.minecraft.client.gui.screens.Screen}, following
 * {@link BlockPicker}: the panel draws at Lune's own GUI scale and the bot keeps running while it is
 * open, and both of those are properties of {@link LuneScreen} that a separate screen would
 * throw away.</p>
 */
public class TrainingScreen extends AbstractWidget {

    private static final int PADDING = 8;
    private static final int TITLE_H = 22;
    private static final int CARD_W = 172;
    private static final int CARD_H = 78;
    private static final int GAP_X = 34;
    private static final int HEADER_H = 15;
    private static final int BUTTON_W = 64;
    private static final int BUTTON_H = 18;
    private static final int PIN = 3;

    private static final int DIM = 0xB0000000;
    private static final int CARD_HOVER = 0x30FFFFFF;
    private static final int CLEARED = 0xFF6FBF87;
    private static final String TICK = "✔";
    private static final String ELLIPSIS = "…";

    private Consumer<TrainingLesson> onStartLesson;
    private boolean confirmingReset;
    private int firstVisible;

    /** Every rectangle the map is made of, computed once per event so drawing and clicking agree. */
    private record Layout(int panelX, int panelY, int panelW, int panelH,
                          int gridX, int gridY, int columns,
                          int buttonY, int resetX, int closeX) {}

    public TrainingScreen() {
        super(-1000, -1000, 10, 10, Component.literal(Lang.get("lune.gui.tasks.training")));
        this.visible = false;
        this.active = false;
    }

    public void setOnStartLesson(Consumer<TrainingLesson> handler) {
        this.onStartLesson = handler;
    }

    public boolean isOpen() {
        return visible;
    }

    public void open() {
        confirmingReset = false;
        int next = TrainingCourse.lessons().indexOf(TrainingProgress.next());
        firstVisible = Math.max(0, next);
        firstVisible = Math.min(firstVisible, Math.max(0, TrainingCourse.lessons().size() - layout().columns()));
        visible = true;
        active = true;
    }

    public void close() {
        visible = false;
        active = false;
        confirmingReset = false;
        setFocused(false);
    }

    // --- drawing -------------------------------------------------------------

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                            float partialTick) {
        // Drawn through render(), like BlockPicker, so LuneScreen can order it above the tab.
    }

    public void render(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        if (!visible) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        Layout layout = layout();
        List<TrainingLesson> lessons = TrainingCourse.lessons();

        extractor.fill(0, 0, Screens.current(mc).width, Screens.current(mc).height, DIM);
        LuneScreen.panel(extractor, layout.panelX(), layout.panelY(), layout.panelW(), layout.panelH());

        ActiveTextCollector text = extractor.textRenderer();
        text.accept(layout.panelX() + PADDING, layout.panelY() + PADDING,
                Component.literal(Lang.get("lune.gui.tasks.training")).withColor(LuneScreen.TEXT));
        String progress = TrainingProgress.completedCount() + Lang.get("lune.gui.recipe.label") + lessons.size() + Lang.get("lune.gui.training.cleared");
        text.accept(layout.panelX() + layout.panelW() - PADDING - font.width(progress),
                layout.panelY() + PADDING,
                Component.literal(progress).withColor(
                        TrainingProgress.completedCount() == lessons.size()
                                ? CLEARED : LuneScreen.TEXT_DIM));

        // Cables first, so a card always sits on top of the line arriving at it.
        for (int i = firstVisible; i + 1 < Math.min(lessons.size(), firstVisible + layout.columns()); i++) {
            drawCable(extractor, layout, i);
        }
        for (int i = firstVisible; i < Math.min(lessons.size(), firstVisible + layout.columns()); i++) {
            drawCard(extractor, text, font, layout, lessons.get(i), i, mouseX, mouseY);
        }

        drawButton(extractor, text, font, layout.resetX(), layout.buttonY(),
                confirmingReset ? Lang.get("lune.gui.tasks.sure") : Lang.get("lune.gui.training.reset"), mouseX, mouseY);
        drawButton(extractor, text, font, layout.closeX(), layout.buttonY(), Lang.get("lune.gui.recipe.close"), mouseX, mouseY);

        drawButton(extractor, text, font, layout.panelX() + PADDING, layout.buttonY(),
                Lang.get("lune.gui.training.previous"), mouseX, mouseY);
        drawButton(extractor, text, font, layout.panelX() + PADDING + BUTTON_W + 6,
                layout.buttonY(), Lang.get("lune.gui.training.next"), mouseX, mouseY);
        text.accept(layout.panelX() + PADDING, layout.buttonY() - 15,
                Component.literal(clip(font, Lang.get("lune.gui.training.clear_each_step_unlock_next_scroll_or"), layout.panelW() - 16))
                        .withColor(LuneScreen.TEXT_DIM));
    }

    private void drawCard(GuiGraphicsExtractor extractor, ActiveTextCollector text, Font font,
                          Layout layout, TrainingLesson lesson, int index, int mouseX, int mouseY) {
        int x = cardX(layout, index);
        int y = cardY(layout, index);
        boolean cleared = TrainingProgress.isComplete(lesson);
        boolean next = lesson == TrainingProgress.next();
        boolean unlocked = TrainingProgress.isUnlocked(lesson);
        boolean hovered = unlocked && hits(x, y, CARD_W, CARD_H, mouseX, mouseY);

        // The accent is the colour the answer card wears on the real canvas, so the map is already
        // teaching the role palette before the player has opened a puzzle.
        NodePalette.Colours colours = NodePalette.of(
                NodePalette.roleOf(new TaskNode(lesson.answerCommandId())),
                BotConfig.get().blueprintTheme);
        int edge = cleared ? CLEARED : next ? LuneScreen.ACCENT : colours.border();

        extractor.fill(x, y, x + CARD_W, y + CARD_H, colours.background());
        extractor.fill(x, y, x + CARD_W, y + HEADER_H, colours.header());
        if (hovered) {
            extractor.fill(x, y, x + CARD_W, y + CARD_H, CARD_HOVER);
        }
        extractor.outline(x, y, CARD_W, CARD_H, edge);
        // A left input pin and a right output pin, the two a canvas card always has.
        extractor.fill(x - PIN, y + HEADER_H + 8, x, y + HEADER_H + 8 + PIN * 2, edge);
        extractor.fill(x + CARD_W, y + HEADER_H + 8, x + CARD_W + PIN, y + HEADER_H + 8 + PIN * 2, edge);

        String step = Lang.get("lune.gui.training.step") + (index + 1);
        text.accept(x + 6, y + 4, Component.literal(step).withColor(colours.accent()));
        String state = !unlocked ? "locked" : cleared ? TICK + Lang.get("lune.gui.training.cleared") : "next";
        text.accept(x + CARD_W - 6 - font.width(state), y + 4,
                Component.literal(state).withColor(cleared ? CLEARED
                        : next ? LuneScreen.ACCENT : LuneScreen.TEXT_DIM));

        text.accept(x + 6, y + HEADER_H + 5,
                Component.literal(clip(font, lesson.title(), CARD_W - 12))
                        .withColor(cleared || next ? LuneScreen.TEXT : LuneScreen.TEXT_DIM));

        List<String> wrapped = wrap(font, lesson.about(), CARD_W - 12, 3);
        for (int line = 0; line < wrapped.size(); line++) {
            text.accept(x + 6, y + HEADER_H + 18 + line * 10,
                    Component.literal(wrapped.get(line)).withColor(LuneScreen.TEXT_DIM));
        }
    }

    /** Straight cables between consecutive steps; the track never wraps. */
    private void drawCable(GuiGraphicsExtractor extractor, Layout layout, int index) {
        int colour = TrainingProgress.isComplete(TrainingCourse.lessons().get(index))
                ? CLEARED : LuneScreen.PANEL_BORDER;
        int y = cardY(layout, index) + HEADER_H + 9;
        extractor.fill(cardX(layout, index) + CARD_W, y, cardX(layout, index + 1), y + 2, colour);
    }

    private void drawButton(GuiGraphicsExtractor extractor, ActiveTextCollector text, Font font,
                            int x, int y, String label, int mouseX, int mouseY) {
        boolean hovered = hits(x, y, BUTTON_W, BUTTON_H, mouseX, mouseY);
        extractor.fill(x, y, x + BUTTON_W, y + BUTTON_H,
                hovered ? LuneScreen.ACCENT : LuneScreen.PANEL_BG);
        extractor.outline(x, y, BUTTON_W, BUTTON_H, LuneScreen.PANEL_BORDER);
        text.accept(x + (BUTTON_W - font.width(label)) / 2, y + 5,
                Component.literal(label).withColor(hovered ? 0xFF000000 : LuneScreen.TEXT));
    }

    // --- geometry ------------------------------------------------------------

    private Layout layout() {
        Minecraft mc = Minecraft.getInstance();
        int screenW = Screens.current(mc) == null ? 400 : Screens.current(mc).width;
        int screenH = Screens.current(mc) == null ? 300 : Screens.current(mc).height;
        int panelW = Math.min(screenW - 20, Math.max(320, (int) (screenW * 0.88)));
        int panelH = Math.min(screenH - 20, Math.max(220, (int) (screenH * 0.82)));
        int panelX = (screenW - panelW) / 2;
        int panelY = (screenH - panelH) / 2;

        int gridW = panelW - PADDING * 2;
        int columns = Math.clamp((gridW + GAP_X) / (CARD_W + GAP_X), 1,
                TrainingCourse.lessons().size());
        int usedW = columns * CARD_W + (columns - 1) * GAP_X;
        int usedH = CARD_H;

        int buttonY = panelY + panelH - PADDING - BUTTON_H;
        int gridTop = panelY + TITLE_H;
        int gridBottom = buttonY - 30;
        return new Layout(panelX, panelY, panelW, panelH,
                panelX + PADDING + Math.max(0, (gridW - usedW) / 2),
                gridTop + Math.max(0, (gridBottom - gridTop - usedH) / 2),
                columns, buttonY,
                panelX + panelW - PADDING - BUTTON_W * 2 - 6,
                panelX + panelW - PADDING - BUTTON_W);
    }

    private int cardX(Layout layout, int index) {
        return layout.gridX() + (index - firstVisible) * (CARD_W + GAP_X);
    }

    private int cardY(Layout layout, int index) {
        return layout.gridY();
    }

    private void page(int direction) {
        firstVisible = Math.clamp(firstVisible + direction * layout().columns(), 0,
                Math.max(0, TrainingCourse.lessons().size() - layout().columns()));
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (!visible) return false;
        double delta = dy != 0 ? dy : dx;
        if (delta != 0) page(delta < 0 ? 1 : -1);
        return true;
    }

    private static boolean hits(int x, int y, int width, int height, int mouseX, int mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    // --- input ---------------------------------------------------------------

    public void handleScreenMouseClick(double mouseX, double mouseY, int button) {
        if (!visible || button != InputConstants.MOUSE_BUTTON_LEFT) {
            return;
        }
        Layout layout = layout();
        int x = (int) mouseX;
        int y = (int) mouseY;

        if (!hits(layout.panelX(), layout.panelY(), layout.panelW(), layout.panelH(), x, y)) {
            close();
            return;
        }
        if (hits(layout.closeX(), layout.buttonY(), BUTTON_W, BUTTON_H, x, y)) {
            close();
            return;
        }
        if (hits(layout.resetX(), layout.buttonY(), BUTTON_W, BUTTON_H, x, y)) {
            // Two clicks, because there is no undo for throwing the course record away.
            if (confirmingReset) {
                TrainingProgress.reset();
                confirmingReset = false;
                firstVisible = 0;
            } else {
                confirmingReset = true;
            }
            return;
        }
        confirmingReset = false;
        if (hits(layout.panelX() + PADDING, layout.buttonY(), BUTTON_W, BUTTON_H, x, y)) {
            page(-1);
            return;
        }
        if (hits(layout.panelX() + PADDING + BUTTON_W + 6, layout.buttonY(), BUTTON_W, BUTTON_H, x, y)) {
            page(1);
            return;
        }

        List<TrainingLesson> lessons = TrainingCourse.lessons();
        for (int i = firstVisible; i < Math.min(lessons.size(), firstVisible + layout.columns()); i++) {
            if (hits(cardX(layout, i), cardY(layout, i), CARD_W, CARD_H, x, y)) {
                if (!TrainingProgress.isUnlocked(lessons.get(i))) return;
                if (onStartLesson != null) {
                    onStartLesson.accept(lessons.get(i));
                }
                close();
                return;
            }
        }
    }

    public void handleScreenKeyPressed(int keyCode, int scanCode, int modifiers) {
        if (visible && keyCode == InputConstants.KEY_RIGHT) page(1);
        if (visible && keyCode == InputConstants.KEY_LEFT) page(-1);
        if (visible && keyCode == InputConstants.KEY_ESCAPE) {
            close();
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
    public boolean isMouseOver(double mouseX, double mouseY) {
        return visible;
    }

    @Override
    public void setPosition(int x, int y) {
        setX(x);
        setY(y);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {}

    // --- text ----------------------------------------------------------------

    private static String clip(Font font, String value, int maxWidth) {
        if (font.width(value) <= maxWidth) {
            return value;
        }
        String fit = font.plainSubstrByWidth(value, Math.max(0, maxWidth - font.width(ELLIPSIS)), false);
        return fit.isEmpty() ? ELLIPSIS : fit + ELLIPSIS;
    }

    /**
     * Greedy word wrap, capped.
     *
     * <p>The overflowing tail is carried into the last line and clipped there, rather than dropped:
     * a description that stops mid-sentence with nothing to say it was cut reads as a bug in the
     * text, not a limit on the card.</p>
     */
    private static List<String> wrap(Font font, String value, int maxWidth, int maxLines) {
        List<String> lines = new ArrayList<>(maxLines);
        String[] words = value.split(" ");
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String candidate = current.isEmpty() ? words[i] : current + " " + words[i];
            if (font.width(candidate) <= maxWidth) {
                current.setLength(0);
                current.append(candidate);
                continue;
            }
            if (lines.size() == maxLines - 1) {
                // No line left to start: everything still unplaced goes here, clipped.
                String tail = String.join(" ", List.of(words).subList(i, words.length));
                lines.add(clip(font, current.isEmpty() ? tail : current + " " + tail, maxWidth));
                return lines;
            }
            if (!current.isEmpty()) {
                lines.add(clip(font, current.toString(), maxWidth));
            }
            current.setLength(0);
            current.append(words[i]);
        }
        if (!current.isEmpty() && lines.size() < maxLines) {
            lines.add(clip(font, current.toString(), maxWidth));
        }
        return lines;
    }
}
