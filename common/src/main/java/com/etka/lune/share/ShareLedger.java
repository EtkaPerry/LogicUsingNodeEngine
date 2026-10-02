package com.etka.lune.share;

import com.etka.lune.Constants;
import com.etka.lune.platform.Services;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The links this player has made, with the key that deletes each one, in
 * {@code config/lune-shares.json}.
 *
 * <p>The service hands the keys back exactly once, on upload, and keeps only their hashes, so this
 * file is the only place they exist. Losing it costs nothing worse than waiting: a link nobody
 * opens is deleted after thirty days either way.</p>
 */
public final class ShareLedger {

    /** One link: what it was made from and how to take it back. */
    public static final class Entry {
        public String id;
        public String link;
        public String deleteKey;
        /** Null for a view-only link. Whoever holds it can change the task, so it stays here. */
        public String editKey;
        /** The task's name when it was shared, so a list of links can say what each one is. */
        public String task;
        /** When it was made, in milliseconds since the epoch. */
        public long created;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ShareLedger() {}

    public static synchronized void remember(ShareService.Shared shared, String task) {
        List<Entry> entries = load();
        Entry entry = new Entry();
        entry.id = shared.id();
        entry.link = shared.link();
        entry.deleteKey = shared.deleteKey();
        entry.editKey = shared.editKey();
        entry.task = task;
        entry.created = System.currentTimeMillis();
        entries.add(entry);
        save(entries);
    }

    public static synchronized Optional<Entry> byId(String id) {
        return load().stream().filter(entry -> entry.id != null && entry.id.equals(id)).findFirst();
    }

    public static synchronized List<Entry> all() {
        return List.copyOf(load());
    }

    public static synchronized void forget(String id) {
        List<Entry> entries = load();
        if (entries.removeIf(entry -> entry.id != null && entry.id.equals(id))) {
            save(entries);
        }
    }

    private static Path file() {
        return Services.PLATFORM.getConfigDir().resolve(Constants.MOD_ID + "-shares.json");
    }

    private static List<Entry> load() {
        Path path = file();
        if (!Files.exists(path)) {
            return new ArrayList<>();
        }
        try (Reader reader = Files.newBufferedReader(path)) {
            List<Entry> entries = GSON.fromJson(reader, new TypeToken<List<Entry>>() {}.getType());
            List<Entry> kept = new ArrayList<>();
            if (entries != null) {
                entries.stream().filter(entry -> entry != null && entry.id != null).forEach(kept::add);
            }
            return kept;
        } catch (IOException | RuntimeException e) {
            Constants.LOG.warn("Could not read {}", path, e);
            return new ArrayList<>();
        }
    }

    private static void save(List<Entry> entries) {
        Path path = file();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(entries, writer);
            }
        } catch (IOException e) {
            Constants.LOG.warn("Could not write {}", path, e);
        }
    }
}
