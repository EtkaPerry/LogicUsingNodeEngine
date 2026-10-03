package com.etka.lune.bot.input;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every hand action a card takes goes through {@link GameModeGate}, so a task beside the player
 * that leaves the mouse to the player - or puts the player first - keeps Lune's hands off it.
 *
 * <p>The gate is only as good as the code that cannot get round it. A card that swings, clicks or
 * closes a screen through the game's own route acts on the player's behalf with nobody's hand on
 * the mouse, and nothing at run time would say so. So the source is read for those routes.</p>
 */
class HandsGoThroughTheGateTest {

    private static final List<Path> ROOTS = List.of(
            Path.of("src/main/java/com/etka/lune/bot"),
            Path.of("src/main/java/com/etka/lune/mods"));
    /**
     * The gate itself, and the engine's own plumbing: the handover holds and lets go of keys on
     * the player's behalf, and the engine, the context and the harness build what the cards use.
     */
    private static final Set<String> ALLOWED = Set.of("GameModeGate.java", "Handover.java",
            "HeldKeys.java", "BotEngine.java", "BotContext.java", "AutoRun.java");

    /** The game's own routes for a hand; the gate's methods of the same names are what to use. */
    private static final Pattern AROUND_THE_GATE = Pattern.compile(
            "\\bHands\\.(?:swing|dropHeld)\\("
                    + "|(?<!\\bgameMode)\\.(?:closeContainer|stopUsingItem)\\(\\)"
                    + "|\\bHeldKeys\\.set\\("
                    + "|(?<!\\bctx)\\.gameMode\\b");

    @Test
    void noCardActsOnTheGameExceptThroughTheGate() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path root : ROOTS) {
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path path : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                    if (ALLOWED.contains(path.getFileName().toString())) {
                        continue;
                    }
                    String code = codeOnly(Files.readString(path, StandardCharsets.UTF_8));
                    Matcher matcher = AROUND_THE_GATE.matcher(code);
                    while (matcher.find()) {
                        offenders.add(path.getFileName() + ": " + line(code, matcher.start()));
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "hand actions that go round GameModeGate, and so reach the game while the mouse "
                        + "is the player's - use ctx.gameMode instead:\n" + String.join("\n", offenders));
    }

    private static String line(String code, int at) {
        int start = code.lastIndexOf('\n', at) + 1;
        int end = code.indexOf('\n', at);
        return code.substring(start, end < 0 ? code.length() : end).trim();
    }

    /** The file without its comments, which may name what the code must not do. */
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
}
