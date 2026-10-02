package com.etka.lune.server;

import com.etka.lune.Constants;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@code config/lune-server.json}: where a server keeps its rules for Lune.
 *
 * <p>Two lines an owner can read and edit without the game running:</p>
 * <pre>
 * {
 *   "run": "operators",
 *   "cheats": "operators"
 * }
 * </pre>
 *
 * <p>The file is watched rather than read once, so an owner with nothing but a console edits it and
 * the players hear the new rules a moment later, without a restart and without a command. A file
 * that cannot be read is left alone: it is somebody's half-finished edit, and the rules already in
 * force stay in force until they finish it - or until an operator changes a rule in game, which
 * writes the file out whole.</p>
 *
 * <p>Server-side, but nothing here depends on that, so it is tested on its own.</p>
 */
public final class ServerRulesFile {

    public static final String NAME = "lune-server.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path path;
    /** When the file was last read or written by this class, so its own writes are not news. */
    private FileTime seen;

    public ServerRulesFile(Path path) {
        this.path = Objects.requireNonNull(path, "path");
    }

    public Path path() {
        return path;
    }

    public boolean exists() {
        return Files.isRegularFile(path);
    }

    /**
     * The rules in the file, or {@code defaults} when there is none or it cannot be read.
     *
     * @param create write {@code defaults} out when there is no file yet, so an owner has something
     *               to find and edit. A dedicated server does; a world opened to LAN does not, and
     *               only gets a file once its host changes something.
     */
    public ServerRules load(ServerRules defaults, boolean create) {
        if (!exists()) {
            if (create) {
                save(defaults);
            }
            return defaults;
        }
        ServerRules read = read(defaults);
        return read == null ? defaults : read;
    }

    /**
     * The rules on disk if the file has changed since it was last read or written here and now
     * says something other than {@code current}; otherwise null. Cheap when nothing changed: one
     * look at the file's modified time.
     */
    public ServerRules changedSince(ServerRules current) {
        FileTime modified = modified();
        if (modified == null || modified.equals(seen)) {
            return null;
        }
        ServerRules read = read(current);
        return read == null || read.equals(current) ? null : read;
    }

    /** Writes the rules down. Returns false, having said why in the log, when it could not. */
    public boolean save(ServerRules rules) {
        JsonObject json = new JsonObject();
        rules.write(json);
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(json, writer);
                writer.write(System.lineSeparator());
            }
            seen = modified();
            return true;
        } catch (IOException | RuntimeException e) {
            Constants.LOG.warn("Could not write Lune's server rules to {}: {}", path, e.toString());
            return false;
        }
    }

    /** The rules in the file, with {@code fallback}'s for anything missing; null if unreadable. */
    private ServerRules read(ServerRules fallback) {
        seen = modified();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (root == null || !root.isJsonObject()) {
                Constants.LOG.warn("{} does not hold Lune's server rules as a JSON object; the rules in"
                        + " force stay as they are", path);
                return null;
            }
            List<ServerRules.Problem> problems = new ArrayList<>();
            ServerRules rules = ServerRules.read(root.getAsJsonObject(), fallback, problems);
            for (ServerRules.Problem problem : problems) {
                if (problem.found() == null) {
                    Constants.LOG.warn("{}: \"{}\" is missing; using \"{}\"", path, problem.field(),
                            problem.used().id());
                } else {
                    Constants.LOG.warn("{}: \"{}\" is {}, which is not everyone, operators or nobody;"
                            + " using \"{}\"", path, problem.field(), problem.found(), problem.used().id());
                }
            }
            return rules;
        } catch (IOException | RuntimeException e) {
            Constants.LOG.warn("Could not read Lune's server rules from {}; the rules in force stay as"
                    + " they are: {}", path, e.toString());
            return null;
        }
    }

    private FileTime modified() {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException | RuntimeException gone) {
            return null;
        }
    }
}
