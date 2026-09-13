package com.etka.lune.client.gui.tab;

import com.etka.lune.util.Lang;
import com.etka.lune.Links;
import com.etka.lune.client.gui.LuneScreen;
import com.etka.lune.client.gui.LuneTab;
import com.etka.lune.client.gui.TermsScreen;
import com.etka.lune.config.Terms;
import com.etka.lune.platform.BuildInfo;
import com.etka.lune.platform.Services;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * What Lune is, what you agreed to, and where everything came from.
 *
 * <p>The four things here were homeless: the terms had no way back once accepted, the license
 * existed only as a file inside the jar, the version - the first thing anyone is asked for in a bug
 * report - was written nowhere a player could see, and there was nowhere to say who made it. None
 * of them belong in Config, which is a list of switches.</p>
 *
 * <p>Four cards rather than one column of prose, because the four answer four unrelated questions
 * and a reader is here for one of them. It is the same card the dashboard uses - panel, title bar,
 * body inset from both - so this tab looks like the rest of the mod instead of like a readme that
 * wandered in.</p>
 */
public class AboutTab extends LuneTab {

    private static final int MARGIN = 10;
    private static final int GAP = 8;
    /** Wider than this and the cards stop growing, because a 900px line of prose is unreadable. */
    private static final int MAX_WIDTH = 820;

    private static final int TITLE_H = DashboardFrame.TITLE_H;
    private static final int PANEL_HEADER = 0xD0222D3A;
    /** Left and right inset of everything inside a card, matching the dashboard's title text. */
    private static final int PAD = 9;
    private static final int BODY_TOP = TITLE_H + 8;
    private static final int LINE = 12;
    private static final int BUTTON_H = 18;
    private static final int BUTTON_GAP = 6;
    /** Column the values line up in on the identity card, so the labels read as a column. */
    private static final int LABEL_COLUMN = 74;

    private static final String TAGLINE =
            Lang.get("lune.gui.about.client_side_automation_bot_wire_job")
                    + Lang.get("lune.gui.about.something_else");

    /**
     * The gist, not the terms. Four lines that answer "may I?" for the things people actually ask,
     * with the button underneath for the version that counts - a card is the wrong place to
     * reproduce a license, and a card that tries is the one nobody reads.
     */
    private static final String LICENSE_SUMMARY =
            "Personal use. Play with it, read the source, change it for yourself, fork it on "
                    + "GitHub, stream it. Free Modrinth modpacks may list it, linking to the "
                    + "official project rather than bundling a copy. No other redistribution, no "
                    + "derivatives. No warranty.";

    private static final String CREDITS_NOTE =
            "Lune's soul and every tactic she runs are her author's. The world she plays in is "
                    + "Mojang's.";

    private final Button reviewTermsButton;
    private final Button licenseButton;
    private final Button repositoryButton;
    private final Button issuesButton;

    public AboutTab() {
        super(Component.literal(Lang.get("lune.gui.about.title")));
        reviewTermsButton = add(Button.builder(Component.literal(Lang.get("lune.gui.about.review_terms")),
                button -> openTerms()).size(120, BUTTON_H).build());
        licenseButton = add(Button.builder(Component.literal(Lang.get("lune.gui.about.read_full_license")),
                openLink(Links.LICENSE)).size(120, BUTTON_H).build());
        repositoryButton = add(Button.builder(Component.literal(Lang.get("lune.gui.about.repository")),
                openLink(Links.REPOSITORY)).size(120, BUTTON_H).build());
        issuesButton = add(Button.builder(Component.literal(Lang.get("lune.gui.about.report_issue")),
                openLink(Links.ISSUES)).size(120, BUTTON_H).build());
    }

    /**
     * Reopens the acknowledgement over the panel, and comes back to it afterwards.
     *
     * <p>Something the player agreed to and then can never read again is not much of an agreement.
     * The panel underneath is handed back on the way out, so this reads as a page rather than as
     * losing your place.</p>
     */
    private void openTerms() {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new TermsScreen(mc.screen, null));
    }

    /** Vanilla's own link confirmation: it shows the address, and returns here either way. */
    private static Button.OnPress openLink(String url) {
        return button -> ConfirmLinkScreen.confirmLinkNow(Minecraft.getInstance().screen, url);
    }

    @Override
    protected void layout(ScreenRectangle area) {
        Frame frame = Frame.of(area);
        place(reviewTermsButton, frame.terms(), 0, 1);
        place(licenseButton, frame.license(), 0, 1);
        place(repositoryButton, frame.credits(), 0, 2);
        place(issuesButton, frame.credits(), 1, 2);
    }

    /**
     * Puts a button on the bottom row of its own card, sharing that row with {@code of} others.
     *
     * <p>Anchored to the card rather than placed after the text, so a paragraph that wraps to one
     * line more on a narrow panel cannot walk a button off the bottom of the card it belongs to.</p>
     */
    private static void place(Button button, Card card, int index, int of) {
        int available = card.width() - PAD * 2 - BUTTON_GAP * (of - 1);
        int width = Math.max(20, available / of);
        button.setPosition(card.x() + PAD + index * (width + BUTTON_GAP),
                card.y() + card.height() - PAD - BUTTON_H);
        button.setSize(width, BUTTON_H);
    }

    @Override
    public void extractTabBackground(GuiGraphicsExtractor extractor) {
        Frame frame = Frame.of(area);
        for (Card card : frame.all()) {
            LuneScreen.panel(extractor, card.x(), card.y(), card.width(), card.height());
        }
    }

    @Override
    public void extractTabRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                      float partialTick) {
        Frame frame = Frame.of(area);
        drawIdentity(extractor, frame.identity());
        drawTerms(extractor, frame.terms());
        drawLicense(extractor, frame.license());
        drawCredits(extractor, frame.credits());
    }

    private void drawIdentity(GuiGraphicsExtractor extractor, Card card) {
        header(extractor, card, Lang.get("lune.gui.about.lune"));
        int y = card.y() + BODY_TOP;
        y = line(extractor, card, y, Lang.get("lune.gui.about.logic_using_node_engine"), LuneScreen.TEXT);
        y = paragraph(extractor, card, y, TAGLINE) + 4;
        y = field(extractor, card, y, Lang.get("lune.gui.about.version"), BuildInfo.version());
        y = field(extractor, card, y, Lang.get("lune.gui.about.minecraft"), BuildInfo.minecraftVersion());
        field(extractor, card, y, Lang.get("lune.gui.about.loader"), Services.PLATFORM.getPlatformName());
    }

    private void drawTerms(GuiGraphicsExtractor extractor, Card card) {
        header(extractor, card, Lang.get("lune.gui.about.what_you_accepted"));
        int y = card.y() + BODY_TOP;
        for (Terms.Point point : Terms.POINTS) {
            y = line(extractor, card, y, "• " + point.label(), LuneScreen.TEXT);
        }
        boolean accepted = Terms.accepted();
        line(extractor, card, y + 4, accepted
                        ? Lang.get("lune.gui.about.accepted_install")
                        : Lang.get("lune.gui.about.accepted_yet_lune_run"),
                accepted ? LuneScreen.TEXT_DIM : LuneScreen.ACCENT);
    }

    private void drawLicense(GuiGraphicsExtractor extractor, Card card) {
        header(extractor, card, Lang.get("lune.gui.about.license"));
        paragraph(extractor, card, card.y() + BODY_TOP, LICENSE_SUMMARY);
    }

    private void drawCredits(GuiGraphicsExtractor extractor, Card card) {
        header(extractor, card, Lang.get("lune.gui.about.credits"));
        int y = card.y() + BODY_TOP;
        y = line(extractor, card, y, Lang.get("lune.gui.about.made_by", BuildInfo.author()), LuneScreen.TEXT) + 4;
        paragraph(extractor, card, y, CREDITS_NOTE);
    }

    private static void header(GuiGraphicsExtractor extractor, Card card, String title) {
        extractor.fill(card.x() + 1, card.y() + 1, card.x() + card.width() - 1,
                card.y() + TITLE_H, PANEL_HEADER);
        extractor.textRenderer().accept(card.x() + PAD, card.y() + 5,
                Component.literal(fit(title, card.textWidth())).withColor(LuneScreen.ACCENT));
    }

    /** A dim label and its value, on one line. */
    private static int field(GuiGraphicsExtractor extractor, Card card, int y, String label,
                             String value) {
        if (!card.holds(y)) {
            return y;
        }
        var text = extractor.textRenderer();
        text.accept(card.x() + PAD, y, Component.literal(label).withColor(LuneScreen.TEXT_DIM));
        text.accept(card.x() + PAD + LABEL_COLUMN, y,
                Component.literal(fit(value, card.textWidth() - LABEL_COLUMN))
                        .withColor(LuneScreen.TEXT));
        return y + LINE;
    }

    /** One line, trimmed to the card rather than drawn over its border. */
    private static int line(GuiGraphicsExtractor extractor, Card card, int y, String body,
                            int colour) {
        if (!card.holds(y)) {
            return y;
        }
        extractor.textRenderer().accept(card.x() + PAD, y,
                Component.literal(fit(body, card.textWidth())).withColor(colour));
        return y + LINE;
    }

    /** Wrapped body text, stopping at the card's last usable line, and the y after it. */
    private static int paragraph(GuiGraphicsExtractor extractor, Card card, int y, String body) {
        List<FormattedCharSequence> lines = Minecraft.getInstance().font
                .split(Component.literal(body).withColor(LuneScreen.TEXT_DIM), card.textWidth());
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
        return font.plainSubstrByWidth(value, Math.max(0, maxWidth - font.width("...")), false)
                + "...";
    }

    /** One card, and the two questions every drawing helper asks it. */
    private record Card(int x, int y, int width, int height, boolean hasButtonRow) {

        /** Width available to text inside the card's padding. */
        int textWidth() {
            return Math.max(1, width - PAD * 2);
        }

        /** Whether a line starting at {@code y} still fits above the border - or the button row. */
        boolean holds(int y) {
            int floor = y() + height() - PAD - (hasButtonRow ? BUTTON_H + 4 : 0);
            return y + 9 <= floor;
        }
    }

    /**
     * Two columns by two rows, filling the tab.
     *
     * <p>Not a stacked fallback at narrow widths, unlike the dashboard: these cards hold a few
     * short lines each, so a narrow column costs them one wrapped line rather than making them
     * unreadable, and four cards in a row down a short screen would be worse than either.</p>
     */
    private record Frame(Card identity, Card terms, Card license, Card credits) {

        static Frame of(ScreenRectangle area) {
            int available = Math.max(80, area.width() - MARGIN * 2);
            int width = Math.min(MAX_WIDTH, available);
            int left = area.left() + Math.max(MARGIN, (area.width() - width) / 2);
            int top = area.top() + MARGIN;
            int height = Math.max(80, area.height() - MARGIN * 2);
            int columnWidth = Math.max(40, (width - GAP) / 2);
            int rowHeight = Math.max(40, (height - GAP) / 2);
            int rightX = left + columnWidth + GAP;
            int bottomY = top + rowHeight + GAP;
            return new Frame(
                    new Card(left, top, columnWidth, rowHeight, false),
                    new Card(rightX, top, columnWidth, rowHeight, true),
                    new Card(left, bottomY, columnWidth, rowHeight, true),
                    new Card(rightX, bottomY, columnWidth, rowHeight, true));
        }

        List<Card> all() {
            return List.of(identity, terms, license, credits);
        }
    }
}
