package com.etka.lune.task;

/**
 * Where a card sits on the blueprint canvas and where its pins are.
 *
 * <p>The editor draws these and {@link DefaultTasks} lays cables around them, so both need the same
 * numbers. They used to live only in the panel, which meant a seeded cable route was written
 * against a card width nothing enforced - move a pin by three pixels and every shipped route would
 * quietly start cutting a corner.</p>
 */
public final class TaskCanvas {

    public static final int CARD_WIDTH = 124;
    public static final int CARD_HEIGHT = 72;

    /** The input pin and the Success pin share a row; Fail sits below it, While below that. */
    public static final int INPUT_OFFSET_Y = 29;
    public static final int SUCCESS_OFFSET_Y = 29;
    public static final int FAILURE_OFFSET_Y = 44;
    public static final int WHILE_OFFSET_Y = 59;

    /** A clock has no Success/Fail rows, so its fan-out pin sits on the first row. */
    public static final int CLOCK_OUTPUT_OFFSET_Y = 29;

    private TaskCanvas() {}

    public static int left(TaskNode node) {
        return node == null || node.editorX == null ? 0 : node.editorX;
    }

    public static int top(TaskNode node) {
        return node == null || node.editorY == null ? 0 : node.editorY;
    }

    public static int inputX(TaskNode node) {
        return left(node);
    }

    public static int outputX(TaskNode node) {
        return left(node) + CARD_WIDTH;
    }

    public static int inputY(TaskNode node) {
        return top(node) + INPUT_OFFSET_Y;
    }

    public static int successY(TaskNode node) {
        return top(node) + SUCCESS_OFFSET_Y;
    }

    public static int failureY(TaskNode node) {
        return top(node) + FAILURE_OFFSET_Y;
    }

    public static int whileY(TaskNode node) {
        return top(node) + (node != null && node.isClockNode()
                ? CLOCK_OUTPUT_OFFSET_Y : WHILE_OFFSET_Y);
    }

    /** True when a point would be drawn on top of a card rather than in the space between them. */
    public static boolean insideCard(TaskNode node, int x, int y) {
        return x >= left(node) && x <= left(node) + CARD_WIDTH
                && y >= top(node) && y <= top(node) + CARD_HEIGHT;
    }
}
