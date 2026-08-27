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
import com.etka.lune.routine.RoutineStore;
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
    private final Param.Bool omniscientMiningParam;
    private final Param.Bool omniscientHarvestingParam;

    private static final int FOOTER_BUTTON_WIDTH = 180;
    private static final int FOOTER_BUTTON_GAP = 8;
    private static final int FOOTER_BUTTON_H = 18;

    public ConfigTab() {
        super(Component.literal("Config"));

        BotConfig config = BotConfig.get();
        boolean omniscientAllowed = OmniscientAccess.isAllowed(Minecraft.getInstance());
        omniscientMiningParam = new Param.Bool("omniscient_mining", "Omniscient mining",
                "Legacy loaded-chunk access (cheat/X-ray). Only available in singleplayer or to server operators. "
                        + "Off = human-like vision only",
                omniscientAllowed && config.omniscientMining);
        omniscientHarvestingParam = new Param.Bool("omniscient_harvesting", "Omniscient harvesting",
                "Legacy loaded-chunk access for mature crops. Only available in singleplayer or to server operators. "
                        + "Off = human-like vision only",
                omniscientAllowed && config.omniscientHarvesting);
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
                new Param.Ints("stop_health", "Stop below health",
                        "Halt the bot when health drops this low (0 = never)", (int) config.stopBelowHealth, 0, 20),
                new Param.Ints("stop_food", "Stop below food",
                        "Halt when hunger drops this low (0 = never)", config.stopBelowFood, 0, 20),
                new Param.Bool("stop_attacked", "Stop if attacked",
                        "Halt when another player hits you", config.stopWhenAttackedByPlayer),
                new Param.Bool("stop_full", "Stop when full",
                        "Halt when the inventory has no free slot", config.stopWhenFull),
                new Param.Choice("ui_scale", "Menu size",
                        "Auto shrinks Lune's menus by the smallest step that fits the layout, and "
                                + "never past half the game's GUI scale; Match game keeps your GUI "
                                + "scale exactly; Compact shrinks as far as Auto is allowed to",
                        List.of(BotConfig.LUNE_UI_AUTO, BotConfig.LUNE_UI_MATCH_GAME,
                                BotConfig.LUNE_UI_COMPACT), config.luneUiScale),
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
                new Param.Bool("show_debug", "Debug overlay",
                        "Show live telemetry top-left (F6)", config.showDebug),
                new Param.Bool("debug_detail", "Debug: path detail",
                        "Include pathfinder counters, target visibility, memory and give-up limits",
                        config.debugPathDetail),
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
                omniscientMiningParam,
                omniscientHarvestingParam
        ));
        if (!BuildFeatures.approvalFeedback()) {
            parameters.removeIf(parameter -> parameter.id().equals("user_learning"));
        }
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
        config.stopBelowHealth = settings.intValue("stop_health");
        config.stopBelowFood = settings.intValue("stop_food");
        config.stopWhenAttackedByPlayer = settings.boolValue("stop_attacked");
        config.stopWhenFull = settings.boolValue("stop_full");
        config.luneUiScale = settings.choiceValue("ui_scale");
        config.showLune = settings.boolValue("show_lune");
        config.luneSpeech = settings.choiceValue("lune_speech");
        config.luneSize = settings.choiceValue("lune_size");
        config.luneChatboxSize = settings.choiceValue("lune_chatbox");
        config.showDebug = settings.boolValue("show_debug");
        config.debugPathDetail = settings.boolValue("debug_detail");
        config.debugRunLog = settings.boolValue("debug_run_log");
        config.showActionMarkers = settings.boolValue("action_markers");
        config.learningEnabled = settings.boolValue("learning");
        if (BuildFeatures.approvalFeedback()) {
            config.userLearningEnabled = settings.boolValue("user_learning");
        }
        if (OmniscientAccess.isAllowed(Minecraft.getInstance())) {
            config.omniscientMining = omniscientMiningParam.get();
            config.omniscientHarvesting = omniscientHarvestingParam.get();
        } else {
            // A config file may have been edited while outside a world, or may still contain a
            // value from a previous singleplayer session. Never carry that value into a server.
            omniscientMiningParam.set(false);
            omniscientHarvestingParam.set(false);
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
        if (config.luneDismissedRoutineSuggestions != null) {
            config.luneDismissedRoutineSuggestions.clear();
        }
        if (config.luneSuggestionReminders != null) {
            config.luneSuggestionReminders.clear();
        }
        MascotAdvisor.restoreSuggestionMemory();
        config.save();
        restoreSuggestionsButton.setMessage(Component.literal("Suggestions restored"));
    }

    private void restoreDefaultTasks() {
        int restored = RoutineStore.get().restoreMissingDefaults();
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
                Map.entry("stop_health", "Safety Stops"),
                Map.entry("stop_food", "Safety Stops"),
                Map.entry("stop_attacked", "Safety Stops"),
                Map.entry("stop_full", "Safety Stops"),
                Map.entry("ui_scale", "Lune & Interface"),
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
            int footerWidth = buttonWidth * 2 + FOOTER_BUTTON_GAP;
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
