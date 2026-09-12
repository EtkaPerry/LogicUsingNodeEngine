package com.etka.lune.client.gui;

import com.etka.lune.util.Lang;
import com.etka.lune.Links;
import com.etka.lune.bot.BotEngine;
import com.etka.lune.config.Terms;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The first-run acknowledgement: {@link Terms} put in front of the player, one tick box per point.
 *
 * <p>Deliberately a plain vanilla screen rather than one of Lune's own panels. It is the one screen
 * a player sees before they have agreed to anything, so it borrows nothing from the mod's interface
 * and draws at the game's own GUI scale - there is no version of this that renders oddly because
 * Lune's menu scaling was still deciding what size to be.</p>
 *
 * <p>Accept is dead until every box is ticked. That is the whole point of the screen: a single "OK"
 * is a thing you dismiss, three boxes are a thing you read.</p>
 */
public class TermsScreen extends Screen {

    /** Wide enough that no line wraps more than twice, narrow enough to stay readable. */
    private static final int MAX_CONTENT_WIDTH = 440;
    private static final int SIDE_MARGIN = 20;
    /** Breathing room between the text and the edge of the panel behind it. */
    private static final int CARD_PADDING = 14;

    /**
     * Every gap on this page, written out.
     *
     * <p>The rows carry no spacing of their own and each one states the air above it instead. That
     * is what separates a point from the point below it while keeping its own line tucked under its
     * label - a single uniform spacing cannot do both, and the version that tried read as a wall of
     * text with tick boxes in it.</p>
     *
     * <p>The budget these are spent against is real: at the largest GUI scale the game will pick
     * there is very little height, and terms pushed off the bottom edge are terms nobody agreed to.
     * Which is why the wording is short. Short text is what pays for the space around it.</p>
     */
    private static final int GAP_AFTER_TITLE = 6;
    private static final int GAP_BEFORE_POINT = 8;
    private static final int GAP_BEFORE_DETAIL = 2;
    private static final int GAP_BEFORE_FOOTNOTE = 6;
    private static final int BUTTON_GAP = 8;
    private static final int BUTTON_HEIGHT = 20;

    private final LinearLayout layout = LinearLayout.vertical().spacing(0);
    private final List<Checkbox> boxes = new ArrayList<>();
    /** Text that spans the whole column: the opening paragraph and the closing note. */
    private final List<MultiLineTextWidget> paragraphs = new ArrayList<>();
    /**
     * Text set in under a tick box, so it lines up with the label above it rather than with the
     * box. It is the same column narrowed by the indent, and has to be re-narrowed by exactly that
     * much whenever the column changes - kept apart from {@link #paragraphs} so it cannot quietly
     * be handed the full width and hang off the right-hand side.
     */
    private final List<MultiLineTextWidget> details = new ArrayList<>();

    /** Where declining goes back to; null means the game itself. */
    private final Screen returnTo;
    /** What accepting opens instead of {@link #returnTo}; null to go back the same way. */
    private final Runnable onAccept;

    private Button acceptButton;
    /** The panel drawn behind the text, sized to whatever the layout arranged itself into. */
    private ScreenRectangle card = ScreenRectangle.empty();

    /**
     * @param returnTo screen to return to when the player declines or closes, or null for the game
     * @param onAccept what to do once accepted, or null to return the same way as declining
     */
    public TermsScreen(Screen returnTo, Runnable onAccept) {
        super(Component.literal(Terms.TITLE));
        this.returnTo = returnTo;
        this.onAccept = onAccept;
    }

    @Override
    protected void init() {
        super.init();
        int content = contentWidth();
        int indent = Checkbox.getBoxSize(this.font) + 4;

        layout.defaultCellSetting().alignHorizontallyLeft();
        layout.addChild(new StringWidget(this.title, this.font),
                settings -> settings.alignHorizontallyCenter());
        // Centred under a centred title, and dim: the opening line sets the scene, the tick boxes
        // are the part being agreed to, and they should not look like the same weight of text.
        paragraphs.add(layout.addChild(
                new MultiLineTextWidget(Component.literal(Terms.INTRO)
                        .withColor(LuneScreen.TEXT_DIM), this.font)
                        .setMaxWidth(content).setCentered(true),
                settings -> settings.alignHorizontallyCenter().paddingTop(GAP_AFTER_TITLE)));

        for (Terms.Point point : Terms.POINTS) {
            boxes.add(layout.addChild(Checkbox.builder(Component.literal(point.label()), this.font)
                            .maxWidth(content - indent)
                            .onValueChange((box, ticked) -> updateAcceptButton())
                            .build(),
                    settings -> settings.paddingTop(GAP_BEFORE_POINT)));
            details.add(layout.addChild(
                    new MultiLineTextWidget(Component.literal(point.detail())
                            .withColor(LuneScreen.TEXT_DIM), this.font)
                            .setMaxWidth(content - indent),
                    settings -> settings.paddingLeft(indent).paddingTop(GAP_BEFORE_DETAIL)));
        }

        paragraphs.add(layout.addChild(new MultiLineTextWidget(
                        Component.literal(Terms.FOOTNOTE).withColor(LuneScreen.TEXT_DIM), this.font)
                        .setMaxWidth(content).setCentered(true),
                settings -> settings.alignHorizontallyCenter().paddingTop(GAP_BEFORE_FOOTNOTE)));

        // All three buttons together at the foot, in the order they are used: read it, then decide.
        // The license is the only thing on this screen a player cannot check for themselves without
        // leaving the game, so the way to read it belongs on the screen that asks them to accept it.
        layout.addChild(Button.builder(Component.literal(Lang.get("lune.gui.about.read_full_license")),
                        ConfirmLinkScreen.confirmLink(this, Links.LICENSE))
                        .size(Math.min(200, content), BUTTON_HEIGHT).build(),
                settings -> settings.alignHorizontallyCenter().paddingTop(GAP_BEFORE_POINT + 2));

        LinearLayout buttons = layout.addChild(LinearLayout.horizontal().spacing(BUTTON_GAP),
                settings -> settings.alignHorizontallyCenter().paddingTop(BUTTON_GAP / 2));
        int decisionWidth = Math.min(150, (content - BUTTON_GAP) / 2);
        acceptButton = buttons.addChild(Button.builder(Component.literal(Lang.get("lune.gui.terms.i_accept")),
                button -> accept()).size(decisionWidth, BUTTON_HEIGHT).build());
        buttons.addChild(Button.builder(Component.literal(Lang.get("lune.gui.mascot.now")),
                button -> onClose()).size(decisionWidth, BUTTON_HEIGHT).build());

        updateAcceptButton();
        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    @Override
    protected void repositionElements() {
        // Widths are recomputed rather than only set once: a window dragged narrower re-arranges
        // this screen without rebuilding it, and text that still believes in the old width spills
        // off the side of the very screen that is asking to be read.
        int content = contentWidth();
        int indent = Checkbox.getBoxSize(this.font) + 4;
        for (Checkbox box : boxes) {
            box.adjustWidth(content - indent, this.font);
        }
        for (MultiLineTextWidget detail : details) {
            detail.setMaxWidth(content - indent);
        }
        for (MultiLineTextWidget paragraph : paragraphs) {
            paragraph.setMaxWidth(content);
        }
        layout.arrangeElements();
        FrameLayout.centerInRectangle(layout, this.getRectangle());
        card = new ScreenRectangle(layout.getX() - CARD_PADDING, layout.getY() - CARD_PADDING,
                layout.getWidth() + CARD_PADDING * 2, layout.getHeight() + CARD_PADDING * 2);
    }

    /**
     * Dims the world and puts a panel behind the text.
     *
     * <p>Vanilla leaves an in-world screen looking straight at the world, which for a wall of small
     * grey type over a midday sky is close to unreadable - and this is the one screen in the mod
     * where "I could not really read it" is a problem rather than an annoyance. The dim is vanilla's
     * own in-game one; the panel is the same one every Lune tab draws, so the grey sits on the
     * background it was picked for.</p>
     */
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        extractTransparentBackground(graphics);
        LuneScreen.panel(graphics, card.left(), card.top(), card.width(), card.height());
    }

    private int contentWidth() {
        return Math.max(120, Math.min(MAX_CONTENT_WIDTH, this.width - SIDE_MARGIN * 2));
    }

    private void updateAcceptButton() {
        acceptButton.active = boxes.stream().allMatch(Checkbox::selected);
        acceptButton.setTooltip(acceptButton.active ? null
                : Tooltip.create(Component.literal(Lang.get("lune.gui.terms.tick_every_box_continue"))));
    }

    /**
     * Pauses a singleplayer world while it is being read - unless the bot is working in that world.
     *
     * <p>This is a screen someone is meant to stop and read, and reading it should not be something
     * a creeper can interrupt. When it is reached the ordinary way nothing is running, because
     * nothing <em>can</em> run before it is accepted, so the pause costs nothing.</p>
     *
     * <p>The exception is the About tab reopening it over a task in progress. Pausing there would
     * freeze the world the bot is halfway through a job in, which is the same reason
     * {@link LuneScreen} never pauses. On a server neither case pauses anything, and the terms are
     * the same either way.</p>
     */
    @Override
    public boolean isPauseScreen() {
        return !BotEngine.get().isDriving();
    }

    private void accept() {
        Terms.accept();
        if (onAccept != null) {
            onAccept.run();
        } else {
            this.minecraft.setScreen(returnTo);
        }
    }

    /**
     * Declining, and every other way out of this screen - Escape included.
     *
     * <p>Leaving is allowed on purpose. There is no version of this mod that holds someone's game
     * hostage until they agree to something; what refusing costs is the panel and the bot, which is
     * exactly what the terms are about. Nothing is recorded either way, so pressing J again is all
     * it takes to change your mind.</p>
     */
    @Override
    public void onClose() {
        this.minecraft.setScreen(returnTo);
    }
}
