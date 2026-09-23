package com.etka.lune.client.gui.widget;

import com.etka.lune.client.gui.Accessibility;
import com.etka.lune.client.gui.LuneScreen;
import com.etka.lune.client.gui.mascot.MascotAdvisor;
import com.etka.lune.config.BotConfig;
import com.etka.lune.task.TaskNode;
import com.etka.lune.training.TrainingCourse;
import com.etka.lune.training.TrainingLesson;
import com.etka.lune.training.TrainingProgress;
import com.etka.lune.util.Lang;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The Training page of Lune's corner: the course as a track of step cards wired end to end, the
 * chosen step told in full below it, and the button that starts it.
 *
 * <p>This is the old course map with a page of its own. It keeps what made the map right - the
 * lessons drawn as cards on a cable, because reading a wired graph is the whole subject, and one
 * click on a card to start it - and adds what it never had: what a step is about before you are in
 * it, and a Continue that is one click from the page opening.</p>
 */
public final class TrainingSection implements LuneMenu.Section {

    private static final int CARD_W = 66;
    private static final int CARD_H = 46;
    private static final int HEADER_H = 13;
    private static final int CABLE = 20;
    private static final int PIN = 3;
    /** Room either end of the track for the arrows that scroll it. */
    private static final int ARROW_ZONE = 16;
    /** Room above the cards, where the moon hangs over the step that is next. */
    private static final int MARKER_H = 20;
    private static final int TILE = 30;
    private static final int BUTTON_H = 20;
    private static final int CARD_HOVER = 0x30FFFFFF;
    private static final int LOCKED_VEIL = 0x66000000;
    private static final int MOONLIGHT = 0xFFF4C95D;

    private final Consumer<TrainingLesson> start;
    /** The first card on the track, or -1 until the page is laid out and can centre the focus. */
    private int firstVisible = -1;
    private int lastVisibleCount = 1;
    /** The step the page describes: the one last pointed at, or moved to with the arrow keys. */
    private int focus;
    private boolean confirmingReset;

    private record Layout(int titleY, List<String> intro, int introY, int trackTop, int cardY,
                          int trackX, int trackWidth, int visible, int startX,
                          LuneMenu.Area detail, int footerY) {}

    /** {@code start} is handed the lesson to open; the corner closes and the Tasks tab takes it. */
    public TrainingSection(Consumer<TrainingLesson> start) {
        this.start = start;
    }

    private static List<TrainingLesson> lessons() {
        return TrainingCourse.lessons();
    }

    // --- the side menu -------------------------------------------------------

    @Override
    public String title() {
        return Lang.get("lune.gui.tasks.training");
    }

    @Override
    public String caption() {
        return TrainingProgress.completedCount() + "/" + lessons().size();
    }

    @Override
    public int captionColour() {
        return TrainingProgress.next() == null
                ? Accessibility.colour(Accessibility.Mark.GOOD) : Accessibility.dim();
    }

    @Override
    public List<LuneMenu.Segment> progress() {
        return segments(lessons(), TrainingProgress::isComplete);
    }

    /** Until the first step is cleared: the course is the thing a new player most wants to find. */
    @Override
    public boolean hasNews() {
        return TrainingProgress.completedCount() == 0;
    }

    @Override
    public String luneLine() {
        TrainingLesson next = TrainingProgress.next();
        if (next == null) {
            return Lang.get("lune.mascot.corner.training_done");
        }
        if (TrainingProgress.completedCount() == 0) {
            return Lang.get("lune.mascot.corner.training_new");
        }
        return Lang.get("lune.mascot.corner.training_next", lessons().indexOf(next) + 1);
    }

    @Override
    public MascotAdvisor.Mood luneMood() {
        if (TrainingProgress.next() == null) {
            return MascotAdvisor.Mood.SUCCESS;
        }
        return TrainingProgress.completedCount() == 0 ? MascotAdvisor.Mood.IDLE : MascotAdvisor.Mood.LEARNING;
    }

    /**
     * Two cards and the cable between them, in the player's own canvas theme.
     *
     * <p>The course teaches one thing, reading a wired graph, so its picture is the smallest graph
     * there is: a source feeding a card, drawn in the colours those two roles wear on the canvas.
     * Changing the theme recolours it along with the board.</p>
     */
    @Override
    public void drawIcon(GuiGraphicsExtractor extractor, int x, int y, int size) {
        String theme = BotConfig.get().blueprintTheme;
        int left = x + (size - 26) / 2;
        int top = y + (size - 26) / 2;
        miniCard(extractor, left + 2, top + 4, NodePalette.of(NodePalette.Role.SOURCE, theme));
        miniCard(extractor, left + 16, top + 15, NodePalette.of(NodePalette.Role.ACTION, theme));
        int cable = NodePalette.pins().success();
        extractor.fill(left + 10, top + 7, left + 13, top + 8, cable);
        extractor.fill(left + 12, top + 7, left + 13, top + 19, cable);
        extractor.fill(left + 12, top + 18, left + 16, top + 19, cable);
    }

    private static void miniCard(GuiGraphicsExtractor extractor, int x, int y, NodePalette.Colours colours) {
        extractor.fill(x, y, x + 8, y + 7, colours.border());
        extractor.fill(x + 1, y + 1, x + 7, y + 6, 0xFF2C303B);
        extractor.fill(x, y, x + 8, y + 2, colours.accent());
        extractor.fill(x + 2, y + 4, x + 6, y + 5, 0xFF5C6272);
    }

    // --- the page ------------------------------------------------------------

    /** Opens on the step that is next, or on the first once the course is finished. */
    @Override
    public void shown() {
        TrainingLesson next = TrainingProgress.next();
        focus = next == null ? 0 : Math.max(0, lessons().indexOf(next));
        firstVisible = -1;
        confirmingReset = false;
    }

    private Layout layout(LuneMenu.Area area, Font font) {
        int count = lessons().size();
        // The introduction is the first thing a short screen gives up; the track and the step
        // below it are the page.
        List<String> intro = area.height() >= 200
                ? MenuPaint.wrap(font, Lang.get("lune.gui.tasks.ten_puzzles_easiest_first_each_one_hands"),
                        area.width() - 4, 2)
                : List.of();
        int titleY = area.y() + 2;
        int introY = area.y() + 24;
        int trackTop = intro.isEmpty() ? introY : introY + intro.size() * 10 + 4;
        int cardY = trackTop + MARKER_H;
        int trackX = area.x() + ARROW_ZONE;
        int trackWidth = Math.max(CARD_W, area.width() - ARROW_ZONE * 2);
        int visible = Math.clamp((trackWidth + CABLE) / (CARD_W + CABLE), 1, Math.max(1, count));
        int used = visible * CARD_W + (visible - 1) * CABLE;
        int startX = trackX + Math.max(0, (trackWidth - used) / 2);
        int footerY = area.bottom() - 10;
        int detailY = cardY + CARD_H + 12;
        LuneMenu.Area detail = new LuneMenu.Area(area.x(), detailY, area.width(),
                Math.max(0, footerY - 8 - detailY));
        return new Layout(titleY, intro, introY, trackTop, cardY, trackX, trackWidth, visible, startX,
                detail, footerY);
    }

    /** Keeps the track's window inside the course, and centred on the focus the first time. */
    private void placeWindow(int visible, int count) {
        lastVisibleCount = visible;
        if (firstVisible < 0) {
            firstVisible = windowStart(focus, visible, count);
        }
        firstVisible = Math.clamp(firstVisible, 0, Math.max(0, count - visible));
    }

    private void keepFocusInView() {
        if (focus < firstVisible) {
            firstVisible = focus;
        } else if (focus >= firstVisible + lastVisibleCount) {
            firstVisible = focus - lastVisibleCount + 1;
        }
    }

    private int cardX(Layout layout, int index) {
        return layout.startX() + (index - firstVisible) * (CARD_W + CABLE);
    }

    private int cardAt(Layout layout, double mouseX, double mouseY) {
        int last = Math.min(lessons().size(), firstVisible + layout.visible());
        for (int i = firstVisible; i < last; i++) {
            if (MenuPaint.hits(cardX(layout, i), layout.cardY(), CARD_W, CARD_H, mouseX, mouseY)) {
                return i;
            }
        }
        return -1;
    }

    private LuneMenu.Area leftArrow(Layout layout) {
        return new LuneMenu.Area(layout.trackX() - ARROW_ZONE, layout.cardY(), ARROW_ZONE, CARD_H);
    }

    private LuneMenu.Area rightArrow(Layout layout) {
        return new LuneMenu.Area(layout.trackX() + layout.trackWidth(), layout.cardY(), ARROW_ZONE, CARD_H);
    }

    private static NodePalette.Colours colours(TrainingLesson lesson) {
        // The colour the answer card wears on the real canvas, so the track is already teaching
        // the role palette before the player has opened a puzzle.
        return NodePalette.of(NodePalette.roleOf(new TaskNode(lesson.answerCommandId())),
                BotConfig.get().blueprintTheme);
    }

    @Override
    public void render(GuiGraphicsExtractor extractor, LuneMenu.Area area, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        long now = Util.getMillis();
        List<TrainingLesson> lessons = lessons();
        if (lessons.isEmpty()) {
            return;
        }
        Layout layout = layout(area, font);
        placeWindow(layout.visible(), lessons.size());
        int pointed = cardAt(layout, mouseX, mouseY);
        if (pointed >= 0) {
            // Pointing at a step is choosing it: the page below follows, and stays when the
            // pointer moves down to its button.
            focus = pointed;
        }
        focus = Math.clamp(focus, 0, lessons.size() - 1);

        MenuPaint.bigText(extractor, title(), area.x() + 2, layout.titleY(), MenuPaint.WHITE);
        boolean finished = TrainingProgress.next() == null;
        int good = Accessibility.colour(Accessibility.Mark.GOOD);
        String badge = finished ? Lang.get("lune.gui.menu.training_done")
                : TrainingProgress.completedCount() + "/" + lessons.size() + Lang.get("lune.gui.training.cleared");
        int badgeX = area.right() - 2 - font.width(badge);
        MenuPaint.text(extractor, badge, badgeX, layout.titleY() + 5, finished ? good : Accessibility.dim());
        if (finished) {
            MenuPaint.check(extractor, badgeX - 11, layout.titleY() + 5, good);
        }
        for (int i = 0; i < layout.intro().size(); i++) {
            MenuPaint.text(extractor, layout.intro().get(i), area.x() + 2, layout.introY() + i * 10,
                    Accessibility.dim());
        }

        drawTrack(extractor, layout, lessons, pointed, mouseX, mouseY, now);
        drawDetail(extractor, font, layout, lessons.get(focus), mouseX, mouseY);
        drawFooter(extractor, font, area, layout, mouseX, mouseY);
    }

    private void drawTrack(GuiGraphicsExtractor extractor, Layout layout, List<TrainingLesson> lessons,
                           int pointed, int mouseX, int mouseY, long now) {
        int count = lessons.size();
        int last = Math.min(count, firstVisible + layout.visible());
        int cableY = layout.cardY() + HEADER_H + 8;
        int good = Accessibility.colour(Accessibility.Mark.GOOD);

        // Cables first, so a card always sits on top of the line arriving at it. A track scrolled
        // part-way runs its cable off the edge, so it reads as going on rather than stopping.
        for (int i = Math.max(0, firstVisible - 1); i < Math.min(count - 1, last); i++) {
            int from = i < firstVisible ? layout.trackX() : cardX(layout, i) + CARD_W + PIN;
            int to = i + 1 >= last ? layout.trackX() + layout.trackWidth() : cardX(layout, i + 1) - PIN;
            if (to > from) {
                extractor.fill(from, cableY, to, cableY + 2,
                        TrainingProgress.isComplete(lessons.get(i)) ? good : LuneScreen.PANEL_BORDER);
            }
        }
        for (int i = firstVisible; i < last; i++) {
            drawStep(extractor, layout, lessons.get(i), i, i == pointed, now);
        }

        if (firstVisible > 0) {
            drawArrow(extractor, leftArrow(layout), false, mouseX, mouseY);
        }
        if (last < count) {
            drawArrow(extractor, rightArrow(layout), true, mouseX, mouseY);
        }

        TrainingLesson next = TrainingProgress.next();
        int nextIndex = next == null ? -1 : lessons.indexOf(next);
        if (nextIndex >= firstVisible && nextIndex < last) {
            // The moon hangs over the step that is next, bobbing on the same slow beat as Lune.
            int bob = (int) Math.round(Math.sin(now / 420.0) * 1.4);
            int moonX = cardX(layout, nextIndex) + CARD_W / 2 - 5;
            int moonY = layout.trackTop() + 3 + bob;
            var pose = extractor.pose();
            pose.pushMatrix();
            pose.translate(moonX, moonY);
            pose.scale(2.0F, 2.0F);
            MenuPaint.moon(extractor, 0, 0, MOONLIGHT);
            pose.popMatrix();
        }
    }

    private void drawStep(GuiGraphicsExtractor extractor, Layout layout, TrainingLesson lesson, int index,
                          boolean pointed, long now) {
        int x = cardX(layout, index);
        int y = layout.cardY();
        boolean cleared = TrainingProgress.isComplete(lesson);
        boolean unlocked = TrainingProgress.isUnlocked(lesson);
        boolean next = lesson.equals(TrainingProgress.next());
        NodePalette.Colours colours = colours(lesson);
        int good = Accessibility.colour(Accessibility.Mark.GOOD);
        int edge = cleared ? good : next ? LuneScreen.ACCENT : unlocked ? colours.border() : LuneScreen.PANEL_BORDER;

        if (next) {
            // A slow pulse round the step that is next, so the eye finds it before the words.
            float beat = 0.5F + 0.5F * (float) Math.sin(now / 320.0);
            MenuPaint.roundedOutline(extractor, x - 2, y - 2, CARD_W + 4, CARD_H + 4,
                    MenuPaint.fade(LuneScreen.ACCENT, 0.25F + 0.55F * beat));
        } else if (index == focus) {
            MenuPaint.roundedOutline(extractor, x - 2, y - 2, CARD_W + 4, CARD_H + 4,
                    MenuPaint.fade(LuneScreen.ACCENT_HOVER, 0.6F));
        }

        extractor.fill(x, y, x + CARD_W, y + CARD_H, colours.background());
        extractor.fill(x, y, x + CARD_W, y + HEADER_H, colours.header());
        if (!unlocked) {
            extractor.fill(x, y, x + CARD_W, y + CARD_H, LOCKED_VEIL);
        } else if (pointed) {
            extractor.fill(x, y, x + CARD_W, y + CARD_H, CARD_HOVER);
        }
        extractor.outline(x, y, CARD_W, CARD_H, edge);
        // The two pins a canvas card always has, in and out.
        extractor.fill(x - PIN, y + HEADER_H + 7, x, y + HEADER_H + 11, edge);
        extractor.fill(x + CARD_W, y + HEADER_H + 7, x + CARD_W + PIN, y + HEADER_H + 11, edge);
        MenuPaint.text(extractor, Lang.get("lune.gui.training.step") + (index + 1), x + 5, y + 3,
                unlocked ? colours.accent() : Accessibility.dim());

        int centreX = x + CARD_W / 2;
        int centreY = y + HEADER_H + (CARD_H - HEADER_H) / 2;
        if (cleared) {
            MenuPaint.check(extractor, centreX - 3, centreY - 3, good);
        } else if (!unlocked) {
            MenuPaint.lock(extractor, centreX - 3, centreY - 4, Accessibility.dim(), colours.background());
        } else {
            var pose = extractor.pose();
            pose.pushMatrix();
            pose.translate(centreX - 4, centreY - 7);
            pose.scale(2.0F, 2.0F);
            MenuPaint.play(extractor, 0, 0, LuneScreen.ACCENT);
            pose.popMatrix();
        }
    }

    private static void drawArrow(GuiGraphicsExtractor extractor, LuneMenu.Area arrow, boolean right,
                                  int mouseX, int mouseY) {
        boolean hot = arrow.contains(mouseX, mouseY);
        if (hot) {
            MenuPaint.roundedFill(extractor, arrow.x() + 1, arrow.y(), arrow.width() - 2, arrow.height(),
                    MenuPaint.HOVER);
        }
        MenuPaint.chevron(extractor, arrow.x() + (arrow.width() - 6) / 2, arrow.y() + (arrow.height() - 9) / 2,
                right, hot ? MenuPaint.WHITE : LuneScreen.TEXT);
    }

    /** What the button under a step says, and how loudly. */
    private record Action(String label, MenuPaint.Button style) {}

    private static Action action(TrainingLesson lesson) {
        if (TrainingProgress.isComplete(lesson)) {
            return new Action(Lang.get("lune.gui.training.replay"), MenuPaint.Button.SECONDARY);
        }
        if (!TrainingProgress.isUnlocked(lesson)) {
            return new Action(Lang.get("lune.gui.training.locked"), MenuPaint.Button.DISABLED);
        }
        return new Action(Lang.get(TrainingProgress.completedCount() == 0
                ? "lune.gui.main.start" : "lune.gui.tasks.debug_continue"), MenuPaint.Button.PRIMARY);
    }

    private static LuneMenu.Area buttonArea(LuneMenu.Area detail, Font font, Action action) {
        int width = MenuPaint.buttonWidth(font, action.label(), action.style());
        return new LuneMenu.Area(detail.right() - 10 - width, detail.bottom() - 10 - BUTTON_H, width, BUTTON_H);
    }

    private void drawDetail(GuiGraphicsExtractor extractor, Font font, Layout layout, TrainingLesson lesson,
                            int mouseX, int mouseY) {
        LuneMenu.Area detail = layout.detail();
        if (detail.height() < TILE + 20) {
            return;
        }
        MenuPaint.card(extractor, detail.x(), detail.y(), detail.width(), detail.height());
        int index = lessons().indexOf(lesson);
        NodePalette.Colours colours = colours(lesson);
        int good = Accessibility.colour(Accessibility.Mark.GOOD);

        // The step's number on a tile in the colour its card wears on the track.
        int tileX = detail.x() + 10;
        int tileY = detail.y() + 10;
        extractor.fill(tileX, tileY, tileX + TILE, tileY + TILE, colours.header());
        extractor.outline(tileX, tileY, TILE, TILE, colours.border());
        String number = String.valueOf(index + 1);
        MenuPaint.bigText(extractor, number, tileX + (TILE - font.width(number) * 2) / 2 + 1, tileY + 8,
                colours.accent());

        Action action = action(lesson);
        LuneMenu.Area button = buttonArea(detail, font, action);
        int textX = tileX + TILE + 10;
        int textRight = detail.right() - 10;

        String status;
        int statusColour;
        if (TrainingProgress.isComplete(lesson)) {
            status = Lang.get("lune.gui.training.status_cleared");
            statusColour = good;
            MenuPaint.check(extractor, textX, detail.y() + 11, good);
        } else if (TrainingProgress.isUnlocked(lesson)) {
            status = Lang.get("lune.gui.training.status_next");
            statusColour = LuneScreen.ACCENT;
            MenuPaint.play(extractor, textX + 1, detail.y() + 11, LuneScreen.ACCENT);
        } else {
            TrainingLesson gap = TrainingProgress.next();
            status = Lang.get("lune.gui.training.status_locked", gap == null ? 1 : lessons().indexOf(gap) + 1);
            statusColour = Accessibility.dim();
            MenuPaint.lock(extractor, textX, detail.y() + 10, Accessibility.dim(), 0xFF14161B);
        }
        MenuPaint.text(extractor, MenuPaint.clip(font, status, textRight - textX - 11), textX + 11,
                detail.y() + 11, statusColour);

        // The name shares its row with the button only when the card is too short to keep them
        // apart, and then it gives way.
        int titleRight = button.y() < detail.y() + 33 ? button.x() - 8 : textRight;
        MenuPaint.text(extractor, MenuPaint.clip(font, lesson.title(), titleRight - textX), textX,
                detail.y() + 23, MenuPaint.WHITE);
        int aboutLines = Math.clamp((button.y() - 4 - (detail.y() + 36)) / 10, 0, 3);
        List<String> about = MenuPaint.wrap(font, lesson.about(), textRight - textX, aboutLines);
        for (int i = 0; i < about.size(); i++) {
            MenuPaint.text(extractor, about.get(i), textX, detail.y() + 36 + i * 10, Accessibility.dim());
        }

        boolean hot = action.style() != MenuPaint.Button.DISABLED && button.contains(mouseX, mouseY);
        MenuPaint.button(extractor, font, button.x(), button.y(), button.width(), button.height(),
                action.label(), action.style(), hot);
    }

    private LuneMenu.Area resetArea(LuneMenu.Area area, Layout layout, Font font) {
        if (TrainingProgress.completedCount() == 0 && !confirmingReset) {
            return null;
        }
        String label = Lang.get(confirmingReset ? "lune.gui.tasks.sure" : "lune.gui.training.reset");
        int width = font.width(label);
        return new LuneMenu.Area(area.right() - 2 - width - 3, layout.footerY() - 3, width + 6, 14);
    }

    private void drawFooter(GuiGraphicsExtractor extractor, Font font, LuneMenu.Area area, Layout layout,
                            int mouseX, int mouseY) {
        LuneMenu.Area reset = resetArea(area, layout, font);
        int hintRight = area.right() - 2;
        if (reset != null) {
            String label = Lang.get(confirmingReset ? "lune.gui.tasks.sure" : "lune.gui.training.reset");
            boolean hot = reset.contains(mouseX, mouseY);
            // Quiet until pointed at: throwing the course record away is not a thing to invite.
            int colour = confirmingReset ? Accessibility.colour(Accessibility.Mark.WARN)
                    : hot ? LuneScreen.TEXT : Accessibility.dim();
            MenuPaint.text(extractor, label, reset.x() + 3, layout.footerY(), colour);
            if (hot || confirmingReset) {
                extractor.fill(reset.x() + 3, layout.footerY() + 9, reset.right() - 3, layout.footerY() + 10, colour);
            }
            hintRight = reset.x() - 10;
        }
        MenuPaint.text(extractor, MenuPaint.clip(font,
                        Lang.get("lune.gui.training.clear_each_step_unlock_next_scroll_or"), hintRight - area.x() - 2),
                area.x() + 2, layout.footerY(), Accessibility.dim());
    }

    // --- input ---------------------------------------------------------------

    @Override
    public void click(LuneMenu.Area area, double mouseX, double mouseY, int button) {
        if (button != InputConstants.MOUSE_BUTTON_LEFT) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        List<TrainingLesson> lessons = lessons();
        if (lessons.isEmpty()) {
            return;
        }
        Layout layout = layout(area, font);
        placeWindow(layout.visible(), lessons.size());
        boolean wasConfirming = confirmingReset;
        LuneMenu.Area reset = resetArea(area, layout, font);
        confirmingReset = false;

        if (firstVisible > 0 && leftArrow(layout).contains(mouseX, mouseY)) {
            MenuPaint.click();
            scrollBy(-layout.visible());
            return;
        }
        if (firstVisible + layout.visible() < lessons.size() && rightArrow(layout).contains(mouseX, mouseY)) {
            MenuPaint.click();
            scrollBy(layout.visible());
            return;
        }
        int card = cardAt(layout, mouseX, mouseY);
        if (card >= 0) {
            // One click on a card starts it, as the course map always did.
            begin(lessons.get(card));
            return;
        }
        TrainingLesson chosen = lessons.get(Math.clamp(focus, 0, lessons.size() - 1));
        if (layout.detail().height() >= TILE + 20
                && buttonArea(layout.detail(), font, action(chosen)).contains(mouseX, mouseY)) {
            begin(chosen);
            return;
        }
        if (reset != null && reset.contains(mouseX, mouseY)) {
            MenuPaint.click();
            if (wasConfirming) {
                // Two clicks, because there is no undo for throwing the course record away.
                TrainingProgress.reset();
                shown();
            } else {
                confirmingReset = true;
            }
        }
    }

    private void begin(TrainingLesson lesson) {
        if (!TrainingProgress.isUnlocked(lesson)) {
            return;
        }
        MenuPaint.click();
        start.accept(lesson);
    }

    private void scrollBy(int cards) {
        firstVisible = Math.clamp(firstVisible + cards, 0, Math.max(0, lessons().size() - lastVisibleCount));
        focus = Math.clamp(focus, firstVisible, firstVisible + lastVisibleCount - 1);
    }

    @Override
    public void scroll(LuneMenu.Area area, double mouseX, double mouseY, double delta) {
        scrollBy(delta < 0 ? 1 : -1);
    }

    /** Left and right walk the steps; Enter or Space starts the one the page is on. */
    @Override
    public void key(LuneMenu.Area area, int keyCode) {
        int count = lessons().size();
        if (count == 0) {
            return;
        }
        if (keyCode == InputConstants.KEY_LEFT) {
            focus = Math.max(0, focus - 1);
            keepFocusInView();
        } else if (keyCode == InputConstants.KEY_RIGHT) {
            focus = Math.min(count - 1, focus + 1);
            keepFocusInView();
        } else if (keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER
                || keyCode == InputConstants.KEY_SPACE) {
            begin(lessons().get(Math.clamp(focus, 0, count - 1)));
        }
    }

    // --- the rules, free of the screen --------------------------------------

    /**
     * One segment per lesson: cleared ones done, the first one not cleared current, the rest ahead.
     *
     * <p>Current is the first gap, not the step after the last clear. An old save can hold a clear
     * beyond a step that is open again, and the gap is where {@link TrainingProgress#next()} and
     * the track send the player.</p>
     */
    static List<LuneMenu.Segment> segments(List<TrainingLesson> lessons, Predicate<TrainingLesson> cleared) {
        List<LuneMenu.Segment> segments = new ArrayList<>(lessons.size());
        boolean currentPlaced = false;
        for (TrainingLesson lesson : lessons) {
            if (cleared.test(lesson)) {
                segments.add(LuneMenu.Segment.DONE);
            } else if (!currentPlaced) {
                segments.add(LuneMenu.Segment.CURRENT);
                currentPlaced = true;
            } else {
                segments.add(LuneMenu.Segment.AHEAD);
            }
        }
        return segments;
    }

    /** The first card to show so that {@code focus} is in view, as near the middle as the ends allow. */
    static int windowStart(int focus, int visible, int count) {
        return Math.clamp(focus - (visible - 1) / 2, 0, Math.max(0, count - visible));
    }
}
