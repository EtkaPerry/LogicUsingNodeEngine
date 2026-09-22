package com.etka.lune.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The language file, checked against the source that asks it questions.
 *
 * <p>The whole arrangement rests on keys matching on both sides, and nothing in Java notices when
 * they stop. A renamed key is a silently blank button; a placeholder a translator dropped is a
 * number that never appears. These are the checks that turn both into a failing build.</p>
 */
class LangTest {

    private static final Path LANG = Path.of("src/main/resources/assets/lune/lang");
    private static final Path EN_US = LANG.resolve("en_us.json");
    private static final Path SOURCE = Path.of("src/main/java/com/etka/lune");
    /** Keys written out in Java, as opposed to built from an id at runtime. */
    private static final Pattern LITERAL_KEY = Pattern.compile("\"(lune\\.[a-z0-9_.]+)\"");
    /**
     * Namespaces under {@code lune.} that are not text.
     *
     * <p>System properties the launcher sets and build values baked into the jar. They share the
     * prefix because they share the mod, not because anybody reads them.</p>
     */
    private static final Set<String> NOT_TEXT = Set.of(
            "lune.autorun", "lune.learning", "lune.version", "lune.author", "lune.minecraft",
            "lune.release");
    private static final Pattern PLACEHOLDER =
            Pattern.compile("%(?:%|[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z])");

    private static Map<String, String> lang() throws IOException {
        Map<String, String> entries = new TreeMap<>();
        try (InputStream stream = Files.newInputStream(EN_US)) {
            JsonObject json = JsonParser.parseReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                entries.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        return entries;
    }

    private static List<Path> sources() throws IOException {
        try (Stream<Path> walk = Files.walk(SOURCE)) {
            return walk.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }

    @Test
    void everyKeyWrittenInSourceExists() throws IOException {
        Map<String, String> lang = lang();
        Set<String> missing = new LinkedHashSet<>();
        for (Path path : sources()) {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            Matcher matcher = LITERAL_KEY.matcher(text);
            while (matcher.find()) {
                String key = matcher.group(1);
                if (isNotText(key)) {
                    continue;
                }
                // Banks are asked for by their stem; the numbered lines are what exist.
                if (lang.containsKey(key) || lang.containsKey(key + ".1")) {
                    continue;
                }
                missing.add(key + "  (" + path.getFileName() + ")");
            }
        }
        assertTrue(missing.isEmpty(),
                "keys asked for in Java but absent from en_us.json:\n" + String.join("\n", missing));
    }

    @Test
    void everyLineInTheFileIsAskedFor() throws IOException {
        Map<String, String> lang = lang();
        StringBuilder all = new StringBuilder();
        for (Path path : sources()) {
            all.append(Files.readString(path, StandardCharsets.UTF_8));
        }
        String text = all.toString();

        List<String> orphans = new ArrayList<>();
        for (String key : lang.keySet()) {
            if (!key.startsWith("lune.")) {
                continue;  // vanilla keybind keys, which the game asks for on our behalf
            }
            if (text.contains('"' + key + '"')) {
                continue;
            }
            // A numbered bank line, a key built from an id, or a choice label looked up by value.
            String stem = key.replaceAll("\\.\\d+$", "");
            if (text.contains('"' + stem + '"')) {
                continue;
            }
            if (isDerived(key, text)) {
                continue;
            }
            orphans.add(key);
        }
        assertTrue(orphans.isEmpty(),
                "lines in en_us.json that nothing asks for:\n" + String.join("\n", orphans));
    }

    /**
     * Keys assembled at runtime out of an id the code already holds - a command's, a lesson's, or
     * a dropdown value's. There is no literal to search for, so the shape is what gets checked.
     */
    /** A half-built prefix, a system property, or a build value - none of them lines of text. */
    private static boolean isNotText(String key) {
        if (key.endsWith(".")) {
            return true;
        }
        for (String namespace : NOT_TEXT) {
            if (key.equals(namespace) || key.startsWith(namespace + ".")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDerived(String key, String source) {
        if (key.startsWith("lune.choice.")) {
            return source.contains("optionLabel");
        }
        if (key.startsWith("lune.gui.section.")) {
            // Section headers are grouped by their name and drawn through a lookup on it.
            return source.contains("\"lune.gui.section.\"");
        }
        if (key.startsWith("lune.cheat.")
                && (key.endsWith(".name") || key.endsWith(".about"))) {
            // One pair of lines per cheat, reached through Cheats.Mode rather than by name.
            return source.contains("\"lune.cheat.\" + id()");
        }
        if (key.startsWith("lune.training.")) {
            return source.contains("\"lune.training.\" + id");
        }
        if (key.startsWith("lune.command.")) {
            return source.contains("\"lune.command.\" + id")
                    || source.contains("\"lune.command.\" + owner");
        }
        if (key.startsWith("lune.param.")) {
            // The wording every card shares for a parameter of that name, reached only when the
            // card has written nothing of its own.
            return source.contains("\"lune.param.\" + id");
        }
        if (key.startsWith("lune.mascot.dismissal.")) {
            return source.contains("\"lune.mascot.dismissal.\"");
        }
        if (key.startsWith("lune.task.seeded.")) {
            // A starter job's title, looked up by the id the graph carries.
            return source.contains("\"lune.task.seeded.\" + seededId");
        }
        return false;
    }

    /**
     * The failure that translation invites: a line that is drawn <em>and</em> compared against.
     *
     * <p>Move the drawing into the language file and leave the comparison as an English literal,
     * and the two agree in English and silently stop agreeing in every other language. A folder
     * that opened by default opens closed; a mood never fires. Nothing throws, so nothing tells
     * you. Anything that must be recognised as well as shown needs an identifier of its own.</p>
     */
    @Test
    void nothingIsBothTranslatedAndComparedAgainst() throws IOException {
        Map<String, String> lang = lang();
        Set<String> translated = new LinkedHashSet<>(lang.values());
        // lune.choice.* is the documented exception: a dropdown value is an identifier stored in
        // saved tasks, and only its rendering is translated. Being compared against is the point
        // of it, so its English is exempt even where some other line happens to read the same.
        for (Map.Entry<String, String> entry : lang.entrySet()) {
            if (entry.getKey().startsWith("lune.choice.")) {
                translated.remove(entry.getValue());
            }
        }
        // Deliberately a simple character class rather than an escape-aware one: the alternation
        // that handles \" backtracks catastrophically over a four-thousand-line file.
        List<Pattern> comparisons = List.of(
                Pattern.compile("\"([^\"\\n]*)\"\\s*\\.\\s*"
                        + "(?:equals|equalsIgnoreCase|contentEquals)\\s*\\("),
                Pattern.compile("\\.\\s*(?:equals|equalsIgnoreCase|startsWith|endsWith|contains)"
                        + "\\s*\\(\\s*\"([^\"\\n]*)\""),
                Pattern.compile("case\\s+\"([^\"\\n]*)\""));

        Set<String> clashes = new LinkedHashSet<>();
        for (Path path : sources()) {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            for (Pattern pattern : comparisons) {
                Matcher matcher = pattern.matcher(text);
                while (matcher.find()) {
                    String literal = matcher.group(1);
                    // Single words are identifiers by convention here - choice values, ids,
                    // command names. It is the sentences that give the game away.
                    if (!translated.contains(literal) || !literal.contains(" ")) {
                        continue;
                    }
                    clashes.add(path.getFileName() + ": " + literal);
                }
            }
        }
        assertTrue(clashes.isEmpty(),
                "compared as English and also drawn from the language file:\n"
                        + String.join("\n", clashes));
    }

    /**
     * Rendered status text is for reading, never for deciding.
     *
     * <p>{@code Task.status()} is a sentence in the player's language. Asking whether it contains
     * "water" is a question about English that happens to be true in English, so it survives every
     * test and then quietly answers no for everybody else. That exact line lived in
     * {@code QuickStoneTask}, where it meant a staircase's deliberate stop at a flooded pocket went
     * unrecognised in Turkish and the bot mined out the walls it had just refused to open.</p>
     *
     * <p>The key is the half that means the same in every language. Compare that.</p>
     */
    @Test
    void nothingReadsWordsOutOfRenderedStatusText() throws IOException {
        Pattern assigned = Pattern.compile(
                "\\b(?:String\\s+)?(\\w+)\\s*=\\s*[\\w.()]*\\.(?:status|text)\\(\\)\\s*;");
        List<String> offenders = new ArrayList<>();
        for (Path path : sources()) {
            String text = codeOnly(Files.readString(path, StandardCharsets.UTF_8));
            Set<String> holders = new LinkedHashSet<>();
            Matcher declaration = assigned.matcher(text);
            while (declaration.find()) {
                holders.add(declaration.group(1));
            }
            // The direct form too: someBody.status().contains("...").
            for (String inspect : List.of("contains", "equals", "equalsIgnoreCase", "startsWith",
                    "endsWith", "matches", "indexOf")) {
                if (text.contains(".status()." + inspect + "(")
                        || text.contains(".text()." + inspect + "(")) {
                    offenders.add(path.getFileName() + ": .status()." + inspect + "(...)");
                }
                for (String holder : holders) {
                    Matcher use = Pattern.compile("\\b" + Pattern.quote(holder) + "\\s*\\.\\s*"
                            + inspect + "\\s*\\(\\s*\"").matcher(text);
                    if (use.find()) {
                        offenders.add(path.getFileName() + ": " + holder + "." + inspect + "(\"…\")");
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "decisions made by reading words out of translated status text:\n"
                        + String.join("\n", offenders));
    }

    /**
     * One line of English, one key - wherever that is achievable.
     *
     * <p>Two keys with the same words are two lines a translator has to translate identically and
     * one chance to translate them differently. Not every repeat can be removed: a key built from
     * an id at runtime has no literal to repoint, so a dropdown value and a parameter label that
     * both read "Blocks" are two keys by necessity. Those are exempt. Two hand-written keys saying
     * the same thing are not.</p>
     */
    @Test
    void noTwoHandWrittenKeysSayTheSameThing() throws IOException {
        Map<String, List<String>> byLine = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> entry : lang().entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith("lune.") || isNotText(key) || isBuiltFromAnId(key)) {
                continue;
            }
            byLine.computeIfAbsent(entry.getValue(), ignored -> new ArrayList<>()).add(key);
        }
        List<String> repeats = byLine.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(entry -> entry.getValue() + "  all say: " + entry.getKey())
                .toList();
        assertTrue(repeats.isEmpty(),
                "the same line written under more than one key; point them at one:\n"
                        + String.join("\n", repeats));
    }

    /** A key assembled at runtime, or a numbered line of a dialogue bank. Neither can be merged. */
    private static boolean isBuiltFromAnId(String key) {
        return key.startsWith("lune.command.") || key.startsWith("lune.choice.")
                || key.startsWith("lune.training.") || key.startsWith("lune.param.")
                || key.startsWith("lune.gui.section.")
                || key.startsWith("lune.mascot.dismissal.") || key.matches(".*\\.\\d+$");
    }

    /**
     * Seeded tasks store settings, not sentences.
     *
     * <p>A default task's parameters are dropdown values, written to disk and compared against by
     * the card that reads them. Looking one up in the language file writes a translated string
     * into a Turkish player's shipped tasks, and the cards then fail to recognise their own
     * settings - a bug that cannot happen in English and breaks every other language.</p>
     */
    @Test
    void seededTasksDoNotLookUpTheirOwnSettings() throws IOException {
        Path defaults = SOURCE.resolve("task").resolve("DefaultTasks.java");
        String text = Files.readString(defaults, StandardCharsets.UTF_8);
        List<String> lookups = text.lines()
                .filter(line -> line.contains("Lang.get(") || line.contains("Lang.getOr("))
                .map(String::trim)
                .toList();
        assertTrue(lookups.isEmpty(),
                "DefaultTasks writes task parameters, which are identifiers - it must not "
                        + "translate them:\n" + String.join("\n", lookups));
    }

    /**
     * Text that is looked up cannot be resolved before the language file exists.
     *
     * <p>A {@code static} initialiser runs at class-load, which is earlier than the game has a
     * resource pack open. Whatever it read then is frozen for the session and never follows a
     * change of language.</p>
     */
    @Test
    void nothingResolvesTextInAStaticInitialiser() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path path : sources()) {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            int block = text.indexOf("\n    static {");
            if (block < 0) {
                continue;
            }
            int end = text.indexOf("\n    }", block);
            String body = end < 0 ? text.substring(block) : text.substring(block, end);
            if (resolvesText(body)) {
                offenders.add(path.getFileName().toString());
            }
            // One hop further. `static { registerVanilla(); }` looks clean and froze forty-nine
            // biome notes in whatever language was loaded first; the call is the whole point of
            // a static block, so following it is the only way this test means anything.
            for (String called : calledIn(body)) {
                String callee = methodBody(text, called);
                if (callee != null && resolvesText(callee)) {
                    offenders.add(path.getFileName().toString() + " (via " + called + ")");
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "text resolved at class-load, before there is a language to resolve it in: "
                        + offenders);
    }

    private static boolean resolvesText(String body) {
        return body.contains("Lang.get(") || body.contains("Lang.getOr(");
    }

    /**
     * The file with its comments taken out, so these checks read code rather than prose.
     *
     * <p>A javadoc explaining why {@code failure.contains("water")} was wrong is not itself an
     * instance of it, and a test that cannot tell the difference punishes writing the explanation
     * down.</p>
     */
    private static String codeOnly(String source) {
        StringBuilder kept = new StringBuilder(source.length());
        int at = 0;
        while (at < source.length()) {
            char c = source.charAt(at);
            if (source.startsWith("//", at)) {
                int end = source.indexOf('\n', at);
                at = end < 0 ? source.length() : end;
            } else if (source.startsWith("/*", at)) {
                int end = source.indexOf("*/", at + 2);
                at = end < 0 ? source.length() : end + 2;
            } else if (c == '"') {
                int end = at + 1;
                while (end < source.length() && source.charAt(end) != '"') {
                    end += source.charAt(end) == '\\' ? 2 : 1;
                }
                kept.append(source, at, Math.min(end + 1, source.length()));
                at = end + 1;
            } else {
                kept.append(c);
                at++;
            }
        }
        return kept.toString();
    }

    /** The no-argument private helpers a static block calls; those are what it delegates to. */
    private static List<String> calledIn(String body) {
        List<String> names = new ArrayList<>();
        java.util.regex.Matcher call =
                java.util.regex.Pattern.compile("(?m)^\\s+(\\w+)\\(\\);").matcher(body);
        while (call.find()) {
            names.add(call.group(1));
        }
        return names;
    }

    /** The body of a method declared in the same file, or null when it is somewhere else. */
    private static String methodBody(String text, String name) {
        java.util.regex.Matcher declaration = java.util.regex.Pattern
                .compile("(?m)^    (?:private|static|\\s)+[\\w<>\\[\\], ]+\\s"
                        + java.util.regex.Pattern.quote(name) + "\\(\\)\\s*\\{")
                .matcher(text);
        if (!declaration.find()) {
            return null;
        }
        int end = text.indexOf("\n    }", declaration.end());
        return end < 0 ? text.substring(declaration.end()) : text.substring(declaration.end(), end);
    }

    @Test
    void placeholdersAreWellFormed() throws IOException {
        List<String> broken = new ArrayList<>();
        for (Map.Entry<String, String> entry : lang().entrySet()) {
            String value = entry.getValue();
            for (int i = 0; i < value.length(); i++) {
                if (value.charAt(i) != '%') {
                    continue;
                }
                Matcher matcher = PLACEHOLDER.matcher(value);
                if (!matcher.find(i) || matcher.start() != i) {
                    broken.add(entry.getKey() + " = " + value);
                    break;
                }
                i = matcher.end() - 1;
            }
        }
        assertTrue(broken.isEmpty(),
                "a bare % that String.format will reject:\n" + String.join("\n", broken));
    }

    /**
     * Every shipped translation, checked against the English it is a translation of.
     *
     * <p>Two ways a translation goes wrong quietly. A key that English no longer has is a line
     * nothing will ever ask for, and a line that drops or retypes a placeholder throws inside
     * {@code String.format} - which {@link Lang} catches, so the player simply loses the number
     * rather than seeing a crash. Both are invisible unless something looks.</p>
     *
     * <p>Missing keys are not an error: vanilla stacks en_us underneath, so a half-finished
     * translation reads as English and is perfectly usable.</p>
     */
    @Test
    void everyTranslationMatchesTheEnglishItTranslates() throws IOException {
        Map<String, String> english = lang();
        List<String> problems = new ArrayList<>();
        try (Stream<Path> files = Files.list(LANG)) {
            for (Path path : files.toList()) {
                String name = path.getFileName().toString();
                if (!name.endsWith(".json") || name.equals("en_us.json")) {
                    continue;
                }
                Map<String, String> translated = read(path);
                for (Map.Entry<String, String> entry : translated.entrySet()) {
                    String source = english.get(entry.getKey());
                    if (source == null) {
                        problems.add(name + ": " + entry.getKey() + " is not a key in en_us.json");
                        continue;
                    }
                    Map<Integer, Character> want = arguments(source);
                    Map<Integer, Character> got = arguments(entry.getValue());
                    if (!want.equals(got)) {
                        problems.add(name + ": " + entry.getKey() + "\n     en: " + source
                                + "\n     " + name.replace(".json", "") + ": " + entry.getValue());
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(),
                "translations that would lose a value or point at nothing:\n"
                        + String.join("\n", problems));
    }

    /**
     * Which arguments a line consumes, and as what.
     *
     * <p>Turkish puts words in a different order, so a translation reorders with {@code %2$s
     * %1$s} - the same two arguments, not a mistake. What must not change is which argument goes
     * where and what it is: handing {@code %d} a float throws.</p>
     */
    private static Map<Integer, Character> arguments(String text) {
        Map<Integer, Character> used = new java.util.LinkedHashMap<>();
        Matcher matcher = Pattern.compile("%(?:%|(?:(\\d+)\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?([a-zA-Z]))")
                .matcher(text);
        int position = 0;
        while (matcher.find()) {
            if (matcher.group(0).equals("%%")) {
                continue;
            }
            int index = matcher.group(1) != null
                    ? Integer.parseInt(matcher.group(1)) : ++position;
            used.put(index, Character.toLowerCase(matcher.group(2).charAt(0)));
        }
        return used;
    }

    private static Map<String, String> read(Path path) throws IOException {
        Map<String, String> entries = new TreeMap<>();
        try (InputStream stream = Files.newInputStream(path)) {
            JsonObject json = JsonParser.parseReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                entries.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        return entries;
    }

    @Test
    void shippedTurkishIsComplete() throws IOException {
        Set<String> toTranslate = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : lang().entrySet()) {
            // A line pointing at a vanilla key is not a line: the game supplies it in every
            // language it ships, so a translation that repeated it would be a second copy to
            // keep in step and the first one to go stale.
            if (!entry.getValue().startsWith("@")) {
                toTranslate.add(entry.getKey());
            }
        }
        assertEquals(toTranslate, read(LANG.resolve("tr_tr.json")).keySet(),
                "the bundled Turkish catalog must keep up with new English UI text, "
                        + "and must not restate a line the game already provides");
    }

    /**
     * A pointer has to point at something, and the thing it points at has to be vanilla's.
     *
     * <p>A typo in {@code @item.minecraft.blaze_rodd} is invisible: the line resolves to the key
     * with its marker stripped, which is a plausible-looking string nobody reads twice.</p>
     */
    @Test
    void everyGameNamePointerNamesARealVanillaKey() throws IOException {
        List<String> broken = new ArrayList<>();
        for (Map.Entry<String, String> entry : lang().entrySet()) {
            String line = entry.getValue();
            if (!line.startsWith("@")) {
                continue;
            }
            String target = line.substring(1);
            if (!target.matches("(?:block|item|entity|effect|biome)\\.minecraft\\.[a-z0-9_/.]+")) {
                broken.add(entry.getKey() + " -> " + line + " (not a vanilla name key)");
            } else if (!Lang.has(target)) {
                broken.add(entry.getKey() + " -> " + line + " (no such key in the game)");
            }
        }
        assertTrue(broken.isEmpty(), "pointers that do not resolve:\n" + String.join("\n", broken));
    }

    @Test
    void bankReadsNumberedLinesAndStops() {
        List<String> idle = Lang.bank("lune.mascot.idle.day");
        assertTrue(idle.size() >= 2, "the day bank should hold several lines, got " + idle);
        assertFalse(idle.contains("lune.mascot.idle.day"),
                "an unresolved key leaked into the bank: " + idle);
        // pick() is a rotation, not a random draw: the same number must give the same line.
        assertEquals(Lang.pick("lune.mascot.idle.day", 3), Lang.pick("lune.mascot.idle.day", 3));
        assertEquals(idle.get(0), Lang.pick("lune.mascot.idle.day", idle.size()));
    }

    @Test
    void missingKeyReadsAsItselfAndFallbackWins() {
        assertEquals("lune.nothing.here", Lang.get("lune.nothing.here"));
        assertEquals("carried on", Lang.getOr("lune.nothing.here", "carried on"));
    }

    @Test
    void argumentsAreFilledIn() {
        String line = Lang.get("lune.status.detail", "walking", "42 blocks left");
        assertEquals("walking - 42 blocks left", line);
    }

    @Test
    void aTranslationDroppingAPlaceholderStillRenders() {
        // String.format would throw; a missing number is better than a missing screen.
        assertEquals("Stop", Lang.get("lune.gui.tasks.stop", 1, 2, 3));
    }

    @Test
    void everyKeyIsLowerCaseAndDotted() throws IOException {
        List<String> odd = lang().keySet().stream()
                .filter(key -> key.startsWith("lune."))
                .filter(key -> !key.equals(key.toLowerCase(Locale.ROOT))
                        || key.contains("..") || key.endsWith("."))
                .toList();
        assertTrue(odd.isEmpty(), "keys that break the naming convention: " + odd);
    }
}
