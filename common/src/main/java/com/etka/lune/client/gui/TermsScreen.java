package com.etka.lune.client.gui;

import com.etka.lune.compat.Screens;
import com.etka.lune.util.Lang;
import com.etka.lune.util.LuneLanguages;
import com.etka.lune.Links;
import com.etka.lune.bot.BotEngine;
import com.etka.lune.bot.command.Param;
import com.etka.lune.config.BotConfig;
import com.etka.lune.config.Terms;
import com.mojang.blaze3d.platform.Window;
import java.net.URI;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The first-run acknowledgement: {@link Terms} put in front of the player, one tick box per point.
 *
 * <p>Accept is dead until every box is ticked. That is the whole point of the screen: a single "OK"
 * is a thing you dismiss, three boxes are a thing you read.</p>
 *
 * <h2>Why this page carries its own settings</h2>
 *
 * <p>Every other Lune surface can say "turn it up in the Config tab". This one cannot: the Config
 * tab is behind the panel, and the panel is behind this page. So a player who cannot read this
 * page has no route to the setting that would fix it, and the mod has locked them out with a
 * legal notice. The row of controls at the top is the answer - text size, face, contrast and
 * language, all applied immediately and saved, all reachable before agreeing to anything.</p>
 *
 * <h2>Why it cannot run out of room</h2>
 *
 * <p>Three guards, because the failure mode here is not a cramped page but an Accept button below
 * the bottom edge - which is a mod nobody can turn on. The page draws at its own scale
 * ({@link UiScale#termsScale}), the text column scrolls when it still does not fit, and the
 * buttons live in a footer that is laid out from the bottom edge upwards and never scrolls. Any
 * one of the three would usually do; the combination is what makes "I could not press Accept"
 * impossible rather than unlikely.</p>
 */
public class TermsScreen extends ScaledScreen {

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
     */
    private static final int GAP_AFTER_TITLE = 6;
    private static final int GAP_BEFORE_POINT = 8;
    private static final int GAP_BEFORE_DETAIL = 2;
    private static final int GAP_BEFORE_FOOTNOTE = 6;
    private static final int BUTTON_GAP = 8;
    private static final int BUTTON_HEIGHT = 20;
    /** The accessibility row: four small controls and the line that says what they are set to. */
    private static final int TOOL_HEIGHT = 14;
    private static final int TOOL_GAP = 4;
    private static final int STEP_WIDTH = 16;

    private LinearLayout layout = LinearLayout.vertical().spacing(0);
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
    /** The footer and the tool row: interactive, but drawn by hand outside the scroll clip. */
    private final List<AbstractWidget> pinned = new ArrayList<>();

    /** Where declining goes back to; null means the game itself. */
    private final Screen returnTo;
    /** What accepting opens instead of {@link #returnTo}; null to go back the same way. */
    private final Runnable onAccept;

    private Button acceptButton;
    private Button declineButton;
    private Button licenseButton;
    /** The accessibility row, in the order it is laid out left to right. */
    private final List<AbstractWidget> tools = new ArrayList<>();
    /** The panel drawn behind the text, sized to whatever the layout arranged itself into. */
    private ScreenRectangle card = ScreenRectangle.empty();
    /** The part of the card the text column is allowed to occupy, and how far it has scrolled. */
    private ScreenRectangle viewport = ScreenRectangle.empty();
    private int scroll;
    private int scrollMax;
    /**
     * Which boxes were ticked before a settings change rebuilt the page.
     *
     * <p>Changing the text size re-lays every widget out, which means building new tick boxes.
     * Losing three ticks because somebody made the text bigger would teach them not to.</p>
     */
    private boolean[] ticked = new boolean[Terms.POINTS.size()];
    /**
     * A settings change asked for during widget dispatch, applied on the next tick.
     *
     * <p>Rebuilding the widget list from inside a button's own handler mutates the collection that
     * is being iterated to deliver the click.</p>
     */
    private boolean rebuildPending;

    /**
     * @param returnTo screen to return to when the player declines or closes, or null for the game
     * @param onAccept what to do once accepted, or null to return the same way as declining
     */
    public TermsScreen(Screen returnTo, Runnable onAccept) {
        super(Component.literal(Terms.title()));
        this.returnTo = returnTo;
        this.onAccept = onAccept;
    }

    /**
     * This page asks for far less room than a tab, and honours the text size on top.
     *
     * <p>It is one short column, so it stays legible at a scale where the dashboard would not fit
     * at all - which is the whole reason it is allowed its own answer.</p>
     */
    @Override
    protected int scaleFor(Window window) {
        return UiScale.termsScale(window);
    }

    @Override
    protected void init() {
        applyMenuScale();
        layout = LinearLayout.vertical().spacing(0);
        boxes.clear();
        paragraphs.clear();
        details.clear();
        pinned.clear();
        // Cleared too: this runs again on every text-size change, and a row that accumulated its
        // previous generation would lay five more controls out on top of the five already there.
        tools.clear();

        int content = contentWidth();
        int indent = Checkbox.getBoxSize(this.font) + 4;

        layout.defaultCellSetting().alignHorizontallyLeft();
        layout.addChild(new StringWidget(Accessibility.text(Terms.title()), this.font),
                settings -> settings.alignHorizontallyCenter());
        // Centred under a centred title, and dim: the opening line sets the scene, the tick boxes
        // are the part being agreed to, and they should not look like the same weight of text.
        paragraphs.add(layout.addChild(
                new MultiLineTextWidget(Accessibility.text(Terms.intro(), Accessibility.dim()),
                        this.font).setMaxWidth(content).setCentered(true),
                settings -> settings.alignHorizontallyCenter().paddingTop(GAP_AFTER_TITLE)));

        for (int index = 0; index < Terms.POINTS.size(); index++) {
            Terms.Point point = Terms.POINTS.get(index);
            int slot = index;
            boxes.add(layout.addChild(Checkbox.builder(Accessibility.text(point.label()), this.font)
                            .maxWidth(content - indent)
                            .selected(ticked[slot])
                            .onValueChange((box, isTicked) -> {
                                ticked[slot] = isTicked;
                                updateAcceptButton();
                            })
                            .build(),
                    settings -> settings.paddingTop(GAP_BEFORE_POINT)));
            details.add(layout.addChild(
                    new MultiLineTextWidget(
                            Accessibility.text(point.detail(), Accessibility.dim()), this.font)
                            .setMaxWidth(content - indent),
                    settings -> settings.paddingLeft(indent).paddingTop(GAP_BEFORE_DETAIL)));
        }

        paragraphs.add(layout.addChild(new MultiLineTextWidget(
                        Accessibility.text(Terms.footnote(), Accessibility.dim()), this.font)
                        .setMaxWidth(content).setCentered(true),
                settings -> settings.alignHorizontallyCenter().paddingTop(GAP_BEFORE_FOOTNOTE)));

        layout.visitWidgets(this::addRenderableWidget);
        buildTools();
        buildFooter(content);
        updateAcceptButton();
        repositionElements();
    }

    /**
     * The four controls that make this page readable, and the only settings reachable from it.
     *
     * <p>Registered with {@code addWidget} rather than {@code addRenderableWidget}: they are
     * clickable, focusable and narrated like any other widget, but this screen draws them itself,
     * after the scroll clip has been lifted. Anything in the renderable list would be clipped to
     * the text column along with the text.</p>
     */
    private void buildTools() {
        BotConfig config = BotConfig.get();
        // The two steps come first, so they are the two that survive a column too narrow for the
        // whole row - they are also the two that fix the problem this row exists for.
        tool(Component.literal("A−"), "lune.gui.terms.text_smaller", button -> stepTextSize(-1));
        tool(Component.literal("A+"), "lune.gui.terms.text_larger", button -> stepTextSize(1));
        // Each of the rest is labelled with the value it currently holds rather than with what it
        // does. On a page nobody has read yet, a control that shows its own state needs no legend.
        tool(Accessibility.text(Param.Choice.optionLabel(config.luneFont)),
                "lune.gui.terms.font_tip", button -> {
                    config.luneFont = BotConfig.FONT_UNIFORM.equalsIgnoreCase(config.luneFont)
                            ? BotConfig.FONT_DEFAULT : BotConfig.FONT_UNIFORM;
                    applyAndRebuild();
                });
        tool(Accessibility.text(Lang.get(config.highContrast
                        ? "lune.gui.terms.contrast_on" : "lune.gui.terms.contrast_off")),
                "lune.gui.terms.contrast_tip", button -> {
                    config.highContrast = !config.highContrast;
                    applyAndRebuild();
                });
        // A page written in a language the player does not read is not a page they agreed to, and
        // following the game's setting is only right until it is not.
        tool(Accessibility.text(LuneLanguages.displayName(config.language)),
                "lune.gui.terms.language_tip", button -> {
                    List<String> codes = LuneLanguages.available();
                    int next = (codes.indexOf(config.language) + 1) % Math.max(1, codes.size());
                    config.language = codes.get(next);
                    Lang.select(config.language);
                    applyAndRebuild();
                });
    }

    private Button tool(Component label, String tipKey, Button.OnPress onPress) {
        Button button = Button.builder(label, onPress).size(STEP_WIDTH, TOOL_HEIGHT).build();
        button.setTooltip(Tooltip.create(Accessibility.text(Lang.get(tipKey))));
        addWidget(button);
        pinned.add(button);
        tools.add(button);
        return button;
    }

    private void buildFooter(int content) {
        // The license is the only thing on this screen a player cannot check for themselves without
        // leaving the game, so the way to read it belongs on the screen that asks them to accept it.
        licenseButton = Button.builder(
                        Accessibility.text(Lang.get("lune.gui.about.read_full_license")),
                        ConfirmLinkScreen.confirmLink(this, URI.create(Links.LICENSE)))
                .size(Math.min(200, content), BUTTON_HEIGHT).build();
        acceptButton = Button.builder(
                        Accessibility.text(Lang.get("lune.gui.terms.i_accept")),
                        button -> accept())
                .size(Math.min(150, content), BUTTON_HEIGHT).build();
        declineButton = Button.builder(
                        Accessibility.text(Lang.get("lune.gui.terms.decline")),
                        button -> onClose())
                .size(Math.min(150, content), BUTTON_HEIGHT).build();
        for (Button button : List.of(licenseButton, acceptButton, declineButton)) {
            addWidget(button);
            pinned.add(button);
        }
    }

    private void stepTextSize(int direction) {
        BotConfig config = BotConfig.get();
        int at = BotConfig.TEXT_SIZES.indexOf(config.luneTextSize);
        int next = Math.clamp((at < 0 ? 1 : at) + direction, 0, BotConfig.TEXT_SIZES.size() - 1);
        config.luneTextSize = BotConfig.TEXT_SIZES.get(next);
        applyAndRebuild();
    }

    /** Writes the setting out and asks for a rebuild once the click has finished being delivered. */
    private void applyAndRebuild() {
        BotConfig.get().save();
        rebuildPending = true;
    }

    @Override
    public void tick() {
        super.tick();
        if (rebuildPending || scaleIsStale()) {
            rebuildPending = false;
            rebuildWidgets();
        }
    }

    @Override
    protected void repositionElements() {
        applyMenuScale();
        if (acceptButton == null) {
            return;
        }
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

        int left = (this.width - content) / 2;
        int toolsY = SIDE_MARGIN / 2;
        layoutTools(left, toolsY, content);

        // From the bottom edge upwards, so the decision is placed before the reading is. A page
        // that lays its text out first and gives the buttons what is left is the page that loses
        // the buttons.
        int decisionWidth = Math.min(150, (content - BUTTON_GAP) / 2);
        int decisionY = this.height - SIDE_MARGIN / 2 - BUTTON_HEIGHT;
        int licenseWidth = Math.min(200, content);
        int licenseY = decisionY - BUTTON_GAP - BUTTON_HEIGHT;
        place(licenseButton, left + (content - licenseWidth) / 2, licenseY, licenseWidth);
        int pairWidth = decisionWidth * 2 + BUTTON_GAP;
        place(acceptButton, left + (content - pairWidth) / 2, decisionY, decisionWidth);
        place(declineButton, left + (content - pairWidth) / 2 + decisionWidth + BUTTON_GAP,
                decisionY, decisionWidth);

        int top = toolsY + TOOL_HEIGHT + TOOL_GAP + CARD_PADDING;
        int bottom = licenseY - BUTTON_GAP;
        int available = Math.max(BUTTON_HEIGHT, bottom - top - CARD_PADDING);
        scrollMax = Math.max(0, layout.getHeight() - available);
        scroll = Math.clamp(scroll, 0, scrollMax);
        viewport = new ScreenRectangle(left, top, content, available);
        layout.setX(left);
        layout.setY(top - scroll);
        layout.arrangeElements();
        card = new ScreenRectangle(left - CARD_PADDING, top - CARD_PADDING,
                content + CARD_PADDING * 2, available + CARD_PADDING * 2);
    }

    private void layoutTools(int left, int y, int content) {
        int x = left;
        for (AbstractWidget tool : tools) {
            int wide = Math.max(STEP_WIDTH,
                    Accessibility.width(tool.getMessage().getString()) + 8);
            // Dropped rather than squeezed: five controls at a large text size are wider than the
            // column above them, and a control too narrow to read its own label helps nobody. The
            // two text-size steps are first in the row, so they are the two that always survive.
            tool.visible = x == left || x + wide <= left + content;
            tool.active = tool.visible;
            if (!tool.visible) {
                continue;
            }
            place(tool, x, y, wide);
            x += wide + TOOL_GAP;
        }
    }

    private static void place(AbstractWidget widget, int x, int y, int width) {
        widget.setPosition(x, y);
        widget.setWidth(width);
    }

    private int contentWidth() {
        return Math.max(120, Math.min(MAX_CONTENT_WIDTH, this.width - SIDE_MARGIN * 2));
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
    protected void scaledBackground(GuiGraphicsExtractor extractor) {
        extractTransparentBackground(extractor);
        panel(extractor, card);
    }

    @Override
    protected void scaledRender(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                float partialTick) {
        // The text column only. Anything the scroll has pushed past the top or bottom of the card
        // is cut here rather than drawn over the tool row and the buttons.
        extractor.enableScissor(viewport.left(), viewport.top(), viewport.right(),
                viewport.bottom());
        super.scaledRender(extractor, mouseX, mouseY, partialTick);
        extractor.disableScissor();

        for (AbstractWidget widget : pinned) {
            widget.extractRenderState(extractor, mouseX, mouseY, partialTick);
        }
        if (scrollMax > 0) {
            // Nothing is hidden behind this - the buttons are outside the scroll - but a page that
            // continues below the fold should say so.
            extractor.textRenderer().accept(viewport.right() - 8, viewport.bottom() - 9,
                    Accessibility.text(scroll < scrollMax ? "▾" : "▴", Accessibility.dim()));
        }
    }

    /** Draws the card, honouring the high-contrast setting rather than the shared panel colour. */
    private static void panel(GuiGraphicsExtractor extractor, ScreenRectangle at) {
        extractor.fill(at.left(), at.top(), at.right(), at.bottom(),
                Accessibility.panelBackground());
        extractor.fill(at.left(), at.top(), at.right(), at.top() + 1, LuneScreen.PANEL_BORDER);
        extractor.fill(at.left(), at.bottom() - 1, at.right(), at.bottom(), LuneScreen.PANEL_BORDER);
        extractor.fill(at.left(), at.top(), at.left() + 1, at.bottom(), LuneScreen.PANEL_BORDER);
        extractor.fill(at.right() - 1, at.top(), at.right(), at.bottom(), LuneScreen.PANEL_BORDER);
    }

    @Override
    protected boolean scaledMouseScrolled(double mouseX, double mouseY, double deltaX,
                                          double deltaY) {
        if (scrollMax > 0 && viewport.containsPoint((int) mouseX, (int) mouseY)) {
            scroll = Math.clamp(scroll - (int) Math.signum(deltaY) * 12, 0, scrollMax);
            layout.setY(viewport.top() - scroll);
            layout.arrangeElements();
            return true;
        }
        return super.scaledMouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    /**
     * Keeps a click on a tick box that the scroll has pushed out of sight from landing.
     *
     * <p>The widgets move with the scroll rather than being drawn through a transform, so one
     * scrolled above the card is still sitting at a real position - just an invisible one, under
     * the tool row. Clipping the drawing without clipping the clicks is how a page grows a button
     * you cannot see and can still press.</p>
     */
    @Override
    protected boolean scaledMouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int x = (int) event.x();
        int y = (int) event.y();
        boolean onPinned = pinned.stream().anyMatch(w -> w.isMouseOver(x, y));
        if (!onPinned && !viewport.containsPoint(x, y)) {
            return false;
        }
        return super.scaledMouseClicked(event, doubleClick);
    }

    private void updateAcceptButton() {
        acceptButton.active = boxes.stream().allMatch(Checkbox::selected);
        acceptButton.setTooltip(acceptButton.active ? null
                : Tooltip.create(Accessibility.text(
                        Lang.get("lune.gui.terms.tick_every_box_continue"))));
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
            Screens.open(this.minecraft, returnTo);
        }
    }

    /**
     * Declining, and every other way out of this screen - Escape included.
     *
     * <p>Leaving is allowed on purpose. There is no version of this mod that holds someone's game
     * hostage until they agree to something; what refusing costs is the panel and the bot, which is
     * exactly what the terms are about. Nothing is recorded either way, so reaching for the panel
     * again is all it takes to change your mind.</p>
     */
    @Override
    public void onClose() {
        Screens.open(this.minecraft, returnTo);
    }
}
