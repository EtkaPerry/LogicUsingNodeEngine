package com.etka.lune.client.gui.tab;

import com.etka.lune.compat.Screens;
import com.etka.lune.util.Lang;
import com.etka.lune.bot.BotEngine;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.command.CommandDef;
import com.etka.lune.bot.command.CommandRegistry;
import com.etka.lune.bot.task.TaskRunner;
import com.etka.lune.client.gui.LuneScreen;
import com.etka.lune.client.gui.LuneTab;
import com.etka.lune.bot.command.Param;
import com.etka.lune.client.gui.widget.BlockPicker;
import com.etka.lune.client.gui.widget.NamePrompt;
import com.etka.lune.client.gui.widget.RecipePicker;
import com.etka.lune.client.gui.widget.InventoryPicker;
import com.etka.lune.client.gui.widget.SoundPicker;
import com.etka.lune.client.gui.widget.BlueprintPanel;
import com.etka.lune.client.gui.widget.ListPanel;
import com.etka.lune.client.gui.widget.PalettePanel;
import com.etka.lune.client.gui.widget.ParamPanel;
import com.etka.lune.client.gui.widget.TrainingScreen;
import com.etka.lune.client.gui.widget.VerticalSplitter;
import com.etka.lune.training.TrainingCourse;
import com.etka.lune.training.TrainingAnswer;
import com.etka.lune.training.TrainingLesson;
import com.etka.lune.training.TrainingProgress;
import com.etka.lune.task.TaskDebug;
import com.etka.lune.task.TaskGraph;
import com.etka.lune.task.TaskGroup;
import com.etka.lune.task.TaskCableAnchor;
import com.etka.lune.task.TaskWiring;
import com.etka.lune.task.TaskNode;
import com.etka.lune.task.TaskStore;
import com.etka.lune.config.BotConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;

/**
 * Task editor: your tasks on the left, the selected task's steps in the middle, and the selected
 * step's parameters on the right.
 * <p>
 * Steps run top to bottom. Each one repeats a set number of times (or forever) and then moves on;
 * a step that fails stops the task unless it has a failure edge. A While companion is different:
 * it stays live for the duration of the step it is attached to. Because "run a task" is itself a
 * command, a task can end by handing off to another one - which is how a mining run switches to
 * harvesting and back.
 * <p>
 * <b>Export</b> puts the task on the clipboard as JSON and <b>Import</b> reads one back, so tasks
 * can be shared. Blocks and mobs are stored as namespaced ids, so an imported task
 * works on a different mod set, quietly dropping anything unrecognised.
 */
public class TasksTab extends LuneTab {

    private static final int MARGIN = 8;
    private static final int GAP = 6;
    /**
     * Height reserved at the foot of the task pane for its button block.
     *
     * <p>Four rows now: Training sits full-width above New/Delete/Run, where it reads as a heading
     * over the block rather than a fourth peer squeezed into a row of three.</p>
     */
    private static final int BUTTON_ROW = 91;
    private static final int BUTTON_GAP = 2;
    /**
     * Offered repeat counts; 0 is the endless one and shows as an infinity sign.
     *
     * <p>Spread wide on purpose. The old list stopped at 25, which is fine for "chop a few trees"
     * and useless for the long unattended jobs this is actually left running for - those want a
     * thousand or no limit at all. Anything not on the list can be typed into the box beside it.
     */
    private static final int[] REPEAT_CYCLE = {1, 2, 3, 5, 10, 20, 50, 100, 1000, 0};
    /** Pulse periods offered for an Always source; zero is continuous and the seconds box accepts any value in range. */
    private static final int[] ALWAYS_INTERVAL_CYCLE = {0, 1, 5, 10, 30, 60, 300, 600};
    /** Upper bound on a typed repeat, so a slipped keystroke cannot become a millions-long job. */
    private static final int MAX_TYPED_REPEAT = 1_000_000;

    private static final int DEFAULT_LEFT_PANE = 132;
    private static final int DEFAULT_RIGHT_PANE = 180;
    private static final int MIN_PANE = 100;
    private static final int MIN_CENTER = 80;
    /**
     * Below this the three panes stop being worth it. The step controls total 272px laid end to
     * end and pack onto two rows at about 160, so a centre pane narrower than that starts wrapping
     * them into a third row and eating the canvas: 132 + 180 for the sides, 12 for the gaps and
     * 160 for the middle is 484. The task list is the pane that gives way, because you pick a task
     * once and then spend the rest of the session in the canvas and the palette.
     */
    private static final int THREE_PANE_WIDTH = 490;
    private static final int SEARCH_H = 18;
    private static final int CONTROL_H = 18;
    private static final int CONTROL_GAP = 4;
    private static final int CONTROL_ROW_H = CONTROL_H + 3;
    /** Restores the editor selection when the control screen/tab is opened again this session. */
    private static String lastOpenedTaskName;

    private final ListPanel<TaskGraph> taskList;
    private final BlueprintPanel blueprintPanel;
    private final PalettePanel palettePanel;
    private final ParamPanel stepParams;
    private BlockPicker blockPicker;
    private InventoryPicker inventoryPicker;
    private RecipePicker recipePicker;
    private SoundPicker soundPicker;
    private NamePrompt namePrompt;
    private TrainingScreen trainingScreen;
    /** The lesson being attempted, or null when the editor is being used for real work. */
    private TrainingLesson activeLesson;
    /** Ticks spent on the current lesson, used to rotate gentle practice dialogue. */
    private int lessonTicks;
    private String lessonSpeech = "";
    private boolean previewFailure;
    private boolean helpExpanded;
    private boolean hintUsed;
    private boolean answerUsed;
    /** Number of Answer requests; the fifth request starts the visible auto-solve. */
    private int answerPresses;
    private boolean answerRevealActive;
    private int answerRevealTicks;
    private boolean answerRevealHistoryStarted;
    /** True once the current lesson is solved; training stays on so the task can be run. */
    private boolean lessonSolved;
    /** The task selected before training started, restored on the way out. */
    private String taskBeforeTraining;
    /**
     * The task that lesson was handed to, by name.
     *
     * <p>By name rather than by reference: undo restores the whole store from a JSON snapshot, so
     * every {@link TaskGraph} the tab is holding is replaced by an equal one the moment the player
     * presses it, and a reference kept here would go quietly stale mid-puzzle.</p>
     */
    private String lessonTaskName;
    private final EditBox nameBox;
    private final EditBox paletteSearch;

    private final Button newButton;
    private final Button deleteButton;
    private final Button importButton;
    private final Button exportButton;
    private final Button runButton;
    private final Button undoButton;
    private final Button redoButton;
    private final Button trainingButton;
    private final Button restartButton;
    private final Button outcomeButton;
    private final Button helpButton;
    private final Button nextLessonButton;

    private boolean confirmingDelete;
    private final ArrayDeque<HistoryEntry> undoStack = new ArrayDeque<>();
    private final ArrayDeque<HistoryEntry> redoStack = new ArrayDeque<>();
    /** Normal editor history is parked while a scratch lesson is open. */
    private ArrayDeque<HistoryEntry> savedUndoStack;
    private ArrayDeque<HistoryEntry> savedRedoStack;
    private String lastStoreSnapshot;
    private String selectedTaskName;
    private String knownTaskList;
    /** A drag can change the graph for many ticks; hold its first snapshot until the drag settles. */
    private String pendingHistorySnapshot;
    private String pendingHistoryTaskName;
    private int pendingHistoryIdleTicks;

    private record HistoryEntry(String snapshot, String taskName) {}

    private final Button removeStepButton;
    private final Button upButton;
    private final Button downButton;
    private final Button repeatButton;
    private final EditBox repeatBox;
    private final Button alwaysFrequencyButton;
    private final EditBox alwaysSecondsBox;
    private final Button relayInputsButton;
    private final Button relayOutputsButton;
    private final Button buttonPressButton;
    /** Guards the responder while the box is being refreshed from the selection, not by typing. */
    private boolean syncingRepeatBox;
    private boolean syncingAlwaysSecondsBox;
    private final Button layoutButton;
    private final Button minimapButton;
    private final Button findButton;
    private final Button noteButton;
    private final Button groupButton;
    /**
     * The debugger's three controls.
     *
     * <p>Only on screen when there is something to debug - a card selected, or a run to hold. A
     * permanent Step button on a tab nobody is debugging is three buttons of canvas given away to
     * a mode most sessions never enter.</p>
     */
    private final Button breakpointButton;
    private final Button stepButton;
    private final Button resumeButton;
    /** Only appears when the tab is too narrow for three panes; shows and hides the task list. */
    private final Button tasksButton;

    private final VerticalSplitter leftSplitter;
    private final VerticalSplitter rightSplitter;

    private int leftPaneWidth = DEFAULT_LEFT_PANE;
    private int rightPaneWidth = DEFAULT_RIGHT_PANE;
    /** Whether the collapsed task list is currently pulled open. Only consulted when narrow. */
    private boolean listExpanded;
    /** Rows the control strip wrapped onto last layout; the canvas starts below them. */
    private int controlRows = 1;

    private String message = "";

    public TasksTab() {
        super(Component.literal(Lang.get("lune.gui.tasks.task")));

        taskList = add(new ListPanel<>(0, 0, 10, 10, TaskGraph::describe, this::onTaskSelected));
        blueprintPanel = add(new BlueprintPanel(0, 0, 10, 10, this::onStepSelected,
                () -> TaskStore.get().save(), value -> message = value, this::removeSteps));
        palettePanel = add(new PalettePanel(0, 0, 10, 10, this::onPaletteSelect, this::onPaletteDrop));
        paletteSearch = add(new EditBox(Minecraft.getInstance().font, 0, 0, 10, 16, Component.literal(Lang.get("lune.gui.tasks.search"))));
        paletteSearch.setHint(Component.literal(Lang.get("lune.gui.tasks.search_nodes")));
        paletteSearch.setMaxLength(32);
        paletteSearch.setResponder(this::onPaletteSearch);
        stepParams = add(new ParamPanel(0, 0, 10, 10));

        leftSplitter = add(new VerticalSplitter(0, 0, 10, 1, dx -> resizeLeftPane(dx)));
        rightSplitter = add(new VerticalSplitter(0, 0, 10, -1, dx -> resizeRightPane(dx)));

        nameBox = add(new EditBox(Minecraft.getInstance().font, 0, 0, 120, 16, Component.literal(Lang.get("lune.gui.tasks.name"))));
        nameBox.setHint(Component.literal(Lang.get("lune.gui.tasks.task_name")));
        nameBox.setMaxLength(32);
        nameBox.setResponder(this::onRename);

        newButton = add(Button.builder(Component.literal(Lang.get("lune.gui.tasks.new")), b -> createTask()).size(42, 18).build());
        deleteButton = add(Button.builder(Component.literal(Lang.get("lune.gui.tasks.del")), b -> deleteTask()).size(42, 18)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.literal(Lang.get("lune.gui.tasks.delete_whole_task_delete_card_instead"))))
                .build());
        importButton = add(Button.builder(Component.literal(Lang.get("lune.gui.tasks.import")), b -> importTask()).size(44, 18).build());
        exportButton = add(Button.builder(Component.literal(Lang.get("lune.gui.tasks.export")), b -> exportTask()).size(50, 18).build());
        runButton = add(Button.builder(Component.literal(Lang.get("lune.gui.tasks.run")), b -> runTask()).size(124, 18).build());
        trainingButton = add(Button.builder(Component.literal(Lang.get("lune.gui.tasks.training")), b -> onTrainingButton())
                .size(104, 18)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.literal(Lang.get("lune.gui.tasks.ten_puzzles_easiest_first_each_one_hands"))))
                .build());
        restartButton = add(Button.builder(Component.literal(Lang.get("lune.gui.tasks.restart")), b -> restartLesson())
                .size(96, 18).build());
        outcomeButton = add(Button.builder(Component.literal(Lang.get("lune.gui.tasks.path_success")), b -> {
            previewFailure = !previewFailure;
            outcomeButtonLabel();
        }).size(108, 18).build());
        helpButton = add(Button.builder(Component.literal(Lang.get("lune.gui.tasks.need_help")), b -> showHelp())
                .size(104, 18).build());
        nextLessonButton = add(Button.builder(Component.literal(Lang.get("lune.gui.tasks.next")), b -> nextLesson())
                .size(104, 18).build());
        undoButton = add(Button.builder(Component.literal(Lang.get("lune.gui.tasks.undo")), b -> undo()).size(42, 18).build());
        redoButton = add(Button.builder(Component.literal(Lang.get("lune.gui.tasks.redo")), b -> redo()).size(42, 18).build());

        removeStepButton = add(Button.builder(Component.literal(Lang.get("lune.gui.tasks.del")), b -> removeStep()).size(46, 18).build());
        upButton = add(Button.builder(Component.literal("▲"), b -> moveStep(-1)).size(22, 18).build());
        downButton = add(Button.builder(Component.literal("▼"), b -> moveStep(1)).size(22, 18).build());
        repeatButton = add(Button.builder(Component.literal(Lang.get("lune.gui.tasks.x1")), b -> cycleRepeat()).size(34, 18).build());
        // A typed box beside the cycle, because the useful count is often not on any short list -
        // "run this 37 times" is a perfectly ordinary thing to want and cycling to it is not.
        repeatBox = add(new EditBox(Minecraft.getInstance().font, 0, 0, 34, 18,
                Component.literal(Lang.get("lune.gui.tasks.repeat"))));
        repeatBox.setMaxLength(7);
        repeatBox.setResponder(TasksTab.this::applyTypedRepeat);
        alwaysFrequencyButton = add(Button.builder(Component.literal(Lang.get("lune.gui.tasks.every_tick")),
                b -> cycleAlwaysFrequency()).size(62, 18).build());
        alwaysSecondsBox = add(new EditBox(Minecraft.getInstance().font, 0, 0, 38, 18,
                Component.literal(Lang.get("lune.gui.tasks.seconds"))));
        alwaysSecondsBox.setHint(Component.literal(Lang.get("lune.gui.tasks.sec")));
        alwaysSecondsBox.setMaxLength(4);
        alwaysSecondsBox.setResponder(TasksTab.this::applyTypedAlwaysFrequency);
        relayInputsButton = add(Button.builder(Component.literal(Lang.get("lune.gui.tasks.inputs_1")),
                b -> cycleRelayPorts(true)).size(64, 18).build());
        relayOutputsButton = add(Button.builder(Component.literal(Lang.get("lune.gui.tasks.outputs_1")),
                b -> cycleRelayPorts(false)).size(72, 18).build());
        buttonPressButton = add(Button.builder(Component.literal(Lang.get("lune.gui.tasks.press")),
                b -> pressSelectedButton()).size(42, 18).build());
        layoutButton = add(Button.builder(Component.literal(Lang.get("lune.gui.tasks.layout")), b -> blueprintPanel.autoLayout()).size(48, 18).build());
        minimapButton = add(Button.builder(Component.literal(Lang.get("lune.gui.tasks.map")), b -> toggleMinimap()).size(58, 18).build());
        findButton = add(Button.builder(Component.literal(Lang.get("lune.gui.tasks.find")),
                        b -> blueprintPanel.toggleFind()).size(42, 18)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.literal(Lang.get("lune.gui.tasks.find_tip"))))
                .build());
        noteButton = add(Button.builder(Component.literal(Lang.get("lune.gui.tasks.note")),
                        b -> addNote()).size(44, 18)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.literal(Lang.get("lune.gui.tasks.note_tip"))))
                .build());
        groupButton = add(Button.builder(Component.literal(Lang.get("lune.gui.tasks.group")),
                        b -> groupCards()).size(52, 18)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.literal(Lang.get("lune.gui.tasks.group_tip"))))
                .build());
        breakpointButton = add(Button.builder(Component.literal(Lang.get("lune.gui.tasks.breakpoint")),
                        b -> toggleBreakpoint()).size(52, 18)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.literal(Lang.get("lune.gui.tasks.breakpoint_tip"))))
                .build());
        stepButton = add(Button.builder(Component.literal(Lang.get("lune.gui.tasks.debug_step")),
                        b -> stepTask()).size(44, 18)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.literal(Lang.get("lune.gui.tasks.debug_step_tip"))))
                .build());
        resumeButton = add(Button.builder(Component.literal(Lang.get("lune.gui.tasks.debug_continue")),
                        b -> resumeTask()).size(60, 18)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.literal(Lang.get("lune.gui.tasks.debug_continue_tip"))))
                .build());
        tasksButton = add(Button.builder(Component.literal(Lang.get("lune.gui.tasks.title")), b -> toggleList()).size(46, 18).build());

        // Hidden until there is something to debug; layout may run before the first tick does.
        breakpointButton.visible = false;
        stepButton.visible = false;
        resumeButton.visible = false;

        stepParams.setOpenBlockPicker(this::openBlockPicker);
        stepParams.setOpenItemPicker(this::openItemPicker);
        stepParams.setOpenRecipePicker(this::openRecipePicker);
        stepParams.setOpenNamePrompt(this::openNamePrompt);
        stepParams.setOpenChoicePicker(this::openChoicePicker);

        if (lastOpenedTaskName == null && BotConfig.get().lastOpenedTask != null
                && !BotConfig.get().lastOpenedTask.isBlank()) {
            lastOpenedTaskName = BotConfig.get().lastOpenedTask;
        }
        refreshTasks();
        restoreTaskSelection();
        recordEdit();
    }

    // --- task level -------------------------------------------------------

    public void setBlockPicker(BlockPicker picker) {
        this.blockPicker = picker;
    }

    public void setInventoryPicker(InventoryPicker picker) {
        this.inventoryPicker = picker;
    }

    public void setSoundPicker(SoundPicker picker) {
        this.soundPicker = picker;
    }

    public void setRecipePicker(RecipePicker picker) {
        this.recipePicker = picker;
    }

    public void setNamePrompt(NamePrompt prompt) {
        this.namePrompt = prompt;
    }

    public void setTrainingScreen(TrainingScreen screen) {
        this.trainingScreen = screen;
        if (screen != null) {
            screen.setOnStartLesson(this::startLesson);
        }
    }

    // --- training ------------------------------------------------------------

    /** True while a puzzle is open, which is what puts the tab - and Lune - into training mode. */
    public boolean inTraining() {
        return activeLesson != null;
    }

    /** Gentle, non-answer guidance, replaced by the requested hint until the player moves on. */
    public String trainingLine() {
        if (activeLesson == null) return "";
        if (!lessonSpeech.isBlank()) return lessonSpeech;
        if (lessonSolved) return Lang.get("lune.gui.tasks.step_complete_watch_signal_travel_then");
        String[] lines = {
                Lang.get("lune.gui.tasks.take_time_follow_wires_see_where_each"),
                Lang.get("lune.gui.tasks.try_tracing_one_branch_from_source_where"),
                Lang.get("lune.gui.tasks.send_preview_pulse_whenever_want_inspect"),
                Lang.get("lune.gui.tasks.success_fail_different_paths_preview"),
                Lang.get("lune.gui.tasks.experiments_welcome_here_undo_lets_try"),
                Lang.get("lune.gui.tasks.look_card_whose_job_missing_from_task"),
                Lang.get("lune.gui.tasks.time_limit_we_work_through_pace"),
                Lang.get("lune.gui.tasks.experiment_safely_task_only_training"),
                Lang.get("lune.gui.tasks.card_needs_connection_take_part_look"),
                Lang.get("lune.gui.tasks.follow_one_wire_time_do_need_solve_whole")
        };
        return lines[(lessonTicks / (20 * 18)) % lines.length];
    }

    private void outcomeButtonLabel() {
        outcomeButton.setMessage(Component.literal(Lang.get(previewFailure ? "lune.gui.tasks.path_fail" : "lune.gui.tasks.path_success")));
    }

    public boolean trainingHelpVisible() {
        return activeLesson != null && helpExpanded;
    }

    public void showHelp() {
        if (activeLesson != null) {
            helpExpanded = true;
            lessonSpeech = Lang.get("lune.gui.tasks.give_small_hint_or_tell_card_choose_when");
            syncTrainingControls();
        }
    }

    private void nextLesson() {
        if (!lessonSolved || activeLesson == null) return;
        int nextIndex = stepNumber(activeLesson);
        leavePuzzle();
        if (nextIndex < TrainingCourse.lessons().size()) startLesson(TrainingCourse.lessons().get(nextIndex));
        else if (trainingScreen != null) trainingScreen.open();
    }

    /** Opens the course map, or walks out of the puzzle already open. */
    private void onTrainingButton() {
        if (activeLesson != null) {
            leavePuzzle();
            return;
        }
        if (trainingScreen != null) {
            trainingScreen.open();
        }
    }

    /**
     * Hands the player a broken task and the three cards one of which finishes it.
     *
     * <p>The attempt is a real task in the real store, edited with the real editor. A read-only
     * sandbox would be easier to write and would teach a set of controls the player never uses
     * again; the only thing that has to be protected is the task they were already working on, and
     * that is protected by giving the lesson its own.</p>
     */
    private void startLesson(TrainingLesson lesson) {
        if (lesson == null || !TrainingProgress.isUnlocked(lesson)) {
            return;
        }
        if (activeLesson == null) {
            taskBeforeTraining = selectedTaskName;
            // A lesson is a disposable copy. Park the real editor's history before adopting it;
            // otherwise Undo in Step 4 can jump out of the puzzle and resurrect the user's old
            // While cards and END node.
            savedUndoStack = new ArrayDeque<>(undoStack);
            savedRedoStack = new ArrayDeque<>(redoStack);
            undoStack.clear();
            redoStack.clear();
            pendingHistorySnapshot = null;
            pendingHistoryTaskName = null;
            pendingHistoryIdleTicks = 0;
        }
        activeLesson = lesson;
        lessonSpeech = "";
        previewFailure = false;
        helpExpanded = false;
        hintUsed = false;
        answerUsed = false;
        answerPresses = 0;
        answerRevealActive = false;
        answerRevealTicks = 0;
        answerRevealHistoryStarted = false;
        outcomeButtonLabel();
        blueprintPanel.setTrainingSolved(false);
        blueprintPanel.stopTrainingPulse();
        lessonTicks = 0;
        lessonSolved = false;
        TaskGraph attempt = TaskStore.get().adopt(lesson.newAttempt());
        lessonTaskName = attempt.name;
        refreshTasks();
        taskList.setSelected(attempt);
        onTaskSelected(attempt);
        // Not autoLayout: the starter's positions are the puzzle. Each one leaves an empty column
        // where the answer belongs, and a grid reflow would fill it in.
        blueprintPanel.resetView();
        paletteSearch.setValue("");
        palettePanel.setPuzzleChoices(lesson.choices());
        syncTrainingControls();
        if (area != null && area.width() > 0) {
            layout(area);
        }
        recordEdit();
        message = Lang.get("lune.gui.tasks.step_2") + stepNumber(lesson) + ": " + lesson.about();
    }

    /**
     * Ends training and takes the scratch task away with it.
     *
     * <p>The attempt is never left behind. It looks exactly like a task the player wrote, sits in
     * the list beside the ones they did write, and is the whole reason training has to be a mode
     * rather than a task with a prefix on its name.</p>
     */
    private void leavePuzzle() {
        boolean wasSolved = lessonSolved;
        activeLesson = null;
        lessonSpeech = "";
        helpExpanded = false;
        hintUsed = false;
        answerUsed = false;
        answerPresses = 0;
        answerRevealActive = false;
        answerRevealTicks = 0;
        answerRevealHistoryStarted = false;
        blueprintPanel.setTrainingSolved(false);
        blueprintPanel.stopTrainingPulse();
        lessonSolved = false;
        lessonTicks = 0;
        palettePanel.setPuzzleChoices(null);
        discardAttempt();
        restoreTaskBeforeTraining();
        // Refresh the baseline before putting the user's history back. This makes a subsequent
        // edit behave like the training detour never happened.
        recordEdit();
        if (savedUndoStack != null) {
            undoStack.clear();
            undoStack.addAll(savedUndoStack);
            savedUndoStack = null;
        }
        if (savedRedoStack != null) {
            redoStack.clear();
            redoStack.addAll(savedRedoStack);
            savedRedoStack = null;
        }
        syncTrainingControls();
        if (area != null && area.width() > 0) {
            layout(area);
        }
        message = wasSolved
                ? Lang.get("lune.gui.tasks.back_tasks_practice_task_scratch_has")
                : Lang.get("lune.gui.tasks.left_training_nothing_added_task_list");
    }

    private void discardAttempt() {
        if (lessonTaskName != null) {
            TaskStore store = TaskStore.get();
            store.byName(lessonTaskName).ifPresent(store::remove);
            lessonTaskName = null;
        }
    }

    private void restoreTaskBeforeTraining() {
        refreshTasks();
        TaskGraph previous = taskBeforeTraining == null
                ? null : TaskStore.get().byName(taskBeforeTraining).orElse(null);
        taskBeforeTraining = null;
        if (previous == null) {
            restoreTaskSelection();
            return;
        }
        taskList.setSelected(previous);
        onTaskSelected(previous);
    }

    public void showHint() {
        if (activeLesson != null) {
            helpExpanded = true;
            hintUsed = true;
            lessonSpeech = activeLesson.hint();
            syncTrainingControls();
        }
    }

    /** Rebuilds only the current scratch lesson, leaving the user's task and history parked. */
    private void restartLesson() {
        if (activeLesson == null) {
            return;
        }
        TrainingLesson lesson = activeLesson;
        undoStack.clear();
        redoStack.clear();
        pendingHistorySnapshot = null;
        pendingHistoryTaskName = null;
        pendingHistoryIdleTicks = 0;
        answerRevealActive = false;
        answerRevealHistoryStarted = false;
        startLesson(lesson);
        message = Lang.get("lune.gui.tasks.restarted_step_practice_copy_fresh", stepNumber(lesson));
    }

    public void showAnswer() {
        if (activeLesson != null && !answerRevealActive && !lessonSolved) {
            helpExpanded = true;
            answerUsed = true;
            answerPresses = Math.min(5, answerPresses + 1);
            if (answerPresses >= 5) {
                answerRevealActive = true;
                answerRevealTicks = 0;
                answerRevealHistoryStarted = false;
                lessonSpeech = Lang.get("lune.gui.tasks.ill_place_answer_one_part_time_watch");
            } else {
                lessonSpeech = Lang.get("lune.gui.tasks.answer_request_5_ask_again_when_want_me", answerPresses);
            }
            syncTrainingControls();
        }
    }

    /** The name a card wears in the palette; the general nodes are not in the command registry. */
    private static String cardName(String commandId) {
        CommandDef def = CommandRegistry.byId(commandId);
        if (def != null) {
            return def.name();
        }
        return switch (commandId) {
            case TaskNode.START_COMMAND -> Lang.get("lune.gui.tasks.start");
            case TaskNode.ALWAYS_COMMAND -> Lang.get("lune.gui.tasks.always");
            case TaskNode.PULSE_COMMAND -> Lang.get("lune.gui.tasks.pulse");
            case TaskNode.SIGNAL_RELAY_COMMAND -> Lang.get("lune.gui.tasks.signal_relay");
            case TaskNode.BUTTON_COMMAND -> Lang.get("lune.gui.tasks.button");
            case TaskNode.TIMER_COMMAND -> Lang.get("lune.gui.tasks.timer");
            case TaskNode.COUNTER_COMMAND -> Lang.get("lune.gui.tasks.counter");
            case TaskNode.END_COMMAND -> Lang.get("lune.gui.tasks.end");
            default -> commandId;
        };
    }

    private int stepNumber(TrainingLesson lesson) {
        return TrainingCourse.lessons().indexOf(lesson) + 1;
    }

    /**
     * The task the current lesson was handed to, but only while it is the one on screen.
     *
     * <p>Selecting another task mid-puzzle must not have that task marked - or criticised - against
     * a lesson it has nothing to do with.</p>
     */
    private TaskGraph lessonTask() {
        TaskGraph selected = taskList.getSelected();
        return selected != null && selected.name.equals(lessonTaskName) ? selected : null;
    }

    /**
     * Marks a lesson cleared the moment its task is whole.
     *
     * <p>Checked every tick rather than behind a "check my answer" button. The audit is already
     * computed for the canvas, and a puzzle that only tells you at the end is a puzzle you solve by
     * guessing; one that reacts as you wire teaches which wire was the one that mattered.</p>
     */
    private void checkLesson() {
        if (activeLesson == null) {
            return;
        }
        lessonTicks++;
        TaskGraph attempt = lessonTask();
        boolean solvedNow = attempt != null && activeLesson.isSolvedBy(attempt);
        if (!solvedNow) {
            if (lessonSolved) {
                lessonSolved = false;
                lessonSpeech = "";
                blueprintPanel.setTrainingSolved(false);
                blueprintPanel.stopTrainingPulse();
                palettePanel.setPuzzleChoices(activeLesson.choices());
            }
            return;
        }
        if (lessonSolved) return;
        boolean firstTime = !TrainingProgress.isComplete(activeLesson);
        TrainingProgress.markComplete(activeLesson);
        // Training mode stays on. Ending it here would drop the practice task straight into the
        // task list, and the reward for finishing a task is being allowed to run it.
        lessonSolved = true;
        lessonSpeech = "";
        blueprintPanel.setTrainingSolved(true);
        blueprintPanel.sendTrainingPulse(false);
        palettePanel.setPuzzleChoices(null);
        message = Lang.get("lune.gui.tasks.solved_step", stepNumber(activeLesson), activeLesson.title(), (firstTime ? Lang.get("lune.gui.tasks.watch_pulse_then_choose_next_step") : Lang.get("lune.gui.tasks.already_cleared_press_finish_when_done")));
    }

    private void onPaletteSearch(String value) {
        palettePanel.setFilter(value);
    }

    private void openBlockPicker(Param.BlockSet param) {
        if (area.width() > 0 && area.height() > 0) {
            blockPicker.setPosition(area.left(), area.top());
            blockPicker.setSize(area.width(), area.height());
        } else {
            int w = Math.max(280, Screens.current(Minecraft.getInstance()).width * 4 / 5);
            int h = Math.max(200, Screens.current(Minecraft.getInstance()).height * 4 / 5);
            blockPicker.setPosition((Screens.current(Minecraft.getInstance()).width - w) / 2, (Screens.current(Minecraft.getInstance()).height - h) / 2);
            blockPicker.setSize(w, h);
        }
        blockPicker.open(param, param::set);
    }

    private void openItemPicker(Param.ItemChoice param) {
        if (inventoryPicker == null) {
            param.cycle();
            return;
        }
        int w = Math.clamp(area.width() * 3 / 4, 220, 420);
        int h = Math.clamp(area.height() * 3 / 4, 160, 320);
        inventoryPicker.setPosition(area.left() + (area.width() - w) / 2,
                area.top() + (area.height() - h) / 2);
        inventoryPicker.setSize(w, h);
        // The escape hatch keeps every registered item reachable: a backpack that stores its
        // contents somewhere Lune cannot read contributes nothing to the grid, and what you want
        // to craft is usually not something you are already carrying.
        inventoryPicker.open(param, param::set, () -> {
            if (recipePicker == null) {
                param.cycle();
            } else {
                recipePicker.openItem(param.get(), param::set);
            }
        });
    }

    /** Opens the typing prompt for a parameter whose value is a name nothing can offer as a list. */
    /**
     * Opens whichever panel a choice named, and falls back to cycling if that panel is not there.
     *
     * <p>Cycling a thousand sounds one click at a time is no use, but it is a great deal better
     * than a parameter that cannot be changed at all - which is what a missing picker would
     * otherwise mean on a screen that has one.</p>
     */
    private void openChoicePicker(Param.Choice param) {
        if (soundPicker == null || !Param.Choice.SOUND_PICKER.equals(param.pickerId())) {
            param.cycle();
            return;
        }
        int w = Math.clamp(area.width() * 3 / 4, 260, 460);
        int h = Math.clamp(area.height() * 3 / 4, 180, 340);
        soundPicker.setPosition(area.left() + (area.width() - w) / 2,
                area.top() + (area.height() - h) / 2);
        soundPicker.setSize(w, h);
        soundPicker.open(param, chosen -> {
            param.set(chosen);
            stepParams.refresh();
        });
    }

    private void openNamePrompt(Param.Text param) {
        if (namePrompt == null) {
            return;
        }
        CommandDef editing = stepParams.getCommand();
        String title = (editing == null ? "" : editing.name() + " - ") + param.label();
        namePrompt.open(title, param.hint(), param.get(), param.maxLength(), typed -> {
            param.set(typed);
            stepParams.refresh();
        });
    }

    /**
     * Opens the recipe editor. It lays itself out against the whole screen, like the block picker,
     * because a crafting grid beside a full item catalog does not fit in a parameter pane.
     */
    private void openRecipePicker(Param.Recipe param) {
        if (recipePicker == null) {
            return;
        }
        recipePicker.open(param.get(), recipe -> {
            param.set(recipe);
            // Which rows the card shows depends on the answer - a drawing decides its own grid
            // size, so it is not asked about a table - and the panel only rebuilds when told to.
            stepParams.refresh();
        });
    }

    private void createTask() {
        beginEdit();
        TaskGraph created = TaskStore.get().create(Lang.get("lune.gui.tasks.new_task"));
        refreshTasks();
        taskList.setSelected(created);
        onTaskSelected(created);
        recordEdit();
    }

    private void deleteTask() {
        TaskGraph selected = taskList.getSelected();
        if (selected == null) {
            return;
        }
        if (!confirmingDelete) {
            confirmingDelete = true;
            deleteButton.setMessage(Component.literal(Lang.get("lune.gui.tasks.sure")));
            message = Lang.get("lune.gui.tasks.click_delete_again_confirm");
            return;
        }
        beginEdit();
        TaskStore.get().remove(selected);
        if (selected.name.equals(lastOpenedTaskName)) {
            lastOpenedTaskName = null;
            BotConfig.get().lastOpenedTask = "";
            BotConfig.get().save();
        }
        refreshTasks();
        setTask(null);
        setSelectedStep(null);
        nameBox.setValue("");
        selectedTaskName = null;
        recordEdit();
        resetDeleteConfirmation();
        message = Lang.get("lune.gui.tasks.deleted") + selected.displayName();
    }

    private void exportTask() {
        TaskGraph selected = taskList.getSelected();
        message = TaskStore.get().exportToClipboard(selected)
                ? Lang.get("lune.gui.tasks.copied_clipboard", selected.displayName())
                : Lang.get("lune.gui.tasks.select_task_first");
    }

    private void importTask() {
        beginEdit();
        Optional<TaskGraph> imported = TaskStore.get().importFromClipboard();
        message = imported
                .map(task -> Lang.get("lune.gui.tasks.imported") + task.displayName() + "'")
                .orElse(Lang.get("lune.gui.tasks.clipboard_did_contain_task"));
        if (imported.isPresent()) {
            TaskGraph task = imported.get();
            taskList.setSelected(task);
            onTaskSelected(task);
        }
        refreshTasks();
        recordEdit();
    }

    /** True when the bot is running the task currently selected in the list. */
    private boolean selectedTaskIsRunning() {
        TaskGraph selected = taskList.getSelected();
        return selected != null && BotEngine.get().getCurrent() instanceof TaskRunner running
                && running.currentTask() == selected;
    }

    /**
     * Runs the selected task, or stops it if it is the one already running.
     *
     * <p>The button used to say Run whatever the bot was doing, so the only way to stop a task
     * from the screen that started it was to leave and find the keybind. One button that reports
     * the state it is in can also be the one that changes it.</p>
     */
    private void runTask() {
        if (inTraining()) {
            blueprintPanel.sendTrainingPulse(previewFailure);
            message = Lang.get("lune.gui.tasks.wiring_preview_outcomes_one_pass_timer", (previewFailure ? Lang.get("lune.gui.blueprint.fail") : Lang.get("lune.gui.blueprint.success")));
            return;
        }
        if (selectedTaskIsRunning()) {
            BotEngine.get().stopAll();
            message = Lang.get("lune.engine.stopped");
            syncRunButton();
            return;
        }
        TaskGraph selected = taskList.getSelected();
        if (selected == null || selected.nodes.isEmpty()) {
            message = Lang.get("lune.gui.tasks.nothing_run");
            return;
        }
        TaskStore.get().save();
        BotEngine.get().runNow(new TaskRunner(selected));
        if (BotConfig.get().closePanelOnRun) {
            Screens.open(Minecraft.getInstance(), null);
        } else {
            message = Lang.get("lune.gui.tasks.running_panel_stays_open", selected.displayName());
            syncRunButton();
        }
    }

    private void syncRunButton() {
        if (inTraining()) {
            runButton.setMessage(Component.literal(Lang.get("lune.gui.tasks.send_pulse")));
            runButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(Lang.get("lune.gui.tasks.preview_one_pulse_through_selected_path"))));
            return;
        }
        boolean running = selectedTaskIsRunning();
        runButton.setMessage(Component.literal(Lang.get(running ? "lune.gui.tasks.stop" : "lune.gui.tasks.run")));
        runButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.literal(Lang.get(running ? "lune.gui.tasks.stop_tip"
                : BotConfig.get().closePanelOnRun ? "lune.gui.tasks.run_tip_close"
                : "lune.gui.tasks.run_tip_keep"))));
    }

    private void onRename(String value) {
        TaskGraph selected = taskList.getSelected();
        if (selected != null && !value.isBlank() && !value.equals(selected.displayName())) {
            selected.name = value;
            // Named by the player now, so it stops following the language. Showing them a title
            // they did not write, over the one they just typed, would be the wrong answer to
            // "what is this task called".
            selected.seededId = null;
            lastOpenedTaskName = value;
            BotConfig.get().lastOpenedTask = value;
            BotConfig.get().save();
            refreshTasks();
            taskList.setSelected(selected);
        }
    }

    private void onTaskSelected(TaskGraph task) {
        if (task != null) {
            lastOpenedTaskName = task.name;
            BotConfig.get().lastOpenedTask = task.name;
            BotConfig.get().save();
        }
        selectedTaskName = task != null ? task.name : null;
        resetDeleteConfirmation();
        setTask(task);
        setSelectedStep(null);
        if (task != null && !task.displayName().equals(nameBox.getValue())) {
            nameBox.setValue(task.displayName());
            // setValue parks the cursor at the end, which scrolls a name longer than the box out
            // of view and leaves its tail showing - "land Cleanup" for "Woodland Cleanup". Nobody
            // reading a name wants to read the end of it first; typing still starts wherever the
            // player clicks.
            nameBox.moveCursorToStart(false);
        }
    }

    /** Selects a task when another tab asks to inspect or rename it. */
    public void openTask(TaskGraph task, boolean focusName) {
        if (task == null) {
            return;
        }
        taskList.setSelected(task);
        onTaskSelected(task);
        if (focusName) {
            nameBox.setFocused(true);
            nameBox.setCursorPosition(nameBox.getValue().length());
        }
    }

    private void refreshTasks() {
        List<TaskGraph> available = TaskStore.get().all();
        taskList.setItems(available);
        knownTaskList = available.stream().map(task -> task.name).toList().toString();
    }

    private void restoreTaskSelection() {
        TaskGraph selected = lastOpenedTaskName == null
                ? null
                : TaskStore.get().byName(lastOpenedTaskName).orElse(null);
        if (selected == null && !TaskStore.get().all().isEmpty()) {
            selected = TaskStore.get().all().get(0);
        }
        taskList.setSelected(selected);
        onTaskSelected(selected);
    }

    // --- step level ----------------------------------------------------------

    /** A tile dropped on the canvas lands where it was released. */
    private boolean onPaletteDrop(PalettePanel.Item item, int screenX, int screenY) {
        if (!blueprintPanel.acceptsDropAt(screenX, screenY)) {
            return false;
        }
        addFromPalette(item, screenX, screenY);
        return true;
    }

    private void onPaletteSelect(PalettePanel.Item item) {
        addFromPalette(item, null, null);
    }

    private void addFromPalette(PalettePanel.Item item, Integer screenX, Integer screenY) {
        TaskGraph task = taskList.getSelected();
        if (task == null) {
            message = Lang.get("lune.gui.tasks.create_task_first");
            return;
        }

        int index = task.nodes.size();
        TaskNode selectedStep = selectedStep();
        if (selectedStep != null) {
            int selectedIndex = task.indexOf(selectedStep);
            if (selectedIndex >= 0) {
                index = selectedIndex + 1;
            }
        }

        beginEdit();
        if (item instanceof PalettePanel.Blueprint blueprint) {
            List<TaskNode> copies = new java.util.ArrayList<>();
            for (TaskNode node : blueprint.nodes()) {
                TaskNode copy = node.copy();
                // Palette insertion creates independent nodes. Connections are always made by
                // an explicit pin gesture in Blueprint view.
                copy.onSuccess = null;
                copy.onFailure = null;
                copy.onWhile = null;
                if (copy.alwaysTargets != null) {
                    copy.alwaysTargets.clear();
                }
                if (copy.inputLinks != null) {
                    copy.inputLinks.clear();
                }
                copies.add(copy);
            }
            blueprintPanel.placeNewNodesAt(copies, screenX, screenY);
            task.nodes.addAll(index, copies);
            if (!copies.isEmpty()) {
                setSelectedStep(copies.get(0));
                onStepSelected(copies.get(0));
            }
            message = Lang.get("lune.gui.tasks.added_steps_from", copies.size(), blueprint.name());
        } else if (item instanceof PalettePanel.General general) {
            TaskNode node = new TaskNode(general.id());
            if (node.isPulseSourceNode()) {
                // A clock with no rate is a clock that never ticks, so a new one arrives usable.
                node.alwaysIntervalSeconds = TaskNode.DEFAULT_PULSE_INTERVAL_SECONDS;
            }
            blueprintPanel.placeNewNodesAt(List.of(node), screenX, screenY);
            task.nodes.add(index, node);
            setSelectedStep(node);
            onStepSelected(node);
            message = Lang.get("lune.gui.tasks.added") + general.name() + Lang.get("lune.gui.tasks.node");
        } else if (item instanceof PalettePanel.Command cmd) {
            CommandDef def = CommandRegistry.byId(cmd.id());
            if (def == null) {
                return;
            }
            TaskNode node = new TaskNode(def.id());
            node.params.putAll(def.snapshot());
            blueprintPanel.placeNewNodesAt(List.of(node), screenX, screenY);
            task.nodes.add(index, node);
            setSelectedStep(node);
            onStepSelected(node);
        }

        recordEdit();
        TaskStore.get().save();
        setTask(task);
    }

    private void removeStep() {
        List<TaskNode> steps = blueprintPanel.getSelectedNodes();
        removeSteps(steps);
    }

    private void removeSteps(List<TaskNode> steps) {
        TaskGraph task = taskList.getSelected();
        if (task != null && !steps.isEmpty()) {
            beginEdit();
            List<TaskNode> removed = steps.stream()
                    .filter(task.nodes::contains)
                    .toList();
            java.util.Set<String> removedIds = removed.stream()
                    .map(step -> step.id)
                    .collect(java.util.stream.Collectors.toSet());
            task.nodes.removeAll(removed);
            if (task.cableAnchors != null) {
                task.cableAnchors.keySet().removeIf(key -> removedIds.stream()
                        .anyMatch(id -> TaskCableAnchor.referencesNode(key, id)));
            }
            for (TaskNode node : task.nodes) {
                if (removed.stream().anyMatch(step -> step.id.equals(node.onSuccess))) {
                    node.onSuccess = null;
                }
                if (removed.stream().anyMatch(step -> step.id.equals(node.onFailure))) {
                    node.onFailure = null;
                }
                if (removed.stream().anyMatch(step -> step.id.equals(node.onWhile))) {
                    node.onWhile = null;
                }
                if (node.alwaysTargets != null) {
                    node.alwaysTargets.removeIf(removedIds::contains);
                }
                if (node.inputLinks != null) {
                    node.inputLinks.entrySet().removeIf(entry -> removed.stream().anyMatch(step ->
                            entry.getValue() != null && step.id.equals(entry.getValue().sourceNodeId)));
                }
                if (node.alwaysTargetInputPorts != null) {
                    node.alwaysTargetInputPorts.keySet().removeIf(removedIds::contains);
                }
                if (node.signalLinks != null) {
                    node.signalLinks.removeIf(link -> link == null || removedIds.contains(link.targetNodeId));
                }
            }
            if (removed.stream().anyMatch(step -> step.id.equals(task.onWhile))) {
                task.onWhile = null;
            }
            // A frame naming cards that no longer exist is a frame around nothing, so it goes with
            // them - and one that still holds cards simply shrinks to fit what is left.
            TaskGroup.prune(task);
            setSelectedStep(null);
            recordEdit();
            TaskStore.get().save();
            setTask(task);
            message = removed.size() == 1 ? Lang.get("lune.gui.tasks.step_deleted") : Lang.get("lune.gui.tasks.deleted_steps", removed.size());
        }
    }

    private void moveStep(int delta) {
        TaskGraph task = taskList.getSelected();
        List<TaskNode> steps = selectedNodesForAction();
        if (task != null && !steps.isEmpty()) {
            beginEdit();
            List<TaskNode> ordered = task.nodes.stream()
                    .filter(steps::contains)
                    .toList();
            if (delta < 0) {
                for (TaskNode step : ordered) {
                    task.move(step, delta);
                }
            } else {
                for (int i = ordered.size() - 1; i >= 0; i--) {
                    task.move(ordered.get(i), delta);
                }
            }
            recordEdit();
            TaskStore.get().save();
            setTask(task);
            setSelectedStep(steps.get(0));
        }
    }

    /**
     * Applies a repeat typed into the box.
     *
     * <p>Ignores an empty box and anything out of range rather than fighting the user mid-keystroke:
     * "1" on the way to "100" must not run the step once and save that.
     */
    private void applyTypedRepeat(String text) {
        if (syncingRepeatBox || text.isBlank()) {
            return;
        }
        int typed;
        try {
            typed = Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return;
        }
        if (typed < 0 || typed > MAX_TYPED_REPEAT) {
            return;
        }
        List<TaskNode> steps = selectedNodesForAction();
        steps = steps.stream().filter(step -> !isWhileCompanion(step)).toList();
        if (steps.isEmpty()) {
            return;
        }
        beginEdit();
        for (TaskNode step : steps) {
            step.repeat = typed;
        }
        recordEdit();
        TaskStore.get().save();
    }

    private void cycleRepeat() {
        List<TaskNode> steps = selectedNodesForAction();
        steps = steps.stream().filter(step -> !isWhileCompanion(step)).toList();
        if (steps.isEmpty()) {
            return;
        }
        beginEdit();
        int index = 0;
        for (int i = 0; i < REPEAT_CYCLE.length; i++) {
            if (REPEAT_CYCLE[i] == steps.get(0).repeat) {
                index = i;
                break;
            }
        }
        int nextRepeat = REPEAT_CYCLE[(index + 1) % REPEAT_CYCLE.length];
        for (TaskNode step : steps) {
            step.repeat = nextRepeat;
        }
        recordEdit();
        TaskStore.get().save();
    }

    private void applyTypedAlwaysFrequency(String text) {
        if (syncingAlwaysSecondsBox || text.isBlank()) {
            return;
        }
        int typed;
        try {
            typed = Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return;
        }
        if (typed < TaskNode.MIN_ALWAYS_INTERVAL_SECONDS
                || typed > TaskNode.MAX_ALWAYS_INTERVAL_SECONDS) {
            return;
        }
        List<TaskNode> sources = selectedNodesForAction().stream()
                .filter(TaskNode::isAlwaysNode).toList();
        if (sources.isEmpty()) {
            return;
        }
        beginEdit();
        for (TaskNode source : sources) {
            source.alwaysIntervalSeconds = typed;
        }
        recordEdit();
        TaskStore.get().save();
        syncAlwaysFrequencyControls();
    }

    private void cycleAlwaysFrequency() {
        List<TaskNode> sources = selectedNodesForAction().stream()
                .filter(TaskNode::isAlwaysNode).toList();
        if (sources.isEmpty()) {
            return;
        }
        int current = Math.clamp(sources.get(0).alwaysIntervalSeconds,
                TaskNode.MIN_ALWAYS_INTERVAL_SECONDS, TaskNode.MAX_ALWAYS_INTERVAL_SECONDS);
        int index = 0;
        for (int i = 0; i < ALWAYS_INTERVAL_CYCLE.length; i++) {
            if (ALWAYS_INTERVAL_CYCLE[i] == current) {
                index = i;
                break;
            }
        }
        int next = ALWAYS_INTERVAL_CYCLE[(index + 1) % ALWAYS_INTERVAL_CYCLE.length];
        beginEdit();
        for (TaskNode source : sources) {
            source.alwaysIntervalSeconds = next;
        }
        recordEdit();
        TaskStore.get().save();
        syncAlwaysFrequencyControls();
    }

    private List<TaskNode> selectedNodesForAction() {
        List<TaskNode> selected = blueprintPanel.getSelectedNodes();
        if (!selected.isEmpty()) {
            return selected;
        }
        TaskNode step = selectedStep();
        return step == null ? List.of() : List.of(step);
    }

    private boolean isWhileCompanion(TaskNode step) {
        return TaskWiring.isWhileTarget(taskList.getSelected(), step);
    }

    private void toggleMinimap() {
        boolean visible = blueprintPanel.toggleMinimap();
        minimapButton.setMessage(Component.literal(Lang.get(visible ? "lune.gui.tasks.map" : "lune.gui.tasks.map_off")));
        message = visible ? Lang.get("lune.gui.tasks.minimap_shown") : Lang.get("lune.gui.tasks.minimap_hidden");
    }

    private void addNote() {
        if (taskList.getSelected() == null) {
            message = Lang.get("lune.gui.tasks.pick_task_first");
            return;
        }
        blueprintPanel.addNoteAt(null, null);
    }

    /** Group, or ungroup when Shift is held - the same pair the canvas binds to Ctrl+G. */
    private void groupCards() {
        if (taskList.getSelected() == null) {
            message = Lang.get("lune.gui.tasks.pick_task_first");
            return;
        }
        if (Minecraft.getInstance().hasShiftDown()) {
            blueprintPanel.ungroupSelection();
        } else {
            blueprintPanel.groupSelection();
        }
    }

    private void toggleBreakpoint() {
        if (Minecraft.getInstance().hasShiftDown()) {
            blueprintPanel.clearBreakpoints();
        } else {
            blueprintPanel.toggleBreakpoints();
        }
    }

    private void stepTask() {
        TaskDebug.step();
        message = Lang.get(TaskDebug.holding() ? "lune.gui.tasks.stepping"
                : "lune.gui.tasks.stepping_armed");
    }

    private void resumeTask() {
        TaskDebug.resume();
        message = Lang.get("lune.gui.tasks.resumed");
    }

    /**
     * Shows the debugger's controls when there is something for them to act on.
     *
     * <p>Breakpoint appears with a card selected, because that is what it acts on. Step and
     * Continue appear while the selected task is the one running, or while a run is already being
     * held - the second case matters because a hold survives switching to another task in the
     * list, and the buttons that release it must survive with it.</p>
     */
    private void syncDebugControls() {
        boolean cardSelected = selectedStep() != null;
        boolean debugging = selectedTaskIsRunning() || TaskDebug.holding()
                || TaskDebug.waitingToBreak();
        boolean changed = breakpointButton.visible != cardSelected
                || stepButton.visible != debugging;
        breakpointButton.visible = cardSelected;
        breakpointButton.active = cardSelected;
        stepButton.visible = debugging;
        resumeButton.visible = debugging;
        stepButton.active = debugging;
        // Continue also cancels a hold that has been asked for but not yet reached, which is the
        // only way back out of an armed Step on a task that has since gone quiet.
        resumeButton.active = TaskDebug.holding() || TaskDebug.waitingToBreak();
        if (!cardSelected) {
            breakpointButton.setFocused(false);
        }
        if (!debugging) {
            stepButton.setFocused(false);
            resumeButton.setFocused(false);
        }
        findButton.setMessage(Component.literal(Lang.get(blueprintPanel.isFindOpen()
                ? "lune.gui.tasks.find_open" : "lune.gui.tasks.find")));
        if (changed && area != null && area.width() > 0) {
            layout(area);
        }
    }

    private void onStepSelected(TaskNode step) {
        if (step == null) {
            setSelectedStep(null);
            stepParams.setCommand(null);
            stepParams.setSourceDescription(null);
            stepParams.setRepeat(1);
            stepParams.setPortExposure(java.util.Set.of(), java.util.Set.of(), null);
            syncAlwaysFrequencyControls();
            syncRelayPortControls();
            syncButtonControls();
            return;
        }
        setSelectedStep(step);
        if (step.isStartNode() || step.isClockNode() || step.isButtonNode()) {
            stepParams.setCommand(null);
            stepParams.setSourceDescription(step.isStartNode()
                    ? Lang.get("lune.gui.tasks.start_sends_one_signal_connected_success")
                    : step.isClockNode()
                    ? Lang.get("lune.gui.tasks.always_sends_new_signal_each_connected", step.describeAlwaysInterval())
                    : Lang.get("lune.gui.tasks.press_control_send_one_pulse_every"));
            stepParams.setRepeat(1);
            stepParams.setPortExposure(java.util.Set.of(), java.util.Set.of(), null);
            syncAlwaysFrequencyControls();
            syncRelayPortControls();
            syncButtonControls();
            return;
        }
        if (step.isSignalRelayNode()) {
            stepParams.setCommand(null);
            stepParams.setSourceDescription(relayDescription(step));
            stepParams.setRepeat(1);
            stepParams.setPortExposure(java.util.Set.of(), java.util.Set.of(), null);
            syncAlwaysFrequencyControls();
            syncRelayPortControls();
            syncButtonControls();
            return;
        }
        CommandDef def = CommandRegistry.byId(step.commandId);
        if (def == null) {
            stepParams.setCommand(null);
            stepParams.setSourceDescription(null);
            stepParams.setRepeat(isWhileCompanion(step) ? 0 : step.repeat);
            stepParams.setPortExposure(java.util.Set.of(), java.util.Set.of(), null);
            syncAlwaysFrequencyControls();
            syncRelayPortControls();
            syncButtonControls();
            return;
        }
        def.apply(step.params);
        stepParams.setRepeat(isWhileCompanion(step) ? 0 : step.repeat);
        stepParams.setCommand(def);
        // Selecting another card of the same kind reuses the same definition, so the panel does not
        // rebuild on its own - and which rows a card shows can depend on the values just applied.
        stepParams.refresh();
        stepParams.setPortExposure(step.exposedInputs, step.exposedOutputs,
                (parameterId, side) -> togglePortExposure(step, parameterId, side));
        syncAlwaysFrequencyControls();
        syncRelayPortControls();
        syncButtonControls();
    }

    private void pressSelectedButton() {
        TaskNode step = selectedStep();
        if (step == null || !step.isButtonNode()) {
            return;
        }
        TaskRunner.pressButton(step);
        message = Lang.get("lune.gui.tasks.button_pulse_queued");
    }

    private void syncButtonControls() {
        TaskNode step = selectedStep();
        boolean visible = step != null && step.isButtonNode();
        boolean changed = buttonPressButton.visible != visible;
        buttonPressButton.visible = visible;
        buttonPressButton.active = visible;
        if (!visible) {
            buttonPressButton.setFocused(false);
        }
        if (changed && area != null && area.width() > 0) {
            layout(area);
        }
    }

    private void syncAlwaysFrequencyControls() {
        TaskNode step = selectedStep();
        boolean visible = step != null && step.isClockNode();
        boolean changed = alwaysFrequencyButton.visible != visible
                || alwaysSecondsBox.visible != visible;
        alwaysFrequencyButton.visible = visible;
        alwaysSecondsBox.visible = visible;
        alwaysFrequencyButton.active = visible;
        alwaysSecondsBox.active = visible;
        if (!visible) {
            alwaysFrequencyButton.setFocused(false);
            alwaysSecondsBox.setFocused(false);
        } else {
            int seconds = Math.clamp(step.alwaysIntervalSeconds,
                    TaskNode.MIN_ALWAYS_INTERVAL_SECONDS, TaskNode.MAX_ALWAYS_INTERVAL_SECONDS);
            alwaysFrequencyButton.setMessage(Component.literal(alwaysFrequencyLabel(seconds)));
            stepParams.setSourceDescription(Lang.get("lune.gui.tasks.always_sends_new_signal_each_connected", step.describeAlwaysInterval()));
            syncingAlwaysSecondsBox = true;
            alwaysSecondsBox.setValue(String.valueOf(seconds));
            syncingAlwaysSecondsBox = false;
        }
        if (changed && area != null && area.width() > 0) {
            layout(area);
        }
    }

    private String alwaysFrequencyLabel(int seconds) {
        return seconds == 0 ? Lang.get("lune.gui.tasks.every_tick") : Lang.get("lune.gui.tasks.every_seconds", seconds);
    }

    private String relayDescription(TaskNode node) {
        return Lang.get("lune.gui.tasks.signal_relay_has_input_output_pulse", node.signalInputCount, (node.signalInputCount == 1 ? "" : "s"), node.signalOutputCount, (node.signalOutputCount == 1 ? "" : "s"));
    }

    private void syncRelayPortControls() {
        TaskNode step = selectedStep();
        boolean visible = step != null && step.isSignalRelayNode();
        boolean changed = relayInputsButton.visible != visible
                || relayOutputsButton.visible != visible;
        relayInputsButton.visible = visible;
        relayOutputsButton.visible = visible;
        relayInputsButton.active = visible;
        relayOutputsButton.active = visible;
        if (!visible) {
            relayInputsButton.setFocused(false);
            relayOutputsButton.setFocused(false);
        } else {
            relayInputsButton.setMessage(Component.literal(Lang.get("lune.gui.tasks.inputs", step.signalInputCount)));
            relayOutputsButton.setMessage(Component.literal(Lang.get("lune.gui.tasks.outputs", step.signalOutputCount)));
        }
        if (visible) {
            stepParams.setSourceDescription(relayDescription(step));
        }
        if (changed && area != null && area.width() > 0) {
            layout(area);
        }
    }

    private void cycleRelayPorts(boolean inputs) {
        TaskGraph task = taskList.getSelected();
        TaskNode step = selectedStep();
        if (task == null || step == null || !step.isSignalRelayNode()) {
            return;
        }
        beginEdit();
        if (inputs) {
            step.signalInputCount = step.signalInputCount >= TaskNode.MAX_SIGNAL_PORTS
                    ? TaskNode.MIN_SIGNAL_PORTS : step.signalInputCount + 1;
            pruneRelayInputs(task, step);
        } else {
            step.signalOutputCount = step.signalOutputCount >= TaskNode.MAX_SIGNAL_PORTS
                    ? TaskNode.MIN_SIGNAL_PORTS : step.signalOutputCount + 1;
            if (step.signalLinks != null) {
                step.signalLinks.removeIf(link -> link == null
                        || link.outputPort >= step.signalOutputCount);
            }
        }
        TaskStore.get().save();
        setTask(task);
        onStepSelected(step);
        recordEdit();
    }

    private void pruneRelayInputs(TaskGraph task, TaskNode relay) {
        for (TaskNode source : task.nodes) {
            if (relay.id.equals(source.onSuccess)
                    && source.successInputPort >= relay.signalInputCount) {
                source.onSuccess = null;
            }
            if (relay.id.equals(source.onFailure)
                    && source.failureInputPort >= relay.signalInputCount) {
                source.onFailure = null;
            }
            if (relay.id.equals(source.onWhile)
                    && source.whileInputPort >= relay.signalInputCount) {
                source.onWhile = null;
            }
            if (source.alwaysTargetInputPorts != null
                    && source.alwaysTargetInputPorts.getOrDefault(relay.id, 0) >= relay.signalInputCount) {
                source.alwaysTargets.remove(relay.id);
                source.alwaysTargetInputPorts.remove(relay.id);
            }
            if (source.signalLinks != null) {
                source.signalLinks.removeIf(link -> link != null && relay.id.equals(link.targetNodeId)
                        && link.targetPort >= relay.signalInputCount);
            }
        }
    }

    private void togglePortExposure(TaskNode step, String parameterId, ParamPanel.PortSide side) {
        TaskGraph task = taskList.getSelected();
        if (task == null || step == null || !task.nodes.contains(step)) {
            return;
        }
        beginEdit();
        if (step.exposedInputs == null) {
            step.exposedInputs = new java.util.LinkedHashSet<>();
        }
        if (step.exposedOutputs == null) {
            step.exposedOutputs = new java.util.LinkedHashSet<>();
        }

        if (side == ParamPanel.PortSide.INPUT) {
            if (step.exposedInputs.remove(parameterId)) {
                if (step.inputLinks != null) {
                    step.inputLinks.remove(parameterId);
                }
                message = Lang.get("lune.gui.tasks.removed_data_input", parameterId);
            } else {
                step.exposedInputs.add(parameterId);
                message = Lang.get("lune.gui.tasks.added_data_input", parameterId);
            }
        } else {
            if (step.exposedOutputs.remove(parameterId)) {
                for (TaskNode target : task.nodes) {
                    if (target.inputLinks != null) {
                        target.inputLinks.entrySet().removeIf(entry -> {
                            var link = entry.getValue();
                            return link != null && step.id.equals(link.sourceNodeId)
                                    && parameterId.equals(link.sourcePort);
                        });
                    }
                }
                message = Lang.get("lune.gui.tasks.removed_data_output", parameterId);
            } else {
                step.exposedOutputs.add(parameterId);
                message = Lang.get("lune.gui.tasks.added_data_output", parameterId);
            }
        }
        TaskStore.get().save();
        setTask(task);
        onStepSelected(step);
        recordEdit();
    }

    /** Mirrors the parameter editor back into the selected step. */
    @Override
    public void tick() {
        String currentTaskList = TaskStore.get().names().toString();
        if (!currentTaskList.equals(knownTaskList)) {
            refreshTasks();
            restoreTaskSelection();
        }
        maybeRecordHistory();
        TaskNode step = selectedStep();
        CommandDef editing = stepParams.getCommand();
        if (step != null && editing != null && editing.id().equals(step.commandId)) {
            java.util.Map<String, String> snapshot = editing.snapshot();
            for (TaskNode selected : selectedNodesForAction()) {
                if (editing.id().equals(selected.commandId)) {
                    selected.params = new java.util.LinkedHashMap<>(snapshot);
                }
            }
        }
        // Both delete controls used to read "Del", and the one in the task toolbar throws the
        // whole task away. Saying how many cards this one takes is what makes the difference
        // visible at the moment of the click rather than after it.
        int selectedCount = selectedNodesForAction().size();
        removeStepButton.setMessage(Component.literal(Lang.get(selectedCount > 1 ? "lune.gui.tasks.delete_many"
                : "lune.gui.tasks.del", selectedCount)));
        removeStepButton.active = selectedCount > 0;
        removeStepButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.literal(Lang.get(selectedCount == 0 ? "lune.gui.tasks.delete_tip_none"
                        : selectedCount == 1 ? "lune.gui.tasks.delete_tip_one"
                        : "lune.gui.tasks.delete_tip_many", selectedCount))));

        syncRunButton();
        syncTrainingControls();
        repeatButton.setMessage(Component.literal(step == null ? "-" : isWhileCompanion(step) ? "x∞" : step.describeRepeat()));
        boolean repeatable = step != null && !step.isSourceNode()
                && (!step.isPulseNode() || step.isTimerNode())
                && !isWhileCompanion(step);
        repeatButton.active = repeatable;
        repeatBox.active = repeatable;
        syncingRepeatBox = true;
        repeatBox.setValue(step == null || step.repeat == 0 ? "" : String.valueOf(step.repeat));
        syncingRepeatBox = false;
        stepParams.setRepeat(step == null || isWhileCompanion(step) ? 0 : step.repeat);
        syncAlwaysFrequencyControls();
        syncRelayPortControls();
        syncButtonControls();
        syncDebugControls();
        tickAnswerReveal();
        checkLesson();
    }

    /**
     * The fifth Answer request is an actual demonstration. The missing card appears first, then
     * its connections arrive a short moment later, so the player can see what Lune changed instead
     * of watching a finished graph pop into existence.
     */
    private void tickAnswerReveal() {
        if (!answerRevealActive || activeLesson == null) {
            return;
        }
        TaskGraph attempt = lessonTask();
        if (attempt == null) {
            answerRevealActive = false;
            return;
        }
        if (!answerRevealHistoryStarted) {
            beginEdit();
            TrainingAnswer.applyCard(activeLesson, attempt);
            TaskStore.get().save();
            recordEdit();
            answerRevealHistoryStarted = true;
            lessonSpeech = Lang.get("lune.gui.tasks.placed_now_im_connecting_signal_path", cardName(activeLesson.answerCommandId()));
            return;
        }
        if (++answerRevealTicks < 10) {
            return;
        }
        TrainingAnswer.solve(activeLesson, attempt);
        TaskStore.get().save();
        recordEdit();
        answerRevealActive = false;
        lessonSpeech = Lang.get("lune.gui.tasks.answer_wired_watch_pulse_then_try");
    }

    private TaskNode selectedStep() {
        return blueprintPanel.getSelected();
    }

    private void setSelectedStep(TaskNode node) {
        blueprintPanel.setSelected(node);
    }

    private void setTask(TaskGraph task) {
        blueprintPanel.setTask(task);
    }


    private void beginEdit() {
        if (inTraining()) blueprintPanel.stopTrainingPulse();
        commitPendingHistory();
        if (lastStoreSnapshot != null) {
            undoStack.push(new HistoryEntry(lastStoreSnapshot, selectedTaskName));
            redoStack.clear();
        }
    }

    private void recordEdit() {
        pendingHistorySnapshot = null;
        pendingHistoryTaskName = null;
        pendingHistoryIdleTicks = 0;
        TaskGraph task = taskList.getSelected();
        selectedTaskName = task != null ? task.name : null;
        lastStoreSnapshot = TaskStore.get().exportAll();
    }

    private void maybeRecordHistory() {
        if (lastStoreSnapshot == null) {
            recordEdit();
            return;
        }
        String current = TaskStore.get().exportAll();
        if (!current.equals(lastStoreSnapshot)) {
            if (pendingHistorySnapshot == null) {
                pendingHistorySnapshot = lastStoreSnapshot;
                pendingHistoryTaskName = selectedTaskName;
            }
            pendingHistoryIdleTicks = 0;
            lastStoreSnapshot = current;
            TaskGraph task = taskList.getSelected();
            selectedTaskName = task != null ? task.name : null;
            return;
        }
        if (pendingHistorySnapshot != null && ++pendingHistoryIdleTicks >= 3) {
            commitPendingHistory();
        }
    }

    private void commitPendingHistory() {
        if (pendingHistorySnapshot == null) return;
        undoStack.push(new HistoryEntry(pendingHistorySnapshot, pendingHistoryTaskName));
        redoStack.clear();
        pendingHistorySnapshot = null;
        pendingHistoryTaskName = null;
        pendingHistoryIdleTicks = 0;
    }

    private void undo() {
        commitPendingHistory();
        if (undoStack.isEmpty()) {
            message = Lang.get("lune.gui.tasks.nothing_undo");
            return;
        }
        HistoryEntry entry = undoStack.pop();
        String current = TaskStore.get().exportAll();
        redoStack.push(new HistoryEntry(current, selectedTaskName));
        TaskStore.get().restoreAll(entry.snapshot());
        lastStoreSnapshot = entry.snapshot();
        selectedTaskName = entry.taskName;
        lastOpenedTaskName = selectedTaskName;
        if (selectedTaskName != null) {
            BotConfig.get().lastOpenedTask = selectedTaskName;
            BotConfig.get().save();
        }
        resetDeleteConfirmation();
        refreshTasks();
        restoreTaskSelection();
        setSelectedStep(null);
        message = Lang.get("lune.gui.tasks.undone");
    }

    private void redo() {
        commitPendingHistory();
        if (redoStack.isEmpty()) {
            message = Lang.get("lune.gui.tasks.nothing_redo");
            return;
        }
        HistoryEntry entry = redoStack.pop();
        String current = TaskStore.get().exportAll();
        undoStack.push(new HistoryEntry(current, selectedTaskName));
        TaskStore.get().restoreAll(entry.snapshot());
        lastStoreSnapshot = entry.snapshot();
        selectedTaskName = entry.taskName;
        lastOpenedTaskName = selectedTaskName;
        if (selectedTaskName != null) {
            BotConfig.get().lastOpenedTask = selectedTaskName;
            BotConfig.get().save();
        }
        resetDeleteConfirmation();
        refreshTasks();
        restoreTaskSelection();
        setSelectedStep(null);
        message = Lang.get("lune.gui.tasks.redone");
    }

    private void resetDeleteConfirmation() {
        if (confirmingDelete) {
            confirmingDelete = false;
            deleteButton.setMessage(Component.literal(Lang.get("lune.gui.tasks.delete_2")));
        }
    }

    /** Called when the panel closes, so edits survive without writing the file every tick. */
    public void save() {
        tick();
        if (inTraining()) {
            // Closing the panel mid-puzzle would otherwise write the practice task into
            // lune-tasks.json, which is the one place it must never end up.
            leavePuzzle();
        }
        TaskStore.get().save();
    }

    private void resizeLeftPane(int dx) {
        leftPaneWidth += dx;
        clampPaneWidths();
        if (area.width() > 0) {
            layout(area);
        }
    }

    private void resizeRightPane(int dx) {
        rightPaneWidth += dx;
        clampPaneWidths();
        if (area.width() > 0) {
            layout(area);
        }
    }

    private void clampPaneWidths() {
        if (area == null || area.width() <= 0) {
            leftPaneWidth = Math.max(MIN_PANE, leftPaneWidth);
            rightPaneWidth = Math.max(MIN_PANE, rightPaneWidth);
            return;
        }
        // Only the panes actually on screen get to claim room; a collapsed list claims none.
        int available = area.width() - MARGIN * 2 - MIN_CENTER;
        if (listVisible()) {
            int maxTotal = available - GAP * 2;
            leftPaneWidth = Math.clamp(leftPaneWidth, MIN_PANE, Math.max(MIN_PANE, maxTotal - MIN_PANE));
            rightPaneWidth = Math.clamp(rightPaneWidth, MIN_PANE, Math.max(MIN_PANE, maxTotal - leftPaneWidth));
        } else {
            rightPaneWidth = Math.clamp(rightPaneWidth, MIN_PANE, Math.max(MIN_PANE, available - GAP));
        }
    }

    /** True when three panes no longer fit, whatever the player's splitter drags asked for. */
    private boolean narrow() {
        return area != null && area.width() > 0 && area.width() - MARGIN * 2 < THREE_PANE_WIDTH;
    }

    private boolean listVisible() {
        return !narrow() || listExpanded;
    }

    private void toggleList() {
        listExpanded = !listExpanded;
        if (area != null && area.width() > 0) {
            layout(area);
        }
    }

    // --- layout --------------------------------------------------------------

    /**
     * Every derived measurement in the tab, in one place. The widget positions, the panel
     * backgrounds behind them and the status line all read from this, so they cannot drift apart -
     * which they previously did, each recomputing the same four expressions.
     */
    private record Frame(int left, int top, int height, int listHeight, int leftPane,
                         int flowX, int flowWidth, int flowTop, int flowHeight,
                         int rightX, int rightPane, int paletteBackHeight, int paletteTop,
                         int paletteHeight, int paramsTop, int paramsHeight, boolean listVisible) {}

    private Frame frame(ScreenRectangle area, int controlRows) {
        boolean listVisible = listVisible();
        int left = area.left() + MARGIN;
        int top = area.top() + MARGIN;
        int height = Math.max(40, area.height() - MARGIN * 2);
        int leftPane = listVisible ? leftPaneWidth : 0;
        int flowX = left + (listVisible ? leftPaneWidth + GAP : 0);
        int rightX = area.right() - MARGIN - rightPaneWidth;
        int controlsHeight = controlRows * CONTROL_ROW_H + 3;
        // The palette's backing panel is derived from the palette, never the other way round, so a
        // short screen cannot leave the widget hanging below the panel it is supposed to sit in.
        int paletteTop = top + SEARCH_H + 2;
        int paletteHeight = Math.max(40, (int) (height * 0.45) - SEARCH_H - 2);
        int paletteBackHeight = SEARCH_H + 2 + paletteHeight;
        int paramsTop = top + paletteBackHeight + GAP;
        return new Frame(left, top, height, height - BUTTON_ROW, leftPane,
                flowX, Math.max(60, rightX - flowX - GAP),
                top + controlsHeight, Math.max(40, height - controlsHeight - 2),
                rightX, rightPaneWidth, paletteBackHeight, paletteTop, paletteHeight,
                paramsTop, Math.max(40, top + height - paramsTop - 2), listVisible);
    }

    @Override
    protected void layout(ScreenRectangle area) {
        clampPaneWidths();
        boolean listVisible = listVisible();
        syncPaneWidgets(listVisible);
        tasksButton.visible = narrow();
        tasksButton.setMessage(Component.literal(Lang.get(listExpanded ? "lune.gui.tasks.hide_list" : "lune.gui.tasks.title")));

        // The strip is flowed first: how many rows it wraps onto decides where the canvas starts.
        Frame provisional = frame(area, 1);
        controlRows = flowControls(provisional.flowX(), provisional.top() + 2,
                provisional.flowWidth(), listVisible);
        Frame frame = frame(area, controlRows);

        if (listVisible) {
            layoutTaskPane(frame);
        }

        blueprintPanel.setPosition(frame.flowX(), frame.flowTop());
        blueprintPanel.setSize(frame.flowWidth(), frame.flowHeight());

        paletteSearch.setPosition(frame.rightX() + 2, frame.top() + 2);
        paletteSearch.setWidth(frame.rightPane() - 4);
        palettePanel.setPosition(frame.rightX(), frame.paletteTop());
        palettePanel.setSize(frame.rightPane(), frame.paletteHeight());
        stepParams.setPosition(frame.rightX(), frame.paramsTop());
        stepParams.setSize(frame.rightPane(), frame.paramsHeight());

        int splitWidth = VerticalSplitter.WIDTH;
        leftSplitter.setPosition(frame.flowX() - (GAP + splitWidth) / 2, frame.top());
        leftSplitter.setSize(splitWidth, frame.height());
        rightSplitter.setPosition(frame.rightX() - (GAP + splitWidth) / 2, frame.top());
        rightSplitter.setSize(splitWidth, frame.height());

        if (blockPicker.isOpen()) {
            blockPicker.setPosition(area.left(), area.top());
            blockPicker.setSize(area.width(), area.height());
        }
        if (inventoryPicker != null && inventoryPicker.isOpen()) {
            int pickerW = Math.clamp(area.width() * 3 / 4, 220, 420);
            int pickerH = Math.clamp(area.height() * 3 / 4, 160, 320);
            inventoryPicker.setPosition(area.left() + (area.width() - pickerW) / 2,
                    area.top() + (area.height() - pickerH) / 2);
            inventoryPicker.setSize(pickerW, pickerH);
        }
    }

    private void layoutTaskPane(Frame frame) {
        int left = frame.left();
        int top = frame.top();
        int rowY = top + frame.height() - BUTTON_ROW + 4;
        int fullW = Math.max(28, frame.leftPane());
        int halfW = Math.max(28, (frame.leftPane() - BUTTON_GAP) / 2);
        int tailW = Math.max(28, frame.leftPane() - halfW - BUTTON_GAP);

        // Row one is the mode switch in both modes, so the button that took you in is the button
        // that takes you out, without moving.
        trainingButton.setPosition(left, rowY);
        trainingButton.setSize(fullW, CONTROL_H);

        if (inTraining()) {
            layoutTrainingPane(rowY, left, fullW, halfW, tailW);
            return;
        }

        nameBox.setPosition(left + 2, top + 2);
        nameBox.setWidth(frame.leftPane() - 4);
        taskList.setPosition(left, top + 22);
        taskList.setSize(frame.leftPane(), Math.max(20, frame.listHeight() - 22));

        int taskButtonsY = rowY + 21;
        int topW = Math.max(28, (frame.leftPane() - BUTTON_GAP * 2) / 3);
        newButton.setPosition(left, taskButtonsY);
        newButton.setSize(topW, CONTROL_H);
        deleteButton.setPosition(left + topW + BUTTON_GAP, taskButtonsY);
        deleteButton.setSize(topW, CONTROL_H);
        runButton.setPosition(left + 2 * (topW + BUTTON_GAP), taskButtonsY);
        runButton.setSize(Math.max(28, frame.leftPane() - 2 * (topW + BUTTON_GAP)), CONTROL_H);

        int midY = taskButtonsY + 21;
        importButton.setPosition(left, midY);
        importButton.setSize(halfW, CONTROL_H);
        exportButton.setPosition(left + halfW + BUTTON_GAP, midY);
        exportButton.setSize(tailW, CONTROL_H);

        int botY = midY + 21;
        undoButton.setPosition(left, botY);
        undoButton.setSize(halfW, CONTROL_H);
        redoButton.setPosition(left + halfW + BUTTON_GAP, botY);
        redoButton.setSize(tailW, CONTROL_H);
    }

    /**
     * The same four rows, with different occupants.
     *
     * <p>Keeping the geometry identical is deliberate: the pane above it changes completely, and
     * a button block that also jumped around would make the switch read as a different screen
     * rather than the same editor in another mode.</p>
     */
    private void layoutTrainingPane(int rowY, int left, int fullW, int halfW, int tailW) {
        // Training controls live in the canvas toolbar. The brief stays focused on the lesson.
        int botY = rowY + 21;
        undoButton.setPosition(left, botY);
        undoButton.setSize(halfW, CONTROL_H);
        redoButton.setPosition(left + halfW + BUTTON_GAP, botY);
        redoButton.setSize(tailW, CONTROL_H);
    }

    /**
     * Which of the left pane's two occupants is on screen.
     *
     * <p>The pane holds the task list or the training brief, never both. That is the point: a
     * practice task looks exactly like a real one on the canvas, so the only honest place to say
     * which mode the editor is in is the pane the player reads before they touch anything.</p>
     *
     * <p>Run and Training are absent from both sets. Neither is ever hidden - when the pane
     * collapses they move into the control strip instead, because starting a task and getting into
     * the course are not things to bury behind a toggle.</p>
     */
    private void syncPaneWidgets(boolean paneVisible) {
        show(paneVisible && !inTraining(), nameBox, taskList, newButton, deleteButton,
                importButton, exportButton);
        show(paneVisible, undoButton, redoButton, leftSplitter);
    }

    private static void show(boolean visible, AbstractWidget... widgets) {
        for (AbstractWidget widget : widgets) {
            widget.visible = visible;
            if (!visible) {
                // The name box would otherwise keep the focus ring, and the keystrokes with it.
                widget.setFocused(false);
            }
        }
    }

    /** Help lives on Lune; the pane offers preview controls and gated progression. */
    private void syncTrainingControls() {
        boolean training = inTraining();
        restartButton.visible = training;
        restartButton.active = training;
        outcomeButton.visible = training;
        outcomeButton.active = training;
        helpButton.visible = training;
        helpButton.active = training;
        nextLessonButton.visible = training;
        nextLessonButton.active = training && lessonSolved;
        nextLessonButton.setMessage(Component.literal(Lang.get(activeLesson != null
                && stepNumber(activeLesson) == TrainingCourse.lessons().size()
                ? "lune.gui.tasks.course_map" : "lune.gui.tasks.next_step")));
        trainingButton.setMessage(Component.literal(Lang.get(!training ? "lune.gui.tasks.training" : "lune.gui.tasks.leave_puzzle")));
        syncRunButton();
    }

    /**
     * Lays the step controls left to right, wrapping to another row when one runs out. Returns the
     * number of rows used, which is what the canvas below has to make room for.
     */
    private int flowControls(int x, int y, int available, boolean listVisible) {
        List<AbstractWidget> controls = new java.util.ArrayList<>(List.of(removeStepButton,
                upButton, downButton, repeatButton, repeatBox, layoutButton, minimapButton));
        if (alwaysFrequencyButton.visible) {
            controls.add(5, alwaysFrequencyButton);
            controls.add(6, alwaysSecondsBox);
        }
        if (relayInputsButton.visible) {
            controls.add(relayInputsButton);
            controls.add(relayOutputsButton);
        }
        if (buttonPressButton.visible) {
            controls.add(buttonPressButton);
        }
        // The canvas tools sit after the card controls: those act on what is selected, these act
        // on the board, and keeping the two apart is what stops the strip reading as one long row
        // of unrelated buttons.
        controls.add(findButton);
        controls.add(noteButton);
        controls.add(groupButton);
        if (breakpointButton.visible) {
            controls.add(breakpointButton);
        }
        if (stepButton.visible) {
            controls.add(stepButton);
            controls.add(resumeButton);
        }
        if (inTraining()) {
            controls.addAll(0, List.of(runButton, restartButton, outcomeButton, helpButton,
                    nextLessonButton, trainingButton));
        } else if (!listVisible) {
            // Run lives in the task pane; with that collapsed it moves here, because starting the
            // selected task is not something to hide behind a toggle. Training comes with it, and
            // so do the preview and next-step buttons - otherwise a narrow window is a window with
            // no way into the course and no way out of a puzzle.
            List<AbstractWidget> promoted = new java.util.ArrayList<>(
                    List.of(runButton, trainingButton));
            if (outcomeButton.visible) promoted.add(outcomeButton);
            if (nextLessonButton.visible) promoted.add(nextLessonButton);
            controls.addAll(0, promoted);
        }
        if (tasksButton.visible) {
            controls.add(0, tasksButton);
        }

        int cursorX = x;
        int rows = 1;
        for (AbstractWidget control : controls) {
            if (cursorX > x && cursorX + control.getWidth() > x + available) {
                cursorX = x;
                rows++;
            }
            control.setPosition(cursorX, y + (rows - 1) * CONTROL_ROW_H);
            cursorX += control.getWidth() + CONTROL_GAP;
        }
        return rows;
    }

    @Override
    public void extractTabBackground(GuiGraphicsExtractor extractor) {
        Frame frame = frame(area, controlRows);
        if (frame.listVisible()) {
            // The 22px gap at the top is the name box's. Training has no name box - the practice
            // task is not something to rename - so the brief gets the whole pane to sit on.
            int paneTop = inTraining() ? frame.top() : frame.top() + 22;
            LuneScreen.panel(extractor, frame.left(), paneTop, frame.leftPane(),
                    Math.max(20, frame.top() + frame.listHeight() - paneTop));
        }
        LuneScreen.panel(extractor, frame.flowX(), frame.flowTop(), frame.flowWidth(),
                frame.flowHeight());
        if (inTraining()) {
            // The canvas is the same widget in both modes and a practice task looks exactly
            // like a real one on it, so the frame around it is what says this is not your task.
            extractor.outline(frame.flowX(), frame.flowTop(), frame.flowWidth(),
                    frame.flowHeight(), LuneScreen.ACCENT);
        }
        LuneScreen.panel(extractor, frame.rightX(), frame.top(), frame.rightPane(),
                frame.paletteBackHeight());
        LuneScreen.panel(extractor, frame.rightX(), frame.paramsTop(), frame.rightPane(),
                frame.paramsHeight());
    }

    @Override
    public void extractTabRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        var text = extractor.textRenderer();
        drawTrainingBrief(extractor);
        int y = area.bottom() - 12;
        // The lesson brief owns the teaching text. Keeping the answer-like critique out of this
        // single-line footer prevents it being clipped and makes the left panel the place to read
        // what the exercise is about.
        if (activeLesson != null) {
            String footer = lessonSolved
                    ? Lang.get("lune.gui.tasks.step_complete_use_next_step_continue", stepNumber(activeLesson))
                    : Lang.get("lune.gui.tasks.training_goal_shown_left_panel_follow");
            text.accept(area.left() + MARGIN, y, Component.literal(footer)
                    .withColor(lessonSolved ? 0xFF6FBF87 : LuneScreen.TEXT_DIM));
        } else if (!message.isEmpty()) {
            text.accept(area.left() + MARGIN, y, Component.literal(message).withColor(LuneScreen.ACCENT));
        } else {
            // A collapsed list is easy to miss, so the idle line says where the tasks went.
            text.accept(area.left() + MARGIN, y,
                    Component.literal(Lang.get(listVisible() ? "lune.gui.tasks.blueprint_hint"
                : "lune.gui.tasks.list_hidden_hint"))
                            .withColor(LuneScreen.TEXT_DIM));
        }

        syncRunButton();
        Task current = BotEngine.get().getCurrent();
        if (current instanceof TaskRunner task) {
            text.accept(area.left() + MARGIN, y - 13,
                    Component.literal(Lang.get("lune.gui.tasks.running", task.currentTask().name, task.describeFlow()))
                            .withColor(LuneScreen.ACCENT));
        }

        drawPaletteDragGhost(extractor);
    }

    /**
     * The training brief, drawn where the task list normally is.
     *
     * <p>This is the mode marker that matters. Everything else on the tab - the canvas, the
     * palette, the toolbar - is the ordinary editor doing ordinary things, and a player who walked
     * in halfway would have no way to tell a lesson from their own work without it.</p>
     */
    private void drawTrainingBrief(GuiGraphicsExtractor extractor) {
        if (!inTraining() || !listVisible()) {
            return;
        }
        Frame frame = frame(area, controlRows);
        var text = extractor.textRenderer();
        var font = Minecraft.getInstance().font;
        int left = frame.left() + 5;
        int width = Math.max(20, frame.leftPane() - 10);
        int y = frame.top() + 4;

        text.accept(left, y, Component.literal(Lang.get("lune.gui.tasks.training_2")).withColor(LuneScreen.ACCENT));
        y += 12;
        text.accept(left, y, Component.literal(Lang.get("lune.gui.tasks.step", stepNumber(activeLesson), TrainingCourse.lessons().size())).withColor(LuneScreen.TEXT_DIM));
        y += 14;
        text.accept(left, y, Component.literal(Lang.get("lune.gui.tasks.what_teaches"))
                .withColor(LuneScreen.ACCENT));
        y += 12;
        for (String line : wrapPlain(font, activeLesson.title(), width, 2)) {
            text.accept(left, y, Component.literal(line).withColor(LuneScreen.TEXT));
            y += 10;
        }
        y += 4;
        text.accept(left, y, Component.literal(Lang.get("lune.gui.tasks.task_2"))
                .withColor(LuneScreen.ACCENT));
        y += 12;
        for (String line : wrapPlain(font, activeLesson.about(), width, 4)) {
            text.accept(left, y, Component.literal(line).withColor(LuneScreen.TEXT_DIM));
            y += 10;
        }
        y += 8;
        String status = lessonSolved ? Lang.get("lune.gui.tasks.solved") : Lang.get("lune.gui.tasks.solved_yet");
        if (hintUsed) status += Lang.get("lune.gui.tasks.hint_used");
        if (answerUsed) status += Lang.get("lune.gui.tasks.answer_used");
        for (String line : wrapPlain(font, status, width, 2)) {
            text.accept(left, y, Component.literal(line)
                    .withColor(lessonSolved ? 0xFF6FBF87 : LuneScreen.TEXT_DIM));
            y += 10;
        }
        y += 2;
        if (!lessonSolved) {
            for (String line : wrapPlain(font,
                    helpExpanded ? Lang.get("lune.gui.tasks.lune_ready_with_hint_answer")
                            : Lang.get("lune.gui.tasks.lune_offer_help_when_ask"), width, 5)) {
                text.accept(left, y, Component.literal(line).withColor(LuneScreen.TEXT_DIM));
                y += 10;
            }
        }
    }

    /** Greedy word wrap, capped. The overflowing tail is clipped into the last line, not dropped. */
    private static List<String> wrapPlain(net.minecraft.client.gui.Font font, String value,
                                          int maxWidth, int maxLines) {
        List<String> lines = new java.util.ArrayList<>(maxLines);
        String[] words = value.split(" ");
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String candidate = current.isEmpty() ? words[i] : current + " " + words[i];
            if (font.width(candidate) <= maxWidth) {
                current.setLength(0);
                current.append(candidate);
                continue;
            }
            if (lines.size() == maxLines - 1) {
                String tail = String.join(" ", List.of(words).subList(i, words.length));
                lines.add(clipPlain(font, current.isEmpty() ? tail : current + " " + tail, maxWidth));
                return lines;
            }
            if (!current.isEmpty()) {
                lines.add(clipPlain(font, current.toString(), maxWidth));
            }
            current.setLength(0);
            current.append(words[i]);
        }
        if (!current.isEmpty() && lines.size() < maxLines) {
            lines.add(clipPlain(font, current.toString(), maxWidth));
        }
        return lines;
    }

    private static String clipPlain(net.minecraft.client.gui.Font font, String value, int maxWidth) {
        if (font.width(value) <= maxWidth) {
            return value;
        }
        String fit = font.plainSubstrByWidth(value, Math.max(0, maxWidth - font.width("...")), false);
        return fit.isEmpty() ? "..." : fit + "...";
    }

    /** A card outline under the cursor, so a dragged palette tile is visibly being carried. */
    private void drawPaletteDragGhost(GuiGraphicsExtractor extractor) {
        PalettePanel.Item dragged = palettePanel.draggingItem();
        if (dragged == null) {
            return;
        }
        String label = dragged instanceof PalettePanel.Command command ? command.name()
                : dragged instanceof PalettePanel.General general ? general.name()
                : ((PalettePanel.Blueprint) dragged).name();
        int width = Minecraft.getInstance().font.width(label) + 14;
        int left = palettePanel.dragPointerX() - width / 2;
        int top = palettePanel.dragPointerY() - 9;
        boolean overCanvas = blueprintPanel.acceptsDropAt(
                palettePanel.dragPointerX(), palettePanel.dragPointerY());
        extractor.fill(left, top, left + width, top + 18, overCanvas ? 0xD0284F7A : 0xA01D1E24);
        extractor.outline(left, top, width, 18, overCanvas ? LuneScreen.ACCENT : LuneScreen.TEXT_DIM);
        extractor.textRenderer().accept(left + 7, top + 5,
                Component.literal(label).withColor(overCanvas ? 0xFFFFFFFF : LuneScreen.TEXT_DIM));
    }
}
