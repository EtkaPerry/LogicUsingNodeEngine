package com.etka.lune.client.gui.tab;

import com.etka.lune.bot.BotEngine;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.command.CommandDef;
import com.etka.lune.bot.command.CommandRegistry;
import com.etka.lune.bot.task.TaskRunner;
import com.etka.lune.client.gui.LuneScreen;
import com.etka.lune.client.gui.LuneTab;
import com.etka.lune.bot.command.Param;
import com.etka.lune.client.gui.widget.BlockPicker;
import com.etka.lune.client.gui.widget.InventoryPicker;
import com.etka.lune.client.gui.widget.BlueprintPanel;
import com.etka.lune.client.gui.widget.ListPanel;
import com.etka.lune.client.gui.widget.PalettePanel;
import com.etka.lune.client.gui.widget.ParamPanel;
import com.etka.lune.client.gui.widget.VerticalSplitter;
import com.etka.lune.task.TaskGraph;
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
    private static final int BUTTON_ROW = 70;
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
    private final EditBox nameBox;
    private final EditBox paletteSearch;

    private final Button newButton;
    private final Button deleteButton;
    private final Button importButton;
    private final Button exportButton;
    private final Button runButton;
    private final Button undoButton;
    private final Button redoButton;

    private boolean confirmingDelete;
    private final ArrayDeque<HistoryEntry> undoStack = new ArrayDeque<>();
    private final ArrayDeque<HistoryEntry> redoStack = new ArrayDeque<>();
    private String lastStoreSnapshot;
    private String selectedTaskName;
    private String knownTaskList;

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
        super(Component.literal("Task"));

        taskList = add(new ListPanel<>(0, 0, 10, 10, TaskGraph::describe, this::onTaskSelected));
        blueprintPanel = add(new BlueprintPanel(0, 0, 10, 10, this::onStepSelected,
                () -> TaskStore.get().save(), value -> message = value, this::removeSteps));
        palettePanel = add(new PalettePanel(0, 0, 10, 10, this::onPaletteSelect, this::onPaletteDrop));
        paletteSearch = add(new EditBox(Minecraft.getInstance().font, 0, 0, 10, 16, Component.literal("Search")));
        paletteSearch.setHint(Component.literal("search nodes..."));
        paletteSearch.setMaxLength(32);
        paletteSearch.setResponder(this::onPaletteSearch);
        stepParams = add(new ParamPanel(0, 0, 10, 10));

        leftSplitter = add(new VerticalSplitter(0, 0, 10, 1, dx -> resizeLeftPane(dx)));
        rightSplitter = add(new VerticalSplitter(0, 0, 10, -1, dx -> resizeRightPane(dx)));

        nameBox = add(new EditBox(Minecraft.getInstance().font, 0, 0, 120, 16, Component.literal("Name")));
        nameBox.setHint(Component.literal("task name"));
        nameBox.setMaxLength(32);
        nameBox.setResponder(this::onRename);

        newButton = add(Button.builder(Component.literal("New"), b -> createTask()).size(42, 18).build());
        deleteButton = add(Button.builder(Component.literal("Delete"), b -> deleteTask()).size(42, 18)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.literal("Delete this whole task. To delete a card instead,"
                                + " select it and press Delete.")))
                .build());
        importButton = add(Button.builder(Component.literal("Import"), b -> importTask()).size(44, 18).build());
        exportButton = add(Button.builder(Component.literal("Export"), b -> exportTask()).size(50, 18).build());
        runButton = add(Button.builder(Component.literal("Run"), b -> runTask()).size(44, 18).build());
        undoButton = add(Button.builder(Component.literal("Undo"), b -> undo()).size(42, 18).build());
        redoButton = add(Button.builder(Component.literal("Redo"), b -> redo()).size(42, 18).build());

        removeStepButton = add(Button.builder(Component.literal("Del"), b -> removeStep()).size(46, 18).build());
        upButton = add(Button.builder(Component.literal("▲"), b -> moveStep(-1)).size(22, 18).build());
        downButton = add(Button.builder(Component.literal("▼"), b -> moveStep(1)).size(22, 18).build());
        repeatButton = add(Button.builder(Component.literal("x1"), b -> cycleRepeat()).size(34, 18).build());
        // A typed box beside the cycle, because the useful count is often not on any short list -
        // "run this 37 times" is a perfectly ordinary thing to want and cycling to it is not.
        repeatBox = add(new EditBox(Minecraft.getInstance().font, 0, 0, 34, 18,
                Component.literal("Repeat")));
        repeatBox.setMaxLength(7);
        repeatBox.setResponder(TasksTab.this::applyTypedRepeat);
        alwaysFrequencyButton = add(Button.builder(Component.literal("Every tick"),
                b -> cycleAlwaysFrequency()).size(62, 18).build());
        alwaysSecondsBox = add(new EditBox(Minecraft.getInstance().font, 0, 0, 38, 18,
                Component.literal("Seconds")));
        alwaysSecondsBox.setHint(Component.literal("sec"));
        alwaysSecondsBox.setMaxLength(4);
        alwaysSecondsBox.setResponder(TasksTab.this::applyTypedAlwaysFrequency);
        relayInputsButton = add(Button.builder(Component.literal("Inputs: 1"),
                b -> cycleRelayPorts(true)).size(64, 18).build());
        relayOutputsButton = add(Button.builder(Component.literal("Outputs: 1"),
                b -> cycleRelayPorts(false)).size(72, 18).build());
        buttonPressButton = add(Button.builder(Component.literal("Press"),
                b -> pressSelectedButton()).size(42, 18).build());
        layoutButton = add(Button.builder(Component.literal("Layout"), b -> blueprintPanel.autoLayout()).size(48, 18).build());
        minimapButton = add(Button.builder(Component.literal("Map: on"), b -> toggleMinimap()).size(58, 18).build());
        tasksButton = add(Button.builder(Component.literal("Tasks"), b -> toggleList()).size(46, 18).build());

        stepParams.setOpenBlockPicker(this::openBlockPicker);
        stepParams.setOpenItemPicker(this::openItemPicker);

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

    private void onPaletteSearch(String value) {
        palettePanel.setFilter(value);
    }

    private void openBlockPicker(Param.BlockSet param) {
        if (area.width() > 0 && area.height() > 0) {
            blockPicker.setPosition(area.left(), area.top());
            blockPicker.setSize(area.width(), area.height());
        } else {
            int w = Math.max(280, Minecraft.getInstance().screen.width * 4 / 5);
            int h = Math.max(200, Minecraft.getInstance().screen.height * 4 / 5);
            blockPicker.setPosition((Minecraft.getInstance().screen.width - w) / 2, (Minecraft.getInstance().screen.height - h) / 2);
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
        // contents somewhere Lune cannot read contributes nothing to the grid.
        inventoryPicker.open(param, param::set, param::cycle);
    }

    private void createTask() {
        beginEdit();
        TaskGraph created = TaskStore.get().create("New Task");
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
            deleteButton.setMessage(Component.literal("Sure?"));
            message = "Click Delete again to confirm";
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
        message = "Deleted " + selected.name;
    }

    private void exportTask() {
        TaskGraph selected = taskList.getSelected();
        message = TaskStore.get().exportToClipboard(selected)
                ? "Copied '" + selected.name + "' to clipboard"
                : "Select a task first";
    }

    private void importTask() {
        beginEdit();
        Optional<TaskGraph> imported = TaskStore.get().importFromClipboard();
        message = imported
                .map(task -> "Imported '" + task.name + "'")
                .orElse("Clipboard did not contain a task");
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
        if (selectedTaskIsRunning()) {
            BotEngine.get().stopAll();
            message = "Stopped";
            syncRunButton();
            return;
        }
        TaskGraph selected = taskList.getSelected();
        if (selected == null || selected.nodes.isEmpty()) {
            message = "Nothing to run";
            return;
        }
        TaskStore.get().save();
        BotEngine.get().runNow(new TaskRunner(selected));
        if (BotConfig.get().closePanelOnRun) {
            Minecraft.getInstance().setScreen(null);
        } else {
            message = "Running " + selected.name + " - the panel stays open";
            syncRunButton();
        }
    }

    private void syncRunButton() {
        boolean running = selectedTaskIsRunning();
        runButton.setMessage(Component.literal(running ? "Stop" : "Run"));
        runButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.literal(running
                        ? "Stop this task"
                        : BotConfig.get().closePanelOnRun
                        ? "Start this task and close the panel"
                        : "Start this task and keep the panel open")));
    }

    private void onRename(String value) {
        TaskGraph selected = taskList.getSelected();
        if (selected != null && !value.isBlank() && !value.equals(selected.name)) {
            selected.name = value;
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
        if (task != null && !task.name.equals(nameBox.getValue())) {
            nameBox.setValue(task.name);
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
            message = "Create a task first";
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
            message = "Added " + copies.size() + " steps from " + blueprint.name();
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
            message = "Added " + general.name() + " node";
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
            setSelectedStep(null);
            recordEdit();
            TaskStore.get().save();
            setTask(task);
            message = removed.size() == 1 ? "Step deleted" : "Deleted " + removed.size() + " steps";
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
        minimapButton.setMessage(Component.literal(visible ? "Map: on" : "Map: off"));
        message = visible ? "Minimap shown" : "Minimap hidden";
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
                    ? "START sends one signal to its connected Success target."
                    : step.isClockNode()
                    ? "Always sends a new signal to each connected target "
                            + step.describeAlwaysInterval() + ". Each target runs as its own circuit."
                    : "Press this control to send one pulse to every connected output.");
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
        message = "Button pulse queued";
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
            stepParams.setSourceDescription("Always sends a new signal to each connected target "
                    + step.describeAlwaysInterval() + ". Each target runs as its own circuit.");
            syncingAlwaysSecondsBox = true;
            alwaysSecondsBox.setValue(String.valueOf(seconds));
            syncingAlwaysSecondsBox = false;
        }
        if (changed && area != null && area.width() > 0) {
            layout(area);
        }
    }

    private String alwaysFrequencyLabel(int seconds) {
        return seconds == 0 ? "Every tick" : "Every " + seconds + "s";
    }

    private String relayDescription(TaskNode node) {
        return "Signal Relay has " + node.signalInputCount + " input"
                + (node.signalInputCount == 1 ? "" : "s") + " and " + node.signalOutputCount
                + " output" + (node.signalOutputCount == 1 ? "" : "s")
                + ". A pulse arriving at any input is forwarded through every connected output.";
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
            relayInputsButton.setMessage(Component.literal("Inputs: " + step.signalInputCount));
            relayOutputsButton.setMessage(Component.literal("Outputs: " + step.signalOutputCount));
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
                message = "Removed " + parameterId + " data input";
            } else {
                step.exposedInputs.add(parameterId);
                message = "Added " + parameterId + " data input";
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
                message = "Removed " + parameterId + " data output";
            } else {
                step.exposedOutputs.add(parameterId);
                message = "Added " + parameterId + " data output";
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
        removeStepButton.setMessage(Component.literal(
                selectedCount > 1 ? "Del " + selectedCount : "Del"));
        removeStepButton.active = selectedCount > 0;
        removeStepButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.literal(selectedCount == 0
                        ? "Select a card to delete it. Delete or Backspace does the same."
                        : selectedCount == 1
                        ? "Delete the selected card (Delete or Backspace)"
                        : "Delete the " + selectedCount + " selected cards (Delete or Backspace)")));

        syncRunButton();
        repeatButton.setMessage(Component.literal(step == null ? "-"
                : isWhileCompanion(step) ? "x∞" : step.describeRepeat()));
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
        if (lastStoreSnapshot != null) {
            undoStack.push(new HistoryEntry(lastStoreSnapshot, selectedTaskName));
            redoStack.clear();
        }
    }

    private void recordEdit() {
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
            undoStack.push(new HistoryEntry(lastStoreSnapshot, selectedTaskName));
            redoStack.clear();
            lastStoreSnapshot = current;
            TaskGraph task = taskList.getSelected();
            selectedTaskName = task != null ? task.name : null;
        }
    }

    private void undo() {
        if (undoStack.isEmpty()) {
            message = "Nothing to undo";
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
        message = "Undone";
    }

    private void redo() {
        if (redoStack.isEmpty()) {
            message = "Nothing to redo";
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
        message = "Redone";
    }

    private void resetDeleteConfirmation() {
        if (confirmingDelete) {
            confirmingDelete = false;
            deleteButton.setMessage(Component.literal("Delete"));
        }
    }

    /** Called when the panel closes, so edits survive without writing the file every tick. */
    public void save() {
        tick();
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
        setLeftPaneVisible(listVisible);
        tasksButton.visible = narrow();
        tasksButton.setMessage(Component.literal(listExpanded ? "Hide" : "Tasks"));

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
        nameBox.setPosition(left + 2, top + 2);
        nameBox.setWidth(frame.leftPane() - 4);
        taskList.setPosition(left, top + 22);
        taskList.setSize(frame.leftPane(), Math.max(20, frame.listHeight() - 22));

        int taskButtonsY = top + frame.height() - BUTTON_ROW + 4;
        int topW = Math.max(28, (frame.leftPane() - BUTTON_GAP * 2) / 3);
        newButton.setPosition(left, taskButtonsY);
        newButton.setSize(topW, CONTROL_H);
        deleteButton.setPosition(left + topW + BUTTON_GAP, taskButtonsY);
        deleteButton.setSize(topW, CONTROL_H);
        runButton.setPosition(left + 2 * (topW + BUTTON_GAP), taskButtonsY);
        runButton.setSize(Math.max(28, frame.leftPane() - 2 * (topW + BUTTON_GAP)), CONTROL_H);

        int midY = taskButtonsY + 21;
        int midW = Math.max(28, (frame.leftPane() - BUTTON_GAP) / 2);
        importButton.setPosition(left, midY);
        importButton.setSize(midW, CONTROL_H);
        exportButton.setPosition(left + midW + BUTTON_GAP, midY);
        exportButton.setSize(Math.max(28, frame.leftPane() - midW - BUTTON_GAP), CONTROL_H);

        int botY = midY + 21;
        undoButton.setPosition(left, botY);
        undoButton.setSize(midW, CONTROL_H);
        redoButton.setPosition(left + midW + BUTTON_GAP, botY);
        redoButton.setSize(Math.max(28, frame.leftPane() - midW - BUTTON_GAP), CONTROL_H);
    }

    private void setLeftPaneVisible(boolean visible) {
        for (AbstractWidget widget : List.of(nameBox, taskList, newButton, deleteButton,
                importButton, exportButton, undoButton, redoButton, leftSplitter)) {
            widget.visible = visible;
            if (!visible) {
                // The name box would otherwise keep the focus ring, and the keystrokes with it.
                widget.setFocused(false);
            }
        }
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
        if (!listVisible) {
            // Run lives in the task pane; with that collapsed it moves here, because starting the
            // selected task is not something to hide behind a toggle.
            controls.add(0, runButton);
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
            LuneScreen.panel(extractor, frame.left(), frame.top() + 22, frame.leftPane(),
                    Math.max(20, frame.listHeight() - 22));
        }
        LuneScreen.panel(extractor, frame.flowX(), frame.flowTop(), frame.flowWidth(),
                frame.flowHeight());
        LuneScreen.panel(extractor, frame.rightX(), frame.top(), frame.rightPane(),
                frame.paletteBackHeight());
        LuneScreen.panel(extractor, frame.rightX(), frame.paramsTop(), frame.rightPane(),
                frame.paramsHeight());
    }

    @Override
    public void extractTabRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        var text = extractor.textRenderer();
        int y = area.bottom() - 12;
        if (!message.isEmpty()) {
            text.accept(area.left() + MARGIN, y, Component.literal(message).withColor(LuneScreen.ACCENT));
        } else {
            // A collapsed list is easy to miss, so the idle line says where the tasks went.
            text.accept(area.left() + MARGIN, y,
                    Component.literal(listVisible()
                                    ? "Blueprint wires use the same task logic. Export copies JSON."
                                    : "Tasks is hidden - open it to pick or create another task.")
                            .withColor(LuneScreen.TEXT_DIM));
        }

        syncRunButton();
        Task current = BotEngine.get().getCurrent();
        if (current instanceof TaskRunner task) {
            text.accept(area.left() + MARGIN, y - 13,
                    Component.literal("Running " + task.currentTask().name + ": " + task.describeFlow())
                            .withColor(LuneScreen.ACCENT));
        }

        drawPaletteDragGhost(extractor);
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
