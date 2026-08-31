package com.etka.lune.bot;

import com.etka.lune.Constants;
import com.etka.lune.platform.Services;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persists the last completed run and all-time dashboard totals independently of UI settings. */
public final class BotStatisticsStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static BotStatisticsStore instance;

    private final Path file;
    private Data data;

    public BotStatisticsStore(Path file) {
        this.file = file;
        this.data = load(file);
    }

    public static synchronized BotStatisticsStore get() {
        if (instance == null) {
            instance = new BotStatisticsStore(defaultFile());
        }
        return instance;
    }

    public static Path defaultFile() {
        return Services.PLATFORM.getConfigDir().resolve(Constants.MOD_ID + "-statistics.json");
    }

    public synchronized BotStatistics lastRun() {
        return data.lastRun.copy();
    }

    public synchronized BotStatistics allTime() {
        return data.allTime.copy();
    }

    /** Stores one finished or interrupted run and adds it to the lifetime totals. */
    public synchronized void recordRun(BotStatistics run) {
        BotStatistics safe = run == null ? new BotStatistics() : run.copy();
        data.lastRun = safe;
        data.allTime.add(safe);
        save();
    }

    private static Data load(Path file) {
        if (file != null && Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file)) {
                Data loaded = GSON.fromJson(reader, Data.class);
                if (loaded != null) {
                    if (loaded.lastRun == null) {
                        loaded.lastRun = new BotStatistics();
                    }
                    if (loaded.allTime == null) {
                        loaded.allTime = new BotStatistics();
                    }
                    return loaded;
                }
            } catch (IOException | RuntimeException e) {
                Constants.LOG.warn("Could not read dashboard statistics from {}", file, e);
            }
        }
        return new Data();
    }

    private void save() {
        if (file == null) {
            return;
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(data, writer);
            }
        } catch (IOException e) {
            Constants.LOG.warn("Could not write dashboard statistics to {}", file, e);
        }
    }

    private static final class Data {
        private BotStatistics lastRun = new BotStatistics();
        private BotStatistics allTime = new BotStatistics();
    }
}
