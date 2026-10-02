package com.etka.lune.client.gui.tab;

import com.etka.lune.bot.util.ServerAccess;
import com.etka.lune.client.gui.Accessibility;
import com.etka.lune.client.gui.LuneScreen;
import com.etka.lune.client.gui.LuneTab;
import com.etka.lune.net.RulesPayload;
import com.etka.lune.server.ServerRules;
import com.etka.lune.util.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * The rules of the server Lune is playing on: whether she may work here, who decides, and - for
 * the people who do - the buttons that change them and the list of who else has Lune.
 *
 * <p>Beside Config because it is the other half of the same question. Config is how the player
 * likes the bot to behave; this is what the server they are on allows it to do. Everyone can read
 * it, so a player a server refuses can see which rule refused them. Only operators, and the owner
 * of a world opened to LAN, can press anything, and nothing changes here directly: a press asks the
 * server, and the tab shows what the server answers.</p>
 *
 * <p>Three cards in the About tab's style: where the player stands, the two rules, and who has
 * Lune. The chosen answer to a rule is underlined in the accent, as the Main tab marks its chosen
 * statistics view.</p>
 */
public class ServerTab extends LuneTab {

    static final int MARGIN = 10;
    static final int GAP = 8;
    /** Wider than this and the cards stop growing, as on the About tab. */
    static final int MAX_WIDTH = 820;
    /** Narrower than this and the cards stack in one column. */
    static final int STACK_WIDTH = 420;
    static final int TITLE_H = DashboardFrame.TITLE_H;
    static final int PAD = 9;
    static final int BODY_TOP = TITLE_H + 8;
    static final int LINE = 12;
    static final int BUTTON_H = 18;
    static final int BUTTON_GAP = 6;
    /** A rule's label, its buttons, and the room left under them before the next rule. */
    static final int RULE_H = LINE + BUTTON_H + 12;
    /** The smallest status card that still holds its heading and three lines. */
    static final int STATUS_H = BODY_TOP + LINE * 3 + PAD;
    /** Both rules, and two lines of footnote under them. */
    static final int RULES_H = BODY_TOP + RULE_H * 2 + LINE * 2 + PAD;

    private static final int PANEL_HEADER = 0xD0222D3A;
    /** The order the answers are offered in, from the most open to the most closed. */
    private static final ServerRules.Who[] CHOICES = ServerRules.Who.values();

    /** One card, and the questions the drawing helpers ask it. */
    record Card(int x, int y, int width, int height) {
        int bottom() {
            return y + height;
        }

        int textWidth() {
            return Math.max(1, width - PAD * 2);
        }

        /** Whether a line of text starting at {@code lineY} still fits inside the border. */
        boolean holds(int lineY) {
            return lineY + 9 <= bottom() - PAD + 2;
        }
    }

    /**
     * Where the cards and the rule buttons go, for a content area. Two columns - where the player
     * stands and who has Lune on the left, the rules on the right - and one column on a panel too
     * narrow for two. Free of Minecraft types, so the layout is tested without a client.
     */
    record Frame(Card status, Card rules, Card players) {

        static Frame of(int left, int top, int right, int bottom) {
            int areaWidth = right - left;
            int width = Math.min(MAX_WIDTH, Math.max(80, areaWidth - MARGIN * 2));
            int x = left + Math.max(MARGIN, (areaWidth - width) / 2);
            int y = top + MARGIN;
            int height = Math.max(80, bottom - top - MARGIN * 2);
            if (width < STACK_WIDTH) {
                Card status = new Card(x, y, width, STATUS_H);
                Card rules = new Card(x, status.bottom() + GAP, width, RULES_H);
                int playersTop = rules.bottom() + GAP;
                return new Frame(status, rules, new Card(x, playersTop, width, Math.max(0, y + height - playersTop)));
            }
            int column = (width - GAP) / 2;
            Card status = new Card(x, y, column, Math.min(height, Math.max(STATUS_H, (height - GAP) / 2)));
            int playersTop = status.bottom() + GAP;
            Card players = new Card(x, playersTop, column, Math.max(0, y + height - playersTop));
            Card rules = new Card(x + column + GAP, y, width - column - GAP, height);
            return new Frame(status, rules, players);
        }

        /** The top of a rule's label; its buttons sit one line under it. */
        int ruleY(int rule) {
            return rules.y() + BODY_TOP + rule * RULE_H;
        }

        int buttonsY(int rule) {
            return ruleY(rule) + LINE;
        }

        int footnoteY() {
            return ruleY(2);
        }

        int buttonWidth() {
            return Math.max(20, (rules.width() - PAD * 2 - BUTTON_GAP * (CHOICES.length - 1)) / CHOICES.length);
        }

        int buttonX(int index) {
            return rules.x() + PAD + index * (buttonWidth() + BUTTON_GAP);
        }
    }

    /** One row of answers per rule: who may run tasks, then who may use the omniscient modes. */
    private final List<Button> runButtons = new ArrayList<>();
    private final List<Button> cheatButtons = new ArrayList<>();

    public ServerTab() {
        super(Component.literal(Lang.get("lune.gui.server.title")));
        for (ServerRules.Who who : CHOICES) {
            runButtons.add(add(Button.builder(Component.literal(label(who)), button -> choose(true, who))
                    .size(60, BUTTON_H).build()));
        }
        for (ServerRules.Who who : CHOICES) {
            cheatButtons.add(add(Button.builder(Component.literal(label(who)), button -> choose(false, who))
                    .size(60, BUTTON_H).build()));
        }
        sync();
    }

    private static String label(ServerRules.Who who) {
        return switch (who) {
            case EVERYONE -> Lang.get("lune.gui.server.who.everyone");
            case OPERATORS -> Lang.get("lune.gui.server.who.operators");
            case NOBODY -> Lang.get("lune.gui.server.who.nobody");
        };
    }

    /**
     * Asks the server for one answer to one rule. The other rule is left out of the request, so an
     * operator changing it at the same moment keeps what they chose.
     */
    private static void choose(boolean run, ServerRules.Who who) {
        RulesPayload heard = ServerAccess.heard();
        if (heard == null || !heard.mayEdit()) {
            return;
        }
        ServerRules.Who current = run ? heard.rules().run() : heard.rules().cheats();
        if (who != current) {
            ServerAccess.propose(run ? who : null, run ? null : who);
        }
    }

    /** The buttons are there once the server has said its rules, and pressable by those who may. */
    private void sync() {
        RulesPayload heard = shownRules();
        boolean editable = heard != null && heard.mayEdit();
        for (List<Button> row : List.of(runButtons, cheatButtons)) {
            for (Button button : row) {
                button.visible = heard != null;
                button.active = editable;
            }
        }
    }

    /** The rules the tab has to show, or null when the server has none to show. */
    private static RulesPayload shownRules() {
        ServerAccess.Standing standing = ServerAccess.standing(Minecraft.getInstance());
        return standing == ServerAccess.Standing.NO_LUNE || standing == ServerAccess.Standing.WAITING
                ? null : ServerAccess.heard();
    }

    @Override
    public void tick() {
        sync();
    }

    @Override
    protected void layout(ScreenRectangle area) {
        sync();
        Frame frame = Frame.of(area.left(), area.top(), area.right(), area.bottom());
        for (int index = 0; index < CHOICES.length; index++) {
            place(runButtons.get(index), frame, 0, index);
            place(cheatButtons.get(index), frame, 1, index);
        }
    }

    private static void place(Button button, Frame frame, int rule, int index) {
        button.setPosition(frame.buttonX(index), frame.buttonsY(rule));
        button.setSize(frame.buttonWidth(), BUTTON_H);
    }

    @Override
    public void extractTabBackground(GuiGraphicsExtractor extractor) {
        Frame frame = Frame.of(area.left(), area.top(), area.right(), area.bottom());
        for (Card card : List.of(frame.status(), frame.rules(), frame.players())) {
            if (card.height() > TITLE_H) {
                LuneScreen.panel(extractor, card.x(), card.y(), card.width(), card.height());
            }
        }
    }

    @Override
    public void extractTabRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                      float partialTick) {
        Frame frame = Frame.of(area.left(), area.top(), area.right(), area.bottom());
        ServerAccess.Standing standing = ServerAccess.standing(Minecraft.getInstance());
        RulesPayload heard = shownRules();
        drawStatus(extractor, frame.status(), standing, ServerAccess.heard());
        drawRules(extractor, frame, standing, heard);
        if (frame.players().height() > TITLE_H) {
            drawPlayers(extractor, frame.players(), standing, heard);
        }
    }

    /** Where the player stands: the server's name for itself as the title, and the verdict under it. */
    private static void drawStatus(GuiGraphicsExtractor extractor, Card card, ServerAccess.Standing standing,
                                   RulesPayload heard) {
        header(extractor, card, switch (standing) {
            case OWN_WORLD -> Lang.get("lune.gui.server.own_world");
            case NO_LUNE -> Lang.get("lune.gui.server.no_lune");
            case WAITING -> Lang.get("lune.gui.server.waiting");
            case ALLOWED, REFUSED -> Lang.get("lune.gui.server.runs_lune", heard == null ? "" : heard.version());
        });
        int y = card.y() + BODY_TOP;
        switch (standing) {
            case OWN_WORLD -> paragraph(extractor, card, y, Lang.get("lune.gui.server.own_world_detail"),
                    Accessibility.dim());
            case NO_LUNE -> paragraph(extractor, card, y, Lang.get("lune.gui.server.no_lune_detail"),
                    Accessibility.dim());
            case WAITING -> paragraph(extractor, card, y, Lang.get("lune.gui.server.waiting_detail"),
                    Accessibility.dim());
            case ALLOWED -> y = paragraph(extractor, card, y, Accessibility.marked(
                    Lang.get("lune.gui.server.allowed_detail"), Accessibility.Mark.GOOD),
                    Accessibility.colour(Accessibility.Mark.GOOD));
            case REFUSED -> {
                String refusal = ServerAccess.refusal(Minecraft.getInstance());
                y = paragraph(extractor, card, y, Accessibility.marked(refusal == null ? "" : refusal,
                        Accessibility.Mark.BAD), Accessibility.colour(Accessibility.Mark.BAD));
            }
        }
        if (heard != null && (standing == ServerAccess.Standing.ALLOWED || standing == ServerAccess.Standing.REFUSED)) {
            // The other rule's answer, for this player: the cheats are a rule of their own.
            paragraph(extractor, card, y + 4, Lang.get(heard.mayCheat()
                    ? "lune.gui.server.cheats_allowed" : "lune.gui.server.cheats_refused"), Accessibility.dim());
        }
    }

    /** The two rules with their answers, or why there are none to show. */
    private static void drawRules(GuiGraphicsExtractor extractor, Frame frame, ServerAccess.Standing standing,
                                  RulesPayload heard) {
        Card card = frame.rules();
        header(extractor, card, Lang.get("lune.gui.server.card.rules"));
        if (heard == null) {
            paragraph(extractor, card, card.y() + BODY_TOP, Lang.get(standing == ServerAccess.Standing.WAITING
                    ? "lune.gui.server.waiting" : "lune.gui.server.no_rules"), Accessibility.dim());
            return;
        }
        line(extractor, card, frame.ruleY(0), Lang.get("lune.gui.server.rule.run"), LuneScreen.TEXT);
        line(extractor, card, frame.ruleY(1), Lang.get("lune.gui.server.rule.cheats"), LuneScreen.TEXT);
        underline(extractor, frame, 0, heard.rules().run());
        underline(extractor, frame, 1, heard.rules().cheats());
        paragraph(extractor, card, frame.footnoteY(), Lang.get(heard.mayEdit()
                ? "lune.gui.server.editable" : "lune.gui.server.read_only"), Accessibility.dim());
    }

    /** The accent bar under a rule's current answer, the way the Main tab marks its chosen view. */
    private static void underline(GuiGraphicsExtractor extractor, Frame frame, int rule, ServerRules.Who chosen) {
        int x = frame.buttonX(chosen.ordinal());
        int y = frame.buttonsY(rule) + BUTTON_H - 1;
        extractor.fill(x, y, x + frame.buttonWidth(), y + 2, LuneScreen.ACCENT);
    }

    /** Who has Lune here, for those who may change the rules; for anybody else, why they cannot see. */
    private static void drawPlayers(GuiGraphicsExtractor extractor, Card card, ServerAccess.Standing standing,
                                    RulesPayload heard) {
        int y = card.y() + BODY_TOP;
        if (heard != null && heard.mayEdit()) {
            header(extractor, card, Lang.get("lune.gui.server.players", heard.players().size()));
            List<String> names = new ArrayList<>(heard.players().size());
            for (RulesPayload.LunePlayer player : heard.players()) {
                names.add(Lang.get("lune.gui.server.player", player.name(), player.version()));
            }
            var font = Minecraft.getInstance().font;
            for (String row : rows(names, font::width, card.textWidth())) {
                line(extractor, card, y, row, LuneScreen.TEXT);
                y += LINE;
            }
            return;
        }
        header(extractor, card, Lang.get("lune.gui.server.card.players"));
        if (heard != null) {
            paragraph(extractor, card, y, Lang.get("lune.gui.server.players_hidden"), Accessibility.dim());
        } else if (standing == ServerAccess.Standing.NO_LUNE) {
            paragraph(extractor, card, y, Lang.get("lune.gui.server.players_unknown"), Accessibility.dim());
        }
    }

    /**
     * The players, as many to a line as fit, broken only between two of them: a name is never
     * split from its version, and never across lines.
     */
    static List<String> rows(List<String> entries, ToIntFunction<String> width, int room) {
        String separator = " · ";
        List<String> rows = new ArrayList<>();
        StringBuilder row = new StringBuilder();
        for (String entry : entries) {
            String longer = row.isEmpty() ? entry : row + separator + entry;
            if (row.isEmpty() || width.applyAsInt(longer) <= room) {
                row.setLength(0);
                row.append(longer);
            } else {
                rows.add(row.toString());
                row.setLength(0);
                row.append(entry);
            }
        }
        if (!row.isEmpty()) {
            rows.add(row.toString());
        }
        return rows;
    }

    // --- drawing, as the About tab draws ------------------------------------------------------

    private static void header(GuiGraphicsExtractor extractor, Card card, String title) {
        extractor.fill(card.x() + 1, card.y() + 1, card.x() + card.width() - 1, card.y() + TITLE_H, PANEL_HEADER);
        extractor.textRenderer().accept(card.x() + PAD, card.y() + 5,
                Component.literal(fit(title, card.textWidth())).withColor(LuneScreen.ACCENT));
    }

    private static void line(GuiGraphicsExtractor extractor, Card card, int y, String text, int colour) {
        if (card.holds(y)) {
            extractor.textRenderer().accept(card.x() + PAD, y,
                    Component.literal(fit(text, card.textWidth())).withColor(colour));
        }
    }

    /** Wrapped text, stopping at the card's last line that fits; the y after it. */
    private static int paragraph(GuiGraphicsExtractor extractor, Card card, int y, String text, int colour) {
        List<FormattedCharSequence> lines = Minecraft.getInstance().font
                .split(Component.literal(text).withColor(colour), card.textWidth());
        for (FormattedCharSequence wrapped : lines) {
            if (!card.holds(y)) {
                return y;
            }
            extractor.textRenderer().accept(card.x() + PAD, y, wrapped);
            y += LINE;
        }
        return y;
    }

    private static String fit(String value, int maxWidth) {
        var font = Minecraft.getInstance().font;
        if (maxWidth <= 0 || font.width(value) <= maxWidth) {
            return value;
        }
        return font.plainSubstrByWidth(value, Math.max(0, maxWidth - font.width("...")), false) + "...";
    }
}
