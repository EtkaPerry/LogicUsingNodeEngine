package com.etka.lune.task;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToIntFunction;

/**
 * A named frame drawn around a handful of cards.
 *
 * <p>Editor-only, like {@link TaskNote}: a group has no pins, carries no signal and is never asked
 * to run. It answers the question a large task stops being able to answer on its own - "which of
 * these forty cards are the smelting part?" - and it does it without moving a card or touching a
 * wire, so the graph that runs is byte-for-byte the graph that ran before it was drawn.</p>
 *
 * <h2>Membership is by id, and the frame follows the members</h2>
 *
 * <p>The alternative - whatever happens to be inside the rectangle belongs to it - reads well until
 * two frames overlap or a card is nudged a pixel over the line, at which point a card silently
 * changes owner. Ids are explicit: a card joins when the player puts it in and leaves when they
 * take it out. The rectangle is then recomputed from the members every frame, which is why
 * {@link #fit} exists and why {@link #x} and friends are a cache rather than the truth. The one
 * time they are the truth is while the group is collapsed, because there are no visible members to
 * measure.</p>
 */
public final class TaskGroup {

    public static final int HEADER_HEIGHT = 14;
    /** Breathing room between the frame and the cards inside it. */
    public static final int PADDING = 12;
    public static final int MIN_WIDTH = 96;
    public static final int COLLAPSED_WIDTH = 168;
    public static final int MAX_TITLE = 48;

    public String id = UUID.randomUUID().toString().substring(0, 8);

    public String title = "";

    /** Card ids inside this frame, in the order they were added. */
    public Set<String> members = new LinkedHashSet<>();

    /** Recomputed from the members while open; the last fitted rectangle while collapsed. */
    public int x;
    public int y;
    public int width = MIN_WIDTH;
    public int height = HEADER_HEIGHT;

    /** An index into the editor's note palette, on the same terms as {@link TaskNote#colour}. */
    public int colour;

    /**
     * Whether the members are hidden behind the title bar.
     *
     * <p>Collapsing is the reason to group at all on a task big enough to need it. Wires that leave
     * the group are still drawn - to the bar, because that is where their far end now is - so a
     * collapsed group reads as one card with a lot going on inside it rather than as a piece of
     * the task that has gone missing.</p>
     */
    public boolean collapsed;

    public TaskGroup() {}

    public TaskGroup(String title) {
        this.title = title;
    }

    public TaskGroup copy() {
        TaskGroup copy = new TaskGroup(title);
        copy.members.addAll(members);
        copy.x = x;
        copy.y = y;
        copy.width = width;
        copy.height = height;
        copy.colour = colour;
        copy.collapsed = collapsed;
        return copy;
    }

    public boolean holds(TaskNode node) {
        return node != null && node.id != null && members.contains(node.id);
    }

    public int right() {
        return x + Math.max(MIN_WIDTH, width);
    }

    /**
     * The foot of the frame.
     *
     * <p>Zero-height on purpose while collapsed: a closed frame is its title bar and nothing else,
     * so a click a few pixels below it belongs to the canvas underneath rather than to the frame.
     * The bar itself sits above {@link #y}, which is why {@link #contains} still finds it.</p>
     */
    public int bottom() {
        return y + Math.max(0, height);
    }

    /** The title bar: what is dragged, renamed and clicked. The body below it is not clickable. */
    public boolean headerContains(int pointX, int pointY) {
        return pointX >= x && pointX < right() && pointY >= y - HEADER_HEIGHT && pointY < y;
    }

    public boolean contains(int pointX, int pointY) {
        return pointX >= x && pointX < right() && pointY >= y - HEADER_HEIGHT && pointY < bottom();
    }

    /** The cards this group holds, in task order, skipping ids that no longer exist. */
    public List<TaskNode> membersOf(TaskGraph task) {
        List<TaskNode> found = new ArrayList<>();
        if (task == null || task.nodes == null) {
            return found;
        }
        for (TaskNode node : task.nodes) {
            if (holds(node)) {
                found.add(node);
            }
        }
        return found;
    }

    /**
     * Re-measures the frame around its members.
     *
     * <p>{@code heightOf} is passed in because a card's height depends on how many data pins the
     * player has exposed on it, and only the canvas knows that. A group that measured cards as a
     * fixed height would clip the tall ones, which is exactly the card the player grouped because
     * it was complicated.</p>
     *
     * @return false when the group has no surviving members and should be dropped
     */
    public boolean fit(TaskGraph task, ToIntFunction<TaskNode> heightOf) {
        List<TaskNode> found = membersOf(task);
        if (found.isEmpty()) {
            return false;
        }
        if (collapsed) {
            // Nothing visible to measure. The bar keeps the shape it had when it was closed, so
            // reopening puts the cards back exactly where the player left them.
            width = Math.max(MIN_WIDTH, COLLAPSED_WIDTH);
            height = 0;
            return true;
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (TaskNode node : found) {
            int left = TaskCanvas.left(node);
            int top = TaskCanvas.top(node);
            minX = Math.min(minX, left);
            minY = Math.min(minY, top);
            maxX = Math.max(maxX, left + TaskCanvas.CARD_WIDTH);
            maxY = Math.max(maxY, top + Math.max(1, heightOf.applyAsInt(node)));
        }
        x = minX - PADDING;
        y = minY - PADDING;
        width = Math.max(MIN_WIDTH, maxX - minX + PADDING * 2);
        height = maxY - minY + PADDING * 2;
        return true;
    }

    /** Moves every member, which is the whole of what dragging a group does. */
    public void moveBy(TaskGraph task, int dx, int dy) {
        x += dx;
        y += dy;
        for (TaskNode node : membersOf(task)) {
            node.editorX = TaskCanvas.left(node) + dx;
            node.editorY = TaskCanvas.top(node) + dy;
        }
    }

    /** The group holding this card, or null. A card belongs to at most one. */
    public static TaskGroup holding(TaskGraph task, TaskNode node) {
        if (task == null || task.groups == null || node == null) {
            return null;
        }
        for (TaskGroup group : task.groups) {
            if (group != null && group.holds(node)) {
                return group;
            }
        }
        return null;
    }

    /** True when this card is inside a group that is currently closed, and so is not drawn. */
    public static boolean isHidden(TaskGraph task, TaskNode node) {
        TaskGroup group = holding(task, node);
        return group != null && group.collapsed;
    }

    /**
     * Frames the given cards, taking them out of whatever group already held them.
     *
     * <p>Taking them out first is what keeps "at most one group" true. Without it a card grouped
     * twice would be drawn inside two frames and moved twice by a drag of either.</p>
     */
    public static TaskGroup group(TaskGraph task, List<TaskNode> nodes, String title) {
        if (task == null || nodes == null || nodes.isEmpty()) {
            return null;
        }
        TaskGroup created = new TaskGroup(title == null ? "" : title);
        for (TaskNode node : nodes) {
            if (node != null && node.id != null) {
                created.members.add(node.id);
            }
        }
        if (created.members.isEmpty()) {
            return null;
        }
        if (task.groups == null) {
            task.groups = new ArrayList<>();
        }
        for (TaskGroup existing : task.groups) {
            if (existing != null) {
                existing.members.removeAll(created.members);
            }
        }
        task.groups.removeIf(existing -> existing == null || existing.members.isEmpty());
        task.groups.add(created);
        return created;
    }

    /** Removes the groups holding any of these cards. Returns how many frames were dropped. */
    public static int ungroup(TaskGraph task, List<TaskNode> nodes) {
        if (task == null || task.groups == null || nodes == null) {
            return 0;
        }
        Set<TaskGroup> doomed = new LinkedHashSet<>();
        for (TaskNode node : nodes) {
            TaskGroup group = holding(task, node);
            if (group != null) {
                doomed.add(group);
            }
        }
        task.groups.removeAll(doomed);
        return doomed.size();
    }

    /** Drops ids for cards that no longer exist, and any frame left with nothing in it. */
    public static void prune(TaskGraph task) {
        if (task == null || task.groups == null) {
            return;
        }
        Set<String> alive = new LinkedHashSet<>();
        if (task.nodes != null) {
            for (TaskNode node : task.nodes) {
                if (node != null && node.id != null) {
                    alive.add(node.id);
                }
            }
        }
        for (TaskGroup group : task.groups) {
            if (group != null && group.members != null) {
                group.members.retainAll(alive);
            }
        }
        task.groups.removeIf(group -> group == null || group.members == null
                || group.members.isEmpty());
    }
}
