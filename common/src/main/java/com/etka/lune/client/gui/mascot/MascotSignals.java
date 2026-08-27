package com.etka.lune.client.gui.mascot;

import java.util.Locale;

/** Shared, client-independent interpretation of task status text for mascot states. */
final class MascotSignals {

    enum Signal {
        NONE,
        WAITING,
        BLOCKED,
        DANGER,
        SUCCESS,
        INVENTORY_FULL,
        MISSING_MATERIALS
    }

    private static final String[] SUCCESS_MARKERS = {
            "threat gone",
            "landed safely",
            "escaped lava",
            "water placed",
            "preservation complete",
            "all done"
    };

    private static final String[] DANGER_MARKERS = {
            "low health",
            "health dropped below",
            "health did not recover",
            "health recovers",
            "health regeneration",
            "recovering health",
            "escaping to air",
            "drowning",
            "lava",
            "creeper",
            "evading",
            "retreating from",
            "emergency preservation",
            "emergency cover",
            "falling through",
            "waiting to land",
            "nothing below to land on",
            "placing water",
            "water out of reach",
            "attacked by another player",
            "player is dead",
            "player died",
            "fighting ",
            "lowering shield",
            "drawing bow at",
            "charging bow at",
            "backing off from",
            "moving away from",
            "building cover from",
            "under two-block shelter",
            "luring enderman",
            "enderman shelter",
            "avoiding enderman",
            "arrow line",
            "waiting for fire resistance"
    };

    private static final String[] INVENTORY_FULL_MARKERS = {
            "inventory full",
            "inventory is full",
            "no free inventory slot",
            "no free slot",
            "inventory has no room"
    };

    private static final String[] MISSING_MATERIAL_MARKERS = {
            "missing materials",
            "no materials",
            "not enough materials",
            "missing ingredient",
            "no food",
            "out of food",
            "no fuel",
            "no furnace",
            "no fishing rod",
            "no eyes of ender",
            "no boat available",
            "no bridge blocks",
            "no tool",
            "no water bucket",
            "no empty bucket",
            "nothing left to light",
            "no support for",
            "need a solid block",
            "no blocks in inventory",
            "no usable flower",
            "is no longer in the inventory",
            "not in inventory"
    };

    private static final String[] BLOCKED_MARKERS = {
            "blocked",
            "stuck",
            "unreachable",
            "cannot reach",
            "can't reach",
            "could not reach",
            "no route",
            "no walking route",
            "no reachable place",
            "made no progress",
            "walled in",
            "cannot get any closer",
            "could not enter",
            "can't open",
            "cannot break",
            "could not clear",
            "can't place",
            "could not place",
            "no stable ground",
            "no nearby walkable surface",
            "could not find a walkable surface"
    };

    private static final String[] WAITING_MARKERS = {
            "waiting",
            "pausing",
            "cooling down",
            "holding position",
            "standing by",
            "watching"
    };

    private MascotSignals() {}

    static Signal classify(String status) {
        if (status == null || status.isBlank()) {
            return Signal.NONE;
        }
        String lower = status.toLowerCase(Locale.ROOT);
        if (contains(lower, SUCCESS_MARKERS)) {
            return Signal.SUCCESS;
        }
        if (contains(lower, DANGER_MARKERS)) {
            return Signal.DANGER;
        }
        if (contains(lower, INVENTORY_FULL_MARKERS)) {
            return Signal.INVENTORY_FULL;
        }
        if (contains(lower, MISSING_MATERIAL_MARKERS)) {
            return Signal.MISSING_MATERIALS;
        }
        if (contains(lower, BLOCKED_MARKERS)) {
            return Signal.BLOCKED;
        }
        if (contains(lower, WAITING_MARKERS)) {
            return Signal.WAITING;
        }
        return Signal.NONE;
    }

    static Signal classifyMilestone(String message) {
        Signal signal = classify(message);
        if (signal != Signal.NONE) {
            return signal;
        }
        if (message == null || message.isBlank()) {
            return Signal.NONE;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains(" finished") || lower.endsWith("finished")) {
            return Signal.SUCCESS;
        }
        if (lower.contains(" failed:") || lower.startsWith("stopped:")) {
            return Signal.BLOCKED;
        }
        return Signal.NONE;
    }

    private static boolean contains(String value, String[] markers) {
        for (String marker : markers) {
            if (value.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
