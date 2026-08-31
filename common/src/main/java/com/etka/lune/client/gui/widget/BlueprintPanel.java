package com.etka.lune.client.gui.widget;

import com.etka.lune.bot.BotEngine;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.command.CommandDef;
import com.etka.lune.bot.command.CommandRegistry;
import com.etka.lune.bot.command.Param;
import com.etka.lune.bot.task.TaskRunner;
import com.etka.lune.client.gui.LuneScreen;
import com.etka.lune.client.gui.UiScale;
import com.etka.lune.task.TaskCableAnchor;
import com.etka.lune.task.TaskCableRoute;
import com.etka.lune.task.TaskGraph;
import com.etka.lune.task.TaskDataLink;
import com.etka.lune.task.TaskNode;
import com.etka.lune.task.TaskPower;
import com.etka.lune.task.TaskSignalLink;
import com.etka.lune.task.TaskWiring;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * An Unreal-style node canvas for tasks.
 *
 * <p>The canvas is deliberately only another view over {@link TaskNode}. Success and failure
 * wires write the model's existing {@code onSuccess}/{@code onFailure} fields, so the runner, old
 * JSON and the legacy list editor all continue to work exactly as before.</p>
 */
public final class BlueprintPanel extends AbstractWidget {

    private static final int NODE_W = 124;
    private static final int NODE_H = 72;
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
    private static final int NODE_SELECTED = 0xFF79AFFF;
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

    private static final int LIVE_WIRE_SPARK = 0xFFFFFFFF;
    private static final long SPARK_PERIOD_MS = 900;
    private static final double SPARK_LENGTH = 0.07;

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

    public BlueprintPanel(int x, int y, int width, int height, Consumer<TaskNode> onSelect,
                          Runnable onChanged, Consumer<String> onMessage,
                          Consumer<List<TaskNode>> onDelete) {
        super(x, y, width, height, Component.literal("Task Blueprint canvas"));
        this.onSelect = onSelect;
        this.onChanged = onChanged;
        this.onMessage = onMessage;
        this.onDelete = onDelete;
    }

    public void setTask(TaskGraph task) {
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
        minimapDragging = false;
        selecting = false;
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
        if (changed) {
            onChanged.run();
        }
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
        panX = 0;
        panY = 0;
        zoom = 1.0f;
        onChanged.run();
        onMessage.accept("Blueprint layout reset into a 2D grid; task logic was unchanged");
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
                    Component.literal("Pick a task on the left").withColor(LuneScreen.TEXT_DIM));
            pose.popMatrix();
            extractor.disableScissor();
            return;
        }
        if (task.nodes.isEmpty()) {
            extractor.textRenderer().accept(10, 9,
                    Component.literal("Choose a command from the palette").withColor(LuneScreen.TEXT_DIM));
            pose.popMatrix();
            extractor.disableScissor();
            return;
        }

        int canvasMouseX = canvasX(mouseX);
        int canvasMouseY = canvasY(mouseY);

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
                            drawWire(extractor, outputX(source), whileY(source),
                                    inputX(target), targetInputY(target,
                                            source.alwaysTargetInputPorts == null
                                                    ? 0 : source.alwaysTargetInputPorts.getOrDefault(target.id, 0)),
                                    pinWhile,
                                    power.isLiveWire(source, TaskPower.ALWAYS, 0, target),
                                    anchorFor(TaskCableAnchor.key("always", source.id, target.id)));
                        }
                    }
                }
            }
            TaskNode successTarget = task.nodeById(source.onSuccess);
            if (successTarget != null) {
                drawWire(extractor, outputX(source), successY(source), inputX(successTarget),
                        targetInputY(successTarget, successTarget.isPulseNode()
                                ? source.successInputPort : -1), pinSuccess,
                        power.isLiveWire(source, TaskPower.SUCCESS, 0, successTarget),
                        anchorFor(TaskCableAnchor.key("success", source.id, successTarget.id)));
            }
            TaskNode failureTarget = task.nodeById(source.onFailure);
            if (failureTarget != null) {
                drawWire(extractor, outputX(source), failureY(source), inputX(failureTarget),
                        targetInputY(failureTarget, failureTarget.isPulseNode()
                                ? source.failureInputPort : -1), pinFailure,
                        power.isLiveWire(source, TaskPower.FAILURE, 0, failureTarget),
                        anchorFor(TaskCableAnchor.key("failure", source.id, failureTarget.id)));
            }
            if (!source.isClockNode() && source.whileVisible) {
                TaskNode whileTarget = task.nodeById(source.onWhile);
                if (whileTarget != null) {
                    drawWire(extractor, outputX(source), whileY(source), inputX(whileTarget),
                            targetInputY(whileTarget, whileTarget.isPulseNode()
                                    ? source.whileInputPort : -1), pinWhile,
                            power.isLiveWire(source, TaskPower.WHILE, 0, whileTarget),
                            anchorFor(TaskCableAnchor.key("while", source.id, whileTarget.id)));
                }
            }
            if (source.isPulseNode() && source.signalLinks != null) {
                for (TaskSignalLink link : source.signalLinks) {
                    if (link == null) {
                        continue;
                    }
                    TaskNode target = task.nodeById(link.targetNodeId);
                    if (target != null) {
                        drawWire(extractor, outputX(source), signalOutputY(source, link.outputPort),
                                inputX(target), targetInputY(target, link.targetPort), pinSignal,
                                power.isLiveWire(source, TaskPower.SIGNAL, link.outputPort, target),
                                anchorFor(TaskCableAnchor.key("signal", source.id,
                                        String.valueOf(link.outputPort), target.id,
                                        String.valueOf(link.targetPort))));
                    }
                }
            }
            if (source.isObserverNode() && source.observedNodeId != null) {
                TaskNode watched = task.nodeById(source.observedNodeId);
                if (watched != null) {
                    drawWire(extractor, outputX(watched), nodeY(watched) + HEADER_H / 2,
                            inputX(source), signalInputY(source, 0), pinObserve,
                            power.isLive(watched),
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
                        drawWire(extractor, outputX(dataSource), dataOutputY(dataSource, sourcePort),
                                inputX(source), dataInputY(source, targetPort), pinData,
                                anchorFor(TaskCableAnchor.key("data", dataSource.id, link.sourcePort,
                                        source.id, entry.getKey())));
                    }
                }
            }
        }

        if (wireSource != null) {
            drawWire(extractor, outputX(wireSource), wireY(wireSource, wireType),
                    canvasX(pointerX), canvasY(pointerY), wireColour(wireType));
        }

        for (int i = task.nodes.size() - 1; i >= 0; i--) {
            drawNode(extractor, task.nodes.get(i), canvasMouseX, canvasMouseY);
        }
        drawCableRoutePoints(extractor);

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

        extractor.textRenderer().accept(getX() + 7, getY() + getHeight() - 11,
                Component.literal(draggedCableKey == null
                                ? "Pull cable to add a point • drag white point to move • right-click point to remove • right-click line to cut"
                                : draggedCablePointIndex >= 0
                                ? "Release to move this white point • Esc cancels"
                                : "Release to add a routing point • pull again for another • Esc cancels")
                        .withColor(LuneScreen.TEXT_DIM));
        extractor.textRenderer().accept(getX() + getWidth() - 45, getY() + 7,
                Component.literal(Math.round(zoom * 100) + "%").withColor(LuneScreen.TEXT_DIM));
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
        text.accept(x + 9, y + 23, Component.literal("In").withColor(LuneScreen.TEXT_DIM));
        text.accept(x + 8, y + height - 14,
                Component.literal(node.describeRepeat()).withColor(LuneScreen.TEXT_DIM));
        text.accept(x + NODE_W - 46, y + 25, Component.literal("Success").withColor(pinSuccess));
        text.accept(x + NODE_W - 31, y + 40, Component.literal("Fail").withColor(pinFailure));
        if (node.whileVisible) {
            text.accept(x + NODE_W - 38, y + 55, Component.literal("While").withColor(pinWhile));
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
        text.accept(x + 7, y + 5, Component.literal(pulse ? "Pulse" : "Always")
                .withColor(0xFFFFFFFF));
        text.accept(x + 8, y + 23, Component.literal(pulse ? "clock source" : "pulse source")
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
        text.accept(x + 7, y + 5, Component.literal("START").withColor(0xFFFFFFFF));
        text.accept(x + 8, y + 23, Component.literal("entry point").withColor(NodePalette.of(node).accent()));
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
        text.accept(x + 7, y + 5, Component.literal("Signal Relay").withColor(0xFFFFFFFF));
        text.accept(x + 8, y + 19, Component.literal("pulse junction").withColor(NodePalette.of(node).accent()));
        for (int i = 0; i < node.signalInputCount; i++) {
            int portY = signalInputY(node, i);
            text.accept(x + 8, portY - 3, Component.literal("In " + (i + 1)).withColor(pinSignal));
            drawPin(extractor, inputX(node), portY, pinSignal);
        }
        for (int i = 0; i < node.signalOutputCount; i++) {
            int portY = signalOutputY(node, i);
            text.accept(x + NODE_W - 39, portY - 3,
                    Component.literal("Out " + (i + 1)).withColor(pinSignal));
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
        text.accept(x + 7, y + 5, Component.literal("Timer").withColor(0xFFFFFFFF));
        text.accept(x + 8, y + 19, Component.literal("pulse delay").withColor(NodePalette.of(node).accent()));
        text.accept(x + 8, signalInputY(node, 0) - 3,
                Component.literal("In").withColor(pinSignal));
        text.accept(x + NODE_W - 31, signalOutputY(node, 0) - 3,
                Component.literal("Out").withColor(pinSignal));
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
        drawPulseCard(extractor, node, "Counter", "pulse counter", "In", "Out", pinSignal,
                mouseX, mouseY, true);
    }

    private void drawEndNode(GuiGraphicsExtractor extractor, TaskNode node,
                             int mouseX, int mouseY) {
        int x = nodeX(node);
        int y = nodeY(node);
        int height = nodeHeight(node);
        drawCardShell(extractor, node);
        var text = extractor.textRenderer();
        text.accept(x + 7, y + 5, Component.literal("End").withColor(0xFFFFFFFF));
        text.accept(x + 8, y + 19, Component.literal("pulse sink").withColor(NodePalette.of(node).accent()));
        text.accept(x + 8, signalInputY(node, 0) - 3, Component.literal("In").withColor(pinFailure));
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
        text.accept(x + 7, y + 5, Component.literal("Observer").withColor(0xFFFFFFFF));
        TaskNode watched = task == null || node.observedNodeId == null
                ? null : task.nodeById(node.observedNodeId);
        text.accept(x + 8, y + 19, Component.literal(watched == null
                ? "watch nothing yet" : "watches " + nodeName(watched)).withColor(NodePalette.of(node).accent()));
        text.accept(x + 8, signalInputY(node, 0) - 3, Component.literal("Watch").withColor(pinSignal));
        text.accept(x + NODE_W - 31, signalOutputY(node, 0) - 3,
                Component.literal("Out").withColor(pinSignal));
        drawPin(extractor, inputX(node), signalInputY(node, 0), pinSignal);
        drawPin(extractor, outputX(node), signalOutputY(node, 0), pinSignal);
        if (contains(node, mouseX, mouseY) && !selectedNodes.contains(node)) {
            extractor.fill(x + 1, y + 1, x + NODE_W - 1, y + height - 1, 0x10FFFFFF);
        }
    }

    private void drawButtonNode(GuiGraphicsExtractor extractor, TaskNode node,
                                int mouseX, int mouseY) {
        drawSourcePulseCard(extractor, node, "Button", "manual source", pinSuccess, mouseX, mouseY);
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
                    Component.literal("every " + node.params.getOrDefault("count", "3") + " pulses")
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
                Component.literal("Out").withColor(colour));
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
        String logic = node.isStartNode()
                ? "Send one signal to the connected Success target to start this circuit."
                : node.isClockNode()
                ? "Send a new signal to each connected target " + node.describeAlwaysInterval()
                + ". Each target is an independent circuit; " + nodeName(node)
                + " does not need START."
                : node.isSignalRelayNode()
                ? "Forward a pulse arriving at any input through every connected output."
                : logicDescription(node);
        extractor.setComponentTooltipForNextFrame(Minecraft.getInstance().font,
                List.of(Component.literal("Logic").withColor(LuneScreen.ACCENT),
                        Component.literal(logic).withColor(LuneScreen.TEXT)),
                UiScale.toGamePixels(pointerX), UiScale.toGamePixels(pointerY));
    }

    private String logicDescription(TaskNode node) {
        CommandDef def = CommandRegistry.byId(node.commandId);
        return def == null
                ? "This node is unavailable. Success follows Success; failure follows Fail."
                : def.logicDescription(node.repeat);
    }

    private TaskPower readLivePower() {
        Task current = BotEngine.get().getCurrent();
        return current instanceof TaskRunner running && running.currentTask() == task
                ? running.livePower() : TaskPower.NONE;
    }

    private boolean isActiveNode(TaskNode candidate) {
        return power.isLive(candidate);
    }

    private boolean isFailedNode(TaskNode candidate) {
        return task != null && TaskRunner.isLastFailed(task, candidate);
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
    private static final int CHIP_HOVER = 0xF04C6E9C;
    private static final int CHIP_DANGER = 0xF09C4C50;
    private static final int CHIP_TEXT = 0xFFE8E8EE;
    private static final int CHIP_EDIT = 0xF0203A56;
    private static final int CONTEXT_ROW_H = 18;
    private static final int CONTEXT_MAIN_H = CONTEXT_ROW_H * 2;
    private static final int CONTEXT_MAIN_W = 58;
    private static final int CONTEXT_SUB_W = 72;
    private static final int CONTEXT_GAP = 2;
    private static final int CONTEXT_BG = 0xF01A1A20;
    private static final int CONTEXT_HOVER = 0xF04C6E9C;
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
        if (pinnedToolbarNode != null && !task.nodes.contains(pinnedToolbarNode)) {
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
            return "Delete this card (or press Delete)";
        }
        if (hasForeverChip(node) && chip == chipCount(node) - 2) {
            return "Run forever";
        }
        boolean clock = node.isPulseSourceNode();
        return switch (chip) {
            case CHIP_MINUS -> clock ? "One second less (Shift: ten)" : "One run fewer (Shift: ten)";
            case CHIP_PLUS -> clock ? "One second more (Shift: ten)" : "One run more (Shift: ten)";
            default -> clock
                    ? "Seconds between pulses - click to type one; 0 means every tick"
                    : "How many times this card runs - click to type a number";
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
            onMessage.accept(nodeName(node) + " runs " + node.describeRepeat());
            return true;
        }
        int magnitude = shiftDown() ? 10 : 1;
        if (node.isPulseSourceNode()) {
            int seconds = node.alwaysIntervalSeconds
                    + (chip == CHIP_PLUS ? magnitude : -magnitude);
            node.alwaysIntervalSeconds = Math.clamp(seconds,
                    TaskNode.MIN_ALWAYS_INTERVAL_SECONDS, TaskNode.MAX_ALWAYS_INTERVAL_SECONDS);
            onChanged.run();
            onMessage.accept("Pulse now fires " + node.describeAlwaysInterval());
            return true;
        }
        // Stepping down from forever lands on a real number rather than staying at zero.
        int base = node.repeat == 0 ? 1 : node.repeat;
        node.repeat = Math.clamp(base + (chip == CHIP_PLUS ? magnitude : -magnitude), 1, MAX_REPEAT);
        onChanged.run();
        onMessage.accept(nodeName(node) + " runs " + node.describeRepeat());
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
            onMessage.accept("Pulse now fires " + node.describeAlwaysInterval());
        } else {
            node.repeat = Math.clamp(typed, 0, MAX_REPEAT);
            onMessage.accept(nodeName(node) + " runs " + node.describeRepeat());
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

    private boolean contextDeleteContains(double x, double y) {
        return x >= contextMenuX && x < contextMenuX + CONTEXT_MAIN_W
                && y >= contextMenuY + CONTEXT_ROW_H && y < contextMenuY + CONTEXT_MAIN_H;
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
                        ? "While output shown for " : "While output hidden for ")
                        + nodeName(contextNode));
            }
            closeContextMenu();
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
        boolean deleteHovered = contextDeleteContains(mouseX, mouseY);
        boolean submenuHovered = contextSubmenuContains(mouseX, mouseY);
        if (showHovered || submenuHovered) {
            contextSubmenuOpen = true;
        } else if (!deleteHovered) {
            contextSubmenuOpen = false;
        }
        drawContextPanel(extractor, contextMenuX, contextMenuY, CONTEXT_MAIN_W, CONTEXT_MAIN_H);
        drawContextRow(extractor, contextMenuX, contextMenuY, CONTEXT_MAIN_W, showHovered);
        drawContextRow(extractor, contextMenuX, contextMenuY + CONTEXT_ROW_H,
                CONTEXT_MAIN_W, deleteHovered);
        var text = extractor.textRenderer();
        text.accept(contextMenuX + 7, contextMenuY + 5,
                Component.literal("Show").withColor(CONTEXT_TEXT));
        text.accept(contextMenuX + CONTEXT_MAIN_W - 12, contextMenuY + 5,
                Component.literal("> ").withColor(LuneScreen.TEXT_DIM));
        text.accept(contextMenuX + 7, contextMenuY + CONTEXT_ROW_H + 5,
                Component.literal("Delete").withColor(CONTEXT_DANGER));

        if (contextSubmenuOpen) {
            int x = contextSubmenuX();
            drawContextPanel(extractor, x, contextMenuY, CONTEXT_SUB_W, CONTEXT_ROW_H);
            drawContextRow(extractor, x, contextMenuY, CONTEXT_SUB_W, submenuHovered);
            text.accept(x + 7, contextMenuY + 5,
                    Component.literal("While").withColor(supportsWhilePort(contextNode)
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
        long window = Minecraft.getInstance().getWindow().handle();
        return GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
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

        extractor.fill(x, y, x + NODE_W, y + height, border);
        extractor.fill(x + 1, y + 1, x + NODE_W - 1, y + height - 1, colours.background());
        // Only the outline reports that a card is live. Recolouring the title bar too made a
        // running card read as a different kind of card rather than the same one, lit up.
        extractor.fill(x + 1, y + 1, x + NODE_W - 1, y + HEADER_H,
                border == NODE_FAILED ? HEADER_FAILED : colours.header());
        // The role stripe: the one mark that separates a source from an action from a decision.
        extractor.fill(x + 1, y + 1, x + 4, y + HEADER_H, colours.accent());
        return border;
    }

    private void drawPin(GuiGraphicsExtractor extractor, int x, int y, int colour) {
        extractor.fill(x - 3, y - 3, x + 4, y + 4, 0xFF101014);
        extractor.fill(x - 2, y - 2, x + 3, y + 3, colour);
    }

    /** Tiny white handles show exactly where each released cable point is stored. */
    private void drawCableRoutePoints(GuiGraphicsExtractor extractor) {
        if (task == null || task.cableAnchors == null) {
            return;
        }
        task.cableAnchors.forEach((key, savedRoute) -> {
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
                extractor.fill(point.x - 2, point.y - 2, point.x + 3, point.y + 3, 0xFFFFFFFF);
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
     * shows where the signal came from as well as where it is now. The spark's position comes from
     * wall-clock time rather than a tick counter: the panel renders while the game is paused, and a
     * dead wire and a stalled animation should not look the same.</p>
     */
    private void drawWire(GuiGraphicsExtractor extractor, int x1, int y1, int x2, int y2,
                          int colour, boolean live, TaskCableRoute route) {
        if (!wireCouldBeVisible(x1, y1, x2, y2, route)) {
            // Whole-wire cull. The per-point check below still had to walk every sample of a wire
            // that was nowhere near the screen, which on a large task is most of them.
            return;
        }
        int distance = wireLength(x1, y1, x2, y2, route);
        // Sampled by how long the wire is *on screen*, not on the canvas. The dots are two pixels
        // across, so anything finer than one sample per two screen pixels draws quads on top of
        // quads: at the old flat 180 a task with thirty edges spent five thousand of them per frame
        // to no visible effect.
        int samples = Math.clamp(Math.round(distance * zoom / 2f), 8, 96);
        double spark = live ? (System.currentTimeMillis() % SPARK_PERIOD_MS) / (double) SPARK_PERIOD_MS : -1;
        for (int i = 0; i <= samples; i++) {
            double t = i / (double) samples;
            WirePoint point = wirePoint(x1, y1, x2, y2, route, t);
            int px = (int) Math.round(point.x());
            int py = (int) Math.round(point.y());
            if (!inside(px, py)) {
                continue;
            }
            if (!live) {
                extractor.fill(px, py, px + 2, py + 2, colour);
                continue;
            }
            // One quad, not two: a live wire is drawn a pixel wider and a shade brighter rather
            // than having a separate halo laid over every point of it.
            extractor.fill(px - 1, py - 1, px + 2, py + 2,
                    Math.abs(t - spark) < SPARK_LENGTH ? LIVE_WIRE_SPARK : colour);
        }
    }

    private TaskCableRoute anchorFor(String key) {
        if (key == null) {
            return null;
        }
        if (key.equals(draggedCableKey) && cableMoved) {
            return draggedCablePreview;
        }
        return task == null || task.cableAnchors == null ? null : task.cableAnchors.get(key);
    }

    private double curveTangent(int x1, int x2) {
        return Math.max(28, Math.abs(x2 - x1) * 0.45);
    }

    private int wireLength(int x1, int y1, int x2, int y2, TaskCableRoute route) {
        if (route == null || route.points == null || route.points.isEmpty()) {
            return Math.max(12, Math.abs(x2 - x1) + Math.abs(y2 - y1));
        }
        int length = 0;
        int previousX = x1;
        int previousY = y1;
        for (TaskCableAnchor point : route.points) {
            if (point == null) {
                continue;
            }
            length += Math.abs(point.x - previousX) + Math.abs(point.y - previousY);
            previousX = point.x;
            previousY = point.y;
        }
        length += Math.abs(x2 - previousX) + Math.abs(y2 - previousY);
        return Math.max(12, length);
    }

    private WirePoint wirePoint(int x1, int y1, int x2, int y2,
                                TaskCableRoute route, double t) {
        if (route == null || route.points == null || route.points.isEmpty()) {
            return curvedPoint(x1, y1, x2, y2, t);
        }
        double total = wireLength(x1, y1, x2, y2, route);
        double remaining = Math.clamp(t, 0.0, 1.0) * total;
        int previousX = x1;
        int previousY = y1;
        for (TaskCableAnchor point : route.points) {
            if (point == null) {
                continue;
            }
            double segment = Math.abs(point.x - previousX) + Math.abs(point.y - previousY);
            if (segment > 0 && remaining <= segment) {
                return curvedPoint(previousX, previousY, point.x, point.y,
                        remaining / segment);
            }
            remaining -= segment;
            previousX = point.x;
            previousY = point.y;
        }
        double finalSegment = Math.abs(x2 - previousX) + Math.abs(y2 - previousY);
        if (finalSegment == 0) {
            return new WirePoint(x2, y2);
        }
        return curvedPoint(previousX, previousY, x2, y2,
                Math.clamp(remaining / finalSegment, 0.0, 1.0));
    }

    private WirePoint curvedPoint(int x1, int y1, int x2, int y2, double t) {
        double tangent = curveTangent(x1, x2);
        double u = 1.0 - t;
        return new WirePoint(
                u * u * u * x1 + 3 * u * u * t * (x1 + tangent)
                        + 3 * u * t * t * (x2 - tangent) + t * t * t * x2,
                u * u * u * y1 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y2);
    }

    /** True when any part of a wire's path could land inside the visible canvas. */
    private boolean wireCouldBeVisible(int x1, int y1, int x2, int y2,
                                       TaskCableRoute route) {
        double left = -panX / zoom;
        double top = -panY / zoom;
        double right = (getWidth() - panX) / zoom;
        double bottom = (getHeight() - panY) / zoom;
        double tangent = curveTangent(x1, x2);
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
                tangent = Math.max(tangent, curveTangent(previousX, point.x));
                minX = Math.min(minX, Math.min(previousX, point.x) - tangent - 2);
                maxX = Math.max(maxX, Math.max(previousX, point.x) + tangent + 2);
                minY = Math.min(minY, Math.min(previousY, point.y) - 2);
                maxY = Math.max(maxY, Math.max(previousY, point.y) + 2);
                previousX = point.x;
                previousY = point.y;
            }
        }
        tangent = Math.max(tangent, curveTangent(previousX, x2));
        minX = Math.min(minX, Math.min(previousX, x2) - tangent - 2);
        maxX = Math.max(maxX, Math.max(previousX, x2) + tangent + 2);
        minY = Math.min(minY, Math.min(previousY, y2) - 2);
        maxY = Math.max(maxY, Math.max(previousY, y2) + 2);
        return maxX >= left && minX <= right && maxY >= top && minY <= bottom;
    }

    private record WirePoint(double x, double y) {}

    private record CableHit(String key, int insertionIndex) {}

    private record CablePointHit(String key, int pointIndex) {}

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (task == null) {
            return;
        }
        pointerX = (int) event.x();
        pointerY = (int) event.y();

        if (event.button() == 2) {
            closeContextMenu();
            panning = true;
            selecting = false;
            dragged = null;
            return;
        }
        if (event.button() != 0) {
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
            boolean ctrl = (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
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

        selectionAdditive = (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
        if (!selectionAdditive) {
            selectedNodes.clear();
            selected = null;
            onSelect.accept(null);
        }
        selectionStartX = pointerX;
        selectionStartY = pointerY;
        selectionMoved = false;
        selecting = selectionAdditive;
        panning = !selectionAdditive;
    }

    /** Right-click opens a node menu, while a wire segment still offers the quick cut action. */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && contextNode != null) {
            boolean handled = handleContextMenuClick(event.x(), event.y());
            setFocused(true);
            return handled;
        }
        if (event.button() == 2) {
            if (isMouseOver(event.x(), event.y())) {
                onClick(event, doubleClick);
                setFocused(true);
                return true;
            }
            return false;
        }
        if (event.button() == 1) {
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
        TaskCableRoute stored = task.cableAnchors == null ? null
                : task.cableAnchors.get(draggedCableKey);
        draggedCableBase = stored == null ? new TaskCableRoute() : stored.copy();
        draggedCablePreview = draggedCableBase.copy();
        draggedCablePointIndex = -1;
        draggedCableInsertIndex = Math.clamp(hit.insertionIndex(), 0,
                draggedCablePreview.points.size());
        cableDragStartX = x;
        cableDragStartY = y;
        cableMoved = false;
        onMessage.accept("Drag the cable and release to add a routing point");
    }

    private void beginCablePointDrag(CablePointHit hit, int x, int y) {
        draggedCableKey = hit.key();
        TaskCableRoute stored = task.cableAnchors == null ? null
                : task.cableAnchors.get(draggedCableKey);
        draggedCableBase = stored == null ? new TaskCableRoute() : stored.copy();
        draggedCablePreview = draggedCableBase.copy();
        draggedCablePointIndex = Math.clamp(hit.pointIndex(), 0,
                Math.max(0, draggedCablePreview.points.size() - 1));
        draggedCableInsertIndex = 0;
        cableDragStartX = x;
        cableDragStartY = y;
        cableMoved = false;
        onMessage.accept("Drag the white point and release to move it");
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
                    ? "Cable routing point moved" : "Cable routing point added");
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
        if (task == null || task.cableAnchors == null) {
            return null;
        }
        for (java.util.Map.Entry<String, TaskCableRoute> entry : task.cableAnchors.entrySet()) {
            int pointIndex = cablePointAt(entry.getKey(), x, y);
            if (pointIndex >= 0) {
                return new CablePointHit(entry.getKey(), pointIndex);
            }
        }
        return null;
    }

    /** Finds the saved route point nearest the pointer for the small white handle action. */
    private int cablePointAt(String key, int x, int y) {
        if (task == null || task.cableAnchors == null) {
            return -1;
        }
        TaskCableRoute route = task.cableAnchors.get(key);
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
            onMessage.accept("Cable routing point removed");
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
                        onMessage.accept("Always connection cut");
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
                onMessage.accept("Success wire cut");
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
                onMessage.accept("Failure wire cut");
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
                    onMessage.accept("While wire cut");
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
                    onMessage.accept("Observer wire cut");
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
                        onMessage.accept("Pulse output wire cut");
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
                    onMessage.accept("Data wire cut");
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
        double closestDistance = Double.POSITIVE_INFINITY;
        int closestSegment = -1;
        int segmentIndex = 0;
        int previousX = x1;
        int previousY = y1;
        if (route != null && route.points != null) {
            for (TaskCableAnchor point : route.points) {
                if (point == null) {
                    continue;
                }
                double distance = distanceToCurve(px, py, previousX, previousY, point.x, point.y);
                if (distance < closestDistance) {
                    closestDistance = distance;
                    closestSegment = segmentIndex;
                }
                previousX = point.x;
                previousY = point.y;
                segmentIndex++;
            }
        }
        double distance = distanceToCurve(px, py, previousX, previousY, x2, y2);
        if (distance < closestDistance) {
            closestDistance = distance;
            closestSegment = segmentIndex;
        }
        return closestDistance <= 7.0 ? closestSegment : -1;
    }

    private double distanceToCurve(double px, double py, int x1, int y1, int x2, int y2) {
        int length = Math.abs(x2 - x1) + Math.abs(y2 - y1);
        int samples = Math.clamp(length / 4, 8, 64);
        double closest = Double.POSITIVE_INFINITY;
        WirePoint last = curvedPoint(x1, y1, x2, y2, 0);
        for (int i = 1; i <= samples; i++) {
            WirePoint next = curvedPoint(x1, y1, x2, y2, i / (double) samples);
            closest = Math.min(closest,
                    distanceToSegment(px, py, last.x(), last.y(), next.x(), next.y()));
            last = next;
        }
        return closest;
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
            panX += (int) Math.round(dragX);
            panY += (int) Math.round(dragY);
        }
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
                        onMessage.accept("Data ports must use the same value type");
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
                    onMessage.accept((old == null ? "Data" : "Data wire replaced")
                            + " connected to " + parameterLabel(target.node(), target.parameterId()));
                } else if (target == null) {
                    onMessage.accept("Drop the data wire on an exposed input");
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
                    onMessage.accept("Drop the pulse output on a command or pulse input");
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
                        onMessage.accept((wireSource.isTimerNode() ? "Timer output" : "Relay output")
                                + " " + (wireSignalPort + 1)
                                + " connected to " + nodeName(target));
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
                    onMessage.accept("Drop " + nodeName(wireSource) + " on a command's In pin");
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
                        onMessage.accept(nodeName(wireSource) + " connected to " + nodeName(target));
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
                    onMessage.accept("An Observer cannot watch itself");
                } else {
                    String oldObserved = target.observedNodeId;
                    target.observedNodeId = wireSource.id;
                    if (oldObserved != null && !oldObserved.equals(wireSource.id)) {
                        removeCableAnchor(TaskCableAnchor.key("observe", oldObserved, target.id));
                    }
                    onChanged.run();
                    onMessage.accept("Observer now watches " + nodeName(wireSource));
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
                onMessage.accept("START and Always are source nodes; connect to a command's In pin");
                wireSource = null;
                wireDataPort = null;
                wireSignalPort = -1;
                dragged = null;
                moved = false;
                panning = false;
                return;
            }
            if (wireType == WIRE_WHILE && target != null && target.isPulseNode()) {
                onMessage.accept("Use Success or Fail to send a pulse into this node");
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
                onMessage.accept("Cable drag cancelled; release on an input to connect");
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
                String label = wireType == WIRE_FAILURE ? "Failure"
                        : wireType == WIRE_WHILE ? "While" : "Success";
                onMessage.accept(label + " connected to " + nodeName(target));
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
        extractor.outline(left, top, right - left, bottom - top, 0xFF79AFFF);
    }

    private void selectAnchor() {
        selected = selectedNodes.isEmpty() ? null : selectedNodes.iterator().next();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (task == null) {
            return false;
        }
        if (editingNode != null) {
            // While a number is being typed the chip owns the keyboard. Backspace especially:
            // it is also the delete-card binding, and deleting the card you are editing because
            // you mistyped a digit would be a memorable way to lose work.
            if (event.key() == GLFW.GLFW_KEY_BACKSPACE) {
                if (!editBuffer.isEmpty()) {
                    editBuffer = editBuffer.substring(0, editBuffer.length() - 1);
                }
                return true;
            }
            if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
                commitChipEdit();
                return true;
            }
            if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
                editingNode = null;
                editBuffer = "";
                return true;
            }
            return true;
        }
        boolean ctrl = (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
        if (ctrl && (event.key() == GLFW.GLFW_KEY_0 || event.key() == GLFW.GLFW_KEY_KP_0)) {
            zoom = 1.0f;
            panX = 0;
            panY = 0;
            onMessage.accept("Blueprint view reset");
            return true;
        }
        if (ctrl && event.key() == GLFW.GLFW_KEY_A) {
            selectedNodes.clear();
            selectedNodes.addAll(task.nodes);
            selectAnchor();
            onSelect.accept(selected);
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_DELETE || event.key() == GLFW.GLFW_KEY_BACKSPACE) {
            if (!selectedNodes.isEmpty()) {
                onDelete.accept(List.copyOf(selectedNodes));
                return true;
            }
        }
        if (event.key() == GLFW.GLFW_KEY_ESCAPE
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
        return false;
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        int codepoint = event.codepoint();
        if (editingNode == null || codepoint < '0' || codepoint > '9') {
            return false;
        }
        if (editBuffer.length() < 7) {
            editBuffer += (char) codepoint;
        }
        return true;
    }

    private boolean controlDown() {
        long window = Minecraft.getInstance().getWindow().handle();
        return GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
    }

    private TaskNode nodeAt(int x, int y) {
        if (task == null) {
            return null;
        }
        for (int i = task.nodes.size() - 1; i >= 0; i--) {
            TaskNode node = task.nodes.get(i);
            if (contains(node, x, y)) {
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
        return nodeY(node) + 29;
    }

    private int outputX(TaskNode node) {
        return nodeX(node) + NODE_W;
    }

    private int successY(TaskNode node) {
        return nodeY(node) + 29;
    }

    private int failureY(TaskNode node) {
        return nodeY(node) + 44;
    }

    private int whileY(TaskNode node) {
        return nodeY(node) + (node.isClockNode() ? 29 : 59);
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
        return rows == 0 ? baseHeight : dataStartY(node) + rows * DATA_ROW_HEIGHT + DATA_FOOTER_GAP;
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
        if (task.cableAnchors != null) {
            for (TaskCableRoute route : task.cableAnchors.values()) {
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
                Component.literal("Always").withColor(LuneScreen.TEXT));
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
                Component.literal("MAP").withColor(0xFFFFFFFF));

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

        for (TaskNode node : task.nodes) {
            int nodeLeft = minimapX(bounds, nodeX(node), scale);
            int nodeTop = minimapY(bounds, nodeY(node), scale);
            int nodeRight = minimapX(bounds, nodeX(node) + NODE_W, scale);
            int nodeBottom = minimapY(bounds, nodeY(node) + nodeHeight(node), scale);
            int colour = selectedNodes.contains(node) ? NODE_SELECTED
                    : node.isClockNode() ? pinWhile
                    : isActiveNode(node) ? NODE_ACTIVE : NODE_BORDER;
            extractor.fill(nodeLeft, nodeTop, Math.max(nodeLeft + 3, nodeRight),
                    Math.max(nodeTop + 3, nodeBottom), colour);
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
            return "START";
        }
        if (node.isClockNode()) {
            return node.isPulseSourceNode() ? "Pulse" : "Always";
        }
        if (node.isSignalRelayNode()) {
            return "Signal Relay";
        }
        if (node.isTimerNode()) {
            return "Timer";
        }
        if (node.isEndNode()) {
            return "End";
        }
        if (node.isCounterNode()) {
            return "Counter";
        }
        if (node.isObserverNode()) {
            return "Observer";
        }
        if (node.isButtonNode()) {
            return "Button";
        }
        CommandDef def = CommandRegistry.byId(node.commandId);
        return def == null ? node.commandId : def.name();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        if (selected != null) {
            output.add(NarratedElementType.TITLE, Component.literal("Selected " + nodeName(selected)));
        }
    }
}
