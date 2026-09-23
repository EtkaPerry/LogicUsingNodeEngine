package com.etka.lune.client.gui.widget;

import com.etka.lune.config.TaskView;
import com.etka.lune.task.TaskGraph;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Moving the task canvas's camera, and putting it back.
 *
 * <p>A screen hands a widget drags and releases for the left button only, so the canvas used to
 * hear a middle press and nothing after it: the view never moved, and the pan stayed armed for
 * whichever left drag came next. The button is named rather than numbered here for the same
 * reason the canvas now names it - 26.3 counts the buttons from 1.</p>
 */
class BlueprintPanelViewTest {

    private static BlueprintPanel canvas() {
        BlueprintPanel panel = new BlueprintPanel(0, 0, 400, 300, node -> {}, () -> {},
                message -> {}, nodes -> {});
        panel.setTask(new TaskGraph("View"));
        return panel;
    }

    private static MouseButtonEvent middle(double x, double y) {
        return new MouseButtonEvent(x, y,
                new MouseButtonInfo(InputConstants.MOUSE_BUTTON_MIDDLE, 0));
    }

    @Test
    void aMiddleDragMovesTheViewUntilTheButtonComesUp() {
        BlueprintPanel panel = canvas();

        assertTrue(panel.mouseClicked(middle(100, 100), false));
        assertTrue(panel.mouseDragged(middle(130, 90), 30, -10));
        assertEquals(30, panel.view().panX);
        assertEquals(-10, panel.view().panY);

        assertTrue(panel.mouseReleased(middle(130, 90)));
        assertFalse(panel.mouseDragged(middle(160, 90), 30, 0),
                "after the release the drag is no longer the canvas's to act on");
        assertEquals(30, panel.view().panX);
    }

    @Test
    void aSlowDragStillMovesTheView() {
        BlueprintPanel panel = canvas();
        panel.mouseClicked(middle(100, 100), false);

        // A quarter of a pixel at a time, which each rounded to nothing when every event was
        // rounded on its own.
        for (int i = 1; i <= 12; i++) {
            panel.mouseDragged(middle(100 + i * 0.25, 100 - i * 0.25), 0.25, -0.25);
        }

        assertEquals(3, panel.view().panX);
        assertEquals(-3, panel.view().panY);
    }

    @Test
    void aMiddlePressThatMissedTheCanvasDoesNotArmAPan() {
        BlueprintPanel panel = canvas();

        assertFalse(panel.mouseClicked(middle(500, 100), false));
        assertFalse(panel.mouseDragged(middle(520, 100), 20, 0));
        assertEquals(0, panel.view().panX);
    }

    @Test
    void aRememberedViewIsPutBack() {
        BlueprintPanel panel = canvas();

        panel.showView(new TaskView(-240, 80, 1.5F));
        assertEquals(-240, panel.view().panX);
        assertEquals(80, panel.view().panY);
        assertEquals(1.5F, panel.view().zoom);

        panel.showView(null);
        assertEquals(0, panel.view().panX, "a task with no view opens at home, as Ctrl+0 leaves it");
        assertEquals(0, panel.view().panY);
        assertEquals(1.0F, panel.view().zoom);
    }

    @Test
    void aViewEditedIntoNonsenseIsReinedIn() {
        BlueprintPanel panel = canvas();

        panel.showView(new TaskView(0, 0, Float.NaN));
        assertEquals(1.0F, panel.view().zoom);

        panel.showView(new TaskView(0, 0, 40.0F));
        assertEquals(2.0F, panel.view().zoom, "held to the zoom the canvas itself allows");
    }
}
