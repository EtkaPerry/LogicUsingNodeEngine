package com.etka.lune.client.gui;

import net.minecraft.client.gui.navigation.ScreenRectangle;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tooltips on Lune's pages, which the game draws after the page's own scale is undone.
 *
 * <p>Getting the anchor wrong cannot be seen in a window small enough for Lune's scale to equal the
 * game's, and cannot be seen headless at all. So the arithmetic is checked here, and the source is
 * read for the two ways around it: a widget handed a tooltip of its own, and a tooltip left for the
 * end of the frame with its anchor still in the page's pixels.</p>
 */
class ScaledTooltipsTest {

    private static final Path CLIENT = Path.of("src/main/java/com/etka/lune/client");
    /** The one file allowed to hand the game a widget tooltip, having switched its drawing off. */
    private static final String OWNER = "ScaledTooltips.java";

    @Test
    void aRectangleIsCarriedIntoTheGamesPixels() {
        ScreenRectangle same = ScaledTooltips.toGame(new ScreenRectangle(30, 60, 90, 18), 1.0F);
        assertEquals(new ScreenRectangle(30, 60, 90, 18), same);
        // Lune at 3 inside the game's 4: a Lune pixel is three quarters of a game pixel.
        ScreenRectangle smaller = ScaledTooltips.toGame(new ScreenRectangle(40, 80, 48, 20), 0.75F);
        assertEquals(new ScreenRectangle(30, 60, 36, 15), smaller);
        // Edges are carried over, not the size, so neighbours still meet after rounding.
        ScreenRectangle thirds = ScaledTooltips.toGame(new ScreenRectangle(10, 10, 20, 20), 2.0F / 3.0F);
        assertEquals(7, thirds.left());
        assertEquals(20, thirds.right());
    }

    /**
     * A widget's own tooltip is anchored in the page's pixels and drawn in the game's, so on a
     * scaled page it goes through {@link ScaledTooltips} instead.
     */
    @Test
    void noWidgetOnAScaledPageDrawsItsOwnTooltip() throws IOException {
        Pattern own = Pattern.compile("\\.tooltip\\(\\s*[^)\\s]|\\bsetTooltip(?:Delay)?\\(");
        List<String> offenders = new ArrayList<>();
        for (Path path : sources()) {
            if (path.getFileName().toString().equals(OWNER)) {
                continue;
            }
            String code = codeOnly(Files.readString(path, StandardCharsets.UTF_8));
            Matcher matcher = own.matcher(code);
            while (matcher.find()) {
                offenders.add(path.getFileName() + ": " + line(code, matcher.start()));
            }
        }
        assertTrue(offenders.isEmpty(),
                "widget tooltips that bypass ScaledTooltips, and so land in the wrong place "
                        + "whenever Lune's scale is not the game's:\n" + String.join("\n", offenders));
    }

    /**
     * A tooltip left for the end of the frame by hand hands over its anchor in the game's pixels.
     */
    @Test
    void everyTooltipLeftForLaterIsAnchoredInTheGamesPixels() throws IOException {
        Pattern call = Pattern.compile("TooltipForNextFrame\\(");
        List<String> offenders = new ArrayList<>();
        for (Path path : sources()) {
            if (path.getFileName().toString().equals(OWNER)) {
                continue;
            }
            String code = codeOnly(Files.readString(path, StandardCharsets.UTF_8));
            Matcher matcher = call.matcher(code);
            while (matcher.find()) {
                String arguments = arguments(code, matcher.end());
                if (!arguments.contains("toGamePixels(")) {
                    offenders.add(path.getFileName() + ": " + line(code, matcher.start()));
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "tooltips anchored in the page's pixels, which the game reads as its own:\n"
                        + String.join("\n", offenders));
    }

    private static List<Path> sources() throws IOException {
        try (Stream<Path> walk = Files.walk(CLIENT)) {
            return walk.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }

    /** From just inside an opening bracket to its partner, string literals skipped. */
    private static String arguments(String code, int from) {
        int depth = 1;
        int at = from;
        while (at < code.length() && depth > 0) {
            char c = code.charAt(at);
            if (c == '"') {
                at++;
                while (at < code.length() && code.charAt(at) != '"') {
                    at += code.charAt(at) == '\\' ? 2 : 1;
                }
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            at++;
        }
        return code.substring(from, Math.min(at, code.length()));
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
