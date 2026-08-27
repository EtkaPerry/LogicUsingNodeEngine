package com.etka.lune.config;

import com.etka.lune.Constants;
import com.etka.lune.bot.path.AStarPathfinder;
import com.etka.lune.platform.Services;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * User-tunable bot settings, backed by {@code config/lune.json}. Plain public fields keep the
 * Config tab's editing code trivial - it binds widgets straight to them.
 */
public final class BotConfig {

    public static final String LUNE_SPEECH_SILENT = "Silent";
    public static final String LUNE_SPEECH_QUIET = "Quiet";
    public static final String LUNE_SPEECH_NORMAL = "Normal";
    public static final String LUNE_SIZE_SMALL = "Small";
    public static final String LUNE_SIZE_NORMAL = "Normal";
    public static final String LUNE_SIZE_LARGE = "Large";
    public static final String LUNE_UI_AUTO = "Auto";
    public static final String LUNE_UI_MATCH_GAME = "Match game";
    public static final String LUNE_UI_COMPACT = "Compact";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static BotConfig instance;

    // --- Movement -----------------------------------------------------------
    /** Sprint on straight, level stretches. */
    public boolean allowSprint = true;
    /** Enter water while pathing. */
    public boolean allowSwim = true;
    /** Largest voluntary drop, in blocks. 3 is the most that never costs health. */
    public int maxFall = 3;
    /** Degrees the view may turn per tick at full speed. Lower looks more human. ~12 is natural. */
    public float turnSpeed = 12.0F;
    /**
     * How sharply a turn starts and stops, in degrees per tick per tick. Low values ease in and out
     * of every turn like a real head; high values approach an instant, mechanical snap to speed.
     */
    public float turnSmoothing = 2.0F;

    // --- Pathfinding --------------------------------------------------------
    /** A* node budget per search. Higher finds longer routes but costs more per tick. */
    public int nodeBudget = 10_000;
    /** Ticks between re-paths while travelling. */
    public int repathInterval = 100;

    // --- Debug --------------------------------------------------------------
    /** Draw the live telemetry overlay in the top-left corner. */
    public boolean showDebug = false;
    /** Include the pathfinder's node/timing counters in the overlay. */
    public boolean debugPathDetail = true;
    /** Write a compact, change-driven text journal for each bot run. */
    public boolean debugRunLog = true;
    /** Draw world-space markers for the bot's current break, movement, placement and stay area. */
    public boolean showActionMarkers = true;

    // --- Learning ----------------------------------------------------------
    /** Persist bounded job best times and normalised skill outcomes, then use safe tactics later. */
    public boolean learningEnabled = true;
    /** Keep the explicit F7/F8 override for the last selected concrete skill tactic. */
    public boolean userLearningEnabled = true;

    // --- Perception ---------------------------------------------------------
    /**
     * When true, and the current world permits it, the bot can target any matching block in loaded
     * chunks (X-ray/cheat). On a non-admin multiplayer account it is always treated as false.
     */
    public boolean omniscientMining = false;
    /**
     * When true, and the current world permits it, the bot can target any mature crop in loaded
     * chunks. On a non-admin multiplayer account it is always treated as false.
     */
    public boolean omniscientHarvesting = false;

    // --- Safety -------------------------------------------------------------
    /** Start emergency preservation, then stop if health cannot be recovered. 0 disables the check. */
    public float stopBelowHealth = 6.0F;
    /** Stop when hunger drops to this or below. 0 disables the check. */
    public int stopBelowFood = 6;
    /** Stop when another player lands a hit - the usual sign an AFK session has been noticed. */
    public boolean stopWhenAttackedByPlayer = true;
    /** Stop when the inventory has no free slot left. */
    public boolean stopWhenFull = true;

    // --- Editor state -------------------------------------------------------
    /** Last routine opened in the editor; harmless when the routine was later deleted. */
    public String lastOpenedRoutine = "";

    // --- Lune assistant -----------------------------------------------------
    /** Hide the assistant and compact in-world status without losing her position or preferences. */
    public boolean showLune = true;
    /** Silent = sleeping/no bubbles, Quiet = actionable speech only, Normal = status speech too. */
    public String luneSpeech = LUNE_SPEECH_NORMAL;
    /** Independent display sizes for the character and her popup. */
    public String luneSize = LUNE_SIZE_NORMAL;
    public String luneChatboxSize = LUNE_SIZE_NORMAL;
    /**
     * How Lune's menus answer the game's GUI scale. Auto shrinks them by the smallest step that
     * gives the layout room; Match game leaves the player's scale alone even where it does not fit.
     */
    public String luneUiScale = LUNE_UI_AUTO;
    /** Screen-relative resting position of the movable Lune assistant. */
    public float mascotScreenX = 0.78F;
    public float mascotScreenY = 0.20F;
    /** Suggestion kinds the player has permanently muted, such as FOOD or CONNECTION. */
    public Set<String> luneDismissedSuggestionTypes = new LinkedHashSet<>();
    /** Routine/kind pairs muted only for that routine. */
    public Set<String> luneDismissedRoutineSuggestions = new LinkedHashSet<>();
    /** Absolute reminder times for "Remind me later", keyed by the exact suggestion identity. */
    public Map<String, Long> luneSuggestionReminders = new LinkedHashMap<>();

    /**
     * Player-tunable ore-layer knowledge. Empty by default; values here override the built-in
     * vanilla defaults. Keys are arbitrary names, values list the block IDs and the Y range.
     */
    public Map<String, OreProfileData> oreProfiles = new LinkedHashMap<>();

    /**
     * Player-tunable biome knowledge - what each biome is worth looking in, and how expensive it is
     * to cross. Empty by default; entries here override the built-in vanilla table and are the way
     * to teach the bot about biomes from a mod it has never seen.
     */
    public Map<String, BiomeProfileData> biomeProfiles = new LinkedHashMap<>();

    public static BotConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    /**
     * How greedily the search heads for the goal. 1.0 is textbook A* (optimal but explores a lot);
     * higher trades a slightly longer route for far fewer nodes expanded. Mining leans harder on
     * this because breaking blocks costs much more than the straight-line estimate can express.
     */
    private static final double WALK_HEURISTIC_WEIGHT = 1.6;
    private static final double MINE_HEURISTIC_WEIGHT = 3.0;

    /** Pathfinder settings derived from the current config. */
    public AStarPathfinder.Settings walkingSettings() {
        return new AStarPathfinder.Settings(maxFall, false, allowSwim, true, nodeBudget,
                WALK_HEURISTIC_WEIGHT, null);
    }

    /** As {@link #walkingSettings()}, but allowed to tunnel through terrain. */
    public AStarPathfinder.Settings miningSettings() {
        return new AStarPathfinder.Settings(maxFall, true, allowSwim, true, nodeBudget,
                MINE_HEURISTIC_WEIGHT, null);
    }

    private static Path file() {
        return Services.PLATFORM.getConfigDir().resolve(Constants.MOD_ID + ".json");
    }

    private static BotConfig load() {
        Path path = file();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                BotConfig loaded = GSON.fromJson(reader, BotConfig.class);
                if (loaded != null) {
                    return loaded;
                }
            } catch (IOException | RuntimeException e) {
                // A hand-edited config with a typo shouldn't stop the mod loading.
                Constants.LOG.warn("Could not read {}, falling back to defaults", path, e);
            }
        }
        return new BotConfig();
    }

    public void save() {
        Path path = file();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            Constants.LOG.warn("Could not write {}", path, e);
        }
    }
}
