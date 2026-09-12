package com.etka.lune.bot.command;

import com.etka.lune.bot.Task;
import com.etka.lune.util.Lang;
import com.etka.lune.bot.task.ConditionText;
import com.etka.lune.bot.task.PlaceBlockTask;
import com.etka.lune.bot.task.StepPolicy;
import com.etka.lune.bot.task.SaveWaypointTask;
import com.etka.lune.bot.util.InventoryHelper;
import net.minecraft.core.BlockPos;
import com.etka.lune.bot.catalog.BlockTarget;
import com.etka.lune.bot.catalog.CraftPattern;
import com.etka.lune.bot.catalog.CraftRecipe;
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
    private final List<Param<?>> params;
    private final Function<CommandDef, Task> factory;

    public CommandDef(String id, List<Param<?>> params,
                      Function<CommandDef, Task> factory) {
        this.id = id;
        this.params = List.copyOf(params);
        this.factory = factory;
        for (Param<?> param : this.params) {
            param.attachTo(id);
        }
    }

    public String id() {
        return id;
    }

    /** The palette name, in the player's language. */
    public String name() {
        return Lang.get("lune.command." + id + ".name");
    }

    public String description() {
        return Lang.get("lune.command." + id + ".desc");
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
                    Lang.get("lune.card.inventory_count", itemName()), choiceValue("comparison"), intValue("count"));
            case "check_player" -> ConditionText.describeRule(
                    Lang.get("lune.card.player_metric", Param.Choice.optionLabel(choiceValue("metric"))), choiceValue("comparison"), intValue("threshold"));
            case "check_distance" -> ConditionText.describeRule(
                    Lang.get("lune.card.distance_blocks", waypointName()),
                    choiceValue("comparison"), intValue("distance"));
            case "check_time" -> Lang.get("lune.card.if_overworld_clock_says_success", Param.Choice.optionLabel(choiceValue("phase")));
            case "stay_near" -> stayNearDescription(repeat);
            case "self_preservation" -> selfPreservationDescription(repeat);
            case "chop" -> actionDescription(Lang.get("lune.card.fell_trees_within_blocks_stopping_after", intValue("radius"), limitDescription(intValue("limit"), "logs")));
            case "mine" -> actionDescription(Lang.get("lune.card.mine_matching_blocks_within_blocks", intValue("radius"), intValue("y_min"), intValue("y_max"), limitDescription(intValue("limit"), "blocks")));
            case "portal" -> actionDescription(Lang.get("lune.card.build_light_nether_portal_using_frame", Param.Choice.optionLabel(choiceValue("frame_mode"))));
            case "harvest" -> actionDescription(Lang.get("lune.card.harvest_mature_crops_within_blocks", intValue("radius"), limitDescription(intValue("limit"), "crops"), (boolValue("collect") ? Lang.get("lune.card.collecting_drops") : ""), (boolValue("replant") ? Lang.get("lune.card.replanting_matching_seeds") : "")));
            case "find" -> Lang.get("lune.card.lune_look_within_blocks_nearest_finding", intValue("radius"), Param.Choice.optionLabel(choiceValue("target_kind")));
            case "explore" -> Lang.get("lune.card.lune_walk_up_directions_scanning_every", intValue("attempts"), intValue("step"));
            case "walk" -> travelDescription(false);
            case "run" -> travelDescription(true);
            case "waypoint" -> Lang.get("lune.card.lune_travel_waypoint_get_within_blocks", waypointNameFor("name"), intValue("tolerance"));
            case "craft" -> craftDescription();
            case "save_waypoint" -> saveWaypointDescription();
            case "place" -> placeBlockDescription();
            case "step" -> stepDescription();
            case "eat" -> actionDescription(Lang.get("lune.card.eat_until_hunger_reaches", intValue("minimum_food")));
            case "loot" -> actionDescription(Lang.get("lune.card.pick_up_nearby_dropped_items_within", intValue("radius")));
            case "timer" -> {
                int seconds = intValue("seconds");
                if (repeat == 0) {
                    yield Lang.get("lune.card.after_receiving_pulse_lune_waits_seconds", seconds);
                }
                if (repeat == 1) {
                    yield Lang.get("lune.card.after_receiving_pulse_lune_waits_seconds_2", seconds);
                }
                yield Lang.get("lune.card.after_receiving_pulse_lune_waits_seconds_3", seconds, repeat);
            }
            case "stop_game" -> stopGameDescription();
            case "select_item" -> selectItemDescription();
            case "counter" -> Lang.get("lune.card.after_every_incoming_pulses_lune", intValue("count"));
            case "observer" -> observerDescription();
            case "end" -> Lang.get("lune.card.lune_consumes_incoming_pulse_finishes");
            case "button" -> Lang.get("lune.card.press_button_control_send_one_pulse");
            default -> actionDescription(lowerFirst(description()));
        };
    }

    private String selectItemDescription() {
        String which = switch (choiceValue("enchanting")) {
            case "Enchanted only" -> Lang.get("lune.card.enchanted", itemName());
            case "Unenchanted only" -> Lang.get("lune.card.unenchanted", itemName());
            default -> itemName();
        };
        int floor = intValue("min_durability");
        String durability = floor == 0 ? ""
                : Lang.get("lune.card.with_least_durability_left", floor);
        String tie = "Least durability".equals(choiceValue("prefer"))
                ? Lang.get("lune.card.carrying_several_holds_most_worn_one")
                : Lang.get("lune.card.carrying_several_holds_one_with_most");
        return Lang.get("lune.card.lune_hold_holding_gives_success_having", which, durability, Param.Choice.optionLabel(choiceValue("hand")), tie, (floor == 0 ? "" : Lang.get("lune.card.or_only_worn_out_ones")));
    }

    /**
     * The two halves of Craft read as different jobs, because they are: one asks the recipe book
     * for something by name, the other lays out a drawing whose result nobody has named.
     */
    private String craftDescription() {
        CraftRecipe recipe = recipeValue("recipe");
        int count = intValue("count");
        if (!recipe.isDrawn()) {
            if (recipe.item() == null) {
                return Lang.get("lune.card.nothing_chosen_yet_click_recipe_row");
            }
            return Lang.get("lune.card.lune_craft_top_any_already_carries", count, recipe.describe(), (boolValue("table") ? Lang.get("lune.card.using_crafting_table") : Lang.get("lune.card.using_2x2_grid")));
        }
        CraftPattern pattern = recipe.pattern();
        if (pattern.isEmpty()) {
            return Lang.get("lune.card.grid_empty_click_recipe_row_open_draw");
        }
        return Lang.get("lune.card.lune_lay_drawn_ingredients_into_take", pattern.filledCells(), (pattern.fitsIn(CraftPattern.SMALL) ? Lang.get("lune.card.own_2x2_grid") : Lang.get("lune.card.crafting_table")), count == 1 ? Lang.get("lune.card.once") : Lang.get("lune.card.times", count));
    }

    /**
     * Whether a parameter is worth showing for the values the card currently holds.
     * <p>
     * A row that is read in one mode and ignored in the other is worse than no row: it invites an
     * answer and then throws it away. A drawing already knows whether it needs a table - it is
     * three cells wide or it is not - so asking is not a choice, it is noise.
     */
    public boolean isRelevant(String paramId) {
        if ("craft".equals(id) && "table".equals(paramId)) {
            return !recipeValue("recipe").isDrawn();
        }
        if ("place".equals(id)) {
            PlaceBlockTask.Where where = PlaceBlockTask.Where.fromLabel(choiceValue("where"));
            if ("target".equals(paramId)) {
                return where == PlaceBlockTask.Where.COORDINATES;
            }
            if ("waypoint".equals(paramId)) {
                return where == PlaceBlockTask.Where.WAYPOINT;
            }
        }
        if ("self_preservation".equals(id)) {
            // How to answer a danger is only a question while that danger is being watched for.
            if (paramId.startsWith("clutch_")) {
                return boolValue("protect_fall");
            }
            if ("protect_fireballs".equals(paramId) || "build_cover".equals(paramId)) {
                return boolValue("protect_monsters");
            }
        }
        if ("save_waypoint".equals(id)) {
            boolean saving = SaveWaypointTask.Action.ADD.label().equals(choiceValue("action"));
            // A new place is named, an old one is chosen from the list. Never both.
            if ("name".equals(paramId)) {
                return saving;
            }
            if ("waypoint".equals(paramId)) {
                return !saving;
            }
        }
        return true;
    }

    /** Saving a place, moving one and forgetting one are three sentences, not one with a switch. */
    private String saveWaypointDescription() {
        String action = choiceValue("action");
        if (SaveWaypointTask.Action.REMOVE.label().equals(action)) {
            String waypoint = choiceValue("waypoint");
            return waypoint == null || waypoint.isBlank()
                    ? Lang.get("lune.card.choose_which_saved_place_forget")
                    : Lang.get("lune.card.lune_forget_waypoint_forgetting_gives", waypoint);
        }
        if (SaveWaypointTask.Action.MOVE.label().equals(action)) {
            String waypoint = choiceValue("waypoint");
            return waypoint == null || waypoint.isBlank()
                    ? Lang.get("lune.card.choose_which_saved_place_move_wherever")
                    : Lang.get("lune.card.lune_move_waypoint_wherever_standing", waypoint);
        }
        String name = textValue("name");
        if (name.isBlank()) {
            return Lang.get("lune.card.lune_save_wherever_standing_name_after");
        }
        return Lang.get("lune.card.lune_save_wherever_standing_as_replacing", name);
    }

    /** Where a block goes is the only interesting thing about this card, so the wording leads with it. */
    private String placeBlockDescription() {
        PlaceBlockTask.Where where = PlaceBlockTask.Where.fromLabel(choiceValue("where"));
        String spot = switch (where) {
            case IN_FRONT -> Lang.get("lune.card.spot_front");
            case UNDER -> Lang.get("lune.card.spot_under_feet");
            case ABOVE -> Lang.get("lune.card.spot_over_head");
            case COORDINATES -> {
                BlockPos target = posValue("target");
                yield target == null ? Lang.get("lune.card.coordinates_have_set_yet")
                        : Lang.get("lune.card.spot", target.getX(), target.getY(), target.getZ());
            }
            case WAYPOINT -> Lang.get("lune.card.named_waypoint", waypointNameFor("waypoint"));
        };
        String walking = where.isRelative() ? "" : Lang.get("lune.card.walking_first");
        return Lang.get("lune.card.lune_put_one_placing_gives_success_spot", blockName("blocks"), spot, walking);
    }

    /** The chosen block, or the first of several, for a sentence that has to name one. */
    private String blockName(String parameterId) {
        BlockTarget target = blockValue(parameterId);
        return target.anyConcreteBlock()
                .map(block -> block.getName().getString())
                .orElse(Lang.get("lune.gui.overlay.a_block"));
    }

    /** The point of the card is what it does not do - turn - so the wording has to say that. */
    private String stepDescription() {
        int blocks = intValue("blocks");
        return Lang.get("lune.card.lune_move_where_facing_without_turning", blocks, Lang.get(blocks == 1 ? "lune.card.block_unit" : "lune.card.blocks_unit"), lowerFirst(Param.Choice.optionLabel(choiceValue("side"))), (boolValue("careful") ? Lang.get("lune.card.sneaking_stops_block_cannot_walk_off") : Lang.get("lune.card.walking")));
    }

    private String stopGameDescription() {
        return switch (choiceValue("ending")) {
            case "Pause the game" -> Lang.get("lune.card.lune_open_pause_menu_which_singleplayer");
            case "Return to main menu" -> Lang.get("lune.card.lune_save_leave_world_stopping_title");
            default -> Lang.get("lune.card.lune_save_leave_world_then_close_game");
        };
    }

    private String observerDescription() {
        return Lang.get("lune.card.lune_sends_one_pulse_each_time_watched");
    }

    private String stayNearDescription(int repeat) {
        String centre = "Where the run started".equals(choiceValue("anchor"))
                ? Lang.get("lune.card.starting_point")
                : Lang.get("lune.card.named_waypoint", waypointNameFor("waypoint"));
        if (repeat == 0) {
            return Lang.get("lune.card.lune_keep_watching_within_blocks", intValue("radius"), centre);
        }
        String completion = repeat == 1
                ? Lang.get("lune.card.staying_inside_gives_success")
                : Lang.get("lune.card.staying_inside_checks_gives_success", repeat);
        return Lang.get("lune.card.lune_stay_within_blocks_connected_while", intValue("radius"), centre, completion);
    }

    private String selfPreservationDescription(int repeat) {
        StringBuilder text = new StringBuilder(Lang.get("lune.card.protect_against"));
        boolean hasProtection = false;
        if (boolValue("protect_health")) {
            text.append(" ").append(Param.Choice.optionLabel("Health")).append(" ").append(ConditionText.comparisonPhrase(choiceValue("health_compare")))
                    .append(' ').append(intValue("health_value"));
            hasProtection = true;
        }
        if (boolValue("protect_air")) {
            text.append(hasProtection ? ", " : " ").append(Param.Choice.optionLabel("Air")).append(" ")
                    .append(ConditionText.comparisonPhrase(choiceValue("air_compare")))
                    .append(' ').append(intValue("air_value"));
            hasProtection = true;
        }
        if (boolValue("protect_monsters")) {
            text.append(hasProtection ? Lang.get("lune.card.nearest_hostile") : Lang.get("lune.card.nearest_hostile_2"))
                    .append(ConditionText.comparisonPhrase(choiceValue("monster_compare")))
                    .append(' ').append(intValue("monster_distance")).append(" ").append(Param.Choice.optionLabel("blocks"));
            hasProtection = true;
        }
        if (boolValue("protect_lava")) {
            text.append(hasProtection ? ", " : " ").append(Lang.get("lune.card.lava"));
            hasProtection = true;
        }
        if (boolValue("protect_fall")) {
            text.append(hasProtection ? Lang.get("lune.card.falls") : Lang.get("lune.card.falls_2"))
                    .append(intValue("fall_threshold")).append("+ ").append(Param.Choice.optionLabel("blocks"));
            hasProtection = true;
        }
        if (!hasProtection) {
            return Lang.get("lune.card.protection_enabled_other_circuits_keep");
        }
        if (repeat == 0) {
            return text.append(Lang.get("lune.card.other_circuits_keep_working_protection"))
                    .toString();
        }
        String completion = repeat == 1
                ? Lang.get("lune.card.staying_safe_gives_success")
                : Lang.get("lune.card.staying_safe_checks_gives_success", repeat);
        return text.append(". ").append(completion)
                .append(Lang.get("lune.card.failing_recover_gives_fail")).toString();
    }

    private String travelDescription(boolean sprint) {
        String direction = choiceValue("direction");
        if ("Coordinates".equals(direction)) {
            return Lang.get("lune.card.lune_selected_coordinates_stopping", (sprint ? Lang.get("lune.card.sprint") : Lang.get("lune.card.walk")), intValue("tolerance"));
        }
        return Lang.get("lune.card.lune_blocks_finishing_gives_success", (sprint ? Lang.get("lune.card.sprint") : Lang.get("lune.card.walk")), intValue("distance"), Param.Choice.optionLabel(direction));
    }

    private String limitDescription(int limit, String unit) {
        String label = Param.Choice.optionLabel(unit);
        return limit == 0 ? Lang.get("lune.card.unlimited_number", label) : limit + " " + label;
    }

    private String actionDescription(String action) {
        return Lang.get("lune.card.lune_gives_success_when_action_complete", action);
    }

    private String waypointNameFor(String parameterId) {
        String waypoint = choiceValue(parameterId);
        return waypoint == null || waypoint.isBlank() ? Lang.get("lune.card.selected_waypoint") : "'" + waypoint + "'";
    }

    private static String lowerFirst(String value) {
        if (value == null || value.isBlank()) {
            return Lang.get("lune.card.perform_action");
        }
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    private String itemName() {
        Item item = itemValue("item");
        return item == null ? Lang.get("lune.card.selected_item") : InventoryHelper.itemName(item);
    }

    private String waypointName() {
        String waypoint = choiceValue("waypoint");
        return waypoint == null || waypoint.isBlank() ? Lang.get("lune.card.selected_waypoint") : waypoint;
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
        return name().toLowerCase().contains(lower)
                || id.toLowerCase().contains(lower)
                || description().toLowerCase().contains(lower);
    }

    private Param<?> param(String paramId) {
        for (Param<?> param : params) {
            if (param.id().equals(paramId)) {
                return param;
            }
        }
        throw new IllegalArgumentException(Lang.get("lune.card.command_has_parameter", id, paramId));
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

    public CraftRecipe recipeValue(String paramId) {
        return ((Param.Recipe) param(paramId)).get();
    }

    public String textValue(String paramId) {
        return ((Param.Text) param(paramId)).get();
    }
}
