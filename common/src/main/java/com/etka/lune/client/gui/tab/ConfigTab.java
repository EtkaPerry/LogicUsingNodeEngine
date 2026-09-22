package com.etka.lune.client.gui.tab;

import com.etka.lune.util.Alerts;
import com.etka.lune.util.Lang;
import com.etka.lune.util.LuneLanguages;
import com.etka.lune.bot.command.CommandDef;
import com.etka.lune.bot.command.Param;
import com.etka.lune.client.gui.LuneScreen;
import com.etka.lune.client.gui.LuneTab;
import com.etka.lune.client.gui.mascot.MascotAdvisor;
import com.etka.lune.client.gui.widget.ParamPanel;
import com.etka.lune.config.BotConfig;
import com.etka.lune.platform.BuildFeatures;
import com.etka.lune.task.TaskStore;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
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
    private final com.etka.lune.task.SecretTaskUnlock secretTaskUnlock = new com.etka.lune.task.SecretTaskUnlock();
    private final Button resetSettingsButton;
    /** Reset is the one footer button that cannot be undone, so it asks first. */
    private boolean confirmingReset;

    private static final int FOOTER_BUTTON_WIDTH = 180;
    private static final int FOOTER_BUTTON_GAP = 8;
    private static final int FOOTER_BUTTON_H = 18;

    public ConfigTab() {
        super(Component.literal(Lang.get("lune.gui.config.title")));

        BotConfig config = BotConfig.get();
        List<Param<?>> parameters = buildParameters(config);
        settings = new CommandDef("settings", parameters,
                def -> null);

        panel = add(new ParamPanel(0, 0, 10, 10));
        panel.setCommand(settings);
        panel.setCompactMode(true);
        panel.setSections(configSections());
        restoreSuggestionsButton = add(Button.builder(Component.literal(Lang.get("lune.gui.config.restore_lune_suggestions")),
                button -> restoreSuggestions()).size(180, 18).build());
        restoreDefaultTasksButton = add(Button.builder(Component.literal(Lang.get("lune.gui.config.restore_default_tasks")),
                button -> restoreDefaultTasks()).size(FOOTER_BUTTON_WIDTH, 18).build());
        resetSettingsButton = add(Button.builder(Component.literal(Lang.get("lune.gui.config.reset_config_settings")),
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
        List<Param<?>> parameters = new ArrayList<>(List.of(
                new Param.Bool("allow_sprint", config.allowSprint),
                new Param.Bool("allow_swim", config.allowSwim),
                new Param.Ints("max_fall", config.maxFall, 0, 20),
                new Param.Ints("turn_speed", (int) config.turnSpeed, 1, 90),
                new Param.Ints("turn_smoothness", smoothnessSlider(config.turnSmoothing), 1, SMOOTHNESS_STEPS),
                new Param.Ints("node_budget", config.nodeBudget / BUDGET_SCALE, 1, 100),
                new Param.Ints("repath", config.repathInterval, 20, 600),
                new Param.Choice("language", LuneLanguages::available, config.language,
                        LuneLanguages::displayName),
                new Param.Choice("ui_scale", List.of(BotConfig.LUNE_UI_AUTO, BotConfig.LUNE_UI_MATCH_GAME,
                                BotConfig.LUNE_UI_COMPACT), config.luneUiScale),
                // The accessibility group, kept contiguous: a section header is emitted wherever
                // the section changes, so a stray member further down prints the heading twice.
                new Param.Choice("lune_text_size", BotConfig.TEXT_SIZES, config.luneTextSize),
                new Param.Choice("lune_font", List.of(BotConfig.FONT_DEFAULT,
                                BotConfig.FONT_UNIFORM), config.luneFont),
                new Param.Bool("high_contrast", config.highContrast),
                new Param.Choice("blueprint_pins", List.of(BotConfig.PINS_CLASSIC,
                                BotConfig.PINS_COLOUR_BLIND), config.blueprintPins),
                new Param.Ints("dashboard_line_height", config.dashboardLineHeight, DashboardFrame.MIN_ROW_H, DashboardFrame.MAX_ROW_H),
                new Param.Bool("dashboard_coordinates", config.dashboardShowCoordinates),
                new Param.Bool("dashboard_seed", config.dashboardShowSeed),
                new Param.Bool("dashboard_enemies", config.dashboardShowEnemies),
                new Param.Bool("dashboard_saturation", config.dashboardShowSaturation),
                new Param.Bool("dashboard_items", config.dashboardShowItemConditions),
                new Param.Bool("dashboard_experience", config.dashboardShowExperience),
                new Param.Bool("dashboard_effects", config.dashboardShowEffects),
                new Param.Bool("dashboard_environment", config.dashboardShowEnvironment),
                new Param.Bool("dashboard_light", config.dashboardShowLight),
                new Param.Bool("dashboard_facing", config.dashboardShowFacing),
                new Param.Bool("dashboard_learning", config.dashboardShowLearning),
                new Param.Choice("blueprint_theme", List.of(BotConfig.THEME_ORANGE, BotConfig.THEME_SLATE, BotConfig.THEME_BLUE,
                                BotConfig.THEME_PURPLE, BotConfig.THEME_AMBER,
                                BotConfig.THEME_GREEN), config.blueprintTheme),
                new Param.Bool("close_on_run", config.closePanelOnRun),
                new Param.Bool("show_lune", config.showLune),
                new Param.Choice("lune_speech", List.of(BotConfig.LUNE_SPEECH_SILENT, BotConfig.LUNE_SPEECH_QUIET,
                                BotConfig.LUNE_SPEECH_NORMAL), config.luneSpeech),
                new Param.Choice("lune_size", List.of(BotConfig.LUNE_SIZE_SMALL, BotConfig.LUNE_SIZE_NORMAL,
                                BotConfig.LUNE_SIZE_LARGE), config.luneSize),
                new Param.Choice("lune_chatbox", List.of(BotConfig.LUNE_SIZE_SMALL, BotConfig.LUNE_SIZE_NORMAL,
                                 BotConfig.LUNE_SIZE_LARGE), config.luneChatboxSize),
                new Param.Bool("warn_slow_steps", config.warnAboutSlowSteps),
                new Param.Bool("alert_task_end", config.alertOnTaskEnd),
                new Param.Bool("alert_death", config.alertOnDeath),
                new Param.Bool("alert_sound", config.alertSound),
                new Param.Ints("alert_volume", config.alertVolume, 0, Alerts.MAX_VOLUME_PERCENT),
                new Param.Choice("alert_tone_finished", Alerts.Tone.labels(), config.alertToneFinished),
                new Param.Choice("alert_tone_failed", Alerts.Tone.labels(), config.alertToneFailed),
                new Param.Choice("alert_tone_death", Alerts.Tone.labels(), config.alertToneDeath),
                new Param.Bool("alert_toast", config.alertToast),
                new Param.Bool("alert_chat", config.alertChat),
                new Param.Bool("show_debug", config.showDebug),
                new Param.Bool("debug_detail", config.debugPathDetail),
                new Param.Bool("debug_profiler", config.debugProfiler),
                new Param.Bool("debug_run_log", config.debugRunLog),
                new Param.Bool("action_markers", config.showActionMarkers),
                new Param.Bool("learning", config.learningEnabled),
                new Param.Bool("node_stats", config.nodeStats),
                new Param.Bool("user_learning", config.userLearningEnabled)
        ));
        if (!BuildFeatures.approvalFeedback()) {
            parameters.removeIf(parameter -> parameter.id().equals("user_learning")
                    || parameter.id().equals("debug_run_log"));
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
        config.language = settings.choiceValue("language");
        // Cheap when nothing changed; the moment it does, every line drawn after
        // this frame comes from the new file.
        Lang.select(config.language);
        config.luneUiScale = settings.choiceValue("ui_scale");
        config.luneTextSize = settings.choiceValue("lune_text_size");
        config.luneFont = settings.choiceValue("lune_font");
        config.highContrast = settings.boolValue("high_contrast");
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
        config.alertOnTaskEnd = settings.boolValue("alert_task_end");
        config.alertOnDeath = settings.boolValue("alert_death");
        config.alertSound = settings.boolValue("alert_sound");
        config.alertVolume = settings.intValue("alert_volume");
        config.alertToneFinished = settings.choiceValue("alert_tone_finished");
        config.alertToneFailed = settings.choiceValue("alert_tone_failed");
        config.alertToneDeath = settings.choiceValue("alert_tone_death");
        config.alertToast = settings.boolValue("alert_toast");
        config.alertChat = settings.boolValue("alert_chat");
        config.showDebug = settings.boolValue("show_debug");
        config.debugPathDetail = settings.boolValue("debug_detail");
        config.debugProfiler = settings.boolValue("debug_profiler");
        com.etka.lune.bot.LuneProfiler.setEnabled(config.debugProfiler);
        config.debugRunLog = BuildFeatures.runTracingEnabled()
                && settings.boolValue("debug_run_log");
        config.showActionMarkers = settings.boolValue("action_markers");
        config.learningEnabled = settings.boolValue("learning");
        config.nodeStats = settings.boolValue("node_stats");
        if (BuildFeatures.approvalFeedback()) {
            config.userLearningEnabled = settings.boolValue("user_learning");
        }
        // The omniscient modes are not here on purpose. They are cheats, they last one session, and
        // they are switched with /lune omniscient - see com.etka.lune.bot.util.Cheats.
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
        restoreSuggestionsButton.setMessage(Component.literal(Lang.get("lune.gui.config.suggestions_restored")));
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
            resetSettingsButton.setMessage(Component.literal(Lang.get("lune.gui.config.reset_click_again")));
            return;
        }
        confirmingReset = false;
        CommandDef shipped = new CommandDef("defaults", buildParameters(new BotConfig()), def -> null);
        settings.apply(shipped.snapshot());
        panel.refresh();
        // tick() mirrors the panel back into the live config every frame, so writing the panel is
        // writing the settings; this only forces it to happen now rather than a frame later.
        tick();
        BotConfig.get().save();
        resetSettingsButton.setMessage(Component.literal(Lang.get("lune.gui.config.config_settings_reset")));
    }

    private void restoreDefaultTasks() {
        boolean unlock = secretTaskUnlock.press(System.nanoTime());
        int restored = TaskStore.get().restoreMissingDefaults();
        if (unlock) {
            TaskStore.get().unlockSecretTasks();
            restoreDefaultTasksButton.setMessage(Component.literal(Lang.get("lune.gui.config.secret_task_unlocked")));
            return;
        }
        restoreDefaultTasksButton.setMessage(restored == 0
                ? Component.literal(Lang.get("lune.gui.config.defaults_already_present"))
                : Component.literal(Lang.get(restored == 1 ? "lune.gui.config.default_task_restored"
                        : "lune.gui.config.default_tasks_restored", restored)));
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
                Map.entry("language", "Lune & Interface"),
                Map.entry("ui_scale", "Lune & Interface"),
                Map.entry("lune_text_size", "Accessibility"),
                Map.entry("lune_font", "Accessibility"),
                Map.entry("high_contrast", "Accessibility"),
                Map.entry("blueprint_pins", "Accessibility"),
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
                Map.entry("close_on_run", "Lune & Interface"),
                Map.entry("show_lune", "Lune & Interface"),
                Map.entry("lune_speech", "Lune & Interface"),
                Map.entry("lune_size", "Lune & Interface"),
                Map.entry("lune_chatbox", "Lune & Interface"),
                Map.entry("warn_slow_steps", "Alerts"),
                Map.entry("alert_task_end", "Alerts"),
                Map.entry("alert_death", "Alerts"),
                Map.entry("alert_sound", "Alerts"),
                Map.entry("alert_volume", "Alerts"),
                Map.entry("alert_tone_finished", "Alerts"),
                Map.entry("alert_tone_failed", "Alerts"),
                Map.entry("alert_tone_death", "Alerts"),
                Map.entry("alert_toast", "Alerts"),
                Map.entry("alert_chat", "Alerts"),
                Map.entry("show_debug", "Debug & Learning"),
                Map.entry("debug_detail", "Debug & Learning"),
                Map.entry("debug_profiler", "Debug & Learning"),
                Map.entry("debug_run_log", "Debug & Learning"),
                Map.entry("action_markers", "Debug & Learning"),
                Map.entry("learning", "Debug & Learning"),
                Map.entry("node_stats", "Debug & Learning"),
                Map.entry("user_learning", "Debug & Learning"));
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
                Component.literal(Lang.get("lune.gui.config.numbers_click_shift_10_yes_no_click"))
                        .withColor(LuneScreen.TEXT_DIM));
    }
}
