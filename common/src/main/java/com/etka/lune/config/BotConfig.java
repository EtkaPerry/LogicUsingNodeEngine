package com.etka.lune.config;

import com.etka.lune.util.Alerts;
import com.etka.lune.util.Lang;
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
    /** Card colour families for the task canvas. */
    public static final String THEME_SLATE = "Slate";
    public static final String THEME_ORANGE = "Orange";
    public static final String THEME_BLUE = "Blue";
    public static final String THEME_PURPLE = "Purple";
    public static final String THEME_AMBER = "Amber";
    public static final String THEME_GREEN = "Green";

    /** Pin and wire colours, which carry meaning rather than taste. */
    public static final String PINS_CLASSIC = "Classic";
    public static final String PINS_COLOUR_BLIND = "Colour-blind safe";

    /** Follow the game's language setting rather than pinning one of Lune's own. */
    public static final String LANGUAGE_GAME_DEFAULT = "auto";

    public static final String LUNE_UI_AUTO = "Auto";
    public static final String LUNE_UI_MATCH_GAME = "Match game";
    public static final String LUNE_UI_COMPACT = "Compact";

    /**
     * How much bigger or smaller than the fitted size Lune's own text is drawn.
     *
     * <p>Separate from {@link #luneUiScale}, which is a question about layout - how much room the
     * panels would like. This is a question about eyesight, and it gets the last word: a larger
     * step can push a panel below the room it asked for, and the panel is expected to cope.</p>
     */
    public static final String TEXT_SIZE_SMALL = "Small";
    public static final String TEXT_SIZE_NORMAL = "Normal";
    public static final String TEXT_SIZE_LARGE = "Large";
    public static final String TEXT_SIZE_LARGEST = "Largest";

    /** The order the two buttons on the terms page step through, smallest first. */
    public static final java.util.List<String> TEXT_SIZES =
            java.util.List.of(TEXT_SIZE_SMALL, TEXT_SIZE_NORMAL, TEXT_SIZE_LARGE, TEXT_SIZE_LARGEST);

    /** Whole GUI-scale steps away from the fitted size, so every Lune pixel stays a whole pixel. */
    public static int textSizeStep(String textSize) {
        if (TEXT_SIZE_SMALL.equalsIgnoreCase(textSize)) {
            return -1;
        }
        if (TEXT_SIZE_LARGE.equalsIgnoreCase(textSize)) {
            return 1;
        }
        return TEXT_SIZE_LARGEST.equalsIgnoreCase(textSize) ? 2 : 0;
    }

    /** Which face Lune's own panels are drawn in. */
    public static final String FONT_DEFAULT = "Default";
    public static final String FONT_UNIFORM = "Uniform";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static BotConfig instance;

    // --- Terms --------------------------------------------------------------
    /**
     * Highest {@link Terms#VERSION} the player has accepted; 0 until they do, which is what keeps
     * the panel shut and the bot idle on a fresh install.
     */
    public int acceptedTermsVersion = 0;

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

    /**
     * Measure where Lune's own client-tick time goes and show it on the debug overlay.
     *
     * <p>Off by default because it costs a branch per instrumented section even when idle, and
     * because the readout is only meaningful to someone chasing a stall.</p>
     */
    public boolean debugProfiler = false;
    /** Draw world-space markers for the bot's current break, movement, placement and stay area. */
    public boolean showActionMarkers = true;

    // --- Learning ----------------------------------------------------------
    /** Persist bounded job best times and normalised skill outcomes, then use safe tactics later. */
    public boolean learningEnabled = true;
    /** Keep the explicit F7/F8 override for the last selected concrete skill tactic. */
    public boolean userLearningEnabled = true;
    /**
     * Show on each card of the canvas what the learned profile has measured for its job.
     *
     * <p>Off by default: it is a row of numbers on every card that has ever run, and a canvas
     * usually wants to read as a circuit rather than a ledger. Switched on, a card carries its
     * average, last and best pace, and hovering that row lists every situation the job has been
     * measured in - which tool, what size of tree - which is where a player sees what would make
     * it faster.</p>
     */
    public boolean nodeStats = false;

    // --- Perception ---------------------------------------------------------
    // The omniscient modes used to live here, as omniscientMining and omniscientHarvesting. They
    // are cheats rather than settings, so they moved out of this file entirely and into
    // com.etka.lune.bot.util.Cheats, behind /lune omniscient. Nothing here grants X-ray: a config
    // file outlives the session that was allowed one, and a value in it is only ever the player's
    // own assertion of authority. See that class for the whole argument.

    // --- Dashboard ---------------------------------------------------------
    /** Vertical spacing between dashboard lines; larger values make room for custom details. */
    public int dashboardLineHeight = 17;
    /** Optional world position line in the Safety Monitor. */
    public boolean dashboardShowCoordinates = true;
    /** Optional seed line in the Safety Monitor. */
    public boolean dashboardShowSeed = true;
    /** Optional visible-enemies line in the Safety Monitor. */
    public boolean dashboardShowEnemies = true;
    /** Optional saturation line in the Safety Monitor. */
    public boolean dashboardShowSaturation = true;
    /** Optional main/off-hand condition lines in the Safety Monitor. */
    public boolean dashboardShowItemConditions = true;
    /** Optional experience and level line in the Safety Monitor. */
    public boolean dashboardShowExperience = true;
    /** Optional active-effects line in the Safety Monitor. */
    public boolean dashboardShowEffects = true;
    /** Optional movement/environment state line in the Safety Monitor. */
    public boolean dashboardShowEnvironment = true;
    /** Optional local light level line in the Safety Monitor. */
    public boolean dashboardShowLight = true;
    /** Optional facing line in the Safety Monitor. */
    public boolean dashboardShowFacing = true;
    /** Optional learning summary in the Statistics card. */
    public boolean dashboardShowLearning = true;

    // --- Editor state -------------------------------------------------------
    /** Last task opened in the editor; harmless when the task was later deleted. */
    public String lastOpenedTask = "";
    /** Training lesson ids the player has cleared; see {@code com.etka.lune.training}. */
    public Set<String> trainingCompleted = new LinkedHashSet<>();

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
    /**
     * Which language Lune reads in, independent of the game's own setting.
     *
     * <p>Stored as the language code ("tr_tr"), never as the name shown in the dropdown - the name
     * is itself translated, so a config keyed on it would stop matching the moment it changed.</p>
     */
    public String language = LANGUAGE_GAME_DEFAULT;

    public String luneUiScale = LUNE_UI_AUTO;

    /** How much bigger or smaller than the fitted size Lune draws her own text. */
    public String luneTextSize = TEXT_SIZE_NORMAL;

    /** Which face Lune's panels are drawn in; Uniform is the game's even-width fallback font. */
    public String luneFont = FONT_DEFAULT;

    /**
     * Drop the see-through panel backgrounds and the dimmed label grey.
     *
     * <p>Lune's panels are 75% opaque on purpose - the bot is still playing behind them and that is
     * worth seeing. It also means the contrast of every line depends on the world behind it, and
     * dim grey over a snowfield at noon is not text anybody has to put up with.</p>
     */
    public boolean highContrast = false;

    /** Which card colour scheme the task canvas draws with. */
    public String blueprintTheme = THEME_ORANGE;

    /**
     * Whether colour alone is allowed to carry meaning.
     *
     * <p>Named for the task canvas because that is where it started, and kept under that name so
     * the setting somebody already chose survives the upgrade. It now answers for every surface -
     * see {@link #colourBlindSafe()}, which is what those surfaces ask.</p>
     */
    public String blueprintPins = PINS_CLASSIC;

    /**
     * Whether a surface must say in something other than colour what it is saying in colour.
     *
     * <p>Green for running and amber for paused is one channel, and roughly one player in twelve
     * with northern-European ancestry reads it as one colour. Every surface that answers yes here
     * adds a glyph, an outline or a word beside the colour rather than instead of it.</p>
     */
    public boolean colourBlindSafe() {
        return PINS_COLOUR_BLIND.equalsIgnoreCase(blueprintPins);
    }
    /** Whether starting a task from the panel closes it and hands the screen back to the game. */
    public boolean closePanelOnRun = true;
    /** Screen-relative resting position of the movable Lune assistant. */
    public float mascotScreenX = 0.78F;
    public float mascotScreenY = 0.20F;
    /**
     * Speak up when one step of a running task is what is costing the frame rate.
     *
     * <p>On by default: a task that quietly holds the client at a third of its tick rate is the
     * kind of problem a player experiences as "the mod is broken" and has no way to attribute. The
     * warning never changes or cancels anything - it names the step and asks whether that was the
     * intent - so the cost of it being wrong is one dismissable prompt.</p>
     */
    public boolean warnAboutSlowSteps = true;
    /**
     * Whether an alert makes a sound.
     *
     * <p>On by default, and the one that actually does the job: the whole point of an alert is to
     * reach somebody who is in another window, and a toast cannot. See
     * {@link com.etka.lune.util.Alerts}.</p>
     */
    public boolean alertSound = true;
    /**
     * How loud an alert is, as a percentage of full.
     *
     * <p>Full by default, and capped at a hundred because that is where vanilla's sound engine
     * clamps an instance volume before the player's own sliders touch it - see
     * {@link com.etka.lune.util.Alerts#MAX_VOLUME_PERCENT}.</p>
     */
    public int alertVolume = 100;
    /**
     * Which sound each of the automatic alerts makes.
     *
     * <p>Three settings rather than one, because from another window the sound <em>is</em> the
     * message: "it finished", "it gave up" and "it died" are three different things to walk back
     * for, and one tone for all three tells the player only that something happened. The values
     * are {@link com.etka.lune.util.Alerts.Tone} labels - identifiers, written to this file in
     * English, never a translated name. Taken from the enum rather than written out here so a
     * renamed tone cannot leave a default quietly pointing at nothing; that is safe because
     * {@code Tone} holds its sounds as suppliers and so loads no registry to be asked its name.</p>
     */
    public String alertToneFinished = Alerts.Tone.FANFARE.label();
    public String alertToneFailed = Alerts.Tone.BELL.label();
    public String alertToneDeath = Alerts.Tone.ALARM.label();
    /** Whether an alert leaves a toast on screen, for somebody who was looking away rather than out. */
    public boolean alertToast = true;
    /**
     * Whether an alert also writes a chat line.
     *
     * <p>Off by default. Chat already carries the engine's own "task finished" line, so switching
     * this on mostly means hearing it twice; it is here for anyone who reads chat history to find
     * out what happened while they were gone.</p>
     */
    public boolean alertChat = false;
    /** Whether the end of a run - finished or failed - raises an alert on its own. */
    public boolean alertOnTaskEnd = true;
    /** Whether the bot dying raises an alert on its own. */
    public boolean alertOnDeath = true;
    /** Suggestion kinds the player has permanently muted, such as FOOD or CONNECTION. */
    public Set<String> luneDismissedSuggestionTypes = new LinkedHashSet<>();
    /** TaskGraph/kind pairs muted only for that task. */
    public Set<String> luneDismissedTaskSuggestions = new LinkedHashSet<>();
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
            // Once, here, rather than from the config screen: the screen is not the first thing
            // that draws text, and a language applied only when you go looking for the setting
            // is a language that is not applied.
            Lang.select(instance.language);
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
