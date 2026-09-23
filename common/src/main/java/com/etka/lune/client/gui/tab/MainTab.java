package com.etka.lune.client.gui.tab;

import com.etka.lune.compat.Screens;
import com.etka.lune.util.Durations;
import com.etka.lune.util.Lang;
import com.etka.lune.bot.BotEngine;
import com.etka.lune.bot.BotStatistics;
import com.etka.lune.bot.DebugInfo;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskProgress;
import com.etka.lune.bot.AutoRun;
import com.etka.lune.bot.task.TaskRunner;
import com.etka.lune.bot.util.Vision;
import com.etka.lune.config.BotConfig;
import com.etka.lune.client.gui.Accessibility;
import com.etka.lune.client.gui.LuneScreen;
import com.etka.lune.client.gui.LuneTab;
import com.etka.lune.client.gui.widget.DashboardStatsPanel;
import com.etka.lune.client.gui.widget.GuiIcons;
import com.etka.lune.client.gui.widget.ListPanel;
import com.etka.lune.client.gui.widget.VerticalSplitter;
import com.etka.lune.task.TaskGraph;
import com.etka.lune.task.TaskStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

/**
 * Live dashboard for the bot. The Tasks tab is the editor; Main is the operational view for what
 * is running, what the bot can see, and what happened when it stopped.
 */
public class MainTab extends LuneTab {

    private static final int CONTROL_H = DashboardFrame.CONTROL_H;
    private static final int TITLE_H = DashboardFrame.TITLE_H;
    private static final int BODY_TOP = DashboardFrame.BODY_TOP;
    private static final int BUTTON_GAP = DashboardFrame.BUTTON_GAP;
    private static final int PANEL_HEADER = 0xD0222D3A;

    private final Button pauseButton;
    private final Button clearButton;
    private final Button stopButton;
    private final ListPanel<TaskGraph> launchList;
    private final DashboardStatsPanel statisticsPanel;
    private final Button statisticsCurrentButton;
    private final Button statisticsLastButton;
    private final Button statisticsAllTimeButton;
    private final VerticalSplitter topStatusSplitter;
    private final VerticalSplitter topActivitySplitter;
    private final VerticalSplitter bottomTasksSplitter;
    private final VerticalSplitter bottomSafetyVitalsSplitter;
    private final VerticalSplitter bottomSafetyWorldSplitter;
    private BiConsumer<TaskGraph, Boolean> taskEditorOpener = (task, rename) -> {};
    private StatisticsScope statisticsScope = StatisticsScope.CURRENT;

    /** Dashboard splitters mirror the Task tab: drag a divider to give its left card more room. */
    private int topStatusWidth = -1;
    private int topActivityWidth = -1;
    private int bottomTasksWidth = -1;
    private int bottomSafetyVitalsWidth = -1;
    private int bottomSafetyWorldWidth = -1;

    public MainTab() {
        super(Component.literal(Lang.get("lune.gui.main.title")));

        pauseButton = add(Button.builder(Component.literal(Lang.get("lune.gui.main.pause")), b -> togglePause())
                .size(76, CONTROL_H).build());
        clearButton = add(Button.builder(Component.literal(Lang.get("lune.gui.main.clear_queue")), b -> BotEngine.get().clearQueue())
                .size(92, CONTROL_H).build());
        stopButton = add(Button.builder(Component.literal(Lang.get("lune.gui.main.stop_all")), b -> BotEngine.get().stopAll())
                .size(76, CONTROL_H).build());
        launchList = add(new ListPanel<>(0, 0, 10, 10, TaskGraph::describe, task -> {}));
        launchList.setActions(List.of(
                new ListPanel.RowAction<>(GuiIcons.Icon.PLAY, Lang.get("lune.gui.main.start"), this::launch,
                        task -> task != null && !task.nodes.isEmpty(),
                        task -> !isRunningTask(task)),
                new ListPanel.RowAction<>(GuiIcons.Icon.PAUSE, Lang.get("lune.gui.main.pause"), this::toggleTaskPause,
                        this::isRunningTask, this::isRunningTask),
                new ListPanel.RowAction<>(GuiIcons.Icon.RENAME, Lang.get("lune.gui.main.rename"),
                        task -> taskEditorOpener.accept(task, true)),
                new ListPanel.RowAction<>(GuiIcons.Icon.VIEW, Lang.get("lune.gui.main.view"),
                        task -> taskEditorOpener.accept(task, false))));
        statisticsPanel = add(new DashboardStatsPanel(0, 0, 10, 10));
        statisticsCurrentButton = add(Button.builder(Component.literal(Lang.get("lune.gui.main.current")),
                b -> selectStatistics(StatisticsScope.CURRENT)).size(62, 16).build());
        statisticsLastButton = add(Button.builder(Component.literal(Lang.get("lune.gui.main.last_task")),
                b -> selectStatistics(StatisticsScope.LAST_RUN)).size(70, 16).build());
        statisticsAllTimeButton = add(Button.builder(Component.literal(Lang.get("lune.gui.main.all_time")),
                b -> selectStatistics(StatisticsScope.ALL_TIME)).size(64, 16).build());
        topStatusSplitter = add(new VerticalSplitter(0, 0, 1, 1, this::resizeTopStatus));
        topActivitySplitter = add(new VerticalSplitter(0, 0, 1, 1, this::resizeTopActivity));
        bottomTasksSplitter = add(new VerticalSplitter(0, 0, 1, 1, this::resizeBottomTasks));
        bottomSafetyVitalsSplitter = add(new VerticalSplitter(0, 0, 1, 1,
                this::resizeBottomSafetyVitals));
        bottomSafetyWorldSplitter = add(new VerticalSplitter(0, 0, 1, 1,
                this::resizeBottomSafetyWorld));
    }

    /** Connects the dashboard's row actions to the screen's existing task editor. */
    public void setTaskEditorOpener(BiConsumer<TaskGraph, Boolean> opener) {
        taskEditorOpener = opener == null ? (task, rename) -> {} : opener;
    }

    private void togglePause() {
        BotEngine engine = BotEngine.get();
        engine.setPaused(!engine.isPaused());
    }

    @Override
    public void tick() {
        BotEngine engine = BotEngine.get();
        pauseButton.setMessage(Component.literal(Lang.get(engine.isPaused() ? "lune.gui.main.resume" : "lune.gui.main.pause")));
        pauseButton.active = engine.getCurrent() != null;
        clearButton.active = !engine.getQueue().isEmpty();
        stopButton.active = engine.getCurrent() != null || !engine.getQueue().isEmpty();

        // Saved tasks stay available while a run is active. The current run and queue are visible
        // in Recent Activity and Current Working Node instead of replacing this useful list.
        launchList.setItems(TaskStore.get().listed());
        launchList.visible = true;
        launchList.active = true;
        statisticsPanel.setLineHeight(BotConfig.get().dashboardLineHeight);
        statisticsPanel.setLines(statisticsLines(engine));
        statisticsPanel.visible = true;
        statisticsPanel.active = true;
        statisticsCurrentButton.visible = true;
        statisticsCurrentButton.active = true;
        statisticsLastButton.visible = true;
        statisticsLastButton.active = true;
        statisticsAllTimeButton.visible = true;
        statisticsAllTimeButton.active = true;
    }

    private void launch(TaskGraph task) {
        if (task == null || task.nodes.isEmpty()) {
            return;
        }
        BotEngine.get().runNow(new TaskRunner(task));
        if (BotConfig.get().closePanelOnRun) {
            Screens.open(Minecraft.getInstance(), null);
        }
    }

    private boolean isRunningTask(TaskGraph task) {
        return task != null && BotEngine.get().getCurrent() instanceof TaskRunner running
                && running.currentTask() == task;
    }

    private void toggleTaskPause(TaskGraph task) {
        if (isRunningTask(task)) {
            togglePause();
        }
    }

    private void selectStatistics(StatisticsScope scope) {
        statisticsScope = scope;
    }

    private DashboardFrame frameOf(ScreenRectangle area) {
        DashboardFrame frame = DashboardFrame.of(area.left(), area.top(), area.right(), area.bottom(),
                BotConfig.get().dashboardLineHeight, topStatusWidth, topActivityWidth,
                bottomTasksWidth, bottomSafetyVitalsWidth, bottomSafetyWorldWidth);
        if (bottomTasksWidth <= 0 || bottomSafetyVitalsWidth <= 0 || bottomSafetyWorldWidth <= 0) {
            bottomTasksWidth = frame.tasks().width();
            bottomSafetyVitalsWidth = frame.safetyVitals().width();
            bottomSafetyWorldWidth = frame.safetyWorld().width();
        }
        if (area.width() - DashboardFrame.MARGIN * 2 >= DashboardFrame.STACK_WIDTH
                && (topStatusWidth <= 0 || topActivityWidth <= 0)) {
            topStatusWidth = frame.status().width();
            topActivityWidth = frame.recentActivity().width();
        }
        return DashboardFrame.of(area.left(), area.top(), area.right(), area.bottom(),
                BotConfig.get().dashboardLineHeight, topStatusWidth, topActivityWidth,
                bottomTasksWidth, bottomSafetyVitalsWidth, bottomSafetyWorldWidth);
    }

    @Override
    protected void layout(ScreenRectangle area) {
        DashboardFrame frame = frameOf(area);
        DashboardFrame.Card tasks = frame.tasks();
        launchList.setPosition(tasks.x() + 8, tasks.y() + TITLE_H + 6);
        launchList.setSize(Math.max(1, tasks.width() - 16),
                Math.max(1, tasks.height() - TITLE_H - 14));

        DashboardFrame.Card statistics = frame.statistics();
        int tabY = statistics.y() + TITLE_H + 2;
        int tabsWidth = Math.max(1, statistics.width() - 12);
        int tabWidth = Math.max(20, (tabsWidth - BUTTON_GAP * 2) / 3);
        int tabX = statistics.x() + 6;
        place(statisticsCurrentButton, tabX, tabY, tabWidth, 16);
        place(statisticsLastButton, tabX + tabWidth + BUTTON_GAP, tabY, tabWidth, 16);
        place(statisticsAllTimeButton, tabX + (tabWidth + BUTTON_GAP) * 2, tabY,
                Math.max(1, tabsWidth - (tabWidth + BUTTON_GAP) * 2), 16);
        statisticsPanel.setPosition(statistics.x() + 6, tabY + 20);
        statisticsPanel.setSize(Math.max(1, statistics.width() - 12),
                Math.max(1, statistics.height() - TITLE_H - 26));

        // Sized every pass, not just when narrow: a screen that grows again has to undo this.
        if (frame.sharedButtonRow()) {
            int buttonWidth = Math.max(28, (frame.width() - BUTTON_GAP * 2) / 3);
            place(pauseButton, frame.left(), frame.buttonY(), buttonWidth);
            place(clearButton, frame.left() + buttonWidth + BUTTON_GAP, frame.buttonY(), buttonWidth);
            place(stopButton, frame.left() + (buttonWidth + BUTTON_GAP) * 2, frame.buttonY(), buttonWidth);
        } else {
            place(pauseButton, frame.left(), frame.buttonY(), 76);
            place(clearButton, frame.left() + 82, frame.buttonY(), 92);
            place(stopButton, frame.left() + 182, frame.buttonY(), 76);
        }
        layoutSplitters(frame);
    }

    private void layoutSplitters(DashboardFrame frame) {
        boolean topWide = frame.status().y() == frame.recentActivity().y();
        topStatusSplitter.visible = topWide;
        topStatusSplitter.active = topWide;
        topActivitySplitter.visible = topWide;
        topActivitySplitter.active = topWide;
        if (topWide) {
            placeSplitter(topStatusSplitter, frame.status(), frame.recentActivity());
            placeSplitter(topActivitySplitter, frame.recentActivity(), frame.workingNode());
            topStatusWidth = frame.status().width();
            topActivityWidth = frame.recentActivity().width();
        }

        bottomTasksSplitter.visible = true;
        bottomTasksSplitter.active = true;
        bottomSafetyVitalsSplitter.visible = true;
        bottomSafetyVitalsSplitter.active = true;
        bottomSafetyWorldSplitter.visible = true;
        bottomSafetyWorldSplitter.active = true;
        placeSplitter(bottomTasksSplitter, frame.tasks(), frame.safetyVitals());
        placeSplitter(bottomSafetyVitalsSplitter, frame.safetyVitals(), frame.safetyWorld());
        placeSplitter(bottomSafetyWorldSplitter, frame.safetyWorld(), frame.statistics());
        bottomTasksWidth = frame.tasks().width();
        bottomSafetyVitalsWidth = frame.safetyVitals().width();
        bottomSafetyWorldWidth = frame.safetyWorld().width();
    }

    private static void placeSplitter(VerticalSplitter splitter, DashboardFrame.Card left,
                                      DashboardFrame.Card right) {
        int x = left.x() + left.width()
                + (right.x() - left.x() - left.width() - VerticalSplitter.WIDTH) / 2;
        splitter.setPosition(x, left.y());
        splitter.setSize(VerticalSplitter.WIDTH, left.height());
    }

    private void resizeTopStatus(int dx) {
        topStatusWidth += dx;
        if (area.width() > 0) {
            layout(area);
        }
    }

    private void resizeTopActivity(int dx) {
        topActivityWidth += dx;
        if (area.width() > 0) {
            layout(area);
        }
    }

    private void resizeBottomTasks(int dx) {
        bottomTasksWidth += dx;
        if (area.width() > 0) {
            layout(area);
        }
    }

    private void resizeBottomSafetyVitals(int dx) {
        bottomSafetyVitalsWidth += dx;
        if (area.width() > 0) {
            layout(area);
        }
    }

    private void resizeBottomSafetyWorld(int dx) {
        bottomSafetyWorldWidth += dx;
        if (area.width() > 0) {
            layout(area);
        }
    }

    private static void place(Button button, int x, int y, int width) {
        place(button, x, y, width, CONTROL_H);
    }

    private static void place(Button button, int x, int y, int width, int height) {
        button.setPosition(x, y);
        button.setSize(width, height);
    }

    @Override
    public void extractTabBackground(GuiGraphicsExtractor extractor) {
        DashboardFrame frame = frameOf(area);
        panel(extractor, frame.status());
        panel(extractor, frame.recentActivity());
        panel(extractor, frame.workingNode());
        panel(extractor, frame.tasks());
        panel(extractor, frame.safetyVitals());
        panel(extractor, frame.safetyWorld());
        panel(extractor, frame.statistics());
    }

    @Override
    public void extractTabRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                      float partialTick) {
        BotEngine engine = BotEngine.get();
        DebugInfo debug = engine.getDebug();
        Task current = engine.getCurrent();
        Minecraft mc = Minecraft.getInstance();

        DashboardFrame frame = frameOf(area);
        drawStatusCard(extractor, frame.status(), engine, current, debug);
        drawRecentActivityCard(extractor, frame.recentActivity(), engine, debug);
        drawWorkingNodeCard(extractor, frame.workingNode(), engine, current, debug);
        drawSavedTasksHeader(extractor, frame.tasks(), engine);
        drawSafetyVitalsCard(extractor, frame.safetyVitals(), mc);
        drawSafetyWorldCard(extractor, frame.safetyWorld(), mc);
        drawStatisticsCard(extractor, frame.statistics());
        extractor.textRenderer().accept(frame.left(), frame.hintY(),
                Component.literal(fit(Lang.get("lune.gui.main.c_pause_resume_shift_c_stop_everything"), frame.width()))
                        .withColor(Accessibility.dim()));
        drawTaskActionTooltip(extractor, mouseX, mouseY);
    }

    private void drawTaskActionTooltip(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
        String label = launchList.hoveredActionLabel(mouseX, mouseY);
        if (label == null) {
            return;
        }
        var font = Minecraft.getInstance().font;
        int width = font.width(label) + 10;
        int maxX = Math.max(area.left(), area.right() - width);
        int maxY = Math.max(area.top(), area.bottom() - 12);
        int x = Math.clamp(mouseX + 8, area.left(), maxX);
        int y = Math.clamp(mouseY - 16, area.top(), maxY);
        extractor.fill(x, y, x + width, y + 12, 0xF0101014);
        extractor.fill(x, y, x + width, y + 1, LuneScreen.ACCENT);
        extractor.textRenderer().accept(x + 5, y + 2,
                Component.literal(label).withColor(LuneScreen.TEXT));
    }

    /** A line in a card. Priority is the order in which compact cards give lines up. */
    private record Row(int priority, String label, String value, int colour) {}

    private void drawStatusCard(GuiGraphicsExtractor extractor, DashboardFrame.Card card,
                                BotEngine engine, Task current, DebugInfo debug) {
        drawPanelHeader(extractor, card, Lang.get("lune.gui.main.bot_status"));
        int stateColour = stateColour(engine, current);
        String state = current == null ? Lang.get("lune.gui.main.idle") : engine.isPaused() ? Lang.get("lune.gui.overlay.paused") : Lang.get("lune.gui.overlay.working");
        int availableStateWidth = Math.max(1, card.width() - 20);
        int stateWidth = Math.min(availableStateWidth,
                Math.max(62, Minecraft.getInstance().font.width(state) + 18));
        int stateX = card.x() + card.width() - stateWidth - 8;
        extractor.fill(stateX, card.y() + 5, stateX + stateWidth, card.y() + 17,
                0x40202028 | (stateColour & 0x00FFFFFF));
        extractor.textRenderer().accept(stateX + 9, card.y() + 7,
                Component.literal(fit(state, Math.max(1, stateWidth - 18))).withColor(stateColour));

        drawRows(extractor, card, 62, List.of(
                new Row(1, Lang.get("lune.gui.main.current"), current == null ? Lang.get("lune.gui.main.nothing_running") : current.name(),
                        LuneScreen.TEXT),
                new Row(2, "", current == null ? Lang.get("lune.gui.main.ready_task")
                        : firstNonBlank(current.status(), debug.taskStatus, Lang.get("lune.gui.overlay.working")), stateColour),
                current instanceof TaskRunner task
                        ? new Row(3, Lang.get("lune.gui.main.flow"), task.describeFlow(), LuneScreen.ACCENT)
                        : new Row(3, Lang.get("lune.gui.main.queue_next"),
                                debug.nextTask.isBlank() ? Lang.get("lune.gui.main.nothing_queued") : debug.nextTask,
                                LuneScreen.TEXT),
                new Row(4, Lang.get("lune.gui.main.last"), firstNonBlank(engine.getLastMessage(), Lang.get("lune.gui.main.recent_activity")),
                        Accessibility.dim())));
    }

    private void drawRecentActivityCard(GuiGraphicsExtractor extractor, DashboardFrame.Card card,
                                        BotEngine engine, DebugInfo debug) {
        drawPanelHeader(extractor, card, Lang.get("lune.gui.main.recent_activity_2"));
        String last = firstNonBlank(engine.getLastMessage(), Lang.get("lune.gui.main.activity_yet"));
        String reason = stopReason(engine, debug);
        String event = firstNonBlank(debug.lastEvent, Lang.get("lune.gui.main.no_event"));
        String decision = debug.decisions.peekLast();
        drawRows(extractor, card, 62, List.of(
                new Row(1, Lang.get("lune.gui.main.last"), last, LuneScreen.TEXT),
                new Row(2, Lang.get("lune.gui.main.why_stopped"), reason, engine.getCurrent() != null || engine.getLastMessage().isBlank()
                        ? Accessibility.dim() : Accessibility.colour(Accessibility.Mark.WARN)),
                new Row(3, Lang.get("lune.gui.main.event"), event, Accessibility.dim()),
                new Row(4, Lang.get("lune.gui.main.decision"), firstNonBlank(decision, "-"), LuneScreen.ACCENT)));
    }

    private void drawWorkingNodeCard(GuiGraphicsExtractor extractor, DashboardFrame.Card card,
                                     BotEngine engine, Task current, DebugInfo debug) {
        drawPanelHeader(extractor, card, Lang.get("lune.gui.main.current_working_node"));
        if (current == null) {
            drawRows(extractor, card, 62, List.of(
                    new Row(1, Lang.get("lune.gui.main.node"), Lang.get("lune.gui.main.active_node"), Accessibility.dim()),
                    new Row(2, Lang.get("lune.gui.main.queue"), debug.nextTask.isBlank() ? Lang.get("lune.gui.main.nothing_queued") : debug.nextTask,
                            LuneScreen.TEXT)));
            return;
        }

        String node = Lang.get("lune.gui.main.task_level_work");
        if (current instanceof TaskRunner runner && runner.currentNode() != null) {
            var command = com.etka.lune.bot.command.CommandRegistry.byId(runner.currentNode().commandId);
            node = command == null ? runner.currentNode().commandId : command.name();
        }
        TaskProgress progress = current.progress();
        String progressText = progress == null ? Lang.get("lune.gui.main.open_ended") : progress.label();
        drawRows(extractor, card, 62, List.of(
                new Row(1, Lang.get("lune.gui.main.node"), node, LuneScreen.ACCENT),
                new Row(2, Lang.get("lune.gui.main.state"), firstNonBlank(current.status(), debug.taskStatus, Lang.get("lune.gui.overlay.working")),
                        stateColour(engine, current)),
                new Row(3, Lang.get("lune.gui.main.progress"), progressText, LuneScreen.TEXT),
                new Row(4, Lang.get("lune.gui.main.path"), debug.pathLength <= 0 ? Lang.get("lune.gui.main.no_route") : debug.pathIndex + " / "
                        + debug.pathLength, Accessibility.dim()),
                new Row(5, Lang.get("lune.gui.main.goal"), debug.goal, Accessibility.dim())));
    }

    /** Draws the heading for the persistent task list in the left bottom column. */
    private void drawSavedTasksHeader(GuiGraphicsExtractor extractor, DashboardFrame.Card card,
                                      BotEngine engine) {
        drawPanelHeader(extractor, card, Lang.get("lune.gui.main.saved_tasks"));
        var font = Minecraft.getInstance().font;
        int count = engine.getQueue().size();
        String subtitle = count == 0 ? Lang.get("lune.gui.main.press_start_run_task")
                : count + (count == 1 ? Lang.get("lune.gui.main.task_waiting") : Lang.get("lune.gui.main.tasks_waiting"));
        if (font.width(Lang.get("lune.gui.main.saved_tasks")) + font.width(subtitle) + 28 <= card.width()) {
            extractor.textRenderer().accept(card.x() + card.width() - 10 - font.width(subtitle),
                    card.y() + 6, Component.literal(subtitle).withColor(Accessibility.dim()));
        }
    }

    private void drawSafetyVitalsCard(GuiGraphicsExtractor extractor, DashboardFrame.Card card,
                                      Minecraft mc) {
        drawPanelHeader(extractor, card, Lang.get("lune.gui.main.safety_monitor"));
        if (mc.player == null || mc.level == null) {
            drawRows(extractor, card, 64, List.of(
                    new Row(1, "", Lang.get("lune.gui.main.world"), Accessibility.dim()),
                    new Row(2, "", Lang.get("lune.gui.main.live_player_safety_data_appear_here"), Accessibility.dim())));
            return;
        }

        BotConfig config = BotConfig.get();
        List<Row> rows = new ArrayList<>();
        int priority = 1;
        rows.add(new Row(priority++, Lang.get("lune.gui.main.health"), formatOne(mc.player.getHealth()) + " / "
                + formatOne(mc.player.getMaxHealth()), healthColour(mc.player.getHealth(),
                mc.player.getMaxHealth())));
        rows.add(new Row(priority++, Lang.get("lune.gui.main.hunger"), mc.player.getFoodData().getFoodLevel() + " / 20",
                mc.player.getFoodData().getFoodLevel() <= 8 ? Accessibility.colour(Accessibility.Mark.WARN) : LuneScreen.TEXT));
        if (config.dashboardShowEnemies) {
            rows.add(new Row(priority++, Lang.get("lune.gui.main.enemies"), visibleEnemies(mc) + Lang.get("lune.gui.main.visible"), Accessibility.colour(Accessibility.Mark.WARN)));
        }
        rows.add(new Row(priority++, Lang.get("lune.gui.main.inventory"), occupiedSlots(mc.player) + "/"
                + mc.player.getInventory().getContainerSize() + Lang.get("lune.gui.main.used"), LuneScreen.TEXT));
        rows.add(new Row(priority++, Lang.get("lune.gui.main.air"), mc.player.getAirSupply() + " / " + mc.player.getMaxAirSupply(),
                mc.player.getAirSupply() < mc.player.getMaxAirSupply() / 3 ? Accessibility.colour(Accessibility.Mark.WARN) : LuneScreen.TEXT));
        if (config.dashboardShowSaturation) {
            rows.add(new Row(priority++, Lang.get("lune.gui.main.saturation"), formatOne(mc.player.getFoodData().getSaturationLevel()),
                    Accessibility.dim()));
        }
        if (config.dashboardShowItemConditions) {
            rows.add(new Row(priority++, Lang.get("lune.gui.main.main_hand"), itemCondition(mc.player.getMainHandItem()),
                    LuneScreen.TEXT));
            rows.add(new Row(priority++, Lang.get("lune.gui.main.off_hand"), itemCondition(mc.player.getOffhandItem()),
                    LuneScreen.TEXT));
        }
        rows.add(new Row(priority, Lang.get("lune.gui.main.armor"), mc.player.getArmorValue() + Lang.get("lune.gui.main.armor_2"), LuneScreen.TEXT));
        drawRows(extractor, card, 64, rows);
    }

    private void drawSafetyWorldCard(GuiGraphicsExtractor extractor, DashboardFrame.Card card,
                                     Minecraft mc) {
        drawPanelHeader(extractor, card, Lang.get("lune.gui.main.safety_world"));
        if (mc.player == null || mc.level == null) {
            drawRows(extractor, card, 64, List.of(
                    new Row(1, "", Lang.get("lune.gui.main.world"), Accessibility.dim()),
                    new Row(2, "", Lang.get("lune.gui.main.live_world_data_appear_here"), Accessibility.dim())));
            return;
        }

        BotConfig config = BotConfig.get();
        String seed = AutoRun.worldSeed() == 0L ? Lang.get("lune.gui.main.server_hidden") : Long.toString(AutoRun.worldSeed());
        List<Row> rows = new ArrayList<>();
        int priority = 1;
        if (config.dashboardShowCoordinates) {
            rows.add(new Row(priority++, Lang.get("lune.gui.main.position"), mc.player.blockPosition().toShortString(),
                    LuneScreen.TEXT));
        }
        rows.add(new Row(priority++, Lang.get("lune.gui.main.dimension"), mc.level.dimension().identifier().toString(),
                LuneScreen.TEXT));
        if (config.dashboardShowSeed) {
            rows.add(new Row(priority++, Lang.get("lune.gui.main.seed"), seed, Accessibility.dim()));
        }
        if (config.dashboardShowFacing) {
            rows.add(new Row(priority++, Lang.get("lune.gui.main.facing"), Mth.floor(Mth.wrapDegrees(mc.player.getYRot())) + "°",
                    LuneScreen.TEXT));
        }
        if (config.dashboardShowLight) {
            rows.add(new Row(priority++, Lang.get("lune.gui.main.light"), lightLevel(mc), Accessibility.dim()));
        }
        if (config.dashboardShowEnvironment) {
            String environment = environmentKey(mc.player);
            rows.add(new Row(priority++, Lang.get("lune.gui.main.state"), Lang.get(environment),
                    environment.equals("lune.gui.main.safe") ? Accessibility.dim() : Accessibility.colour(Accessibility.Mark.WARN)));
        }
        if (config.dashboardShowExperience) {
            rows.add(new Row(priority++, Lang.get("lune.gui.main.xp"), experience(mc.player), LuneScreen.TEXT));
        }
        if (config.dashboardShowEffects) {
            rows.add(new Row(priority++, Lang.get("lune.gui.main.effects"), activeEffects(mc.player), Accessibility.dim()));
        }
        rows.add(new Row(priority, Lang.get("lune.gui.main.input"), Lang.get("lune.gui.main.live_player_world"), Accessibility.dim()));
        drawRows(extractor, card, 64, rows);
    }

    private void drawStatisticsCard(GuiGraphicsExtractor extractor, DashboardFrame.Card card) {
        drawPanelHeader(extractor, card, Lang.get("lune.gui.main.statistics"));
        int tabY = card.y() + TITLE_H + 2;
        int tabsWidth = Math.max(1, card.width() - 12);
        int tabWidth = Math.max(20, (tabsWidth - BUTTON_GAP * 2) / 3);
        int tabX = card.x() + 6;
        int selected = statisticsScope.ordinal();
        int selectedX = tabX + selected * (tabWidth + BUTTON_GAP);
        int selectedWidth = selected == 2
                ? Math.max(1, tabsWidth - (tabWidth + BUTTON_GAP) * 2) : tabWidth;
        extractor.fill(selectedX, tabY + 15, selectedX + selectedWidth, tabY + 17, LuneScreen.ACCENT);
    }

    private enum StatisticsScope {
        CURRENT,
        LAST_RUN,
        ALL_TIME
    }

    /**
     * Everything the bot measured about itself, grouped the way a run is actually judged: did it
     * finish, what did it produce, how well did it move, and what nearly killed it.
     *
     * <p>Minecraft's own counters used to fill the lower half of this card and have been dropped.
     * The client only receives them when the vanilla statistics screen asks the server for them, so
     * they read as zero for exactly the runs a dashboard exists to describe.</p>
     */
    private List<DashboardStatsPanel.Line> statisticsLines(BotEngine engine) {
        BotStatistics statistics = switch (statisticsScope) {
            case CURRENT -> engine.currentRunStatistics();
            case LAST_RUN -> engine.lastRunStatistics();
            case ALL_TIME -> engine.allTimeStatistics();
        };
        long attempted = statistics.tasksCompleted + statistics.tasksFailed;
        long searches = statistics.repaths;
        long failedSearches = statistics.counter("path_empty");
        long stalls = statistics.counter("stalls");
        long deaths = statistics.counter("deaths");
        long closeCalls = statistics.counter("close_calls");
        long dangers = statistics.counter("danger_responses");

        List<DashboardStatsPanel.Line> lines = new ArrayList<>();
        lines.add(DashboardStatsPanel.Line.section(statisticsScope == StatisticsScope.CURRENT
                ? Lang.get("lune.gui.main.current_run") : statisticsScope == StatisticsScope.LAST_RUN
                ? Lang.get("lune.gui.main.last_task_2") : Lang.get("lune.gui.main.all_time_2")));

        lines.add(DashboardStatsPanel.Line.section(Lang.get("lune.gui.main.overview")));
        statLine(lines, Lang.get("lune.gui.main.worked_time"), formatDuration(statistics.workedTicks), LuneScreen.TEXT);
        statLine(lines, Lang.get("lune.gui.main.runs"), number(statistics.counter("runs")), Accessibility.dim());
        statLine(lines, Lang.get("lune.gui.main.tasks_finished"), number(statistics.tasksCompleted), LuneScreen.TEXT);
        statLine(lines, Lang.get("lune.gui.main.tasks_failed"), number(statistics.tasksFailed),
                statistics.tasksFailed == 0 ? Accessibility.Mark.NEUTRAL : Accessibility.Mark.WARN);
        statLine(lines, Lang.get("lune.gui.main.success_rate"), percent(statistics.tasksCompleted, attempted),
                attempted == 0 ? Accessibility.Mark.NEUTRAL : statistics.tasksFailed == 0 ? Accessibility.Mark.GOOD : Accessibility.Mark.WARN);
        statLine(lines, Lang.get("lune.gui.main.average_task"), perUnit(statistics.workedTicks, attempted), LuneScreen.TEXT);
        statLine(lines, Lang.get("lune.gui.main.longest_task"), formatDuration(statistics.peak("task_ticks")),
                Accessibility.dim());

        lines.add(DashboardStatsPanel.Line.section(Lang.get("lune.gui.main.work")));
        statLine(lines, Lang.get("lune.gui.main.blocks_broken"), number(statistics.blocksBroken), LuneScreen.ACCENT);
        statLine(lines, Lang.get("lune.gui.main.blocks_placed"), number(statistics.blocksPlaced), LuneScreen.ACCENT);
        statLine(lines, Lang.get("lune.gui.main.ores_mined"), number(statistics.counter("ores_mined")), LuneScreen.ACCENT);
        statLine(lines, Lang.get("lune.gui.main.logs_chopped"), number(statistics.counter("logs_chopped")), LuneScreen.ACCENT);
        statLine(lines, Lang.get("lune.gui.main.mining_rate"), perMinute(statistics.blocksBroken, statistics.workedTicks),
                LuneScreen.TEXT);
        statLine(lines, Lang.get("lune.gui.main.items_crafted"), number(statistics.counter("items_crafted")), LuneScreen.TEXT);
        statLine(lines, Lang.get("lune.gui.main.items_gained"), number(statistics.counter("items_gained")), LuneScreen.TEXT);
        statLine(lines, Lang.get("lune.gui.main.drops_swept"), number(statistics.counter("drops_swept")), LuneScreen.TEXT);
        statLine(lines, Lang.get("lune.gui.main.xp_gained"), number(statistics.counter("xp_gained")), LuneScreen.TEXT);
        statLine(lines, Lang.get("lune.gui.main.food_eaten"), number(statistics.foodEaten), LuneScreen.TEXT);

        lines.add(DashboardStatsPanel.Line.section(Lang.get("lune.gui.main.movement")));
        statLine(lines, Lang.get("lune.gui.main.distance"), distance(statistics, "distance_cm"), LuneScreen.TEXT);
        statLine(lines, Lang.get("lune.gui.main.sprinted"), distance(statistics, "sprint_cm"), LuneScreen.TEXT);
        statLine(lines, Lang.get("lune.gui.main.swum"), distance(statistics, "swim_cm"), LuneScreen.TEXT);
        statLine(lines, Lang.get("lune.gui.main.climbed"), distance(statistics, "climb_cm"), LuneScreen.TEXT);
        statLine(lines, Lang.get("lune.gui.main.descended"), distance(statistics, "descend_cm"), LuneScreen.TEXT);
        statLine(lines, Lang.get("lune.gui.main.jumps"), number(statistics.counter("jumps")), Accessibility.dim());
        statLine(lines, Lang.get("lune.gui.main.travel_speed"), perMinute(statistics.counter("distance_cm") / 100L,
                statistics.workedTicks, Lang.get("lune.gui.main.blocks_per_minute")), Accessibility.dim());
        statLine(lines, Lang.get("lune.gui.main.farthest_out"), blocks(statistics.peak("home_cm") / 100.0D),
                Accessibility.dim());

        lines.add(DashboardStatsPanel.Line.section(Lang.get("lune.gui.main.navigation")));
        statLine(lines, Lang.get("lune.gui.main.route_searches"), number(searches), LuneScreen.TEXT);
        statLine(lines, Lang.get("lune.gui.main.dead_ends"), number(failedSearches),
                failedSearches == 0 ? Accessibility.Mark.NEUTRAL : Accessibility.Mark.WARN);
        statLine(lines, Lang.get("lune.gui.main.routes_found"), percent(searches - failedSearches, searches),
                searches == 0 ? Accessibility.Mark.NEUTRAL : failedSearches == 0 ? Accessibility.Mark.GOOD : Accessibility.Mark.WARN);
        statLine(lines, Lang.get("lune.gui.main.search_time"), millis(statistics.counter("path_micros")), Accessibility.dim());
        statLine(lines, Lang.get("lune.gui.main.average_search"),
                millis(divide(statistics.counter("path_micros"), searches)), LuneScreen.TEXT);
        statLine(lines, Lang.get("lune.gui.main.average_nodes"),
                number(divide(statistics.counter("path_nodes"), searches)), Accessibility.dim());
        statLine(lines, Lang.get("lune.gui.main.longest_route"), Lang.get("lune.gui.main.route_nodes", number(statistics.peak("path_length"))),
                Accessibility.dim());
        statLine(lines, Lang.get("lune.gui.main.stalls_recovered"), number(stalls),
                stalls == 0 ? Accessibility.Mark.NEUTRAL : Accessibility.Mark.WARN);

        lines.add(DashboardStatsPanel.Line.section(Lang.get("lune.gui.main.survival")));
        statLine(lines, Lang.get("lune.gui.main.deaths"), number(deaths),
                deaths == 0 ? Accessibility.Mark.NEUTRAL : Accessibility.Mark.BAD);
        statLine(lines, Lang.get("lune.gui.main.health_lost"), health(statistics.counter("health_lost_tenths")),
                LuneScreen.TEXT);
        statLine(lines, Lang.get("lune.gui.main.biggest_hit"), health(statistics.peak("hit_tenths")), LuneScreen.TEXT);
        statLine(lines, Lang.get("lune.gui.main.close_calls"), number(closeCalls),
                closeCalls == 0 ? Accessibility.Mark.NEUTRAL : Accessibility.Mark.WARN);
        statLine(lines, Lang.get("lune.gui.main.mobs_killed"), number(statistics.counter("mobs_killed")), LuneScreen.TEXT);
        statLine(lines, Lang.get("lune.gui.main.escapes"), number(dangers),
                dangers == 0 ? Accessibility.dim() : LuneScreen.TEXT);
        statLine(lines, Lang.get("lune.gui.main.from_monsters"), number(statistics.counter("danger_monster")),
                Accessibility.dim());
        statLine(lines, Lang.get("lune.gui.main.from_lava"), number(statistics.counter("danger_lava")), Accessibility.dim());
        statLine(lines, Lang.get("lune.gui.main.from_fire"), number(statistics.counter("danger_fire")), Accessibility.dim());
        statLine(lines, Lang.get("lune.gui.main.from_drowning"), number(statistics.counter("danger_drowning")),
                Accessibility.dim());
        statLine(lines, Lang.get("lune.gui.main.from_falls"), number(statistics.counter("danger_fall")), Accessibility.dim());
        statLine(lines, Lang.get("lune.gui.main.from_fireballs"), number(statistics.counter("danger_fireball")),
                Accessibility.dim());
        statLine(lines, Lang.get("lune.gui.main.heal_up"), number(statistics.counter("danger_health")),
                Accessibility.dim());
        statLine(lines, Lang.get("lune.gui.main.fireballs_batted"), number(statistics.counter("fireballs_batted")),
                LuneScreen.TEXT);

        if (BotConfig.get().dashboardShowLearning) {
            lines.add(DashboardStatsPanel.Line.section(Lang.get("lune.gui.main.learning")));
            statLine(lines, Lang.get("lune.gui.main.profile"), engine.getLearning().displaySummary(), Accessibility.dim());
        }
        return lines;
    }

    private static void statLine(List<DashboardStatsPanel.Line> lines, String label, String value,
                                 int colour) {
        lines.add(DashboardStatsPanel.Line.metric(label, value, colour));
    }

    /**
     * A row whose colour is the whole message, so it also gets a mark when one is wanted.
     *
     * <p>"0" in grey and "3" in amber differ by more than the digit; "100%" in green and "92%" in
     * amber differ by nothing else at all. These are the rows where reading the colour is reading
     * the row, so they are the ones that carry a glyph in colour-blind-safe mode.</p>
     */
    private static void statLine(List<DashboardStatsPanel.Line> lines, String label, String value,
                                 Accessibility.Mark mark) {
        lines.add(DashboardStatsPanel.Line.metric(label, Accessibility.marked(value, mark),
                Accessibility.colour(mark)));
    }

    private static String number(long value) {
        return String.format(Locale.ROOT, "%,d", Math.max(0L, value));
    }

    private static long divide(long total, long count) {
        return count <= 0L ? 0L : total / count;
    }

    /** A share of a total, or a dash while the total is still zero and the ratio would be a lie. */
    private static String percent(long part, long total) {
        return total <= 0L ? "-"
                : String.format(Locale.ROOT, "%d%%", Math.round(100.0D * part / total));
    }

    private static String perUnit(long ticks, long count) {
        return count <= 0L ? "-" : formatDuration(ticks / count);
    }

    private static String perMinute(long amount, long ticks) {
        return perMinute(amount, ticks, "");
    }

    private static String perMinute(long amount, long ticks, String suffix) {
        return ticks <= 0L ? "-"
                : String.format(Locale.ROOT, "%.1f%s", amount * 1200.0D / ticks, suffix);
    }

    private static String distance(BotStatistics statistics, String key) {
        return blocks(statistics.counter(key) / 100.0D);
    }

    private static String blocks(double value) {
        return Lang.get("lune.gui.main.1f_blocks", value);
    }

    private static String health(long tenths) {
        return String.format(Locale.ROOT, "%.1f", tenths / 10.0D);
    }

    private static String millis(long micros) {
        return Lang.get("lune.gui.main.1f_ms", micros / 1000.0D);
    }

    /** Draws the rows a card is tall enough for, in priority order. */
    private static void drawRows(GuiGraphicsExtractor extractor, DashboardFrame.Card card,
                                 int labelWidth, List<Row> rows) {
        var text = extractor.textRenderer();
        int textX = card.x() + 10;
        int availableLabelWidth = Math.min(labelWidth,
                Math.max(24, (card.width() - 20) / 2));
        int y = card.y() + TITLE_H + BODY_TOP;
        int drawn = 0;
        for (Row row : rows.stream().sorted(java.util.Comparator.comparingInt(Row::priority)).toList()) {
            if (drawn >= card.rows()) {
                break;
            }
            if (row.label().isEmpty()) {
                text.accept(textX, y, Component.literal(fit(row.value(), card.width() - 20))
                        .withColor(row.colour()));
            } else {
                text.accept(textX, y, Component.literal(fit(row.label(), availableLabelWidth))
                        .withColor(Accessibility.dim()));
                text.accept(textX + availableLabelWidth, y,
                        Component.literal(fit(row.value(), card.width() - availableLabelWidth - 20))
                                .withColor(row.colour()));
            }
            y += card.lineHeight();
            drawn++;
        }
    }

    private static int stateColour(BotEngine engine, Task current) {
        if (current == null) {
            return Accessibility.colour(Accessibility.Mark.NEUTRAL);
        }
        return engine.isPaused() ? Accessibility.colour(Accessibility.Mark.WARN) : Accessibility.colour(Accessibility.Mark.GOOD);
    }

    private static int healthColour(float health, float maxHealth) {
        return health <= maxHealth / 3.0F ? Accessibility.colour(Accessibility.Mark.WARN) : LuneScreen.TEXT;
    }

    private static String firstNonBlank(String first, String fallback) {
        return first != null && !first.isBlank() ? first : fallback;
    }

    private static String firstNonBlank(String first, String second, String fallback) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return fallback;
    }

    private static String stopReason(BotEngine engine, DebugInfo debug) {
        String message = engine.getLastMessage();
        if (message != null && message.startsWith("Stopped:")) {
            return message.substring("Stopped:".length()).trim();
        }
        String failure = debug.failures.peekLast();
        if (failure != null && !failure.isBlank()) {
            return failure;
        }
        if (debug.automaticReason != null && !debug.automaticReason.isBlank()) {
            return debug.automaticReason;
        }
        if (engine.getCurrent() == null && message != null && !message.isBlank()) {
            return message;
        }
        return engine.getCurrent() == null ? Lang.get("lune.gui.main.still_running_ready") : Lang.get("lune.gui.main.still_running");
    }

    private static int occupiedSlots(net.minecraft.client.player.LocalPlayer player) {
        int occupied = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (!player.getInventory().getItem(slot).isEmpty()) {
                occupied++;
            }
        }
        return occupied;
    }

    private static String itemCondition(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Lang.get("lune.gui.main.empty");
        }
        String name = stack.getHoverName().getString();
        if (stack.isDamageableItem() && stack.getMaxDamage() > 0) {
            return name + " " + Math.max(0, stack.getMaxDamage() - stack.getDamageValue())
                    + "/" + stack.getMaxDamage();
        }
        return name + " x" + stack.getCount();
    }

    private static String experience(net.minecraft.client.player.LocalPlayer player) {
        return player.experienceLevel + " (" + Math.round(player.experienceProgress * 100.0F) + "%)";
    }

    private static String activeEffects(net.minecraft.client.player.LocalPlayer player) {
        int effects = player.getActiveEffects().size();
        return effects == 0 ? Lang.get("lune.gui.param.none") : Lang.get("lune.gui.main.active_effect_count", effects);
    }

    private static String environmentKey(net.minecraft.client.player.LocalPlayer player) {
        if (player.isOnFire()) {
            return "lune.gui.main.fire";
        }
        if (player.isInLava()) {
            return "lune.gui.main.lava";
        }
        if (player.isInWater()) {
            return "lune.gui.main.swimming";
        }
        return player.onGround() ? "lune.gui.main.safe" : "lune.gui.main.airborne";
    }

    private static String lightLevel(Minecraft mc) {
        var pos = mc.player.blockPosition();
        return Lang.get("lune.gui.main.block") + mc.level.getBrightness(LightLayer.BLOCK, pos)
                + Lang.get("lune.gui.main.sky") + mc.level.getBrightness(LightLayer.SKY, pos);
    }

    private static int visibleEnemies(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            return 0;
        }
        AABB area = mc.player.getBoundingBox().inflate(24.0);
        return mc.level.getEntities(mc.player, area,
                entity -> entity instanceof Enemy && entity.isAlive()
                        && Vision.isEntityVisible(mc, mc.player, mc.level, entity)).size();
    }

    private static String formatOne(float value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String formatDuration(long ticks) {
        return Durations.ofTicks(ticks);
    }

    private static String fit(String value, int maxWidth) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        var font = Minecraft.getInstance().font;
        if (font.width(value) <= maxWidth) {
            return value;
        }
        String suffix = "…";
        return font.plainSubstrByWidth(value, Math.max(0, maxWidth - font.width(suffix)), false) + suffix;
    }

    private void drawPanelHeader(GuiGraphicsExtractor extractor, DashboardFrame.Card card, String title) {
        drawPanelHeader(extractor, card.x(), card.y(), card.width(), title);
    }

    private void drawPanelHeader(GuiGraphicsExtractor extractor, int x, int y, int width, String title) {
        extractor.fill(x + 1, y + 1, x + width - 1, y + TITLE_H, PANEL_HEADER);
        extractor.textRenderer().accept(x + 9, y + 5,
                Component.literal(fit(title, Math.max(1, width - 18))).withColor(LuneScreen.ACCENT));
    }

    private static void panel(GuiGraphicsExtractor extractor, DashboardFrame.Card card) {
        LuneScreen.panel(extractor, card.x(), card.y(), card.width(), card.height());
    }
}
