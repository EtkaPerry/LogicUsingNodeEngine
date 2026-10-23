package com.etka.lune.server;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server half is loaded on dedicated servers, which have no client classes at all. One
 * reference to one, anywhere the server half can reach, is a crash on startup for every server
 * owner who installs Lune - and nothing short of starting a server would show it.
 *
 * <p>So this follows every Lune class the {@code server} and {@code net} packages reach - and the
 * graph runner in {@code task/runner}, which a Lumi runs on the server - through the class files' own
 * constant pools, and fails on any that names a class under {@code net.minecraft.client} or a Lune
 * class that is client-only by where it lives.</p>
 */
class ServerSideClassesTest {

    private static final Pattern LUNE_CLASS = Pattern.compile("com/etka/lune/[A-Za-z0-9_/$]+");
    /** Where client-only Lune code lives: the panel, the bot, the client's settings and tasks. */
    private static final List<String> CLIENT_ONLY = List.of(
            "com/etka/lune/client/", "com/etka/lune/bot/", "com/etka/lune/config/",
            "com/etka/lune/task/");
    /**
     * The few classes in those places that a server may load: a graph and how it is written, and
     * the words a run reports in. A closed list on purpose - a class added beside them is
     * client-only until somebody decides otherwise and says so here. Each is still walked, so one
     * that grows a client reference fails all the same.
     */
    private static final List<String> SERVER_SAFE = List.of(
            "com/etka/lune/task/runner/",
            "com/etka/lune/task/TaskGraph", "com/etka/lune/task/TaskNode",
            "com/etka/lune/task/TaskSignalLink", "com/etka/lune/task/TaskDataLink",
            "com/etka/lune/task/TaskPower", "com/etka/lune/task/TaskWiring",
            "com/etka/lune/task/TaskNote", "com/etka/lune/task/TaskGroup",
            "com/etka/lune/task/TaskCanvas", "com/etka/lune/task/TaskCableRoute",
            "com/etka/lune/task/TaskCableAnchor", "com/etka/lune/task/BesideOptions",
            "com/etka/lune/task/TaskCode", "com/etka/lune/task/TaskJson",
            "com/etka/lune/bot/StatusText", "com/etka/lune/bot/StatusKeys",
            "com/etka/lune/bot/StatusSignal", "com/etka/lune/bot/TaskStatus",
            "com/etka/lune/bot/TaskProgress",
            // What a crop is ready for, which tool gets drops, and a block list - asked by a Lumi's
            // hands on the server as by the player's own cards.
            "com/etka/lune/bot/catalog/BlockTarget", "com/etka/lune/bot/catalog/BlockCatalog",
            "com/etka/lune/bot/catalog/ToolCatalog", "com/etka/lune/bot/catalog/Crops");

    private static Path classes() throws URISyntaxException {
        return Path.of(LuneServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    @Test
    void nothingTheServerHalfReachesNamesAClientClass() throws IOException, URISyntaxException {
        Path root = classes();
        Deque<String> pending = new ArrayDeque<>();
        for (String pkg : List.of("com/etka/lune/server", "com/etka/lune/net", "com/etka/lune/task/runner")) {
            try (Stream<Path> files = Files.list(root.resolve(pkg))) {
                files.filter(path -> path.toString().endsWith(".class"))
                        .forEach(path -> pending.add(pkg + "/" + path.getFileName().toString().replace(".class", "")));
            }
        }
        assertTrue(pending.size() >= 8, "found the server half's classes: " + pending);

        Set<String> seen = new LinkedHashSet<>();
        Map<String, String> reachedFrom = new HashMap<>();
        Set<String> problems = new TreeSet<>();
        while (!pending.isEmpty()) {
            String name = pending.poll();
            if (!seen.add(name)) {
                continue;
            }
            for (String prefix : CLIENT_ONLY) {
                if (name.startsWith(prefix) && !serverSafe(name)) {
                    problems.add(name + " is client-only, and the server half reaches it through "
                            + reachedFrom.get(name));
                }
            }
            if (!problems.isEmpty()) {
                // The first way in is the one to fix; everything past it is the same mistake again.
                continue;
            }
            byte[] bytes = classBytes(root, name);
            if (bytes == null) {
                continue;
            }
            for (String text : constantStrings(bytes)) {
                if (text.contains("net/minecraft/client/")) {
                    problems.add(name + " names " + text);
                }
                Matcher lune = LUNE_CLASS.matcher(text);
                while (lune.find()) {
                    reachedFrom.putIfAbsent(lune.group(), name);
                    pending.add(lune.group());
                }
            }
        }
        assertTrue(problems.isEmpty(), "a dedicated server would fail to load these:\n"
                + String.join("\n", problems));
    }

    /**
     * Nothing the server runs renders text: a run's statuses are keys and arguments, read in the
     * language of whoever they reach. Rendering one there would be a sentence in the server's
     * language - and a read of Lune's language tables from the server thread while the client
     * thread may be filling them.
     */
    @Test
    void theGraphRunnerNeverRendersText() throws IOException {
        Path source = Path.of("src/main/java/com/etka/lune/task/runner");
        Pattern rendering = Pattern.compile("\\bLang\\.|\\.text\\(\\)|\\.status\\(\\)");
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(source)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int index = 0; index < lines.size(); index++) {
                    String line = lines.get(index).strip();
                    if (!line.startsWith("*") && !line.startsWith("//") && rendering.matcher(line).find()) {
                        found.add(file.getFileName() + ":" + (index + 1) + " " + line);
                    }
                }
            }
        }
        assertTrue(found.isEmpty(), "the runner renders text:\n" + String.join("\n", found));
    }

    private static boolean serverSafe(String name) {
        for (String entry : SERVER_SAFE) {
            if (entry.endsWith("/") ? name.startsWith(entry)
                    : name.equals(entry) || name.startsWith(entry + "$")) {
                return true;
            }
        }
        return false;
    }

    private static byte[] classBytes(Path root, String name) throws IOException {
        Path file = root.resolve(name + ".class");
        if (Files.isRegularFile(file)) {
            return Files.readAllBytes(file);
        }
        try (InputStream stream = ServerSideClassesTest.class.getResourceAsStream("/" + name + ".class")) {
            return stream == null ? null : stream.readAllBytes();
        }
    }

    /** Every UTF-8 constant in a class file: class names, descriptors and signatures among them. */
    private static List<String> constantStrings(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        in.readInt();
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        List<String> strings = new ArrayList<>();
        for (int index = 1; index < count; index++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> strings.add(in.readUTF());
                case 7, 8, 16, 19, 20 -> in.skipBytes(2);
                case 15 -> in.skipBytes(3);
                case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipBytes(4);
                case 5, 6 -> {
                    in.skipBytes(8);
                    index++;
                }
                default -> throw new IOException("unknown constant pool tag " + tag);
            }
        }
        return strings;
    }
}
