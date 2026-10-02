package com.etka.lune.client.gui.mascot;

import com.etka.lune.client.gui.LuneScreen;
import com.etka.lune.util.Lang;
import net.minecraft.client.gui.Font;

import java.util.ArrayList;
import java.util.List;

/**
 * How Lune's words look wherever she says them: the banner over them, the colour it is drawn in,
 * and the lines they are broken into.
 *
 * <p>The panel's bubble and the card in the corner of the world both draw from here, so a mood
 * cannot read one way beside the task list and another over the world.</p>
 */
public final class MascotSpeech {

    private static final int BLOCKED_ACCENT = 0xFFE6A15C;
    private static final int WAITING_ACCENT = 0xFF8BB9D8;
    private static final int DANGER_ACCENT = 0xFFFF6868;
    private static final int SUCCESS_ACCENT = 0xFF7EDB92;
    private static final int INVENTORY_ACCENT = 0xFFE4A45F;
    private static final int MISSING_ACCENT = 0xFFC8A6F2;
    private static final int PAUSED_ACCENT = 0xFF91A4BE;
    private static final String ELLIPSIS = "…";

    private MascotSpeech() {}

    /** The banner over what she says. */
    public static String banner(MascotAdvisor.Mood mood) {
        return switch (mood) {
            // The jobs with faces of their own wore WORKING before they had them; the banner over
            // them says what it always said.
            case WORKING, DIGGING, FARMING, CRAFTING, SMELTING, COLLECTING ->
                    Lang.get("lune.mascot.banner.working");
            case WAITING -> Lang.get("lune.mascot.banner.waiting");
            case BLOCKED -> Lang.get("lune.mascot.banner.blocked");
            case DANGER -> Lang.get("lune.mascot.banner.danger");
            case SUCCESS -> Lang.get("lune.mascot.banner.success");
            case INVENTORY_FULL -> Lang.get("lune.mascot.banner.inventory_full");
            case MISSING_MATERIALS -> Lang.get("lune.mascot.banner.missing_materials");
            case PAUSED -> Lang.get("lune.mascot.banner.paused");
            case THINKING -> Lang.get("lune.mascot.banner.thinking");
            case QUIET -> Lang.get("lune.mascot.banner.sleeping");
            case RESTING -> Lang.get("lune.mascot.banner.resting");
            case FIGHT -> Lang.get("lune.mascot.banner.fight");
            case FLEE -> Lang.get("lune.mascot.banner.flee");
            case HURT -> Lang.get("lune.mascot.banner.hurt");
            case RECOVERING -> Lang.get("lune.mascot.banner.recovering");
            case DEAD -> Lang.get("lune.mascot.banner.dead");
            case EFFECT -> Lang.get("lune.mascot.banner.effect");
            case HUNGRY -> Lang.get("lune.mascot.banner.hungry");
            case STALLED -> Lang.get("lune.mascot.banner.stalled");
            default -> Lang.get("lune.gui.about.lune");
        };
    }

    /** The colour of the banner: trouble in its own colour, anything else in the accent. */
    public static int colour(MascotAdvisor.Mood mood) {
        return switch (mood) {
            case WAITING -> WAITING_ACCENT;
            case BLOCKED -> BLOCKED_ACCENT;
            case DANGER, FIGHT, FLEE, HURT, DEAD -> DANGER_ACCENT;
            case RECOVERING, EFFECT, HUNGRY, STALLED -> MISSING_ACCENT;
            case SUCCESS -> SUCCESS_ACCENT;
            case INVENTORY_FULL -> INVENTORY_ACCENT;
            case MISSING_MATERIALS -> MISSING_ACCENT;
            case PAUSED -> PAUSED_ACCENT;
            default -> LuneScreen.ACCENT;
        };
    }

    /**
     * Moods that come with more to say than a job in hand does - an opening line as well as the
     * detail - so wherever she says them she is given a third line.
     */
    public static boolean expanded(MascotAdvisor.Mood mood) {
        return switch (mood) {
            case WAITING, BLOCKED, DANGER, SUCCESS, INVENTORY_FULL, MISSING_MATERIALS, PAUSED,
                    FIGHT, FLEE, HURT, RECOVERING, DEAD, EFFECT, HUNGRY, STALLED -> true;
            default -> false;
        };
    }

    /**
     * Word wrap, greedy, at most {@code maxLines} lines; when words are left over, the last line
     * ends in an ellipsis rather than the rest vanishing without a sign.
     */
    public static List<String> wrap(Font font, String value, int maxWidth, int maxLines) {
        List<String> lines = new ArrayList<>();
        String[] words = value == null ? new String[0] : value.trim().split("\\s+");
        int index = 0;
        while (index < words.length && lines.size() < maxLines) {
            StringBuilder line = new StringBuilder();
            while (index < words.length) {
                String candidate = line.isEmpty() ? words[index] : line + " " + words[index];
                if (!line.isEmpty() && font.width(candidate) > maxWidth) {
                    break;
                }
                line.setLength(0);
                line.append(candidate);
                index++;
                if (font.width(candidate) > maxWidth) {
                    break;
                }
            }
            if (!line.isEmpty()) {
                lines.add(fit(font, line.toString(), maxWidth));
            }
        }
        if (lines.isEmpty()) {
            lines.add("");
        }
        if (index < words.length) {
            int last = lines.size() - 1;
            lines.set(last, withEllipsis(font, lines.get(last), maxWidth));
        }
        return lines;
    }

    /** One line, cut short with an ellipsis where it would run past {@code maxWidth}. */
    public static String fit(Font font, String value, int maxWidth) {
        if (font.width(value) <= maxWidth) {
            return value;
        }
        return withEllipsis(font, value, maxWidth);
    }

    private static String withEllipsis(Font font, String value, int maxWidth) {
        return font.plainSubstrByWidth(value,
                Math.max(0, maxWidth - font.width(ELLIPSIS)), false) + ELLIPSIS;
    }
}
