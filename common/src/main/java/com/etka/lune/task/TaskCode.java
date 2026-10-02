package com.etka.lune.task;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * A task as one line of text: its JSON with no layout, compressed with zlib, in base64url, behind
 * a version mark. About a sixth of the size of the JSON Lune has always exported - Chop Wood goes
 * from twelve thousand characters to two - which is what lets a task travel inside a link.
 *
 * <p>The share page and the share service read the same format with the site's own JavaScript
 * copy of this class. The three have to agree, so a change here is a change there, and a new
 * format gets a new mark rather than a new meaning for the old one.</p>
 */
public final class TaskCode {

    public static final String PREFIX = "v1:";

    /**
     * The most JSON a code may expand to. Checked while inflating rather than after, because a
     * few kilobytes of zlib can be built to expand into gigabytes.
     */
    static final int MAX_JSON_BYTES = 2 * 1024 * 1024;

    /** The ids the share service hands out. */
    private static final Pattern SHARE_ID = Pattern.compile("[A-Za-z0-9]{6,32}");

    private TaskCode() {}

    /** What somebody pasted into Import. */
    public sealed interface Pasted permits Json, Code, Link {}

    /** The JSON Export has always written. */
    public record Json(String json) implements Pasted {}

    /** A code, bare or carried in a link's fragment. */
    public record Code(String code) implements Pasted {}

    /** A link to a task kept by the share service, which still has to be fetched. */
    public record Link(String id) implements Pasted {}

    public static String encode(TaskGraph task) {
        byte[] json = TaskStore.exportCompact(task).getBytes(StandardCharsets.UTF_8);
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(json);
            deflater.finish();
            ByteArrayOutputStream packed = new ByteArrayOutputStream(json.length / 4 + 64);
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                packed.write(buffer, 0, deflater.deflate(buffer));
            }
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(packed.toByteArray());
        } finally {
            deflater.end();
        }
    }

    /**
     * The JSON inside a code.
     *
     * @throws IllegalArgumentException for anything that is not a code this version can read
     */
    public static String decode(String code) {
        String text = code == null ? "" : code.strip();
        if (!text.startsWith(PREFIX)) {
            throw new IllegalArgumentException("not a task code");
        }
        byte[] packed = Base64.getUrlDecoder().decode(text.substring(PREFIX.length()));
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(packed);
            ByteArrayOutputStream json = new ByteArrayOutputStream(packed.length * 6);
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int read = inflater.inflate(buffer);
                if (read == 0 && !inflater.finished()) {
                    // Wanting more input, or a dictionary, both mean the code stops part way.
                    throw new IllegalArgumentException("task code is cut short");
                }
                if (json.size() + read > MAX_JSON_BYTES) {
                    throw new IllegalArgumentException("task code expands too far");
                }
                json.write(buffer, 0, read);
            }
            return json.toString(StandardCharsets.UTF_8);
        } catch (DataFormatException damaged) {
            throw new IllegalArgumentException("task code is damaged", damaged);
        } finally {
            inflater.end();
        }
    }

    /**
     * Reads what was pasted: Export's JSON, a bare code, a link carrying a code, or a link to a
     * task on the share service.
     *
     * <p>A link is only taken from the share service's own address. Import fetches what a link
     * points at, and a clipboard is not somewhere Lune should take a destination from - anything
     * else is not a task, the same answer as for any other text.</p>
     *
     * @param shareBase the share service's address, such as {@code https://lunode.etka.co.uk}
     */
    public static Optional<Pasted> read(String pasted, String shareBase) {
        String text = pasted == null ? "" : pasted.strip();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        if (text.startsWith("{")) {
            return Optional.of(new Json(text));
        }
        if (text.startsWith(PREFIX)) {
            return Optional.of(new Code(text));
        }
        URI link;
        URI home;
        try {
            link = URI.create(text);
            home = URI.create(shareBase);
        } catch (IllegalArgumentException notALink) {
            return Optional.empty();
        }
        String scheme = link.getScheme() == null ? "" : link.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            return Optional.empty();
        }
        String fragment = link.getRawFragment();
        if (fragment != null && fragment.startsWith(PREFIX)) {
            return Optional.of(new Code(fragment));
        }
        if (link.getHost() == null || !link.getHost().equalsIgnoreCase(home.getHost())
                || link.getPort() != home.getPort()) {
            return Optional.empty();
        }
        String path = link.getPath() == null ? "" : link.getPath().replaceAll("^/+|/+$", "");
        return SHARE_ID.matcher(path).matches() ? Optional.of(new Link(path)) : Optional.empty();
    }
}
