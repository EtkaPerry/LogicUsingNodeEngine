package com.etka.lune.bot.task;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.util.InventoryHelper;
import com.etka.lune.waypoint.Waypoint;
import com.etka.lune.waypoint.WaypointStore;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A one-tick checkpoint for routine branching. Conditions deliberately finish with SUCCESS or
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
        WAYPOINT_DISTANCE
    }

    private final Kind kind;
    private final Item item;
    private final String metric;
    private final String waypointName;
    private final String comparison;
    private final int threshold;
    private String status = "";
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

    @Override
    public String name() {
        return switch (kind) {
            case ITEM_COUNT -> "Check Item Count";
            case PLAYER_VALUE -> "Check Player";
            case WAYPOINT_DISTANCE -> "Check Distance";
        };
    }

    @Override
    public String status() {
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
        };
    }

    private TaskStatus checkItemCount(BotContext ctx) {
        if (item == null) {
            status = "no item selected";
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
                status = "unknown player value '" + metric + "'";
                yield Double.NaN;
            }
        };
        if (Double.isNaN(actual)) {
            return TaskStatus.FAILED;
        }
        return result(metric, actual);
    }

    private TaskStatus checkWaypointDistance(BotContext ctx) {
        if (waypointName == null || waypointName.isBlank()) {
            status = "no waypoint selected";
            return TaskStatus.FAILED;
        }
        Waypoint waypoint = WaypointStore.get().byName(waypointName).orElse(null);
        if (waypoint == null) {
            status = "no waypoint named '" + waypointName + "'";
            return TaskStatus.FAILED;
        }
        String dimension = ctx.level.dimension().identifier().toString();
        if (!dimension.equals(waypoint.dimension())) {
            status = "waypoint is in another dimension";
            return TaskStatus.FAILED;
        }
        BlockPos pos = waypoint.pos();
        double actual = Math.sqrt(ctx.player.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5));
        return result("distance to " + waypoint.name(), actual);
    }

    private TaskStatus result(String subject, double actual) {
        outputs.put(outputPort(), format(actual));
        boolean passed = compare(actual, comparison, threshold);
        status = subject + " " + comparisonPhrase(comparison) + " " + threshold
                + " (actual " + format(actual) + ")";
        return passed ? TaskStatus.SUCCESS : TaskStatus.FAILED;
    }

    private String outputPort() {
        return switch (kind) {
            case ITEM_COUNT -> "count";
            case PLAYER_VALUE -> "value";
            case WAYPOINT_DISTANCE -> "distance";
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
