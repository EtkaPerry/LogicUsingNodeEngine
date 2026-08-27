package com.etka.lune.client.gui.widget;

import com.etka.lune.bot.BotEngine;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.command.CommandDef;
import com.etka.lune.bot.command.CommandRegistry;
import com.etka.lune.bot.command.Param;
import com.etka.lune.bot.task.RoutineTask;
import com.etka.lune.client.gui.LuneScreen;
import com.etka.lune.client.gui.UiScale;
import com.etka.lune.routine.Routine;
import com.etka.lune.routine.RoutineDataLink;
import com.etka.lune.routine.RoutineNode;
import com.etka.lune.routine.RoutineSignalLink;
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
 * An Unreal-style node canvas for routines.
 *
 * <p>The canvas is deliberately only another view over {@link RoutineNode}. Success and failure
 * wires write the model's existing {@code onSuccess}/{@code onFailure} fields, so the runner, old
 * JSON and the legacy list editor all continue to work exactly as before.</p>
 */
public final class BlueprintPanel extends AbstractWidget {

    private static final int NODE_W = 124;
    private static final int NODE_H = 72;
    private static final int HEADER_H = 16;
    private static final int GRID = 20;
    private static final int PIN_RADIUS = 6;
    private static final int STEP_X = 154;
    private static final int LAYOUT_COLUMNS = 6;
    private static final int LAYOUT_ROW_GAP = 118;

    private static final int GRID_COLOUR = 0x242F3038;
    private static final int NODE_BG = 0xF21D1E24;
    private static final int NODE_BORDER = 0xFF4A4B55;
    private static final int NODE_SELECTED = 0xFF79AFFF;
    private static final int NODE_ACTIVE = 0xFF76FF9F;
    private static final int NODE_ACTIVE_GLOW = 0x5058FF88;
    private static final int NODE_FAILED = 0xFFE15B64;
    private static final int NODE_FAILED_GLOW = 0x50E15B64;
    private static final int HEADER_ACTIVE = 0xFF247A48;
    private static final int HEADER_FAILED = 0xFF8B3A3A;
    private static final int HEADER_BG = 0xFF285D89;
    private static final int EXEC_PIN = 0xFFF2F2F2;
    private static final int SUCCESS = 0xFF58C878;
    private static final int FAILURE = 0xFFE15B64;
    private static final int WHILE = 0xFFF0B84D;
    private static final int SIGNAL = 0xFFB77DFF;
    private static final int DATA = 0xFFF2C14E;
    private static final int DATA_START_Y = 76;
    private static final int DATA_ROW_HEIGHT = 16;
    private static final int DATA_FOOTER_GAP = 18;
    private static final int MINIMAP_WIDTH = 164;
    private static final int MINIMAP_HEIGHT = 104;
    private static final int MINIMAP_MARGIN = 8;
    private static final int MINIMAP_HEADER = 16;
    private static final int MINIMAP_PADDING = 6;

    private static final int WIRE_SUCCESS = 0;
    private static final int WIRE_FAILURE = 1;
    private static final int WIRE_WHILE = 2;
    private static final int WIRE_DATA = 3;
    private static final int WIRE_SIGNAL = 4;
    private static final float MIN_ZOOM = 0.50f;
    private static final float MAX_ZOOM = 2.00f;
    private static final float ZOOM_STEP = 1.10f;

    private final Consumer<RoutineNode> onSelect;
    private final Runnable onChanged;
    private final Consumer<String> onMessage;
    private final Consumer<List<RoutineNode>> onDelete;

    private Routine routine;
    private RoutineNode selected;
    private final Set<RoutineNode> selectedNodes = new LinkedHashSet<>();
    private RoutineNode dragged;
    private RoutineNode wireSource;
    private int wireType;
    private String wireDataPort;
    private int wireSignalPort = -1;
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

    public BlueprintPanel(int x, int y, int width, int height, Consumer<RoutineNode> onSelect,
                          Runnable onChanged, Consumer<String> onMessage,
                          Consumer<List<RoutineNode>> onDelete) {
        super(x, y, width, height, Component.literal("Task Blueprint canvas"));
        this.onSelect = onSelect;
        this.onChanged = onChanged;
        this.onMessage = onMessage;
        this.onDelete = onDelete;
    }

    public void setRoutine(Routine routine) {
        this.routine = routine;
        dragged = null;
        wireSource = null;
        wireDataPort = null;
        wireSignalPort = -1;
        panning = false;
        minimapDragging = false;
        selecting = false;
        selectedNodes.removeIf(node -> routine == null || !routine.nodes.contains(node));
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

    public RoutineNode getSelected() {
        return selected;
    }

    public void setSelected(RoutineNode node) {
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

    public List<RoutineNode> getSelectedNodes() {
        return List.copyOf(selectedNodes);
    }

    public boolean toggleMinimap() {
        minimapVisible = !minimapVisible;
        minimapDragging = false;
        return minimapVisible;
    }

    /** Places freshly inserted palette nodes near the selected node instead of on top of it. */
    public void placeNewNodes(List<RoutineNode> nodes, RoutineNode after) {
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
            if (routine != null) {
                int shift = nodes.size() * STEP_X;
                for (RoutineNode existing : routine.nodes) {
                    if (existing != after && existing.editorX != null && existing.editorX >= x
                            && existing.editorY != null && Math.abs(existing.editorY - y) < NODE_H) {
                        existing.editorX += shift;
                    }
                }
            }
        } else if (routine != null) {
            for (RoutineNode node : routine.nodes) {
                if (node.editorX != null) {
                    x = Math.max(x, node.editorX + STEP_X);
                }
            }
        }
        for (RoutineNode node : nodes) {
            node.editorX = x;
            node.editorY = y;
            x += STEP_X;
        }
    }

    /** Restores a readable 2D layout without changing execution order or edges. */
    public void autoLayout() {
        if (routine == null) {
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
        for (int i = 0; i < routine.nodes.size(); i++) {
            RoutineNode node = routine.nodes.get(i);
            int column = i % LAYOUT_COLUMNS;
            int row = i / LAYOUT_COLUMNS;
            node.editorX = 24 + column * STEP_X;
            node.editorY = 26 + row * LAYOUT_ROW_GAP;
        }
    }

    private boolean ensurePositions() {
        if (routine == null) {
            return false;
        }
        boolean changed = false;
        for (int i = 0; i < routine.nodes.size(); i++) {
            RoutineNode node = routine.nodes.get(i);
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
        if (routine == null || routine.nodes.size() <= LAYOUT_COLUMNS) {
            return false;
        }
        Integer firstY = routine.nodes.get(0).editorY;
        if (firstY == null) {
            return false;
        }
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        for (RoutineNode node : routine.nodes) {
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
        if (routine == null) {
            extractor.textRenderer().accept(10, 9,
                    Component.literal("Pick a task on the left").withColor(LuneScreen.TEXT_DIM));
            pose.popMatrix();
            extractor.disableScissor();
            return;
        }
        if (routine.nodes.isEmpty()) {
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
        // routine model, but the Blueprint canvas must never imply a connection the user did not
        // create.
        for (RoutineNode source : routine.nodes) {
            if (source.isAlwaysNode()) {
                if (source.alwaysTargets != null) {
                    for (String targetId : source.alwaysTargets) {
                        RoutineNode target = routine.nodeById(targetId);
                        if (target != null) {
                            drawWire(extractor, outputX(source), whileY(source),
                                    inputX(target), targetInputY(target,
                                            source.alwaysTargetInputPorts == null
                                                    ? 0 : source.alwaysTargetInputPorts.getOrDefault(target.id, 0)),
                                    WHILE);
                        }
                    }
                }
            }
            RoutineNode successTarget = routine.nodeById(source.onSuccess);
            if (successTarget != null) {
                drawWire(extractor, outputX(source), successY(source), inputX(successTarget),
                        targetInputY(successTarget, successTarget.isPulseNode()
                                ? source.successInputPort : -1), SUCCESS);
            }
            RoutineNode failureTarget = routine.nodeById(source.onFailure);
            if (failureTarget != null) {
                drawWire(extractor, outputX(source), failureY(source), inputX(failureTarget),
                        targetInputY(failureTarget, failureTarget.isPulseNode()
                                ? source.failureInputPort : -1), FAILURE);
            }
            if (!source.isAlwaysNode()) {
                RoutineNode whileTarget = routine.nodeById(source.onWhile);
                if (whileTarget != null) {
                    drawWire(extractor, outputX(source), whileY(source), inputX(whileTarget),
                            targetInputY(whileTarget, whileTarget.isPulseNode()
                                    ? source.whileInputPort : -1), WHILE);
                }
            }
            if (source.isPulseNode() && source.signalLinks != null) {
                for (RoutineSignalLink link : source.signalLinks) {
                    if (link == null) {
                        continue;
                    }
                    RoutineNode target = routine.nodeById(link.targetNodeId);
                    if (target != null) {
                        drawWire(extractor, outputX(source), signalOutputY(source, link.outputPort),
                                inputX(target), targetInputY(target, link.targetPort), SIGNAL);
                    }
                }
            }
            if (source.inputLinks != null) {
                for (var entry : source.inputLinks.entrySet()) {
                    RoutineDataLink link = entry.getValue();
                    RoutineNode dataSource = link == null ? null : routine.nodeById(link.sourceNodeId);
                    int sourcePort = dataSource == null ? -1
                            : exposedOutputs(dataSource).indexOf(link.sourcePort);
                    int targetPort = exposedInputs(source).indexOf(entry.getKey());
                    if (dataSource != null && sourcePort >= 0 && targetPort >= 0) {
                        drawWire(extractor, outputX(dataSource), dataOutputY(dataSource, sourcePort),
                                inputX(source), dataInputY(source, targetPort), DATA);
                    }
                }
            }
        }

        if (wireSource != null) {
            drawWire(extractor, outputX(wireSource), wireY(wireSource, wireType),
                    canvasX(pointerX), canvasY(pointerY), wireColour(wireType));
        }

        for (int i = routine.nodes.size() - 1; i >= 0; i--) {
            drawNode(extractor, routine.nodes.get(i), canvasMouseX, canvasMouseY);
        }
        pose.popMatrix();

        if (minimapVisible) {
            drawMinimap(extractor);
        }

        extractor.textRenderer().accept(getX() + 7, getY() + getHeight() - 11,
                Component.literal("Ctrl+wheel zoom • Ctrl-drag select • drag empty space to pan")
                        .withColor(LuneScreen.TEXT_DIM));
        extractor.textRenderer().accept(getX() + getWidth() - 45, getY() + 7,
                Component.literal(Math.round(zoom * 100) + "%").withColor(LuneScreen.TEXT_DIM));
        extractor.disableScissor();
    }

    private void drawGrid(GuiGraphicsExtractor extractor) {
        int minX = (int) Math.floor(-panX / zoom) - GRID;
        int minY = (int) Math.floor(-panY / zoom) - GRID;
        int maxX = (int) Math.ceil((getWidth() - panX) / zoom) + GRID;
        int maxY = (int) Math.ceil((getHeight() - panY) / zoom) + GRID;
        int firstX = Math.floorDiv(minX, GRID) * GRID;
        int firstY = Math.floorDiv(minY, GRID) * GRID;
        for (int x = firstX; x <= maxX; x += GRID) {
            for (int y = firstY; y <= maxY; y += GRID) {
                extractor.fill(x, y, x + 1, y + 1, GRID_COLOUR);
            }
        }
    }

    private void drawNode(GuiGraphicsExtractor extractor, RoutineNode node, int mouseX, int mouseY) {
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
        if (node.isAlwaysNode()) {
            drawAlwaysNode(extractor, node, mouseX, mouseY);
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

        boolean active = isActiveNode(node);
        boolean failed = isFailedNode(node);
        if (active) {
            // Two translucent rings keep the running card obvious even when it is not selected.
            extractor.fill(x - 4, y - 4, x + NODE_W + 4, y + height + 4, NODE_ACTIVE_GLOW);
            extractor.fill(x - 2, y - 2, x + NODE_W + 2, y + height + 2, NODE_ACTIVE);
        } else if (failed) {
            extractor.fill(x - 4, y - 4, x + NODE_W + 4, y + height + 4, NODE_FAILED_GLOW);
            extractor.fill(x - 2, y - 2, x + NODE_W + 2, y + height + 2, NODE_FAILED);
        }
        int border = failed ? NODE_FAILED : active ? NODE_ACTIVE
                : selectedNodes.contains(node) ? NODE_SELECTED : NODE_BORDER;
        extractor.fill(x, y, x + NODE_W, y + height, border);
        extractor.fill(x + 1, y + 1, x + NODE_W - 1, y + height - 1, NODE_BG);
        int header = failed ? HEADER_FAILED : active ? HEADER_ACTIVE : HEADER_BG;
        extractor.fill(x + 1, y + 1, x + NODE_W - 1, y + HEADER_H, header);

        CommandDef def = CommandRegistry.byId(node.commandId);
        String name = def == null ? node.commandId : def.name();
        var text = extractor.textRenderer();
        text.accept(x + 7, y + 5, Component.literal(name).withColor(0xFFFFFFFF));
        if (active) {
            text.accept(x + NODE_W - 10, y + 5, Component.literal("●").withColor(NODE_ACTIVE));
        }
        text.accept(x + 9, y + 23, Component.literal("In").withColor(LuneScreen.TEXT_DIM));
        text.accept(x + 8, y + height - 14,
                Component.literal(node.describeRepeat()).withColor(LuneScreen.TEXT_DIM));
        text.accept(x + NODE_W - 46, y + 25, Component.literal("Success").withColor(SUCCESS));
        text.accept(x + NODE_W - 31, y + 40, Component.literal("Fail").withColor(FAILURE));
        text.accept(x + NODE_W - 38, y + 55, Component.literal("While").withColor(WHILE));

        drawPin(extractor, inputX(node), inputY(node), EXEC_PIN);
        drawPin(extractor, outputX(node), successY(node), SUCCESS);
        drawPin(extractor, outputX(node), failureY(node), FAILURE);
        drawPin(extractor, outputX(node), whileY(node), WHILE);

        List<String> inputs = exposedInputs(node);
        for (int i = 0; i < inputs.size(); i++) {
            int portY = dataInputY(node, i);
            text.accept(x + 8, portY + 3, Component.literal(parameterLabel(node, inputs.get(i)))
                    .withColor(DATA));
            drawPin(extractor, inputX(node), portY, DATA);
        }
        List<String> outputs = exposedOutputs(node);
        for (int i = 0; i < outputs.size(); i++) {
            int portY = dataOutputY(node, i);
            text.accept(x + NODE_W - 42, portY - 3,
                    Component.literal(parameterLabel(node, outputs.get(i))).withColor(DATA));
            drawPin(extractor, outputX(node), portY, DATA);
        }

        if (contains(node, mouseX, mouseY) && !selectedNodes.contains(node)) {
            extractor.fill(x + 1, y + 1, x + NODE_W - 1, y + height - 1, 0x10FFFFFF);
        }
    }

    private void drawAlwaysNode(GuiGraphicsExtractor extractor, RoutineNode node, int mouseX, int mouseY) {
        int x = nodeX(node);
        int y = nodeY(node);
        int height = nodeHeight(node);
        int border = selectedNodes.contains(node) ? NODE_SELECTED : WHILE;
        extractor.fill(x, y, x + NODE_W, y + height, border);
        extractor.fill(x + 1, y + 1, x + NODE_W - 1, y + height - 1, NODE_BG);
        extractor.fill(x + 1, y + 1, x + NODE_W - 1, y + HEADER_H, 0xFF8A691A);
        var text = extractor.textRenderer();
        text.accept(x + 7, y + 5, Component.literal("Always").withColor(0xFFFFFFFF));
        text.accept(x + 8, y + 23, Component.literal("pulse source").withColor(WHILE));
        text.accept(x + 8, y + 35, Component.literal(node.describeAlwaysInterval()).withColor(LuneScreen.TEXT_DIM));
        drawPin(extractor, outputX(node), whileY(node), WHILE);
        if (contains(node, mouseX, mouseY) && !selectedNodes.contains(node)) {
            extractor.fill(x + 1, y + 1, x + NODE_W - 1, y + height - 1, 0x10FFFFFF);
        }
    }

    private void drawStartNode(GuiGraphicsExtractor extractor, RoutineNode node, int mouseX, int mouseY) {
        int x = nodeX(node);
        int y = nodeY(node);
        int height = nodeHeight(node);
        int border = selectedNodes.contains(node) ? NODE_SELECTED : SUCCESS;
        extractor.fill(x, y, x + NODE_W, y + height, border);
        extractor.fill(x + 1, y + 1, x + NODE_W - 1, y + height - 1, NODE_BG);
        extractor.fill(x + 1, y + 1, x + NODE_W - 1, y + HEADER_H, 0xFF247A48);
        var text = extractor.textRenderer();
        text.accept(x + 7, y + 5, Component.literal("START").withColor(0xFFFFFFFF));
        text.accept(x + 8, y + 23, Component.literal("entry point").withColor(SUCCESS));
        drawPin(extractor, outputX(node), successY(node), SUCCESS);
        if (contains(node, mouseX, mouseY) && !selectedNodes.contains(node)) {
            extractor.fill(x + 1, y + 1, x + NODE_W - 1, y + height - 1, 0x10FFFFFF);
        }
    }

    private void drawSignalRelayNode(GuiGraphicsExtractor extractor, RoutineNode node,
                                     int mouseX, int mouseY) {
        int x = nodeX(node);
        int y = nodeY(node);
        int height = nodeHeight(node);
        int border = selectedNodes.contains(node) ? NODE_SELECTED : SIGNAL;
        extractor.fill(x, y, x + NODE_W, y + height, border);
        extractor.fill(x + 1, y + 1, x + NODE_W - 1, y + height - 1, NODE_BG);
        extractor.fill(x + 1, y + 1, x + NODE_W - 1, y + HEADER_H, 0xFF68458A);
        var text = extractor.textRenderer();
        text.accept(x + 7, y + 5, Component.literal("Signal Relay").withColor(0xFFFFFFFF));
        text.accept(x + 8, y + 19, Component.literal("pulse junction").withColor(SIGNAL));
        for (int i = 0; i < node.signalInputCount; i++) {
            int portY = signalInputY(node, i);
            text.accept(x + 8, portY - 3, Component.literal("In " + (i + 1)).withColor(SIGNAL));
            drawPin(extractor, inputX(node), portY, SIGNAL);
        }
        for (int i = 0; i < node.signalOutputCount; i++) {
            int portY = signalOutputY(node, i);
            text.accept(x + NODE_W - 39, portY - 3,
                    Component.literal("Out " + (i + 1)).withColor(SIGNAL));
            drawPin(extractor, outputX(node), portY, SIGNAL);
        }
        if (contains(node, mouseX, mouseY) && !selectedNodes.contains(node)) {
            extractor.fill(x + 1, y + 1, x + NODE_W - 1, y + height - 1, 0x10FFFFFF);
        }
    }

    private void drawTimerNode(GuiGraphicsExtractor extractor, RoutineNode node,
                               int mouseX, int mouseY) {
        int x = nodeX(node);
        int y = nodeY(node);
        int height = nodeHeight(node);
        int border = selectedNodes.contains(node) ? NODE_SELECTED : SIGNAL;
        extractor.fill(x, y, x + NODE_W, y + height, border);
        extractor.fill(x + 1, y + 1, x + NODE_W - 1, y + height - 1, NODE_BG);
        extractor.fill(x + 1, y + 1, x + NODE_W - 1, y + HEADER_H, 0xFF68458A);
        var text = extractor.textRenderer();
        text.accept(x + 7, y + 5, Component.literal("Timer").withColor(0xFFFFFFFF));
        text.accept(x + 8, y + 19, Component.literal("pulse delay").withColor(SIGNAL));
        text.accept(x + 8, signalInputY(node, 0) - 3,
                Component.literal("In").withColor(SIGNAL));
        text.accept(x + NODE_W - 31, signalOutputY(node, 0) - 3,
                Component.literal("Out").withColor(SIGNAL));
        text.accept(x + 8, y + height - 14,
                Component.literal(node.describeRepeat()).withColor(LuneScreen.TEXT_DIM));
        drawPin(extractor, inputX(node), signalInputY(node, 0), SIGNAL);
        drawPin(extractor, outputX(node), signalOutputY(node, 0), SIGNAL);
        if (contains(node, mouseX, mouseY) && !selectedNodes.contains(node)) {
            extractor.fill(x + 1, y + 1, x + NODE_W - 1, y + height - 1, 0x10FFFFFF);
        }
    }

    private void drawCounterNode(GuiGraphicsExtractor extractor, RoutineNode node,
                                 int mouseX, int mouseY) {
        drawPulseCard(extractor, node, "Counter", "pulse counter", "In", "Out", SIGNAL,
                mouseX, mouseY, true);
    }

    private void drawEndNode(GuiGraphicsExtractor extractor, RoutineNode node,
                             int mouseX, int mouseY) {
        int x = nodeX(node);
        int y = nodeY(node);
        int height = nodeHeight(node);
        int border = selectedNodes.contains(node) ? NODE_SELECTED : FAILURE;
        extractor.fill(x, y, x + NODE_W, y + height, border);
        extractor.fill(x + 1, y + 1, x + NODE_W - 1, y + height - 1, NODE_BG);
        extractor.fill(x + 1, y + 1, x + NODE_W - 1, y + HEADER_H, 0xFF81383F);
        var text = extractor.textRenderer();
        text.accept(x + 7, y + 5, Component.literal("End").withColor(0xFFFFFFFF));
        text.accept(x + 8, y + 19, Component.literal("pulse sink").withColor(FAILURE));
        text.accept(x + 8, signalInputY(node, 0) - 3, Component.literal("In").withColor(FAILURE));
        drawPin(extractor, inputX(node), signalInputY(node, 0), FAILURE);
        if (contains(node, mouseX, mouseY) && !selectedNodes.contains(node)) {
            extractor.fill(x + 1, y + 1, x + NODE_W - 1, y + height - 1, 0x10FFFFFF);
        }
    }

    private void drawObserverNode(GuiGraphicsExtractor extractor, RoutineNode node,
                                  int mouseX, int mouseY) {
        drawSourcePulseCard(extractor, node, "Observer", "event source", SIGNAL, mouseX, mouseY);
    }

    private void drawButtonNode(GuiGraphicsExtractor extractor, RoutineNode node,
                                int mouseX, int mouseY) {
        drawSourcePulseCard(extractor, node, "Button", "manual source", SUCCESS, mouseX, mouseY);
    }

    private void drawPulseCard(GuiGraphicsExtractor extractor, RoutineNode node, String title,
                               String subtitle, String inputLabel, String outputLabel, int colour,
                               int mouseX, int mouseY, boolean footer) {
        int x = nodeX(node);
        int y = nodeY(node);
        int height = nodeHeight(node);
        int border = selectedNodes.contains(node) ? NODE_SELECTED : colour;
        extractor.fill(x, y, x + NODE_W, y + height, border);
        extractor.fill(x + 1, y + 1, x + NODE_W - 1, y + height - 1, NODE_BG);
        extractor.fill(x + 1, y + 1, x + NODE_W - 1, y + HEADER_H, 0xFF68458A);
        var text = extractor.textRenderer();
        text.accept(x + 7, y + 5, Component.literal(title).withColor(0xFFFFFFFF));
        text.accept(x + 8, y + 19, Component.literal(subtitle).withColor(colour));
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

    private void drawSourcePulseCard(GuiGraphicsExtractor extractor, RoutineNode node,
                                     String title, String subtitle, int colour,
                                     int mouseX, int mouseY) {
        int x = nodeX(node);
        int y = nodeY(node);
        int height = nodeHeight(node);
        int border = selectedNodes.contains(node) ? NODE_SELECTED : colour;
        extractor.fill(x, y, x + NODE_W, y + height, border);
        extractor.fill(x + 1, y + 1, x + NODE_W - 1, y + height - 1, NODE_BG);
        extractor.fill(x + 1, y + 1, x + NODE_W - 1, y + HEADER_H, 0xFF68458A);
        var text = extractor.textRenderer();
        text.accept(x + 7, y + 5, Component.literal(title).withColor(0xFFFFFFFF));
        text.accept(x + 8, y + 23, Component.literal(subtitle).withColor(colour));
        text.accept(x + NODE_W - 31, signalOutputY(node, 0) - 3,
                Component.literal("Out").withColor(colour));
        drawPin(extractor, outputX(node), signalOutputY(node, 0), colour);
        if (contains(node, mouseX, mouseY) && !selectedNodes.contains(node)) {
            extractor.fill(x + 1, y + 1, x + NODE_W - 1, y + height - 1, 0x10FFFFFF);
        }
    }

    private void showLogicTooltip(GuiGraphicsExtractor extractor, RoutineNode node,
                                  int mouseX, int mouseY) {
        if (!contains(node, mouseX, mouseY)) {
            return;
        }
        String logic = node.isStartNode()
                ? "Send one signal to the connected Success target to start this circuit."
                : node.isAlwaysNode()
                ? "Send a new signal to each connected target " + node.describeAlwaysInterval()
                + ". Each target is an independent circuit; Always does not need START."
                : node.isSignalRelayNode()
                ? "Forward a pulse arriving at any input through every connected output."
                : logicDescription(node);
        extractor.setComponentTooltipForNextFrame(Minecraft.getInstance().font,
                List.of(Component.literal("Logic").withColor(LuneScreen.ACCENT),
                        Component.literal(logic).withColor(LuneScreen.TEXT)),
                UiScale.toGamePixels(pointerX), UiScale.toGamePixels(pointerY));
    }

    private String logicDescription(RoutineNode node) {
        CommandDef def = CommandRegistry.byId(node.commandId);
        return def == null
                ? "This node is unavailable. Success follows Success; failure follows Fail."
                : def.logicDescription(node.repeat);
    }

    private boolean isActiveNode(RoutineNode candidate) {
        Task current = BotEngine.get().getCurrent();
        if (!(current instanceof RoutineTask running) || running.currentRoutine() != routine) {
            return false;
        }
        RoutineNode active = running.activeNode();
        return active != null && active.id.equals(candidate.id);
    }

    private boolean isFailedNode(RoutineNode candidate) {
        return routine != null && RoutineTask.isLastFailed(routine, candidate);
    }

    private void drawPin(GuiGraphicsExtractor extractor, int x, int y, int colour) {
        extractor.fill(x - 3, y - 3, x + 4, y + 4, 0xFF101014);
        extractor.fill(x - 2, y - 2, x + 3, y + 3, colour);
    }

    /** Samples a cubic curve; dense 2px points look smooth at Minecraft GUI scale. */
    private void drawWire(GuiGraphicsExtractor extractor, int x1, int y1, int x2, int y2, int colour) {
        int distance = Math.max(12, Math.abs(x2 - x1) + Math.abs(y2 - y1));
        int samples = Math.min(180, distance);
        double tangent = Math.max(28, Math.abs(x2 - x1) * 0.45);
        for (int i = 0; i <= samples; i++) {
            double t = i / (double) samples;
            double u = 1.0 - t;
            double x = u * u * u * x1
                    + 3 * u * u * t * (x1 + tangent)
                    + 3 * u * t * t * (x2 - tangent)
                    + t * t * t * x2;
            double y = u * u * u * y1 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y2;
            int px = (int) Math.round(x);
            int py = (int) Math.round(y);
            if (inside(px, py)) {
                extractor.fill(px, py, px + 2, py + 2, colour);
            }
        }
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (routine == null) {
            return;
        }
        pointerX = (int) event.x();
        pointerY = (int) event.y();

        if (event.button() == 2) {
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

        for (int i = routine.nodes.size() - 1; i >= 0; i--) {
            RoutineNode node = routine.nodes.get(i);
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
            if (node.isAlwaysNode()) {
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
            if (near(canvasPointerX, canvasPointerY, outputX(node), whileY(node))) {
                beginWire(node, WIRE_WHILE);
                return;
            }
        }

        RoutineNode hit = nodeAt(canvasPointerX, canvasPointerY);
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

    /** Right-click is intentionally reserved for cutting an existing explicit connection. */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 2) {
            if (isMouseOver(event.x(), event.y())) {
                onClick(event, doubleClick);
                setFocused(true);
                return true;
            }
            return false;
        }
        if (event.button() == 1) {
            if (routine != null && cutWireAt((int) event.x(), (int) event.y())) {
                return true;
            }
            return isMouseOver(event.x(), event.y());
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!isMouseOver(mouseX, mouseY) || routine == null) {
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

    private boolean cutWireAt(int x, int y) {
        x = canvasX(x);
        y = canvasY(y);
        for (RoutineNode source : routine.nodes) {
            if (source.isAlwaysNode() && source.alwaysTargets != null) {
                java.util.Iterator<String> targets = source.alwaysTargets.iterator();
                while (targets.hasNext()) {
                    RoutineNode target = routine.nodeById(targets.next());
                    if (target != null && nearWire(outputX(source), whileY(source),
                            inputX(target), targetInputY(target, source.alwaysTargetInputPorts == null
                                    ? 0 : source.alwaysTargetInputPorts.getOrDefault(target.id, 0)), x, y)) {
                        targets.remove();
                        if (source.alwaysTargetInputPorts != null) {
                            source.alwaysTargetInputPorts.remove(target.id);
                        }
                        onChanged.run();
                        onMessage.accept("Always connection cut");
                        return true;
                    }
                }
            }
            RoutineNode successTarget = routine.nodeById(source.onSuccess);
            if (successTarget != null && nearWire(outputX(source), successY(source),
                    inputX(successTarget), targetInputY(successTarget,
                            successTarget.isPulseNode() ? source.successInputPort : -1), x, y)) {
                source.onSuccess = null;
                onChanged.run();
                onMessage.accept("Success wire cut");
                return true;
            }
            RoutineNode failureTarget = routine.nodeById(source.onFailure);
            if (failureTarget != null && nearWire(outputX(source), failureY(source),
                    inputX(failureTarget), targetInputY(failureTarget,
                            failureTarget.isPulseNode() ? source.failureInputPort : -1), x, y)) {
                source.onFailure = null;
                onChanged.run();
                onMessage.accept("Failure wire cut");
                return true;
            }
            RoutineNode whileTarget = routine.nodeById(source.onWhile);
            if (whileTarget != null && nearWire(outputX(source), whileY(source),
                    inputX(whileTarget), targetInputY(whileTarget,
                            whileTarget.isPulseNode() ? source.whileInputPort : -1), x, y)) {
                source.onWhile = null;
                onChanged.run();
                onMessage.accept("While wire cut");
                return true;
            }
            if (source.isPulseNode() && source.signalLinks != null) {
                java.util.Iterator<RoutineSignalLink> signalLinks = source.signalLinks.iterator();
                while (signalLinks.hasNext()) {
                    RoutineSignalLink link = signalLinks.next();
                    RoutineNode target = link == null ? null : routine.nodeById(link.targetNodeId);
                    if (target != null && nearWire(outputX(source), signalOutputY(source, link.outputPort),
                            inputX(target), targetInputY(target, link.targetPort), x, y)) {
                        signalLinks.remove();
                        onChanged.run();
                        onMessage.accept("Pulse output wire cut");
                        return true;
                    }
                }
            }
        }
        for (RoutineNode target : routine.nodes) {
            if (target.inputLinks == null) {
                continue;
            }
            for (var entry : target.inputLinks.entrySet()) {
                RoutineDataLink link = entry.getValue();
                RoutineNode source = link == null ? null : routine.nodeById(link.sourceNodeId);
                int sourcePort = source == null ? -1 : exposedOutputs(source).indexOf(link.sourcePort);
                int targetPort = exposedInputs(target).indexOf(entry.getKey());
                if (source != null && sourcePort >= 0 && targetPort >= 0
                        && nearWire(outputX(source), dataOutputY(source, sourcePort),
                        inputX(target), dataInputY(target, targetPort), x, y)) {
                    target.inputLinks.remove(entry.getKey());
                    onChanged.run();
                    onMessage.accept("Data wire cut");
                    return true;
                }
            }
        }
        return false;
    }

    private boolean nearWire(int x1, int y1, int x2, int y2, int px, int py) {
        int samples = Math.min(180, Math.max(12, Math.abs(x2 - x1) + Math.abs(y2 - y1)));
        double tangent = Math.max(28, Math.abs(x2 - x1) * 0.45);
        int lastX = x1;
        int lastY = y1;
        for (int i = 1; i <= samples; i++) {
            double t = i / (double) samples;
            double u = 1.0 - t;
            int nextX = (int) Math.round(u * u * u * x1
                    + 3 * u * u * t * (x1 + tangent)
                    + 3 * u * t * t * (x2 - tangent)
                    + t * t * t * x2);
            int nextY = (int) Math.round(u * u * u * y1 + 3 * u * u * t * y1
                    + 3 * u * t * t * y2 + t * t * t * y2);
            if (distanceToSegment(px, py, lastX, lastY, nextX, nextY) <= 7.0) {
                return true;
            }
            lastX = nextX;
            lastY = nextY;
        }
        return false;
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

    private void beginWire(RoutineNode node, int type) {
        selectedNodes.clear();
        selectedNodes.add(node);
        selected = node;
        onSelect.accept(node);
        wireSource = node;
        wireType = type;
        wireDataPort = null;
        wireSignalPort = -1;
    }

    private void beginDataWire(RoutineNode node, String port) {
        selectedNodes.clear();
        selectedNodes.add(node);
        selected = node;
        onSelect.accept(node);
        wireSource = node;
        wireType = WIRE_DATA;
        wireDataPort = port;
        wireSignalPort = -1;
    }

    private void beginSignalWire(RoutineNode node, int outputPort) {
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
        if (wireSource != null) {
            return;
        }
        if (dragged != null) {
            int dx = (int) Math.round(dragX / zoom);
            int dy = (int) Math.round(dragY / zoom);
            for (RoutineNode node : selectedNodes) {
                node.editorX += dx;
                node.editorY += dy;
            }
            moved = true;
        } else if (selecting) {
            selectionMoved |= Math.abs(pointerX - selectionStartX) > 3
                    || Math.abs(pointerY - selectionStartY) > 3;
        } else if (panning) {
            panX += (int) Math.round(dragX);
            panY += (int) Math.round(dragY);
        }
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        pointerX = (int) event.x();
        pointerY = (int) event.y();
        if (minimapDragging) {
            minimapDragging = false;
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
                    RoutineDataLink old = target.node().inputLinks.put(target.parameterId(),
                            new RoutineDataLink(wireSource.id, wireDataPort));
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
            RoutineNode target = input == null ? null : input.node();
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
                        wireSource.signalLinks.add(new RoutineSignalLink(wireSignalPort,
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
            if (wireType == WIRE_WHILE && wireSource.isAlwaysNode()) {
                if (target == null || target.isAlwaysNode()) {
                    onMessage.accept("Drop Always on a command's In pin");
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
                        onMessage.accept("Always connected to " + nodeName(target));
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
            String oldTarget = switch (wireType) {
                case WIRE_FAILURE -> wireSource.onFailure;
                case WIRE_WHILE -> wireSource.onWhile;
                default -> wireSource.onSuccess;
            };
            String newTarget = target == null ? null : target.id;
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
                target.repeat = 0;
            }
            if (!java.util.Objects.equals(oldTarget, newTarget)) {
                onChanged.run();
                if (target == null) {
                    onMessage.accept(switch (wireType) {
                        case WIRE_FAILURE -> "Failure wire removed (failure now stops the task)";
                        case WIRE_WHILE -> "Always action removed";
                        default -> "Success wire removed (success now follows the next step)";
                    });
                } else {
                    String label = wireType == WIRE_FAILURE ? "Failure"
                            : wireType == WIRE_WHILE ? "While" : "Success";
                    onMessage.accept(label + " connected to " + nodeName(target));
                }
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
        if (!selectionMoved || routine == null) {
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
        for (RoutineNode node : routine.nodes) {
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
        if (routine == null) {
            return false;
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
            selectedNodes.addAll(routine.nodes);
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
        if (event.key() == GLFW.GLFW_KEY_ESCAPE && (wireSource != null || selecting || minimapDragging)) {
            wireSource = null;
            wireDataPort = null;
            selecting = false;
            minimapDragging = false;
            dragged = null;
            panning = false;
            return true;
        }
        return false;
    }

    private boolean controlDown() {
        long window = Minecraft.getInstance().getWindow().handle();
        return GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
    }

    private RoutineNode nodeAt(int x, int y) {
        if (routine == null) {
            return null;
        }
        for (int i = routine.nodes.size() - 1; i >= 0; i--) {
            RoutineNode node = routine.nodes.get(i);
            if (contains(node, x, y)) {
                return node;
            }
        }
        return null;
    }

    private ExecInputHit execInputAt(int x, int y) {
        if (routine == null) {
            return null;
        }
        for (RoutineNode node : routine.nodes) {
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
        if (routine == null) {
            return null;
        }
        for (RoutineNode node : routine.nodes) {
            List<String> inputs = exposedInputs(node);
            for (int i = 0; i < inputs.size(); i++) {
                if (near(x, y, inputX(node), dataInputY(node, i))) {
                    return new DataInputHit(node, inputs.get(i));
                }
            }
        }
        return null;
    }

    private boolean contains(RoutineNode node, int x, int y) {
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

    private int screenNodeY(RoutineNode node) {
        return getY() + panY + Math.round((node.editorY == null ? 0 : node.editorY) * zoom);
    }

    private int nodeX(RoutineNode node) {
        return node.editorX == null ? 0 : node.editorX;
    }

    private int nodeY(RoutineNode node) {
        return node.editorY == null ? 0 : node.editorY;
    }

    private int inputX(RoutineNode node) {
        return nodeX(node);
    }

    private int inputY(RoutineNode node) {
        return nodeY(node) + 29;
    }

    private int outputX(RoutineNode node) {
        return nodeX(node) + NODE_W;
    }

    private int successY(RoutineNode node) {
        return nodeY(node) + 29;
    }

    private int failureY(RoutineNode node) {
        return nodeY(node) + 44;
    }

    private int whileY(RoutineNode node) {
        return nodeY(node) + (node.isAlwaysNode() ? 29 : 59);
    }

    private int signalInputY(RoutineNode node, int index) {
        return nodeY(node) + 34 + index * 14;
    }

    private int signalOutputY(RoutineNode node, int index) {
        return nodeY(node) + 34 + index * 14;
    }

    private int targetInputY(RoutineNode node, int port) {
        if (node.isPulseNode()) {
            int maxPort = node.isSignalRelayNode() ? Math.max(0, node.signalInputCount - 1) : 0;
            return signalInputY(node, Math.clamp(port < 0 ? 0 : port, 0, maxPort));
        }
        return inputY(node);
    }

    private int dataInputY(RoutineNode node, int index) {
        return nodeY(node) + DATA_START_Y + index * DATA_ROW_HEIGHT;
    }

    private int dataOutputY(RoutineNode node, int index) {
        return nodeY(node) + DATA_START_Y + index * DATA_ROW_HEIGHT;
    }

    private int nodeHeight(RoutineNode node) {
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
        return rows == 0 ? NODE_H : DATA_START_Y + rows * DATA_ROW_HEIGHT + DATA_FOOTER_GAP;
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
        if (routine == null || routine.nodes.isEmpty()) {
            return new MinimapBounds(0, 0, getWidth(), getHeight());
        }
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (RoutineNode node : routine.nodes) {
            minX = Math.min(minX, nodeX(node));
            minY = Math.min(minY, nodeY(node));
            maxX = Math.max(maxX, nodeX(node) + NODE_W);
            maxY = Math.max(maxY, nodeY(node) + nodeHeight(node));
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
        if (routine == null || routine.onWhile == null
                || routine.nodes.stream().anyMatch(RoutineNode::isAlwaysNode)) {
            return;
        }
        RoutineNode target = routine.nodeById(routine.onWhile);
        if (target == null) {
            return;
        }
        int x = 6;
        int y = 5;
        int width = 56;
        int height = 18;
        int sourceX = x + width;
        int sourceY = y + height / 2;
        drawWire(extractor, sourceX, sourceY, inputX(target), inputY(target), WHILE);
        extractor.fill(x, y, x + width, y + height, 0xD0202028);
        extractor.outline(x, y, width, height, WHILE);
        extractor.textRenderer().accept(x + 6, y + 5,
                Component.literal("Always").withColor(LuneScreen.TEXT));
        extractor.fill(x + width - 5, y + 6, x + width + 1, y + 12, WHILE);
    }

    private void drawMinimap(GuiGraphicsExtractor extractor) {
        if (routine == null || routine.nodes.isEmpty()) {
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

        for (RoutineNode source : routine.nodes) {
            if (source.isAlwaysNode() && source.alwaysTargets != null) {
                for (String targetId : source.alwaysTargets) {
                    RoutineNode target = routine.nodeById(targetId);
                    if (target != null) {
                        drawMinimapEdge(extractor, bounds, scale, outputX(source), whileY(source),
                                target, WHILE, source.alwaysTargetInputPorts == null
                                        ? 0 : source.alwaysTargetInputPorts.getOrDefault(target.id, 0));
                    }
                }
            }
            drawMinimapEdge(extractor, bounds, scale, outputX(source), successY(source),
                    routine.nodeById(source.onSuccess), SUCCESS,
                    routine.nodeById(source.onSuccess) != null && routine.nodeById(source.onSuccess).isPulseNode()
                            ? source.successInputPort : -1);
            drawMinimapEdge(extractor, bounds, scale, outputX(source), failureY(source),
                    routine.nodeById(source.onFailure), FAILURE,
                    routine.nodeById(source.onFailure) != null && routine.nodeById(source.onFailure).isPulseNode()
                            ? source.failureInputPort : -1);
            drawMinimapEdge(extractor, bounds, scale, outputX(source), whileY(source),
                    routine.nodeById(source.onWhile), WHILE,
                    routine.nodeById(source.onWhile) != null && routine.nodeById(source.onWhile).isPulseNode()
                            ? source.whileInputPort : -1);
            if (source.isPulseNode() && source.signalLinks != null) {
                for (RoutineSignalLink link : source.signalLinks) {
                    if (link == null) {
                        continue;
                    }
                    RoutineNode target = routine.nodeById(link.targetNodeId);
                    if (target != null) {
                        drawMinimapEdge(extractor, bounds, scale,
                                outputX(source), signalOutputY(source, link.outputPort), target, SIGNAL,
                                link.targetPort);
                    }
                }
            }
            if (source.inputLinks != null) {
                for (var entry : source.inputLinks.entrySet()) {
                    RoutineDataLink link = entry.getValue();
                    RoutineNode dataSource = link == null ? null : routine.nodeById(link.sourceNodeId);
                    int sourcePort = dataSource == null ? -1
                            : exposedOutputs(dataSource).indexOf(link.sourcePort);
                    int targetPort = exposedInputs(source).indexOf(entry.getKey());
                    if (dataSource != null && sourcePort >= 0 && targetPort >= 0) {
                        drawMinimapLine(extractor,
                                minimapX(bounds, outputX(dataSource), scale),
                                minimapY(bounds, dataOutputY(dataSource, sourcePort), scale),
                                minimapX(bounds, inputX(source), scale),
                                minimapY(bounds, dataInputY(source, targetPort), scale), DATA);
                    }
                }
            }
        }

        for (RoutineNode node : routine.nodes) {
            int nodeLeft = minimapX(bounds, nodeX(node), scale);
            int nodeTop = minimapY(bounds, nodeY(node), scale);
            int nodeRight = minimapX(bounds, nodeX(node) + NODE_W, scale);
            int nodeBottom = minimapY(bounds, nodeY(node) + nodeHeight(node), scale);
            int colour = selectedNodes.contains(node) ? NODE_SELECTED
                    : node.isAlwaysNode() ? WHILE
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
                                 int sourceX, int sourceY, RoutineNode target, int colour) {
        drawMinimapEdge(extractor, bounds, scale, sourceX, sourceY, target, colour, -1);
    }

    private void drawMinimapEdge(GuiGraphicsExtractor extractor, MinimapBounds bounds, double scale,
                                 int sourceX, int sourceY, RoutineNode target, int colour,
                                 int targetPort) {
        if (target == null) {
            return;
        }
        drawMinimapLine(extractor, minimapX(bounds, sourceX, scale), minimapY(bounds, sourceY, scale),
                minimapX(bounds, inputX(target), scale),
                minimapY(bounds, targetInputY(target, targetPort), scale), colour);
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

    private record MinimapBounds(double minX, double minY, double maxX, double maxY) {
        private double width() {
            return maxX - minX;
        }

        private double height() {
            return maxY - minY;
        }
    }

    private List<String> exposedInputs(RoutineNode node) {
        return node.exposedInputs == null ? List.of() : List.copyOf(node.exposedInputs);
    }

    private List<String> exposedOutputs(RoutineNode node) {
        return node.exposedOutputs == null ? List.of() : List.copyOf(node.exposedOutputs);
    }

    private Param<?> parameter(RoutineNode node, String parameterId) {
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

    private boolean compatibleDataPorts(RoutineNode source, String sourcePort,
                                        RoutineNode target, String targetParameter) {
        Param<?> sourceParam = parameter(source, sourcePort);
        Param<?> targetParam = parameter(target, targetParameter);
        return sourceParam != null && targetParam != null
                && sourceParam.dataType() == targetParam.dataType();
    }

    private String parameterLabel(RoutineNode node, String parameterId) {
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

    private int wireY(RoutineNode node, int type) {
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

    private static int wireColour(int type) {
        return type == WIRE_DATA ? DATA
                : type == WIRE_SIGNAL ? SIGNAL
                : type == WIRE_FAILURE ? FAILURE : type == WIRE_WHILE ? WHILE : SUCCESS;
    }

    private record DataInputHit(RoutineNode node, String parameterId) {}
    private record ExecInputHit(RoutineNode node, int port) {}

    private static String nodeName(RoutineNode node) {
        if (node.isStartNode()) {
            return "START";
        }
        if (node.isAlwaysNode()) {
            return "Always";
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
