package com.etka.lune.client.gui.widget;

import com.etka.lune.Constants;
import com.etka.lune.util.Durations;
import com.etka.lune.util.Lang;
import com.etka.lune.bot.BotEngine;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.command.CommandDef;
import com.etka.lune.bot.command.CommandRegistry;
import com.etka.lune.bot.command.Param;
import com.etka.lune.bot.learning.LearningScope;
import com.etka.lune.bot.learning.LearningStore;
import com.etka.lune.bot.learning.SkillStats;
import com.etka.lune.bot.task.TaskRunner;
import com.etka.lune.client.gui.LuneScreen;
import com.etka.lune.client.gui.UiScale;
import com.etka.lune.config.BotConfig;
import com.etka.lune.config.TaskView;
import com.etka.lune.task.TaskCableAnchor;
import com.etka.lune.task.TaskCablePath;
import com.etka.lune.task.TaskCanvas;
import com.etka.lune.task.TaskCableRoute;
import com.etka.lune.task.TaskDebug;
import com.etka.lune.task.TaskGraph;
import com.etka.lune.task.TaskGroup;
import com.etka.lune.task.TaskDataLink;
import com.etka.lune.task.TaskNode;
import com.etka.lune.task.TaskNote;
import com.etka.lune.task.TaskPower;
import com.etka.lune.task.TaskSearch;
import com.etka.lune.task.TaskSignalLink;
import com.etka.lune.task.TaskWiring;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * An Unreal-style node canvas for tasks.
 *
 * <p>The canvas is deliberately only another view over {@link TaskNode}. Success and failure
 * wires write the model's existing {@code onSuccess}/{@code onFailure} fields, so the runner, old
 * JSON and the legacy list editor all continue to work exactly as before.</p>
 */
public final class BlueprintPanel extends AbstractWidget {

    private static final int NODE_W = TaskCanvas.CARD_WIDTH;
    private static final int NODE_H = TaskCanvas.CARD_HEIGHT;
    private static final int WHILE_ROW_HEIGHT = 15;
    private static final int NODE_H_WITHOUT_WHILE = NODE_H - WHILE_ROW_HEIGHT;
    private static final int HEADER_H = 16;
    private static final int GRID = 20;
    private static final int PIN_RADIUS = 6;
    private static final int STEP_X = 154;
    private static final int LAYOUT_COLUMNS = 6;
    private static final int LAYOUT_ROW_GAP = 118;

    private static final int GRID_COLOUR = 0x242F3038;
    private static final int NODE_BORDER = 0xFF4A4B55;
    private static final int NODE_SELECTED = LuneScreen.ACCENT_HOVER;
    private static final int NODE_ACTIVE = 0xFF76FF9F;
    private static final int NODE_ACTIVE_GLOW = 0x5058FF88;
    private static final int NODE_FAILED = 0xFFE15B64;
    private static final int NODE_FAILED_GLOW = 0x50E15B64;
    private static final int HEADER_FAILED = 0xFF8B3A3A;
    private static final int DATA_START_Y = 76;
    private static final int DATA_ROW_HEIGHT = 16;
    private static final int DATA_FOOTER_GAP = 18;
    private static final int MINIMAP_WIDTH = 164;
    private static final int MINIMAP_HEIGHT = 104;
    private static final int MINIMAP_MARGIN = 8;
    private static final int MINIMAP_HEADER = 16;
    private static final int MINIMAP_PADDING = 6;

    /** How close to the rim a dragged card starts pulling the view after it. */
    private static final int EDGE_PAN_MARGIN = 36;
    private static final int EDGE_PAN_MAX = 14;

    private static final int FIND_BAR_H = 20;
    private static final int FIND_BAR_W = 280;
    /** Everything the find bar did not match is pushed back behind this, rather than hidden. */
    private static final int FIND_DIM = 0x99101014;
    private static final int FIND_MATCH = 0xFFFFD54A;
    private static final int BREAKPOINT = 0xFFE0413F;
    private static final int BREAKPOINT_DISARMED = 0xFF6B4040;
    private static final int HELD_GLOW = 0x60FFD54A;
    private static final int NOTE_TEXT_INSET = 5;
    /** How far a paste lands from the cards it was copied from, so the copy is visibly a copy. */
    private static final int PASTE_OFFSET = 24;

    private static final int LIVE_WIRE_SPARK = 0xFFFFFFFF;

    private static final int WIRE_SUCCESS = 0;
    private static final int WIRE_FAILURE = 1;
    private static final int WIRE_WHILE = 2;
    private static final int WIRE_DATA = 3;
    private static final int WIRE_SIGNAL = 4;
    private static final float MIN_ZOOM = 0.50f;
    private static final float MAX_ZOOM = 2.00f;
    private static final float ZOOM_STEP = 1.10f;

    private final Consumer<TaskNode> onSelect;
    private final Runnable onChanged;
    private final Consumer<String> onMessage;
    private final Consumer<List<TaskNode>> onDelete;

    private TaskGraph task;
    private TaskNode selected;
    private final Set<TaskNode> selectedNodes = new LinkedHashSet<>();
    private TaskNode dragged;
    private TaskNode wireSource;
    private int wireType;
    private String wireDataPort;
    private int wireSignalPort = -1;
    /** The cable currently being rerouted by a left-drag, if any. */
    private String draggedCableKey;
    private TaskCableRoute draggedCableBase;
    private TaskCableRoute draggedCablePreview;
    /** Point-list position matching the exact cable segment grabbed for this pull. */
    private int draggedCableInsertIndex;
    /** Existing white handle being moved; -1 means the pull is adding a new point. */
    private int draggedCablePointIndex = -1;
    private int cableDragStartX;
    private int cableDragStartY;
    private boolean cableMoved;
    private boolean moved;
    private boolean panning;
    /**
     * True from a middle-button press on the canvas until that button comes up. Separate from
     * {@link #panning}, which belongs to a left drag on empty canvas: a middle press used to set
     * that flag and nothing cleared it, so the next left drag - wherever it started - panned once.
     */
    private boolean middlePanning;
    /**
     * The cable a middle click picked out, by its {@link TaskCableAnchor} key, or null. It is drawn
     * last and shining, with every other cable faded behind it, until the same cable or empty
     * canvas is middle-clicked again or Esc is pressed. On a busy canvas this is how a player
     * follows one line to wherever it goes.
     */
    private String tracedCable;
    /** Where the traced cable was drawn this frame; rebuilt by every wire pass. */
    private TracedWire tracedWire;
    /** What the middle press under way landed on, so a click can be told from the start of a pan. */
    private String middlePressCable;
    private boolean middlePressWasTraced;
    private double middlePressX;
    private double middlePressY;
    private boolean middleMoved;
    /**
     * Movement smaller than a pixel, carried to the next drag event rather than rounded away.
     * Rounding each event on its own meant a slow drag moved the view by nothing at all.
     */
    private double panCarryX;
    private double panCarryY;
    private boolean minimapDragging;
    private boolean selecting;
    private boolean selectionAdditive;
    private boolean selectionMoved;
    private int selectionStartX;
    private int selectionStartY;
    private int panX;
    private int panY;
    private float zoom = 1.0f;
    private boolean minimapVisible = true;
    private int pointerX;
    private int pointerY;
    private TaskPower power = TaskPower.NONE;
    // Pin colours are a setting, not a constant: they carry meaning, and which colours convey it
    // depends on who is looking. Sampled once a frame so one canvas never mixes two palettes.
    private int pinExec = 0xFFF2F2F2;
    private int pinSuccess;
    private int pinFailure;
    private int pinWhile;
    private int pinSignal;
    private int pinObserve;
    private int pinData;

    // --- find ---------------------------------------------------------------
    /** The find bar, its answer, and which answer the view is currently sitting on. */
    private boolean searchOpen;
    private String searchQuery = "";
    private List<TaskSearch.Hit> searchHits = List.of();
    private int searchIndex;

    // --- notes and frames ---------------------------------------------------
    private TaskNote selectedNote;
    private TaskNote draggedNote;
    private boolean resizingNote;
    /** The note whose text is being typed into. Owns the keyboard while it is set. */
    private TaskNote editingNote;
    private TaskGroup draggedGroup;
    /** The frame whose title is being typed into. */
    private TaskGroup editingGroup;
    private String titleBuffer = "";

    /**
     * Cards cut or copied from a canvas, waiting to be pasted onto one.
     *
     * <p>Static, so a chunk of one task can be pasted into another: switching task replaces the
     * panel's graph, and a clipboard living on the instance would be emptied by the act of
     * navigating to the place the player wanted to paste it. In memory rather than on the system
     * clipboard, because the system clipboard already carries whole tasks for Import and Export,
     * and quietly overwriting somebody's exported task with three cards would be a poor trade.</p>
     */
    private static final List<TaskNode> CLIPBOARD = new ArrayList<>();

    public BlueprintPanel(int x, int y, int width, int height, Consumer<TaskNode> onSelect,
                          Runnable onChanged, Consumer<String> onMessage,
                          Consumer<List<TaskNode>> onDelete) {
        super(x, y, width, height, Component.literal(Lang.get("lune.gui.blueprint.task_blueprint_canvas")));
        this.onSelect = onSelect;
        this.onChanged = onChanged;
        this.onMessage = onMessage;
        this.onDelete = onDelete;
    }

    private final java.util.Map<String, TaskCableRoute> automaticCableRoutes = new java.util.LinkedHashMap<>();
    private com.etka.lune.training.TrainingPreview trainingPreview;
    private final java.util.Map<String, Integer> previewCounters = new java.util.LinkedHashMap<>();
    private long previewStarted;
    private boolean previewFailure;
    private boolean trainingSolved;
    private double previewSpark = -1;

    public void setTrainingSolved(boolean solved) {
        trainingSolved = solved;
    }

    public void sendTrainingPulse(boolean failure) {
        trainingPreview = com.etka.lune.training.TrainingPreview.trace(task, failure, previewCounters);
        previewStarted = System.currentTimeMillis();
        previewFailure = failure;
    }

    public void stopTrainingPulse() {
        trainingPreview = null;
        previewCounters.clear();
        previewSpark = -1;
    }

    private boolean liveWire(TaskNode from, int kind, int port, TaskNode to) {
        previewSpark = trainingPreview == null ? power.wireProgress(from, kind, port, to) : trainingPreview.wireProgress(
                from, kind, port, to, System.currentTimeMillis() - previewStarted);
        return trainingPreview == null ? power.isLiveWire(from, kind, port, to) : previewSpark >= 0;
    }

    public void setTask(TaskGraph task) {
        if (this.task != task) stopTrainingPulse();
        if (this.task != task) {
            // A key names a cable in the task it came from; in another task it names nothing.
            clearTrace();
        }
        this.task = task;
        dragged = null;
        wireSource = null;
        wireDataPort = null;
        wireSignalPort = -1;
        draggedCableKey = null;
        draggedCableBase = null;
        draggedCablePreview = null;
        draggedCableInsertIndex = 0;
        draggedCablePointIndex = -1;
        cableMoved = false;
        panning = false;
        middlePanning = false;
        minimapDragging = false;
        selecting = false;
        selectedNote = null;
        draggedNote = null;
        resizingNote = false;
        editingNote = null;
        draggedGroup = null;
        editingGroup = null;
        // A query is about the task that was open. Carrying it over would offer the player
        // yesterday's answer to today's question, numbered "1 of 7" and pointing at nothing.
        searchHits = List.of();
        searchIndex = 0;
        closeContextMenu();
        selectedNodes.removeIf(node -> task == null || !task.nodes.contains(node));
        if (selected != null && !selectedNodes.contains(selected)) {
            selected = null;
        }
        if (selected == null && !selectedNodes.isEmpty()) {
            selected = selectedNodes.iterator().next();
        }
        boolean changed = ensurePositions();
        if (needsReadableGridLayout()) {
            applyGridLayout();
            changed = true;
        }
        if (searchOpen) {
            // The bar stays up across a task switch, so it has to answer about the task that is
            // now on screen rather than dim this one against the last one's matches.
            refreshFind();
        }
        if (changed) {
            onChanged.run();
        }
    }

    /** The task on the canvas, or null when none is open. */
    public TaskGraph getTask() {
        return task;
    }

    /** Where the camera is right now, to be handed back to {@link #showView} later. */
    public TaskView view() {
        return new TaskView(panX, panY, zoom);
    }

    /**
     * Puts the camera where a remembered view left it, or home - as Ctrl+0 does - when there is
     * no view to go back to.
     */
    public void showView(TaskView view) {
        if (view == null) {
            resetView();
            return;
        }
        panX = view.panX;
        panY = view.panY;
        // The view comes out of a file a player can edit, and a zoom that is not a number would
        // take every card on the canvas with it.
        zoom = Math.clamp(Float.isFinite(view.zoom) ? view.zoom : 1.0f, MIN_ZOOM, MAX_ZOOM);
    }

    public TaskNode getSelected() {
        return selected;
    }

    public void setSelected(TaskNode node) {
        if (node == null) {
            selected = null;
            selectedNodes.clear();
        } else if (!selectedNodes.contains(node)) {
            selectedNodes.clear();
            selectedNodes.add(node);
            selected = node;
        } else {
            selected = node;
        }
    }

    public List<TaskNode> getSelectedNodes() {
        return List.copyOf(selectedNodes);
    }

    public boolean toggleMinimap() {
        minimapVisible = !minimapVisible;
        minimapDragging = false;
        return minimapVisible;
    }

    /** Places freshly inserted palette nodes near the selected node instead of on top of it. */
    /** True when this screen point is over the canvas, so a dropped palette tile belongs here. */
    public boolean acceptsDropAt(int screenX, int screenY) {
        return screenX >= getX() && screenX < getX() + getWidth()
                && screenY >= getY() && screenY < getY() + getHeight()
                && !(minimapVisible && minimapContains(screenX, screenY));
    }

    /**
     * Places cards at a screen point, or in the middle of the view when none is given.
     *
     * <p>The old placement walked to the right of the last card, which on a task of any size put
     * the new card somewhere off the edge of the view - added, but nowhere the player could see.
     * Landing it where they are looking is the whole point of adding it.</p>
     */
    public void placeNewNodesAt(List<TaskNode> nodes, Integer screenX, Integer screenY) {
        if (nodes.isEmpty()) {
            return;
        }
        int x = screenX == null
                ? canvasX(getX() + getWidth() / 2.0) - NODE_W / 2
                : canvasX(screenX) - NODE_W / 2;
        int y = screenY == null
                ? canvasY(getY() + getHeight() / 2.0) - NODE_H / 2
                : canvasY(screenY) - NODE_H / 2;
        for (TaskNode node : nodes) {
            node.editorX = x;
            node.editorY = y;
            x += STEP_X;
        }
        nudgeApart(nodes);
    }

    /** Keeps a dropped card off one already sitting there, so it cannot be lost underneath it. */
    private void nudgeApart(List<TaskNode> placed) {
        if (task == null) {
            return;
        }
        for (TaskNode node : placed) {
            int guard = 0;
            while (guard++ < 64 && overlapsExisting(node, placed)) {
                node.editorX += 16;
                node.editorY += 16;
            }
        }
    }

    private boolean overlapsExisting(TaskNode node, List<TaskNode> placed) {
        for (TaskNode other : task.nodes) {
            if (other == node || placed.contains(other)
                    || other.editorX == null || other.editorY == null) {
                continue;
            }
            if (Math.abs(other.editorX - node.editorX) < 12
                    && Math.abs(other.editorY - node.editorY) < 12) {
                return true;
            }
        }
        return false;
    }

    public void placeNewNodes(List<TaskNode> nodes, TaskNode after) {
        if (nodes.isEmpty()) {
            return;
        }
        int x = 24;
        int y = 26;
        if (after != null && after.editorX != null && after.editorY != null) {
            x = after.editorX + STEP_X;
            y = after.editorY;
            // Palette insertion also changes the implicit execution order. Make room on the same
            // row so the visual order continues to match that fall-through order.
            if (task != null) {
                int shift = nodes.size() * STEP_X;
                for (TaskNode existing : task.nodes) {
                    if (existing != after && existing.editorX != null && existing.editorX >= x
                            && existing.editorY != null && Math.abs(existing.editorY - y) < NODE_H) {
                        existing.editorX += shift;
                    }
                }
            }
        } else if (task != null) {
            for (TaskNode node : task.nodes) {
                if (node.editorX != null) {
                    x = Math.max(x, node.editorX + STEP_X);
                }
            }
        }
        for (TaskNode node : nodes) {
            node.editorX = x;
            node.editorY = y;
            x += STEP_X;
        }
    }

    /** Restores a readable 2D layout without changing execution order or edges. */
    public void autoLayout() {
        if (task == null) {
            return;
        }
        applyGridLayout();
        resetView();
        onChanged.run();
        onMessage.accept(Lang.get("lune.gui.blueprint.blueprint_layout_reset_into_2d_grid_task"));
    }

    /**
     * Puts the camera back at the origin without moving a single card.
     *
     * <p>Separate from {@link #autoLayout()} because the two are usually wanted apart: a graph laid
     * out deliberately - a seeded job, a training puzzle whose empty column is the question - wants
     * the view brought back to it, and would be destroyed by the grid.</p>
     */
    public void resetView() {
        panX = 0;
        panY = 0;
        zoom = 1.0f;
    }

    private void applyGridLayout() {
        for (int i = 0; i < task.nodes.size(); i++) {
            TaskNode node = task.nodes.get(i);
            int column = i % LAYOUT_COLUMNS;
            int row = i / LAYOUT_COLUMNS;
            node.editorX = 24 + column * STEP_X;
            node.editorY = 26 + row * LAYOUT_ROW_GAP;
        }
    }

    private boolean ensurePositions() {
        if (task == null) {
            return false;
        }
        boolean changed = false;
        for (int i = 0; i < task.nodes.size(); i++) {
            TaskNode node = task.nodes.get(i);
            if (node.editorX == null || node.editorY == null) {
                int column = i % LAYOUT_COLUMNS;
                int row = i / LAYOUT_COLUMNS;
                node.editorX = 24 + column * STEP_X;
                node.editorY = 26 + row * LAYOUT_ROW_GAP;
                changed = true;
            }
        }
        return changed;
    }

    /** Recognises the old persisted layout: many cards, one Y coordinate, and one long row. */
    private boolean needsReadableGridLayout() {
        if (task == null || task.nodes.size() <= LAYOUT_COLUMNS) {
            return false;
        }
        Integer firstY = task.nodes.get(0).editorY;
        if (firstY == null) {
            return false;
        }
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        for (TaskNode node : task.nodes) {
            if (node.editorX == null || node.editorY == null
                    || Math.abs(node.editorY - firstY) > 4) {
                return false;
            }
            minX = Math.min(minX, node.editorX);
            maxX = Math.max(maxX, node.editorX);
        }
        return maxX - minX > STEP_X * (LAYOUT_COLUMNS - 1);
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                            float partialTick) {
        pointerX = mouseX;
        pointerY = mouseY;
        // Sampled once per frame: every card and every wire is asking the same running task the
        // same question, and the answer must not change halfway down the canvas.
        power = readLivePower();
        NodePalette.Pins pins = NodePalette.pins();
        pinExec = pins.exec();
        pinSuccess = pins.success();
        pinFailure = pins.failure();
        pinWhile = pins.whilePin();
        pinSignal = pins.signal();
        pinObserve = pins.observe();
        pinData = pins.data();
        extractor.enableScissor(getX(), getY(), getX() + getWidth(), getY() + getHeight());
        // Keep the marquee behind the cards so selected-card borders and labels stay readable.
        if (selecting) {
            drawSelection(extractor);
        }
        var pose = extractor.pose();
        pose.pushMatrix();
        pose.translate(getX() + panX, getY() + panY);
        pose.scale(zoom, zoom);
        drawGrid(extractor);
        if (task == null) {
            extractor.textRenderer().accept(10, 9,
                    Component.literal(Lang.get("lune.gui.blueprint.pick_task_left")).withColor(LuneScreen.TEXT_DIM));
            pose.popMatrix();
            extractor.disableScissor();
            return;
        }
        if (task.nodes.isEmpty()) {
            extractor.textRenderer().accept(10, 9,
                    Component.literal(Lang.get("lune.gui.blueprint.choose_command_from_palette")).withColor(LuneScreen.TEXT_DIM));
            pose.popMatrix();
            extractor.disableScissor();
            return;
        }

        int canvasMouseX = canvasX(mouseX);
        int canvasMouseY = canvasY(mouseY);

        automaticCableRoutes.clear();
        tracedWire = null;
        // Frames and notes are the paper the graph is drawn on, so they go down first and
        // everything that carries a signal is drawn over them.
        fitGroups();
        drawGroups(extractor, canvasMouseX, canvasMouseY);
        drawNotes(extractor, canvasMouseX, canvasMouseY);
        drawLegacyAlwaysSource(extractor);

        // Only explicit edges are drawn. A null success edge still means fall-through in the
        // task model, but the Blueprint canvas must never imply a connection the user did not
        // create.
        for (TaskNode source : task.nodes) {
            if (source.isClockNode()) {
                if (source.alwaysTargets != null) {
                    for (String targetId : source.alwaysTargets) {
                        TaskNode target = task.nodeById(targetId);
                        if (target != null) {
                            drawNodeWire(extractor, source, outputX(source), whileY(source),
                                    target, inputX(target), targetInputY(target,
                                            source.alwaysTargetInputPorts == null
                                                    ? 0 : source.alwaysTargetInputPorts.getOrDefault(target.id, 0)),
                                    pinWhile,
                                    liveWire(source, TaskPower.ALWAYS, 0, target),
                                    TaskCableAnchor.key("always", source.id, target.id));
                        }
                    }
                }
            }
            TaskNode successTarget = task.nodeById(source.onSuccess);
            if (successTarget != null) {
                drawNodeWire(extractor, source, outputX(source), successY(source),
                        successTarget, inputX(successTarget),
                        targetInputY(successTarget, successTarget.isPulseNode()
                                ? source.successInputPort : -1), pinSuccess,
                        liveWire(source, TaskPower.SUCCESS, 0, successTarget),
                        TaskCableAnchor.key("success", source.id, successTarget.id));
            }
            TaskNode failureTarget = task.nodeById(source.onFailure);
            if (failureTarget != null) {
                drawNodeWire(extractor, source, outputX(source), failureY(source),
                        failureTarget, inputX(failureTarget),
                        targetInputY(failureTarget, failureTarget.isPulseNode()
                                ? source.failureInputPort : -1), pinFailure,
                        liveWire(source, TaskPower.FAILURE, 0, failureTarget),
                        TaskCableAnchor.key("failure", source.id, failureTarget.id));
            }
            if (!source.isClockNode() && source.whileVisible) {
                TaskNode whileTarget = task.nodeById(source.onWhile);
                if (whileTarget != null) {
                    drawNodeWire(extractor, source, outputX(source), whileY(source),
                            whileTarget, inputX(whileTarget),
                            targetInputY(whileTarget, whileTarget.isPulseNode()
                                    ? source.whileInputPort : -1), pinWhile,
                            liveWire(source, TaskPower.WHILE, 0, whileTarget),
                            TaskCableAnchor.key("while", source.id, whileTarget.id));
                }
            }
            if (source.isPulseNode() && source.signalLinks != null) {
                for (TaskSignalLink link : source.signalLinks) {
                    if (link == null) {
                        continue;
                    }
                    TaskNode target = task.nodeById(link.targetNodeId);
                    if (target != null) {
                        drawNodeWire(extractor, source, outputX(source),
                                signalOutputY(source, link.outputPort), target,
                                inputX(target), targetInputY(target, link.targetPort), pinSignal,
                                liveWire(source, TaskPower.SIGNAL, link.outputPort, target),
                                TaskCableAnchor.key("signal", source.id,
                                        String.valueOf(link.outputPort), target.id,
                                        String.valueOf(link.targetPort)));
                    }
                }
            }
            if (source.isObserverNode() && source.observedNodeId != null) {
                TaskNode watched = task.nodeById(source.observedNodeId);
                if (watched != null) {
                    drawNodeWire(extractor, watched, outputX(watched),
                            nodeY(watched) + HEADER_H / 2, source,
                            inputX(source), signalInputY(source, 0), pinObserve,
                            power.isLive(watched),
                            TaskCableAnchor.key("observe", watched.id, source.id));
                }
            }
            if (source.inputLinks != null) {
                for (var entry : source.inputLinks.entrySet()) {
                    TaskDataLink link = entry.getValue();
                    TaskNode dataSource = link == null ? null : task.nodeById(link.sourceNodeId);
                    int sourcePort = dataSource == null ? -1
                            : exposedOutputs(dataSource).indexOf(link.sourcePort);
                    int targetPort = exposedInputs(source).indexOf(entry.getKey());
                    if (dataSource != null && sourcePort >= 0 && targetPort >= 0) {
                        drawNodeWire(extractor, dataSource, outputX(dataSource),
                                dataOutputY(dataSource, sourcePort), source,
                                inputX(source), dataInputY(source, targetPort), pinData, false,
                                TaskCableAnchor.key("data", dataSource.id, link.sourcePort,
                                        source.id, entry.getKey()));
                    }
                }
            }
        }

        // The traced cable last of all the cables, so nothing is drawn across it. A trace whose
        // cable was not drawn this frame - cut, or folded away inside a closed frame - is over.
        if (tracedWire != null) {
            drawTracedWire(extractor);
        } else if (tracedCable != null) {
            tracedCable = null;
        }

        if (wireSource != null) {
            drawWire(extractor, outputX(wireSource), wireY(wireSource, wireType),
                    canvasX(pointerX), canvasY(pointerY), wireColour(wireType));
        }

        for (int i = task.nodes.size() - 1; i >= 0; i--) {
            TaskNode node = task.nodes.get(i);
            if (collapsedHolder(node) == null) {
                drawNode(extractor, node, canvasMouseX, canvasMouseY);
            }
        }
        drawTracedEnds(extractor);
        if (trainingPreview != null) {
            for (TaskNode node : task.nodes) {
                String note = trainingPreview.notes().get(node.id);
                if (note == null) continue;
                int x = nodeX(node);
                int y = nodeY(node) + nodeHeight(node) + 5;
                extractor.fill(x, y - 2, x + Minecraft.getInstance().font.width(note) + 8, y + 12, 0xF0182230);
                extractor.textRenderer().accept(x + 4, y + 1,
                        Component.literal(note).withColor(LuneScreen.TEXT));
            }
        }
        drawCableRoutePoints(extractor);
        if (searchOpen) {
            drawFindOverlay(extractor);
        }

        // Last, and outside the card loop: the chips sit above their card and would otherwise be
        // painted over by whichever card happens to be drawn next.
        refreshToolbarNodes(canvasMouseX, canvasMouseY);
        if (pinnedToolbarNode != null) {
            drawCardToolbar(extractor, pinnedToolbarNode, canvasMouseX, canvasMouseY);
        }
        if (hoverToolbarNode != null) {
            drawCardToolbar(extractor, hoverToolbarNode, canvasMouseX, canvasMouseY);
        }
        pose.popMatrix();

        if (minimapVisible) {
            drawMinimap(extractor);
        }
        if (trainingSolved || trainingPreview != null) {
            String status = trainingSolved ? Lang.get("lune.gui.blueprint.step_complete_next_step_unlocked") : Lang.get("lune.gui.blueprint.wiring_preview");
            if (trainingPreview != null) {
                boolean ended = trainingPreview.finished(System.currentTimeMillis() - previewStarted);
                status += trainingPreview.nodes().isEmpty() ? Lang.get("lune.gui.blueprint.source_connected")
                        : ended ? Lang.get("lune.gui.blueprint.preview_ended") : "  |  " + (previewFailure ? Lang.get("lune.gui.tasks.path_fail") : Lang.get("lune.gui.tasks.path_success"));
            }
            int bannerWidth = Math.min(getWidth() - 60, Minecraft.getInstance().font.width(status) + 16);
            extractor.fill(getX() + 4, getY() + 3, getX() + 4 + bannerWidth, getY() + 22,
                    trainingSolved ? 0xF023513B : 0xF0233045);
            extractor.textRenderer().accept(getX() + 10, getY() + 9,
                    Component.literal(Minecraft.getInstance().font.plainSubstrByWidth(status, bannerWidth - 12))
                            .withColor(trainingSolved ? 0xFF9AF0B5 : LuneScreen.TEXT));
        }

        extractor.textRenderer().accept(getX() + 7, getY() + getHeight() - 11,
                Component.literal(Lang.get(draggedCableKey != null
                        ? draggedCablePointIndex >= 0 ? "lune.gui.blueprint.cable_move_hint"
                                : "lune.gui.blueprint.cable_add_hint"
                        : tracedCable != null ? "lune.gui.blueprint.cable_trace_hint"
                        : "lune.gui.blueprint.cable_idle_hint"))
                        .withColor(LuneScreen.TEXT_DIM));
        extractor.textRenderer().accept(getX() + getWidth() - 45, getY() + 7,
                Component.literal(Math.round(zoom * 100) + "%").withColor(LuneScreen.TEXT_DIM));
        drawDebugBanner(extractor);
        if (searchOpen) {
            drawFindBar(extractor);
        }
        if (contextNode != null) {
            drawContextMenu(extractor, mouseX, mouseY);
        }
        extractor.disableScissor();
    }

    /**
     * The backdrop dots.
     *
     * <p>Every dot is its own quad, so the spacing has to be measured on the screen rather than on
     * the canvas. At 20 canvas pixels and half zoom the dots land 10 screen pixels apart, which
     * quadruples the count for a backdrop nobody is looking at: a full-screen canvas was drawing
     * the better part of nine thousand quads a frame before anything else was drawn at all. Coarser
     * spacing as the view pulls back keeps the count flat and the texture identical.</p>
     */
    private void drawGrid(GuiGraphicsExtractor extractor) {
        int step = GRID;
        while (step * zoom < GRID * 0.75F) {
            step *= 2;
        }
        int minX = (int) Math.floor(-panX / zoom) - step;
        int minY = (int) Math.floor(-panY / zoom) - step;
        int maxX = (int) Math.ceil((getWidth() - panX) / zoom) + step;
        int maxY = (int) Math.ceil((getHeight() - panY) / zoom) + step;
        int firstX = Math.floorDiv(minX, step) * step;
        int firstY = Math.floorDiv(minY, step) * step;
        for (int x = firstX; x <= maxX; x += step) {
            for (int y = firstY; y <= maxY; y += step) {
                extractor.fill(x, y, x + 1, y + 1, GRID_COLOUR);
            }
        }
    }

    // --- frames, notes, find and the debugger -------------------------------

    private static final int GROUP_BTN = 12;
    private static final int NOTE_BTN = 10;

    /** Re-measures every frame around its cards, and forgets the ones left holding nothing. */
    private void fitGroups() {
        if (task == null || task.groups == null) {
            return;
        }
        task.groups.removeIf(group -> group == null || !group.fit(task, this::nodeHeight));
    }

    /** The closed frame hiding this card, or null when the card is on screen. */
    private TaskGroup collapsedHolder(TaskNode node) {
        TaskGroup group = TaskGroup.holding(task, node);
        return group != null && group.collapsed ? group : null;
    }

    /**
     * Whether this canvas rectangle overlaps the part of the board the player can see.
     *
     * <p>The same cull {@link #drawNode} does, and for the same reason: a note is a dozen fills and
     * a paragraph of wrapping, and a task with thirty of them scrolled off the edge should cost
     * nothing to look away from.</p>
     */
    private boolean onScreen(double left, double top, double right, double bottom) {
        return right >= -panX / zoom && left <= (getWidth() - panX) / zoom
                && bottom >= -panY / zoom && top <= (getHeight() - panY) / zoom;
    }

    private void drawGroups(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
        if (task == null || task.groups == null) {
            return;
        }
        var text = extractor.textRenderer();
        for (TaskGroup group : task.groups) {
            int headerTop = group.y - TaskGroup.HEADER_HEIGHT;
            if (!onScreen(group.x, headerTop, group.right(), group.bottom())) {
                continue;
            }
            NodePalette.Paper paper = NodePalette.paper(group.colour);
            boolean hovered = group.contains(mouseX, mouseY);
            if (!group.collapsed) {
                // A wash rather than a fill: the cards inside have to stay the brightest thing in
                // their own frame, or grouping would bury the graph it is supposed to organise.
                extractor.fill(group.x, group.y, group.right(), group.bottom(), 0x18000000 | (paper.body() & 0xFFFFFF));
                extractor.outline(group.x, group.y, group.right() - group.x,
                        group.bottom() - group.y, paper.border());
            }
            extractor.fill(group.x, headerTop, group.right(), group.y, paper.body());
            extractor.fill(group.x, headerTop, group.x + 3, group.y, paper.border());
            String title = group.displayTitle().isBlank()
                    ? Lang.get("lune.gui.blueprint.group_untitled") : group.displayTitle();
            if (editingGroup == group) {
                title = titleBuffer + "_";
            }
            int titleRoom = Math.max(8, group.right() - group.x - 10 - GROUP_BTN * 3);
            text.accept(group.x + 6, headerTop + 3,
                    Component.literal(clip(title, titleRoom)).withColor(paper.text()));
            String badge = group.collapsed
                    ? Lang.get("lune.gui.blueprint.group_cards", group.members.size()) : "";
            if (!badge.isEmpty()) {
                text.accept(group.x + 10 + Minecraft.getInstance().font.width(clip(title, titleRoom)),
                        headerTop + 3, Component.literal(badge).withColor(LuneScreen.TEXT_DIM));
            }
            for (int button = 0; button < 3; button++) {
                int buttonX = groupButtonX(group, button);
                boolean over = hovered && mouseY >= headerTop && mouseY < group.y
                        && mouseX >= buttonX && mouseX < buttonX + GROUP_BTN;
                if (over) {
                    extractor.fill(buttonX, headerTop + 1, buttonX + GROUP_BTN - 1, group.y - 1,
                            0x40FFFFFF);
                }
                String glyph = switch (button) {
                    case 0 -> group.collapsed ? "▸" : "▾";
                    case 1 -> "◆";
                    default -> "✕";
                };
                text.accept(buttonX + 3, headerTop + 3, Component.literal(glyph)
                        .withColor(button == 2 && over ? CONTEXT_DANGER : paper.text()));
            }
        }
    }

    private int groupButtonX(TaskGroup group, int index) {
        return group.right() - 4 - (3 - index) * GROUP_BTN;
    }

    /** Which header button the pointer is on: 0 collapse, 1 colour, 2 ungroup, -1 none. */
    private int groupButtonAt(TaskGroup group, int x, int y) {
        if (y < group.y - TaskGroup.HEADER_HEIGHT || y >= group.y) {
            return -1;
        }
        for (int button = 0; button < 3; button++) {
            int buttonX = groupButtonX(group, button);
            if (x >= buttonX && x < buttonX + GROUP_BTN) {
                return button;
            }
        }
        return -1;
    }

    private void drawNotes(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
        if (task == null || task.notes == null) {
            return;
        }
        var text = extractor.textRenderer();
        for (TaskNote note : task.notes) {
            note.clampSize();
            if (!onScreen(note.x, note.y, note.right(), note.bottom())) {
                continue;
            }
            NodePalette.Paper paper = NodePalette.paper(note.colour);
            boolean hovered = note.contains(mouseX, mouseY);
            extractor.fill(note.x, note.y, note.right(), note.bottom(), paper.body());
            extractor.outline(note.x, note.y, note.right() - note.x, note.bottom() - note.y,
                    selectedNote == note ? NODE_SELECTED : paper.border());
            // The coloured spine doubles as the drag handle, so a note with text right up to the
            // edge still has somewhere to be picked up by.
            extractor.fill(note.x + 1, note.y + 1, note.x + 4, note.bottom() - 1, paper.border());
            String body = editingNote == note ? note.text + "_" : note.displayText();
            if (body.isEmpty()) {
                body = Lang.get("lune.gui.blueprint.note_empty");
            }
            int room = note.right() - note.x - NOTE_TEXT_INSET - 7;
            int line = note.y + 4;
            for (String wrapped : wrapLines(body, room)) {
                if (line + 9 > note.bottom() - 2) {
                    break;
                }
                text.accept(note.x + NOTE_TEXT_INSET + 2, line,
                        Component.literal(wrapped).withColor(note.displayText().isEmpty() && editingNote != note
                                ? LuneScreen.TEXT_DIM : paper.text()));
                line += 9;
            }
            if (hovered || selectedNote == note) {
                // The grip and the two controls only appear under the pointer: a wall of notes
                // covered in permanent buttons is a wall of buttons with some notes behind it.
                extractor.fill(note.right() - 7, note.bottom() - 7, note.right() - 1,
                        note.bottom() - 1, paper.border());
                for (int button = 0; button < 2; button++) {
                    int buttonX = noteButtonX(note, button);
                    boolean over = mouseX >= buttonX && mouseX < buttonX + NOTE_BTN
                            && mouseY >= note.y + 2 && mouseY < note.y + 2 + NOTE_BTN;
                    if (over) {
                        extractor.fill(buttonX, note.y + 2, buttonX + NOTE_BTN,
                                note.y + 2 + NOTE_BTN, 0x40FFFFFF);
                    }
                    text.accept(buttonX + 1, note.y + 3,
                            Component.literal(button == 0 ? "◆" : "✕")
                                    .withColor(button == 1 && over ? CONTEXT_DANGER
                                            : paper.border()));
                }
            }
        }
    }

    private int noteButtonX(TaskNote note, int index) {
        return note.right() - 3 - (2 - index) * NOTE_BTN;
    }

    /** Which note control the pointer is on: 0 colour, 1 delete, -1 none. */
    private int noteButtonAt(TaskNote note, int x, int y) {
        if (y < note.y + 2 || y >= note.y + 2 + NOTE_BTN) {
            return -1;
        }
        for (int button = 0; button < 2; button++) {
            int buttonX = noteButtonX(note, button);
            if (x >= buttonX && x < buttonX + NOTE_BTN) {
                return button;
            }
        }
        return -1;
    }

    /**
     * Draws one wire, following either end into the bar of the frame that is hiding it.
     *
     * <p>A wire with both ends inside the same closed frame is internal to it and is not drawn:
     * the point of closing a frame is to stop looking at what is inside it, and a bundle of cables
     * looping out of a bar and back into it says nothing that the bar did not already say.</p>
     */
    private void drawNodeWire(GuiGraphicsExtractor extractor, TaskNode from, int fromX, int fromY,
                              TaskNode to, int toX, int toY, int colour, boolean live,
                              String key) {
        TaskCableRoute route = anchorFor(key);
        TaskGroup fromHidden = collapsedHolder(from);
        TaskGroup toHidden = collapsedHolder(to);
        if (fromHidden != null && fromHidden == toHidden) {
            // drawWire consumes the spark the liveWire call above just armed; skipping it here
            // would leave that spark to be drawn on whichever wire is rendered next.
            previewSpark = -1;
            return;
        }
        if (fromHidden != null) {
            fromX = fromHidden.right();
            fromY = fromHidden.y - TaskGroup.HEADER_HEIGHT / 2;
            route = null;
        }
        if (toHidden != null) {
            toX = toHidden.x;
            toY = toHidden.y - TaskGroup.HEADER_HEIGHT / 2;
            route = null;
        }
        if (tracedCable != null && tracedCable.equals(key)) {
            // Held back and drawn after every other cable, shining. Its own sparks stand in for
            // the live one, so the spark armed for it is spent here rather than on the next wire.
            tracedWire = new TracedWire(from, to, fromX, fromY, toX, toY, colour, route);
            previewSpark = -1;
            return;
        }
        drawWire(extractor, fromX, fromY, toX, toY, colour, live, route, tracedCable != null);
    }

    // --- tracing one cable ---------------------------------------------------------------------

    /** How far a middle press may wander and still be a click rather than the start of a pan. */
    private static final int CLICK_SLOP = 3;
    /** The dark edge every cable is drawn over, so crossing cables stay apart. */
    private static final int WIRE_OUTLINE = 0xFF10151E;
    /** What faded cables and the glow are mixed toward: the dark of the canvas behind them. */
    private static final int CANVAS_SHADE = 0xFF15151C;
    /** One spark for every this many canvas pixels of a traced cable. */
    private static final double SPARK_SPACING = 90.0;
    /** How fast a traced cable's sparks run, in canvas pixels a second, whatever its length. */
    private static final double SPARK_SPEED = 140.0;

    /** A traced cable as it was drawn this frame: its two cards, its ends, colour and route. */
    private record TracedWire(TaskNode from, TaskNode to, int fromX, int fromY, int toX, int toY,
                              int colour, TaskCableRoute route) {}

    /** The key of the traced cable, or null. Package-private for the tests. */
    String tracedCable() {
        return tracedCable;
    }

    /**
     * A middle press on a cable picks that cable out, and still starts a pan like any other middle
     * press - so a player can pick a cable and drag the view along it to wherever it goes.
     *
     * <p>A card sits on top of the cables that run under it, and the minimap on top of the canvas,
     * so a press on either is only a pan, never the trace of a line nobody can see there.</p>
     */
    private void beginMiddlePress(double screenX, double screenY) {
        middlePressX = screenX;
        middlePressY = screenY;
        middleMoved = false;
        int x = canvasX(screenX);
        int y = canvasY(screenY);
        boolean covered = minimapVisible && minimapContains(screenX, screenY)
                || nodeAt(x, y) != null;
        CableHit hit = covered ? null : cableAt(x, y);
        middlePressCable = hit == null ? null : hit.key();
        middlePressWasTraced = middlePressCable != null && middlePressCable.equals(tracedCable);
        if (middlePressCable != null && !middlePressWasTraced) {
            traceCable(middlePressCable);
        }
    }

    /**
     * A middle click - pressed and let go without panning - lets go of the trace when it landed on
     * empty canvas or on the cable already shining. A pan leaves the trace as it was, which is
     * what makes following a long cable across the canvas possible.
     */
    private void endMiddlePress() {
        if (!middleMoved && (middlePressCable == null || middlePressWasTraced)) {
            clearTrace();
        }
        middlePressCable = null;
    }

    private void traceCable(String key) {
        tracedCable = key;
        tracedWire = null;
        TaskNode[] ends = cableEnds(key);
        if (ends != null) {
            onMessage.accept(Lang.get("lune.gui.blueprint.cable_traced",
                    nodeName(ends[0]), nodeName(ends[1])));
        }
    }

    private void clearTrace() {
        tracedCable = null;
        tracedWire = null;
    }

    /** The two cards a cable joins, read off its key the way {@link #anchorFor} reads it. */
    private TaskNode[] cableEnds(String key) {
        if (task == null || key == null) {
            return null;
        }
        String[] parts = key.split("\\|", -1);
        if (parts.length != 3 && parts.length != 5) {
            return null;
        }
        TaskNode from = task.nodeById(parts[1]);
        TaskNode to = task.nodeById(parts[parts.length == 3 ? 2 : 3]);
        return from == null || to == null ? null : new TaskNode[]{from, to};
    }

    /**
     * The traced cable, shining: a glow that breathes, the cable itself a shade brighter than it is
     * normally drawn, and sparks running from its source to its target so the direction reads at a
     * glance. Every other cable is faded while this one is traced, so it is the line that stands
     * out however many cross it.
     *
     * <p>Faded cables and the glow are mixed toward the canvas rather than drawn see-through: a
     * cable is drawn as a chain of short strokes that overlap at every joint, and translucent
     * strokes would bead wherever two of them meet.</p>
     */
    private void drawTracedWire(GuiGraphicsExtractor extractor) {
        TracedWire wire = tracedWire;
        if (!wireCouldBeVisible(wire.fromX(), wire.fromY(), wire.toX(), wire.toY(), wire.route())) {
            return;
        }
        TaskCablePath path = TaskCablePath.of(wire.fromX(), wire.fromY(), wire.toX(), wire.toY(),
                wire.route());
        int samples = Math.clamp((int) Math.ceil(path.length() * zoom / 6), 12, 256);
        int thickness = Math.max(2, (int) Math.ceil(1.5 / zoom));
        long now = System.currentTimeMillis();
        double breath = 0.5 + 0.5 * Math.sin(now / 240.0);
        int glow = mix(wire.colour(), CANVAS_SHADE, 0.62 - 0.22 * breath);
        int core = mix(wire.colour(), 0xFFFFFFFF, 0.35);

        TaskCablePath.Point previous = path.at(0);
        for (int i = 1; i <= samples; i++) {
            TaskCablePath.Point point = path.at(i / (double) samples);
            cableStroke(extractor, previous, point, thickness + 8, glow);
            previous = point;
        }
        previous = path.at(0);
        for (int i = 1; i <= samples; i++) {
            TaskCablePath.Point point = path.at(i / (double) samples);
            cableStroke(extractor, previous, point, thickness + 3, WIRE_OUTLINE);
            cableStroke(extractor, previous, point, thickness + 1, core);
            previous = point;
        }

        // Sparks spaced by distance and moving at one pace, so a long cable does not look slower
        // than a short one.
        double length = Math.max(1.0, path.length());
        int sparks = Math.max(2, (int) (length / SPARK_SPACING));
        double travelled = (now / 1000.0 * SPARK_SPEED / length) % 1.0;
        for (int i = 0; i < sparks; i++) {
            TaskCablePath.Point point = path.at((travelled + i / (double) sparks) % 1.0);
            int px = (int) Math.round(point.x());
            int py = (int) Math.round(point.y());
            extractor.fill(px - 3, py - 3, px + 4, py + 4, core);
            extractor.fill(px - 1, py - 1, px + 2, py + 2, LIVE_WIRE_SPARK);
        }
    }

    /** A ring round each card the traced cable joins, so both of its ends can be found at once. */
    private void drawTracedEnds(GuiGraphicsExtractor extractor) {
        TracedWire wire = tracedWire;
        if (wire == null) {
            return;
        }
        int core = mix(wire.colour(), 0xFFFFFFFF, 0.35);
        int glow = mix(wire.colour(), CANVAS_SHADE, 0.5);
        for (TaskNode end : List.of(wire.from(), wire.to())) {
            if (collapsedHolder(end) != null) {
                continue;
            }
            int x = nodeX(end);
            int y = nodeY(end);
            int height = nodeHeight(end);
            extractor.outline(x - 3, y - 3, NODE_W + 6, height + 6, glow);
            extractor.outline(x - 2, y - 2, NODE_W + 4, height + 4, core);
        }
    }

    /** {@code colour} carried {@code amount} of the way to {@code toward}, channel by channel. */
    private static int mix(int colour, int toward, double amount) {
        double t = Math.clamp(amount, 0.0, 1.0);
        int result = 0xFF000000;
        for (int shift = 0; shift <= 16; shift += 8) {
            int from = (colour >> shift) & 0xFF;
            int to = (toward >> shift) & 0xFF;
            result |= ((int) Math.round(from + (to - from) * t) & 0xFF) << shift;
        }
        return result;
    }

    private boolean isFindHit(String id) {
        for (TaskSearch.Hit hit : searchHits) {
            if (hit.id().equals(id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Pushes everything the query did not match into the background.
     *
     * <p>Dimming rather than hiding. A card found in isolation is a card without the two either
     * side of it, and "where is it" is nearly always asked in order to then ask "and what is it
     * wired to".</p>
     */
    private void drawFindOverlay(GuiGraphicsExtractor extractor) {
        if (task == null || searchQuery.isBlank()) {
            return;
        }
        String current = currentHitId();
        for (TaskNode node : task.nodes) {
            if (collapsedHolder(node) != null) {
                continue;
            }
            int x = nodeX(node);
            int y = nodeY(node);
            int height = nodeHeight(node);
            if (!isFindHit(node.id)) {
                extractor.fill(x, y, x + NODE_W, y + height, FIND_DIM);
            } else {
                extractor.outline(x - 2, y - 2, NODE_W + 4, height + 4,
                        node.id.equals(current) ? FIND_MATCH : 0x90FFD54A);
            }
        }
        if (task.notes != null) {
            for (TaskNote note : task.notes) {
                if (!isFindHit(note.id)) {
                    extractor.fill(note.x, note.y, note.right(), note.bottom(), FIND_DIM);
                } else {
                    extractor.outline(note.x - 2, note.y - 2, note.right() - note.x + 4,
                            note.bottom() - note.y + 4,
                            note.id.equals(current) ? FIND_MATCH : 0x90FFD54A);
                }
            }
        }
        if (task.groups != null) {
            for (TaskGroup group : task.groups) {
                if (isFindHit(group.id)) {
                    extractor.outline(group.x - 2, group.y - TaskGroup.HEADER_HEIGHT - 2,
                            group.right() - group.x + 4,
                            group.bottom() - group.y + TaskGroup.HEADER_HEIGHT + 4,
                            group.id.equals(current) ? FIND_MATCH : 0x90FFD54A);
                }
            }
        }
    }

    private void drawFindBar(GuiGraphicsExtractor extractor) {
        int x = getX() + 6;
        int y = getY() + 6;
        int width = Math.min(FIND_BAR_W, getWidth() - 12);
        extractor.fill(x, y, x + width, y + FIND_BAR_H, 0xF0181A22);
        extractor.outline(x, y, width, FIND_BAR_H, LuneScreen.ACCENT);
        var text = extractor.textRenderer();
        text.accept(x + 6, y + 6, Component.literal(Lang.get("lune.gui.blueprint.find_prompt"))
                .withColor(LuneScreen.ACCENT));
        int queryX = x + 8 + Minecraft.getInstance().font.width(
                Lang.get("lune.gui.blueprint.find_prompt"));
        String counter = searchQuery.isBlank() ? ""
                : searchHits.isEmpty() ? Lang.get("lune.gui.blueprint.find_none")
                : Lang.get("lune.gui.blueprint.find_counter", searchIndex + 1, searchHits.size());
        int counterWidth = Minecraft.getInstance().font.width(counter);
        text.accept(queryX, y + 6, Component.literal(clip(searchQuery + "_",
                Math.max(8, width - (queryX - x) - counterWidth - 10))).withColor(LuneScreen.TEXT));
        text.accept(x + width - counterWidth - 6, y + 6, Component.literal(counter)
                .withColor(searchHits.isEmpty() && !searchQuery.isBlank()
                        ? NODE_FAILED : LuneScreen.TEXT_DIM));
        if (!searchHits.isEmpty()) {
            TaskSearch.Hit hit = searchHits.get(Math.floorMod(searchIndex, searchHits.size()));
            text.accept(x + 6, y + FIND_BAR_H + 4, Component.literal(clip(
                    Lang.get("lune.gui.blueprint.find_matched", hit.label(), hit.matched()), width))
                    .withColor(LuneScreen.TEXT_DIM));
        }
    }

    /**
     * Says, on the canvas, that the run is being held and on which card.
     *
     * <p>It goes here rather than only on the toolbar because a held bot is a bot doing nothing,
     * which looks exactly like a broken one. The one place a player is certain to be looking when
     * they wonder why is the card that has stopped lighting up.</p>
     */
    private void drawDebugBanner(GuiGraphicsExtractor extractor) {
        // A hold belongs to the run, and the run belongs to one task. Announcing it over somebody
        // else's canvas would name a card that is not on it.
        if (!showsRunningTask() || (!TaskDebug.holding() && !TaskDebug.waitingToBreak())) {
            return;
        }
        TaskNode held = task == null ? null : task.nodeById(TaskDebug.haltedNodeId());
        if (TaskDebug.holding() && held == null) {
            return;
        }
        String line = TaskDebug.holding()
                ? Lang.get("lune.gui.blueprint.debug_held", nodeName(held))
                : Lang.get("lune.gui.blueprint.debug_waiting");
        int width = Math.min(getWidth() - 12, Minecraft.getInstance().font.width(line) + 16);
        int y = getY() + getHeight() - 30;
        extractor.fill(getX() + 6, y, getX() + 6 + width, y + 18, 0xF0402A10);
        extractor.outline(getX() + 6, y, width, 18, FIND_MATCH);
        extractor.textRenderer().accept(getX() + 12, y + 5,
                Component.literal(Minecraft.getInstance().font.plainSubstrByWidth(line, width - 12))
                        .withColor(FIND_MATCH));
    }

    private static String clip(String value, int maxWidth) {
        return Minecraft.getInstance().font.plainSubstrByWidth(value, Math.max(4, maxWidth));
    }

    /** Wraps note text by width, keeping the newlines the player typed. */
    private static List<String> wrapLines(String value, int maxWidth) {
        List<String> lines = new ArrayList<>();
        var font = Minecraft.getInstance().font;
        for (String paragraph : value.split("\n", -1)) {
            if (paragraph.isEmpty()) {
                lines.add("");
                continue;
            }
            String rest = paragraph;
            while (!rest.isEmpty()) {
                String head = font.plainSubstrByWidth(rest, Math.max(8, maxWidth));
                if (head.isEmpty()) {
                    head = rest.substring(0, 1);
                }
                if (head.length() < rest.length()) {
                    int space = head.lastIndexOf(' ');
                    // Only break on a space when there is one worth breaking at; a single long
                    // word gets cut rather than pushed onto a line of its own forever.
                    if (space > head.length() / 3) {
                        head = head.substring(0, space);
                    }
                }
                lines.add(head);
                rest = rest.substring(head.length()).stripLeading();
            }
        }
        return lines;
    }

    // --- find, notes, frames, breakpoints and the clipboard -----------------

    public boolean isFindOpen() {
        return searchOpen;
    }

    public void toggleFind() {
        if (searchOpen) {
            closeFind();
            return;
        }
        searchOpen = true;
        setFocused(true);
        refreshFind();
        onMessage.accept(Lang.get("lune.gui.blueprint.find_opened"));
    }

    public void closeFind() {
        searchOpen = false;
        searchHits = List.of();
    }

    private void refreshFind() {
        searchHits = TaskSearch.find(task, searchQuery, BlueprintPanel::nodeName, this::nodeHeight);
        if (searchIndex >= searchHits.size()) {
            searchIndex = 0;
        }
    }

    private String currentHitId() {
        return searchHits.isEmpty() ? ""
                : searchHits.get(Math.floorMod(searchIndex, searchHits.size())).id();
    }

    /** Walks to the next or previous match, and brings both the view and the selection to it. */
    private void stepFind(int delta) {
        if (searchHits.isEmpty() || task == null) {
            return;
        }
        searchIndex = Math.floorMod(searchIndex + delta, searchHits.size());
        TaskSearch.Hit hit = searchHits.get(searchIndex);
        switch (hit.kind()) {
            case CARD -> {
                TaskNode node = task.nodeById(hit.id());
                if (node != null) {
                    // A match inside a closed frame is a match nobody can see, so finding it opens
                    // the frame. Anything else would count a card the player cannot look at.
                    TaskGroup holder = collapsedHolder(node);
                    if (holder != null) {
                        holder.collapsed = false;
                        fitGroups();
                    }
                    selectedNote = null;
                    setSelected(node);
                    onSelect.accept(node);
                }
            }
            case NOTE -> {
                selectedNodes.clear();
                selected = null;
                selectedNote = task.noteById(hit.id());
                onSelect.accept(null);
            }
            case GROUP -> {
                TaskGroup group = task.groupById(hit.id());
                if (group != null) {
                    selectedNote = null;
                    selectedNodes.clear();
                    selectedNodes.addAll(group.membersOf(task));
                    selectAnchor();
                    onSelect.accept(selected);
                }
            }
        }
        centerOn(hit.centerX(), hit.centerY());
    }

    private void centerOn(int canvasPointX, int canvasPointY) {
        panX = (int) Math.round(getWidth() / 2.0 - canvasPointX * zoom);
        panY = (int) Math.round(getHeight() / 2.0 - canvasPointY * zoom);
    }

    /** Zooms and pans until this canvas rectangle fills the view, within the usual zoom limits. */
    private void frame(int minX, int minY, int maxX, int maxY) {
        int width = Math.max(1, maxX - minX);
        int height = Math.max(1, maxY - minY);
        float wanted = (float) Math.min((getWidth() - 48.0) / width, (getHeight() - 48.0) / height);
        zoom = Math.clamp(wanted, MIN_ZOOM, MAX_ZOOM);
        centerOn((minX + maxX) / 2, (minY + maxY) / 2);
    }

    /** Fills the view with the selection, or with the whole task when nothing is selected. */
    public void frameSelection() {
        if (task == null || task.nodes.isEmpty()) {
            return;
        }
        List<TaskNode> wanted = selectedNodes.isEmpty()
                ? task.nodes : new ArrayList<>(selectedNodes);
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (TaskNode node : wanted) {
            minX = Math.min(minX, nodeX(node));
            minY = Math.min(minY, nodeY(node));
            maxX = Math.max(maxX, nodeX(node) + NODE_W);
            maxY = Math.max(maxY, nodeY(node) + nodeHeight(node));
        }
        if (minX > maxX) {
            return;
        }
        frame(minX - 20, minY - 20, maxX + 20, maxY + 20);
        onMessage.accept(Lang.get(selectedNodes.isEmpty() ? "lune.gui.blueprint.framed_task"
                : "lune.gui.blueprint.framed_selection"));
    }

    /** Pulls the view back until every card, note and frame in the task is on screen at once. */
    public void fitAll() {
        if (task == null || task.nodes.isEmpty()) {
            return;
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (TaskNode node : task.nodes) {
            minX = Math.min(minX, nodeX(node));
            minY = Math.min(minY, nodeY(node));
            maxX = Math.max(maxX, nodeX(node) + NODE_W);
            maxY = Math.max(maxY, nodeY(node) + nodeHeight(node));
        }
        if (task.notes != null) {
            for (TaskNote note : task.notes) {
                minX = Math.min(minX, note.x);
                minY = Math.min(minY, note.y);
                maxX = Math.max(maxX, note.right());
                maxY = Math.max(maxY, note.bottom());
            }
        }
        if (task.groups != null) {
            for (TaskGroup group : task.groups) {
                minX = Math.min(minX, group.x);
                minY = Math.min(minY, group.y - TaskGroup.HEADER_HEIGHT);
                maxX = Math.max(maxX, group.right());
                maxY = Math.max(maxY, group.bottom());
            }
        }
        frame(minX - 20, minY - 20, maxX + 20, maxY + 20);
        onMessage.accept(Lang.get("lune.gui.blueprint.framed_task"));
    }

    /** Drops a sticky note at a screen point, or in the middle of the view when none is given. */
    public TaskNote addNoteAt(Integer screenX, Integer screenY) {
        if (task == null) {
            return null;
        }
        if (task.notes == null) {
            task.notes = new ArrayList<>();
        }
        int x = screenX == null
                ? canvasX(getX() + getWidth() / 2.0) - TaskNote.DEFAULT_WIDTH / 2
                : canvasX(screenX);
        int y = screenY == null
                ? canvasY(getY() + getHeight() / 2.0) - TaskNote.DEFAULT_HEIGHT / 2
                : canvasY(screenY);
        TaskNote note = new TaskNote(x, y);
        task.notes.add(note);
        selectedNodes.clear();
        selected = null;
        onSelect.accept(null);
        selectedNote = note;
        // Straight into typing: a blank note is an invitation, and making the player find the
        // double-click that opens it would make it a chore instead.
        editingNote = note;
        setFocused(true);
        onChanged.run();
        onMessage.accept(Lang.get("lune.gui.blueprint.note_added"));
        return note;
    }

    private void commitNoteEdit() {
        if (editingNote == null) {
            return;
        }
        editingNote.clampSize();
        editingNote = null;
        onChanged.run();
    }

    private void commitTitleEdit() {
        if (editingGroup == null) {
            return;
        }
        editingGroup.title = titleBuffer.length() > TaskGroup.MAX_TITLE
                ? titleBuffer.substring(0, TaskGroup.MAX_TITLE) : titleBuffer;
        editingGroup = null;
        titleBuffer = "";
        onChanged.run();
    }

    /** Frames the selected cards, taking them out of whatever frame already held them. */
    public void groupSelection() {
        if (task == null || selectedNodes.isEmpty()) {
            onMessage.accept(Lang.get("lune.gui.blueprint.group_needs_cards"));
            return;
        }
        TaskGroup group = TaskGroup.group(task, new ArrayList<>(selectedNodes), "");
        if (group == null) {
            return;
        }
        fitGroups();
        editingGroup = group;
        titleBuffer = "";
        setFocused(true);
        onChanged.run();
        onMessage.accept(Lang.get("lune.gui.blueprint.group_created", group.members.size()));
    }

    public void ungroupSelection() {
        if (task == null) {
            return;
        }
        int removed = TaskGroup.ungroup(task, new ArrayList<>(selectedNodes));
        if (removed == 0) {
            onMessage.accept(Lang.get("lune.gui.blueprint.group_none_here"));
            return;
        }
        onChanged.run();
        onMessage.accept(Lang.get("lune.gui.blueprint.group_removed", removed));
    }

    /**
     * Puts a breakpoint on every selected card, or takes them all off when they all have one.
     *
     * <p>Source cards are refused. A run never <em>arrives</em> at an Always or a Button - they are
     * where signals come from - so a breakpoint on one would be a red dot that never once stopped
     * anything, which is worse than not offering it.</p>
     */
    public void toggleBreakpoints() {
        if (task == null || selectedNodes.isEmpty()) {
            onMessage.accept(Lang.get("lune.gui.blueprint.breakpoint_needs_card"));
            return;
        }
        List<TaskNode> usable = new ArrayList<>();
        for (TaskNode node : selectedNodes) {
            if (!node.isSourceNode()) {
                usable.add(node);
            }
        }
        if (usable.isEmpty()) {
            onMessage.accept(Lang.get("lune.gui.blueprint.breakpoint_not_on_source"));
            return;
        }
        boolean adding = usable.stream().anyMatch(node -> !node.breakpoint);
        for (TaskNode node : usable) {
            node.breakpoint = adding;
        }
        onChanged.run();
        onMessage.accept(Lang.get(adding ? "lune.gui.blueprint.breakpoint_set"
                : "lune.gui.blueprint.breakpoint_cleared", usable.size()));
    }

    public void clearBreakpoints() {
        int cleared = TaskDebug.clearAll(task);
        if (cleared > 0) {
            onChanged.run();
        }
        onMessage.accept(Lang.get("lune.gui.blueprint.breakpoint_cleared", cleared));
    }

    public void copySelection() {
        if (selectedNodes.isEmpty()) {
            return;
        }
        CLIPBOARD.clear();
        for (TaskNode node : selectedNodes) {
            TaskNode held = node.copy();
            // The original id is kept only while the card sits on the clipboard, so that the wires
            // between the copied cards can be recognised and rebuilt when they are pasted.
            held.id = node.id;
            CLIPBOARD.add(held);
        }
        onMessage.accept(Lang.get("lune.gui.blueprint.copied", CLIPBOARD.size()));
    }

    public void paste(Integer screenX, Integer screenY) {
        if (task == null || CLIPBOARD.isEmpty()) {
            onMessage.accept(Lang.get("lune.gui.blueprint.clipboard_empty"));
            return;
        }
        List<TaskNode> copies = rewire(CLIPBOARD);
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        for (TaskNode copy : copies) {
            minX = Math.min(minX, TaskCanvas.left(copy));
            minY = Math.min(minY, TaskCanvas.top(copy));
        }
        int targetX = screenX == null
                ? canvasX(getX() + getWidth() / 2.0) - NODE_W / 2 : canvasX(screenX);
        int targetY = screenY == null
                ? canvasY(getY() + getHeight() / 2.0) - NODE_H / 2 : canvasY(screenY);
        for (TaskNode copy : copies) {
            copy.editorX = TaskCanvas.left(copy) - minX + targetX;
            copy.editorY = TaskCanvas.top(copy) - minY + targetY;
        }
        task.nodes.addAll(copies);
        selectedNote = null;
        selectedNodes.clear();
        selectedNodes.addAll(copies);
        selectAnchor();
        onSelect.accept(selected);
        onChanged.run();
        onMessage.accept(Lang.get("lune.gui.blueprint.pasted", copies.size()));
    }

    public void duplicateSelection() {
        if (task == null || selectedNodes.isEmpty()) {
            return;
        }
        List<TaskNode> copies = rewire(new ArrayList<>(selectedNodes));
        for (TaskNode copy : copies) {
            copy.editorX = TaskCanvas.left(copy) + PASTE_OFFSET;
            copy.editorY = TaskCanvas.top(copy) + PASTE_OFFSET;
        }
        task.nodes.addAll(copies);
        selectedNote = null;
        selectedNodes.clear();
        selectedNodes.addAll(copies);
        selectAnchor();
        onSelect.accept(selected);
        onChanged.run();
        onMessage.accept(Lang.get("lune.gui.blueprint.duplicated", copies.size()));
    }

    /**
     * Independent copies of these cards, with fresh ids and every wire <em>between</em> them kept.
     *
     * <p>Wires leaving the set are dropped rather than followed. A copy of half a loop that
     * silently rejoined the original would give one card two claimants for its Success pin, and
     * the player would have built that by pressing Duplicate once.</p>
     */
    private static List<TaskNode> rewire(List<TaskNode> source) {
        java.util.Map<String, String> remap = new java.util.LinkedHashMap<>();
        List<TaskNode> copies = new ArrayList<>();
        for (TaskNode node : source) {
            TaskNode copy = node.copy();
            remap.put(node.id, copy.id);
            copies.add(copy);
        }
        for (TaskNode copy : copies) {
            copy.onSuccess = remap.get(copy.onSuccess);
            copy.onFailure = remap.get(copy.onFailure);
            copy.onWhile = remap.get(copy.onWhile);
            copy.observedNodeId = remap.get(copy.observedNodeId);
            Set<String> targets = new LinkedHashSet<>();
            java.util.Map<String, Integer> ports = new java.util.LinkedHashMap<>();
            for (String target : copy.alwaysTargets) {
                String moved = remap.get(target);
                if (moved != null) {
                    targets.add(moved);
                    Integer port = copy.alwaysTargetInputPorts.get(target);
                    if (port != null) {
                        ports.put(moved, port);
                    }
                }
            }
            copy.alwaysTargets = targets;
            copy.alwaysTargetInputPorts = ports;
            List<TaskSignalLink> links = new ArrayList<>();
            for (TaskSignalLink link : copy.signalLinks) {
                String moved = link == null ? null : remap.get(link.targetNodeId);
                if (moved != null) {
                    links.add(new TaskSignalLink(link.outputPort, moved, link.targetPort));
                }
            }
            copy.signalLinks = links;
            java.util.Map<String, TaskDataLink> inputs = new java.util.LinkedHashMap<>();
            copy.inputLinks.forEach((parameter, link) -> {
                String moved = link == null ? null : remap.get(link.sourceNodeId);
                if (moved != null) {
                    inputs.put(parameter, new TaskDataLink(moved, link.sourcePort));
                }
            });
            copy.inputLinks = inputs;
        }
        return copies;
    }

    private TaskNote noteAt(int x, int y) {
        if (task == null || task.notes == null) {
            return null;
        }
        for (int i = task.notes.size() - 1; i >= 0; i--) {
            TaskNote note = task.notes.get(i);
            if (note != null && note.contains(x, y)) {
                return note;
            }
        }
        return null;
    }

    private TaskGroup groupHeaderAt(int x, int y) {
        if (task == null || task.groups == null) {
            return null;
        }
        for (int i = task.groups.size() - 1; i >= 0; i--) {
            TaskGroup group = task.groups.get(i);
            if (group != null && group.headerContains(x, y)) {
                return group;
            }
        }
        return null;
    }

    /** Handles a click on a note, and says whether it claimed the click. */
    private boolean clickNote(int x, int y, boolean doubleClick) {
        TaskNote note = noteAt(x, y);
        if (note == null) {
            return false;
        }
        commitTitleEdit();
        selectedNodes.clear();
        selected = null;
        onSelect.accept(null);
        selectedNote = note;
        switch (noteButtonAt(note, x, y)) {
            case 0 -> {
                note.colour = Math.floorMod(note.colour + 1, NodePalette.paperCount());
                onChanged.run();
                return true;
            }
            case 1 -> {
                commitNoteEdit();
                deleteSelectedNote();
                return true;
            }
            default -> { }
        }
        if (doubleClick) {
            note.adopt();
            editingNote = note;
            return true;
        }
        commitNoteEdit();
        draggedNote = note;
        resizingNote = note.inResizeCorner(x, y);
        moved = false;
        return true;
    }

    /** Handles a click on a frame's title bar, and says whether it claimed the click. */
    private boolean clickGroup(int x, int y, boolean doubleClick) {
        TaskGroup group = groupHeaderAt(x, y);
        if (group == null) {
            return false;
        }
        commitNoteEdit();
        switch (groupButtonAt(group, x, y)) {
            case 0 -> {
                group.collapsed = !group.collapsed;
                fitGroups();
                onChanged.run();
                onMessage.accept(Lang.get(group.collapsed ? "lune.gui.blueprint.group_collapsed"
                        : "lune.gui.blueprint.group_expanded", group.members.size()));
                return true;
            }
            case 1 -> {
                group.colour = Math.floorMod(group.colour + 1, NodePalette.paperCount());
                onChanged.run();
                return true;
            }
            case 2 -> {
                task.groups.remove(group);
                onChanged.run();
                onMessage.accept(Lang.get("lune.gui.blueprint.group_removed", 1));
                return true;
            }
            default -> { }
        }
        if (doubleClick) {
            group.adopt();
            editingGroup = group;
            titleBuffer = group.title == null ? "" : group.title;
            return true;
        }
        commitTitleEdit();
        selectedNote = null;
        selectedNodes.clear();
        selectedNodes.addAll(group.membersOf(task));
        selectAnchor();
        onSelect.accept(selected);
        draggedGroup = group;
        moved = false;
        return true;
    }

    private void drawNode(GuiGraphicsExtractor extractor, TaskNode node, int mouseX, int mouseY) {
        int x = nodeX(node);
        int y = nodeY(node);
        int height = nodeHeight(node);
        double left = -panX / zoom;
        double top = -panY / zoom;
        double right = (getWidth() - panX) / zoom;
        double bottom = (getHeight() - panY) / zoom;
        if (x + NODE_W < left || x > right || y + height < top || y > bottom) {
            return;
        }

        showLogicTooltip(extractor, node, mouseX, mouseY);

        if (node.isStartNode()) {
            drawStartNode(extractor, node, mouseX, mouseY);
            return;
        }
        if (node.isClockNode()) {
            drawClockNode(extractor, node, mouseX, mouseY);
            return;
        }
        if (node.isSignalRelayNode()) {
            drawSignalRelayNode(extractor, node, mouseX, mouseY);
            return;
        }
        if (node.isTimerNode()) {
            drawTimerNode(extractor, node, mouseX, mouseY);
            return;
        }
        if (node.isCounterNode()) {
            drawCounterNode(extractor, node, mouseX, mouseY);
            return;
        }
        if (node.isEndNode()) {
            drawEndNode(extractor, node, mouseX, mouseY);
            return;
        }
        if (node.isObserverNode()) {
            drawObserverNode(extractor, node, mouseX, mouseY);
            return;
        }
        if (node.isButtonNode()) {
            drawButtonNode(extractor, node, mouseX, mouseY);
            return;
        }

        drawCardShell(extractor, node);

        CommandDef def = CommandRegistry.byId(node.commandId);
        String name = def == null ? node.commandId : def.name();
        var text = extractor.textRenderer();
        text.accept(x + 7, y + 5, Component.literal(name).withColor(0xFFFFFFFF));
        text.accept(x + 9, y + 23, Component.literal(Lang.get("lune.gui.blueprint.in")).withColor(LuneScreen.TEXT_DIM));
        CardStats stats = statsFor(node);
        // The repeat count keeps its place above the numbers rather than sliding down with them.
        int footerY = y + height - 14 - (stats == null ? 0 : STATS_ROW_HEIGHT);
        text.accept(x + 8, footerY,
                Component.literal(node.describeRepeat()).withColor(LuneScreen.TEXT_DIM));
        text.accept(x + NODE_W - 46, y + 25, Component.literal(Lang.get("lune.gui.blueprint.success")).withColor(pinSuccess));
        text.accept(x + NODE_W - 31, y + 40, Component.literal(Lang.get("lune.gui.blueprint.fail")).withColor(pinFailure));
        if (node.whileVisible) {
            text.accept(x + NODE_W - 38, y + 55, Component.literal(Lang.get("lune.gui.blueprint.while")).withColor(pinWhile));
        }

        drawPin(extractor, inputX(node), inputY(node), pinExec);
        drawPin(extractor, outputX(node), successY(node), pinSuccess);
        drawPin(extractor, outputX(node), failureY(node), pinFailure);
        if (node.whileVisible) {
            drawPin(extractor, outputX(node), whileY(node), pinWhile);
        }

        List<String> inputs = exposedInputs(node);
        for (int i = 0; i < inputs.size(); i++) {
            int portY = dataInputY(node, i);
            text.accept(x + 8, portY + 3, Component.literal(parameterLabel(node, inputs.get(i)))
                    .withColor(pinData));
            drawPin(extractor, inputX(node), portY, pinData);
        }
        List<String> outputs = exposedOutputs(node);
        for (int i = 0; i < outputs.size(); i++) {
            int portY = dataOutputY(node, i);
            text.accept(x + NODE_W - 42, portY - 3,
                    Component.literal(parameterLabel(node, outputs.get(i))).withColor(pinData));
            drawPin(extractor, outputX(node), portY, pinData);
        }
        if (stats != null) {
            drawStatsRow(extractor, stats, x, y + height - STATS_ROW_HEIGHT + 2);
        }

        if (contains(node, mouseX, mouseY) && !selectedNodes.contains(node)) {
            extractor.fill(x + 1, y + 1, x + NODE_W - 1, y + height - 1, 0x10FFFFFF);
        }
    }

    /** Always and Pulse share a shape: a clock with a fan-out pin. Only the rate differs. */
    private void drawClockNode(GuiGraphicsExtractor extractor, TaskNode node, int mouseX, int mouseY) {
        int x = nodeX(node);
        int y = nodeY(node);
        int height = nodeHeight(node);
        boolean pulse = node.isPulseSourceNode();
        drawCardShell(extractor, node);
        var text = extractor.textRenderer();
        text.accept(x + 7, y + 5, Component.literal(Lang.get(pulse ? "lune.gui.tasks.pulse" : "lune.gui.tasks.always"))
                .withColor(0xFFFFFFFF));
        text.accept(x + 8, y + 23, Component.literal(Lang.get(pulse ? "lune.gui.blueprint.clock_source" : "lune.gui.blueprint.pulse_source"))
                .withColor(NodePalette.of(node).accent()));
        // The rate sits bottom-right, opposite the repeat count every other card puts bottom-left.
        // It is the only number on a Pulse card and the reason the card exists, so it stays on
        // screen rather than living behind a hover.
        String rate = node.describeAlwaysInterval();
        text.accept(x + NODE_W - 8 - Minecraft.getInstance().font.width(rate), y + height - 14,
                Component.literal(rate).withColor(LuneScreen.TEXT_DIM));
        drawPin(extractor, outputX(node), whileY(node), pinWhile);
        if (contains(node, mouseX, mouseY) && !selectedNodes.contains(node)) {
            extractor.fill(x + 1, y + 1, x + NODE_W - 1, y + height - 1, 0x10FFFFFF);
        }
    }

    private void drawStartNode(GuiGraphicsExtractor extractor, TaskNode node, int mouseX, int mouseY) {
        int x = nodeX(node);
        int y = nodeY(node);
        int height = nodeHeight(node);
        drawCardShell(extractor, node);
        var text = extractor.textRenderer();
        text.accept(x + 7, y + 5, Component.literal(Lang.get("lune.gui.tasks.start")).withColor(0xFFFFFFFF));
        text.accept(x + 8, y + 23, Component.literal(Lang.get("lune.gui.blueprint.entry_point")).withColor(NodePalette.of(node).accent()));
        drawPin(extractor, outputX(node), successY(node), pinSuccess);
        if (contains(node, mouseX, mouseY) && !selectedNodes.contains(node)) {
            extractor.fill(x + 1, y + 1, x + NODE_W - 1, y + height - 1, 0x10FFFFFF);
        }
    }

    private void drawSignalRelayNode(GuiGraphicsExtractor extractor, TaskNode node,
                                     int mouseX, int mouseY) {
        int x = nodeX(node);
        int y = nodeY(node);
        int height = nodeHeight(node);
        drawCardShell(extractor, node);
        var text = extractor.textRenderer();
        text.accept(x + 7, y + 5, Component.literal(Lang.get("lune.gui.tasks.signal_relay")).withColor(0xFFFFFFFF));
        text.accept(x + 8, y + 19, Component.literal(Lang.get("lune.gui.blueprint.pulse_junction")).withColor(NodePalette.of(node).accent()));
        for (int i = 0; i < node.signalInputCount; i++) {
            int portY = signalInputY(node, i);
            text.accept(x + 8, portY - 3, Component.literal(Lang.get("lune.gui.blueprint.in_2", (i + 1))).withColor(pinSignal));
            drawPin(extractor, inputX(node), portY, pinSignal);
        }
        for (int i = 0; i < node.signalOutputCount; i++) {
            int portY = signalOutputY(node, i);
            text.accept(x + NODE_W - 39, portY - 3,
                    Component.literal(Lang.get("lune.gui.blueprint.out", (i + 1))).withColor(pinSignal));
            drawPin(extractor, outputX(node), portY, pinSignal);
        }
        if (contains(node, mouseX, mouseY) && !selectedNodes.contains(node)) {
            extractor.fill(x + 1, y + 1, x + NODE_W - 1, y + height - 1, 0x10FFFFFF);
        }
    }

    private void drawTimerNode(GuiGraphicsExtractor extractor, TaskNode node,
                               int mouseX, int mouseY) {
        int x = nodeX(node);
        int y = nodeY(node);
        int height = nodeHeight(node);
        drawCardShell(extractor, node);
        var text = extractor.textRenderer();
        text.accept(x + 7, y + 5, Component.literal(Lang.get("lune.gui.tasks.timer")).withColor(0xFFFFFFFF));
        text.accept(x + 8, y + 19, Component.literal(Lang.get("lune.gui.blueprint.pulse_delay")).withColor(NodePalette.of(node).accent()));
        text.accept(x + 8, signalInputY(node, 0) - 3,
                Component.literal(Lang.get("lune.gui.blueprint.in")).withColor(pinSignal));
        text.accept(x + NODE_W - 31, signalOutputY(node, 0) - 3,
                Component.literal(Lang.get("lune.gui.blueprint.out_2")).withColor(pinSignal));
        text.accept(x + 8, y + height - 14,
                Component.literal(node.describeRepeat()).withColor(LuneScreen.TEXT_DIM));
        drawPin(extractor, inputX(node), signalInputY(node, 0), pinSignal);
        drawPin(extractor, outputX(node), signalOutputY(node, 0), pinSignal);
        if (contains(node, mouseX, mouseY) && !selectedNodes.contains(node)) {
            extractor.fill(x + 1, y + 1, x + NODE_W - 1, y + height - 1, 0x10FFFFFF);
        }
    }

    private void drawCounterNode(GuiGraphicsExtractor extractor, TaskNode node,
                                 int mouseX, int mouseY) {
        drawPulseCard(extractor, node, Lang.get("lune.gui.tasks.counter"), Lang.get("lune.gui.blueprint.pulse_counter"), Lang.get("lune.gui.blueprint.in"), Lang.get("lune.gui.blueprint.out_2"), pinSignal,
                mouseX, mouseY, true);
    }

    private void drawEndNode(GuiGraphicsExtractor extractor, TaskNode node,
                             int mouseX, int mouseY) {
        int x = nodeX(node);
        int y = nodeY(node);
        int height = nodeHeight(node);
        drawCardShell(extractor, node);
        var text = extractor.textRenderer();
        text.accept(x + 7, y + 5, Component.literal(Lang.get("lune.gui.palette.end")).withColor(0xFFFFFFFF));
        text.accept(x + 8, y + 19, Component.literal(Lang.get("lune.gui.blueprint.pulse_sink")).withColor(NodePalette.of(node).accent()));
        text.accept(x + 8, signalInputY(node, 0) - 3, Component.literal(Lang.get("lune.gui.blueprint.in")).withColor(pinFailure));
        drawPin(extractor, inputX(node), signalInputY(node, 0), pinFailure);
        if (contains(node, mouseX, mouseY) && !selectedNodes.contains(node)) {
            extractor.fill(x + 1, y + 1, x + NODE_W - 1, y + height - 1, 0x10FFFFFF);
        }
    }

    private void drawObserverNode(GuiGraphicsExtractor extractor, TaskNode node,
                                  int mouseX, int mouseY) {
        int x = nodeX(node);
        int y = nodeY(node);
        int height = nodeHeight(node);
        drawCardShell(extractor, node);
        var text = extractor.textRenderer();
        text.accept(x + 7, y + 5, Component.literal(Lang.get("lune.gui.palette.observer")).withColor(0xFFFFFFFF));
        TaskNode watched = task == null || node.observedNodeId == null
                ? null : task.nodeById(node.observedNodeId);
        text.accept(x + 8, y + 19, watched == null ? Component.literal(Lang.get("lune.gui.blueprint.watch_nothing"))
                : Component.literal(Lang.get("lune.gui.blueprint.watches", nodeName(watched))).withColor(NodePalette.of(node).accent()));
        text.accept(x + 8, signalInputY(node, 0) - 3, Component.literal(Lang.get("lune.gui.blueprint.watch")).withColor(pinSignal));
        text.accept(x + NODE_W - 31, signalOutputY(node, 0) - 3,
                Component.literal(Lang.get("lune.gui.blueprint.out_2")).withColor(pinSignal));
        drawPin(extractor, inputX(node), signalInputY(node, 0), pinSignal);
        drawPin(extractor, outputX(node), signalOutputY(node, 0), pinSignal);
        if (contains(node, mouseX, mouseY) && !selectedNodes.contains(node)) {
            extractor.fill(x + 1, y + 1, x + NODE_W - 1, y + height - 1, 0x10FFFFFF);
        }
    }

    private void drawButtonNode(GuiGraphicsExtractor extractor, TaskNode node,
                                int mouseX, int mouseY) {
        drawSourcePulseCard(extractor, node, Lang.get("lune.gui.tasks.button"), Lang.get("lune.gui.blueprint.manual_source"), pinSuccess, mouseX, mouseY);
    }

    private void drawPulseCard(GuiGraphicsExtractor extractor, TaskNode node, String title,
                               String subtitle, String inputLabel, String outputLabel, int colour,
                               int mouseX, int mouseY, boolean footer) {
        int x = nodeX(node);
        int y = nodeY(node);
        int height = nodeHeight(node);
        drawCardShell(extractor, node);
        var text = extractor.textRenderer();
        text.accept(x + 7, y + 5, Component.literal(title).withColor(0xFFFFFFFF));
        text.accept(x + 8, y + 19, Component.literal(subtitle)
                .withColor(NodePalette.of(node).accent()));
        text.accept(x + 8, signalInputY(node, 0) - 3, Component.literal(inputLabel).withColor(colour));
        text.accept(x + NODE_W - 31, signalOutputY(node, 0) - 3,
                Component.literal(outputLabel).withColor(colour));
        if (footer) {
            text.accept(x + 8, y + height - 14,
                    Component.literal(Lang.get("lune.gui.blueprint.every_pulses", node.params.getOrDefault("count", "3")))
                            .withColor(LuneScreen.TEXT_DIM));
        }
        drawPin(extractor, inputX(node), signalInputY(node, 0), colour);
        drawPin(extractor, outputX(node), signalOutputY(node, 0), colour);
        if (contains(node, mouseX, mouseY) && !selectedNodes.contains(node)) {
            extractor.fill(x + 1, y + 1, x + NODE_W - 1, y + height - 1, 0x10FFFFFF);
        }
    }

    private void drawSourcePulseCard(GuiGraphicsExtractor extractor, TaskNode node,
                                     String title, String subtitle, int colour,
                                     int mouseX, int mouseY) {
        int x = nodeX(node);
        int y = nodeY(node);
        int height = nodeHeight(node);
        drawCardShell(extractor, node);
        var text = extractor.textRenderer();
        text.accept(x + 7, y + 5, Component.literal(title).withColor(0xFFFFFFFF));
        text.accept(x + 8, y + 23, Component.literal(subtitle)
                .withColor(NodePalette.of(node).accent()));
        text.accept(x + NODE_W - 31, signalOutputY(node, 0) - 3,
                Component.literal(Lang.get("lune.gui.blueprint.out_2")).withColor(colour));
        drawPin(extractor, outputX(node), signalOutputY(node, 0), colour);
        if (contains(node, mouseX, mouseY) && !selectedNodes.contains(node)) {
            extractor.fill(x + 1, y + 1, x + NODE_W - 1, y + height - 1, 0x10FFFFFF);
        }
    }

    private void showLogicTooltip(GuiGraphicsExtractor extractor, TaskNode node,
                                  int mouseX, int mouseY) {
        if (contextNode != null || !contains(node, mouseX, mouseY)) {
            return;
        }
        CardStats stats = statsFor(node);
        if (stats != null && mouseY >= nodeY(node) + nodeHeight(node) - STATS_ROW_HEIGHT) {
            // The numbers row is the one part of a card that explains itself rather than its logic.
            extractor.setComponentTooltipForNextFrame(Minecraft.getInstance().font,
                    statsTooltip(node, stats),
                    UiScale.toGamePixels(pointerX), UiScale.toGamePixels(pointerY));
            return;
        }
        String logic = node.isStartNode()
                ? Lang.get("lune.gui.blueprint.send_one_signal_connected_success_target")
                : node.isClockNode()
                ? Lang.get("lune.gui.blueprint.send_new_signal_each_connected_target", node.describeAlwaysInterval(), nodeName(node))
                : node.isSignalRelayNode()
                ? Lang.get("lune.gui.blueprint.forward_pulse_arriving_any_input_through")
                : logicDescription(node);
        extractor.setComponentTooltipForNextFrame(Minecraft.getInstance().font,
                List.of(Component.literal(Lang.get("lune.gui.param.logic")).withColor(LuneScreen.ACCENT),
                        Component.literal(logic).withColor(LuneScreen.TEXT)),
                UiScale.toGamePixels(pointerX), UiScale.toGamePixels(pointerY));
    }

    private String logicDescription(TaskNode node) {
        CommandDef def = CommandRegistry.byId(node.commandId);
        return def == null
                ? Lang.get("lune.gui.blueprint.node_unavailable_success_follows_success")
                : def.logicDescription(node.repeat);
    }

    /** True when the task on screen is the one the engine is actually running. */
    private boolean showsRunningTask() {
        return BotEngine.get().getCurrent() instanceof TaskRunner running
                && running.currentTask() == task;
    }

    private TaskPower readLivePower() {
        Task current = BotEngine.get().getCurrent();
        return current instanceof TaskRunner running && running.currentTask() == task
                ? running.livePower() : TaskPower.NONE;
    }

    private boolean isActiveNode(TaskNode candidate) {
        return trainingPreview == null ? power.isLive(candidate)
                : trainingPreview.nodeLit(candidate, System.currentTimeMillis() - previewStarted);
    }

    private boolean isFailedNode(TaskNode candidate) {
        return task != null && TaskRunner.isLastFailed(task, candidate);
    }

    // --- card stats ---------------------------------------------------------
    //
    // With Node stats switched on, a card whose job has ever finished something carries one more
    // row: how fast the job usually goes, how fast it went last time, and the fastest it has ever
    // gone - the three numbers a player compares to tell whether the change they just made
    // helped. Hovering the row lists every situation the job has been measured in, which is where
    // "with an axe it goes twice as fast" becomes visible.

    private static final int STATS_ROW_HEIGHT = 13;
    /** How long a card's numbers may go unrefreshed. The table is small, but not frame-rate small. */
    private static final long STATS_REFRESH_NANOS = 1_000_000_000L;
    private static final int STATS_AVERAGE = LuneScreen.TEXT;
    private static final int STATS_LAST = 0xFF9CCBFF;
    private static final int STATS_BEST = NODE_ACTIVE;
    /** Situations listed on hover before the rest fold into "+N more". */
    private static final int STATS_ROWS_SHOWN = 6;
    private static final String STATS_SEPARATOR = " · ";
    /** What a profile written before "last time" was kept has to say about it. */
    private static final String STATS_UNKNOWN = "-";

    /** What one card's job has measured, and the parameters that answer was worked out for. */
    private static final class CardStats {
        private final String commandId;
        private final Map<String, String> params;
        /** Null when the card has no task to ask - an unknown command, or one that failed to build. */
        private final LearningScope scope;
        private SkillStats stats = SkillStats.EMPTY;
        private long refreshedNanos;

        private CardStats(String commandId, Map<String, String> params, LearningScope scope,
                          long now) {
            this.commandId = commandId;
            this.params = params;
            this.scope = scope;
            refresh(now);
        }

        private void refresh(long now) {
            stats = scope == null ? SkillStats.EMPTY : LearningStore.get().skillStats(scope);
            refreshedNanos = now;
        }

        /** Whether the job counts something per second, or only has a length to report. */
        private boolean rate() {
            return scope != null && scope.unitKey() != null;
        }

        private String unit() {
            return rate() ? Lang.get(scope.unitKey()) : "";
        }
    }

    /**
     * Per card, and weakly: a card that leaves the task takes its entry with it, so nothing has
     * to remember to clear this on each of the several ways a card can be deleted.
     */
    private final Map<TaskNode, CardStats> cardStats = new WeakHashMap<>();

    /**
     * The numbers for a card, or null when there is nothing to show: the setting is off, the card
     * is a source or a pulse rather than a job, or its job has never finished anything.
     *
     * <p>Which rows a card owns is a question for its task, so the task is built - the same way
     * the runner builds it - once per card, and again only when a parameter changes. The numbers
     * themselves are re-read from the profile every second, so a run in progress updates the
     * card as it goes.</p>
     */
    private CardStats statsFor(TaskNode node) {
        if (node == null || node.isSourceNode() || node.isPulseNode()
                || !BotConfig.get().nodeStats) {
            return null;
        }
        Map<String, String> params = node.params == null ? Map.of() : node.params;
        CardStats card = cardStats.get(node);
        long now = System.nanoTime();
        if (card == null || !Objects.equals(card.commandId, node.commandId)
                || !card.params.equals(params)) {
            card = new CardStats(node.commandId, new LinkedHashMap<>(params),
                    scopeOf(node, params), now);
            cardStats.put(node, card);
        } else if (now - card.refreshedNanos >= STATS_REFRESH_NANOS) {
            card.refresh(now);
        }
        return card.stats.measured() ? card : null;
    }

    /** The rows a card's job writes to, from the task it would run as; null when it has none. */
    private static LearningScope scopeOf(TaskNode node, Map<String, String> params) {
        CommandDef def = CommandRegistry.byId(node.commandId);
        if (def == null) {
            return null;
        }
        try {
            return def.buildWith(params).learningScope();
        } catch (RuntimeException e) {
            // The runner would refuse this card too, and says so when it is run. A canvas that
            // cannot draw is the wrong place to report it.
            Constants.LOG.debug("No stats for card {}: it could not be built", node.commandId, e);
            return null;
        }
    }

    /** The pooled number a card leads with: a pace for a job with a unit, a length otherwise. */
    private static double average(CardStats card) {
        return card.rate() ? card.stats.averageUnitsPerSecond() : card.stats.averageSecondsPerUnit();
    }

    private static double last(CardStats card) {
        return card.rate() ? card.stats.lastUnitsPerSecond() : card.stats.lastSecondsPerUnit();
    }

    private static double best(CardStats card) {
        return card.rate() ? card.stats.bestUnitsPerSecond() : card.stats.bestSecondsPerUnit();
    }

    /** A pace in words: "3.30 logs/s" for a job with a unit, "45.2 s" for one with only a length. */
    private static String pace(CardStats card, double value) {
        return card.rate()
                ? Lang.get("lune.gui.blueprint.stats.rate", SkillStats.format(value), card.unit())
                : Lang.get("lune.gui.blueprint.stats.seconds", SkillStats.format(value));
    }

    private static String count(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    /** {@code minecraft:the_nether} reads as {@code the_nether}; the namespace says nothing here. */
    private static String dimensionName(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }

    /** Average, last and best on one line, coloured the way the hover explains them. */
    private void drawStatsRow(GuiGraphicsExtractor extractor, CardStats card, int x, int y) {
        SkillStats stats = card.stats;
        String average = card.rate()
                ? Lang.get("lune.gui.blueprint.stats.per_second", SkillStats.format(average(card)))
                : Lang.get("lune.gui.blueprint.stats.seconds", SkillStats.format(average(card)));
        String last = stats.hasLast() ? SkillStats.format(last(card)) : STATS_UNKNOWN;
        String best = stats.bestTicksPerUnit() > 0.0 ? SkillStats.format(best(card)) : STATS_UNKNOWN;
        var font = Minecraft.getInstance().font;
        var text = extractor.textRenderer();
        int cursor = x + 8;
        text.accept(cursor, y, Component.literal(average).withColor(STATS_AVERAGE));
        cursor += font.width(average);
        text.accept(cursor, y, Component.literal(STATS_SEPARATOR).withColor(LuneScreen.TEXT_DIM));
        cursor += font.width(STATS_SEPARATOR);
        text.accept(cursor, y, Component.literal(last).withColor(STATS_LAST));
        cursor += font.width(last);
        text.accept(cursor, y, Component.literal(STATS_SEPARATOR).withColor(LuneScreen.TEXT_DIM));
        cursor += font.width(STATS_SEPARATOR);
        text.accept(cursor, y, Component.literal(best).withColor(STATS_BEST));
    }

    /**
     * The detailed view: the three numbers named, the totals behind them, and then one entry per
     * situation the job has been measured in, busiest first, so a player can see which of them
     * is dragging the average - and, where the learner had a choice, which tactic it now prefers.
     */
    private List<Component> statsTooltip(TaskNode node, CardStats card) {
        SkillStats stats = card.stats;
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(Lang.get("lune.gui.blueprint.stats.title", nodeName(node)))
                .withColor(LuneScreen.ACCENT));
        lines.add(Component.literal(Lang.get("lune.gui.blueprint.stats.average",
                pace(card, average(card)))).withColor(STATS_AVERAGE));
        lines.add(Component.literal(Lang.get("lune.gui.blueprint.stats.last",
                stats.hasLast() ? pace(card, last(card)) : STATS_UNKNOWN)).withColor(STATS_LAST));
        lines.add(Component.literal(Lang.get("lune.gui.blueprint.stats.best",
                pace(card, best(card)))).withColor(STATS_BEST));
        lines.add(Component.literal(card.rate()
                ? Lang.get("lune.gui.blueprint.stats.totals", count(stats.runs()),
                        count(stats.completedRuns()), count(stats.totalUnits()), card.unit(),
                        Durations.ofTicks(stats.totalTicks()))
                : Lang.get("lune.gui.blueprint.stats.totals_bare", count(stats.runs()),
                        count(stats.completedRuns()), Durations.ofTicks(stats.totalTicks())))
                .withColor(LuneScreen.TEXT_DIM));
        if (stats.rows().size() < 2) {
            return lines;
        }
        lines.add(Component.literal(Lang.get("lune.gui.blueprint.stats.by_situation"))
                .withColor(LuneScreen.ACCENT));
        boolean severalDimensions = stats.rows().stream()
                .map(row -> row.context().dimension()).distinct().count() > 1;
        int shown = 0;
        for (SkillStats.Row row : stats.rows()) {
            if (shown == STATS_ROWS_SHOWN) {
                lines.add(Component.literal(Lang.get("lune.gui.blueprint.stats.more",
                        count(stats.rows().size() - shown))).withColor(LuneScreen.TEXT_DIM));
                break;
            }
            shown++;
            String situation = (severalDimensions
                    ? dimensionName(row.context().dimension()) + STATS_SEPARATOR : "")
                    + row.context().phase().replace(";", ", ");
            String numbers;
            if (row.measured()) {
                numbers = Lang.get("lune.gui.blueprint.stats.row",
                        pace(card, card.rate() ? row.averageUnitsPerSecond() : row.averageSecondsPerUnit()),
                        pace(card, card.rate() ? row.bestUnitsPerSecond() : row.bestSecondsPerUnit()),
                        count(row.runs()));
                if (!row.leadingTactic().isEmpty()) {
                    numbers += STATS_SEPARATOR + row.leadingTactic();
                }
            } else {
                numbers = Lang.get("lune.gui.blueprint.stats.row_unmeasured", count(row.runs()));
            }
            lines.add(Component.literal(situation).withColor(LuneScreen.TEXT));
            lines.add(Component.literal("  " + numbers).withColor(LuneScreen.TEXT_DIM));
        }
        return lines;
    }

    // --- inline card toolbar -------------------------------------------------
    //
    // The two things done most often to a card are "delete it" and "change how many times it
    // runs", and both used to live in a strip of nine identically-sized buttons above the canvas,
    // one of which shared its label with the button that deletes the entire task. That is a long
    // way to travel, and an easy place to misfire.
    //
    // So they moved onto the card: hover or select one and a small bar appears just above it, the
    // way a selected object in Figma or Miro carries its own controls. The middle control is
    // whatever that card's "how many" happens to mean - repeat count for work, pulse interval for
    // an Always source - which is also how the interval finally became findable, since it was
    // previously only reachable through a button that appeared in the strip and nowhere else.
    // The toolbar buttons stay as they were, for keyboard users and for multi-card selections.

    private static final int CHIP = 13;
    private static final int CHIP_GAP = 2;
    private static final int CHIP_LIFT = 5;
    private static final int CHIP_BG = 0xF0262832;
    private static final int CHIP_HOVER = 0xF0C86400;
    private static final int CHIP_DANGER = 0xF09C4C50;
    private static final int CHIP_TEXT = 0xFFE8E8EE;
    private static final int CHIP_EDIT = 0xF0203A56;
    private static final int CONTEXT_ROW_H = 18;
    private static final int CONTEXT_MAIN_H = CONTEXT_ROW_H * 3;
    private static final int CONTEXT_MAIN_W = 74;
    private static final int CONTEXT_SUB_W = 72;
    private static final int CONTEXT_GAP = 2;
    private static final int CONTEXT_BG = 0xF01A1A20;
    private static final int CONTEXT_HOVER = 0xF0C86400;
    private static final int CONTEXT_TEXT = 0xFFE8E8EE;
    private static final int CONTEXT_DISABLED = 0xFF777783;
    private static final int CONTEXT_DANGER = 0xFFFF9B9B;
    /** Upper bound on a typed repeat, so a slipped keystroke is not a millions-long job. */
    private static final int MAX_REPEAT = 1_000_000;
    /** Height of a drawn glyph box, which is what a chip label is centred against. */
    private static final int GLYPH_H = 8;

    // The chip bar reads: decrement, the number itself, increment, forever, delete.
    //
    // The number is a field rather than a label. "How many times" is genuinely an arbitrary number
    // - 37 is as reasonable as 3 - and a control that can only toggle between one and forever, or
    // step one at a time, cannot express it. Click it and type. Shift on the arrows steps by ten,
    // and the forever chip is separate so it is one click rather than a value buried at the end of
    // a range.
    private static final int CHIP_MINUS = 0;
    private static final int CHIP_VALUE = 1;
    private static final int CHIP_PLUS = 2;

    /** The card whose number chip is being typed into, and what has been typed so far. */
    private TaskNode editingNode;
    private String editBuffer = "";

    /**
     * The cards showing a toolbar this frame.
     *
     * <p>Two of them, at most, and on purpose. Clicking a card pins its controls there so they do
     * not disappear the moment the pointer wanders; hovering another still offers that card's own
     * controls, so a selection can never make the rest of the canvas unreachable. Whichever one the
     * pointer is actually over wins the click.</p>
     */
    private TaskNode hoverToolbarNode;
    private TaskNode pinnedToolbarNode;
    private TaskNode contextNode;
    private int contextMenuX;
    private int contextMenuY;
    private boolean contextSubmenuOpen;

    private void refreshToolbarNodes(int canvasMouseX, int canvasMouseY) {
        if (task == null || wireSource != null || dragged != null || draggedCableKey != null || selecting) {
            hoverToolbarNode = null;
            pinnedToolbarNode = null;
            return;
        }
        // A single selected card keeps its controls; a multi-card selection is the toolbar strip's
        // job, because "delete these six" is not something one card should claim.
        pinnedToolbarNode = selectedNodes.size() == 1 ? selectedNodes.iterator().next() : null;
        if (pinnedToolbarNode != null && (!task.nodes.contains(pinnedToolbarNode)
                || collapsedHolder(pinnedToolbarNode) != null)) {
            pinnedToolbarNode = null;
        }
        // The chips sit above the card, so reaching for them leaves the card's own bounds. Keeping
        // the current one while the pointer is on its chips stops the bar vanishing mid-reach.
        if (hoverToolbarNode != null && task.nodes.contains(hoverToolbarNode)
                && chipAt(hoverToolbarNode, canvasMouseX, canvasMouseY) >= 0) {
            return;
        }
        TaskNode hovered = nodeAt(canvasMouseX, canvasMouseY);
        hoverToolbarNode = hovered == pinnedToolbarNode ? null : hovered;
    }

    /** The card whose chip the pointer is on, hovered first because that is the one under it. */
    private TaskNode toolbarHitNode(int canvasX, int canvasY) {
        if (hoverToolbarNode != null && chipAt(hoverToolbarNode, canvasX, canvasY) >= 0) {
            return hoverToolbarNode;
        }
        if (pinnedToolbarNode != null && chipAt(pinnedToolbarNode, canvasX, canvasY) >= 0) {
            return pinnedToolbarNode;
        }
        return null;
    }

    /**
     * Per-frame memo for the one graph query the chip layout makes.
     *
     * <p>The layout is a cascade - hit-testing asks for a chip's x, which asks for the width of
     * every chip before it, each of which asks how many chips there are - so a single hover asked
     * the graph whether this card was a While companion several dozen times a frame. It is always
     * asking about one card, so remembering the last answer is enough.</p>
     */
    private TaskNode whileCompanionMemoNode;
    private boolean whileCompanionMemo;

    private boolean isWhileCompanion(TaskNode node) {
        if (node != whileCompanionMemoNode) {
            whileCompanionMemoNode = node;
            whileCompanionMemo = TaskWiring.isWhileTarget(task, node);
        }
        return whileCompanionMemo;
    }

    /** True when the middle chips apply: a rate for a clock, a repeat count for work. */
    private boolean hasCountChip(TaskNode node) {
        if (node.isPulseSourceNode()) {
            return true;
        }
        // Always has no rate at all any more - it is on, every tick - so it carries no arrows.
        if (node.isSourceNode()) {
            return false;
        }
        return (!node.isPulseNode() || node.isTimerNode()) && !isWhileCompanion(node);
    }

    /** Forever only means something where a repeat count does. */
    private boolean hasForeverChip(TaskNode node) {
        return hasCountChip(node) && !node.isPulseSourceNode();
    }

    private String countLabel(TaskNode node) {
        if (node == editingNode) {
            return editBuffer.isEmpty() ? "_" : editBuffer;
        }
        return node.isPulseSourceNode() ? node.alwaysIntervalSeconds + "s" : node.describeRepeat();
    }

    private int chipCount(TaskNode node) {
        return hasCountChip(node) ? (hasForeverChip(node) ? 5 : 4) : 1;
    }

    private String chipLabel(TaskNode node, int index) {
        if (!hasCountChip(node)) {
            return "×";
        }
        if (index == chipCount(node) - 1) {
            return "×";
        }
        return switch (index) {
            case CHIP_MINUS -> "-";
            case CHIP_VALUE -> countLabel(node);
            case CHIP_PLUS -> "+";
            default -> "∞";
        };
    }

    /**
     * Width of one chip. The value chip is sized to its own contents, because "x1", "1000" and a
     * half-typed number are all legitimate and a fixed square would clip them.
     */
    private int chipWidth(TaskNode node, int index) {
        return index == CHIP_VALUE && hasCountChip(node)
                ? Math.max(CHIP + 8,
                        Minecraft.getInstance().font.width(chipLabel(node, index)) + 9)
                : CHIP;
    }

    private int toolbarWidth(TaskNode node) {
        int width = 0;
        for (int i = 0; i < chipCount(node); i++) {
            width += chipWidth(node, i) + (i == 0 ? 0 : CHIP_GAP);
        }
        return width;
    }

    private int chipX(TaskNode node, int index) {
        int x = toolbarLeft(node);
        for (int i = 0; i < index; i++) {
            x += chipWidth(node, i) + CHIP_GAP;
        }
        return x;
    }

    private int toolbarLeft(TaskNode node) {
        return nodeX(node) + NODE_W - toolbarWidth(node);
    }

    private int toolbarTop(TaskNode node) {
        return nodeY(node) - CHIP - CHIP_LIFT;
    }

    /** Index of the chip under the pointer, or -1. Chips read left to right. */
    private int chipAt(TaskNode node, int canvasX, int canvasY) {
        if (node == null) {
            return -1;
        }
        int top = toolbarTop(node);
        if (canvasY < top || canvasY >= top + CHIP) {
            return -1;
        }
        for (int i = 0; i < chipCount(node); i++) {
            int left = chipX(node, i);
            if (canvasX >= left && canvasX < left + chipWidth(node, i)) {
                return i;
            }
        }
        return -1;
    }

    private void drawCardToolbar(GuiGraphicsExtractor extractor, TaskNode node,
                                 int canvasMouseX, int canvasMouseY) {
        int chips = chipCount(node);
        int top = toolbarTop(node);
        int hovered = chipAt(node, canvasMouseX, canvasMouseY);
        int accent = NodePalette.of(node).accent();
        var text = extractor.textRenderer();
        for (int i = 0; i < chips; i++) {
            int left = chipX(node, i);
            int width = chipWidth(node, i);
            boolean danger = i == chips - 1;
            boolean typing = i == CHIP_VALUE && node == editingNode;
            boolean forever = hasForeverChip(node) && i == chips - 2 && node.repeat == 0;
            int background = typing ? CHIP_EDIT
                    : hovered == i ? (danger ? CHIP_DANGER : CHIP_HOVER)
                    : forever ? CHIP_HOVER : CHIP_BG;
            extractor.fill(left, top, left + width, top + CHIP, background);
            extractor.outline(left, top, width, CHIP, accent);
            String label = chipLabel(node, i);
            // font.width includes the one-pixel gap that follows the last glyph, so centring on it
            // raw leaves every label sitting a pixel right of true centre. The glyph box is 8px
            // tall inside a 13px chip, which is the other half of the same rounding.
            int labelWidth = Minecraft.getInstance().font.width(label) - 1;
            text.accept(left + (width - labelWidth) / 2, top + (CHIP - GLYPH_H) / 2,
                    Component.literal(label).withColor(CHIP_TEXT));
        }
        if (hovered >= 0) {
            extractor.setComponentTooltipForNextFrame(Minecraft.getInstance().font,
                    List.of(Component.literal(chipHelp(node, hovered)).withColor(LuneScreen.TEXT)),
                    UiScale.toGamePixels(pointerX), UiScale.toGamePixels(pointerY));
        }
    }

    private String chipHelp(TaskNode node, int chip) {
        if (chip == chipCount(node) - 1) {
            return Lang.get("lune.gui.blueprint.delete_card_or_press_delete");
        }
        if (hasForeverChip(node) && chip == chipCount(node) - 2) {
            return Lang.get("lune.gui.blueprint.run_forever");
        }
        boolean clock = node.isPulseSourceNode();
        return switch (chip) {
            case CHIP_MINUS -> clock ? Lang.get("lune.gui.blueprint.one_second_less_shift_ten") : Lang.get("lune.gui.blueprint.one_run_fewer_shift_ten");
            case CHIP_PLUS -> clock ? Lang.get("lune.gui.blueprint.one_second_more_shift_ten") : Lang.get("lune.gui.blueprint.one_run_more_shift_ten");
            default -> clock
                    ? Lang.get("lune.gui.blueprint.seconds_between_pulses_click_type_one_0")
                    : Lang.get("lune.gui.blueprint.how_many_times_card_runs_click_type");
        };
    }

    /** Applies a toolbar chip. Returns true when the click was consumed. */
    private boolean clickCardToolbar(TaskNode node, int chip) {
        if (node == null || chip < 0) {
            return false;
        }
        int last = chipCount(node) - 1;
        if (chip == last) {
            commitChipEdit();
            onDelete.accept(List.of(node));
            return true;
        }
        if (chip == CHIP_VALUE) {
            beginChipEdit(node);
            return true;
        }
        commitChipEdit();
        if (hasForeverChip(node) && chip == last - 1) {
            node.repeat = 0;
            onChanged.run();
            onMessage.accept(nodeName(node) + Lang.get("lune.gui.blueprint.runs") + node.describeRepeat());
            return true;
        }
        int magnitude = shiftDown() ? 10 : 1;
        if (node.isPulseSourceNode()) {
            int seconds = node.alwaysIntervalSeconds
                    + (chip == CHIP_PLUS ? magnitude : -magnitude);
            node.alwaysIntervalSeconds = Math.clamp(seconds,
                    TaskNode.MIN_ALWAYS_INTERVAL_SECONDS, TaskNode.MAX_ALWAYS_INTERVAL_SECONDS);
            onChanged.run();
            onMessage.accept(Lang.get("lune.gui.blueprint.pulse_now_fires", node.describeAlwaysInterval()));
            return true;
        }
        // Stepping down from forever lands on a real number rather than staying at zero.
        int base = node.repeat == 0 ? 1 : node.repeat;
        node.repeat = Math.clamp(base + (chip == CHIP_PLUS ? magnitude : -magnitude), 1, MAX_REPEAT);
        onChanged.run();
        onMessage.accept(nodeName(node) + Lang.get("lune.gui.blueprint.runs") + node.describeRepeat());
        return true;
    }

    private void beginChipEdit(TaskNode node) {
        editingNode = node;
        editBuffer = node.isPulseSourceNode()
                ? String.valueOf(node.alwaysIntervalSeconds)
                : node.repeat == 0 ? "" : String.valueOf(node.repeat);
    }

    /** Writes a typed number back, if one was typed. An empty box leaves the card alone. */
    private void commitChipEdit() {
        TaskNode node = editingNode;
        editingNode = null;
        if (node == null || task == null || !task.nodes.contains(node) || editBuffer.isEmpty()) {
            editBuffer = "";
            return;
        }
        int typed;
        try {
            typed = Integer.parseInt(editBuffer);
        } catch (NumberFormatException ignored) {
            editBuffer = "";
            return;
        }
        editBuffer = "";
        if (node.isPulseSourceNode()) {
            node.alwaysIntervalSeconds = Math.clamp(typed,
                    TaskNode.MIN_ALWAYS_INTERVAL_SECONDS, TaskNode.MAX_ALWAYS_INTERVAL_SECONDS);
            onMessage.accept(Lang.get("lune.gui.blueprint.pulse_now_fires", node.describeAlwaysInterval()));
        } else {
            node.repeat = Math.clamp(typed, 0, MAX_REPEAT);
            onMessage.accept(nodeName(node) + Lang.get("lune.gui.blueprint.runs") + node.describeRepeat());
        }
        onChanged.run();
    }

    private void openContextMenu(TaskNode node, double screenX, double screenY) {
        contextNode = node;
        contextSubmenuOpen = false;
        contextMenuX = Math.clamp((int) Math.round(screenX), getX() + 2,
                getX() + getWidth() - CONTEXT_MAIN_W - 2);
        contextMenuY = Math.clamp((int) Math.round(screenY), getY() + 2,
                getY() + getHeight() - CONTEXT_MAIN_H - 2);
    }

    private void closeContextMenu() {
        contextNode = null;
        contextSubmenuOpen = false;
    }

    private int contextSubmenuX() {
        int right = contextMenuX + CONTEXT_MAIN_W + CONTEXT_GAP;
        return right + CONTEXT_SUB_W <= getX() + getWidth() - 2
                ? right : contextMenuX - CONTEXT_GAP - CONTEXT_SUB_W;
    }

    private boolean contextShowContains(double x, double y) {
        return x >= contextMenuX && x < contextMenuX + CONTEXT_MAIN_W
                && y >= contextMenuY && y < contextMenuY + CONTEXT_ROW_H;
    }

    private boolean contextBreakpointContains(double x, double y) {
        return x >= contextMenuX && x < contextMenuX + CONTEXT_MAIN_W
                && y >= contextMenuY + CONTEXT_ROW_H && y < contextMenuY + CONTEXT_ROW_H * 2;
    }

    private boolean contextDeleteContains(double x, double y) {
        return x >= contextMenuX && x < contextMenuX + CONTEXT_MAIN_W
                && y >= contextMenuY + CONTEXT_ROW_H * 2 && y < contextMenuY + CONTEXT_MAIN_H;
    }

    private boolean contextSubmenuContains(double x, double y) {
        int left = contextSubmenuX();
        return x >= left && x < left + CONTEXT_SUB_W
                && y >= contextMenuY && y < contextMenuY + CONTEXT_ROW_H;
    }

    private boolean supportsWhilePort(TaskNode node) {
        return node != null && !node.isStartNode() && !node.isClockNode() && !node.isPulseNode();
    }

    private boolean handleContextMenuClick(double x, double y) {
        if (contextNode == null) {
            return false;
        }
        if (!task.nodes.contains(contextNode)) {
            closeContextMenu();
            return true;
        }
        if (contextSubmenuContains(x, y)) {
            if (supportsWhilePort(contextNode)) {
                contextNode.whileVisible = !contextNode.whileVisible;
                onChanged.run();
                onMessage.accept((contextNode.whileVisible
                        ? Lang.get("lune.gui.blueprint.while_output_shown") : Lang.get("lune.gui.blueprint.while_output_hidden"))
                        + nodeName(contextNode));
            }
            closeContextMenu();
            return true;
        }
        if (contextBreakpointContains(x, y)) {
            TaskNode node = contextNode;
            closeContextMenu();
            if (node.isSourceNode()) {
                onMessage.accept(Lang.get("lune.gui.blueprint.breakpoint_not_on_source"));
                return true;
            }
            boolean set = TaskDebug.toggle(node);
            onChanged.run();
            onMessage.accept(Lang.get(set ? "lune.gui.blueprint.breakpoint_set"
                    : "lune.gui.blueprint.breakpoint_cleared", 1));
            return true;
        }
        if (contextDeleteContains(x, y)) {
            TaskNode node = contextNode;
            closeContextMenu();
            commitChipEdit();
            onDelete.accept(List.of(node));
            return true;
        }
        if (contextShowContains(x, y)) {
            // The submenu is opened by hovering Show; clicking the parent keeps the menu open so
            // the player can move into While without losing the menu.
            return true;
        }
        closeContextMenu();
        return true;
    }

    private void drawContextMenu(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
        if (task == null || contextNode == null || !task.nodes.contains(contextNode)) {
            closeContextMenu();
            return;
        }
        boolean showHovered = contextShowContains(mouseX, mouseY);
        boolean breakHovered = contextBreakpointContains(mouseX, mouseY);
        boolean deleteHovered = contextDeleteContains(mouseX, mouseY);
        boolean submenuHovered = contextSubmenuContains(mouseX, mouseY);
        if (showHovered || submenuHovered) {
            contextSubmenuOpen = true;
        } else if (!deleteHovered && !breakHovered) {
            contextSubmenuOpen = false;
        }
        drawContextPanel(extractor, contextMenuX, contextMenuY, CONTEXT_MAIN_W, CONTEXT_MAIN_H);
        drawContextRow(extractor, contextMenuX, contextMenuY, CONTEXT_MAIN_W, showHovered);
        drawContextRow(extractor, contextMenuX, contextMenuY + CONTEXT_ROW_H,
                CONTEXT_MAIN_W, breakHovered);
        drawContextRow(extractor, contextMenuX, contextMenuY + CONTEXT_ROW_H * 2,
                CONTEXT_MAIN_W, deleteHovered);
        var text = extractor.textRenderer();
        text.accept(contextMenuX + 7, contextMenuY + 5,
                Component.literal(Lang.get("lune.gui.blueprint.show")).withColor(CONTEXT_TEXT));
        text.accept(contextMenuX + CONTEXT_MAIN_W - 12, contextMenuY + 5,
                Component.literal("> ").withColor(LuneScreen.TEXT_DIM));
        text.accept(contextMenuX + 7, contextMenuY + CONTEXT_ROW_H + 5,
                Component.literal(Lang.get("lune.gui.blueprint.breakpoint")).withColor(
                        contextNode.isSourceNode() ? CONTEXT_DISABLED
                                : contextNode.breakpoint ? BREAKPOINT : CONTEXT_TEXT));
        if (contextNode.breakpoint) {
            text.accept(contextMenuX + CONTEXT_MAIN_W - 14, contextMenuY + CONTEXT_ROW_H + 5,
                    Component.literal("✓").withColor(BREAKPOINT));
        }
        text.accept(contextMenuX + 7, contextMenuY + CONTEXT_ROW_H * 2 + 5,
                Component.literal(Lang.get("lune.gui.tasks.delete_2")).withColor(CONTEXT_DANGER));

        if (contextSubmenuOpen) {
            int x = contextSubmenuX();
            drawContextPanel(extractor, x, contextMenuY, CONTEXT_SUB_W, CONTEXT_ROW_H);
            drawContextRow(extractor, x, contextMenuY, CONTEXT_SUB_W, submenuHovered);
            text.accept(x + 7, contextMenuY + 5,
                    Component.literal(Lang.get("lune.gui.blueprint.while")).withColor(supportsWhilePort(contextNode)
                            ? contextNode.whileVisible ? LuneScreen.ACCENT : CONTEXT_TEXT
                            : CONTEXT_DISABLED));
            if (contextNode.whileVisible && supportsWhilePort(contextNode)) {
                text.accept(x + CONTEXT_SUB_W - 14, contextMenuY + 5,
                        Component.literal("✓").withColor(LuneScreen.ACCENT));
            }
        }
    }

    private void drawContextPanel(GuiGraphicsExtractor extractor, int x, int y, int width,
                                  int height) {
        extractor.fill(x, y, x + width, y + height, CONTEXT_BG);
        extractor.outline(x, y, width, height, LuneScreen.PANEL_BORDER);
    }

    private void drawContextRow(GuiGraphicsExtractor extractor, int x, int y, int width,
                                boolean hovered) {
        if (hovered) {
            extractor.fill(x + 1, y + 1, x + width - 1, y + CONTEXT_ROW_H - 1, CONTEXT_HOVER);
        }
    }

    private boolean shiftDown() {
        return Minecraft.getInstance().hasShiftDown();
    }

    /**
     * Draws the whole card shell - ring, border, body, header and role stripe - and returns the
     * border colour so a caller can tell live from failed from idle.
     *
     * <p>Every card type goes through here, which is what keeps a canvas looking like one board.
     * The role stripe is the only thing that differs between a Mine and a Timer.</p>
     */
    private int drawCardShell(GuiGraphicsExtractor extractor, TaskNode node) {
        int x = nodeX(node);
        int y = nodeY(node);
        int height = nodeHeight(node);
        NodePalette.Colours colours = NodePalette.of(node);

        int border = colours.border();
        if (isActiveNode(node)) {
            // Two translucent rings keep the running card obvious even when it is not selected.
            extractor.fill(x - 4, y - 4, x + NODE_W + 4, y + height + 4, NODE_ACTIVE_GLOW);
            extractor.fill(x - 2, y - 2, x + NODE_W + 2, y + height + 2, NODE_ACTIVE);
            border = NODE_ACTIVE;
        } else if (isFailedNode(node)) {
            extractor.fill(x - 4, y - 4, x + NODE_W + 4, y + height + 4, NODE_FAILED_GLOW);
            extractor.fill(x - 2, y - 2, x + NODE_W + 2, y + height + 2, NODE_FAILED);
            border = NODE_FAILED;
        } else if (selectedNodes.contains(node)) {
            border = NODE_SELECTED;
        }

        if (TaskDebug.isHalted(node)) {
            // A held card is not running and is not broken, so it gets neither of those two rings.
            extractor.fill(x - 4, y - 4, x + NODE_W + 4, y + height + 4, HELD_GLOW);
            extractor.fill(x - 2, y - 2, x + NODE_W + 2, y + height + 2, FIND_MATCH);
            border = FIND_MATCH;
        }

        extractor.fill(x, y, x + NODE_W, y + height, border);
        extractor.fill(x + 1, y + 1, x + NODE_W - 1, y + height - 1, colours.background());
        // Only the outline reports that a card is live. Recolouring the title bar too made a
        // running card read as a different kind of card rather than the same one, lit up.
        extractor.fill(x + 1, y + 1, x + NODE_W - 1, y + HEADER_H,
                border == NODE_FAILED ? HEADER_FAILED : colours.header());
        // The role stripe: the one mark that separates a source from an action from a decision.
        extractor.fill(x + 1, y + 1, x + 4, y + HEADER_H, colours.accent());
        if (node.breakpoint) {
            // Outside the card, in the gutter where a code editor puts it. Inside it would be one
            // more dot competing with the pins, which are the only other round things on a card.
            int dotX = x - 7;
            int dotY = y + HEADER_H / 2;
            int colour = TaskDebug.armed() ? BREAKPOINT : BREAKPOINT_DISARMED;
            extractor.fill(dotX - 4, dotY - 4, dotX + 4, dotY + 4, 0xFF101014);
            extractor.fill(dotX - 3, dotY - 3, dotX + 3, dotY + 3, colour);
        }
        return border;
    }

    private void drawPin(GuiGraphicsExtractor extractor, int x, int y, int colour) {
        extractor.fill(x - 3, y - 3, x + 4, y + 4, 0xFF101014);
        extractor.fill(x - 2, y - 2, x + 3, y + 3, colour);
    }

    /** Tiny white handles show exactly where each released cable point is stored. */
    private void drawCableRoutePoints(GuiGraphicsExtractor extractor) {
        if (task == null) {
            return;
        }
        java.util.Map<String, TaskCableRoute> routes = new java.util.LinkedHashMap<>(automaticCableRoutes);
        if (task.cableAnchors != null) routes.putAll(task.cableAnchors);
        routes.forEach((key, savedRoute) -> {
            TaskCableRoute route = key != null && key.equals(draggedCableKey) && cableMoved
                    ? draggedCablePreview : savedRoute;
            if (route == null || route.points == null) {
                return;
            }
            for (TaskCableAnchor point : route.points) {
                if (point == null || !inside(point.x, point.y)) {
                    continue;
                }
                extractor.fill(point.x - 3, point.y - 3, point.x + 4, point.y + 4, 0xFF15151C);
                int colour = task.cableAnchors != null && task.cableAnchors.containsKey(key)
                        ? 0xFFFFFFFF : 0xFF9CCBFF;
                extractor.fill(point.x - 2, point.y - 2, point.x + 3, point.y + 3, colour);
            }
        });
    }

    private void drawWire(GuiGraphicsExtractor extractor, int x1, int y1, int x2, int y2, int colour) {
        drawWire(extractor, x1, y1, x2, y2, colour, false, null);
    }

    private void drawWire(GuiGraphicsExtractor extractor, int x1, int y1, int x2, int y2,
                          int colour, TaskCableRoute route) {
        drawWire(extractor, x1, y1, x2, y2, colour, false, route);
    }

    /**
     * Draws a cable through its optional ordered routing points. A cable without a route keeps
     * the editor's original smooth curve; every point released by the player becomes another
     * smooth section, so the cable can be shaped freely over repeated pulls.
     *
     * <p>A live wire is drawn thicker and gets a bright spark travelling along it, so the canvas
     * shows where the signal came from as well as where it is now. Each emitted pulse travels once
     * from its own timestamp; an old signal never loops just because its target is still busy.</p>
     */
    private void drawWire(GuiGraphicsExtractor extractor, int x1, int y1, int x2, int y2,
                          int colour, boolean live, TaskCableRoute route) {
        drawWire(extractor, x1, y1, x2, y2, colour, live, route, false);
    }

    /**
     * The same, with {@code faded} set while another cable is traced: every part of this one
     * - edge, line and spark - is mixed toward the canvas, so the traced cable stands out.
     */
    private void drawWire(GuiGraphicsExtractor extractor, int x1, int y1, int x2, int y2,
                          int colour, boolean live, TaskCableRoute route, boolean faded) {
        double spark = live ? previewSpark : -1;
        previewSpark = -1;
        if (!wireCouldBeVisible(x1, y1, x2, y2, route)) {
            // Whole-wire cull. The per-point check below still had to walk every sample of a wire
            // that was nowhere near the screen, which on a large task is most of them.
            return;
        }
        // Built once per cable rather than once per sample: the old sampler re-walked the routing
        // points for every one of its ninety-six steps.
        TaskCablePath path = TaskCablePath.of(x1, y1, x2, y2, route);
        // Join curve samples with solid strokes. A fixed number of isolated dots leaves gaps
        // on long wires and even on short wires where the cubic tangent runs fastest.
        int samples = Math.clamp((int) Math.ceil(path.length() * zoom / 6), 12, 256);
        int thickness = Math.max(2, (int) Math.ceil(1.5 / zoom));
        TaskCablePath.Point previous = path.at(0);
        for (int i = 1; i <= samples; i++) {
            TaskCablePath.Point point = path.at(i / (double) samples);
            // A dark outline separates crossing Success and Fail cables from each other.
            cableStroke(extractor, previous, point, thickness + 2,
                    faded ? mix(WIRE_OUTLINE, CANVAS_SHADE, FADED) : WIRE_OUTLINE);
            cableStroke(extractor, previous, point, thickness,
                    faded ? mix(colour, CANVAS_SHADE, FADED) : colour);
            previous = point;
        }
        if (spark >= 0) {
            TaskCablePath.Point point = path.at(spark);
            int px = (int) Math.round(point.x());
            int py = (int) Math.round(point.y());
            extractor.fill(px - 3, py - 3, px + 4, py + 4,
                    faded ? mix(colour, CANVAS_SHADE, FADED) : colour);
            extractor.fill(px - 1, py - 1, px + 2, py + 2,
                    faded ? mix(LIVE_WIRE_SPARK, CANVAS_SHADE, FADED) : LIVE_WIRE_SPARK);
        }
    }

    /** How far toward the canvas a cable is faded while another one is traced. */
    private static final double FADED = 0.65;

    /** Rotated rectangles join every sample, including at fractional canvas zoom. */
    private void cableStroke(GuiGraphicsExtractor extractor, TaskCablePath.Point from,
                             TaskCablePath.Point to, int width, int colour) {
        double dx = to.x() - from.x();
        double dy = to.y() - from.y();
        var pose = extractor.pose();
        pose.pushMatrix();
        pose.translate((float) from.x(), (float) from.y());
        pose.rotate((float) Math.atan2(dy, dx));
        extractor.fill(0, -width / 2, (int) Math.ceil(Math.hypot(dx, dy)) + 1,
                width - width / 2, colour);
        pose.popMatrix();
    }

    private TaskCableRoute anchorFor(String key) {
        if (key == null) {
            return null;
        }
        if (key.equals(draggedCableKey) && cableMoved) {
            return draggedCablePreview;
        }
        if (task == null) return null;
        TaskCableRoute saved = task.cableAnchors == null ? null : task.cableAnchors.get(key);
        if (saved != null) return saved;
        String[] parts = key.split("\\|", -1);
        if (parts.length != 3 && parts.length != 5) return null;
        TaskNode source = task.nodeById(parts[1]);
        TaskNode target = task.nodeById(parts[parts.length == 3 ? 2 : 3]);
        if (source == null || target == null || outputX(source) < inputX(target)) return null;
        int sy = switch (parts[0]) {
            case "failure" -> failureY(source);
            case "while", "always" -> whileY(source);
            case "signal" -> signalOutputY(source, Integer.parseInt(parts[2]));
            case "observe" -> nodeY(source) + HEADER_H / 2;
            case "data" -> dataOutputY(source, exposedOutputs(source).indexOf(parts[2]));
            default -> successY(source);
        };
        int ty = switch (parts[0]) {
            case "data" -> dataInputY(target, exposedInputs(target).indexOf(parts[4]));
            case "signal" -> targetInputY(target, Integer.parseInt(parts[4]));
            case "success" -> targetInputY(target, source.successInputPort);
            case "failure" -> targetInputY(target, source.failureInputPort);
            case "while" -> targetInputY(target, source.whileInputPort);
            case "always" -> targetInputY(target, source.alwaysTargetInputPorts == null ? 0
                    : source.alwaysTargetInputPorts.getOrDefault(target.id, 0));
            default -> targetInputY(target, 0);
        };
        int bottom = Math.max(nodeY(source) + nodeHeight(source), nodeY(target) + nodeHeight(target));
        // Include cards in this row between the endpoints. Lower rows keep their own space.
        for (TaskNode obstacle : task.nodes) {
            if (nodeX(obstacle) <= outputX(source) && outputX(obstacle) >= inputX(target)
                    && nodeY(obstacle) <= bottom) {
                bottom = Math.max(bottom, nodeY(obstacle) + nodeHeight(obstacle));
            }
        }
        int lane = switch (parts[0]) {
            case "failure" -> 14;
            case "while", "always" -> 28;
            default -> 0;
        };
        int entryOffset = switch (parts[0]) {
            case "failure" -> 9;
            case "while", "always" -> -9;
            case "signal" -> (Integer.parseInt(parts[2]) % 2 == 0) ? -7 : 7;
            default -> 0;
        };
        TaskCableRoute route = TaskCableRoute.returning(outputX(source), sy, inputX(target), ty,
                bottom, lane, entryOffset);
        automaticCableRoutes.put(key, route);
        return route;
    }

    /** True when any part of a wire's path could land inside the visible canvas. */
    private boolean wireCouldBeVisible(int x1, int y1, int x2, int y2,
                                       TaskCableRoute route) {
        double left = -panX / zoom;
        double top = -panY / zoom;
        double right = (getWidth() - panX) / zoom;
        double bottom = (getHeight() - panY) / zoom;
        double tangent = TaskCablePath.pinTangent(x1, x2);
        double minX = Math.min(x1, x2) - tangent - 2;
        double maxX = Math.max(x1, x2) + tangent + 2;
        double minY = Math.min(y1, y2) - 2;
        double maxY = Math.max(y1, y2) + 2;
        int previousX = x1;
        int previousY = y1;
        if (route != null && route.points != null) {
            for (TaskCableAnchor point : route.points) {
                if (point == null) {
                    continue;
                }
                tangent = Math.max(tangent, TaskCablePath.pinTangent(previousX, point.x));
                minX = Math.min(minX, Math.min(previousX, point.x) - tangent - 2);
                maxX = Math.max(maxX, Math.max(previousX, point.x) + tangent + 2);
                minY = Math.min(minY, Math.min(previousY, point.y) - 2);
                maxY = Math.max(maxY, Math.max(previousY, point.y) + 2);
                previousX = point.x;
                previousY = point.y;
            }
        }
        tangent = Math.max(tangent, TaskCablePath.pinTangent(previousX, x2));
        minX = Math.min(minX, Math.min(previousX, x2) - tangent - 2);
        maxX = Math.max(maxX, Math.max(previousX, x2) + tangent + 2);
        minY = Math.min(minY, Math.min(previousY, y2) - 2);
        maxY = Math.max(maxY, Math.max(previousY, y2) + 2);
        return maxX >= left && minX <= right && maxY >= top && minY <= bottom;
    }

    private record CableHit(String key, int insertionIndex) {}

    private record CablePointHit(String key, int pointIndex) {}

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (task == null) {
            return;
        }
        pointerX = (int) event.x();
        pointerY = (int) event.y();

        // Named, never numbered: 26.3 moved to SDL, which counts the buttons from 1, so the
        // literal 0 this used to test was no button at all there, and 1 was the left one.
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
            return;
        }

        if (findBarContains(pointerX, pointerY)) {
            setFocused(true);
            return;
        }

        if (minimapVisible && minimapContains(pointerX, pointerY)) {
            minimapDragging = true;
            centerOnMinimap(pointerX, pointerY);
            return;
        }

        int canvasPointerX = canvasX(pointerX);
        int canvasPointerY = canvasY(pointerY);

        // The card toolbar overhangs empty canvas, so it has to be tested before panning and
        // marquee selection claim the click.
        TaskNode chipOwner = toolbarHitNode(canvasPointerX, canvasPointerY);
        if (chipOwner != null
                && clickCardToolbar(chipOwner, chipAt(chipOwner, canvasPointerX, canvasPointerY))) {
            return;
        }
        // Any click that is not on the number chip finishes whatever was being typed.
        commitChipEdit();

        for (int i = task.nodes.size() - 1; i >= 0; i--) {
            TaskNode node = task.nodes.get(i);
            List<String> outputs = exposedOutputs(node);
            for (int output = 0; output < outputs.size(); output++) {
            if (near(canvasPointerX, canvasPointerY, outputX(node), dataOutputY(node, output))) {
                    beginDataWire(node, outputs.get(output));
                    return;
                }
            }
            if (node.isPulseNode()) {
                int outputCount = node.isEndNode() ? 0
                        : node.isSignalRelayNode() ? node.signalOutputCount : 1;
                for (int output = 0; output < outputCount; output++) {
                    if (near(canvasPointerX, canvasPointerY, outputX(node),
                            signalOutputY(node, output))) {
                        beginSignalWire(node, output);
                        return;
                    }
                }
                continue;
            }
            if (node.isStartNode()) {
                if (near(canvasPointerX, canvasPointerY, outputX(node), successY(node))) {
                    beginWire(node, WIRE_SUCCESS);
                    return;
                }
                continue;
            }
            if (node.isClockNode()) {
                if (near(canvasPointerX, canvasPointerY, outputX(node), whileY(node))) {
                    beginWire(node, WIRE_WHILE);
                    return;
                }
                continue;
            }
            if (near(canvasPointerX, canvasPointerY, outputX(node), successY(node))) {
                beginWire(node, WIRE_SUCCESS);
                return;
            }
                if (near(canvasPointerX, canvasPointerY, outputX(node), failureY(node))) {
                    beginWire(node, WIRE_FAILURE);
                    return;
                }
            if (node.whileVisible
                    && near(canvasPointerX, canvasPointerY, outputX(node), whileY(node))) {
                beginWire(node, WIRE_WHILE);
                return;
            }
        }

        TaskNode hit = nodeAt(canvasPointerX, canvasPointerY);
        if (hit != null) {
            selectedNote = null;
            commitNoteEdit();
            commitTitleEdit();
            boolean ctrl = (event.modifiers() & InputConstants.MOD_CONTROL) != 0;
            if (ctrl) {
                if (!selectedNodes.remove(hit)) {
                    selectedNodes.add(hit);
                    selected = hit;
                } else if (selected == hit) {
                    selectAnchor();
                }
            } else {
                if (!selectedNodes.contains(hit)) {
                    selectedNodes.clear();
                    selectedNodes.add(hit);
                }
                selected = hit;
            }
            onSelect.accept(selected);
            if (pointerY < screenNodeY(hit) + HEADER_H * zoom && selectedNodes.contains(hit)) {
                dragged = hit;
                moved = false;
            }
            return;
        }

        CablePointHit pointHit = cablePointAt(canvasPointerX, canvasPointerY);
        if (pointHit != null) {
            beginCablePointDrag(pointHit, canvasPointerX, canvasPointerY);
            return;
        }

        CableHit cableHit = cableAt(canvasPointerX, canvasPointerY);
        if (cableHit != null) {
            beginCableDrag(cableHit, canvasPointerX, canvasPointerY);
            return;
        }

        // Notes and frames are tested after the cables that cross them, because a cable is drawn
        // on top of both and clicking what you can see is the only rule a canvas can afford.
        if (clickNote(canvasPointerX, canvasPointerY, doubleClick)) {
            return;
        }
        if (clickGroup(canvasPointerX, canvasPointerY, doubleClick)) {
            return;
        }

        selectionAdditive = (event.modifiers() & InputConstants.MOD_CONTROL) != 0;
        if (!selectionAdditive) {
            selectedNodes.clear();
            selected = null;
            selectedNote = null;
            commitNoteEdit();
            commitTitleEdit();
            onSelect.accept(null);
        }
        selectionStartX = pointerX;
        selectionStartY = pointerY;
        selectionMoved = false;
        selecting = selectionAdditive;
        panning = !selectionAdditive;
        panCarryX = 0;
        panCarryY = 0;
    }

    /** Right-click opens a node menu, while a wire segment still offers the quick cut action. */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && contextNode != null) {
            boolean handled = handleContextMenuClick(event.x(), event.y());
            setFocused(true);
            return handled;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_MIDDLE) {
            if (isMouseOver(event.x(), event.y())) {
                // Anywhere on the canvas, cards included, so a pan can start on top of a card
                // without picking it up. On a cable the same press also makes it shine.
                if (task != null) {
                    closeContextMenu();
                    middlePanning = true;
                    panCarryX = 0;
                    panCarryY = 0;
                    beginMiddlePress(event.x(), event.y());
                }
                setFocused(true);
                return true;
            }
            return false;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            if (isMouseOver(event.x(), event.y())) {
                if (minimapVisible && minimapContains(event.x(), event.y())) {
                    closeContextMenu();
                    return true;
                }
                TaskNode hit = task == null ? null
                        : nodeAt(canvasX(event.x()), canvasY(event.y()));
                if (hit != null) {
                    selectedNodes.clear();
                    selectedNodes.add(hit);
                    selected = hit;
                    onSelect.accept(hit);
                    openContextMenu(hit, event.x(), event.y());
                    setFocused(true);
                    return true;
                }
                closeContextMenu();
                if (task != null && removeCablePointAt((int) event.x(), (int) event.y())) {
                    return true;
                }
                if (task != null && cutWireAt((int) event.x(), (int) event.y())) {
                    return true;
                }
                return true;
            }
            closeContextMenu();
            return false;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!isMouseOver(mouseX, mouseY) || task == null) {
            return false;
        }
        if (controlDown()) {
            float oldZoom = zoom;
            double scroll = Math.abs(scrollY) >= Math.abs(scrollX) ? scrollY : scrollX;
            float factor = scroll > 0 ? ZOOM_STEP : 1.0f / ZOOM_STEP;
            zoom = Math.clamp(zoom * factor, MIN_ZOOM, MAX_ZOOM);
            if (zoom != oldZoom) {
                // Keep the point below the cursor fixed while the canvas changes scale.
                double canvasMouseX = (mouseX - getX() - panX) / oldZoom;
                double canvasMouseY = (mouseY - getY() - panY) / oldZoom;
                panX = (int) Math.round(mouseX - getX() - canvasMouseX * zoom);
                panY = (int) Math.round(mouseY - getY() - canvasMouseY * zoom);
            }
        } else if (Math.abs(scrollX) > Math.abs(scrollY)) {
            panX -= (int) Math.round(scrollX * 40.0);
        } else {
            panY -= (int) Math.round(scrollY * 40.0);
        }
        return true;
    }

    /** Returns the cable and exact routed segment under a canvas point, if there is one. */
    private CableHit cableAt(int x, int y) {
        if (task == null) {
            return null;
        }
        for (TaskNode source : task.nodes) {
            CableHit hit;
            if (source.isClockNode() && source.alwaysTargets != null) {
                for (String targetId : source.alwaysTargets) {
                    TaskNode target = task.nodeById(targetId);
                    String key = TaskCableAnchor.key("always", source.id, targetId);
                    hit = target == null ? null : cableHit(key, outputX(source), whileY(source),
                            inputX(target), targetInputY(target, source.alwaysTargetInputPorts == null
                                    ? 0 : source.alwaysTargetInputPorts.getOrDefault(target.id, 0)), x, y);
                    if (hit != null) {
                        return hit;
                    }
                }
            }
            TaskNode successTarget = task.nodeById(source.onSuccess);
            String successKey = successTarget == null ? null
                    : TaskCableAnchor.key("success", source.id, successTarget.id);
            hit = successTarget == null ? null : cableHit(successKey, outputX(source), successY(source),
                    inputX(successTarget), targetInputY(successTarget,
                            successTarget.isPulseNode() ? source.successInputPort : -1), x, y);
            if (hit != null) {
                return hit;
            }
            TaskNode failureTarget = task.nodeById(source.onFailure);
            String failureKey = failureTarget == null ? null
                    : TaskCableAnchor.key("failure", source.id, failureTarget.id);
            hit = failureTarget == null ? null : cableHit(failureKey, outputX(source), failureY(source),
                    inputX(failureTarget), targetInputY(failureTarget,
                            failureTarget.isPulseNode() ? source.failureInputPort : -1), x, y);
            if (hit != null) {
                return hit;
            }
            if (!source.isClockNode() && source.whileVisible) {
                TaskNode whileTarget = task.nodeById(source.onWhile);
                String whileKey = whileTarget == null ? null
                        : TaskCableAnchor.key("while", source.id, whileTarget.id);
                hit = whileTarget == null ? null : cableHit(whileKey, outputX(source), whileY(source),
                        inputX(whileTarget), targetInputY(whileTarget,
                                whileTarget.isPulseNode() ? source.whileInputPort : -1), x, y);
                if (hit != null) {
                    return hit;
                }
            }
            if (source.isPulseNode() && source.signalLinks != null) {
                for (TaskSignalLink link : source.signalLinks) {
                    if (link == null) {
                        continue;
                    }
                    TaskNode target = task.nodeById(link.targetNodeId);
                    String key = target == null ? null : TaskCableAnchor.key("signal", source.id,
                            String.valueOf(link.outputPort), target.id,
                            String.valueOf(link.targetPort));
                    hit = target == null ? null : cableHit(key, outputX(source),
                            signalOutputY(source, link.outputPort), inputX(target),
                            targetInputY(target, link.targetPort), x, y);
                    if (hit != null) {
                        return hit;
                    }
                }
            }
            if (source.isObserverNode() && source.observedNodeId != null) {
                TaskNode watched = task.nodeById(source.observedNodeId);
                String key = watched == null ? null
                        : TaskCableAnchor.key("observe", watched.id, source.id);
                hit = watched == null ? null : cableHit(key, outputX(watched),
                        nodeY(watched) + HEADER_H / 2, inputX(source), signalInputY(source, 0), x, y);
                if (hit != null) {
                    return hit;
                }
            }
            if (source.inputLinks != null) {
                for (var entry : source.inputLinks.entrySet()) {
                    TaskDataLink link = entry.getValue();
                    TaskNode dataSource = link == null ? null : task.nodeById(link.sourceNodeId);
                    int sourcePort = dataSource == null ? -1
                            : exposedOutputs(dataSource).indexOf(link.sourcePort);
                    int targetPort = exposedInputs(source).indexOf(entry.getKey());
                    String key = dataSource == null || sourcePort < 0 || targetPort < 0 ? null
                            : TaskCableAnchor.key("data", dataSource.id, link.sourcePort,
                                    source.id, entry.getKey());
                    hit = dataSource == null || sourcePort < 0 || targetPort < 0 ? null
                            : cableHit(key, outputX(dataSource), dataOutputY(dataSource, sourcePort),
                                    inputX(source), dataInputY(source, targetPort), x, y);
                    if (hit != null) {
                        return hit;
                    }
                }
            }
        }
        return null;
    }

    private CableHit cableHit(String key, int x1, int y1, int x2, int y2, int px, int py) {
        int insertionIndex = wireSegmentAt(x1, y1, x2, y2, px, py, anchorFor(key));
        return insertionIndex < 0 ? null : new CableHit(key, insertionIndex);
    }

    private void beginCableDrag(CableHit hit, int x, int y) {
        draggedCableKey = hit.key();
        TaskCableRoute stored = anchorFor(draggedCableKey);
        draggedCableBase = stored == null ? new TaskCableRoute() : stored.copy();
        draggedCablePreview = draggedCableBase.copy();
        draggedCablePointIndex = -1;
        draggedCableInsertIndex = Math.clamp(hit.insertionIndex(), 0,
                draggedCablePreview.points.size());
        cableDragStartX = x;
        cableDragStartY = y;
        cableMoved = false;
        onMessage.accept(Lang.get("lune.gui.blueprint.drag_cable_release_add_routing_point"));
    }

    private void beginCablePointDrag(CablePointHit hit, int x, int y) {
        draggedCableKey = hit.key();
        TaskCableRoute stored = anchorFor(draggedCableKey);
        draggedCableBase = stored == null ? new TaskCableRoute() : stored.copy();
        draggedCablePreview = draggedCableBase.copy();
        draggedCablePointIndex = Math.clamp(hit.pointIndex(), 0,
                Math.max(0, draggedCablePreview.points.size() - 1));
        draggedCableInsertIndex = 0;
        cableDragStartX = x;
        cableDragStartY = y;
        cableMoved = false;
        onMessage.accept(Lang.get("lune.gui.blueprint.drag_white_point_release_move"));
    }

    private void finishCableDrag() {
        if (draggedCableKey == null) {
            return;
        }
        if (cableMoved) {
            if (task.cableAnchors == null) {
                task.cableAnchors = new java.util.LinkedHashMap<>();
            }
            task.cableAnchors.put(draggedCableKey, draggedCablePreview);
            onChanged.run();
            onMessage.accept(draggedCablePointIndex >= 0
                    ? Lang.get("lune.gui.blueprint.cable_routing_point_moved") : Lang.get("lune.gui.blueprint.cable_routing_point_added"));
        }
        draggedCableKey = null;
        draggedCableBase = null;
        draggedCablePreview = null;
        draggedCableInsertIndex = 0;
        draggedCablePointIndex = -1;
        cableMoved = false;
    }

    private void cancelCableDrag() {
        draggedCableKey = null;
        draggedCableBase = null;
        draggedCablePreview = null;
        draggedCableInsertIndex = 0;
        draggedCablePointIndex = -1;
        cableMoved = false;
    }

    /** Finds the saved white handle nearest the pointer, before ordinary cable hit-testing. */
    private CablePointHit cablePointAt(int x, int y) {
        if (task == null) {
            return null;
        }
        java.util.Map<String, TaskCableRoute> routes = new java.util.LinkedHashMap<>(automaticCableRoutes);
        if (task.cableAnchors != null) routes.putAll(task.cableAnchors);
        for (java.util.Map.Entry<String, TaskCableRoute> entry : routes.entrySet()) {
            int pointIndex = cablePointAt(entry.getKey(), x, y);
            if (pointIndex >= 0) {
                return new CablePointHit(entry.getKey(), pointIndex);
            }
        }
        return null;
    }

    /** Finds the saved route point nearest the pointer for the small white handle action. */
    private int cablePointAt(String key, int x, int y) {
        if (task == null) {
            return -1;
        }
        TaskCableRoute route = task.cableAnchors == null ? automaticCableRoutes.get(key)
                : task.cableAnchors.getOrDefault(key, automaticCableRoutes.get(key));
        if (route == null || route.points == null) {
            return -1;
        }
        double radius = Math.max(8.0, 10.0 / zoom);
        double closest = radius * radius;
        int closestIndex = -1;
        for (int i = 0; i < route.points.size(); i++) {
            TaskCableAnchor point = route.points.get(i);
            if (point == null) {
                continue;
            }
            double distance = Math.pow(point.x - x, 2) + Math.pow(point.y - y, 2);
            if (distance <= closest) {
                closest = distance;
                closestIndex = i;
            }
        }
        return closestIndex;
    }

    /** Removes only the white handle's point, leaving the cable and its other points connected. */
    private boolean removeCablePointAt(int screenX, int screenY) {
        if (task == null || task.cableAnchors == null) {
            return false;
        }
        int x = canvasX(screenX);
        int y = canvasY(screenY);
        java.util.Iterator<java.util.Map.Entry<String, TaskCableRoute>> routes
                = task.cableAnchors.entrySet().iterator();
        while (routes.hasNext()) {
            java.util.Map.Entry<String, TaskCableRoute> entry = routes.next();
            TaskCableRoute route = entry.getValue();
            int pointIndex = cablePointAt(entry.getKey(), x, y);
            if (route == null || route.points == null || pointIndex < 0) {
                continue;
            }
            route.points.remove(pointIndex);
            if (route.points.isEmpty()) {
                routes.remove();
            }
            onChanged.run();
            onMessage.accept(Lang.get("lune.gui.blueprint.cable_routing_point_removed"));
            return true;
        }
        return false;
    }

    /** Builds the live route by retaining every saved point and appending this pull's release. */
    private void updateCablePreview() {
        draggedCablePreview = draggedCableBase == null
                ? new TaskCableRoute()
                : draggedCableBase.copy();
        TaskCableAnchor release = new TaskCableAnchor(canvasX(pointerX), canvasY(pointerY));
        if (draggedCablePointIndex >= 0
                && draggedCablePointIndex < draggedCablePreview.points.size()) {
            draggedCablePreview.points.set(draggedCablePointIndex, release);
        } else {
            draggedCablePreview.insertPoint(draggedCableInsertIndex, release);
        }
    }

    private void removeCableAnchor(String key) {
        if (task != null && task.cableAnchors != null) {
            task.cableAnchors.remove(key);
        }
    }

    private boolean cutWireAt(int x, int y) {
        x = canvasX(x);
        y = canvasY(y);
        for (TaskNode source : task.nodes) {
            if (source.isClockNode() && source.alwaysTargets != null) {
                java.util.Iterator<String> targets = source.alwaysTargets.iterator();
                while (targets.hasNext()) {
                    String targetId = targets.next();
                    TaskNode target = task.nodeById(targetId);
                    String key = TaskCableAnchor.key("always", source.id, targetId);
                    if (target != null && nearWire(outputX(source), whileY(source),
                            inputX(target), targetInputY(target, source.alwaysTargetInputPorts == null
                                    ? 0 : source.alwaysTargetInputPorts.getOrDefault(target.id, 0)), x, y,
                            anchorFor(key))) {
                        targets.remove();
                        if (source.alwaysTargetInputPorts != null) {
                            source.alwaysTargetInputPorts.remove(target.id);
                        }
                        removeCableAnchor(key);
                        onChanged.run();
                        onMessage.accept(Lang.get("lune.gui.blueprint.always_connection_cut"));
                        return true;
                    }
                }
            }
            TaskNode successTarget = task.nodeById(source.onSuccess);
            String successKey = successTarget == null ? null
                    : TaskCableAnchor.key("success", source.id, successTarget.id);
            if (successTarget != null && nearWire(outputX(source), successY(source),
                    inputX(successTarget), targetInputY(successTarget,
                            successTarget.isPulseNode() ? source.successInputPort : -1), x, y,
                    anchorFor(successKey))) {
                source.onSuccess = null;
                removeCableAnchor(successKey);
                onChanged.run();
                onMessage.accept(Lang.get("lune.gui.blueprint.success_wire_cut"));
                return true;
            }
            TaskNode failureTarget = task.nodeById(source.onFailure);
            String failureKey = failureTarget == null ? null
                    : TaskCableAnchor.key("failure", source.id, failureTarget.id);
            if (failureTarget != null && nearWire(outputX(source), failureY(source),
                    inputX(failureTarget), targetInputY(failureTarget,
                            failureTarget.isPulseNode() ? source.failureInputPort : -1), x, y,
                    anchorFor(failureKey))) {
                source.onFailure = null;
                removeCableAnchor(failureKey);
                onChanged.run();
                onMessage.accept(Lang.get("lune.gui.blueprint.failure_wire_cut"));
                return true;
            }
            if (source.whileVisible) {
                TaskNode whileTarget = task.nodeById(source.onWhile);
                String whileKey = whileTarget == null ? null
                        : TaskCableAnchor.key("while", source.id, whileTarget.id);
                if (whileTarget != null && nearWire(outputX(source), whileY(source),
                        inputX(whileTarget), targetInputY(whileTarget,
                                whileTarget.isPulseNode() ? source.whileInputPort : -1), x, y,
                        anchorFor(whileKey))) {
                    source.onWhile = null;
                    removeCableAnchor(whileKey);
                    onChanged.run();
                    onMessage.accept(Lang.get("lune.gui.blueprint.while_wire_cut"));
                    return true;
                }
            }
            if (source.isObserverNode() && source.observedNodeId != null) {
                TaskNode watched = task.nodeById(source.observedNodeId);
                String key = watched == null ? null
                        : TaskCableAnchor.key("observe", watched.id, source.id);
                if (watched != null && nearWire(outputX(watched), nodeY(watched) + HEADER_H / 2,
                        inputX(source), signalInputY(source, 0), x, y, anchorFor(key))) {
                    source.observedNodeId = null;
                    removeCableAnchor(key);
                    onChanged.run();
                    onMessage.accept(Lang.get("lune.gui.blueprint.observer_wire_cut"));
                    return true;
                }
            }
            if (source.isPulseNode() && source.signalLinks != null) {
                java.util.Iterator<TaskSignalLink> signalLinks = source.signalLinks.iterator();
                while (signalLinks.hasNext()) {
                    TaskSignalLink link = signalLinks.next();
                    TaskNode target = link == null ? null : task.nodeById(link.targetNodeId);
                    String key = target == null ? null : TaskCableAnchor.key("signal", source.id,
                            String.valueOf(link.outputPort), target.id,
                            String.valueOf(link.targetPort));
                    if (target != null && nearWire(outputX(source), signalOutputY(source, link.outputPort),
                            inputX(target), targetInputY(target, link.targetPort), x, y,
                            anchorFor(key))) {
                        signalLinks.remove();
                        removeCableAnchor(key);
                        onChanged.run();
                        onMessage.accept(Lang.get("lune.gui.blueprint.pulse_output_wire_cut"));
                        return true;
                    }
                }
            }
        }
        for (TaskNode target : task.nodes) {
            if (target.inputLinks == null) {
                continue;
            }
            for (var entry : target.inputLinks.entrySet()) {
                TaskDataLink link = entry.getValue();
                TaskNode source = link == null ? null : task.nodeById(link.sourceNodeId);
                int sourcePort = source == null ? -1 : exposedOutputs(source).indexOf(link.sourcePort);
                int targetPort = exposedInputs(target).indexOf(entry.getKey());
                String key = source == null || sourcePort < 0 || targetPort < 0 ? null
                        : TaskCableAnchor.key("data", source.id, link.sourcePort,
                                target.id, entry.getKey());
                if (source != null && sourcePort >= 0 && targetPort >= 0
                        && nearWire(outputX(source), dataOutputY(source, sourcePort),
                        inputX(target), dataInputY(target, targetPort), x, y, anchorFor(key))) {
                    target.inputLinks.remove(entry.getKey());
                    removeCableAnchor(key);
                    onChanged.run();
                    onMessage.accept(Lang.get("lune.gui.blueprint.data_wire_cut"));
                    return true;
                }
            }
        }
        return false;
    }

    private boolean nearWire(int x1, int y1, int x2, int y2, int px, int py,
                             TaskCableRoute route) {
        return wireSegmentAt(x1, y1, x2, y2, px, py, route) >= 0;
    }

    /**
     * Returns the point-list insertion index for the exact curved segment under the pointer.
     * Segment zero is before the first saved point; the last segment is after the last point.
     */
    private int wireSegmentAt(int x1, int y1, int x2, int y2, int px, int py,
                              TaskCableRoute route) {
        // Measured against the same spline that is drawn. Sampling each segment's own curve
        // separately, as this used to, meant the line you grabbed was not the line you saw.
        TaskCablePath path = TaskCablePath.of(x1, y1, x2, y2, route);
        int samples = Math.clamp((int) Math.round(path.length() / 4), 16, 192);
        double closestDistance = Double.POSITIVE_INFINITY;
        int closestSegment = -1;
        TaskCablePath.Point last = path.at(0);
        for (int i = 1; i <= samples; i++) {
            double t = i / (double) samples;
            TaskCablePath.Point next = path.at(t);
            double distance = distanceToSegment(px, py, last.x(), last.y(), next.x(), next.y());
            if (distance < closestDistance) {
                closestDistance = distance;
                // The midpoint of the sampled step, so a hit right on a knot lands in the segment
                // the pointer is actually over rather than always in the one after it.
                closestSegment = path.segmentAt(t - 0.5 / samples);
            }
            last = next;
        }
        return closestDistance <= 7.0 ? closestSegment : -1;
    }

    private static double distanceToSegment(double px, double py, double x1, double y1,
                                            double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        if (dx == 0 && dy == 0) {
            return Math.hypot(px - x1, py - y1);
        }
        double t = Math.clamp(((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy), 0.0, 1.0);
        return Math.hypot(px - (x1 + t * dx), py - (y1 + t * dy));
    }

    private void beginWire(TaskNode node, int type) {
        selectedNodes.clear();
        selectedNodes.add(node);
        selected = node;
        onSelect.accept(node);
        wireSource = node;
        wireType = type;
        wireDataPort = null;
        wireSignalPort = -1;
    }

    private void beginDataWire(TaskNode node, String port) {
        selectedNodes.clear();
        selectedNodes.add(node);
        selected = node;
        onSelect.accept(node);
        wireSource = node;
        wireType = WIRE_DATA;
        wireDataPort = port;
        wireSignalPort = -1;
    }

    private void beginSignalWire(TaskNode node, int outputPort) {
        selectedNodes.clear();
        selectedNodes.add(node);
        selected = node;
        onSelect.accept(node);
        wireSource = node;
        wireType = WIRE_SIGNAL;
        wireDataPort = null;
        wireSignalPort = outputPort;
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dragX, double dragY) {
        pointerX = (int) event.x();
        pointerY = (int) event.y();
        if (minimapDragging) {
            centerOnMinimap(pointerX, pointerY);
            return;
        }
        if (draggedCableKey != null) {
            if (Math.abs(pointerX - cableDragStartX) > 2
                    || Math.abs(pointerY - cableDragStartY) > 2) {
                cableMoved = true;
                updateCablePreview();
            }
            return;
        }
        if (wireSource != null) {
            return;
        }
        if (draggedNote != null) {
            int dx = (int) Math.round(dragX / zoom);
            int dy = (int) Math.round(dragY / zoom);
            if (resizingNote) {
                draggedNote.width += dx;
                draggedNote.height += dy;
                draggedNote.clampSize();
            } else {
                draggedNote.x += dx;
                draggedNote.y += dy;
            }
            moved = true;
            return;
        }
        if (draggedGroup != null) {
            draggedGroup.moveBy(task, (int) Math.round(dragX / zoom),
                    (int) Math.round(dragY / zoom));
            moved = true;
            return;
        }
        if (dragged != null) {
            int dx = (int) Math.round(dragX / zoom);
            int dy = (int) Math.round(dragY / zoom);
            for (TaskNode node : selectedNodes) {
                node.editorX += dx;
                node.editorY += dy;
            }
            moved = true;
            followDraggedNode();
        } else if (selecting) {
            selectionMoved |= Math.abs(pointerX - selectionStartX) > 3
                    || Math.abs(pointerY - selectionStartY) > 3;
        } else if (panning) {
            panBy(dragX, dragY);
        }
    }

    /**
     * Carries a middle-button drag, which vanilla never hands a widget itself.
     *
     * <p>A screen passes a drag and a release on to the focused widget for the left button only,
     * so the middle press that starts a pan used to be the last this canvas heard of it: the view
     * stayed put while the pointer moved, and the pan was still armed when the next left drag
     * came along. {@link LuneScreen} now forwards the rest of the gesture to the widget the press
     * landed on, and this is where it arrives.</p>
     */
    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (event.button() == InputConstants.MOUSE_BUTTON_MIDDLE) {
            if (!middlePanning) {
                return false;
            }
            pointerX = (int) event.x();
            pointerY = (int) event.y();
            panBy(dragX, dragY);
            if (Math.abs(event.x() - middlePressX) > CLICK_SLOP
                    || Math.abs(event.y() - middlePressY) > CLICK_SLOP) {
                middleMoved = true;
            }
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == InputConstants.MOUSE_BUTTON_MIDDLE) {
            boolean wasPanning = middlePanning;
            middlePanning = false;
            if (wasPanning) {
                endMiddlePress();
            }
            return wasPanning;
        }
        return super.mouseReleased(event);
    }

    /** Moves the view with the pointer, keeping the fraction of a pixel no single event made. */
    private void panBy(double dragX, double dragY) {
        panCarryX += dragX;
        panCarryY += dragY;
        int stepX = (int) panCarryX;
        int stepY = (int) panCarryY;
        panX += stepX;
        panY += stepY;
        panCarryX -= stepX;
        panCarryY -= stepY;
    }

    /**
     * Pans the canvas when a dragged card reaches the edge.
     *
     * <p>Dragging a card to the rim previously just stopped: the pointer left the panel, the card
     * stayed put, and reaching anywhere further meant dropping it, panning, and picking it up
     * again. The view now gives way instead, faster the further past the edge the pointer goes,
     * so a card can be carried across a task of any size in one gesture.</p>
     */
    private void followDraggedNode() {
        int speedX = edgePush(pointerX - getX(), getWidth());
        int speedY = edgePush(pointerY - getY(), getHeight());
        if (speedX == 0 && speedY == 0) {
            return;
        }
        panX += speedX;
        panY += speedY;
        // The card is being carried by the pointer, which has not moved in screen terms, so it
        // has to be moved by the same amount the world under it just did - otherwise panning
        // would slide the card out from under the cursor.
        int dx = (int) Math.round(-speedX / zoom);
        int dy = (int) Math.round(-speedY / zoom);
        for (TaskNode node : selectedNodes) {
            node.editorX += dx;
            node.editorY += dy;
        }
    }

    /** How hard to push the view when the pointer is within the margin, or outside it entirely. */
    private int edgePush(int position, int size) {
        if (position < EDGE_PAN_MARGIN) {
            return Math.min(EDGE_PAN_MAX, (EDGE_PAN_MARGIN - position) / 3 + 1);
        }
        if (position > size - EDGE_PAN_MARGIN) {
            return -Math.min(EDGE_PAN_MAX, (position - (size - EDGE_PAN_MARGIN)) / 3 + 1);
        }
        return 0;
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        pointerX = (int) event.x();
        pointerY = (int) event.y();
        if (minimapDragging) {
            minimapDragging = false;
            return;
        }
        if (draggedNote != null || draggedGroup != null) {
            if (moved) {
                onChanged.run();
            }
            draggedNote = null;
            draggedGroup = null;
            resizingNote = false;
            moved = false;
            return;
        }
        if (draggedCableKey != null) {
            if (Math.abs(pointerX - cableDragStartX) > 2
                    || Math.abs(pointerY - cableDragStartY) > 2) {
                cableMoved = true;
                updateCablePreview();
            }
            finishCableDrag();
            return;
        }
        if (wireSource != null) {
            if (wireType == WIRE_DATA) {
                DataInputHit target = dataInputAt(canvasX(pointerX), canvasY(pointerY));
                if (target != null && !target.node().id.equals(wireSource.id)) {
                    if (target.node().inputLinks == null) {
                        target.node().inputLinks = new java.util.LinkedHashMap<>();
                    }
                    if (!compatibleDataPorts(wireSource, wireDataPort,
                            target.node(), target.parameterId())) {
                        onMessage.accept(Lang.get("lune.gui.blueprint.data_ports_must_use_same_value_type"));
                        wireSource = null;
                        wireDataPort = null;
                        wireSignalPort = -1;
                        dragged = null;
                        moved = false;
                        panning = false;
                        return;
                    }
                    String newKey = TaskCableAnchor.key("data", wireSource.id, wireDataPort,
                            target.node().id, target.parameterId());
                    TaskDataLink old = target.node().inputLinks.put(target.parameterId(),
                            new TaskDataLink(wireSource.id, wireDataPort));
                    if (old != null) {
                        String oldKey = TaskCableAnchor.key("data", old.sourceNodeId, old.sourcePort,
                                target.node().id, target.parameterId());
                        if (!oldKey.equals(newKey)) {
                            removeCableAnchor(oldKey);
                        }
                    }
                    onChanged.run();
                    onMessage.accept(Lang.get("lune.gui.blueprint.connected", (old == null ? Lang.get("lune.gui.blueprint.data") : Lang.get("lune.gui.blueprint.data_wire_replaced")), parameterLabel(target.node(), target.parameterId())));
                } else if (target == null) {
                    onMessage.accept(Lang.get("lune.gui.blueprint.drop_data_wire_exposed_input"));
                }
                wireSource = null;
                wireDataPort = null;
                wireSignalPort = -1;
                dragged = null;
                moved = false;
                panning = false;
                return;
            }
            ExecInputHit input = execInputAt(canvasX(pointerX), canvasY(pointerY));
            TaskNode target = input == null ? null : input.node();
            int targetPort = input == null ? -1 : input.port();
            if (wireType == WIRE_SIGNAL) {
                if (target == null || target.isSourceNode() || target.id.equals(wireSource.id)) {
                    onMessage.accept(Lang.get("lune.gui.blueprint.drop_pulse_output_command_or_pulse_input"));
                } else {
                    if (wireSource.signalLinks == null) {
                        wireSource.signalLinks = new java.util.ArrayList<>();
                    }
                    int relayTargetPort = target.isSignalRelayNode() ? targetPort : -1;
                    boolean duplicate = wireSource.signalLinks.stream().anyMatch(link ->
                            link != null && link.outputPort == wireSignalPort
                                    && target.id.equals(link.targetNodeId)
                                    && link.targetPort == relayTargetPort);
                    if (!duplicate) {
                        wireSource.signalLinks.add(new TaskSignalLink(wireSignalPort,
                                target.id, relayTargetPort));
                        onChanged.run();
                        onMessage.accept(Lang.get("lune.gui.blueprint.connected_2", (wireSource.isTimerNode() ? Lang.get("lune.gui.blueprint.timer_output") : Lang.get("lune.gui.blueprint.relay_output")), (wireSignalPort + 1), nodeName(target)));
                    }
                }
                wireSource = null;
                wireDataPort = null;
                wireSignalPort = -1;
                wireSignalPort = -1;
                dragged = null;
                moved = false;
                panning = false;
                return;
            }
            if (wireType == WIRE_WHILE && wireSource.isClockNode()) {
                if (target == null || target.isClockNode()) {
                    onMessage.accept(Lang.get("lune.gui.blueprint.drop_commands_pin", nodeName(wireSource)));
                } else {
                    if (wireSource.alwaysTargets == null) {
                        wireSource.alwaysTargets = new java.util.LinkedHashSet<>();
                    }
                    if (wireSource.alwaysTargetInputPorts == null) {
                        wireSource.alwaysTargetInputPorts = new java.util.LinkedHashMap<>();
                    }
                    if (wireSource.alwaysTargets.add(target.id)) {
                        wireSource.alwaysTargetInputPorts.put(target.id,
                                target.isSignalRelayNode() ? targetPort : 0);
                        onChanged.run();
                        onMessage.accept(Lang.get("lune.gui.blueprint.connected", nodeName(wireSource), nodeName(target)));
                    }
                }
                wireSource = null;
                wireDataPort = null;
                wireSignalPort = -1;
                dragged = null;
                moved = false;
                panning = false;
                return;
            }
            if (target != null && target.isObserverNode()) {
                if (target.id.equals(wireSource.id)) {
                    onMessage.accept(Lang.get("lune.gui.blueprint.observer_cannot_watch_itself"));
                } else {
                    String oldObserved = target.observedNodeId;
                    target.observedNodeId = wireSource.id;
                    if (oldObserved != null && !oldObserved.equals(wireSource.id)) {
                        removeCableAnchor(TaskCableAnchor.key("observe", oldObserved, target.id));
                    }
                    onChanged.run();
                    onMessage.accept(Lang.get("lune.gui.blueprint.observer_now_watches", nodeName(wireSource)));
                }
                wireSource = null;
                wireDataPort = null;
                wireSignalPort = -1;
                dragged = null;
                moved = false;
                panning = false;
                return;
            }
            if (target != null && target.isSourceNode()) {
                onMessage.accept(Lang.get("lune.gui.blueprint.start_always_source_nodes_connect"));
                wireSource = null;
                wireDataPort = null;
                wireSignalPort = -1;
                dragged = null;
                moved = false;
                panning = false;
                return;
            }
            if (wireType == WIRE_WHILE && target != null && target.isPulseNode()) {
                onMessage.accept(Lang.get("lune.gui.blueprint.use_success_or_fail_send_pulse_into_node"));
                wireSource = null;
                wireDataPort = null;
                wireSignalPort = -1;
                dragged = null;
                moved = false;
                panning = false;
                return;
            }
            if (target == null) {
                // A left-drag is for connecting or rerouting. Releasing in empty space cancels
                // the gesture; cutting is deliberately the explicit right-click action shown in
                // the canvas hint, so a click or an imprecise release cannot destroy a wire.
                onMessage.accept(Lang.get("lune.gui.blueprint.cable_drag_cancelled_release_input"));
                wireSource = null;
                wireDataPort = null;
                wireSignalPort = -1;
                dragged = null;
                moved = false;
                panning = false;
                return;
            }
            String oldTarget = switch (wireType) {
                case WIRE_FAILURE -> wireSource.onFailure;
                case WIRE_WHILE -> wireSource.onWhile;
                default -> wireSource.onSuccess;
            };
            String newTarget = target.id;
            if (oldTarget != null && !oldTarget.equals(newTarget)) {
                removeCableAnchor(TaskCableAnchor.key(wireType == WIRE_FAILURE ? "failure"
                        : wireType == WIRE_WHILE ? "while" : "success", wireSource.id, oldTarget));
            }
            switch (wireType) {
                case WIRE_FAILURE -> wireSource.onFailure = newTarget;
                case WIRE_WHILE -> wireSource.onWhile = newTarget;
                default -> wireSource.onSuccess = newTarget;
            }
            if (target != null && target.isSignalRelayNode()) {
                switch (wireType) {
                    case WIRE_FAILURE -> wireSource.failureInputPort = targetPort;
                    case WIRE_WHILE -> wireSource.whileInputPort = targetPort;
                    default -> wireSource.successInputPort = targetPort;
                }
            }
            if (wireType == WIRE_WHILE && target != null) {
                // While is a live companion for the source step, not a finite child circuit.
                // Connecting the edge is also the moment the optional output becomes meaningful;
                // keep it exposed so the new cable is visible immediately and after saving.
                wireSource.whileVisible = true;
                target.repeat = 0;
            }
            if (!java.util.Objects.equals(oldTarget, newTarget)) {
                onChanged.run();
                String label = wireType == WIRE_FAILURE ? Lang.get("lune.gui.blueprint.failure")
                        : wireType == WIRE_WHILE ? Lang.get("lune.gui.blueprint.while") : Lang.get("lune.gui.blueprint.success");
                onMessage.accept(Lang.get("lune.gui.blueprint.connected", label, nodeName(target)));
            }
            wireSource = null;
            wireDataPort = null;
            wireSignalPort = -1;
            wireSignalPort = -1;
        }
        if (selecting) {
            applyMarqueeSelection();
        }
        if (dragged != null && moved) {
            onChanged.run();
        }
        dragged = null;
        moved = false;
        selecting = false;
        selectionMoved = false;
        panning = false;
    }

    private void applyMarqueeSelection() {
        if (!selectionMoved || task == null) {
            return;
        }
        int left = Math.min(selectionStartX, pointerX);
        int right = Math.max(selectionStartX, pointerX);
        int top = Math.min(selectionStartY, pointerY);
        int bottom = Math.max(selectionStartY, pointerY);
        double canvasLeft = canvasX(left);
        double canvasRight = canvasX(right);
        double canvasTop = canvasY(top);
        double canvasBottom = canvasY(bottom);
        if (!selectionAdditive) {
            selectedNodes.clear();
        }
        for (TaskNode node : task.nodes) {
            if (collapsedHolder(node) != null) {
                continue;
            }
            double nodeLeft = nodeX(node);
            double nodeRight = nodeLeft + NODE_W;
            double nodeTop = nodeY(node);
            double nodeBottom = nodeTop + nodeHeight(node);
            if (nodeLeft >= canvasLeft && nodeRight <= canvasRight
                    && nodeTop >= canvasTop && nodeBottom <= canvasBottom) {
                selectedNodes.add(node);
            }
        }
        selectAnchor();
        onSelect.accept(selected);
    }

    private void drawSelection(GuiGraphicsExtractor extractor) {
        int left = Math.max(getX(), Math.min(selectionStartX, pointerX));
        int right = Math.min(getX() + getWidth(), Math.max(selectionStartX, pointerX));
        int top = Math.max(getY(), Math.min(selectionStartY, pointerY));
        int bottom = Math.min(getY() + getHeight(), Math.max(selectionStartY, pointerY));
        if (right <= left || bottom <= top) {
            return;
        }
        extractor.fill(left, top, right, bottom, 0x243F8FFF);
        extractor.outline(left, top, right - left, bottom - top, LuneScreen.ACCENT_HOVER);
    }

    private void selectAnchor() {
        selected = selectedNodes.isEmpty() ? null : selectedNodes.iterator().next();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (task == null) {
            return false;
        }
        if (editingNote != null) {
            return handleNoteKey(event);
        }
        if (editingGroup != null) {
            return handleTitleKey(event);
        }
        if (editingNode != null) {
            // While a number is being typed the chip owns the keyboard. Backspace especially:
            // it is also the delete-card binding, and deleting the card you are editing because
            // you mistyped a digit would be a memorable way to lose work.
            if (event.key() == InputConstants.KEY_BACKSPACE) {
                if (!editBuffer.isEmpty()) {
                    editBuffer = editBuffer.substring(0, editBuffer.length() - 1);
                }
                return true;
            }
            if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
                commitChipEdit();
                return true;
            }
            if (event.key() == InputConstants.KEY_ESCAPE) {
                editingNode = null;
                editBuffer = "";
                return true;
            }
            return true;
        }
        boolean ctrl = (event.modifiers() & InputConstants.MOD_CONTROL) != 0;
        if (searchOpen) {
            if (handleFindKey(event)) {
                return true;
            }
            if (!ctrl) {
                // The open find bar owns every plain keystroke: while it is up, 'n' is a letter of
                // the query, not the shortcut that drops a note on the canvas behind it. Chords
                // fall through, so Ctrl+F still closes it and Ctrl+V still pastes.
                return true;
            }
        }
        if (ctrl && (event.key() == InputConstants.KEY_0 || event.key() == InputConstants.KEY_NUMPAD0)) {
            resetView();
            onMessage.accept(Lang.get("lune.gui.blueprint.blueprint_view_reset"));
            return true;
        }
        if (ctrl && event.key() == InputConstants.KEY_A) {
            selectedNodes.clear();
            selectedNodes.addAll(task.nodes);
            selectAnchor();
            onSelect.accept(selected);
            return true;
        }
        if (event.key() == InputConstants.KEY_DELETE || event.key() == InputConstants.KEY_BACKSPACE) {
            if (selectedNote != null && selectedNodes.isEmpty()) {
                deleteSelectedNote();
                return true;
            }
            if (!selectedNodes.isEmpty()) {
                onDelete.accept(List.copyOf(selectedNodes));
                return true;
            }
        }
        if (event.key() == InputConstants.KEY_ESCAPE
                && (wireSource != null || draggedCableKey != null || selecting || minimapDragging)) {
            wireSource = null;
            wireDataPort = null;
            selecting = false;
            minimapDragging = false;
            cancelCableDrag();
            dragged = null;
            panning = false;
            return true;
        }
        if (event.key() == InputConstants.KEY_ESCAPE && tracedCable != null) {
            clearTrace();
            return true;
        }
        return handleShortcut(event, ctrl);
    }

    /**
     * The editor shortcuts that are not about one card.
     *
     * <p>Plain letters rather than chords for the two that are used constantly - N for a note, F
     * to frame what is selected. The canvas only sees a keystroke while it holds the focus, and
     * every text box on the tab is a widget of its own, so there is nothing for them to collide
     * with.</p>
     */
    private boolean handleShortcut(KeyEvent event, boolean ctrl) {
        int key = event.key();
        boolean shift = shiftDown();
        if (ctrl && key == InputConstants.KEY_F) {
            toggleFind();
            return true;
        }
        if (ctrl && key == InputConstants.KEY_G) {
            if (shift) {
                ungroupSelection();
            } else {
                groupSelection();
            }
            return true;
        }
        if (ctrl && key == InputConstants.KEY_D) {
            duplicateSelection();
            return true;
        }
        if (ctrl && key == InputConstants.KEY_C) {
            copySelection();
            return true;
        }
        if (ctrl && key == InputConstants.KEY_V) {
            paste(pointerX, pointerY);
            return true;
        }
        if (key == InputConstants.KEY_F9) {
            if (shift) {
                clearBreakpoints();
            } else {
                toggleBreakpoints();
            }
            return true;
        }
        if (!ctrl && key == InputConstants.KEY_N) {
            addNoteAt(pointerX, pointerY);
            return true;
        }
        if (!ctrl && key == InputConstants.KEY_F) {
            frameSelection();
            return true;
        }
        if (key == InputConstants.KEY_HOME) {
            fitAll();
            return true;
        }
        return false;
    }

    private void deleteSelectedNote() {
        if (task == null || task.notes == null || selectedNote == null) {
            return;
        }
        task.notes.remove(selectedNote);
        selectedNote = null;
        editingNote = null;
        onChanged.run();
        onMessage.accept(Lang.get("lune.gui.blueprint.note_deleted"));
    }

    /** While a note is open it owns the keyboard, exactly as the repeat chip does. */
    private boolean handleNoteKey(KeyEvent event) {
        int key = event.key();
        if (key == InputConstants.KEY_ESCAPE) {
            commitNoteEdit();
            return true;
        }
        if (key == InputConstants.KEY_BACKSPACE) {
            if (!editingNote.text.isEmpty()) {
                editingNote.text = editingNote.text.substring(0, editingNote.text.length() - 1);
            }
            return true;
        }
        if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
            // A note is a paragraph, so Return is a new line and Escape is "done". Return as
            // "done" would make a two-line note impossible to write.
            if (editingNote.text.length() < TaskNote.MAX_TEXT) {
                editingNote.text += "\n";
            }
            return true;
        }
        return true;
    }

    private boolean handleTitleKey(KeyEvent event) {
        int key = event.key();
        if (key == InputConstants.KEY_ESCAPE || key == InputConstants.KEY_RETURN
                || key == InputConstants.KEY_NUMPADENTER) {
            commitTitleEdit();
            return true;
        }
        if (key == InputConstants.KEY_BACKSPACE) {
            if (!titleBuffer.isEmpty()) {
                titleBuffer = titleBuffer.substring(0, titleBuffer.length() - 1);
            }
            return true;
        }
        return true;
    }

    private boolean handleFindKey(KeyEvent event) {
        int key = event.key();
        if (key == InputConstants.KEY_ESCAPE) {
            closeFind();
            return true;
        }
        if (key == InputConstants.KEY_BACKSPACE) {
            if (!searchQuery.isEmpty()) {
                searchQuery = searchQuery.substring(0, searchQuery.length() - 1);
                refreshFind();
            }
            return true;
        }
        if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER
                || key == InputConstants.KEY_DOWN) {
            stepFind(shiftDown() && key != InputConstants.KEY_DOWN ? -1 : 1);
            return true;
        }
        if (key == InputConstants.KEY_UP) {
            stepFind(-1);
            return true;
        }
        return false;
    }

    /** The find bar sits over the top-left of the canvas, so it has to claim its own clicks. */
    private boolean findBarContains(int screenX, int screenY) {
        return searchOpen && screenX >= getX() + 6
                && screenX < getX() + 6 + Math.min(FIND_BAR_W, getWidth() - 12)
                && screenY >= getY() + 6 && screenY < getY() + 6 + FIND_BAR_H;
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        int codepoint = event.codepoint();
        if (editingNote != null) {
            if (codepoint >= ' ' && editingNote.text.length() < TaskNote.MAX_TEXT) {
                editingNote.text += (char) codepoint;
            }
            return true;
        }
        if (editingGroup != null) {
            if (codepoint >= ' ' && titleBuffer.length() < TaskGroup.MAX_TITLE) {
                titleBuffer += (char) codepoint;
            }
            return true;
        }
        if (editingNode != null) {
            if (codepoint >= '0' && codepoint <= '9' && editBuffer.length() < 7) {
                editBuffer += (char) codepoint;
            }
            return true;
        }
        if (searchOpen) {
            if (codepoint >= ' ' && searchQuery.length() < 48) {
                searchQuery += (char) codepoint;
                refreshFind();
            }
            return true;
        }
        return false;
    }

    private boolean controlDown() {
        return Minecraft.getInstance().hasControlDown();
    }

    private TaskNode nodeAt(int x, int y) {
        if (task == null) {
            return null;
        }
        for (int i = task.nodes.size() - 1; i >= 0; i--) {
            TaskNode node = task.nodes.get(i);
            // A card inside a closed frame is not on the canvas, so nothing on the canvas can be
            // pointing at it - not a click, not a hover, and not the end of a dragged cable.
            if (contains(node, x, y) && collapsedHolder(node) == null) {
                return node;
            }
        }
        return null;
    }

    private ExecInputHit execInputAt(int x, int y) {
        if (task == null) {
            return null;
        }
        for (TaskNode node : task.nodes) {
            if (collapsedHolder(node) != null) {
                continue;
            }
            if (node.isObserverNode()) {
                // A source card with an input: the Watch pin takes a wire even though no signal
                // ever travels down it.
                if (near(x, y, inputX(node), signalInputY(node, 0))) {
                    return new ExecInputHit(node, 0);
                }
                continue;
            }
            if (node.isSourceNode()) {
                continue;
            }
            if (node.isPulseNode()) {
                int inputCount = node.isSignalRelayNode() ? node.signalInputCount : 1;
                for (int port = 0; port < inputCount; port++) {
                    if (near(x, y, inputX(node), signalInputY(node, port))) {
                        return new ExecInputHit(node, port);
                    }
                }
                continue;
            }
            if (near(x, y, inputX(node), inputY(node))) {
                return new ExecInputHit(node, -1);
            }
        }
        return null;
    }

    private DataInputHit dataInputAt(int x, int y) {
        if (task == null) {
            return null;
        }
        for (TaskNode node : task.nodes) {
            if (collapsedHolder(node) != null) {
                continue;
            }
            List<String> inputs = exposedInputs(node);
            for (int i = 0; i < inputs.size(); i++) {
                if (near(x, y, inputX(node), dataInputY(node, i))) {
                    return new DataInputHit(node, inputs.get(i));
                }
            }
        }
        return null;
    }

    private boolean contains(TaskNode node, int x, int y) {
        return x >= nodeX(node) && x < nodeX(node) + NODE_W
                && y >= nodeY(node) && y < nodeY(node) + nodeHeight(node);
    }

    private boolean near(int x, int y, int pinX, int pinY) {
        return Math.abs(x - pinX) <= PIN_RADIUS && Math.abs(y - pinY) <= PIN_RADIUS;
    }

    private boolean inside(int x, int y) {
        double left = -panX / zoom;
        double top = -panY / zoom;
        double right = (getWidth() - panX) / zoom;
        double bottom = (getHeight() - panY) / zoom;
        return x >= left && x < right && y >= top && y < bottom;
    }

    private int canvasX(double screenX) {
        return (int) Math.round((screenX - getX() - panX) / zoom);
    }

    private int canvasY(double screenY) {
        return (int) Math.round((screenY - getY() - panY) / zoom);
    }

    private int screenNodeY(TaskNode node) {
        return getY() + panY + Math.round((node.editorY == null ? 0 : node.editorY) * zoom);
    }

    private int nodeX(TaskNode node) {
        return node.editorX == null ? 0 : node.editorX;
    }

    private int nodeY(TaskNode node) {
        return node.editorY == null ? 0 : node.editorY;
    }

    private int inputX(TaskNode node) {
        return nodeX(node);
    }

    private int inputY(TaskNode node) {
        return nodeY(node) + TaskCanvas.INPUT_OFFSET_Y;
    }

    private int outputX(TaskNode node) {
        return nodeX(node) + NODE_W;
    }

    private int successY(TaskNode node) {
        return nodeY(node) + TaskCanvas.SUCCESS_OFFSET_Y;
    }

    private int failureY(TaskNode node) {
        return nodeY(node) + TaskCanvas.FAILURE_OFFSET_Y;
    }

    private int whileY(TaskNode node) {
        return TaskCanvas.whileY(node);
    }

    private int signalInputY(TaskNode node, int index) {
        return nodeY(node) + 34 + index * 14;
    }

    private int signalOutputY(TaskNode node, int index) {
        return nodeY(node) + 34 + index * 14;
    }

    private int targetInputY(TaskNode node, int port) {
        if (node.isPulseNode()) {
            int maxPort = node.isSignalRelayNode() ? Math.max(0, node.signalInputCount - 1) : 0;
            return signalInputY(node, Math.clamp(port < 0 ? 0 : port, 0, maxPort));
        }
        return inputY(node);
    }

    private int dataInputY(TaskNode node, int index) {
        return nodeY(node) + dataStartY(node) + index * DATA_ROW_HEIGHT;
    }

    private int dataOutputY(TaskNode node, int index) {
        return nodeY(node) + dataStartY(node) + index * DATA_ROW_HEIGHT;
    }

    private int dataStartY(TaskNode node) {
        return DATA_START_Y - (supportsWhilePort(node) && !node.whileVisible
                ? WHILE_ROW_HEIGHT : 0);
    }

    private int nodeHeight(TaskNode node) {
        if (node.isSourceNode()) {
            return 44;
        }
        if (node.isSignalRelayNode()) {
            return Math.max(NODE_H, 48 + Math.max(node.signalInputCount, node.signalOutputCount) * 14);
        }
        if (node.isTimerNode()) {
            return NODE_H;
        }
        int rows = Math.max(exposedInputs(node).size(), exposedOutputs(node).size());
        int baseHeight = supportsWhilePort(node) && node.whileVisible
                ? NODE_H : NODE_H_WITHOUT_WHILE;
        int height = rows == 0
                ? baseHeight : dataStartY(node) + rows * DATA_ROW_HEIGHT + DATA_FOOTER_GAP;
        // A card with numbers to show grows a row for them at its foot, under everything that is
        // positioned from the top - pins, labels, data ports - so none of those has to know.
        return statsFor(node) == null ? height : height + STATS_ROW_HEIGHT;
    }

    private int minimapX() {
        return getX() + getWidth() - MINIMAP_WIDTH - MINIMAP_MARGIN;
    }

    private int minimapY() {
        return getY() + getHeight() - MINIMAP_HEIGHT - MINIMAP_MARGIN - 12;
    }

    private int minimapMapX() {
        return minimapX() + MINIMAP_PADDING;
    }

    private int minimapMapY() {
        return minimapY() + MINIMAP_HEADER;
    }

    private int minimapMapWidth() {
        return MINIMAP_WIDTH - MINIMAP_PADDING * 2;
    }

    private int minimapMapHeight() {
        return MINIMAP_HEIGHT - MINIMAP_HEADER - MINIMAP_PADDING;
    }

    private boolean minimapContains(double x, double y) {
        return x >= minimapX() && x < minimapX() + MINIMAP_WIDTH
                && y >= minimapY() && y < minimapY() + MINIMAP_HEIGHT;
    }

    private MinimapBounds minimapBounds() {
        if (task == null || task.nodes.isEmpty()) {
            return new MinimapBounds(0, 0, getWidth(), getHeight());
        }
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (TaskNode node : task.nodes) {
            minX = Math.min(minX, nodeX(node));
            minY = Math.min(minY, nodeY(node));
            maxX = Math.max(maxX, nodeX(node) + NODE_W);
            maxY = Math.max(maxY, nodeY(node) + nodeHeight(node));
        }
        if (task.notes != null) {
            for (TaskNote note : task.notes) {
                minX = Math.min(minX, note.x);
                minY = Math.min(minY, note.y);
                maxX = Math.max(maxX, note.right());
                maxY = Math.max(maxY, note.bottom());
            }
        }
        if (task.groups != null) {
            for (TaskGroup group : task.groups) {
                minX = Math.min(minX, group.x);
                minY = Math.min(minY, group.y - TaskGroup.HEADER_HEIGHT);
                maxX = Math.max(maxX, group.right());
                maxY = Math.max(maxY, group.bottom());
            }
        }
        java.util.List<TaskCableRoute> visibleRoutes = new java.util.ArrayList<>(automaticCableRoutes.values());
        if (task.cableAnchors != null) visibleRoutes.addAll(task.cableAnchors.values());
        {
            for (TaskCableRoute route : visibleRoutes) {
                if (route == null || route.points == null) {
                    continue;
                }
                for (TaskCableAnchor point : route.points) {
                    if (point == null) {
                        continue;
                    }
                    minX = Math.min(minX, point.x);
                    minY = Math.min(minY, point.y);
                    maxX = Math.max(maxX, point.x);
                    maxY = Math.max(maxY, point.y);
                }
            }
        }

        // Keep the visible viewport on the map too, so panning into empty space remains useful.
        minX = Math.min(minX, -panX / zoom);
        minY = Math.min(minY, -panY / zoom);
        maxX = Math.max(maxX, (getWidth() - panX) / zoom);
        maxY = Math.max(maxY, (getHeight() - panY) / zoom);
        double padding = Math.max(24, Math.max(maxX - minX, maxY - minY) * 0.08);
        return new MinimapBounds(minX - padding, minY - padding, maxX + padding, maxY + padding);
    }

    private void centerOnMinimap(double screenX, double screenY) {
        MinimapBounds bounds = minimapBounds();
        double scale = minimapScale(bounds);
        double mapX = Math.clamp(screenX, minimapMapX(), minimapMapX() + minimapMapWidth());
        double mapY = Math.clamp(screenY, minimapMapY(), minimapMapY() + minimapMapHeight());
        double logicalX = bounds.minX + (mapX - minimapMapX()) / scale;
        double logicalY = bounds.minY + (mapY - minimapMapY()) / scale;
        panX = (int) Math.round(getWidth() / 2.0 - logicalX * zoom);
        panY = (int) Math.round(getHeight() / 2.0 - logicalY * zoom);
    }

    private double minimapScale(MinimapBounds bounds) {
        return Math.min(minimapMapWidth() / Math.max(1.0, bounds.width()),
                minimapMapHeight() / Math.max(1.0, bounds.height()));
    }

    private int minimapX(MinimapBounds bounds, double logicalX, double scale) {
        return minimapMapX() + (int) Math.round((logicalX - bounds.minX) * scale);
    }

    private int minimapY(MinimapBounds bounds, double logicalY, double scale) {
        return minimapMapY() + (int) Math.round((logicalY - bounds.minY) * scale);
    }

    private void drawLegacyAlwaysSource(GuiGraphicsExtractor extractor) {
        if (task == null || task.onWhile == null
                || task.nodes.stream().anyMatch(TaskNode::isAlwaysNode)) {
            return;
        }
        TaskNode target = task.nodeById(task.onWhile);
        if (target == null) {
            return;
        }
        int x = 6;
        int y = 5;
        int width = 56;
        int height = 18;
        int sourceX = x + width;
        int sourceY = y + height / 2;
        drawWire(extractor, sourceX, sourceY, inputX(target), inputY(target), pinWhile);
        extractor.fill(x, y, x + width, y + height, 0xD0202028);
        extractor.outline(x, y, width, height, pinWhile);
        extractor.textRenderer().accept(x + 6, y + 5,
                Component.literal(Lang.get("lune.gui.tasks.always")).withColor(LuneScreen.TEXT));
        extractor.fill(x + width - 5, y + 6, x + width + 1, y + 12, pinWhile);
    }

    private void drawMinimap(GuiGraphicsExtractor extractor) {
        if (task == null || task.nodes.isEmpty()) {
            return;
        }
        int x = minimapX();
        int y = minimapY();
        extractor.fill(x, y, x + MINIMAP_WIDTH, y + MINIMAP_HEIGHT, 0xD0101018);
        extractor.outline(x, y, MINIMAP_WIDTH, MINIMAP_HEIGHT, 0xFF66758C);
        extractor.fill(x, y, x + MINIMAP_WIDTH, y + MINIMAP_HEADER, 0xE02A405C);
        extractor.textRenderer().accept(x + 6, y + 4,
                Component.literal(Lang.get("lune.gui.blueprint.map")).withColor(0xFFFFFFFF));

        MinimapBounds bounds = minimapBounds();
        double scale = minimapScale(bounds);
        int mapLeft = minimapMapX();
        int mapTop = minimapMapY();
        int mapRight = mapLeft + minimapMapWidth();
        int mapBottom = mapTop + minimapMapHeight();

        for (TaskNode source : task.nodes) {
            if (source.isClockNode() && source.alwaysTargets != null) {
                for (String targetId : source.alwaysTargets) {
                    TaskNode target = task.nodeById(targetId);
                    if (target != null) {
                        drawMinimapEdge(extractor, bounds, scale, outputX(source), whileY(source),
                                target, pinWhile, source.alwaysTargetInputPorts == null
                                        ? 0 : source.alwaysTargetInputPorts.getOrDefault(target.id, 0),
                                anchorFor(TaskCableAnchor.key("always", source.id, target.id)));
                    }
                }
            }
            TaskNode successTarget = task.nodeById(source.onSuccess);
            drawMinimapEdge(extractor, bounds, scale, outputX(source), successY(source),
                    successTarget, pinSuccess,
                    successTarget != null && successTarget.isPulseNode() ? source.successInputPort : -1,
                    successTarget == null ? null
                            : anchorFor(TaskCableAnchor.key("success", source.id, successTarget.id)));
            TaskNode failureTarget = task.nodeById(source.onFailure);
            drawMinimapEdge(extractor, bounds, scale, outputX(source), failureY(source),
                    failureTarget, pinFailure,
                    failureTarget != null && failureTarget.isPulseNode() ? source.failureInputPort : -1,
                    failureTarget == null ? null
                            : anchorFor(TaskCableAnchor.key("failure", source.id, failureTarget.id)));
            if (!source.isClockNode() && source.whileVisible) {
                TaskNode whileTarget = task.nodeById(source.onWhile);
                drawMinimapEdge(extractor, bounds, scale, outputX(source), whileY(source),
                        whileTarget, pinWhile,
                        whileTarget != null && whileTarget.isPulseNode() ? source.whileInputPort : -1,
                        whileTarget == null ? null
                                : anchorFor(TaskCableAnchor.key("while", source.id, whileTarget.id)));
            }
            if (source.isPulseNode() && source.signalLinks != null) {
                for (TaskSignalLink link : source.signalLinks) {
                    if (link == null) {
                        continue;
                    }
                    TaskNode target = task.nodeById(link.targetNodeId);
                    if (target != null) {
                        drawMinimapEdge(extractor, bounds, scale,
                                outputX(source), signalOutputY(source, link.outputPort), target, pinSignal,
                                link.targetPort,
                                anchorFor(TaskCableAnchor.key("signal", source.id,
                                        String.valueOf(link.outputPort), target.id,
                                        String.valueOf(link.targetPort))));
                    }
                }
            }
            if (source.isObserverNode() && source.observedNodeId != null) {
                TaskNode watched = task.nodeById(source.observedNodeId);
                if (watched != null) {
                    drawMinimapEdge(extractor, bounds, scale,
                            outputX(watched), nodeY(watched) + HEADER_H / 2,
                            source, pinSignal, 0,
                            anchorFor(TaskCableAnchor.key("observe", watched.id, source.id)));
                }
            }
            if (source.inputLinks != null) {
                for (var entry : source.inputLinks.entrySet()) {
                    TaskDataLink link = entry.getValue();
                    TaskNode dataSource = link == null ? null : task.nodeById(link.sourceNodeId);
                    int sourcePort = dataSource == null ? -1
                            : exposedOutputs(dataSource).indexOf(link.sourcePort);
                    int targetPort = exposedInputs(source).indexOf(entry.getKey());
                    if (dataSource != null && sourcePort >= 0 && targetPort >= 0) {
                        drawMinimapRoute(extractor, bounds, scale,
                                outputX(dataSource), dataOutputY(dataSource, sourcePort),
                                inputX(source), dataInputY(source, targetPort), pinData,
                                anchorFor(TaskCableAnchor.key("data", dataSource.id, link.sourcePort,
                                        source.id, entry.getKey())));
                    }
                }
            }
        }

        // Under the cards, in the same order the canvas draws them, so the map is a small copy of
        // the picture rather than a second diagram with its own conventions.
        if (task.groups != null) {
            for (TaskGroup group : task.groups) {
                int colour = NodePalette.paper(group.colour).border();
                extractor.outline(minimapX(bounds, group.x, scale),
                        minimapY(bounds, group.y - TaskGroup.HEADER_HEIGHT, scale),
                        Math.max(3, minimapX(bounds, group.right(), scale)
                                - minimapX(bounds, group.x, scale)),
                        Math.max(3, minimapY(bounds, group.bottom(), scale)
                                - minimapY(bounds, group.y - TaskGroup.HEADER_HEIGHT, scale)),
                        colour);
            }
        }
        if (task.notes != null) {
            for (TaskNote note : task.notes) {
                extractor.fill(minimapX(bounds, note.x, scale), minimapY(bounds, note.y, scale),
                        Math.max(minimapX(bounds, note.x, scale) + 2,
                                minimapX(bounds, note.right(), scale)),
                        Math.max(minimapY(bounds, note.y, scale) + 2,
                                minimapY(bounds, note.bottom(), scale)),
                        NodePalette.paper(note.colour).border());
            }
        }

        for (TaskNode node : task.nodes) {
            if (collapsedHolder(node) != null) {
                continue;
            }
            int nodeLeft = minimapX(bounds, nodeX(node), scale);
            int nodeTop = minimapY(bounds, nodeY(node), scale);
            int nodeRight = minimapX(bounds, nodeX(node) + NODE_W, scale);
            int nodeBottom = minimapY(bounds, nodeY(node) + nodeHeight(node), scale);
            int colour = TaskDebug.isHalted(node) ? FIND_MATCH
                    : selectedNodes.contains(node) ? NODE_SELECTED
                    : node.isClockNode() ? pinWhile
                    : isActiveNode(node) ? NODE_ACTIVE : NODE_BORDER;
            extractor.fill(nodeLeft, nodeTop, Math.max(nodeLeft + 3, nodeRight),
                    Math.max(nodeTop + 3, nodeBottom), colour);
        }
        // The traced cable over everything on the map, so its far end can be found here even
        // when it is nowhere on the canvas.
        TracedWire traced = tracedWire;
        if (traced != null) {
            drawMinimapRoute(extractor, bounds, scale, traced.fromX(), traced.fromY(),
                    traced.toX(), traced.toY(), LIVE_WIRE_SPARK, traced.route());
        }

        double viewLeft = -panX / zoom;
        double viewTop = -panY / zoom;
        double viewRight = (getWidth() - panX) / zoom;
        double viewBottom = (getHeight() - panY) / zoom;
        int viewX = Math.clamp(minimapX(bounds, viewLeft, scale), mapLeft, mapRight);
        int viewY = Math.clamp(minimapY(bounds, viewTop, scale), mapTop, mapBottom);
        int viewRightX = Math.clamp(minimapX(bounds, viewRight, scale), mapLeft, mapRight);
        int viewBottomY = Math.clamp(minimapY(bounds, viewBottom, scale), mapTop, mapBottom);
        if (viewRightX > viewX && viewBottomY > viewY) {
            extractor.outline(viewX, viewY, viewRightX - viewX, viewBottomY - viewY, 0xFFB6D2FF);
        }
    }

    private void drawMinimapEdge(GuiGraphicsExtractor extractor, MinimapBounds bounds, double scale,
                                 int sourceX, int sourceY, TaskNode target, int colour) {
        drawMinimapEdge(extractor, bounds, scale, sourceX, sourceY, target, colour, -1);
    }

    private void drawMinimapEdge(GuiGraphicsExtractor extractor, MinimapBounds bounds, double scale,
                                 int sourceX, int sourceY, TaskNode target, int colour,
                                 int targetPort) {
        drawMinimapEdge(extractor, bounds, scale, sourceX, sourceY, target, colour, targetPort, null);
    }

    private void drawMinimapEdge(GuiGraphicsExtractor extractor, MinimapBounds bounds, double scale,
                                 int sourceX, int sourceY, TaskNode target, int colour,
                                 int targetPort, TaskCableRoute route) {
        if (target == null) {
            return;
        }
        drawMinimapRoute(extractor, bounds, scale, sourceX, sourceY,
                inputX(target), targetInputY(target, targetPort), colour, route);
    }

    private void drawMinimapLine(GuiGraphicsExtractor extractor, int x1, int y1, int x2, int y2,
                                 int colour) {
        int steps = Math.max(1, Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1)));
        for (int i = 0; i <= steps; i++) {
            int x = x1 + (x2 - x1) * i / steps;
            int y = y1 + (y2 - y1) * i / steps;
            extractor.fill(x, y, x + 2, y + 2, colour);
        }
    }

    private void drawMinimapRoute(GuiGraphicsExtractor extractor, MinimapBounds bounds, double scale,
                                  int sourceX, int sourceY, int targetX, int targetY, int colour,
                                  TaskCableRoute route) {
        int previousX = minimapX(bounds, sourceX, scale);
        int previousY = minimapY(bounds, sourceY, scale);
        if (route != null && route.points != null) {
            for (TaskCableAnchor point : route.points) {
                if (point == null) {
                    continue;
                }
                int pointX = minimapX(bounds, point.x, scale);
                int pointY = minimapY(bounds, point.y, scale);
                drawMinimapLine(extractor, previousX, previousY, pointX, pointY, colour);
                previousX = pointX;
                previousY = pointY;
            }
        }
        drawMinimapLine(extractor, previousX, previousY,
                minimapX(bounds, targetX, scale), minimapY(bounds, targetY, scale), colour);
    }

    private record MinimapBounds(double minX, double minY, double maxX, double maxY) {
        private double width() {
            return maxX - minX;
        }

        private double height() {
            return maxY - minY;
        }
    }

    private List<String> exposedInputs(TaskNode node) {
        return node.exposedInputs == null ? List.of() : List.copyOf(node.exposedInputs);
    }

    private List<String> exposedOutputs(TaskNode node) {
        return node.exposedOutputs == null ? List.of() : List.copyOf(node.exposedOutputs);
    }

    private Param<?> parameter(TaskNode node, String parameterId) {
        CommandDef def = CommandRegistry.byId(node.commandId);
        if (def == null) {
            return null;
        }
        for (Param<?> param : def.params()) {
            if (param.id().equals(parameterId)) {
                return param;
            }
        }
        return null;
    }

    private boolean compatibleDataPorts(TaskNode source, String sourcePort,
                                        TaskNode target, String targetParameter) {
        Param<?> sourceParam = parameter(source, sourcePort);
        Param<?> targetParam = parameter(target, targetParameter);
        return sourceParam != null && targetParam != null
                && sourceParam.dataType() == targetParam.dataType();
    }

    private String parameterLabel(TaskNode node, String parameterId) {
        CommandDef def = CommandRegistry.byId(node.commandId);
        if (def != null) {
            for (var param : def.params()) {
                if (param.id().equals(parameterId)) {
                    return param.label();
                }
            }
        }
        return parameterId;
    }

    private int wireY(TaskNode node, int type) {
        if (type == WIRE_DATA) {
            int index = exposedOutputs(node).indexOf(wireDataPort);
            return dataOutputY(node, Math.max(0, index));
        }
        if (type == WIRE_SIGNAL) {
            return signalOutputY(node, Math.max(0, wireSignalPort));
        }
        return type == WIRE_FAILURE ? failureY(node)
                : type == WIRE_WHILE ? whileY(node) : successY(node);
    }

    private int wireColour(int type) {
        return type == WIRE_DATA ? pinData
                : type == WIRE_SIGNAL ? pinSignal
                : type == WIRE_FAILURE ? pinFailure : type == WIRE_WHILE ? pinWhile : pinSuccess;
    }

    private record DataInputHit(TaskNode node, String parameterId) {}
    private record ExecInputHit(TaskNode node, int port) {}

    private static String nodeName(TaskNode node) {
        if (node.isStartNode()) {
            return Lang.get("lune.gui.tasks.start");
        }
        if (node.isClockNode()) {
            return node.isPulseSourceNode() ? Lang.get("lune.gui.tasks.pulse") : Lang.get("lune.gui.tasks.always");
        }
        if (node.isSignalRelayNode()) {
            return Lang.get("lune.gui.tasks.signal_relay");
        }
        if (node.isTimerNode()) {
            return Lang.get("lune.gui.tasks.timer");
        }
        if (node.isEndNode()) {
            return Lang.get("lune.gui.palette.end");
        }
        if (node.isCounterNode()) {
            return Lang.get("lune.gui.tasks.counter");
        }
        if (node.isObserverNode()) {
            return Lang.get("lune.gui.palette.observer");
        }
        if (node.isButtonNode()) {
            return Lang.get("lune.gui.tasks.button");
        }
        CommandDef def = CommandRegistry.byId(node.commandId);
        return def == null ? node.commandId : def.name();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        if (selected != null) {
            output.add(NarratedElementType.TITLE, Component.literal(Lang.get("lune.gui.blueprint.selected", nodeName(selected))));
        }
    }
}
