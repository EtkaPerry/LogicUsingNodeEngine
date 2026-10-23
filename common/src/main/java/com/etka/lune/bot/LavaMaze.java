package com.etka.lune.bot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * The floor plan of the lava-maze fixture: walkway with lava wherever it is not.
 *
 * <p>The maze is cells and the gaps between them. A cell is a square of walkway, and between two
 * cells is a one-block gap, which is walkway where the two are joined and a hole where they are
 * not. Where four cells meet there is never floor, so a corridor turns round a hole or a wall and
 * never across floor that would let a body cut the corner. The outside ring is holes too: the maze
 * has no edge to stand on. Carved as a tree first, which is what makes every cell reachable, then
 * braided: half the dead ends are opened into a neighbour, because a chase on a tree ends in the
 * first dead end the bot turns into.</p>
 *
 * <h2>Two styles</h2>
 *
 * <p>{@link Style#FLAT} is that and nothing more: corridors one to three blocks wide on one level,
 * over lava a block down. {@link Style#BASTION} is the bad side of a bastion, which is where Self
 * Preservation walked into the gaps in the floor and jumped into the lava under the bridges
 * (2026-10-02). The user asked for it (2026-10-17) after the plain jumping course read as too easy:</p>
 *
 * <ul>
 *   <li>Rooms three blocks square at three heights, a step apart wherever they meet. The lava
 *   stands level with the lowest floor, so a room down there is a floor set in lava, and stepping
 *   off it is stepping into lava; one up there drops a block or two into it.</li>
 *   <li>Each room keeps a plus of floor through its middle, and its corners are lava pockets about
 *   half the time.</li>
 *   <li>A join is a three-wide walk, a one-wide bridge, a one-block gap or a two-block leap. The
 *   jumps are only ever between raised rooms of one height: an ordinary route never plans a jump
 *   over lava standing at the take-off's level, which every gap at the lowest level is. A run from
 *   a mob does, and finds more ways off the lowest floor than the plan's jumps.</li>
 *   <li>A closed join or a corner between rooms is a hole or, some of the time, a wall two blocks
 *   over the higher floor beside it, capped with a wall block nobody can stand on: something to be
 *   cornered against and to lose sight of a chaser behind.</li>
 * </ul>
 *
 * <p>The gaps split the walkway into islands only a jump gets between, which a mob never makes.
 * Every island keeps at least {@link #MIN_ISLAND} rooms, so there is room on it for a chaser.</p>
 *
 * <p>No Minecraft in here, so {@code LavaMazeTest} can walk it without a game.
 * {@link LavaMazeFixture} lays it in the world.</p>
 */
public final class LavaMaze {

    /** One level of corridors, or the bad side of a bastion. */
    public enum Style { FLAT, BASTION }

    /** Corridors wider than this stop being a maze and start being a floor with holes in it. */
    public static final int MAX_WIDTH = 3;
    /** How far above the lowest floor the highest stands. */
    public static final int MAX_HEIGHT = 2;
    /** How far a wall stands above the higher floor beside it, under its cap. */
    public static final int WALL_RISE = 2;
    /**
     * Roughly how many blocks across, whatever the corridor width: room for the mob keeper's ring of
     * nine to sixteen blocks round a bot in the middle, and small enough that a chaser finds it.
     */
    static final int TARGET_SIZE = 29;
    /** The share of dead ends opened into a loop. */
    static final double BRAID = 0.5;
    /** Fewest rooms an island may have: one a chaser can be put on, a little way from the bot. */
    static final int MIN_ISLAND = 4;
    /**
     * Fewest islands a bastion is laid with, so it always has to be jumped. A course whose rooms
     * mostly stand at the lava's own level has nowhere to jump, and is dealt again.
     */
    static final int MIN_ISLANDS = 3;
    /** Courses tried for one seed before the one with the most islands is taken anyway. */
    private static final int DEALS = 64;
    /** Blocks across a bastion room. */
    static final int ROOM = 3;
    /**
     * Rooms along a bastion's side unless the fixture asks for more: seven, which is twenty-nine
     * blocks across, the course every run on 2026-10-17 was measured on.
     */
    public static final int BASTION_ROOMS = 7;
    /** The fewest and most rooms along a bastion's side: from a room for a chaser to a long chase. */
    public static final int MIN_ROOMS = 5;
    public static final int MAX_ROOMS = 12;
    /** The share of a bastion room's corners that are lava pockets. */
    static final double POCKETS = 0.45;
    /** The share of closed joins and of corners between rooms that are walls rather than holes. */
    static final double WALLS = 0.3;

    private static final byte HOLE = 0;
    private static final byte FLOOR = 1;
    private static final byte WALL = 2;

    private static final byte CLOSED = 0;
    private static final byte WALK = 1;
    private static final byte BRIDGE = 2;
    private static final byte GAP = 3;
    private static final byte LEAP = 4;

    private static final int[][] SIDES = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};

    private final Style style;
    private final int cells;
    private final int width;
    private final int size;
    private final byte[] kinds;
    private final int[] heights;
    private final int[] islands;
    private final int islandCount;
    private final int gaps;
    private final int leaps;

    private LavaMaze(Style style, int cells, int width, byte[] kinds, int[] heights, int gaps, int leaps) {
        this.style = style;
        this.cells = cells;
        this.width = width;
        this.size = cells * (width + 1) + 1;
        this.kinds = kinds;
        this.heights = heights;
        this.gaps = gaps;
        this.leaps = leaps;
        this.islands = new int[size * size];
        this.islandCount = markIslands();
    }

    /** A flat maze with corridors {@code width} blocks wide, the same one every time for one seed. */
    public static LavaMaze generate(int width, long seed) {
        return generate(Style.FLAT, width, seed);
    }

    /**
     * A maze of either style, the same one every time for one seed; a bastion's rooms are three. A
     * bastion is dealt until it has {@link #MIN_ISLANDS} islands, a gap and a leap, which on most
     * seeds is the first one.
     */
    public static LavaMaze generate(Style style, int width, long seed) {
        if (style == Style.BASTION) {
            return bastion(BASTION_ROOMS, seed);
        }
        int corridor = Math.clamp(width, 1, MAX_WIDTH);
        return generate(cellsFor(corridor), corridor, new Random(seed), BRAID);
    }

    /**
     * A bastion {@code rooms} rooms across - {@link #BASTION_ROOMS} unless a bigger or smaller one
     * is asked for - dealt until it has {@link #MIN_ISLANDS} islands, a gap and a leap, the same one
     * every time for one seed.
     */
    public static LavaMaze bastion(int rooms, long seed) {
        Random random = new Random(seed);
        int cells = Math.clamp(rooms, MIN_ROOMS, MAX_ROOMS);
        LavaMaze best = null;
        for (int deal = 0; deal < DEALS; deal++) {
            LavaMaze maze = generateBastion(cells, random, BRAID, MIN_ISLAND);
            if (maze.islandCount >= MIN_ISLANDS && maze.gaps > 0 && maze.leaps > 0) {
                return maze;
            }
            if (best == null || maze.islandCount > best.islandCount) {
                best = maze;
            }
        }
        return best;
    }

    private static int cellsFor(int corridor) {
        return Math.max(3, Math.round((TARGET_SIZE - 1) / (float) (corridor + 1)));
    }

    static LavaMaze generate(int cells, int width, Random random, double braid) {
        boolean[][] open = carve(cells, random, braid, null);
        int size = cells * (width + 1) + 1;
        byte[] kinds = new byte[size * size];
        for (int cz = 0; cz < cells; cz++) {
            for (int cx = 0; cx < cells; cx++) {
                int cell = cz * cells + cx;
                int x0 = 1 + cx * (width + 1);
                int z0 = 1 + cz * (width + 1);
                fill(kinds, size, x0, z0, width, width, FLOOR);
                if (open[0][cell]) {
                    fill(kinds, size, x0 + width, z0, 1, width, FLOOR);
                }
                if (open[1][cell]) {
                    fill(kinds, size, x0, z0 + width, width, 1, FLOOR);
                }
            }
        }
        return new LavaMaze(Style.FLAT, cells, width, kinds, new int[size * size], 0, 0);
    }

    static LavaMaze generateBastion(int cells, Random random, double braid, int minIsland) {
        int[] levels = new int[cells * cells];
        boolean[][] open = carve(cells, random, braid, levels);
        byte[] east = new byte[cells * cells];
        byte[] south = new byte[cells * cells];
        for (int cell = 0; cell < cells * cells; cell++) {
            if (open[0][cell]) {
                east[cell] = join(levels[cell], levels[cell + 1], random);
            }
            if (open[1][cell]) {
                south[cell] = join(levels[cell], levels[cell + cells], random);
            }
        }
        mendSmallIslands(cells, east, south, random, minIsland);
        int gaps = 0;
        int leaps = 0;
        for (int cell = 0; cell < cells * cells; cell++) {
            gaps += (east[cell] == GAP ? 1 : 0) + (south[cell] == GAP ? 1 : 0);
            leaps += (east[cell] == LEAP ? 1 : 0) + (south[cell] == LEAP ? 1 : 0);
        }

        int stride = ROOM + 1;
        int size = cells * stride + 1;
        byte[] kinds = new byte[size * size];
        int[] heights = new int[size * size];
        for (int cz = 0; cz < cells; cz++) {
            for (int cx = 0; cx < cells; cx++) {
                int level = levels[cz * cells + cx];
                int x0 = 1 + cx * stride;
                int z0 = 1 + cz * stride;
                for (int i = 0; i < ROOM; i++) {
                    for (int j = 0; j < ROOM; j++) {
                        boolean corner = i != ROOM / 2 && j != ROOM / 2;
                        if (!corner || random.nextDouble() >= POCKETS) {
                            put(kinds, heights, size, x0 + i, z0 + j, FLOOR, level);
                        }
                    }
                }
            }
        }
        // Joins after rooms, because a leap takes the edge off one of the two.
        for (int cz = 0; cz < cells; cz++) {
            for (int cx = 0; cx < cells; cx++) {
                int cell = cz * cells + cx;
                int x0 = 1 + cx * stride;
                int z0 = 1 + cz * stride;
                if (cx + 1 < cells) {
                    lay(kinds, heights, size, east[cell], levels[cell], levels[cell + 1],
                            x0 + ROOM, z0, true, random);
                }
                if (cz + 1 < cells) {
                    lay(kinds, heights, size, south[cell], levels[cell], levels[cell + cells],
                            x0, z0 + ROOM, false, random);
                }
            }
        }
        // Where four rooms meet: a hole, or a wall over the highest of them.
        for (int cz = 1; cz < cells; cz++) {
            for (int cx = 1; cx < cells; cx++) {
                if (random.nextDouble() < WALLS) {
                    int highest = Math.max(
                            Math.max(levels[(cz - 1) * cells + cx - 1], levels[(cz - 1) * cells + cx]),
                            Math.max(levels[cz * cells + cx - 1], levels[cz * cells + cx]));
                    put(kinds, heights, size, cx * stride, cz * stride, WALL, highest + WALL_RISE);
                }
            }
        }
        return new LavaMaze(Style.BASTION, cells, ROOM, kinds, heights, gaps, leaps);
    }

    /**
     * What an open join between rooms at {@code a} and {@code b} is. A step is walked, three wide or
     * on a bridge; a level join is jumped about half the time, but never down at the lava's own
     * level, where the gap is lava to the brim and only a run from a mob will jump it.
     */
    private static byte join(int a, int b, Random random) {
        double roll = random.nextDouble();
        if (a != b || a == 0) {
            return roll < 0.5 ? WALK : BRIDGE;
        }
        return roll < 0.25 ? WALK : roll < 0.45 ? BRIDGE : roll < 0.8 ? GAP : LEAP;
    }

    /**
     * Lays one join's three tiles, {@code x0, z0} the first of them. A leap is the gap and one
     * room's edge beside it, which room chosen at random; a closed join is a hole or a wall.
     */
    private static void lay(byte[] kinds, int[] heights, int size, byte join, int a, int b,
                            int x0, int z0, boolean eastward, Random random) {
        int dx = eastward ? 0 : 1;
        int dz = eastward ? 1 : 0;
        int low = Math.min(a, b);
        switch (join) {
            case WALK -> {
                for (int k = 0; k < ROOM; k++) {
                    put(kinds, heights, size, x0 + k * dx, z0 + k * dz, FLOOR, low);
                }
            }
            case BRIDGE -> put(kinds, heights, size, x0 + dx, z0 + dz, FLOOR, low);
            case GAP -> { }
            case LEAP -> {
                // The edge facing the gap, of the room before it or the one after.
                int back = random.nextBoolean() ? -1 : 1;
                for (int k = 0; k < ROOM; k++) {
                    int x = x0 + k * dx + (eastward ? back : 0);
                    int z = z0 + k * dz + (eastward ? 0 : back);
                    put(kinds, heights, size, x, z, HOLE, 0);
                }
            }
            default -> {
                if (random.nextDouble() < WALLS) {
                    for (int k = 0; k < ROOM; k++) {
                        put(kinds, heights, size, x0 + k * dx, z0 + k * dz, WALL, Math.max(a, b) + WALL_RISE);
                    }
                }
            }
        }
    }

    /**
     * The tree, then the braid; {@code [0]} is each cell's join to the east and {@code [1]} to the
     * south. With {@code levels}, each cell is given a height as it is carved - the one it was
     * carved from, a step up or a step down - and a braid never joins two cells more than a step
     * apart.
     */
    private static boolean[][] carve(int cells, Random random, double braid, int[] levels) {
        boolean[] east = new boolean[cells * cells];
        boolean[] south = new boolean[cells * cells];
        boolean[][] open = {east, south};
        boolean[] seen = new boolean[cells * cells];
        Deque<Integer> stack = new ArrayDeque<>();
        int first = random.nextInt(cells * cells);
        seen[first] = true;
        stack.push(first);
        if (levels != null) {
            levels[first] = MAX_HEIGHT / 2;
        }
        List<Integer> options = new ArrayList<>(4);
        while (!stack.isEmpty()) {
            int cell = stack.peek();
            int cx = cell % cells;
            int cz = cell / cells;
            options.clear();
            for (int side = 0; side < SIDES.length; side++) {
                int nx = cx + SIDES[side][0];
                int nz = cz + SIDES[side][1];
                if (inside(cells, nx, nz) && !seen[nz * cells + nx]) {
                    options.add(side);
                }
            }
            if (options.isEmpty()) {
                stack.pop();
                continue;
            }
            int side = options.get(random.nextInt(options.size()));
            setOpen(open, cells, cx, cz, side);
            int next = (cz + SIDES[side][1]) * cells + cx + SIDES[side][0];
            if (levels != null) {
                levels[next] = climb(levels[cell], random);
            }
            seen[next] = true;
            stack.push(next);
        }

        List<Integer> order = new ArrayList<>(cells * cells);
        for (int cell = 0; cell < cells * cells; cell++) {
            order.add(cell);
        }
        Collections.shuffle(order, random);
        for (int cell : order) {
            int cx = cell % cells;
            int cz = cell / cells;
            if (openSides(open, cells, cx, cz) != 1 || random.nextDouble() >= braid) {
                continue;
            }
            // Into another dead end where there is one, which takes two dead ends out with one
            // opening instead of making a loop with a fresh dead end hanging off it.
            List<Integer> closed = new ArrayList<>(3);
            List<Integer> deadEnds = new ArrayList<>(3);
            for (int side = 0; side < SIDES.length; side++) {
                int nx = cx + SIDES[side][0];
                int nz = cz + SIDES[side][1];
                if (!inside(cells, nx, nz) || isOpen(open, cells, cx, cz, side)) {
                    continue;
                }
                if (levels != null && Math.abs(levels[cell] - levels[nz * cells + nx]) > 1) {
                    continue;
                }
                closed.add(side);
                if (openSides(open, cells, nx, nz) == 1) {
                    deadEnds.add(side);
                }
            }
            List<Integer> pick = deadEnds.isEmpty() ? closed : deadEnds;
            if (!pick.isEmpty()) {
                setOpen(open, cells, cx, cz, pick.get(random.nextInt(pick.size())));
            }
        }
        return open;
    }

    /** Level half the time, a step up or down a quarter each, and never off the three heights. */
    private static int climb(int level, Random random) {
        double roll = random.nextDouble();
        int next = roll < 0.25 ? level + 1 : roll < 0.5 ? level - 1 : level;
        return Math.clamp(next, 0, MAX_HEIGHT);
    }

    /**
     * Walks over jumps until every island has {@code minIsland} rooms: the smallest island has one
     * of its jumps to another island made a walk, and the islands are counted afresh.
     */
    private static void mendSmallIslands(int cells, byte[] east, byte[] south, Random random, int minIsland) {
        while (true) {
            int[] island = roomIslands(cells, east, south);
            int[] count = new int[cells * cells];
            for (int id : island) {
                count[id]++;
            }
            int smallest = -1;
            for (int id = 0; id < count.length; id++) {
                if (count[id] > 0 && count[id] < minIsland && (smallest < 0 || count[id] < count[smallest])) {
                    smallest = id;
                }
            }
            if (smallest < 0) {
                return;
            }
            List<int[]> ways = new ArrayList<>();
            for (int cell = 0; cell < cells * cells; cell++) {
                if (jumped(east[cell]) && (island[cell] == smallest) != (island[cell + 1] == smallest)) {
                    ways.add(new int[] {cell, 0});
                }
                if (jumped(south[cell]) && (island[cell] == smallest) != (island[cell + cells] == smallest)) {
                    ways.add(new int[] {cell, 1});
                }
            }
            if (ways.isEmpty()) {
                return;
            }
            int[] way = ways.get(random.nextInt(ways.size()));
            (way[1] == 0 ? east : south)[way[0]] = WALK;
        }
    }

    private static boolean jumped(byte join) {
        return join == GAP || join == LEAP;
    }

    /** Each room's island over the joins that are walked, named by its lowest room. */
    private static int[] roomIslands(int cells, byte[] east, byte[] south) {
        int[] island = new int[cells * cells];
        Arrays.fill(island, -1);
        Deque<Integer> open = new ArrayDeque<>();
        for (int start = 0; start < cells * cells; start++) {
            if (island[start] >= 0) {
                continue;
            }
            island[start] = start;
            open.add(start);
            while (!open.isEmpty()) {
                int cell = open.poll();
                int cx = cell % cells;
                int cz = cell / cells;
                int[] next = {
                        cx + 1 < cells && walked(east[cell]) ? cell + 1 : -1,
                        cz + 1 < cells && walked(south[cell]) ? cell + cells : -1,
                        cx > 0 && walked(east[cell - 1]) ? cell - 1 : -1,
                        cz > 0 && walked(south[cell - cells]) ? cell - cells : -1};
                for (int n : next) {
                    if (n >= 0 && island[n] < 0) {
                        island[n] = start;
                        open.add(n);
                    }
                }
            }
        }
        return island;
    }

    private static boolean walked(byte join) {
        return join == WALK || join == BRIDGE;
    }

    /** Numbers the walkway's islands - floor joined four ways, a step at most - and counts them. */
    private int markIslands() {
        Arrays.fill(islands, -1);
        int next = 0;
        Deque<Integer> open = new ArrayDeque<>();
        for (int start = 0; start < size * size; start++) {
            if (kinds[start] != FLOOR || islands[start] >= 0) {
                continue;
            }
            islands[start] = next;
            open.add(start);
            while (!open.isEmpty()) {
                int tile = open.poll();
                int x = tile % size;
                int z = tile / size;
                for (int[] side : SIDES) {
                    int nx = x + side[0];
                    int nz = z + side[1];
                    if (walkway(nx, nz) && islands[nz * size + nx] < 0
                            && Math.abs(heights[nz * size + nx] - heights[tile]) <= 1) {
                        islands[nz * size + nx] = next;
                        open.add(nz * size + nx);
                    }
                }
            }
            next++;
        }
        return next;
    }

    /** One level of corridors, or the bastion. */
    public Style style() {
        return style;
    }

    /** Blocks across, holes included. */
    public int size() {
        return size;
    }

    /** Blocks across one corridor, or one room of a bastion. */
    public int width() {
        return width;
    }

    /** Cells along one side. */
    public int cells() {
        return cells;
    }

    /** Whether the tile at {@code (x, z)} is floor; anything outside the maze is not. */
    public boolean walkway(int x, int z) {
        return kind(x, z) == FLOOR;
    }

    /** Whether the tile at {@code (x, z)} is a wall. */
    public boolean wall(int x, int z) {
        return kind(x, z) == WALL;
    }

    /**
     * How many blocks above the lowest floor this tile stands: a floor's own height, or the height
     * of the cap on a wall. -1 for a hole.
     */
    public int height(int x, int z) {
        return kind(x, z) == HOLE ? -1 : heights[z * size + x];
    }

    /**
     * Which island the tile is on, or -1 for anything but floor. An island is floor a body can walk
     * and step all over; between two islands there is nothing but a jump.
     */
    public int island(int x, int z) {
        return walkway(x, z) ? islands[z * size + x] : -1;
    }

    /** How many islands there are: one for a flat maze. */
    public int islandCount() {
        return islandCount;
    }

    /** One-block gaps between rooms; none on a flat maze. */
    public int gaps() {
        return gaps;
    }

    /** Two-block leaps between rooms, which only a running jump clears; none on a flat maze. */
    public int leaps() {
        return leaps;
    }

    /** How many tiles of walkway there are. */
    public int walkwayCount() {
        int count = 0;
        for (byte kind : kinds) {
            if (kind == FLOOR) {
                count++;
            }
        }
        return count;
    }

    /** The walkway tile nearest the middle, as {@code {x, z}}: where the bot is put. */
    public int[] start() {
        double middle = (size - 1) / 2.0;
        int[] best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                if (!walkway(x, z)) {
                    continue;
                }
                double distance = (x - middle) * (x - middle) + (z - middle) * (z - middle);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = new int[] {x, z};
                }
            }
        }
        return best;
    }

    /**
     * Walkway tiles whose middle is between {@code min} and {@code max} blocks from a point, as
     * {@code {x, z}}: where a chaser may be put. The point is in the maze's own block coordinates,
     * so the middle of tile {@code (2, 3)} is {@code (2.5, 3.5)}.
     */
    public List<int[]> tilesBetween(double x, double z, double min, double max) {
        return tilesBetween(x, z, min, max, -1);
    }

    /** The same, on one island only; any island when {@code island} is negative. */
    public List<int[]> tilesBetween(double x, double z, double min, double max, int island) {
        List<int[]> found = new ArrayList<>();
        for (int tz = 0; tz < size; tz++) {
            for (int tx = 0; tx < size; tx++) {
                if (!walkway(tx, tz) || (island >= 0 && islands[tz * size + tx] != island)) {
                    continue;
                }
                double dx = tx + 0.5 - x;
                double dz = tz + 0.5 - z;
                double distance = dx * dx + dz * dz;
                if (distance >= min * min && distance <= max * max) {
                    found.add(new int[] {tx, tz});
                }
            }
        }
        return found;
    }

    /**
     * The plan as text, a row per line: {@code ~} lava, {@code S} the start, a flat maze's walkway
     * as {@code #}, and a bastion's floor as its height, {@code 0} to {@code 2}, with its walls as
     * {@code W}.
     */
    public String plan() {
        int[] start = start();
        StringBuilder text = new StringBuilder();
        for (int z = 0; z < size; z++) {
            if (z > 0) {
                text.append('\n');
            }
            for (int x = 0; x < size; x++) {
                char tile = switch (kind(x, z)) {
                    case FLOOR -> style == Style.FLAT ? '#' : (char) ('0' + heights[z * size + x]);
                    case WALL -> 'W';
                    default -> '~';
                };
                boolean isStart = start != null && start[0] == x && start[1] == z;
                text.append(isStart ? 'S' : tile);
            }
        }
        return text.toString();
    }

    private byte kind(int x, int z) {
        return x >= 0 && z >= 0 && x < size && z < size ? kinds[z * size + x] : HOLE;
    }

    private static boolean inside(int cells, int cx, int cz) {
        return cx >= 0 && cz >= 0 && cx < cells && cz < cells;
    }

    private static void setOpen(boolean[][] open, int cells, int cx, int cz, int side) {
        switch (side) {
            case 0 -> open[0][cz * cells + cx] = true;
            case 1 -> open[1][cz * cells + cx] = true;
            case 2 -> open[0][cz * cells + cx - 1] = true;
            default -> open[1][(cz - 1) * cells + cx] = true;
        }
    }

    private static boolean isOpen(boolean[][] open, int cells, int cx, int cz, int side) {
        return switch (side) {
            case 0 -> cx + 1 < cells && open[0][cz * cells + cx];
            case 1 -> cz + 1 < cells && open[1][cz * cells + cx];
            case 2 -> cx > 0 && open[0][cz * cells + cx - 1];
            default -> cz > 0 && open[1][(cz - 1) * cells + cx];
        };
    }

    private static int openSides(boolean[][] open, int cells, int cx, int cz) {
        int count = 0;
        for (int side = 0; side < SIDES.length; side++) {
            if (isOpen(open, cells, cx, cz, side)) {
                count++;
            }
        }
        return count;
    }

    private static void fill(byte[] kinds, int size, int x0, int z0, int dx, int dz, byte kind) {
        for (int z = z0; z < z0 + dz; z++) {
            for (int x = x0; x < x0 + dx; x++) {
                kinds[z * size + x] = kind;
            }
        }
    }

    private static void put(byte[] kinds, int[] heights, int size, int x, int z, byte kind, int height) {
        kinds[z * size + x] = kind;
        heights[z * size + x] = kind == HOLE ? 0 : height;
    }
}
