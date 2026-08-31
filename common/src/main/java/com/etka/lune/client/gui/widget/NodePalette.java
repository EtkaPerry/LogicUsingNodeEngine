package com.etka.lune.client.gui.widget;

import com.etka.lune.config.BotConfig;
import com.etka.lune.task.TaskNode;

import java.util.Map;

/**
 * What colour a card on the canvas is, and why.
 *
 * <p>The canvas used to pick a colour per card type: blue for a command, orange for Always, purple
 * for a relay, green for START, red for End. Six unrelated hues carrying no shared meaning, so the
 * colour told you which <em>class</em> the card was rather than anything you needed while reading
 * the graph.</p>
 *
 * <p>Cards are now coloured by the <b>role</b> they play in the circuit, and only four roles exist:
 * where a signal starts, what decides, what carries signals around, and what does work. A theme
 * picks the family those four are drawn from, so the whole canvas reads as one board in whichever
 * colour the player likes.</p>
 *
 * <h2>Pins are a separate question</h2>
 *
 * <p>Card colour is decoration and can be anything. Pin colour is <em>information</em>: green means
 * Success and red means Fail, and those two are the single most common pair that red-green colour
 * blindness makes indistinguishable - roughly one man in twelve. So pins have their own setting,
 * and the colour-blind palette is Okabe-Ito, chosen because its colours stay distinct under
 * deuteranopia, protanopia and tritanopia alike. The pins also keep their written labels, because
 * a colour scheme should be the second thing telling you which pin you are on, never the only one.</p>
 */
public final class NodePalette {

    /** What a card does in the circuit. Four, on purpose. */
    public enum Role {
        /** Where signals come from: START, Always, Pulse, Observer, Button. */
        SOURCE,
        /** Decisions: the Check cards, whose Success and Fail edges are the point. */
        LOGIC,
        /** Signal plumbing: Signal Relay, Timer, Counter, End. */
        FLOW,
        /** Everything that does real work in the world. */
        ACTION
    }

    /** One card's colours. */
    public record Colours(int border, int header, int accent, int background) {}

    /**
     * The colours that carry meaning rather than taste.
     *
     * @param exec the plain execution In pin
     */
    public record Pins(int exec, int success, int failure, int whilePin, int signal, int observe,
                       int data) {}

    private static final Pins CLASSIC = new Pins(
            0xFFF2F2F2, 0xFF58C878, 0xFFE15B64, 0xFFF0B84D, 0xFFB77DFF, 0xFF6E5A8C, 0xFFF2C14E);

    /**
     * Okabe-Ito, the standard colour-blind-safe qualitative set.
     *
     * <p>Success becomes sky blue and Fail vermillion: the pair is separated by lightness as well
     * as hue, so it survives being seen in greyscale, let alone by a deuteranope.</p>
     */
    private static final Pins COLOUR_BLIND = new Pins(
            0xFFF2F2F2, 0xFF56B4E9, 0xFFD55E00, 0xFFF0E442, 0xFFCC79A7, 0xFF8A6B7D, 0xFFE69F00);

    private NodePalette() {}

    public static Pins pins() {
        return BotConfig.PINS_COLOUR_BLIND.equals(BotConfig.get().blueprintPins)
                ? COLOUR_BLIND : CLASSIC;
    }

    public static Role roleOf(TaskNode node) {
        if (node == null) {
            return Role.ACTION;
        }
        if (node.isSourceNode()) {
            return Role.SOURCE;
        }
        if (node.isPulseNode()) {
            return Role.FLOW;
        }
        return node.commandId != null && node.commandId.startsWith("check_") ? Role.LOGIC : Role.ACTION;
    }

    public static Colours of(TaskNode node) {
        return of(roleOf(node), BotConfig.get().blueprintTheme);
    }

    /**
     * Every theme is one hue family. Roles differ by how saturated the accent is and how far the
     * header is tinted towards it, which keeps four cards on screen distinguishable without turning
     * the canvas into a paint chart.
     */
    // Resolved once. This is asked for on every card and every chip of every frame, and both the
    // accent table and the shaded border and header used to be built fresh on each of those calls.
    private static final int[] SLATE = {0xFFE0A83C, 0xFF57A8E0, 0xFFA07BD8, 0xFF8FA0B4};
    private static final int[] BLUE = {0xFF7FC4FF, 0xFF57A8E0, 0xFF4E8CC4, 0xFF6E93B8};
    private static final int[] PURPLE = {0xFFCBAAFF, 0xFFA07BD8, 0xFF8A64C4, 0xFF9186B8};
    private static final int[] AMBER = {0xFFFFD37A, 0xFFE0A83C, 0xFFC98F28, 0xFFB8A377};
    private static final int[] GREEN = {0xFFA8E5BC, 0xFF6FBF87, 0xFF4F9E68, 0xFF86B394};

    private static final Map<String, Colours[]> THEMES = Map.of(
            BotConfig.THEME_SLATE, build(SLATE),
            BotConfig.THEME_BLUE, build(BLUE),
            BotConfig.THEME_PURPLE, build(PURPLE),
            BotConfig.THEME_AMBER, build(AMBER),
            BotConfig.THEME_GREEN, build(GREEN));

    private static Colours[] build(int[] accents) {
        Colours[] resolved = new Colours[accents.length];
        for (int i = 0; i < accents.length; i++) {
            resolved[i] = new Colours(shade(accents[i], 0.45F), shade(accents[i], 0.20F),
                    accents[i], 0xF21D1E24);
        }
        return resolved;
    }

    public static Colours of(Role role, String theme) {
        Colours[] resolved = THEMES.getOrDefault(theme == null ? BotConfig.THEME_SLATE : theme,
                THEMES.get(BotConfig.THEME_SLATE));
        return resolved[switch (role) {
            case SOURCE -> 0;
            case LOGIC -> 1;
            case FLOW -> 2;
            case ACTION -> 3;
        }];
    }

    /** Mixes an accent down towards the card background, for a border or a header tint. */
    private static int shade(int accent, float towardsAccent) {
        int baseR = 0x2A;
        int baseG = 0x2B;
        int baseB = 0x33;
        int r = Math.round(baseR + (((accent >> 16) & 0xFF) - baseR) * towardsAccent);
        int g = Math.round(baseG + (((accent >> 8) & 0xFF) - baseG) * towardsAccent);
        int b = Math.round(baseB + ((accent & 0xFF) - baseB) * towardsAccent);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
