package com.etka.lune.bot.task;

import java.util.List;

/** Safe melee-openers; enemy-specific retreat, shield and shelter rules remain authoritative. */
final class CombatPolicy {

    static final String CRITICAL_OPENER = "critical-opening-hit";
    static final String GROUNDED_OPENER = "grounded-opening-hit";
    static final List<String> ACTIONS = List.of(CRITICAL_OPENER, GROUNDED_OPENER);
    static final String DEFAULT = CRITICAL_OPENER;

    private CombatPolicy() {}

    static List<String> actions(boolean meleeMayBeUsed) {
        return meleeMayBeUsed ? ACTIONS : List.of(DEFAULT);
    }

    static boolean usesCritical(String action) {
        return !GROUNDED_OPENER.equals(action);
    }
}
