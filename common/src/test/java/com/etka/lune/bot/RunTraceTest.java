package com.etka.lune.bot;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunTraceTest {

    private static final String CHOPPING = "step 5/7 Chop Wood - coming next to the tree - 3 blocks left";
    private static final RunTrace.Body STANDING = new RunTrace.Body(0, 0, false, false, false, 20, 20);

    /** RunTrace keeps a list of landmark blocks, so loading it needs the block registry. */
    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void reportsEveryActionThatCanBlockAUserVisibleRun() {
        String blocked = BlockedReason.describe("placement space occupied", "no solid support face",
                "visible obstruction before requested block", "outside view (turn toward it)",
                11, 42, "possible loop: no inventory/phase change for 120 ticks");

        assertTrue(blocked.contains("obstruction=placement space occupied"));
        assertTrue(blocked.contains("placement=no solid support face"));
        assertTrue(blocked.contains("break=visible obstruction before requested block"));
        assertTrue(blocked.contains("target=outside view"));
        assertTrue(blocked.contains("waypoint_stall_ticks=11"));
        assertTrue(blocked.contains("goal_stall_ticks=42"));
        assertTrue(blocked.contains("possible loop"));
    }

    @Test
    void reportsNoBlockerForAnOrdinaryAction() {
        assertEquals("none", BlockedReason.describe("", "", "breaking",
                "visible and actionable", 0, 0, ""));
    }

    @Test
    void aPausedTickIsBilledToThePauseAndNotToTheStatusOnScreen() {
        StringWriter out = new StringWriter();
        RunTrace trace = new RunTrace(Path.of("run.txt"), new BufferedWriter(out));
        DebugInfo debug = new DebugInfo();
        debug.taskStatus = CHOPPING;
        for (int tick = 1; tick <= 100; tick++) {
            trace.charge(tick, debug, STANDING, tick > 40 && tick <= 90);
        }
        trace.close("test");
        String journal = out.toString();

        assertEquals(100, ticks(journal, "SUMMARY ticks="), "the run is still as long as it was");
        assertEquals(50, ticks(journal, "SUMMARY paused"));
        assertEquals(50, ticks(journal, "SUMMARY status"),
                "only the ticks the bot was driving belong to the status it showed");
        assertTrue(line(journal, "SUMMARY status").endsWith(
                "step N/N Chop Wood - coming next to the tree - N blocks left"));
        assertTrue(line(journal, "SUMMARY motion").contains("idle=50 ticks"),
                "standing still while paused is not the bot idling");
    }

    @Test
    void aRunThatWasNeverPausedSaysSo() {
        StringWriter out = new StringWriter();
        RunTrace trace = new RunTrace(Path.of("run.txt"), new BufferedWriter(out));
        DebugInfo debug = new DebugInfo();
        debug.taskStatus = CHOPPING;
        for (int tick = 1; tick <= 10; tick++) {
            trace.charge(tick, debug, STANDING, false);
        }
        trace.close("test");
        String journal = out.toString();

        assertEquals(0, ticks(journal, "SUMMARY paused"),
                "a zero is written, so its absence can only mean an older journal");
        assertEquals(10, ticks(journal, "SUMMARY status"));
        assertTrue(line(journal, "SUMMARY motion").contains("idle=10 ticks"));
    }

    @Test
    void aWalkTakenWhilePausedIsThePlayersNotTheBots() {
        StringWriter out = new StringWriter();
        RunTrace trace = new RunTrace(Path.of("run.txt"), new BufferedWriter(out));
        DebugInfo debug = new DebugInfo();
        debug.taskStatus = CHOPPING;
        for (int tick = 1; tick <= 10; tick++) {
            trace.charge(tick, debug, walkingTo(0.2 * tick, false), false);
        }
        // The player takes the controls, walks five blocks and jumps on the way.
        for (int tick = 11; tick <= 30; tick++) {
            trace.charge(tick, debug, walkingTo(2.0 + 0.25 * (tick - 10), tick == 20), true);
        }
        // They hand the controls back where they stopped, and the bot stands there for ten ticks.
        for (int tick = 31; tick <= 40; tick++) {
            trace.charge(tick, debug, walkingTo(7.0, false), false);
        }
        trace.close("test");
        String motion = line(out.toString(), "SUMMARY motion");

        assertTrue(motion.contains("walked=1.80;"), motion);
        assertTrue(motion.contains("moving=9t;"), motion);
        assertTrue(motion.contains("airborne=0;"), "the player's jump is not the bot's: " + motion);
        assertTrue(motion.contains("idle=11 ticks"), "40 ticks, 20 paused, 9 moving: " + motion);
    }

    @Test
    void aPauseNeitherRaisesTheStallAlarmNorBringsTheNextOneCloser() {
        RunTrace trace = new RunTrace(Path.of("run.txt"), new BufferedWriter(new StringWriter()));
        DebugInfo debug = new DebugInfo();
        debug.taskStatus = CHOPPING;
        int pauseFrom = RunTrace.STALL_TICKS - 100;
        int resumeAt = pauseFrom + 5 * RunTrace.STALL_TICKS;
        List<String> alarms = new ArrayList<>();
        for (int tick = 1; tick <= resumeAt + 2 * RunTrace.STALL_TICKS; tick++) {
            boolean paused = tick > pauseFrom && tick < resumeAt;
            int stalled = trace.charge(tick, debug, STANDING, paused);
            if (stalled > 0) {
                alarms.add(tick + ":" + stalled);
            }
        }

        // The status first showed on tick 1, so the alarm is due after 2400 ticks of driving:
        // 2299 before the pause, and the other 101 once it is over - and 2400 more for the next.
        assertEquals(List.of(
                (resumeAt + 100) + ":" + RunTrace.STALL_TICKS,
                (resumeAt + 100 + RunTrace.STALL_TICKS) + ":" + 2 * RunTrace.STALL_TICKS), alarms);
    }

    /**
     * Beside the player, the ticks the keys were the player's are billed like a pause, on a line
     * of their own: the walking was theirs, and the status on screen was only Lune watching.
     */
    @Test
    void besideThePlayerTheirTicksAreTheirsAndHaveALineOfTheirOwn() {
        StringWriter out = new StringWriter();
        RunTrace trace = new RunTrace(Path.of("run.txt"), new BufferedWriter(out));
        DebugInfo debug = new DebugInfo();
        debug.taskStatus = CHOPPING;
        // The player walks five blocks while Lune watches, then she takes the keys and stands to
        // mine where they stopped.
        for (int tick = 1; tick <= 20; tick++) {
            trace.charge(tick, debug, walkingTo(0.25 * tick, false), false, true);
        }
        for (int tick = 21; tick <= 30; tick++) {
            trace.charge(tick, debug, walkingTo(5.0, false), false, false);
        }
        trace.close("test");
        String journal = out.toString();

        assertEquals(20, ticks(journal, "SUMMARY beside"));
        assertEquals(0, ticks(journal, "SUMMARY paused"), "watching beside the player is not a pause");
        assertEquals(10, ticks(journal, "SUMMARY status"), "only the ticks she had the keys");
        String motion = line(journal, "SUMMARY motion");
        assertTrue(motion.contains("walked=0.00;"), "the player's walk is not the bot's: " + motion);
        assertTrue(motion.contains("idle=10 ticks"), motion);
    }

    private static RunTrace.Body walkingTo(double x, boolean airborne) {
        return new RunTrace.Body(x, 0, airborne, false, false, 20, 20);
    }

    private static String line(String journal, String prefix) {
        return journal.lines().filter(l -> l.startsWith(prefix)).findFirst()
                .orElseThrow(() -> new AssertionError("no line starting \"" + prefix + "\" in:\n" + journal));
    }

    /** The first tick count on the first line starting with {@code prefix}. */
    private static int ticks(String journal, String prefix) {
        String line = line(journal, prefix);
        Matcher count = Pattern.compile("(\\d+)( ticks|\\s)").matcher(line.substring(prefix.length()));
        if (!count.find()) {
            throw new AssertionError("no tick count on: " + line);
        }
        return Integer.parseInt(count.group(1));
    }
}
