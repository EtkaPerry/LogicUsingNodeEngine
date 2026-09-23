package com.etka.lune.client.gui.widget;

import com.etka.lune.task.TaskGraph;
import com.etka.lune.task.TaskNode;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Picking one cable out of a busy canvas with the middle button.
 *
 * <p>The cable here runs from a START card to an End card placed so it is a straight line, which
 * puts a point on it where arithmetic says it is. START and End are used because their height is
 * fixed: an ordinary card measures itself against the config, and there is no config in a unit
 * test.</p>
 */
class BlueprintCableTraceTest {

    private static final String CABLE = "success|start|end";
    /** Halfway along the cable, which runs level at y 129 from x 124 to x 300. */
    private static final double ON_CABLE_X = 212;
    private static final double ON_CABLE_Y = 129;

    private final List<String> messages = new ArrayList<>();
    private BlueprintPanel panel;

    @BeforeEach
    void canvasWithOneCable() {
        TaskGraph task = new TaskGraph("Trace");
        TaskNode start = card(TaskNode.START_COMMAND, "start", 0, 100);
        // An End takes its signal on a pin five pixels lower than a START sends it, so raising
        // the End by five makes the cable a straight line.
        TaskNode end = card(TaskNode.END_COMMAND, "end", 300, 95);
        start.onSuccess = end.id;
        task.nodes.add(start);
        task.nodes.add(end);
        panel = new BlueprintPanel(0, 0, 800, 400, node -> {}, () -> {}, messages::add,
                nodes -> {});
        panel.setTask(task);
    }

    @Test
    void aMiddleClickOnACableMakesItShine() {
        click(ON_CABLE_X, ON_CABLE_Y);
        assertEquals(CABLE, panel.tracedCable());
        assertFalse(messages.isEmpty(), "the player is told which two cards the cable joins");
    }

    @Test
    void aSecondClickOnTheSameCableLetsItGo() {
        click(ON_CABLE_X, ON_CABLE_Y);
        click(ON_CABLE_X, ON_CABLE_Y);
        assertNull(panel.tracedCable());
    }

    @Test
    void aClickOnEmptyCanvasLetsItGo() {
        click(ON_CABLE_X, ON_CABLE_Y);
        click(500, 300);
        assertNull(panel.tracedCable());
    }

    /** Following a long cable is a pan, and a pan must not put the cable out. */
    @Test
    void panningAwayFromIt() {
        click(ON_CABLE_X, ON_CABLE_Y);

        assertTrue(panel.mouseClicked(middle(500, 300), false));
        panel.mouseDragged(middle(540, 300), 40, 0);
        panel.mouseReleased(middle(540, 300));

        assertEquals(CABLE, panel.tracedCable());
        assertEquals(40, panel.view().panX, "the drag still moved the view");
    }

    @Test
    void aPanThatStartsOnTheTracedCableKeepsItToo() {
        click(ON_CABLE_X, ON_CABLE_Y);

        panel.mouseClicked(middle(ON_CABLE_X, ON_CABLE_Y), false);
        panel.mouseDragged(middle(ON_CABLE_X + 30, ON_CABLE_Y), 30, 0);
        panel.mouseReleased(middle(ON_CABLE_X + 30, ON_CABLE_Y));

        assertEquals(CABLE, panel.tracedCable());
    }

    /** A card covers the cables under it; pressing on one is a pan and nothing else. */
    @Test
    void aPressOnACardTracesNothing() {
        click(40, 110);
        assertNull(panel.tracedCable());
    }

    @Test
    void escapeLetsItGo() {
        click(ON_CABLE_X, ON_CABLE_Y);
        assertTrue(panel.keyPressed(new KeyEvent(InputConstants.KEY_ESCAPE, 0, 0)));
        assertNull(panel.tracedCable());
    }

    @Test
    void anotherTaskStartsWithNothingTraced() {
        click(ON_CABLE_X, ON_CABLE_Y);
        panel.setTask(new TaskGraph("Other"));
        assertNull(panel.tracedCable());
    }

    private void click(double x, double y) {
        assertTrue(panel.mouseClicked(middle(x, y), false));
        assertTrue(panel.mouseReleased(middle(x, y)));
    }

    private static MouseButtonEvent middle(double x, double y) {
        return new MouseButtonEvent(x, y,
                new MouseButtonInfo(InputConstants.MOUSE_BUTTON_MIDDLE, 0));
    }

    private static TaskNode card(String command, String id, int x, int y) {
        TaskNode node = new TaskNode(command);
        node.id = id;
        node.editorX = x;
        node.editorY = y;
        return node;
    }
}
