package com.etka.lune.games;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * The board of Redstone Wires: a grid of redstone dust, every tile turned a quarter at a time, and
 * the power that reaches them from a block of redstone somewhere on the board.
 *
 * <p>Every board has an answer, because every board starts as one: a finished circuit, every lamp
 * lit, whose tiles are then each turned a random number of quarters. Turning them back is always
 * allowed, so the circuit the board was cut from is always there to be found.</p>
 *
 * <p>A tile keeps its links as four bits, one per side. A link only carries power when the tile
 * across that side links back, so power spreads out from the source through matched pairs and
 * stops at the first side that points at nothing. The board is solved when power reaches every
 * tile. Nothing more has to be checked: the links were cut from one tree, which has one link fewer
 * than it has tiles, and a board that joins them all has used every link to do it - none is left
 * over to point off the board or into a tile that does not answer.</p>
 *
 * <p>Nothing here knows about Minecraft. {@code WiresPage} draws the board; the tests play it.</p>
 */
public final class WiresBoard {

    public static final int NORTH = 1;
    public static final int EAST = 2;
    public static final int SOUTH = 4;
    public static final int WEST = 8;

    private static final int[] SIDES = {NORTH, EAST, SOUTH, WEST};
    private static final int[] STEP_X = {0, 1, 0, -1};
    private static final int[] STEP_Y = {-1, 0, 1, 0};
    /**
     * How far a board may lean towards winding. Past this a board is one long snake: at 1 a 5×5 has
     * three lamps on average, and some have one.
     */
    private static final double MAX_WINDING = 0.8;
    /** The source's bit in {@link #canonical()}, above the four sides, so where it stands counts. */
    private static final int SOURCE_MARK = 16;
    /** Trees grown before a board is accepted short of lamps; only a tiny board could run out. */
    private static final int MAX_REGROWS = 1000;

    private final int columns;
    private final int rows;
    private final int source;
    /** How the tree was cut, before the tiles were scrambled. One answer; a board may have others. */
    private final int[] solution;
    private final int[] links;
    private final boolean[] locked;
    private final boolean[] powered;
    /** Reused by every repower, so a turn allocates nothing. */
    private final int[] queue;
    private int poweredCount;
    private int turns;

    private WiresBoard(int columns, int rows, int source, int[] solution, int[] links) {
        this.columns = columns;
        this.rows = rows;
        this.source = source;
        this.solution = solution.clone();
        this.links = links.clone();
        this.locked = new boolean[links.length];
        this.powered = new boolean[links.length];
        this.queue = new int[links.length];
        repower();
    }

    /**
     * A new board: a random tree grown out of a random tile, every tile then given a random number
     * of quarter turns. The same seed always gives the same board.
     *
     * <p>Where the power stands and how the circuit grows out of it are both part of the deal. Each
     * board picks its own lean between bushy - a branch sprouting from anywhere at every step, many
     * short runs and many lamps - and winding, where the newest tip keeps going and the runs are
     * long and the lamps few. Grown from the middle alone, a 5×5 came round again, turned or
     * mirrored, 2,308 times in 200,000 boards, the first after 1,926; dealt this way it was 48
     * times, the first after 14,135. {@code WiresGame.deal} throws back even those.</p>
     */
    public static WiresBoard generate(int columns, int rows, long seed) {
        // A board needs two tiles each way for a tree with branches, and so for any lamps at all.
        if (columns < 2 || rows < 2) {
            throw new IllegalArgumentException(columns + "x" + rows);
        }
        Random random = new Random(seed);
        int size = columns * rows;
        int source;
        int[] tree;
        int grown = 0;
        do {
            source = random.nextInt(size);
            tree = tree(columns, rows, source, random.nextDouble() * MAX_WINDING, random);
        } while (lamps(tree, source) < minimumLamps(size) && ++grown < MAX_REGROWS);
        int[] scrambled = new int[tree.length];
        WiresBoard board;
        do {
            // Every tree has at least two dead ends, and a dead end only fits one of its four
            // ways round, so a scramble that happens to leave the board solved is rare, and the
            // next one is as unlikely again.
            for (int i = 0; i < tree.length; i++) {
                scrambled[i] = turned(tree[i], random.nextInt(4));
            }
            board = new WiresBoard(columns, rows, source, tree, scrambled);
        } while (board.solved());
        return board;
    }

    /**
     * A board laid out by hand and left as it stands, for a picture or a test. Its links are also
     * its solution. A tile with no links is bare stone.
     */
    public static WiresBoard fixed(int columns, int rows, int source, int[] links) {
        if (links.length != columns * rows) {
            throw new IllegalArgumentException(links.length + " != " + columns + "x" + rows);
        }
        Objects.checkIndex(source, links.length);
        return new WiresBoard(columns, rows, source, links, links);
    }

    /**
     * The fewest lamps a board of {@code size} tiles may have: one in eight, and never under three.
     * Below that a board is little more than one run of dust from the source to a lamp or two.
     */
    static int minimumLamps(int size) {
        return size < 16 ? 1 : Math.max(3, size / 8);
    }

    /** The dead ends of {@code tree} other than the source: the lamps it would be dealt with. */
    private static int lamps(int[] tree, int source) {
        int count = 0;
        for (int tile = 0; tile < tree.length; tile++) {
            if (tile != source && Integer.bitCount(tree[tile]) == 1) {
                count++;
            }
        }
        return count;
    }

    /**
     * A random spanning tree, grown one link at a time from {@code start}: each step joins the
     * grown part to one tile beside it. With chance {@code winding} the step carries on from the
     * newest tip; otherwise it picks any pair at the edge of what has grown, which is what sprouts
     * the short branches that end in lamps. The pairs keep the order they were found in, so the
     * newest is always the last, and each tile offers its sides in a shuffled order so a winding
     * run turns every way rather than favouring one.
     */
    private static int[] tree(int columns, int rows, int start, double winding, Random random) {
        int size = columns * rows;
        int[] links = new int[size];
        boolean[] joined = new boolean[size];
        List<int[]> frontier = new ArrayList<>();
        joined[start] = true;
        reachOut(frontier, start, columns, rows, random);
        while (!frontier.isEmpty()) {
            int pick = random.nextDouble() < winding ? frontier.size() - 1 : random.nextInt(frontier.size());
            int[] pair = frontier.remove(pick);
            int from = pair[0];
            int side = pair[1];
            int to = neighbour(from, side, columns, rows);
            if (joined[to]) {
                continue;
            }
            joined[to] = true;
            links[from] |= SIDES[side];
            links[to] |= SIDES[(side + 2) % 4];
            reachOut(frontier, to, columns, rows, random);
        }
        return links;
    }

    private static void reachOut(List<int[]> frontier, int tile, int columns, int rows, Random random) {
        int[] order = {0, 1, 2, 3};
        for (int i = order.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int swap = order[i];
            order[i] = order[j];
            order[j] = swap;
        }
        for (int side : order) {
            if (neighbour(tile, side, columns, rows) >= 0) {
                frontier.add(new int[]{tile, side});
            }
        }
    }

    /** The tile across {@code side}, or -1 at the edge of the board. */
    private static int neighbour(int tile, int side, int columns, int rows) {
        int x = tile % columns + STEP_X[side];
        int y = tile / columns + STEP_Y[side];
        return x < 0 || y < 0 || x >= columns || y >= rows ? -1 : y * columns + x;
    }

    /** {@code links} after {@code quarters} clockwise quarter turns; a negative count turns back. */
    public static int turned(int links, int quarters) {
        int q = Math.floorMod(quarters, 4);
        return ((links << q) | (links >>> (4 - q))) & 0xF;
    }

    /** {@code links} seen in a mirror standing north to south: east and west change places. */
    static int mirrored(int links) {
        return (links & (NORTH | SOUTH)) | ((links & EAST) != 0 ? WEST : 0) | ((links & WEST) != 0 ? EAST : 0);
    }

    // --- telling boards apart --------------------------------------------------

    /**
     * The answer as it looks turned and mirrored every way the board allows - eight ways on a
     * square board, four on any other - with the source marked, and of those the one that sorts
     * first. A board turned round or seen in a mirror is the same puzzle, so two boards are the same
     * exactly when these match.
     */
    int[] canonical() {
        int[] first = null;
        int forms = columns == rows ? 8 : 4;
        for (int form = 0; form < forms; form++) {
            int[] candidate = transformed(form);
            if (first == null || Arrays.compare(candidate, first) < 0) {
                first = candidate;
            }
        }
        return first;
    }

    /**
     * A 32-bit fingerprint of {@link #canonical()}: what the record of dealt boards keeps. Two
     * different circuits sharing one is possible and harmless; it only means a board is dealt again.
     */
    public int fingerprint() {
        long hash = 0xCBF29CE484222325L;
        for (int value : canonical()) {
            hash ^= value;
            hash *= 0x100000001B3L;
        }
        return (int) (hash ^ (hash >>> 32));
    }

    /**
     * The answer under one of the board's symmetries. The first four suit any board: as it is,
     * a half turn, and the two mirrors. The last four only a square one: the quarter turns either
     * way, and the mirrors across its diagonals.
     */
    private int[] transformed(int form) {
        int[] out = new int[solution.length];
        for (int tile = 0; tile < solution.length; tile++) {
            int x = tile % columns;
            int y = tile / columns;
            int links = solution[tile];
            int toX;
            int toY;
            int bits;
            switch (form) {
                case 0 -> { toX = x; toY = y; bits = links; }
                case 1 -> { toX = columns - 1 - x; toY = rows - 1 - y; bits = turned(links, 2); }
                case 2 -> { toX = columns - 1 - x; toY = y; bits = mirrored(links); }
                case 3 -> { toX = x; toY = rows - 1 - y; bits = turned(mirrored(links), 2); }
                case 4 -> { toX = rows - 1 - y; toY = x; bits = turned(links, 1); }
                case 5 -> { toX = y; toY = columns - 1 - x; bits = turned(links, -1); }
                case 6 -> { toX = y; toY = x; bits = turned(mirrored(links), -1); }
                default -> { toX = rows - 1 - y; toY = columns - 1 - x; bits = turned(mirrored(links), 1); }
            }
            out[toY * columns + toX] = bits | (tile == source ? SOURCE_MARK : 0);
        }
        return out;
    }

    /** Spreads power from the source again, through every pair of sides that point at each other. */
    private void repower() {
        Arrays.fill(powered, false);
        powered[source] = true;
        queue[0] = source;
        int head = 0;
        int tail = 1;
        while (head < tail) {
            int at = queue[head++];
            for (int side = 0; side < 4; side++) {
                if ((links[at] & SIDES[side]) == 0) {
                    continue;
                }
                int next = neighbour(at, side, columns, rows);
                if (next < 0 || powered[next] || (links[next] & SIDES[(side + 2) % 4]) == 0) {
                    continue;
                }
                powered[next] = true;
                queue[tail++] = next;
            }
        }
        poweredCount = tail;
    }

    // --- playing ---------------------------------------------------------------

    /**
     * Turns a tile a quarter. False, and nothing counted, when the tile is locked, the board is
     * already solved, or the turn would change nothing - a crossing looks the same every way round.
     */
    public boolean turn(int tile, boolean clockwise) {
        if (!inside(tile) || locked[tile] || solved()) {
            return false;
        }
        int after = turned(links[tile], clockwise ? 1 : -1);
        if (after == links[tile]) {
            return false;
        }
        links[tile] = after;
        turns++;
        repower();
        return true;
    }

    /** Locks a tile against turning, or frees it again. False once the board is solved. */
    public boolean toggleLock(int tile) {
        if (!inside(tile) || solved()) {
            return false;
        }
        locked[tile] = !locked[tile];
        return true;
    }

    private boolean inside(int tile) {
        return tile >= 0 && tile < links.length;
    }

    // --- reading ---------------------------------------------------------------

    public int columns() {
        return columns;
    }

    public int rows() {
        return rows;
    }

    public int size() {
        return links.length;
    }

    public int source() {
        return source;
    }

    /** The tile's sides as bits: {@link #NORTH}, {@link #EAST}, {@link #SOUTH}, {@link #WEST}. */
    public int links(int tile) {
        return links[tile];
    }

    /** The tile's sides in the answer the board was cut from. */
    int solution(int tile) {
        return solution[tile];
    }

    public boolean isLocked(int tile) {
        return locked[tile];
    }

    public boolean isPowered(int tile) {
        return powered[tile];
    }

    /** A dead end: the lamps the power has to reach. The source is never one, even as a dead end. */
    public boolean isLamp(int tile) {
        return tile != source && Integer.bitCount(links[tile]) == 1;
    }

    /** Dust running straight through, which the game draws as a line with no dot in the middle. */
    public boolean isStraight(int tile) {
        return links[tile] == (NORTH | SOUTH) || links[tile] == (EAST | WEST);
    }

    public int lampCount() {
        int count = 0;
        for (int tile = 0; tile < links.length; tile++) {
            if (isLamp(tile)) {
                count++;
            }
        }
        return count;
    }

    public int litLamps() {
        int count = 0;
        for (int tile = 0; tile < links.length; tile++) {
            if (isLamp(tile) && powered[tile]) {
                count++;
            }
        }
        return count;
    }

    public int poweredCount() {
        return poweredCount;
    }

    public boolean solved() {
        return poweredCount == links.length;
    }

    public int turns() {
        return turns;
    }
}
