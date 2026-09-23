package com.etka.lune.config;

/**
 * Where the task canvas was looking: how far the canvas is shifted inside the panel, in the
 * panel's own pixels, and the zoom. A plain object because it is written into
 * {@code config/lune.json}; see {@link BotConfig#taskViews}.
 */
public final class TaskView {

    public int panX;
    public int panY;
    public float zoom = 1.0F;

    public TaskView() {}

    public TaskView(int panX, int panY, float zoom) {
        this.panX = panX;
        this.panY = panY;
        this.zoom = zoom;
    }

    public boolean sameAs(TaskView other) {
        return other != null && panX == other.panX && panY == other.panY
                && Float.compare(zoom, other.zoom) == 0;
    }
}
