package com.etka.lune.bot.task;

import java.util.Locale;

/** Shared, side-effect-free wording for condition comparisons in the task and node editor. */
public final class ConditionText {

    private ConditionText() {}

    public static String comparisonPhrase(String comparison) {
        return switch (comparison) {
            case "Greater than" -> "is greater than";
            case "Less than" -> "is less than";
            case "Equal to" -> "is equal to";
            case "Not equal" -> "is not equal to";
            case "At least" -> "is at least";
            case "At most" -> "is at most";
            default -> "matches '" + comparison + "'";
        };
    }

    public static String describeRule(String subject, String comparison, double threshold) {
        return "If " + subject + " " + comparisonPhrase(comparison) + " " + format(threshold)
                + ": Success; otherwise: Fail.";
    }

    private static String format(double value) {
        return value == Math.rint(value)
                ? String.valueOf((long) value)
                : String.format(Locale.ROOT, "%.1f", value);
    }
}
