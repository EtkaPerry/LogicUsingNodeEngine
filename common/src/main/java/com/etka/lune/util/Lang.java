package com.etka.lune.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.locale.Language;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every line of text Lune says or draws, looked up by key.
 *
 * <p>The words themselves live in {@code assets/lune/lang/en_us.json} and nowhere else. Adding a
 * language is copying that file to {@code tr_tr.json} and translating the right-hand side; changing
 * what Lune says is editing the right-hand side of the one that is already there. Neither needs a
 * line of Java touched, which is the entire point - text that lives in source is text only the
 * person holding the source can change.</p>
 *
 * <p>Vanilla loads en_us underneath whatever language is selected, so a translation that is missing
 * a key falls back to English rather than to a raw key. A half-finished translation is therefore a
 * usable one, and a translator can ship what they have.</p>
 */
public final class Lang {

    /** Read straight from the jar when the game's own language machinery is not up. */
    private static final String BUNDLED = "/assets/lune/lang/en_us.json";
    /** How far a numbered bank is scanned before it is declared finished. */
    private static final int MAX_BANK = 64;

    private static Map<String, String> bundled;
    private static final Map<String, List<String>> BANKS = new HashMap<>();

    /** The language the player pinned, or {@link LuneLanguages#GAME_DEFAULT} to follow the game. */
    private static String selected = LuneLanguages.GAME_DEFAULT;
    /**
     * The pinned language's lines, loaded on first use.
     *
     * <p>Lazily, because the setting is read out of the config long before the game has a resource
     * manager to read language files with, and a load attempted then would quietly find nothing
     * and cache the emptiness.</p>
     */
    private static Map<String, String> pinned;

    private Lang() {}

    /**
     * Pins Lune to one language, or hands her back to the game's own setting.
     *
     * <p>Separate from the game's language on purpose. Someone playing in English may want Lune in
     * their own language, or the reverse - and someone writing a translation wants to look at it
     * without restarting into a different game.</p>
     */
    public static void select(String code) {
        String wanted = code == null || code.isBlank() ? LuneLanguages.GAME_DEFAULT : code;
        if (wanted.equals(selected)) {
            return;
        }
        selected = wanted;
        pinned = null;
        reload();
    }

    /** Which language is pinned, for the config screen to show. */
    public static String selected() {
        return selected;
    }

    /** Re-reads the pinned language from disk, for a player editing their own file as they go. */
    public static void refresh() {
        pinned = null;
        reload();
    }

    /** The line for {@code key}, with {@code %s} placeholders filled in order. */
    public static String get(String key, Object... args) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        String raw = translate(key);
        if (raw == null) {
            return key;
        }
        if (args == null || args.length == 0) {
            return raw;
        }
        try {
            return String.format(Locale.ROOT, raw, args);
        } catch (RuntimeException formatMismatch) {
            // A translator who dropped a placeholder should lose the number, not the whole screen.
            return raw;
        }
    }

    /**
     * The line for {@code key}, or {@code fallback} when nothing has been written for it.
     *
     * <p>For text whose English is also its identity - a dropdown value that is stored in a saved
     * task and compared against elsewhere - where a missing translation should read as the value
     * rather than as a raw key.</p>
     */
    public static String getOr(String key, String fallback) {
        if (key == null || key.isEmpty()) {
            return fallback;
        }
        String raw = translate(key);
        return raw == null ? fallback : raw;
    }

    /** True when this key resolves to real text rather than to itself. */
    public static boolean has(String key) {
        return key != null && !key.isEmpty() && translate(key) != null;
    }

    /**
     * A numbered set of interchangeable lines: {@code key.1}, {@code key.2}, and so on until one is
     * missing.
     *
     * <p>This is what makes Lune's dialogue editable rather than merely translatable. The count is
     * discovered instead of declared, so a translator who wants six ways of saying good morning
     * where English has four just writes a sixth line, and one who wants two deletes the others.
     * No Java knows how many there are.</p>
     */
    public static List<String> bank(String key) {
        List<String> cached = BANKS.get(key);
        // Checked rather than trusted: switching language in the options menu does not tell us,
        // and a bank cached in English would keep Lune speaking it for the rest of the session.
        // One lookup to notice, against scanning the whole bank on every line she says.
        if (cached != null && cached.get(0).equals(firstLineOf(key))) {
            return cached;
        }
        List<String> lines = new ArrayList<>();
        for (int index = 1; index <= MAX_BANK; index++) {
            String line = translate(key + "." + index);
            if (line == null) {
                break;
            }
            lines.add(line);
        }
        if (lines.isEmpty()) {
            // Better a visible key than a crash on an empty bank at the point of speaking.
            lines.add(has(key) ? translate(key) : key);
        }
        List<String> frozen = List.copyOf(lines);
        BANKS.put(key, frozen);
        return frozen;
    }

    /** One line from a bank, chosen by a caller-supplied rotation rather than at random. */
    public static String pick(String key, int rotation) {
        List<String> lines = bank(key);
        return lines.get(Math.floorMod(rotation, lines.size()));
    }

    /**
     * Drops the cached banks so a reloaded resource pack or a switched language is picked up.
     * Called from the client's resource-reload hook.
     */
    public static void reload() {
        BANKS.clear();
    }

    /** What {@code key.1} says right now, or the fallback a one-line bank was given. */
    private static String firstLineOf(String key) {
        String first = translate(key + ".1");
        if (first != null) {
            return first;
        }
        return has(key) ? translate(key) : key;
    }

    /**
     * The mark that says "this is not a line, it is the game's word for something".
     *
     * <p>Half of what Lune has to name, Minecraft has already named: a Blaze, an Ocak, a Tekne.
     * Writing those out again means a translator types them a second time, a player running a
     * language nobody has translated Lune into reads them in English while their inventory says
     * otherwise, and the two can drift apart - which is how Lune came to call a furnace a
     * {@code Fırın}, the game's word for a Smoker.</p>
     *
     * <p>So a line may instead point at a vanilla key, and Lune asks the game:</p>
     *
     * <pre>"lune.choice.endermen": "@entity.minecraft.enderman"</pre>
     *
     * <p>That line is then right in every language Minecraft ships, including the ones Lune has
     * no translation for, and a translation file simply leaves the key out. It is deliberately
     * opt-in and written down one line at a time: {@code lune.choice.blue} is a colour theme and
     * {@code lune.command.observer.name} is one of Lune's own cards, and guessing from the words
     * would rename both after a dye and a redstone block.</p>
     */
    private static final String GAME_NAME = "@";

    private static String translate(String key) {
        String line = lookUp(key);
        // One hop only. A pointer names a vanilla key, and vanilla lines are never pointers.
        if (line != null && line.length() > 1 && line.startsWith(GAME_NAME)) {
            String fromGame = lookUp(line.substring(1));
            return fromGame != null ? fromGame : line.substring(1);
        }
        return line;
    }

    private static String lookUp(String key) {
        Map<String, String> chosen = pinned();
        if (!chosen.isEmpty()) {
            String line = chosen.get(key);
            if (line != null) {
                return line;
            }
            // A pinned language that has not translated this line yet reads as English rather
            // than as a raw key, the same way vanilla stacks en_us underneath every language.
            String english = bundled().get(key);
            if (english != null) {
                return english;
            }
        }
        try {
            if (I18n.exists(key)) {
                // I18n.get formats immediately. Calling it without arguments turns a valid "%s"
                // line into Minecraft's "Format error: ..." text before get() can fill it.
                return Language.getInstance().getOrDefault(key);
            }
        } catch (Throwable noClient) {
            // Headless tests have no language machinery; the bundled file below answers instead.
        }
        return bundled().get(key);
    }

    private static Map<String, String> pinned() {
        if (LuneLanguages.GAME_DEFAULT.equals(selected)) {
            return Map.of();
        }
        if (pinned == null) {
            pinned = LuneLanguages.load(selected);
        }
        return pinned;
    }

    private static Map<String, String> bundled() {
        if (bundled != null) {
            return bundled;
        }
        Map<String, String> loaded = new HashMap<>();
        try (InputStream stream = Lang.class.getResourceAsStream(BUNDLED)) {
            if (stream != null) {
                JsonObject json = JsonParser.parseReader(
                        new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
                for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                    loaded.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        } catch (Exception unreadable) {
            // Leaves the map empty, and every key renders as itself. Loud, but not fatal.
        }
        bundled = loaded;
        return bundled;
    }
}
