package com.etka.lune.client.gui.tab;

import com.etka.lune.bot.command.CommandDef;
import com.etka.lune.bot.command.Param;
import com.etka.lune.client.gui.LuneScreen;
import com.etka.lune.client.gui.LuneTab;
import com.etka.lune.client.gui.mascot.MascotAdvisor;
import com.etka.lune.client.gui.widget.ParamPanel;
import com.etka.lune.bot.util.OmniscientAccess;
import com.etka.lune.config.BotConfig;
import com.etka.lune.platform.BuildFeatures;
import com.etka.lune.task.TaskStore;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;

/**
 * Global bot settings.
 * <p>
 * Reuses {@link ParamPanel} by describing the config as a synthetic command - the same parameter
 * types already render and edit correctly, so the settings screen costs a list of parameters rather
 * than a second editor implementation. Values are copied back into {@link BotConfig} every tick and
 * written to disk when the panel closes.
 */
public class ConfigTab extends LuneTab {

    private static final int MARGIN = 10;
    /** The node budget is edited in thousands; single-stepping to 10,000 would be absurd. */
    private static final int BUDGET_SCALE = 1000;

    /**
     * Turn smoothness is stored as an acceleration in degrees per tick squared, which is not a
     * number anyone wants to pick off a slider. The slider runs 1 to 10, snappiest to smoothest,
     * and these bounds map it onto the useful part of that range.
     */
    private static final int SMOOTHNESS_STEPS = 10;
    private static final float SNAPPIEST = 6.0F;
    private static final float SMOOTHEST = 0.75F;

    private final ParamPanel panel;
    private final CommandDef settings;
    private final Button restoreSuggestionsButton;
    private final Button restoreDefaultTasksButton;
    private final Button resetSettingsButton;
    /** Reset is the one footer button that cannot be undone, so it asks first. */
    private boolean confirmingReset;

    private static final int FOOTER_BUTTON_WIDTH = 180;
    private static final int FOOTER_BUTTON_GAP = 8;
    private static final int FOOTER_BUTTON_H = 18;

    public ConfigTab() {
        super(Component.literal("Config"));

        BotConfig config = BotConfig.get();
        List<Param<?>> parameters = buildParameters(config);
        settings = new CommandDef("settings", "Settings", "Global bot behaviour", parameters,
                def -> null);

        panel = add(new ParamPanel(0, 0, 10, 10));
        panel.setCommand(settings);
        panel.setCompactMode(true);
        panel.setParameterEnabled(id -> !isOmniscientParameter(id)
                || OmniscientAccess.isAllowed(Minecraft.getInstance()));
        panel.setSections(configSections());
        restoreSuggestionsButton = add(Button.builder(Component.literal("Restore Lune suggestions"),
                button -> restoreSuggestions()).size(180, 18).build());
        restoreDefaultTasksButton = add(Button.builder(Component.literal("Restore default tasks"),
                button -> restoreDefaultTasks()).size(FOOTER_BUTTON_WIDTH, 18).build());
        resetSettingsButton = add(Button.builder(Component.literal("Reset config settings"),
                button -> resetSettings()).size(FOOTER_BUTTON_WIDTH, 18).build());
    }

    /**
     * The editable settings, read out of a config.
     *
     * <p>Taking the config as an argument rather than reaching for the live one is what lets Reset
     * work: it builds the same list from a brand new {@link BotConfig}, whose fields are the shipped
     * defaults by definition, and copies those values across. No second list of defaults to drift.</p>
     */
    private List<Param<?>> buildParameters(BotConfig config) {
        boolean omniscientAllowed = OmniscientAccess.isAllowed(Minecraft.getInstance());
        List<Param<?>> parameters = new ArrayList<>(List.of(
                new Param.Bool("allow_sprint", "Allow sprint",
                        "Sprint on straight, level ground", config.allowSprint),
                new Param.Bool("allow_swim", "Allow swimming",
                        "Let the pathfinder route through water", config.allowSwim),
                new Param.Ints("max_fall", "Max fall",
                        "Largest voluntary drop, in blocks", config.maxFall, 0, 20),
                new Param.Ints("turn_speed", "Turn speed",
                        "Degrees the view may turn per tick at full speed", (int) config.turnSpeed, 1, 90),
                new Param.Ints("turn_smoothness", "Turn smoothness",
                        "How gently turns start and stop. 1 snaps straight to full speed; "
                                + "10 eases in and out of every turn like a real head",
                        smoothnessSlider(config.turnSmoothing), 1, SMOOTHNESS_STEPS),
                new Param.Ints("node_budget", "Path budget (x1000)",
                        "A* nodes per search; higher finds longer routes",
                        config.nodeBudget / BUDGET_SCALE, 1, 100),
                new Param.Ints("repath", "Repath interval",
                        "Ticks between route recalculations", config.repathInterval, 20, 600),
                new Param.Choice("ui_scale", "Menu size",
                        "Auto shrinks Lune's menus by the smallest step that fits the layout, and "
                                + "never past half the game's GUI scale; Match game keeps your GUI "
                                + "scale exactly; Compact shrinks as far as Auto is allowed to",
                        List.of(BotConfig.LUNE_UI_AUTO, BotConfig.LUNE_UI_MATCH_GAME,
                                BotConfig.LUNE_UI_COMPACT), config.luneUiScale),
                new Param.Ints("dashboard_line_height", "Dashboard line spacing",
                        "Vertical spacing between Main dashboard lines; increase it when you add more detail",
                        config.dashboardLineHeight, DashboardFrame.MIN_ROW_H, DashboardFrame.MAX_ROW_H),
                new Param.Bool("dashboard_coordinates", "Dashboard coordinates",
                        "Show the player's position in the Safety Monitor", config.dashboardShowCoordinates),
                new Param.Bool("dashboard_seed", "Dashboard seed",
                        "Show the world seed when the client is allowed to know it", config.dashboardShowSeed),
                new Param.Bool("dashboard_enemies", "Dashboard visible enemies",
                        "Show the number of hostile mobs currently visible to the player",
                        config.dashboardShowEnemies),
                new Param.Bool("dashboard_saturation", "Dashboard saturation",
                        "Show the player's hidden food-saturation reserve",
                        config.dashboardShowSaturation),
                new Param.Bool("dashboard_items", "Dashboard item conditions",
                        "Show main-hand and off-hand item condition lines", config.dashboardShowItemConditions),
                new Param.Bool("dashboard_experience", "Dashboard experience",
                        "Show experience level and progress", config.dashboardShowExperience),
                new Param.Bool("dashboard_effects", "Dashboard active effects",
                        "Show how many potion or status effects are active", config.dashboardShowEffects),
                new Param.Bool("dashboard_environment", "Dashboard environment",
                        "Show whether the player is grounded, swimming, burning, or airborne",
                        config.dashboardShowEnvironment),
                new Param.Bool("dashboard_light", "Dashboard light level",
                        "Show block and sky light at the player's position", config.dashboardShowLight),
                new Param.Bool("dashboard_facing", "Dashboard facing",
                        "Show the player's current facing direction", config.dashboardShowFacing),
                new Param.Bool("dashboard_learning", "Dashboard learning details",
                        "Show the learning summary instead of queue size in Statistics",
                        config.dashboardShowLearning),
                new Param.Choice("blueprint_theme", "Node colours",
                        "The colour family task cards are drawn from. Cards are shaded by the "
                                + "role they play - source, decision, signal, work - within whichever "
                                + "family you pick",
                        List.of(BotConfig.THEME_SLATE, BotConfig.THEME_BLUE,
                                BotConfig.THEME_PURPLE, BotConfig.THEME_AMBER,
                                BotConfig.THEME_GREEN), config.blueprintTheme),
                new Param.Choice("blueprint_pins", "Pin colours",
                        "Success and Fail are green and red, the pair red-green colour blindness "
                                + "makes hardest to tell apart. Colour-blind safe swaps every pin "
                                + "and wire to the Okabe-Ito palette, where they stay distinct",
                        List.of(BotConfig.PINS_CLASSIC, BotConfig.PINS_COLOUR_BLIND),
                        config.blueprintPins),
                new Param.Bool("close_on_run", "Close panel on Run",
                        "Hand the screen back to the game when a task starts, instead of leaving "
                                + "the panel open to watch it", config.closePanelOnRun),
                new Param.Bool("show_lune", "Show Lune",
                        "Show Lune in menus and the compact status card while the bot runs", config.showLune),
                new Param.Choice("lune_speech", "Lune speech",
                        "Silent lets Lune sleep with no chat; Quiet keeps only questions and replies; Normal also shows work status",
                        List.of(BotConfig.LUNE_SPEECH_SILENT, BotConfig.LUNE_SPEECH_QUIET,
                                BotConfig.LUNE_SPEECH_NORMAL), config.luneSpeech),
                new Param.Choice("lune_size", "Lune size",
                        "Size of the floating character",
                        List.of(BotConfig.LUNE_SIZE_SMALL, BotConfig.LUNE_SIZE_NORMAL,
                                BotConfig.LUNE_SIZE_LARGE), config.luneSize),
                new Param.Choice("lune_chatbox", "Lune chat box",
                        "Width and height of Lune's popup",
                        List.of(BotConfig.LUNE_SIZE_SMALL, BotConfig.LUNE_SIZE_NORMAL,
                                 BotConfig.LUNE_SIZE_LARGE), config.luneChatboxSize),
                new Param.Bool("warn_slow_steps", "Warn about slow steps",
                        "Let Lune speak up when one step of a running task is what is costing the "
                                + "frame rate. It never changes or stops the task, only asks "
                                + "whether that was intended", config.warnAboutSlowSteps),
                new Param.Bool("show_debug", "Debug overlay",
                        "Show live telemetry top-left (F6)", config.showDebug),
                new Param.Bool("debug_detail", "Debug: path detail",
                        "Include pathfinder counters, target visibility, memory and give-up limits",
                        config.debugPathDetail),
                new Param.Bool("debug_profiler", "Debug: profiler",
                        "Measure where Lune's client-tick time goes and list the worst sections on "
                                + "the overlay. Turn this on when the game stutters while a task runs",
                        config.debugProfiler),
                new Param.Bool("debug_run_log", "Debug: run journal",
                        "Write a text file with the bot's decisions, tools, blocks, memory and stalls",
                        config.debugRunLog),
                new Param.Bool("action_markers", "World action markers",
                        "Show the block Lune is breaking or walking toward, plus the Stay Near area",
                        config.showActionMarkers),
                new Param.Bool("learning", "Local learning",
                        "Remember job best times and safe skill tactics in config/lune-learning.json",
                        config.learningEnabled),
                new Param.Bool("user_learning", "User feedback",
                        "F7 approves and F8 rejects the last concrete skill tactic, not its route",
                        config.userLearningEnabled),
                new Param.Bool("omniscient_mining", "Omniscient mining",
                        "Legacy loaded-chunk access (cheat/X-ray). Only available in singleplayer or to server operators. "
                                + "Off = human-like vision only",
                        omniscientAllowed && config.omniscientMining),
                new Param.Bool("omniscient_harvesting", "Omniscient harvesting",
                        "Legacy loaded-chunk access for mature crops. Only available in singleplayer or to server operators. "
                                + "Off = human-like vision only",
                        omniscientAllowed && config.omniscientHarvesting)
        ));
        if (!BuildFeatures.approvalFeedback()) {
            parameters.removeIf(parameter -> parameter.id().equals("user_learning"));
        }
        return parameters;
    }

    /** Mirrors the edited values back into the live config so changes take effect immediately. */
    @Override
    public void tick() {
        BotConfig config = BotConfig.get();
        config.allowSprint = settings.boolValue("allow_sprint");
        config.allowSwim = settings.boolValue("allow_swim");
        config.maxFall = settings.intValue("max_fall");
        config.turnSpeed = settings.intValue("turn_speed");
        config.turnSmoothing = smoothnessAcceleration(settings.intValue("turn_smoothness"));
        config.nodeBudget = settings.intValue("node_budget") * BUDGET_SCALE;
        config.repathInterval = settings.intValue("repath");
        config.luneUiScale = settings.choiceValue("ui_scale");
        config.dashboardLineHeight = settings.intValue("dashboard_line_height");
        config.dashboardShowCoordinates = settings.boolValue("dashboard_coordinates");
        config.dashboardShowSeed = settings.boolValue("dashboard_seed");
        config.dashboardShowEnemies = settings.boolValue("dashboard_enemies");
        config.dashboardShowSaturation = settings.boolValue("dashboard_saturation");
        config.dashboardShowItemConditions = settings.boolValue("dashboard_items");
        config.dashboardShowExperience = settings.boolValue("dashboard_experience");
        config.dashboardShowEffects = settings.boolValue("dashboard_effects");
        config.dashboardShowEnvironment = settings.boolValue("dashboard_environment");
        config.dashboardShowLight = settings.boolValue("dashboard_light");
        config.dashboardShowFacing = settings.boolValue("dashboard_facing");
        config.dashboardShowLearning = settings.boolValue("dashboard_learning");
        config.blueprintTheme = settings.choiceValue("blueprint_theme");
        config.blueprintPins = settings.choiceValue("blueprint_pins");
        config.closePanelOnRun = settings.boolValue("close_on_run");
        config.showLune = settings.boolValue("show_lune");
        config.luneSpeech = settings.choiceValue("lune_speech");
        config.luneSize = settings.choiceValue("lune_size");
        config.luneChatboxSize = settings.choiceValue("lune_chatbox");
        config.warnAboutSlowSteps = settings.boolValue("warn_slow_steps");
        config.showDebug = settings.boolValue("show_debug");
        config.debugPathDetail = settings.boolValue("debug_detail");
        config.debugProfiler = settings.boolValue("debug_profiler");
        com.etka.lune.bot.LuneProfiler.setEnabled(config.debugProfiler);
        config.debugRunLog = settings.boolValue("debug_run_log");
        config.showActionMarkers = settings.boolValue("action_markers");
        config.learningEnabled = settings.boolValue("learning");
        if (BuildFeatures.approvalFeedback()) {
            config.userLearningEnabled = settings.boolValue("user_learning");
        }
        if (OmniscientAccess.isAllowed(Minecraft.getInstance())) {
            config.omniscientMining = settings.boolValue("omniscient_mining");
            config.omniscientHarvesting = settings.boolValue("omniscient_harvesting");
        } else {
            // A config file may have been edited while outside a world, or may still contain a
            // value from a previous singleplayer session. Never carry that value into a server.
            settings.apply(Map.of("omniscient_mining", "false", "omniscient_harvesting", "false"));
            config.omniscientMining = false;
            config.omniscientHarvesting = false;
        }
    }

    /** Called when the panel closes; avoids writing the file on every tick. */
    public void save() {
        tick();
        BotConfig.get().save();
    }

    private void restoreSuggestions() {
        BotConfig config = BotConfig.get();
        if (config.luneDismissedSuggestionTypes != null) {
            config.luneDismissedSuggestionTypes.clear();
        }
        if (config.luneDismissedTaskSuggestions != null) {
            config.luneDismissedTaskSuggestions.clear();
        }
        if (config.luneSuggestionReminders != null) {
            config.luneSuggestionReminders.clear();
        }
        MascotAdvisor.restoreSuggestionMemory();
        config.save();
        restoreSuggestionsButton.setMessage(Component.literal("Suggestions restored"));
    }

    /**
     * Puts every setting on this tab back to its shipped value.
     *
     * <p>Two clicks, because there is no undo for it. What it deliberately does not touch is the
     * player's own content - saved tasks and waypoints are not settings, and the two buttons beside
     * this one are how those come back.</p>
     */
    private void resetSettings() {
        if (!confirmingReset) {
            confirmingReset = true;
            resetSettingsButton.setMessage(Component.literal("Reset? Click again"));
            return;
        }
        confirmingReset = false;
        CommandDef shipped = new CommandDef("defaults", "Defaults", "",
                buildParameters(new BotConfig()), def -> null);
        settings.apply(shipped.snapshot());
        panel.refresh();
        // tick() mirrors the panel back into the live config every frame, so writing the panel is
        // writing the settings; this only forces it to happen now rather than a frame later.
        tick();
        BotConfig.get().save();
        resetSettingsButton.setMessage(Component.literal("Config settings reset"));
    }

    private void restoreDefaultTasks() {
        int restored = TaskStore.get().restoreMissingDefaults();
        restoreDefaultTasksButton.setMessage(Component.literal(restored == 0
                ? "Default tasks already present"
                : restored + " default task" + (restored == 1 ? "" : "s") + " restored"));
    }

    /** Stored acceleration to a slider position, so a hand-edited config still shows up sensibly. */
    private static int smoothnessSlider(float acceleration) {
        return Math.clamp(Math.round((SNAPPIEST - acceleration) / smoothnessStep()) + 1,
                1, SMOOTHNESS_STEPS);
    }

    private static float smoothnessAcceleration(int slider) {
        return SNAPPIEST - (Math.clamp(slider, 1, SMOOTHNESS_STEPS) - 1) * smoothnessStep();
    }

    private static float smoothnessStep() {
        return (SNAPPIEST - SMOOTHEST) / (SMOOTHNESS_STEPS - 1);
    }

    private static Map<String, String> configSections() {
        return Map.ofEntries(
                Map.entry("allow_sprint", "Movement & Pathfinding"),
                Map.entry("allow_swim", "Movement & Pathfinding"),
                Map.entry("max_fall", "Movement & Pathfinding"),
                Map.entry("turn_speed", "Movement & Pathfinding"),
                Map.entry("turn_smoothness", "Movement & Pathfinding"),
                Map.entry("node_budget", "Movement & Pathfinding"),
                Map.entry("repath", "Movement & Pathfinding"),
                Map.entry("ui_scale", "Lune & Interface"),
                Map.entry("dashboard_line_height", "Lune & Interface"),
                Map.entry("dashboard_coordinates", "Lune & Interface"),
                Map.entry("dashboard_seed", "Lune & Interface"),
                Map.entry("dashboard_enemies", "Lune & Interface"),
                Map.entry("dashboard_saturation", "Lune & Interface"),
                Map.entry("dashboard_items", "Lune & Interface"),
                Map.entry("dashboard_experience", "Lune & Interface"),
                Map.entry("dashboard_effects", "Lune & Interface"),
                Map.entry("dashboard_environment", "Lune & Interface"),
                Map.entry("dashboard_light", "Lune & Interface"),
                Map.entry("dashboard_facing", "Lune & Interface"),
                Map.entry("dashboard_learning", "Lune & Interface"),
                Map.entry("blueprint_theme", "Lune & Interface"),
                Map.entry("blueprint_pins", "Lune & Interface"),
                Map.entry("show_lune", "Lune & Interface"),
                Map.entry("lune_speech", "Lune & Interface"),
                Map.entry("lune_size", "Lune & Interface"),
                Map.entry("lune_chatbox", "Lune & Interface"),
                Map.entry("show_debug", "Debug & Learning"),
                Map.entry("debug_detail", "Debug & Learning"),
                Map.entry("debug_run_log", "Debug & Learning"),
                Map.entry("action_markers", "Debug & Learning"),
                Map.entry("learning", "Debug & Learning"),
                Map.entry("user_learning", "Debug & Learning"),
                Map.entry("omniscient_mining", "Advanced World Access"),
                Map.entry("omniscient_harvesting", "Advanced World Access"));
    }

    private static boolean isOmniscientParameter(String id) {
        return "omniscient_mining".equals(id) || "omniscient_harvesting".equals(id);
    }

    /**
     * The settings panel and the footer beneath it. The hint line used to be drawn straight across
     * the footer buttons; giving each a row of its own is what the extra reserved height buys.
     */
    private record Frame(int left, int top, int width, int height, int hintY, int footerY,
                         int buttonWidth, int footerX) {

        static Frame of(ScreenRectangle area) {
            int available = Math.max(60, area.width() - MARGIN * 2);
            int width = Math.min(820, available);
            int left = area.left() + Math.max(MARGIN, (area.width() - width) / 2);
            int top = area.top() + MARGIN;
            int footerY = area.bottom() - MARGIN - FOOTER_BUTTON_H;
            int hintY = footerY - 13;
            int buttonWidth = Math.min(FOOTER_BUTTON_WIDTH,
                    Math.max(40, (width - FOOTER_BUTTON_GAP) / 2));
            int footerWidth = buttonWidth * 3 + FOOTER_BUTTON_GAP * 2;
            return new Frame(left, top, width, Math.max(40, hintY - 4 - top), hintY, footerY,
                    buttonWidth, left + Math.max(0, (width - footerWidth) / 2));
        }
    }

    @Override
    protected void layout(ScreenRectangle area) {
        Frame frame = Frame.of(area);
        panel.setPosition(frame.left(), frame.top());
        panel.setSize(frame.width(), frame.height());
        restoreSuggestionsButton.setPosition(frame.footerX(), frame.footerY());
        restoreSuggestionsButton.setSize(frame.buttonWidth(), FOOTER_BUTTON_H);
        restoreDefaultTasksButton.setPosition(
                frame.footerX() + frame.buttonWidth() + FOOTER_BUTTON_GAP, frame.footerY());
        restoreDefaultTasksButton.setSize(frame.buttonWidth(), FOOTER_BUTTON_H);
        resetSettingsButton.setPosition(
                frame.footerX() + 2 * (frame.buttonWidth() + FOOTER_BUTTON_GAP), frame.footerY());
        resetSettingsButton.setSize(frame.buttonWidth(), FOOTER_BUTTON_H);
    }

    @Override
    public void extractTabBackground(GuiGraphicsExtractor extractor) {
        Frame frame = Frame.of(area);
        LuneScreen.panel(extractor, frame.left(), frame.top(), frame.width(), frame.height());
    }

    @Override
    public void extractTabRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        Frame frame = Frame.of(area);
        extractor.textRenderer().accept(frame.left() + 4, frame.hintY(),
                Component.literal("Numbers: click −/+ (Shift = 10)  •  Yes/No: click to toggle  •  Choices: click to open")
                        .withColor(LuneScreen.TEXT_DIM));
    }
}
