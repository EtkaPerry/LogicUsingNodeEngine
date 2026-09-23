package com.etka.lune.games;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static com.etka.lune.games.WiresBoard.EAST;
import static com.etka.lune.games.WiresBoard.NORTH;
import static com.etka.lune.games.WiresBoard.SOUTH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WiresGameTest {

    /** Two turns from solved: the dust in the middle, then the lamp at the end. */
    private static WiresGame game() {
        return new WiresGame(WiresSize.SMALL, WiresBoard.fixed(3, 1, 0, new int[]{EAST, NORTH | SOUTH, NORTH}));
    }

    // --- the clock ---------------------------------------------------------------

    @Test
    void theClockWaitsForTheFirstTurn() {
        WiresGame game = game();
        game.watched(1_000);
        game.watched(1_200);
        assertEquals(0, game.elapsedMillis());
        assertFalse(game.started());

        assertEquals(WiresGame.Outcome.TURNED, game.turn(1, true, 1_300));
        assertTrue(game.inProgress());
        game.watched(1_400);
        assertEquals(100, game.elapsedMillis());
    }

    @Test
    void lookingAwayCostsNothing() {
        WiresGame game = game();
        game.turn(1, true, 1_000);
        game.watched(1_100);
        game.unwatched();
        // Ten seconds away watching Lune work, and the first frame back starts the clock again.
        game.watched(11_100);
        assertEquals(100, game.elapsedMillis());
        game.watched(11_200);
        assertEquals(200, game.elapsedMillis());
    }

    @Test
    void aGapBetweenFramesCountsAsLookingAwayNotAsASlowMove() {
        WiresGame game = game();
        game.turn(1, true, 1_000);
        game.watched(6_000);
        assertEquals(WiresGame.MAX_FRAME_GAP, game.elapsedMillis());
    }

    @Test
    void theClockStopsAtTheTurnThatSolvesTheBoard() {
        WiresGame game = game();
        game.turn(1, true, 1_000);
        game.watched(1_200);
        assertEquals(WiresGame.Outcome.SOLVED, game.turn(2, false, 1_300));
        assertEquals(300, game.elapsedMillis(), "the last move counts in full");
        assertEquals(1_300, game.solvedAt());
        game.watched(9_000);
        assertEquals(300, game.elapsedMillis());
        assertFalse(game.inProgress());
    }

    @Test
    void aRefusedTurnIsNotAMove() {
        WiresGame game = game();
        game.toggleLock(1);
        assertEquals(WiresGame.Outcome.REFUSED, game.turn(1, true, 1_000));
        assertFalse(game.started());
    }

    @Test
    void theClockReadsMinutesAndSeconds() {
        assertEquals("0:00", WiresGame.clock(0));
        assertEquals("0:00", WiresGame.clock(-5));
        assertEquals("0:09", WiresGame.clock(9_999));
        assertEquals("1:05", WiresGame.clock(65_000));
        assertEquals("59:59", WiresGame.clock(3_599_000));
        assertEquals("1:02:07", WiresGame.clock(3_727_000));
    }

    // --- dealing -------------------------------------------------------------------

    /** Five thousand small boards in a row, the size a repeat was likeliest in, and never the same one twice. */
    @Test
    void noBoardIsDealtTwice() {
        SplittableRandom seeds = new SplittableRandom(20260923L);
        Set<Integer> dealt = new HashSet<>();
        Set<String> circuits = new HashSet<>();
        for (int i = 0; i < 5_000; i++) {
            WiresBoard board = WiresGame.deal(WiresSize.SMALL, seeds::nextLong, dealt::contains);
            assertTrue(dealt.add(board.fingerprint()), "board " + i + " was dealt before");
            assertTrue(circuits.add(Arrays.toString(board.canonical())), "board " + i + " repeats a circuit");
        }
    }

    @Test
    void aRepeatIsThrownBackAndAnotherDealt() {
        long[] seeds = {11L, 11L, 12L};
        AtomicInteger next = new AtomicInteger();
        Set<Integer> dealt = new HashSet<>();
        WiresBoard first = WiresGame.deal(WiresSize.SMALL, () -> seeds[next.getAndIncrement()], dealt::contains);
        dealt.add(first.fingerprint());
        WiresBoard second = WiresGame.deal(WiresSize.SMALL, () -> seeds[next.getAndIncrement()], dealt::contains);
        assertEquals(3, next.get(), "the repeat of seed 11 should have been dealt again");
        assertNotEquals(first.fingerprint(), second.fingerprint());
    }

    @Test
    void aSizeWithNothingLeftToDealStillDealsRatherThanLoopingForever() {
        AtomicInteger asked = new AtomicInteger();
        WiresBoard board = WiresGame.deal(WiresSize.SMALL, () -> asked.getAndIncrement(), fingerprint -> true);
        assertNotNull(board);
        assertEquals(WiresGame.MAX_REDEALS, asked.get());
    }

    @Test
    void aDealtBoardIsRememberedAndTheOldestLetGo() {
        String packed = null;
        for (int fingerprint : new int[]{7, -3, 42, 1 << 30}) {
            packed = DealtHistory.appended(packed, fingerprint, 3);
        }
        assertFalse(DealtHistory.contains(packed, 7), "the oldest is let go past the cap");
        assertTrue(DealtHistory.contains(packed, -3));
        assertTrue(DealtHistory.contains(packed, 42));
        assertTrue(DealtHistory.contains(packed, 1 << 30));
        assertFalse(packed.contains("="), "no padding for Gson to write as an escape");
    }

    @Test
    void aHistoryThatNoLongerReadsIsForgottenRatherThanFailingADeal() {
        assertFalse(DealtHistory.contains(null, 1));
        assertFalse(DealtHistory.contains("", 1));
        assertFalse(DealtHistory.contains("not base64!", 1));
        assertTrue(DealtHistory.contains(DealtHistory.appended("not base64!", 5, 10), 5));
    }

    // --- records -------------------------------------------------------------------

    @Test
    void theFirstClearSetsTheRecordAndOnlyAFasterOneBeatsIt() {
        Map<String, Long> bests = new LinkedHashMap<>();
        Map<String, Integer> cleared = new LinkedHashMap<>();
        assertFalse(WiresRecords.record(bests, cleared, "small", 5_000), "there was nothing to beat");
        assertEquals(5_000L, bests.get("small"));
        assertTrue(WiresRecords.record(bests, cleared, "small", 4_000));
        assertFalse(WiresRecords.record(bests, cleared, "small", 6_000));
        assertEquals(4_000L, bests.get("small"));
        assertEquals(3, cleared.get("small"), "a slow clear is still a clear");
        assertFalse(WiresRecords.record(bests, cleared, "huge", 90_000));
        assertEquals(2, bests.size(), "each size keeps its own record");
    }

    /** Stored ids are identifiers: a Turkish lower case would turn MEDIUM into "medıum". */
    @Test
    void sizeIdsDoNotDependOnTheLanguage() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("medium", WiresSize.MEDIUM.id());
            assertEquals("huge", WiresSize.HUGE.id());
        } finally {
            Locale.setDefault(before);
        }
        assertEquals("13×11", WiresSize.HUGE.label());
    }
}
