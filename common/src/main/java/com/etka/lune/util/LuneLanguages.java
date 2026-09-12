package com.etka.lune.util;

import com.etka.lune.Constants;
import com.etka.lune.platform.Services;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.LanguageInfo;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Which languages Lune can be read in, and where they come from.
 *
 * <p>Three sources, in the order they win. A file the player dropped in
 * {@code config/lune-lang/} beats one that shipped in the jar, which beats nothing - so somebody
 * who wants to reword one line of Turkish does not have to wait for a release, and somebody
 * translating a language nobody has translated yet can do it without a compiler.</p>
 *
 * <p>The list always opens with {@link #GAME_DEFAULT}, which is not a language: it means "whatever
 * the game is set to", which is what almost everybody wants and what a fresh install uses.</p>
 */
public final class LuneLanguages {

    /** Follow the game's own language setting rather than pinning one. */
    public static final String GAME_DEFAULT = "auto";
    public static final String ENGLISH = "en_us";
    /** Where a player's own translations go, one {@code <code>.json} per language. */
    private static final String USER_DIRECTORY = Constants.MOD_ID + "-lang";

    private LuneLanguages() {}

    /**
     * The folder a player drops their own language files into, or null before the loader is up.
     *
     * <p>Created on demand by whoever writes into it, never here - an empty folder in everybody's
     * config directory is a question nobody asked.</p>
     */
    public static Path userDirectory() {
        try {
            return Services.PLATFORM.getConfigDir().resolve(USER_DIRECTORY);
        } catch (Throwable noPlatform) {
            // Headless tests have no loader to ask for a config directory.
            return null;
        }
    }

    /**
     * Everything the dropdown can offer: the game default, English, and every other language
     * file found either in the jar (or a resource pack) or in the player's own folder.
     */
    public static List<String> available() {
        Set<String> codes = new LinkedHashSet<>();
        codes.add(GAME_DEFAULT);
        codes.add(ENGLISH);
        // Sorted so the list does not reshuffle itself between openings of the screen.
        Set<String> found = new TreeSet<>();
        found.addAll(shipped());
        found.addAll(userSupplied());
        codes.addAll(found);
        return List.copyOf(codes);
    }

    /** What to call a language on screen: the game's own name for it, or the file's name. */
    public static String displayName(String code) {
        if (code == null || code.isEmpty() || GAME_DEFAULT.equals(code)) {
            return Lang.get("lune.gui.config.language_game_default");
        }
        try {
            LanguageInfo info = Minecraft.getInstance().getLanguageManager().getLanguage(code);
            if (info != null) {
                String name = info.name() + " (" + info.region() + ")";
                return userSupplied().contains(code)
                        ? Lang.get("lune.gui.config.language_custom", name) : name;
            }
        } catch (Throwable noClient) {
            // Headless, or a code the game has never heard of. The file name says enough.
        }
        return userSupplied().contains(code)
                ? Lang.get("lune.gui.config.language_custom", code) : code;
    }

    /**
     * Every line of one language, or an empty map when there is no such file.
     *
     * <p>The player's own file is read last so it lands on top of the shipped one: a file with
     * three lines in it changes three lines and leaves the rest alone.</p>
     */
    public static Map<String, String> load(String code) {
        if (code == null || code.isEmpty() || GAME_DEFAULT.equals(code)) {
            return Map.of();
        }
        Map<String, String> lines = new HashMap<>();
        // The jar is also available before resource packs load and in headless tests.
        // Resource packs and the user's file still override these bundled defaults below.
        try (InputStream stream = LuneLanguages.class.getResourceAsStream(
                "/assets/lune/lang/" + code + ".json")) {
            if (stream != null) {
                readInto(new InputStreamReader(stream, StandardCharsets.UTF_8), lines);
            }
        } catch (Exception unreadable) {
            Constants.LOG.warn("Could not read the bundled {} translation", code, unreadable);
        }
        for (Resource resource : shippedFiles(code)) {
            try (InputStream stream = resource.open()) {
                readInto(new InputStreamReader(stream, StandardCharsets.UTF_8), lines);
            } catch (Exception unreadable) {
                Constants.LOG.warn("Could not read the bundled {} translation", code, unreadable);
            }
        }
        Path directory = userDirectory();
        Path own = directory == null ? null : directory.resolve(code + ".json");
        if (own != null && Files.isRegularFile(own)) {
            try (Reader reader = Files.newBufferedReader(own, StandardCharsets.UTF_8)) {
                readInto(reader, lines);
            } catch (Exception unreadable) {
                Constants.LOG.warn("Could not read {}", own, unreadable);
            }
        }
        return lines;
    }

    private static void readInto(Reader reader, Map<String, String> into) {
        JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            if (entry.getValue().isJsonPrimitive()) {
                into.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
    }

    /** Language codes shipped in the jar, plus any a resource pack adds for this mod. */
    private static Set<String> shipped() {
        Set<String> codes = new TreeSet<>();
        ResourceManager resources = resourceManager();
        if (resources == null) {
            codes.add(ENGLISH);
            return codes;
        }
        try {
            for (Identifier id : resources.listResources("lang", LuneLanguages::isOurLanguageFile)
                    .keySet()) {
                String path = id.getPath();
                codes.add(path.substring("lang/".length(), path.length() - ".json".length()));
            }
        } catch (Throwable unavailable) {
            codes.add(ENGLISH);
        }
        return codes;
    }

    private static boolean isOurLanguageFile(Identifier id) {
        return Constants.MOD_ID.equals(id.getNamespace()) && id.getPath().endsWith(".json");
    }

    private static List<Resource> shippedFiles(String code) {
        ResourceManager resources = resourceManager();
        if (resources == null) {
            return List.of();
        }
        try {
            return resources.getResourceStack(Identifier.fromNamespaceAndPath(
                    Constants.MOD_ID, "lang/" + code + ".json"));
        } catch (Throwable unavailable) {
            return List.of();
        }
    }

    private static Set<String> userSupplied() {
        Path directory = userDirectory();
        if (directory == null || !Files.isDirectory(directory)) {
            return Set.of();
        }
        Set<String> codes = new TreeSet<>();
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> found = new ArrayList<>(files.toList());
            for (Path file : found) {
                String name = file.getFileName().toString();
                if (name.toLowerCase(Locale.ROOT).endsWith(".json")) {
                    codes.add(name.substring(0, name.length() - ".json".length()));
                }
            }
        } catch (Exception unreadable) {
            Constants.LOG.warn("Could not list {}", directory, unreadable);
        }
        return codes;
    }

    private static ResourceManager resourceManager() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            return minecraft == null ? null : minecraft.getResourceManager();
        } catch (Throwable noClient) {
            return null;
        }
    }
}
