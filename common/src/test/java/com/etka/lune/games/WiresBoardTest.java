package com.etka.lune.games;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;

import static com.etka.lune.games.WiresBoard.EAST;
import static com.etka.lune.games.WiresBoard.NORTH;
import static com.etka.lune.games.WiresBoard.SOUTH;
import static com.etka.lune.games.WiresBoard.WEST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WiresBoardTest {

    private static final long[] SEEDS = {1L, 7L, 42L, 20260923L, -5L, Long.MAX_VALUE};

    /** A row of three: the source, dust running the wrong way, and a lamp facing away from it. */
    private static WiresBoard row() {
        return WiresBoard.fixed(3, 1, 0, new int[]{EAST, NORTH | SOUTH, NORTH});
    }

    /** Turns every tile clockwise until it matches the answer the board was cut from. */
    private static void solve(WiresBoard board) {
        for (int tile = 0; tile < board.size(); tile++) {
            for (int quarter = 0; quarter < 4 && board.links(tile) != board.solution(tile); quarter++) {
                board.turn(tile, true);
            }
        }
    }

    /** Whether side {@code bit} of {@code tile} is answered by the tile across it, in the answer. */
    private static boolean answered(WiresBoard board, int tile, int bit) {
        int x = tile % board.columns();
        int y = tile / board.columns();
        int nx = x + (bit == EAST ? 1 : bit == WEST ? -1 : 0);
        int ny = y + (bit == SOUTH ? 1 : bit == NORTH ? -1 : 0);
        if (nx < 0 || ny < 0 || nx >= board.columns() || ny >= board.rows()) {
            return false;
        }
        return (board.solution(ny * board.columns() + nx) & WiresBoard.turned(bit, 2)) != 0;
    }

    /** Every tile linked, every link answered, and one link fewer than tiles: a spanning tree. */
    private static void assertAnswerIsOneTree(WiresBoard board, String which) {
        int halves = 0;
        for (int tile = 0; tile < board.size(); tile++) {
            int links = board.solution(tile);
            assertNotEquals(0, links, which + ": tile " + tile + " is cut off");
            halves += Integer.bitCount(links);
            for (int bit : new int[]{NORTH, EAST, SOUTH, WEST}) {
                if ((links & bit) != 0) {
                    assertTrue(answered(board, tile, bit), which + ": tile " + tile + " points at nothing");
                }
            }
        }
        assertEquals(2 * (board.size() - 1), halves, which);
    }

    // --- every board has an answer ---------------------------------------------

    @Test
    void theAnswerIsOneTreeAcrossTheWholeBoard() {
        for (WiresSize size : WiresSize.values()) {
            for (long seed : SEEDS) {
                assertAnswerIsOneTree(WiresBoard.generate(size.columns(), size.rows(), seed), size + " seed " + seed);
            }
        }
    }

    /**
     * Two thousand random boards of every size, each one dealt unsolved and each one solved by
     * turning its tiles back: random and always solvable are the same promise.
     */
    @Test
    void everyBoardDealtHasAnAnswer() {
        SplittableRandom seeds = new SplittableRandom(20260923L);
        for (WiresSize size : WiresSize.values()) {
            for (int i = 0; i < 2_000; i++) {
                long seed = seeds.nextLong();
                WiresBoard board = WiresBoard.generate(size.columns(), size.rows(), seed);
                String which = size + " seed " + seed;
                assertFalse(board.solved(), which + " was dealt solved");
                assertAnswerIsOneTree(board, which);
                solve(board);
                assertTrue(board.solved(), which + " did not solve");
                assertEquals(board.lampCount(), board.litLamps(), which);
            }
        }
    }

    @Test
    void aNewBoardIsNeverDealtAlreadySolved() {
        for (long seed = 0; seed < 300; seed++) {
            assertFalse(WiresBoard.generate(5, 5, seed).solved(), "seed " + seed);
        }
    }

    @Test
    void turningEveryTileBackToTheAnswerSolvesTheBoard() {
        for (WiresSize size : WiresSize.values()) {
            for (long seed : SEEDS) {
                WiresBoard board = WiresBoard.generate(size.columns(), size.rows(), seed);
                solve(board);
                assertTrue(board.solved(), size + " seed " + seed);
                assertEquals(board.lampCount(), board.litLamps());
            }
        }
    }

    // --- every board is different ----------------------------------------------

    @Test
    void theSourceCanStandAnywhere() {
        Set<Integer> sources = new HashSet<>();
        for (long seed = 0; seed < 500; seed++) {
            int source = WiresBoard.generate(5, 5, seed).source();
            assertTrue(source >= 0 && source < 25);
            sources.add(source);
        }
        assertEquals(25, sources.size(), "every tile of a 5×5 should have held the source");
    }

    /** Some boards bushy with lamps, some winding with few, and none with fewer than its size allows. */
    @Test
    void lampCountsVaryButNeverFallShort() {
        for (WiresSize size : WiresSize.values()) {
            int tiles = size.columns() * size.rows();
            int fewest = Integer.MAX_VALUE;
            int most = 0;
            for (long seed = 0; seed < 400; seed++) {
                int lamps = WiresBoard.generate(size.columns(), size.rows(), seed).lampCount();
                assertTrue(lamps >= WiresBoard.minimumLamps(tiles), size + " seed " + seed + ": " + lamps + " lamps");
                fewest = Math.min(fewest, lamps);
                most = Math.max(most, lamps);
            }
            assertTrue(most - fewest >= tiles / 6, size + ": lamps only ranged " + fewest + "-" + most);
        }
    }

    @Test
    void theSameSeedDealsTheSameBoard() {
        WiresBoard first = WiresBoard.generate(9, 9, 99L);
        WiresBoard again = WiresBoard.generate(9, 9, 99L);
        WiresBoard other = WiresBoard.generate(9, 9, 100L);
        boolean differs = false;
        for (int tile = 0; tile < first.size(); tile++) {
            assertEquals(first.links(tile), again.links(tile));
            differs |= first.links(tile) != other.links(tile);
        }
        assertTrue(differs, "two seeds dealt the same board");
    }

    /** The board turned a quarter clockwise, worked out apart from the code under test. Square only. */
    private static WiresBoard quarterTurn(WiresBoard board) {
        int n = board.columns();
        int[] links = new int[board.size()];
        int source = -1;
        for (int tile = 0; tile < board.size(); tile++) {
            int x = tile % n;
            int y = tile / n;
            int to = x * n + (n - 1 - y);
            links[to] = WiresBoard.turned(board.solution(tile), 1);
            if (tile == board.source()) {
                source = to;
            }
        }
        return WiresBoard.fixed(n, n, source, links);
    }

    /** The board in a mirror standing north to south: east and west swap, and so do the columns. */
    private static WiresBoard mirror(WiresBoard board) {
        int columns = board.columns();
        int[] links = new int[board.size()];
        int source = -1;
        for (int tile = 0; tile < board.size(); tile++) {
            int to = (tile / columns) * columns + (columns - 1 - tile % columns);
            int bits = board.solution(tile);
            links[to] = (bits & (NORTH | SOUTH)) | ((bits & EAST) != 0 ? WEST : 0) | ((bits & WEST) != 0 ? EAST : 0);
            if (tile == board.source()) {
                source = to;
            }
        }
        return WiresBoard.fixed(columns, board.rows(), source, links);
    }

    /** The board upside down, which suits a board of any shape. */
    private static WiresBoard halfTurn(WiresBoard board) {
        int[] links = new int[board.size()];
        for (int tile = 0; tile < board.size(); tile++) {
            links[board.size() - 1 - tile] = WiresBoard.turned(board.solution(tile), 2);
        }
        return WiresBoard.fixed(board.columns(), board.rows(), board.size() - 1 - board.source(), links);
    }

    @Test
    void aSquareBoardTurnedOrMirroredIsTheSamePuzzle() {
        for (long seed : SEEDS) {
            WiresBoard board = WiresBoard.generate(7, 7, seed);
            String canonical = Arrays.toString(board.canonical());
            WiresBoard turned = board;
            for (int quarter = 0; quarter < 4; quarter++) {
                WiresBoard mirrored = mirror(turned);
                assertEquals(canonical, Arrays.toString(turned.canonical()), "seed " + seed + ", " + quarter + " turns");
                assertEquals(canonical, Arrays.toString(mirrored.canonical()), "seed " + seed + ", mirrored");
                assertEquals(board.fingerprint(), turned.fingerprint());
                assertEquals(board.fingerprint(), mirrored.fingerprint());
                turned = quarterTurn(turned);
            }
        }
    }

    @Test
    void anOblongBoardTurnedOrMirroredIsTheSamePuzzle() {
        for (long seed : SEEDS) {
            WiresBoard board = WiresBoard.generate(13, 11, seed);
            assertEquals(board.fingerprint(), halfTurn(board).fingerprint(), "seed " + seed);
            assertEquals(board.fingerprint(), mirror(board).fingerprint(), "seed " + seed);
            assertEquals(board.fingerprint(), mirror(halfTurn(board)).fingerprint(), "seed " + seed);
        }
    }

    /** The same circuit with the source moved is a different puzzle: the power comes from elsewhere. */
    @Test
    void moveTheSourceAndItIsAnotherBoard() {
        int[] links = {EAST, EAST | WEST, WEST};
        assertNotEquals(WiresBoard.fixed(3, 1, 0, links).fingerprint(), WiresBoard.fixed(3, 1, 1, links).fingerprint());
        // Either end is the same board seen in a mirror.
        assertEquals(WiresBoard.fixed(3, 1, 0, links).fingerprint(), WiresBoard.fixed(3, 1, 2, links).fingerprint());
    }

    @Test
    void differentCircuitsAreToldApart() {
        Set<String> circuits = new HashSet<>();
        Set<Integer> fingerprints = new HashSet<>();
        for (long seed = 0; seed < 2_000; seed++) {
            WiresBoard board = WiresBoard.generate(7, 7, seed);
            if (circuits.add(Arrays.toString(board.canonical()))) {
                assertTrue(fingerprints.add(board.fingerprint()), "seed " + seed + " shares a fingerprint");
            }
        }
    }

    // --- playing -----------------------------------------------------------------

    @Test
    void aQuarterTurnCarriesEachSideRoundClockwise() {
        assertEquals(EAST, WiresBoard.turned(NORTH, 1));
        assertEquals(NORTH, WiresBoard.turned(WEST, 1));
        assertEquals(EAST | SOUTH, WiresBoard.turned(NORTH | EAST, 1));
        assertEquals(WEST, WiresBoard.turned(NORTH, -1));
        for (int links = 0; links < 16; links++) {
            assertEquals(links, WiresBoard.turned(links, 4));
            assertEquals(links, WiresBoard.turned(WiresBoard.turned(links, 1), -1));
            assertEquals(links, WiresBoard.mirrored(WiresBoard.mirrored(links)));
        }
    }

    @Test
    void powerOnlyCrossesSidesThatPointAtEachOther() {
        WiresBoard board = row();
        assertEquals(1, board.poweredCount(), "the dust runs north and south, away from the source");
        assertTrue(board.turn(1, true));
        assertTrue(board.isPowered(1));
        assertFalse(board.isPowered(2), "the lamp still faces north");
        assertFalse(board.solved());
        assertTrue(board.turn(2, false));
        assertTrue(board.solved());
        assertEquals(1, board.litLamps());
        assertEquals(2, board.turns());
    }

    @Test
    void aLockedTileWillNotTurnUntilItIsFreed() {
        WiresBoard board = row();
        assertTrue(board.toggleLock(1));
        assertFalse(board.turn(1, true));
        assertEquals(0, board.turns(), "a refused turn is not counted");
        assertTrue(board.toggleLock(1));
        assertTrue(board.turn(1, true));
    }

    @Test
    void aSolvedBoardIsFrozen() {
        WiresBoard board = row();
        board.turn(1, true);
        board.turn(2, false);
        assertTrue(board.solved());
        assertFalse(board.turn(1, true));
        assertFalse(board.toggleLock(1));
        assertEquals(2, board.turns());
    }

    @Test
    void aCrossingLooksTheSameEveryWayRoundSoTurningItCostsNothing() {
        WiresBoard board = WiresBoard.fixed(3, 3, 4, new int[]{
                0, SOUTH, 0,
                EAST, NORTH | EAST | SOUTH | WEST, WEST,
                0, NORTH, 0});
        assertFalse(board.turn(4, true));
        assertEquals(0, board.turns());
    }

    @Test
    void lampsAreTheDeadEndsOtherThanTheSource() {
        WiresBoard picture = WiresBoard.fixed(3, 3, 4, new int[]{
                0, SOUTH, 0,
                EAST, NORTH | EAST | SOUTH | WEST, WEST,
                0, NORTH, 0});
        assertEquals(4, picture.lampCount());
        assertEquals(4, picture.litLamps());
        assertFalse(picture.isLamp(0), "bare stone is not a lamp");

        // A source that is itself a dead end is still the source.
        assertFalse(row().isLamp(0));
        assertTrue(row().isLamp(2));
        assertTrue(row().isStraight(1));
    }
}
