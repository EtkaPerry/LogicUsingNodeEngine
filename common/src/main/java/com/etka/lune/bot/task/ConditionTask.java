package com.etka.lune.bot.task;

import com.etka.lune.util.Lang;
import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.util.ClockSource;
import com.etka.lune.bot.util.InventoryHelper;
import com.etka.lune.bot.util.WorldClock;
import com.etka.lune.waypoint.Waypoint;
import com.etka.lune.waypoint.WaypointStore;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A one-tick checkpoint for task branching. Conditions deliberately finish with SUCCESS or
 * FAILED instead of owning a new branching system: the existing Success and Fail wires become the
 * true and false paths.
 */
public final class ConditionTask implements Task {

    @Override
    public boolean automaticSkillLearning() {
        return false;
    }

    public enum Kind {
        ITEM_COUNT,
        PLAYER_VALUE,
        WAYPOINT_DISTANCE,
        WORLD_TIME,
        CLOCK_TIME
    }

    /**
     * The two ways a Check Clock card can read a time of day.
     *
     * <p>Not the six numeric comparisons the other conditions use. "Time of day at most 1200" is
     * both correct and unreadable, and the two questions anybody actually asks a clock are whether
     * it is still before an hour and whether it has got there.</p>
     *
     * <p>Identifiers, written into saved tasks: only their rendering is translated.</p>
     */
    public static final String BEFORE = "Before";
    public static final String AT_OR_AFTER = "At or after";

    private final Kind kind;
    private final Item item;
    private final String metric;
    private final String waypointName;
    private final String comparison;
    private final int threshold;
    private final StatusText status = new StatusText();
    private final Map<String, String> outputs = new LinkedHashMap<>();

    private ConditionTask(Kind kind, Item item, String metric, String waypointName,
                          String comparison, int threshold) {
        this.kind = kind;
        this.item = item;
        this.metric = metric;
        this.waypointName = waypointName;
        this.comparison = comparison;
        this.threshold = threshold;
    }

    public static ConditionTask itemCount(Item item, String comparison, int threshold) {
        return new ConditionTask(Kind.ITEM_COUNT, item, null, null, comparison, threshold);
    }

    public static ConditionTask playerValue(String metric, String comparison, int threshold) {
        return new ConditionTask(Kind.PLAYER_VALUE, null, metric, null, comparison, threshold);
    }

    public static ConditionTask waypointDistance(String waypointName, String comparison, int threshold) {
        return new ConditionTask(Kind.WAYPOINT_DISTANCE, null, null, waypointName, comparison, threshold);
    }

    /**
     * Asks the overworld clock a yes-or-no question.
     *
     * <p>The odd one out among the conditions: a stretch of the day is not a number you usefully
     * compare against, so this one carries a phase name where the others carry a comparison. Making
     * it "time of day at most 13800" instead would have been consistent and unreadable.</p>
     */
    public static ConditionTask worldTime(String phase) {
        return new ConditionTask(Kind.WORLD_TIME, null, phase, null, null, 0);
    }

    /**
     * Asks a clock - the world's or the computer's - whether it has reached a given hour.
     *
     * <p>The companion to {@link #worldTime}: that one answers "is it dark?", this one answers "is
     * it eight yet?". A job told to run until 20:00 is this card's Success edge looping back into
     * the work and its Fail edge leaving.</p>
     *
     * @param clock a {@link ClockSource} label
     */
    public static ConditionTask clockTime(String clock, String comparison, int hour, int minute) {
        int target = Math.floorMod(hour, 24) * 60 + Math.floorMod(minute, 60);
        return new ConditionTask(Kind.CLOCK_TIME, null, clock, null, comparison, target);
    }

    @Override
    public String name() {
        return switch (kind) {
            case ITEM_COUNT -> Lang.get("lune.command.check_item.name");
            case PLAYER_VALUE -> Lang.get("lune.command.check_player.name");
            case WAYPOINT_DISTANCE -> Lang.get("lune.command.check_distance.name");
            case WORLD_TIME -> Lang.get("lune.command.check_time.name");
            case CLOCK_TIME -> Lang.get("lune.command.check_clock.name");
        };
    }

    @Override
    public String learningId() {
        return Task.learningName(switch (kind) {
            case ITEM_COUNT -> Lang.get("lune.command.check_item.name");
            case PLAYER_VALUE -> Lang.get("lune.command.check_player.name");
            case WAYPOINT_DISTANCE -> Lang.get("lune.command.check_distance.name");
            case WORLD_TIME -> Lang.get("lune.command.check_time.name");
            case CLOCK_TIME -> Lang.get("lune.command.check_clock.name");
        });
    }

    @Override
    public StatusText statusLine() {
        return status;
    }

    @Override
    public Map<String, String> dataOutputs() {
        return Map.copyOf(outputs);
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        return switch (kind) {
            case ITEM_COUNT -> checkItemCount(ctx);
            case PLAYER_VALUE -> checkPlayerValue(ctx);
            case WAYPOINT_DISTANCE -> checkWaypointDistance(ctx);
            case WORLD_TIME -> checkWorldTime(ctx);
            case CLOCK_TIME -> checkClockTime(ctx);
        };
    }

    private TaskStatus checkItemCount(BotContext ctx) {
        if (item == null) {
            status.set("lune.status.condition.no_item_selected");
            return TaskStatus.FAILED;
        }
        double actual = InventoryHelper.count(ctx.player, item);
        return result(InventoryHelper.itemName(item), actual);
    }

    private TaskStatus checkPlayerValue(BotContext ctx) {
        double actual = switch (metric) {
            case "Health" -> ctx.player.getHealth();
            case "Hunger" -> ctx.player.getFoodData().getFoodLevel();
            case "Air" -> ctx.player.getAirSupply();
            default -> {
                status.set("lune.status.condition.unknown_player_value", metric);
                yield Double.NaN;
            }
        };
        if (Double.isNaN(actual)) {
            return TaskStatus.FAILED;
        }
        return result(com.etka.lune.bot.command.Param.Choice.optionLabel(metric), actual);
    }

    private TaskStatus checkWaypointDistance(BotContext ctx) {
        if (waypointName == null || waypointName.isBlank()) {
            status.set("lune.status.condition.no_waypoint_selected");
            return TaskStatus.FAILED;
        }
        Waypoint waypoint = WaypointStore.get().byName(waypointName).orElse(null);
        if (waypoint == null) {
            status.set("lune.status.fail.no_waypoint_named", waypointName);
            return TaskStatus.FAILED;
        }
        String dimension = ctx.level.dimension().identifier().toString();
        if (!dimension.equals(waypoint.dimension())) {
            status.set("lune.status.fail.waypoint_other_dimension");
            return TaskStatus.FAILED;
        }
        BlockPos pos = waypoint.pos();
        double actual = Math.sqrt(ctx.player.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5));
        return result(Lang.get("lune.condition.distance_to", waypoint.name()), actual);
    }

    private TaskStatus checkWorldTime(BotContext ctx) {
        WorldClock.Phase wanted = WorldClock.fromLabel(metric);
        if (wanted == null) {
            status.set("lune.status.condition.unknown_time_day", metric);
            return TaskStatus.FAILED;
        }
        long time = WorldClock.dayTime(ctx.level);
        outputs.put(outputPort(), Long.toString(time));
        boolean matches = wanted.matches(time);
        status.set("lune.status.condition.which", Lang.get("lune.condition.world_time",
                com.etka.lune.bot.command.Param.Choice.optionLabel(WorldClock.bandOf(time).label()), WorldClock.clock(time)),
                com.etka.lune.bot.command.Param.Choice.optionLabel(wanted.label()),
                matches ? Lang.get("lune.gui.blueprint.success") : Lang.get("lune.gui.blueprint.fail"));
        return matches ? TaskStatus.SUCCESS : TaskStatus.FAILED;
    }

    /**
     * A line per rule, because the rule is not a word that can be dropped into a shared frame:
     * languages disagree about whether "before" comes before the hour or after it.
     */
    private TaskStatus checkClockTime(BotContext ctx) {
        ClockSource clock = ClockSource.fromLabel(metric);
        long now = clock.minuteOfDay(ctx.level);
        outputs.put(outputPort(), WorldClock.clockOf(now));
        boolean matches = clockMatches(comparison, now, threshold);
        status.set(AT_OR_AFTER.equals(comparison)
                        ? "lune.status.condition.clock_at_or_after"
                        : "lune.status.condition.clock_before",
                com.etka.lune.bot.command.Param.Choice.optionLabel(clock.label()),
                WorldClock.clockOf(now),
                WorldClock.clockOf(threshold),
                matches ? Lang.get("lune.gui.blueprint.success") : Lang.get("lune.gui.blueprint.fail"));
        return matches ? TaskStatus.SUCCESS : TaskStatus.FAILED;
    }

    /**
     * Whether a clock reading satisfies a Before / At or after rule, both in minutes past midnight.
     *
     * <p>Deliberately a plain comparison with no wrap-around cleverness. "Before 20:00" is true
     * again at one in the morning, because that is what a clock does and what the player reading
     * the card will predict. A job meant to stop overnight wants two of these cards, not a rule
     * that guesses which side of midnight was intended.</p>
     */
    public static boolean clockMatches(String comparison, long nowMinute, long targetMinute) {
        return AT_OR_AFTER.equals(comparison)
                ? nowMinute >= targetMinute
                : nowMinute < targetMinute;
    }

    private TaskStatus result(String subject, double actual) {
        outputs.put(outputPort(), format(actual));
        boolean passed = compare(actual, comparison, threshold);
        status.set("lune.status.condition.actual", subject, comparisonPhrase(comparison), threshold, format(actual));
        return passed ? TaskStatus.SUCCESS : TaskStatus.FAILED;
    }

    private String outputPort() {
        return switch (kind) {
            case ITEM_COUNT -> "count";
            case PLAYER_VALUE -> "value";
            case WAYPOINT_DISTANCE -> "distance";
            case WORLD_TIME -> "time";
            case CLOCK_TIME -> "clock";
        };
    }

    public static boolean compare(double actual, String comparison, double threshold) {
        return switch (comparison) {
            case "Greater than" -> actual > threshold;
            case "Less than" -> actual < threshold;
            case "Equal to" -> actual == threshold;
            case "Not equal" -> actual != threshold;
            case "At least" -> actual >= threshold;
            case "At most" -> actual <= threshold;
            default -> false;
        };
    }

    /**
     * The words shown to a player for a comparison choice. Keeping this beside the runtime
     * comparison prevents the editor and the task status from describing different rules.
     */
    public static String comparisonPhrase(String comparison) {
        return ConditionText.comparisonPhrase(comparison);
    }

    /** Returns the plain-language branch rule used by the node editor. */
    public static String describeRule(String subject, String comparison, double threshold) {
        return ConditionText.describeRule(subject, comparison, threshold);
    }

    private static String format(double value) {
        return value == Math.rint(value)
                ? String.valueOf((long) value)
                : String.format(Locale.ROOT, "%.1f", value);
    }
}
