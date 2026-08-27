package com.etka.lune.bot.task;

import java.util.ArrayList;
import java.util.List;

/** Minecraft-free frame ordering math, separated so support invariants stay unit-testable. */
final class PortalOrderPolicy {

    static final String ALTERNATING = "alternating-sides";
    static final String NEAREST_COLUMN = "nearest-column-first";
    static final String FARTHEST_COLUMN = "farthest-column-first";
    static final List<String> ACTIONS = List.of(ALTERNATING, NEAREST_COLUMN, FARTHEST_COLUMN);

    record Offset(int x, int y) {}

    private PortalOrderPolicy() {}

    static List<Offset> offsets(boolean includeCorners, String action, double playerRelativeX) {
        List<Offset> balanced = balanced(includeCorners);
        if (!includeCorners || ALTERNATING.equals(action)) {
            return balanced;
        }

        boolean playerNearLeft = Math.abs(playerRelativeX) <= Math.abs(playerRelativeX - 3.0);
        boolean leftFirst = NEAREST_COLUMN.equals(action)
                ? playerNearLeft : !playerNearLeft;
        int firstX = leftFirst ? 0 : 3;
        int secondX = leftFirst ? 3 : 0;
        int horizontalStep = leftFirst ? 1 : -1;
        List<Offset> ordered = new ArrayList<>(14);
        for (int x = 0; x < 4; x++) ordered.add(new Offset(x, 0));
        for (int y = 1; y < 5; y++) ordered.add(new Offset(firstX, y));
        for (int x = firstX + horizontalStep; x != secondX + horizontalStep; x += horizontalStep) {
            ordered.add(new Offset(x, 4));
        }
        for (int y = 1; y < 4; y++) ordered.add(new Offset(secondX, y));
        return List.copyOf(ordered);
    }

    private static List<Offset> balanced(boolean includeCorners) {
        List<Offset> offsets = new ArrayList<>(includeCorners ? 14 : 10);
        for (int x = 0; x < 4; x++) {
            if (includeCorners || x == 1 || x == 2) offsets.add(new Offset(x, 0));
        }
        for (int y = 1; y < 4; y++) {
            offsets.add(new Offset(0, y));
            offsets.add(new Offset(3, y));
        }
        for (int x = 0; x < 4; x++) {
            if (includeCorners || x == 1 || x == 2) offsets.add(new Offset(x, 4));
        }
        return List.copyOf(offsets);
    }
}
