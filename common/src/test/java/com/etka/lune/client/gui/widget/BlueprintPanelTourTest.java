package com.etka.lune.client.gui.widget;

import com.etka.lune.config.TaskView;
import com.etka.lune.task.TaskGraph;
import com.etka.lune.task.TaskNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the tour asks of the canvas on its settings stop: where the card it picked is drawn, so it
 * can be lit beside its settings, and the camera moved onto it when it is off the canvas.
 */
class BlueprintPanelTourTest {

    private static final int LEFT = 20;
    private static final int TOP = 30;

    /**
     * A Start card: its height is fixed, where a working card's asks the config whether the node
     * stats row is shown, and the config cannot be loaded in a headless test. The tour picks
     * working cards; where one is drawn is the same arithmetic.
     */
    private static TaskNode card(TaskGraph task, int x, int y) {
        TaskNode node = new TaskNode(TaskNode.START_COMMAND);
        node.editorX = x;
        node.editorY = y;
        task.nodes.add(node);
        return node;
    }

    private static BlueprintPanel canvas(TaskGraph task) {
        BlueprintPanel panel = new BlueprintPanel(LEFT, TOP, 400, 300, node -> {}, () -> {},
                message -> {}, nodes -> {});
        panel.setTask(task);
        panel.showView(new TaskView(0, 0, 1.0F));
        return panel;
    }

    @Test
    void aCardInViewIsFoundWhereItIsDrawn() {
        TaskGraph task = new TaskGraph("Tour");
        TaskNode mine = card(task, 40, 60);
        BlueprintPanel panel = canvas(task);

        Spotlight.Box box = panel.cardOnScreen(mine);
        assertNotNull(box);
        assertEquals(LEFT + 40, box.x());
        assertEquals(TOP + 60, box.y());

        // Halved, the card is drawn half the size, from half as far in.
        panel.showView(new TaskView(10, 0, 0.5F));
        Spotlight.Box zoomed = panel.cardOnScreen(mine);
        assertEquals(LEFT + 10 + 20, zoomed.x());
        assertEquals(box.width() / 2, zoomed.width(), 1);
    }

    @Test
    void aCardOffTheCanvasOrNotOnItIsNotLit() {
        TaskGraph task = new TaskGraph("Tour");
        TaskNode far = card(task, 2000, 60);
        TaskNode elsewhere = new TaskNode(TaskNode.START_COMMAND);
        BlueprintPanel panel = canvas(task);

        assertNull(panel.cardOnScreen(far), "a card past the canvas's edge is not on it");
        assertNull(panel.cardOnScreen(elsewhere), "a card of another task is not on this canvas");
        assertNull(panel.cardOnScreen(null));
    }

    @Test
    void aCardOffTheCanvasIsBroughtIntoTheMiddleAtTheSameZoom() {
        TaskGraph task = new TaskGraph("Tour");
        TaskNode far = card(task, 2000, 900);
        BlueprintPanel panel = canvas(task);
        panel.showView(new TaskView(0, 0, 0.75F));

        panel.bringIntoView(far);

        Spotlight.Box box = panel.cardOnScreen(far);
        assertNotNull(box, "the card is on the canvas whole once it has been brought into view");
        assertEquals(0.75F, panel.view().zoom);
        assertEquals(LEFT + 200, box.centreX(), 2);
        assertEquals(TOP + 150, box.centreY(), 2);
    }

    @Test
    void aCardAlreadyInViewDoesNotMoveTheCamera() {
        TaskGraph task = new TaskGraph("Tour");
        TaskNode near = card(task, 40, 60);
        BlueprintPanel panel = canvas(task);
        panel.showView(new TaskView(12, -8, 1.0F));

        panel.bringIntoView(near);

        assertEquals(12, panel.view().panX);
        assertEquals(-8, panel.view().panY);
        assertTrue(panel.cardOnScreen(near) != null);
    }
}
