package com.etka.lune.client.gui.widget;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.function.IntUnaryOperator;

/**
 * Where the tour's light falls and where its card stands, as plain arithmetic.
 *
 * <p>The panel is dimmed everywhere but the parts a stop is about, and the card that talks about
 * them stands beside the first of them without covering any. Both are worked out here, free of
 * Minecraft, so the rules can be held to a test: the dim and the light together cover the screen
 * exactly once, and a card that can stand clear of the light always does.</p>
 */
public final class Spotlight {

    private Spotlight() {}

    /** A rectangle in the panel's own pixels. */
    public record Box(int x, int y, int width, int height) {

        public int right() {
            return x + width;
        }

        public int bottom() {
            return y + height;
        }

        public int centreX() {
            return x + width / 2;
        }

        public int centreY() {
            return y + height / 2;
        }

        public boolean empty() {
            return width <= 0 || height <= 0;
        }

        public boolean contains(double pointX, double pointY) {
            return pointX >= x && pointX < right() && pointY >= y && pointY < bottom();
        }

        /** The same box with {@code by} more on every side. */
        public Box grow(int by) {
            return new Box(x - by, y - by, width + by * 2, height + by * 2);
        }

        /** The part of this box inside {@code bounds}; empty when they do not meet. */
        public Box clip(Box bounds) {
            int left = Math.max(x, bounds.x());
            int top = Math.max(y, bounds.y());
            int right = Math.min(right(), bounds.right());
            int bottom = Math.min(bottom(), bounds.bottom());
            return new Box(left, top, Math.max(0, right - left), Math.max(0, bottom - top));
        }

        /** How many pixels this box shares with {@code other}. */
        public int overlap(Box other) {
            Box shared = clip(other);
            return shared.empty() ? 0 : shared.width() * shared.height();
        }

        /** The smallest box round all of {@code boxes} that are not empty, or null for none. */
        public static Box around(List<Box> boxes) {
            Box around = null;
            for (Box box : boxes) {
                if (box == null || box.empty()) {
                    continue;
                }
                if (around == null) {
                    around = box;
                    continue;
                }
                int left = Math.min(around.x(), box.x());
                int top = Math.min(around.y(), box.y());
                int right = Math.max(around.right(), box.right());
                int bottom = Math.max(around.bottom(), box.bottom());
                around = new Box(left, top, right - left, bottom - top);
            }
            return around;
        }
    }

    /** Which side of the first lit area the card stands on; NONE when no side was clear. */
    public enum Side { BELOW, RIGHT, LEFT, ABOVE, NONE }

    public record Placement(Box card, Side side) {}

    /**
     * The dim: boxes covering {@code screen} everywhere the holes do not, none of them overlapping.
     *
     * <p>The screen is cut into bands at every hole's top and bottom; within a band the holes that
     * span it are walked left to right and the gaps between them are the dim. Holes may overlap
     * one another, and may run off the screen.</p>
     */
    public static List<Box> shade(Box screen, List<Box> holes) {
        List<Box> lit = new ArrayList<>();
        for (Box hole : holes) {
            Box clipped = hole.clip(screen);
            if (!clipped.empty()) {
                lit.add(clipped);
            }
        }
        if (lit.isEmpty()) {
            return screen.empty() ? List.of() : List.of(screen);
        }
        TreeSet<Integer> edges = new TreeSet<>();
        edges.add(screen.y());
        edges.add(screen.bottom());
        for (Box hole : lit) {
            edges.add(hole.y());
            edges.add(hole.bottom());
        }
        List<Box> dim = new ArrayList<>();
        Integer top = null;
        for (int bottom : edges) {
            if (top != null && bottom > top) {
                band(screen, lit, top, bottom, dim);
            }
            top = bottom;
        }
        return dim;
    }

    private static void band(Box screen, List<Box> lit, int top, int bottom, List<Box> dim) {
        List<Box> spanning = new ArrayList<>();
        for (Box hole : lit) {
            if (hole.y() <= top && hole.bottom() >= bottom) {
                spanning.add(hole);
            }
        }
        spanning.sort(Comparator.comparingInt(Box::x));
        int cursor = screen.x();
        for (Box hole : spanning) {
            if (hole.x() > cursor) {
                dim.add(new Box(cursor, top, hole.x() - cursor, bottom - top));
            }
            cursor = Math.max(cursor, hole.right());
        }
        if (cursor < screen.right()) {
            dim.add(new Box(cursor, top, screen.right() - cursor, bottom - top));
        }
    }

    /**
     * Where the card stands: beside the first hole, {@code gap} away from it, inside the screen by
     * {@code margin}, and clear of every hole.
     *
     * <p>Below comes first, then right, left and above, at the card's own width and then at
     * {@code narrowest}, which makes it taller ({@code heightFor} says how tall for a width). When
     * no side is clear - the canvas can fill most of a small screen - the card goes where it
     * covers the least of what is lit, and says so with {@link Side#NONE}, so nothing points from
     * it at a place it is sitting on. With nothing lit at all it stands in the middle.</p>
     */
    public static Placement place(Box screen, List<Box> holes, int gap, int margin, int width,
                                  int narrowest, IntUnaryOperator heightFor) {
        Box room = new Box(screen.x() + margin, screen.y() + margin,
                Math.max(1, screen.width() - margin * 2), Math.max(1, screen.height() - margin * 2));
        int wide = Math.min(width, room.width());
        if (holes.isEmpty()) {
            int height = heightFor.applyAsInt(wide);
            return new Placement(new Box(room.centreX() - wide / 2, room.centreY() - height / 2,
                    wide, height), Side.NONE);
        }
        Box anchor = holes.get(0);
        List<Placement> tried = new ArrayList<>();
        int[] widths = narrowest < wide ? new int[] {wide, narrowest} : new int[] {wide};
        for (int cardWidth : widths) {
            int height = heightFor.applyAsInt(cardWidth);
            for (Side side : List.of(Side.BELOW, Side.RIGHT, Side.LEFT, Side.ABOVE)) {
                Box card = beside(anchor, side, cardWidth, height, gap, room);
                if (inside(card, room) && clear(card, holes)) {
                    return new Placement(card, side);
                }
                tried.add(new Placement(into(card, room), side));
            }
        }
        Placement best = null;
        int bestCover = Integer.MAX_VALUE;
        for (Placement placement : tried) {
            int cover = covered(placement.card(), holes);
            if (cover < bestCover) {
                best = placement;
                bestCover = cover;
            }
        }
        Side side = best.card().overlap(anchor) > 0 ? Side.NONE : best.side();
        return new Placement(best.card(), side);
    }

    /** The card against one side of the anchor, slid along that side to stay in the room. */
    private static Box beside(Box anchor, Side side, int width, int height, int gap, Box room) {
        int alongX = Math.clamp(anchor.centreX() - width / 2, room.x(),
                Math.max(room.x(), room.right() - width));
        int alongY = Math.clamp(anchor.centreY() - height / 2, room.y(),
                Math.max(room.y(), room.bottom() - height));
        return switch (side) {
            case BELOW -> new Box(alongX, anchor.bottom() + gap, width, height);
            case ABOVE -> new Box(alongX, anchor.y() - gap - height, width, height);
            case RIGHT -> new Box(anchor.right() + gap, alongY, width, height);
            case LEFT, NONE -> new Box(anchor.x() - gap - width, alongY, width, height);
        };
    }

    private static boolean inside(Box card, Box room) {
        return card.x() >= room.x() && card.y() >= room.y()
                && card.right() <= room.right() && card.bottom() <= room.bottom();
    }

    private static boolean clear(Box card, List<Box> holes) {
        return covered(card, holes) == 0;
    }

    private static int covered(Box card, List<Box> holes) {
        int covered = 0;
        for (Box hole : holes) {
            covered += card.overlap(hole);
        }
        return covered;
    }

    /** The card pushed back inside the room, keeping its size where the room allows. */
    private static Box into(Box card, Box room) {
        int x = Math.clamp(card.x(), room.x(), Math.max(room.x(), room.right() - card.width()));
        int y = Math.clamp(card.y(), room.y(), Math.max(room.y(), room.bottom() - card.height()));
        return new Box(x, y, card.width(), card.height());
    }
}
