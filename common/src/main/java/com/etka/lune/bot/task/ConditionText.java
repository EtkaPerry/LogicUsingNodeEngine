package com.etka.lune.bot.task;

import com.etka.lune.util.Lang;
import java.util.Locale;

/** Shared, side-effect-free wording for condition comparisons in the task and node editor. */
public final class ConditionText {

    private ConditionText() {}

    public static String comparisonPhrase(String comparison) {
        return switch (comparison) {
            case "Greater than" -> Lang.get("lune.condition.greater_than");
            case "Less than" -> Lang.get("lune.condition.less_than");
            case "Equal to" -> Lang.get("lune.condition.equal");
            case "Not equal" -> Lang.get("lune.condition.equal_2");
            case "At least" -> Lang.get("lune.condition.least");
            case "At most" -> Lang.get("lune.condition.most");
            default -> Lang.get("lune.condition.matches", comparison);
        };
    }

    public static String describeRule(String subject, String comparison, double threshold) {
        return Lang.get("lune.condition.rule", subject, comparisonPhrase(comparison), format(threshold));
    }

    private static String format(double value) {
        return value == Math.rint(value)
                ? String.valueOf((long) value)
                : String.format(Locale.ROOT, "%.1f", value);
    }
}
