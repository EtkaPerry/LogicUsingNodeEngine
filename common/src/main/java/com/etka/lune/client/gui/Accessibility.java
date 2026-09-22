package com.etka.lune.client.gui;

import com.etka.lune.config.BotConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

/**
 * The one place that answers "can everybody read this?", for every Lune surface.
 *
 * <p>Three settings feed it, and they are three genuinely different questions. <b>Colour-blind
 * safe</b> asks whether colour is allowed to be the only thing saying something. <b>High
 * contrast</b> asks whether Lune's see-through panels and dim label grey are readable where the
 * player is sitting. <b>Font</b> asks which face the panels are drawn in. Nothing here decides
 * anything about layout; that is {@link UiScale}'s question.</p>
 *
 * <h2>Why the marks exist</h2>
 *
 * <p>Lune says a great deal in colour: green is running, amber wants attention, red failed, grey is
 * nothing to report. That is a good code and a fast one to read - for people who can read it.
 * Red-green colour blindness affects roughly one man in twelve, and the green/amber pair the
 * dashboard leans on hardest is one of the ones it flattens.</p>
 *
 * <p>So a surface asks for a {@link Mark} alongside its colour, and gets a short glyph when the
 * player has asked for one and an empty string when they have not. The colour never goes away -
 * this adds a channel rather than replacing one - and a player who does not need the glyphs never
 * sees them. Where a whole palette is at stake rather than a single row, {@link #colourBlindSafe()}
 * surfaces switch to Okabe-Ito, the same set the task canvas already uses for its pins.</p>
 *
 * <p>The glyphs are drawn from the handful this codebase already ships in its own text, so they are
 * known to exist in the game's font rather than hoped for.</p>
 */
public final class Accessibility {

    /** What a colour is saying, apart from which colour it is. */
    public enum Mark {
        /** Finished, healthy, running: the green. */
        GOOD("✓"),
        /** Paused, low, worth a look: the amber. */
        WARN("!"),
        /** Failed, died, gave up: the red. */
        BAD("✕"),
        /** Nothing to report: the grey. */
        NEUTRAL("·");

        private final String glyph;

        Mark(String glyph) {
            this.glyph = glyph;
        }

        /** The glyph itself, whether or not the player has asked to see it. */
        public String glyph() {
            return glyph;
        }
    }

    /**
     * Okabe-Ito, the same colour-blind-safe set
     * {@link com.etka.lune.client.gui.widget.NodePalette} gives the canvas pins.
     *
     * <p>Sky blue and vermillion are separated by lightness as well as hue, so the pair survives
     * being seen in greyscale - let alone by a deuteranope.</p>
     */
    public static final int SKY_BLUE = 0xFF56B4E9;
    public static final int VERMILLION = 0xFFD55E00;
    public static final int YELLOW = 0xFFF0E442;
    public static final int ORANGE = 0xFFE69F00;
    public static final int REDDISH_PURPLE = 0xFFCC79A7;
    public static final int BLUISH_GREEN = 0xFF009E73;

    /** The ordinary palette: what the dashboard has always used. */
    private static final int GREEN = 0xFF69E391;
    private static final int AMBER = 0xFFFFC15C;
    private static final int RED = 0xFFFF6B6B;
    private static final int GREY = 0xFF9299A6;

    /** Label grey, and the brighter one that replaces it when the world behind is winning. */
    private static final int DIM_CONTRAST = 0xFFC8CCD4;
    /** The panel fill, opaque, for when 75% of it is not enough. */
    private static final int PANEL_OPAQUE = 0xFF101014;

    private Accessibility() {}

    public static boolean colourBlindSafe() {
        return BotConfig.get().colourBlindSafe();
    }

    public static boolean highContrast() {
        return BotConfig.get().highContrast;
    }

    // --- what a colour means -------------------------------------------------

    /** The colour for {@code mark}, in whichever palette the player has asked for. */
    public static int colour(Mark mark) {
        return colour(mark, colourBlindSafe());
    }

    /** The rule itself, free of the config, so it can be reasoned about and tested directly. */
    static int colour(Mark mark, boolean safe) {
        return switch (mark) {
            case GOOD -> safe ? SKY_BLUE : GREEN;
            case WARN -> safe ? YELLOW : AMBER;
            case BAD -> safe ? VERMILLION : RED;
            // Grey is grey either way: it is the absence of a signal, and no palette makes an
            // absence easier to tell apart from the three that are saying something.
            case NEUTRAL -> GREY;
        };
    }

    /** The glyph for {@code mark}, or an empty string when colour alone is allowed to say it. */
    public static String mark(Mark mark) {
        return colourBlindSafe() ? mark.glyph() : "";
    }

    /**
     * {@code value} with its mark in front of it, or unchanged when none is wanted.
     *
     * <p>In front rather than behind: a row whose value is long enough to be ellipsised would lose
     * a trailing glyph to the very truncation that makes the row hard to read.</p>
     */
    public static String marked(String value, Mark mark) {
        return marked(value, mark, colourBlindSafe());
    }

    static String marked(String value, Mark mark, boolean safe) {
        return safe ? mark.glyph() + " " + value : value;
    }

    // --- contrast ------------------------------------------------------------

    /** The dim label grey, brightened when the player has asked for contrast. */
    public static int dim() {
        return highContrast() ? DIM_CONTRAST : LuneScreen.TEXT_DIM;
    }

    /** The panel fill: see-through by default, solid when the world behind it is in the way. */
    public static int panelBackground() {
        return highContrast() ? PANEL_OPAQUE : LuneScreen.PANEL_BG;
    }

    // --- the face ------------------------------------------------------------

    /**
     * The game's own even-width fallback face, {@code minecraft:uniform}.
     *
     * <p>Named by id rather than through a constant because {@code Minecraft.UNIFORM_FONT} exists
     * only on 26.1.2 and was gone by 26.2, while {@code assets/minecraft/font/uniform.json} ships
     * in all three. One source tree builds for all of them, so the resource is the stable half.</p>
     */
    private static final FontDescription UNIFORM =
            new FontDescription.Resource(Identifier.withDefaultNamespace("uniform"));

    /**
     * The style Lune's own text carries, which is the font and nothing else.
     *
     * <p>Resolved every call rather than cached: the setting is edited live in the Config tab, and
     * a face frozen at class-load is a face that never changes.</p>
     */
    public static Style style() {
        return BotConfig.FONT_UNIFORM.equalsIgnoreCase(BotConfig.get().luneFont)
                ? Style.EMPTY.withFont(UNIFORM)
                : Style.EMPTY;
    }

    /** A line of Lune's text in the player's face, left for the widget to colour. */
    public static MutableComponent text(String value) {
        return Component.literal(value == null ? "" : value).withStyle(style());
    }

    /** A line of Lune's text: the player's colour, the player's face. */
    public static Component text(String value, int colour) {
        return text(value).withColor(colour);
    }

    /**
     * How wide {@code value} is in the face it will actually be drawn in.
     *
     * <p>{@link Font#width(String)} always measures the default font, so a panel that measured with
     * it and drew in Uniform would ellipsise in the wrong place - Uniform's Latin glyphs are wider.
     * The {@code FormattedText} overload reads the style, which is why this exists.</p>
     */
    public static int width(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        return font().width(Component.literal(value).withStyle(style()));
    }

    /** {@code value} cut to {@code maxWidth} with an ellipsis, measured in the drawn face. */
    public static String fit(String value, int maxWidth) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        if (maxWidth <= 0) {
            return "";
        }
        if (width(value) <= maxWidth) {
            return value;
        }
        int room = maxWidth - width("…");
        if (room <= 0) {
            return "";
        }
        return font().getSplitter().plainHeadByWidth(value, room, style()) + "…";
    }

    private static Font font() {
        return Minecraft.getInstance().font;
    }
}
