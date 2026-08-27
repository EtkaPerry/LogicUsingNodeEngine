package com.etka.lune.bot;

import java.util.ArrayList;
import java.util.List;

/** Pure formatting for the reasons a run is visibly blocked; kept testable without Minecraft. */
public final class BlockedReason {

    private BlockedReason() {}

    public static String describe(String obstruction, String placement, String breaking,
                                   String target, int waypointStallTicks, int goalStallTicks,
                                   String missionLoop) {
        List<String> reasons = new ArrayList<>();
        if (!blank(obstruction)) {
            reasons.add("obstruction=" + obstruction);
        }
        if (!blank(placement) && !placement.equalsIgnoreCase("placed")) {
            reasons.add("placement=" + placement);
        }
        if (!blank(breaking) && !breaking.equalsIgnoreCase("breaking")) {
            reasons.add("break=" + breaking);
        }
        if (!blank(target) && !target.startsWith("visible") && !target.equalsIgnoreCase("actionable")) {
            reasons.add("target=" + target);
        }
        if (waypointStallTicks > 0) {
            reasons.add("waypoint_stall_ticks=" + waypointStallTicks);
        }
        if (goalStallTicks > 0) {
            reasons.add("goal_stall_ticks=" + goalStallTicks);
        }
        if (!blank(missionLoop) && missionLoop.startsWith("possible loop")) {
            reasons.add(missionLoop);
        }
        return reasons.isEmpty() ? "none" : String.join(";", reasons);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
