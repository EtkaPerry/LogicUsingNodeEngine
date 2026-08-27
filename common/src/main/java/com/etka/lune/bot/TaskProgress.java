package com.etka.lune.bot;

/** A small, UI-facing snapshot for work with a concrete finish line. */
public record TaskProgress(int completed, int target, String unit) {

    public TaskProgress {
        completed = Math.max(0, completed);
        target = Math.max(1, target);
        unit = unit == null ? "" : unit.trim();
    }

    public float fraction() {
        return Math.clamp((float) completed / target, 0.0F, 1.0F);
    }

    public String label() {
        return completed + " / " + target + (unit.isEmpty() ? "" : " " + unit);
    }
}
