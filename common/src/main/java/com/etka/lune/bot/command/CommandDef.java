package com.etka.lune.bot.command;

import com.etka.lune.bot.Task;
import com.etka.lune.bot.task.ConditionText;
import com.etka.lune.bot.util.InventoryHelper;
import net.minecraft.core.BlockPos;
import com.etka.lune.bot.catalog.BlockTarget;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * A command the user can pick from the Tasks palette: a name, the parameters it takes, and a factory
 * that turns those parameters into a runnable {@link Task}.
 * <p>
 * A definition also holds the live parameter values being edited. There is one local player and one
 * panel, so a single editing instance per command is enough - and it means the settings you last
 * used are still there next time you open the screen.
 */
public final class CommandDef {

    private final String id;
    private final String name;
    private final String description;
    private final List<Param<?>> params;
    private final Function<CommandDef, Task> factory;

    public CommandDef(String id, String name, String description, List<Param<?>> params,
                      Function<CommandDef, Task> factory) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.params = List.copyOf(params);
        this.factory = factory;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    /**
     * Plain-language explanation of the currently configured node. Conditions use the exact
     * comparison wording used by the runtime; ordinary nodes describe the work they perform.
     */
    public String logicDescription() {
        return logicDescription(1);
    }

    /** Same explanation with the node's repeat count, used for finite monitor circuits. */
    public String logicDescription(int repeat) {
        return switch (id) {
            case "check_item" -> ConditionText.describeRule(
                    "inventory count of " + itemName(), choiceValue("comparison"), intValue("count"));
            case "check_player" -> ConditionText.describeRule(
                    "player " + choiceValue("metric"), choiceValue("comparison"), intValue("threshold"));
            case "check_distance" -> ConditionText.describeRule(
                    "distance to " + waypointName() + " in blocks",
                    choiceValue("comparison"), intValue("distance"));
            case "stay_near" -> stayNearDescription(repeat);
            case "self_preservation" -> selfPreservationDescription(repeat);
            case "chop" -> actionDescription("fell trees within " + intValue("radius") + " blocks, stopping after "
                    + limitDescription(intValue("limit"), "logs"));
            case "mine" -> actionDescription("mine matching blocks within " + intValue("radius")
                    + " blocks, between Y " + intValue("y_min") + " and " + intValue("y_max")
                    + ", stopping after " + limitDescription(intValue("limit"), "blocks"));
            case "portal" -> actionDescription("build and light a Nether portal using the "
                    + choiceValue("frame_mode") + " frame mode");
            case "harvest" -> actionDescription("harvest mature crops within " + intValue("radius")
                    + " blocks, stopping after " + limitDescription(intValue("limit"), "crops")
                    + (boolValue("collect") ? ", collecting the drops" : "")
                    + (boolValue("replant") ? ", and replanting matching seeds" : ""));
            case "find" -> "Lune will look within " + intValue("radius") + " blocks for the nearest "
                    + choiceValue("target_kind").toLowerCase() + "; finding one gives Success, finding none gives Fail.";
            case "explore" -> "Lune will walk up to " + intValue("attempts") + " directions, scanning every "
                    + intValue("step") + " blocks for a visible target; seeing one gives Success. If none is found, it gives Fail.";
            case "walk" -> travelDescription(false);
            case "run" -> travelDescription(true);
            case "waypoint" -> "Lune will travel to waypoint " + waypointNameFor("name") + " and get within "
                    + intValue("tolerance") + " blocks of it; arrival gives Success, no route gives Fail.";
            case "eat" -> actionDescription("eat until hunger reaches " + intValue("minimum_food"));
            case "loot" -> actionDescription("pick up nearby dropped items within " + intValue("radius") + " blocks");
            case "timer" -> {
                int seconds = intValue("seconds");
                if (repeat == 0) {
                    yield "After receiving a pulse, Lune waits " + seconds
                            + " seconds, forwards a pulse, and repeats until stopped.";
                }
                if (repeat == 1) {
                    yield "After receiving a pulse, Lune waits " + seconds
                            + " seconds, forwards one pulse, then finishes.";
                }
                yield "After receiving a pulse, Lune waits " + seconds
                        + " seconds and forwards a pulse; it repeats " + repeat
                        + " times, then finishes.";
            }
            case "stop_game" -> stopGameDescription();
            case "select_item" -> selectItemDescription();
            case "counter" -> "After every " + intValue("count")
                    + " incoming pulses, Lune forwards one pulse and starts counting again.";
            case "observer" -> observerDescription();
            case "end" -> "Lune consumes the incoming pulse and finishes that circuit.";
            case "button" -> "Press the Button control to send one pulse to its connected outputs.";
            default -> actionDescription(lowerFirst(description));
        };
    }

    private String selectItemDescription() {
        String which = switch (choiceValue("enchanting")) {
            case "Enchanted only" -> "an enchanted " + itemName();
            case "Unenchanted only" -> "an unenchanted " + itemName();
            default -> itemName();
        };
        int floor = intValue("min_durability");
        String durability = floor == 0 ? ""
                : " with at least " + floor + "% durability left";
        String tie = "Least durability".equals(choiceValue("prefer"))
                ? " Carrying several, it holds the most worn one."
                : " Carrying several, it holds the one with the most durability left.";
        return "Lune will hold " + which + durability + " in the "
                + choiceValue("hand").toLowerCase() + "." + tie
                + " Holding it gives Success; having none left"
                + (floor == 0 ? "" : ", or only worn-out ones,") + " gives Fail.";
    }

    private String stopGameDescription() {
        return switch (choiceValue("ending")) {
            case "Pause the game" -> "Lune will open the pause menu, which in singleplayer also"
                    + " stops the world clock. On a server nothing pauses, so it gives Fail there.";
            case "Return to main menu" -> "Lune will save and leave the world, stopping at the"
                    + " title screen. The client stays open.";
            default -> "Lune will save and leave the world, then close the game.";
        };
    }

    private String observerDescription() {
        return "Lune sends one pulse each time the watched card lights up, and one more each time"
                + " it goes dark. Wire the card to watch into the Observer's left pin.";
    }

    private String stayNearDescription(int repeat) {
        String centre = "Where the run started".equals(choiceValue("anchor"))
                ? "the starting point"
                : "waypoint " + waypointNameFor("waypoint");
        if (repeat == 0) {
            return "Lune will keep watching within " + intValue("radius") + " blocks of " + centre
                    + ". Connected While actions work inside; if the bot leaves, walk back."
                    + " It stays active until stopped and fails only if there is no route home.";
        }
        String completion = repeat == 1
                ? "Staying inside gives Success"
                : "Staying inside for " + repeat + " checks gives Success";
        return "Lune will stay within " + intValue("radius") + " blocks of " + centre
                + ". Connected While actions work inside. " + completion + "; if the bot leaves,"
                + " it walks back. It fails only if there is no route home.";
    }

    private String selfPreservationDescription(int repeat) {
        StringBuilder text = new StringBuilder("Protect against");
        boolean hasProtection = false;
        if (boolValue("protect_health")) {
            text.append(" health ").append(ConditionText.comparisonPhrase(choiceValue("health_compare")))
                    .append(' ').append(intValue("health_value"));
            hasProtection = true;
        }
        if (boolValue("protect_air")) {
            text.append(hasProtection ? ", air " : " air ")
                    .append(ConditionText.comparisonPhrase(choiceValue("air_compare")))
                    .append(' ').append(intValue("air_value"));
            hasProtection = true;
        }
        if (boolValue("protect_monsters")) {
            text.append(hasProtection ? ", nearest hostile " : " nearest hostile ")
                    .append(ConditionText.comparisonPhrase(choiceValue("monster_compare")))
                    .append(' ').append(intValue("monster_distance")).append(" blocks");
            hasProtection = true;
        }
        if (boolValue("protect_lava")) {
            text.append(hasProtection ? ", lava" : " lava");
            hasProtection = true;
        }
        if (boolValue("protect_fall")) {
            text.append(hasProtection ? ", falls of " : " falls of ")
                    .append(intValue("fall_threshold")).append("+ blocks");
            hasProtection = true;
        }
        if (!hasProtection) {
            return "No protection is enabled; other circuits keep working.";
        }
        if (repeat == 0) {
            return text.append(". Other circuits keep working; this protection stays active until stopped.")
                    .toString();
        }
        String completion = repeat == 1
                ? "Staying safe gives Success"
                : "Staying safe for " + repeat + " checks gives Success";
        return text.append(". ").append(completion)
                .append("; failing to recover gives Fail.").toString();
    }

    private String travelDescription(boolean sprint) {
        String direction = choiceValue("direction");
        if ("Coordinates".equals(direction)) {
            return "Lune will " + (sprint ? "sprint" : "walk") + " to the selected coordinates, stopping within "
                    + intValue("tolerance") + " blocks; arrival gives Success, no route gives Fail.";
        }
        return "Lune will " + (sprint ? "sprint" : "walk") + " " + intValue("distance") + " blocks "
                + direction.toLowerCase() + ". Finishing gives Success; being unable to continue gives Fail.";
    }

    private String limitDescription(int limit, String unit) {
        return limit == 0 ? "an unlimited number of " + unit : limit + " " + unit;
    }

    private String actionDescription(String action) {
        return "Lune will " + action
                + ". It gives Success when the action is complete and Fail when it cannot continue.";
    }

    private String waypointNameFor(String parameterId) {
        String waypoint = choiceValue(parameterId);
        return waypoint == null || waypoint.isBlank() ? "the selected waypoint" : "'" + waypoint + "'";
    }

    private static String lowerFirst(String value) {
        if (value == null || value.isBlank()) {
            return "perform this action";
        }
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    private String itemName() {
        Item item = itemValue("item");
        return item == null ? "the selected item" : InventoryHelper.itemName(item);
    }

    private String waypointName() {
        String waypoint = choiceValue("waypoint");
        return waypoint == null || waypoint.isBlank() ? "the selected waypoint" : waypoint;
    }

    public List<Param<?>> params() {
        return params;
    }

    /** Builds a task from the current parameter values. */
    public Task build() {
        return factory.apply(this);
    }

    /** The current values, keyed by parameter id, in the portable form tasks store. */
    public Map<String, String> snapshot() {
        Map<String, String> values = new LinkedHashMap<>();
        for (Param<?> param : params) {
            values.put(param.id(), param.serialize());
        }
        return values;
    }

    /** Applies stored values; parameters absent from {@code values} keep whatever they hold. */
    public void apply(Map<String, String> values) {
        for (Param<?> param : params) {
            String raw = values.get(param.id());
            if (raw != null) {
                param.deserialize(raw);
            }
        }
    }

    /**
     * Builds a task from stored values without disturbing what the user is editing.
     * <p>
     * A definition doubles as the Tasks palette's live editing state, so a task step must not
     * overwrite it - hence the save/restore around the build. Safe because the client is
     * single-threaded and the factory reads the parameters synchronously.
     */
    public Task buildWith(Map<String, String> values) {
        Map<String, String> saved = snapshot();
        try {
            apply(values);
            return build();
        } finally {
            apply(saved);
        }
    }

    /** True if this command matches a search box query, by name or description. */
    public boolean matches(String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String lower = query.toLowerCase();
        return name.toLowerCase().contains(lower)
                || id.toLowerCase().contains(lower)
                || description.toLowerCase().contains(lower);
    }

    private Param<?> param(String paramId) {
        for (Param<?> param : params) {
            if (param.id().equals(paramId)) {
                return param;
            }
        }
        throw new IllegalArgumentException("Command '" + id + "' has no parameter '" + paramId + "'");
    }

    // Typed accessors, so task factories read parameters without casting at every call site.

    public int intValue(String paramId) {
        return ((Param.Ints) param(paramId)).get();
    }

    public boolean boolValue(String paramId) {
        return ((Param.Bool) param(paramId)).get();
    }

    public String choiceValue(String paramId) {
        return ((Param.Choice) param(paramId)).get();
    }

    public BlockTarget blockValue(String paramId) {
        return ((Param.BlockSet) param(paramId)).get();
    }

    public Set<EntityType<?>> entityValue(String paramId) {
        return Set.copyOf(((Param.EntitySet) param(paramId)).get());
    }

    public Item itemValue(String paramId) {
        return ((Param.ItemChoice) param(paramId)).get();
    }

    public BlockPos posValue(String paramId) {
        return ((Param.Pos) param(paramId)).get();
    }
}
