package com.etka.lune.client.gui.widget;

import com.etka.lune.client.gui.Accessibility;
import com.etka.lune.client.gui.LuneScreen;
import com.etka.lune.client.gui.mascot.MascotAdvisor;
import com.etka.lune.client.gui.mascot.MascotRenderer;
import com.etka.lune.config.BotConfig;
import com.etka.lune.util.Lang;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.util.List;

/**
 * The menu: a button at the right end of the tab bar, and Lune's corner, the page it opens.
 *
 * <p>The corner is where everything that is not a tab's own work lives - the training course, and
 * games to play while Lune works. Each of them is a {@link Section}: a row in the side menu on
 * the left, and the whole page to the right of it while it is chosen. Training used to be a
 * full-width button on the Tasks tab, on the one screen where the player is busy with their own
 * work; here it has a page of its own and is one click from every tab.</p>
 *
 * <p>Lune lives in the side menu while the corner is open, and talks about the page that is. The
 * corner covers the tab below the bar and leaves the bar itself alone, so the tabs stay one click
 * away and pressing one simply goes there.</p>
 *
 * <p>Like the pickers it is drawn by {@code LuneScreen} rather than being a screen of its own, so
 * the panel keeps its own GUI scale and the bot keeps running underneath. While open it owns the
 * pointer below the bar and the keyboard.</p>
 */
public class LuneMenu extends AbstractWidget {

    /** A rectangle a page may draw in. */
    public record Area(int x, int y, int width, int height) {
        public int right() {
            return x + width;
        }

        public int bottom() {
            return y + height;
        }

        public boolean contains(double pointX, double pointY) {
            return pointX >= x && pointX < x + width && pointY >= y && pointY < y + height;
        }
    }

    /** How far one step of a section's progress has got. */
    public enum Segment { DONE, CURRENT, AHEAD }

    /**
     * One page of the corner: its row in the side menu, and everything right of it while chosen.
     *
     * <p>Everything is asked for as it is drawn, never cached, so a row is never a frame behind the
     * state it describes and never holds text from the language it was built in.</p>
     */
    public interface Section {
        /** The section's name, in the side menu and over its page. */
        String title();

        /** The small line under the name in the side menu. */
        String caption();

        default int captionColour() {
            return Accessibility.dim();
        }

        /** One segment per step, drawn under the caption. Empty for nothing to show. */
        default List<Segment> progress() {
            return List.of();
        }

        /** Listed but not built yet: dimmed in the side menu, and its page says so. */
        default boolean soon() {
            return false;
        }

        /** Something here the player has not looked at yet; it earns the Menu button a dot. */
        default boolean hasNews() {
            return false;
        }

        /** What Lune says while the page is open. */
        String luneLine();

        default MascotAdvisor.Mood luneMood() {
            return MascotAdvisor.Mood.IDLE;
        }

        /** Draws the section's picture into the {@code size} square at {@code x, y}. */
        void drawIcon(GuiGraphicsExtractor extractor, int x, int y, int size);

        /** The page is about to be shown: start it from where the player is. */
        default void shown() {}

        void render(GuiGraphicsExtractor extractor, Area area, int mouseX, int mouseY);

        /** A click on the page, with any button; a page that only acts on the left one checks. */
        default void click(Area area, double mouseX, double mouseY, int button) {}

        /** The pointer moved with {@code button} held down, wherever the press was. */
        default void drag(Area area, double mouseX, double mouseY, int button) {}

        /** {@code button} came back up. */
        default void release(Area area, double mouseX, double mouseY, int button) {}

        default void scroll(Area area, double mouseX, double mouseY, double delta) {}

        default void key(Area area, int keyCode) {}
    }

    // --- the button ----------------------------------------------------------

    private static final int BUTTON_H = 18;
    private static final int BUTTON_PAD = 6;
    private static final int GLYPH_W = 8;
    private static final int GLYPH_H = 7;
    private static final int GLYPH_GAP = 5;

    // --- the corner ----------------------------------------------------------

    private static final int MAX_W = 720;
    private static final int MAX_H = 440;
    /** Room kept between the corner and the edges of the tab area it covers. */
    private static final int OUTER = 8;
    private static final int HEADER_H = 26;
    private static final int INSET = 8;
    private static final int SIDEBAR_W = 138;
    private static final int ITEM_H = 38;
    private static final int ITEM_GAP = 2;
    private static final int ICON = 24;
    private static final int CLOSE = 16;
    /** The cell Lune is drawn in; her head is about seven tenths of it. */
    private static final int LUNE_ART = 56;
    private static final int OPEN_MILLIS = 160;
    private static final int SLIDE = 6;

    private static final int CHIP = 0x66000000;
    private static final int CHIP_EDGE = 0x40FFFFFF;
    private static final int CHIP_HOVER = 0x2EFFFFFF;
    private static final int CHIP_HOVER_EDGE = 0x90FFFFFF;
    private static final int BACKDROP = 0xA8000000;
    private static final int PANEL = 0xF614161B;
    private static final int HEADER = 0xFF1B1D24;
    private static final int SIDEBAR = 0x26000000;
    private static final int SELECTED = 0x33FF8811;
    private static final int TILE = 0xFF1B1E26;
    private static final int TILE_EDGE = 0xFF363A47;
    private static final int TRACK = 0xFF2C2F38;
    private static final int BUBBLE = 0xFF23252D;
    private static final int BUBBLE_EDGE = 0xFF3D4050;
    /** The colour of Lune's own glow, so the moon in the title is plainly hers. */
    private static final int MOONLIGHT = 0xFFF4C95D;

    /**
     * Stars in the header: where across it (a fraction), how far down, and whether it is one of the
     * two bright ones. Fixed rather than random, so the sky is the same every time it is opened.
     */
    private static final float[][] STARS = {
            {0.36F, 7, 0}, {0.43F, 16, 0}, {0.50F, 5, 1}, {0.57F, 14, 0}, {0.64F, 8, 0},
            {0.71F, 17, 0}, {0.78F, 6, 1}, {0.85F, 12, 0}};

    /**
     * Whether the corner has been opened since the game started.
     *
     * <p>Static on purpose: the panel is rebuilt every time it opens, and a dot that came back each
     * time would be a dot the player learns to ignore. Once a session is enough of a nudge.</p>
     */
    private static boolean lookedIn;
    /** The page last looked at, so the corner reopens where the player left it. */
    private static int remembered;

    private final MascotRenderer lune = new MascotRenderer();
    private List<Section> sections = List.of();
    private int selected;
    private boolean open;
    private boolean compact;
    private int barHeight = 24;
    private int screenWidth = 400;
    private int screenHeight = 300;
    private long openedAt;

    /** The corner's box, its header's close button, the side menu and the page. */
    private record Frame(int x, int y, int width, int height, Area sidebar, Area page, Area close) {}

    public LuneMenu() {
        super(0, 0, 10, BUTTON_H, Component.literal(Lang.get("lune.gui.menu.title")));
    }

    public void setSections(List<Section> sections) {
        this.sections = List.copyOf(sections);
        selected = Math.clamp(remembered, 0, Math.max(0, this.sections.size() - 1));
    }

    public boolean isOpen() {
        return open;
    }

    /** Opens on the page last looked at. */
    public void open() {
        open(selected());
    }

    /** Opens on {@code section}'s page. */
    public void open(Section section) {
        int index = sections.indexOf(section);
        if (index >= 0) {
            selected = index;
            remembered = index;
        }
        open = true;
        lookedIn = true;
        openedAt = Util.getMillis();
        Section page = selected();
        if (page != null) {
            page.shown();
        }
    }

    public void close() {
        open = false;
    }

    private Section selected() {
        return selected >= 0 && selected < sections.size() ? sections.get(selected) : null;
    }

    private void select(int index) {
        if (index == selected || index < 0 || index >= sections.size()) {
            return;
        }
        selected = index;
        remembered = index;
        sections.get(index).shown();
    }

    // --- placing -------------------------------------------------------------

    /** How wide the button is: the glyph and the word, or the glyph alone when {@code compact}. */
    public int buttonWidth(boolean compact) {
        int glyph = BUTTON_PAD * 2 + GLYPH_W;
        return compact ? glyph : glyph + GLYPH_GAP + Minecraft.getInstance().font.width(label());
    }

    /**
     * Puts the button in the tab bar with its right edge at {@code right}.
     *
     * <p>It sits on the same line as the tab labels and clear of the separator under them, so it
     * reads as a control in the header rather than a sixth tab.</p>
     */
    public void place(int right, int barHeight, boolean compact, int screenWidth, int screenHeight) {
        this.compact = compact;
        this.barHeight = barHeight;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        int width = buttonWidth(compact);
        setPosition(right - width, Math.max(0, barHeight - 3 - BUTTON_H));
        setSize(width, BUTTON_H);
    }

    private String label() {
        return Lang.get("lune.gui.menu.title");
    }

    private Frame frame() {
        int areaHeight = Math.max(80, screenHeight - barHeight);
        int width = Math.min(MAX_W, Math.max(200, screenWidth - OUTER * 2));
        int height = Math.min(MAX_H, Math.max(120, areaHeight - OUTER * 2));
        int x = (screenWidth - width) / 2;
        int y = barHeight + Math.max(OUTER, (areaHeight - height) / 2);
        int bodyTop = y + HEADER_H + INSET;
        int bodyHeight = Math.max(40, height - HEADER_H - INSET * 2);
        int sidebarWidth = Math.min(SIDEBAR_W, Math.max(96, width / 4));
        Area sidebar = new Area(x + INSET, bodyTop, sidebarWidth, bodyHeight);
        int pageX = sidebar.right() + INSET;
        Area page = new Area(pageX, bodyTop, Math.max(40, x + width - INSET - pageX), bodyHeight);
        Area close = new Area(x + width - 6 - CLOSE, y + (HEADER_H - CLOSE) / 2, CLOSE, CLOSE);
        return new Frame(x, y, width, height, sidebar, page, close);
    }

    private static Area item(Frame frame, int index) {
        Area sidebar = frame.sidebar();
        return new Area(sidebar.x() + 4, sidebar.y() + 4 + index * (ITEM_H + ITEM_GAP),
                sidebar.width() - 8, ITEM_H);
    }

    // --- drawing -------------------------------------------------------------

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                            float partialTick) {
        // Drawn through renderButton and renderCorner, so LuneScreen can put the corner last.
    }

    /** The button, drawn with the tab bar. */
    public void renderButton(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();
        boolean hovered = !open && (onButton(mouseX, mouseY) || isFocused());

        MenuPaint.roundedFill(extractor, x, y, w, h,
                open ? LuneScreen.ACCENT_SELECTION : hovered ? CHIP_HOVER : CHIP);
        MenuPaint.roundedOutline(extractor, x, y, w, h,
                open ? LuneScreen.ACCENT : hovered ? CHIP_HOVER_EDGE : CHIP_EDGE);

        int ink = open || hovered ? MenuPaint.WHITE : LuneScreen.TEXT;
        int glyphX = x + BUTTON_PAD;
        int glyphY = y + (h - GLYPH_H) / 2;
        for (int bar = 0; bar < 3; bar++) {
            extractor.fill(glyphX, glyphY + bar * 3, glyphX + GLYPH_W, glyphY + bar * 3 + 1, ink);
        }
        if (!compact) {
            MenuPaint.text(extractor, label(), glyphX + GLYPH_W + GLYPH_GAP, y + (h - 8) / 2, ink);
        }

        if (!lookedIn && !open && hasNews()) {
            // A dot on the corner, ringed in the page colour so it lifts off the button's edge.
            int dotX = x + w - 4;
            int dotY = y - 2;
            MenuPaint.roundedFill(extractor, dotX - 1, dotY - 1, 7, 7, 0xFF101014);
            MenuPaint.roundedFill(extractor, dotX, dotY, 5, 5, LuneScreen.ACCENT);
        }
    }

    /** The corner, drawn above the tab and the mascot. Only while open. */
    public void renderCorner(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
        if (!open) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        long now = Util.getMillis();
        Frame frame = frame();

        // The tab under it is dimmed and the bar is not: the bar is still how the player gets out.
        extractor.fill(0, barHeight, screenWidth, screenHeight, BACKDROP);

        // Rises the last few pixels into place rather than appearing. It settles in a sixth of a
        // second and then never moves again; the pointer is shifted with it so hover stays true.
        float t = Math.min(1.0F, (now - openedAt) / (float) OPEN_MILLIS);
        int slide = Math.round(SLIDE * (1.0F - t) * (1.0F - t) * (1.0F - t));
        var pose = extractor.pose();
        pose.pushMatrix();
        pose.translate(0.0F, slide);
        int pointerY = mouseY - slide;

        drawPanel(extractor, font, frame, mouseX, pointerY, now);
        drawSidebar(extractor, font, frame, mouseX, pointerY);
        drawLune(extractor, font, frame, now);
        Section page = selected();
        if (page != null) {
            page.render(extractor, frame.page(), mouseX, pointerY);
        }
        pose.popMatrix();
    }

    private void drawPanel(GuiGraphicsExtractor extractor, Font font, Frame frame, int mouseX,
                           int mouseY, long now) {
        int x = frame.x();
        int y = frame.y();
        int w = frame.width();
        int h = frame.height();
        extractor.fill(x + 3, y + 3, x + w + 3, y + h + 3, 0x40000000);
        extractor.fill(x + 1, y + 1, x + w + 1, y + h + 1, 0x30000000);
        MenuPaint.roundedFill(extractor, x, y, w, h, Accessibility.highContrast() ? 0xFF101014 : PANEL);
        MenuPaint.roundedOutline(extractor, x, y, w, h, LuneScreen.PANEL_BORDER);

        extractor.fill(x + 1, y + 1, x + w - 1, y + HEADER_H, HEADER);
        extractor.fill(x + 1, y + HEADER_H, x + w - 1, y + HEADER_H + 1, MenuPaint.CARD_EDGE);
        extractor.fill(x + 1, y, x + w - 1, y + 1, LuneScreen.ACCENT);

        String title = Lang.get("lune.gui.menu.corner");
        int titleY = y + (HEADER_H - 8) / 2;
        MenuPaint.moon(extractor, x + 12, titleY, MOONLIGHT);
        MenuPaint.text(extractor, title, x + 23, titleY, MenuPaint.WHITE);
        drawStars(extractor, frame, x + 23 + font.width(title) + 14, now);

        Area close = frame.close();
        boolean closeHot = close.contains(mouseX, mouseY);
        MenuPaint.roundedFill(extractor, close.x(), close.y(), CLOSE, CLOSE,
                closeHot ? LuneScreen.ACCENT : 0x26FFFFFF);
        MenuPaint.cross(extractor, close.x() + 4, close.y() + 4,
                closeHot ? MenuPaint.INK_ON_ACCENT : LuneScreen.TEXT);
    }

    /**
     * A few stars across the header, each breathing on its own slow beat. It is the one thing in
     * the corner that moves for no reason, and the reason it is allowed to is that it is Lune's sky.
     */
    private static void drawStars(GuiGraphicsExtractor extractor, Frame frame, int clearOfTitle, long now) {
        int left = frame.x();
        int limit = frame.close().x() - 10;
        for (int i = 0; i < STARS.length; i++) {
            int x = left + Math.round(frame.width() * STARS[i][0]);
            int y = frame.y() + Math.round(STARS[i][1]);
            if (x < clearOfTitle || x + 2 > limit) {
                continue;
            }
            float beat = 0.5F + 0.5F * (float) Math.sin(now / 900.0 + i * 1.7);
            boolean bright = STARS[i][2] > 0;
            int colour = MenuPaint.fade(bright ? MOONLIGHT : MenuPaint.WHITE,
                    (bright ? 0.45F : 0.20F) + (bright ? 0.55F : 0.45F) * beat);
            extractor.fill(x, y, x + 1, y + 1, colour);
            if (bright) {
                int arm = MenuPaint.fade(colour, 0.55F);
                extractor.fill(x - 1, y, x, y + 1, arm);
                extractor.fill(x + 1, y, x + 2, y + 1, arm);
                extractor.fill(x, y - 1, x + 1, y, arm);
                extractor.fill(x, y + 1, x + 1, y + 2, arm);
            }
        }
    }

    private void drawSidebar(GuiGraphicsExtractor extractor, Font font, Frame frame, int mouseX,
                             int mouseY) {
        Area sidebar = frame.sidebar();
        MenuPaint.roundedFill(extractor, sidebar.x(), sidebar.y(), sidebar.width(), sidebar.height(), SIDEBAR);
        MenuPaint.roundedOutline(extractor, sidebar.x(), sidebar.y(), sidebar.width(), sidebar.height(),
                MenuPaint.CARD_EDGE);
        for (int i = 0; i < sections.size(); i++) {
            Section section = sections.get(i);
            Area item = item(frame, i);
            boolean chosen = i == selected;
            if (chosen) {
                MenuPaint.roundedFill(extractor, item.x(), item.y(), item.width(), item.height(), SELECTED);
                extractor.fill(item.x(), item.y() + 6, item.x() + 2, item.bottom() - 6, LuneScreen.ACCENT);
            } else if (item.contains(mouseX, mouseY)) {
                MenuPaint.roundedFill(extractor, item.x(), item.y(), item.width(), item.height(), MenuPaint.HOVER);
            }

            int iconX = item.x() + 7;
            int iconY = item.y() + (ITEM_H - ICON) / 2;
            MenuPaint.roundedFill(extractor, iconX, iconY, ICON, ICON, TILE);
            MenuPaint.roundedOutline(extractor, iconX, iconY, ICON, ICON, chosen ? LuneScreen.ACCENT : TILE_EDGE);
            section.drawIcon(extractor, iconX, iconY, ICON);
            if (section.soon()) {
                // Not built yet: the picture is there, under a veil, so the shelf reads as waiting.
                MenuPaint.roundedFill(extractor, iconX + 1, iconY + 1, ICON - 2, ICON - 2, 0x70000000);
            }

            int textX = iconX + ICON + 7;
            int textWidth = Math.max(10, item.right() - 6 - textX);
            int nameColour = chosen ? MenuPaint.WHITE : section.soon() ? Accessibility.dim() : LuneScreen.TEXT;
            MenuPaint.text(extractor, MenuPaint.clip(font, section.title(), textWidth), textX, item.y() + 8, nameColour);
            MenuPaint.text(extractor, MenuPaint.clip(font, section.caption(), textWidth), textX, item.y() + 19,
                    section.captionColour());
            drawProgress(extractor, section.progress(), textX, item.y() + 30, textWidth);
        }
    }

    /** One segment per step, so a bar counts the steps rather than smearing them into a percentage. */
    static void drawProgress(GuiGraphicsExtractor extractor, List<Segment> segments, int x, int y, int width) {
        int count = segments.size();
        if (count == 0) {
            return;
        }
        int gap = width / count >= 8 ? 2 : 1;
        int each = (width - gap * (count - 1)) / count;
        if (each < 1) {
            return;
        }
        // The pixels the division leaves over go to the first few segments, one each, so the bar
        // ends exactly where the text above it may.
        int spare = width - gap * (count - 1) - each * count;
        int cursor = x;
        for (int i = 0; i < count; i++) {
            int segmentWidth = each + (i < spare ? 1 : 0);
            int colour = switch (segments.get(i)) {
                case DONE -> Accessibility.colour(Accessibility.Mark.GOOD);
                case CURRENT -> LuneScreen.ACCENT;
                case AHEAD -> TRACK;
            };
            extractor.fill(cursor, y, cursor + segmentWidth, y + 2, colour);
            cursor += segmentWidth + gap;
        }
    }

    /**
     * Lune, at the foot of the side menu, saying something about the page that is open.
     *
     * <p>She is only drawn where she fits whole, bubble and all: on a short screen she steps out
     * rather than being cut in half by the rows above her. The player's own switch for her is
     * honoured here the same as everywhere else.</p>
     */
    private void drawLune(GuiGraphicsExtractor extractor, Font font, Frame frame, long now) {
        Section page = selected();
        if (page == null || !BotConfig.get().showLune || sections.isEmpty()) {
            return;
        }
        Area sidebar = frame.sidebar();
        int rowsBottom = item(frame, sections.size() - 1).bottom() + 6;
        int bob = (int) Math.round(Math.sin(now / 700.0) * 2.0);
        int artX = sidebar.x() + (sidebar.width() - LUNE_ART) / 2;
        int artY = sidebar.bottom() - LUNE_ART + 2 + bob;
        int headTop = artY + LUNE_ART * 17 / 96;
        if (headTop < rowsBottom) {
            return;
        }

        List<String> lines = MenuPaint.wrap(font, page.luneLine(), sidebar.width() - 24, 6);
        int bubbleWidth = sidebar.width() - 12;
        int bubbleHeight = lines.size() * 10 + 9;
        int bubbleX = sidebar.x() + 6;
        int bubbleY = headTop - 5 - bubbleHeight;
        while (!lines.isEmpty() && bubbleY < rowsBottom) {
            // Too tall for the room left: drop a line (the last one is clipped with an ellipsis).
            lines = MenuPaint.wrap(font, page.luneLine(), sidebar.width() - 24, lines.size() - 1);
            bubbleHeight = lines.size() * 10 + 9;
            bubbleY = headTop - 5 - bubbleHeight;
        }
        if (!lines.isEmpty()) {
            MenuPaint.roundedFill(extractor, bubbleX, bubbleY, bubbleWidth, bubbleHeight, BUBBLE);
            MenuPaint.roundedOutline(extractor, bubbleX, bubbleY, bubbleWidth, bubbleHeight, BUBBLE_EDGE);
            // The tail, pointing down at her.
            int tailX = artX + LUNE_ART / 2;
            int tailY = bubbleY + bubbleHeight - 1;
            extractor.fill(tailX - 3, tailY, tailX + 4, tailY + 1, BUBBLE);
            extractor.fill(tailX - 2, tailY + 1, tailX + 3, tailY + 2, BUBBLE_EDGE);
            extractor.fill(tailX - 1, tailY + 2, tailX + 2, tailY + 3, BUBBLE_EDGE);
            extractor.fill(tailX, tailY + 3, tailX + 1, tailY + 4, BUBBLE_EDGE);
            for (int i = 0; i < lines.size(); i++) {
                MenuPaint.text(extractor, lines.get(i), bubbleX + 6, bubbleY + 5 + i * 10, LuneScreen.TEXT);
            }
        }
        lune.draw(extractor, page.luneMood(), artX, artY, LUNE_ART, now);
    }

    private boolean hasNews() {
        for (Section section : sections) {
            if (section.hasNews()) {
                return true;
            }
        }
        return false;
    }

    public boolean onButton(double mouseX, double mouseY) {
        return MenuPaint.hits(getX(), getY(), getWidth(), getHeight(), mouseX, mouseY);
    }

    // --- input ---------------------------------------------------------------

    /**
     * A click, while the corner is open or on the button while it is not.
     *
     * <p>Returns whether the menu took it. A click in the tab bar closes the corner and is handed
     * back, so the tab it landed on is where the player goes; any other click is the corner's, and
     * one that misses the corner closes it without reaching whatever was underneath.</p>
     */
    public boolean handleClick(double mouseX, double mouseY, int button) {
        if (!open) {
            if (button != InputConstants.MOUSE_BUTTON_LEFT || !onButton(mouseX, mouseY)) {
                return false;
            }
            playDownSound(Minecraft.getInstance().getSoundManager());
            open();
            return true;
        }
        if (onButton(mouseX, mouseY)) {
            close();
            return true;
        }
        if (mouseY < barHeight) {
            close();
            return false;
        }
        Frame frame = frame();
        if (!MenuPaint.hits(frame.x(), frame.y(), frame.width(), frame.height(), mouseX, mouseY)) {
            close();
            return true;
        }
        if (button != InputConstants.MOUSE_BUTTON_LEFT) {
            // The side menu and the close button answer the left button alone; the page is handed
            // every button, because a game turns a tile back with the right one.
            Section page = selected();
            if (page != null && frame.page().contains(mouseX, mouseY)) {
                page.click(frame.page(), mouseX, mouseY, button);
            }
            return true;
        }
        if (frame.close().contains(mouseX, mouseY)) {
            MenuPaint.click();
            close();
            return true;
        }
        for (int i = 0; i < sections.size(); i++) {
            if (item(frame, i).contains(mouseX, mouseY)) {
                if (i != selected) {
                    MenuPaint.click();
                    select(i);
                }
                return true;
            }
        }
        Section page = selected();
        if (page != null && frame.page().contains(mouseX, mouseY)) {
            page.click(frame.page(), mouseX, mouseY, button);
        }
        return true;
    }

    /**
     * A drag while the corner is open, any button, for the page: a game lays items along a drag
     * the way the game's own inventories do. The page keeps track of what the press started.
     */
    public void handleDrag(double mouseX, double mouseY, int button) {
        Section page = selected();
        if (open && page != null) {
            page.drag(frame().page(), mouseX, mouseY, button);
        }
    }

    public void handleRelease(double mouseX, double mouseY, int button) {
        Section page = selected();
        if (open && page != null) {
            page.release(frame().page(), mouseX, mouseY, button);
        }
    }

    public void handleScroll(double mouseX, double mouseY, double delta) {
        Section page = selected();
        if (open && page != null && delta != 0) {
            page.scroll(frame().page(), mouseX, mouseY, delta);
        }
    }

    /** Escape closes, up and down change page, and the page has the rest. */
    public void handleKey(int keyCode) {
        if (!open) {
            return;
        }
        if (keyCode == InputConstants.KEY_ESCAPE) {
            close();
        } else if (keyCode == InputConstants.KEY_UP && !sections.isEmpty()) {
            select(Math.floorMod(selected - 1, sections.size()));
        } else if ((keyCode == InputConstants.KEY_DOWN || keyCode == InputConstants.KEY_TAB)
                && !sections.isEmpty()) {
            select(Math.floorMod(selected + 1, sections.size()));
        } else if (selected() != null) {
            selected().key(frame().page(), keyCode);
        }
    }

    /**
     * Enter or Space on the button, for a player going through the panel with Tab.
     *
     * <p>Training was a vanilla button on the Tasks tab, and a vanilla button can be reached and
     * pressed without a mouse; moving it into the corner must not take that away.</p>
     */
    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (open || !isFocused() || (key != InputConstants.KEY_RETURN
                && key != InputConstants.KEY_NUMPADENTER && key != InputConstants.KEY_SPACE)) {
            return false;
        }
        playDownSound(Minecraft.getInstance().getSoundManager());
        open();
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return false;
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
