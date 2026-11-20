package com.etka.lune.client.gui.widget;

import com.etka.lune.client.gui.Accessibility;
import com.etka.lune.client.gui.LuneScreen;
import com.etka.lune.client.gui.mascot.MascotAdvisor;
import com.etka.lune.client.gui.mascot.MascotRenderer;
import com.etka.lune.compat.Screens;
import com.etka.lune.config.BotConfig;
import com.etka.lune.util.Lang;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;

/**
 * Lune's tour of the panel, drawn over it: first the offer, then one stop at a time.
 *
 * <p>The offer is a card in the middle of the dimmed panel - Lune asks whether the player would
 * like a quick look round, and <b>Show me around</b> and <b>Skip</b> both answer it for good, so it
 * is asked once ({@code BotConfig.tourOffered}). The tour itself is never more than a click away
 * after that: it has a page in Lune's corner.</p>
 *
 * <p>Each stop dims the panel everywhere but what it is about ({@link Spotlight}), outlines that in
 * the accent, and stands a card beside it with Lune's face, the stop's name, a line or two and the
 * way on. Lune is in the card rather than in her usual spot, so she is drawn once, beside what she
 * is talking about. Nothing under the dim can be used while the tour is up: it is a look round, not
 * a lesson, and a click that started a task on the way past would be a strange first impression.
 * The training course is the lesson, and the last stop offers it.</p>
 *
 * <p>Drawn and routed by {@code LuneScreen} like the other popups: while it is open it owns the
 * pointer and the keyboard. Which tab a stop is on, and what it lights, are the panel's to answer
 * through {@link Host}; the order is {@link PanelTour}'s.</p>
 */
public class TourOverlay extends AbstractWidget {

    /** What the panel answers for the tour. */
    public interface Host {
        /** The tour is about to start: remember the tab to come back to. */
        void tourStarting();

        /** Whether the panel as it stands can show this stop at all. */
        boolean canShow(PanelTour.Stop stop);

        /** Goes to the stop's tab and lays it out for the stop. */
        void show(PanelTour.Stop stop);

        /** What the stop lights, in the panel's pixels, the part its words are about first. */
        List<Spotlight.Box> light(PanelTour.Stop stop);

        /** The tour is over: back to the tab it started on, with anything it opened closed. */
        void tourEnded();

        /** Opens the training page of Lune's corner. */
        void openTraining();
    }

    private enum Action { START, SKIP_OFFER, BACK, NEXT, SKIP_TOUR, TRAINING }

    private record Hit(Action action, Spotlight.Box box) {}

    private static final int DIM = 0xB0000000;
    private static final int CARD = 0xF614161B;
    static final int CARD_W = 228;
    /** The narrowest a stop's card gets, taller for it, to stand beside something wide. */
    static final int CARD_NARROW = 168;
    private static final int OFFER_W = 288;
    static final int PAD = 8;
    /** Lune's head beside a stop's name. */
    static final int HEAD = 14;
    /** Her whole cell on the offer, drift room and all. */
    private static final int OFFER_ART = 52;
    private static final int TITLE_H = 16;
    /** Between Lune's head and the stop's name. */
    static final int TITLE_GAP = 6;
    /** The progress segments under a stop's words; the training course draws its own the same. */
    private static final int PROGRESS_H = 2;
    private static final int LINE_H = 10;
    private static final int BUTTON_H = 16;
    private static final int OFFER_BUTTON_H = 18;
    private static final int BUTTON_GAP = 4;
    /** Room either side of a footer button's word. */
    private static final int BUTTON_PAD = 6;
    /** The play arrow a primary button carries after its word: a gap and the arrow. */
    private static final int ARROW_ROOM = 9;
    /** Between the light and the card, where the card's tail points across. */
    private static final int GAP = 8;
    private static final int MARGIN = 4;
    /** The light reaches this far past what it shows, so its outline is not drawn on a border. */
    private static final int LIGHT_PAD = 2;
    /**
     * More lines than any stop's words take at the narrowest card, in any shipped language
     * ({@code TourWordsTest} holds them to fewer); past it the last line is clipped, never lost.
     */
    static final int MAX_LINES = 12;

    private final PanelTour tour = new PanelTour();
    private final MascotRenderer lune = new MascotRenderer();
    private final List<Hit> hits = new ArrayList<>();
    private Host host;
    private boolean offering;
    /** When the stop on show came up, so its light can open rather than appear. */
    private long shownAt;

    public TourOverlay() {
        super(-1000, -1000, 10, 10, Component.literal(Lang.get("lune.tour.title")));
        this.visible = false;
        this.active = false;
    }

    public void setHost(Host host) {
        this.host = host;
    }

    public boolean isOpen() {
        return offering || tour.running();
    }

    /** Asks whether the player would like the tour; answered either way, it is not asked again. */
    public void offer() {
        tour.end();
        offering = true;
        opened();
    }

    /** Starts the tour at {@code from}, or the first stop after it the panel can show. */
    public void start(PanelTour.Stop from) {
        if (host == null) {
            return;
        }
        offering = false;
        host.tourStarting();
        if (!tour.start(from, host::canShow)) {
            close();
            return;
        }
        opened();
        showStop();
    }

    private void opened() {
        visible = true;
        active = true;
        hits.clear();
        setFocused(true);
    }

    private void close() {
        boolean wasRunning = tour.running();
        tour.end();
        offering = false;
        visible = false;
        active = false;
        hits.clear();
        setFocused(false);
        if (wasRunning && host != null) {
            host.tourEnded();
        }
    }

    private void showStop() {
        PanelTour.Stop stop = tour.stop();
        if (stop != null && host != null) {
            host.show(stop);
            shownAt = Util.getMillis();
        }
    }

    /** The offer, answered: written down at once, so a panel closed a second later remembers it. */
    private static void answerOffer() {
        BotConfig config = BotConfig.get();
        if (PanelTour.shouldOffer(config.tourOffered)) {
            config.tourOffered = PanelTour.VERSION;
            config.save();
        }
    }

    private void run(Action action) {
        switch (action) {
            case START -> {
                answerOffer();
                start(PanelTour.Stop.values()[0]);
            }
            case SKIP_OFFER -> {
                answerOffer();
                close();
            }
            case NEXT -> {
                if (tour.next(host::canShow)) {
                    showStop();
                } else {
                    close();
                }
            }
            case BACK -> {
                if (tour.back(host::canShow)) {
                    showStop();
                }
            }
            case SKIP_TOUR -> close();
            case TRAINING -> {
                close();
                host.openTraining();
            }
        }
    }

    // --- drawing --------------------------------------------------------------------------------

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                            float partialTick) {
        // Required by AbstractWidget; drawn through render() so LuneScreen can put it on top.
    }

    public void render(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        hits.clear();
        if (!isOpen() || host == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        long now = Util.getMillis();
        Spotlight.Box screen = new Spotlight.Box(0, 0, Screens.current(mc).width,
                Screens.current(mc).height);
        if (offering) {
            extractor.fill(screen.x(), screen.y(), screen.right(), screen.bottom(), DIM);
            drawOffer(extractor, font, screen, mouseX, mouseY, now);
            return;
        }
        PanelTour.Stop stop = tour.stop();
        List<Spotlight.Box> holes = new ArrayList<>();
        for (Spotlight.Box box : host.light(stop)) {
            Spotlight.Box lit = box.grow(LIGHT_PAD).clip(screen);
            if (!lit.empty()) {
                holes.add(lit);
            }
        }
        for (Spotlight.Box dim : Spotlight.shade(screen, holes)) {
            extractor.fill(dim.x(), dim.y(), dim.right(), dim.bottom(), DIM);
        }
        drawLight(extractor, holes, now);
        drawStop(extractor, font, screen, holes, stop, mouseX, mouseY, now);
    }

    /** The accent round each lit part, breathing, and brighter for a moment as the stop opens. */
    private void drawLight(GuiGraphicsExtractor extractor, List<Spotlight.Box> holes, long now) {
        float beat = 0.5F + 0.5F * (float) Math.sin(now / 320.0);
        float arrival = Math.max(0.0F, 1.0F - (now - shownAt) / 400.0F);
        for (Spotlight.Box hole : holes) {
            MenuPaint.roundedOutline(extractor, hole.x(), hole.y(), hole.width(), hole.height(),
                    MenuPaint.fade(LuneScreen.ACCENT, Math.min(1.0F, 0.6F + 0.4F * beat + arrival)));
            Spotlight.Box glow = hole.grow(1);
            MenuPaint.roundedOutline(extractor, glow.x(), glow.y(), glow.width(), glow.height(),
                    MenuPaint.fade(LuneScreen.ACCENT, 0.15F + 0.25F * beat + 0.5F * arrival));
        }
    }

    private void drawStop(GuiGraphicsExtractor extractor, Font font, Spotlight.Box screen,
                          List<Spotlight.Box> holes, PanelTour.Stop stop, int mouseX, int mouseY,
                          long now) {
        String text = Lang.get(stop.textKey());
        boolean face = BotConfig.get().showLune;
        Spotlight.Placement placement = Spotlight.place(screen, holes, GAP, MARGIN, CARD_W,
                CARD_NARROW, width -> stopHeight(font, text, width));
        Spotlight.Box card = placement.card();
        drawCard(extractor, card);
        if (!holes.isEmpty()) {
            drawTail(extractor, card, holes.get(0), placement.side());
        }

        int x = card.x() + PAD;
        int inner = card.width() - PAD * 2;
        int titleY = card.y() + PAD + (TITLE_H - 8) / 2;
        int titleX = x;
        boolean first = tour.first(host::canShow);
        boolean last = tour.last(host::canShow);
        if (face) {
            lune.drawHead(extractor, last ? MascotAdvisor.Mood.SUCCESS : MascotAdvisor.Mood.IDLE,
                    x, card.y() + PAD + 1, HEAD, now);
            titleX = x + HEAD + TITLE_GAP;
        }
        MenuPaint.text(extractor, MenuPaint.clip(font, Lang.get(stop.titleKey()),
                Math.max(10, card.right() - PAD - titleX)), titleX, titleY, MenuPaint.WHITE);

        List<String> lines = lines(font, text, inner);
        int bodyY = card.y() + PAD + TITLE_H + 4;
        for (int i = 0; i < lines.size(); i++) {
            MenuPaint.text(extractor, lines.get(i), x, bodyY + i * LINE_H, LuneScreen.TEXT);
        }
        // How far along: a segment per stop the tour makes, as the training course counts its
        // steps, rather than a count beside the name that a narrow card had no room for.
        LuneMenu.drawProgress(extractor, segments(stop), x, bodyY + lines.size() * LINE_H + 4, inner);

        int footerY = card.bottom() - PAD - BUTTON_H;
        int right = card.right() - PAD;
        String next = Lang.get(last ? "lune.suggestion.got" : "lune.gui.tasks.next");
        int nextW = font.width(next) + BUTTON_PAD * 2 + ARROW_ROOM;
        right -= nextW;
        button(extractor, font, new Hit(Action.NEXT, new Spotlight.Box(right, footerY, nextW, BUTTON_H)),
                next, MenuPaint.Button.PRIMARY, mouseX, mouseY);
        if (!first) {
            String back = Lang.get("lune.gui.mascot.back");
            int backW = font.width(back) + BUTTON_PAD * 2;
            right -= BUTTON_GAP + backW;
            button(extractor, font, new Hit(Action.BACK, new Spotlight.Box(right, footerY, backW, BUTTON_H)),
                    back, MenuPaint.Button.SECONDARY, mouseX, mouseY);
        }
        // The quiet way out, at the other end: on the last stop the way on is the training course.
        String leave = Lang.get(last ? "lune.tour.training" : "lune.tour.skip");
        int leaveW = Math.min(font.width(leave), Math.max(0, right - BUTTON_GAP - x));
        if (leaveW > 0) {
            link(extractor, font, new Hit(last ? Action.TRAINING : Action.SKIP_TOUR,
                    new Spotlight.Box(x, footerY, leaveW, BUTTON_H)), leave, mouseX, mouseY);
        }
    }

    private void drawOffer(GuiGraphicsExtractor extractor, Font font, Spotlight.Box screen,
                           int mouseX, int mouseY, long now) {
        boolean face = BotConfig.get().showLune;
        OfferLayout layout = offerLayout(font, screen, face);
        Spotlight.Box card = layout.card();
        drawCard(extractor, card);
        int x = card.x() + PAD;
        if (face) {
            lune.draw(extractor, MascotAdvisor.Mood.ASKING, x - 4, card.y() + PAD - 4, OFFER_ART, now);
        }
        int textX = layout.textX();
        int y = card.y() + PAD + 4;
        MenuPaint.text(extractor, MenuPaint.clip(font, Lang.get("lune.tour.offer.title"),
                card.right() - PAD - textX), textX, y, MenuPaint.WHITE);
        y += 14;
        for (String line : layout.lines()) {
            MenuPaint.text(extractor, line, textX, y, LuneScreen.TEXT);
            y += LINE_H;
        }
        int laterY = layout.laterY();
        for (String line : layout.later()) {
            MenuPaint.text(extractor, line, x, laterY, Accessibility.dim());
            laterY += LINE_H;
        }
        int buttonY = card.bottom() - PAD - OFFER_BUTTON_H;
        String start = Lang.get("lune.tour.offer.start");
        String skip = Lang.get("lune.tour.offer.skip");
        int startW = MenuPaint.buttonWidth(font, start, MenuPaint.Button.PRIMARY);
        int skipW = MenuPaint.buttonWidth(font, skip, MenuPaint.Button.SECONDARY);
        int startX = card.right() - PAD - startW;
        int skipX = startX - BUTTON_GAP - skipW;
        button(extractor, font, new Hit(Action.SKIP_OFFER,
                new Spotlight.Box(skipX, buttonY, skipW, OFFER_BUTTON_H)), skip,
                MenuPaint.Button.SECONDARY, mouseX, mouseY);
        button(extractor, font, new Hit(Action.START,
                new Spotlight.Box(startX, buttonY, startW, OFFER_BUTTON_H)), start,
                MenuPaint.Button.PRIMARY, mouseX, mouseY);
    }

    private record OfferLayout(Spotlight.Box card, int textX, List<String> lines, int laterY,
                               List<String> later) {}

    /** Measured from the words, so the card is as tall as the language it is in needs. */
    private static OfferLayout offerLayout(Font font, Spotlight.Box screen, boolean face) {
        int width = Math.min(OFFER_W, screen.width() - MARGIN * 2);
        int textX = PAD + (face ? OFFER_ART - 4 : 0);
        List<String> lines = lines(font, Lang.get("lune.tour.offer.text"), width - textX - PAD);
        int top = Math.max(14 + lines.size() * LINE_H, face ? OFFER_ART - 8 : 0);
        List<String> later = lines(font, Lang.get("lune.tour.offer.later"), width - PAD * 2);
        int laterY = PAD + 4 + top + 8;
        int height = laterY + later.size() * LINE_H + 8 + OFFER_BUTTON_H + PAD;
        int x = screen.centreX() - width / 2;
        int y = Math.max(MARGIN, screen.centreY() - height / 2);
        return new OfferLayout(new Spotlight.Box(x, y, width, height), x + textX, lines, y + laterY,
                later);
    }

    /** Title, words, the progress segments and the buttons, for a card {@code width} wide. */
    private static int stopHeight(Font font, String text, int width) {
        return PAD + TITLE_H + 4 + lines(font, text, width - PAD * 2).size() * LINE_H + 4
                + PROGRESS_H + 6 + BUTTON_H + PAD;
    }

    /** One segment per stop the tour makes: the ones seen, the one on show, the ones to come. */
    private List<LuneMenu.Segment> segments(PanelTour.Stop on) {
        List<LuneMenu.Segment> segments = new ArrayList<>();
        for (PanelTour.Stop stop : PanelTour.Stop.values()) {
            if (host.canShow(stop)) {
                segments.add(stop.ordinal() < on.ordinal() ? LuneMenu.Segment.DONE
                        : stop == on ? LuneMenu.Segment.CURRENT : LuneMenu.Segment.AHEAD);
            }
        }
        return segments;
    }

    /** Every line of {@code text} at {@code width}; a stop's words are a few lines long by rule. */
    private static List<String> lines(Font font, String text, int width) {
        return MenuPaint.wrap(font, text, Math.max(20, width), MAX_LINES);
    }

    /** The corner's panel: a dark rounded box under a line of accent. */
    private static void drawCard(GuiGraphicsExtractor extractor, Spotlight.Box card) {
        extractor.fill(card.x() + 3, card.y() + 3, card.right() + 3, card.bottom() + 3, 0x40000000);
        MenuPaint.roundedFill(extractor, card.x(), card.y(), card.width(), card.height(),
                Accessibility.highContrast() ? 0xFF101014 : CARD);
        MenuPaint.roundedOutline(extractor, card.x(), card.y(), card.width(), card.height(),
                LuneScreen.PANEL_BORDER);
        extractor.fill(card.x() + 1, card.y(), card.right() - 1, card.y() + 1, LuneScreen.ACCENT);
    }

    /** A small accent triangle on the card's edge, pointing across the gap at what it explains. */
    private static void drawTail(GuiGraphicsExtractor extractor, Spotlight.Box card,
                                 Spotlight.Box anchor, Spotlight.Side side) {
        int colour = LuneScreen.ACCENT;
        switch (side) {
            case BELOW, ABOVE -> {
                int tipX = Math.clamp(anchor.centreX(), card.x() + 8, card.right() - 9);
                for (int row = 0; row < 4; row++) {
                    int half = 3 - row;
                    int y = side == Spotlight.Side.BELOW ? card.y() - 1 - row : card.bottom() + row;
                    extractor.fill(tipX - half, y, tipX + half + 1, y + 1, colour);
                }
            }
            case RIGHT, LEFT -> {
                int tipY = Math.clamp(anchor.centreY(), card.y() + 8, card.bottom() - 9);
                for (int column = 0; column < 4; column++) {
                    int half = 3 - column;
                    int x = side == Spotlight.Side.RIGHT ? card.x() - 1 - column : card.right() + column;
                    extractor.fill(x, tipY - half, x + 1, tipY + half + 1, colour);
                }
            }
            case NONE -> {
            }
        }
    }

    private void button(GuiGraphicsExtractor extractor, Font font, Hit hit, String label,
                        MenuPaint.Button style, int mouseX, int mouseY) {
        Spotlight.Box box = hit.box();
        MenuPaint.button(extractor, font, box.x(), box.y(), box.width(), box.height(), label, style,
                box.contains(mouseX, mouseY));
        hits.add(hit);
    }

    /** Words that act like a button without looking like one: the way out stays quiet. */
    private void link(GuiGraphicsExtractor extractor, Font font, Hit hit, String label, int mouseX,
                      int mouseY) {
        Spotlight.Box box = hit.box();
        boolean hovered = box.contains(mouseX, mouseY);
        int textY = box.y() + (box.height() - 8) / 2;
        String shown = MenuPaint.clip(font, label, box.width());
        MenuPaint.text(extractor, shown, box.x(), textY, hovered ? MenuPaint.WHITE : Accessibility.dim());
        if (hovered) {
            extractor.fill(box.x(), textY + 9, box.x() + font.width(shown), textY + 10, MenuPaint.WHITE);
        }
        hits.add(hit);
    }

    // --- input ----------------------------------------------------------------------------------

    /** A click anywhere while the tour is up; only its buttons answer, and nothing under it does. */
    public void handleScreenMouseClick(double mouseX, double mouseY, int button) {
        if (!isOpen() || button != InputConstants.MOUSE_BUTTON_LEFT) {
            return;
        }
        for (Hit hit : List.copyOf(hits)) {
            if (hit.box().contains(mouseX, mouseY)) {
                MenuPaint.click();
                run(hit.action());
                return;
            }
        }
    }

    /** Enter, Space or the right arrow go on, the left arrow goes back, and Escape leaves. */
    public void handleScreenKeyPressed(int keyCode) {
        if (!isOpen()) {
            return;
        }
        boolean forward = keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER
                || keyCode == InputConstants.KEY_SPACE || keyCode == InputConstants.KEY_RIGHT;
        if (offering) {
            if (keyCode == InputConstants.KEY_ESCAPE) {
                run(Action.SKIP_OFFER);
            } else if (forward && keyCode != InputConstants.KEY_RIGHT) {
                run(Action.START);
            }
            return;
        }
        if (keyCode == InputConstants.KEY_ESCAPE) {
            run(Action.SKIP_TOUR);
        } else if (forward) {
            run(Action.NEXT);
        } else if (keyCode == InputConstants.KEY_LEFT || keyCode == InputConstants.KEY_BACKSPACE) {
            run(Action.BACK);
        }
    }

    /**
     * The widgets of {@code widgets} on screen, lit as one area: what a tab hands the tour for a
     * stop about several of its controls. None on screen is nothing to light.
     */
    public static List<Spotlight.Box> around(AbstractWidget... widgets) {
        List<Spotlight.Box> shown = new ArrayList<>();
        for (AbstractWidget widget : widgets) {
            if (widget != null && widget.visible) {
                shown.add(new Spotlight.Box(widget.getX(), widget.getY(), widget.getWidth(),
                        widget.getHeight()));
            }
        }
        Spotlight.Box around = Spotlight.Box.around(shown);
        return around == null ? List.of() : List.of(around);
    }

    /** The panel is closing under the tour: whatever it moved goes back, and an offer waits. */
    public void panelClosing() {
        if (tour.running()) {
            close();
        }
        offering = false;
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
        return isOpen();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        if (offering) {
            output.add(NarratedElementType.TITLE, Component.literal(Lang.get("lune.tour.offer.title")
                    + " " + Lang.get("lune.tour.offer.text")));
        } else if (tour.running()) {
            PanelTour.Stop stop = tour.stop();
            output.add(NarratedElementType.TITLE, Component.literal(Lang.get(stop.titleKey())
                    + ". " + Lang.get(stop.textKey())));
        }
    }
}
