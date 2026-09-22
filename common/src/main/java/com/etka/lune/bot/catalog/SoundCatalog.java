package com.etka.lune.bot.catalog;

import com.etka.lune.util.Lang;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Every sound the game knows about, read from the live registry.
 *
 * <p>The same bargain the block and item pickers make: Lune does not keep a list of sounds, it
 * asks. A mod that adds a sound gets it in the picker for free, and a sound that leaves the game
 * stops being offered instead of failing quietly at the moment somebody's task tries to play
 * it.</p>
 *
 * <p>Names come from the game's own subtitles - the line it shows a player who has subtitles
 * switched on - so the list reads "Zombie groans" rather than
 * {@code minecraft:entity.zombie.ambient}, in whatever language the game is in, without Lune
 * writing a single one of those names down. Anything with no subtitle falls back to its id read as
 * words, which is what a modded sound usually gets.</p>
 */
public final class SoundCatalog {

    /**
     * Stored by a card that wants no sound at all.
     *
     * <p>Not a registry id and deliberately shaped like one that could never exist, so it can live
     * in the same dropdown and the same saved parameter without a second field to say which kind
     * of value this is.</p>
     */
    public static final String SILENT = "none";

    private SoundCatalog() {}

    /**
     * Every sound, {@link #SILENT} first, ordered by the name the player reads.
     *
     * <p>Built on demand rather than cached: the order depends on the current language, and a
     * cache would hold yesterday's order the first time somebody changed it. Callers open a picker
     * with this, which happens on a click and not on a frame.</p>
     */
    public static List<String> ids() {
        List<String> ids = new ArrayList<>();
        for (Identifier id : BuiltInRegistries.SOUND_EVENT.keySet()) {
            ids.add(id.toString());
        }
        ids.sort(Comparator.comparing(SoundCatalog::label, String.CASE_INSENSITIVE_ORDER));
        ids.add(0, SILENT);
        return ids;
    }

    /** What a sound is called on screen: the game's subtitle for it, or its id read as words. */
    public static String label(String id) {
        if (id == null || id.isBlank() || SILENT.equals(id)) {
            return Lang.get("lune.choice.none");
        }
        String path = path(id);
        String subtitle = "subtitles." + path;
        return Lang.has(subtitle) ? Lang.get(subtitle) : humanize(path);
    }

    /**
     * The sound itself, or null for {@link #SILENT} and for an id nothing answers to any more.
     *
     * <p>A task saved when a mod was installed still names that mod's sound after it is removed.
     * Null here, and a card that plays nothing, is the right end for that: it is not worth failing
     * a night's work over a sound.</p>
     */
    public static SoundEvent sound(String id) {
        if (id == null || id.isBlank() || SILENT.equals(id)) {
            return null;
        }
        try {
            return BuiltInRegistries.SOUND_EVENT.getValue(Identifier.parse(id));
        } catch (RuntimeException notAnId) {
            return null;
        }
    }

    /** Whether an id names a sound that exists right now. */
    public static boolean exists(String id) {
        return sound(id) != null;
    }

    /** The part after the namespace, which is what subtitle keys are built from. */
    private static String path(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }

    /** {@code entity.zombie.ambient} as {@code Entity zombie ambient}, for a sound with no subtitle. */
    private static String humanize(String path) {
        String words = path.replace('.', ' ').replace('_', ' ').trim();
        if (words.isEmpty()) {
            return path;
        }
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
