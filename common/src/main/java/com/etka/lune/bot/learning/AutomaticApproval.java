package com.etka.lune.bot.learning;

/**
 * Pure decision rule for the engine's automatic mission verdict.
 *
 * <p>A successful first run is accepted while it establishes the usual duration. Once a usual
 * duration exists, a successful run is accepted only when it finishes in that time or faster. A
 * failed or aborted run is always rejected.</p>
 */
public final class AutomaticApproval {

    private AutomaticApproval() {}

    public static Decision evaluate(boolean success, long elapsedTicks, long usualTicks) {
        long elapsed = Math.max(0L, elapsedTicks);
        long usual = Math.max(0L, usualTicks);
        if (!success) {
            return new Decision(false, elapsed, usual, "mission failed or was aborted");
        }
        if (usual == 0L) {
            return new Decision(true, elapsed, 0L,
                    "mission succeeded; first duration becomes the usual baseline");
        }
        if (elapsed <= usual) {
            return new Decision(true, elapsed, usual,
                    "mission succeeded in " + elapsed + " ticks (usual " + usual + ")");
        }
        return new Decision(false, elapsed, usual,
                "mission took " + elapsed + " ticks, slower than usual " + usual);
    }

    public record Decision(boolean approved, long elapsedTicks, long usualTicks, String reason) {
        public String label() {
            return approved ? "approved" : "disapproved";
        }
    }
}
