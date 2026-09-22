package com.etka.lune.bot;

import java.util.HashMap;
import java.util.Map;

import static com.etka.lune.bot.StatusSignal.BLOCKED;
import static com.etka.lune.bot.StatusSignal.BRIDGING;
import static com.etka.lune.bot.StatusSignal.DANGER;
import static com.etka.lune.bot.StatusSignal.DEAD;
import static com.etka.lune.bot.StatusSignal.FIGHT;
import static com.etka.lune.bot.StatusSignal.FLEE;
import static com.etka.lune.bot.StatusSignal.FRAMING;
import static com.etka.lune.bot.StatusSignal.HURT;
import static com.etka.lune.bot.StatusSignal.INVENTORY_FULL;
import static com.etka.lune.bot.StatusSignal.LOADING;
import static com.etka.lune.bot.StatusSignal.MISSING_MATERIALS;
import static com.etka.lune.bot.StatusSignal.NONE;
import static com.etka.lune.bot.StatusSignal.PILLARING;
import static com.etka.lune.bot.StatusSignal.RECOVERING;
import static com.etka.lune.bot.StatusSignal.SEARCH;
import static com.etka.lune.bot.StatusSignal.STAIRS;
import static com.etka.lune.bot.StatusSignal.SUCCESS;
import static com.etka.lune.bot.StatusSignal.TRAVEL;
import static com.etka.lune.bot.StatusSignal.WAITING;

/**
 * Which status lines mean something is wrong, right, or merely slow.
 *
 * <p>One table, because "what makes Lune look worried" is a question worth being able to answer by
 * reading a file rather than by grepping eighty task classes for adjectives. Every key not listed
 * here means {@link StatusSignal#NONE} - ordinary work in progress - so the table only ever holds
 * the lines that change her face.</p>
 *
 * <p>{@link StatusSignal#DANGER} used to carry the whole of it, which meant drawing a bow,
 * running from a creeper, bleeding out and standing in lava all wore the same face. It now means
 * only what the <em>world</em> is doing to her - lava, water, air, a fall. A mob she is facing is
 * {@link StatusSignal#FIGHT}, a mob she is getting away from is {@link StatusSignal#FLEE}, and
 * her own body is {@link StatusSignal#HURT}, {@link StatusSignal#RECOVERING} or
 * {@link StatusSignal#DEAD}.</p>
 *
 * <p>The last few signals are not trouble at all: they say which piece of ordinary work is in hand,
 * so bridging a gap, pillaring up, cutting stairs, raising a portal frame and walking somewhere
 * each get their own face. They sit at the bottom of {@link StatusSignal#priority()}, so a
 * blockage or a mob still wins over "she is building".</p>
 */
public final class StatusKeys {

    private static final Map<String, StatusSignal> SIGNALS = new HashMap<>();

    private StatusKeys() {}

    /** What the given status key means. Unlisted keys are ordinary progress. */
    public static StatusSignal signalOf(String key) {
        if (key == null || key.isEmpty()) {
            return NONE;
        }
        return SIGNALS.getOrDefault(key, NONE);
    }

    /** Visible for the test that checks every declared key still exists in the language file. */
    public static Map<String, StatusSignal> all() {
        return Map.copyOf(SIGNALS);
    }

    private static void put(StatusSignal signal, String... keys) {
        for (String key : keys) {
            SIGNALS.put(key, signal);
        }
    }

    static {
        put(DEAD,
                "lune.engine.player_died",
                "lune.status.self_preservation.player_dead");

        put(SUCCESS,
                "lune.status.dragon_egg.collected",
                "lune.status.self_preservation.escaped_lava",
                "lune.status.self_preservation.fireball_gone",
                "lune.status.self_preservation.fireball_sent_back",
                "lune.status.self_preservation.fireball_sent_back_at",
                "lune.status.self_preservation.landed_safely",
                "lune.status.self_preservation.threat_gone",
                "lune.status.self_preservation.water_placed");

        put(DANGER,
                "lune.status.break.not_enough_air_for_breach",
                "lune.status.break.refusing_lava_flow",
                "lune.status.break.water_has_no_exit",
                "lune.status.mine.tree_drop_not_worth_it",
                "lune.status.route.escaped_water_replanning",
                "lune.status.route.escaping_to_air",
                "lune.status.route.refusing_dig_beside_lava",
                "lune.status.route.unexpected_fall",
                "lune.status.route.water_escaping",
                "lune.status.route.water_no_safe_exit",
                "lune.status.self_preservation.escaping_air_left",
                "lune.status.self_preservation.escaping_lava",
                "lune.status.self_preservation.falling_through_water",
                "lune.status.self_preservation.no_safe_ground_near_lava",
                "lune.status.self_preservation.nothing_below_land_yet",
                "lune.status.self_preservation.placing_water",
                "lune.status.self_preservation.waiting_land",
                "lune.status.self_preservation.water_out_reach",
                "lune.status.speedrun.aiming_lava_source",
                "lune.status.speedrun.aiming_water_lava",
                "lune.status.speedrun.found_water_lava_sources",
                "lune.status.speedrun.getting_lava",
                "lune.status.speedrun.placing_lava",
                "lune.status.speedrun.waiting_lava_bucket",
                "lune.status.speedrun.waiting_lava_source_place");

        put(FIGHT,
                "lune.status.complete_game.fighting_dragon",
                "lune.status.kill.charging_bow",
                "lune.status.kill.drawing_bow",
                "lune.status.kill.fighting_killed",
                "lune.status.kill.lowering_shield_draw_bow",
                "lune.status.kill.lowering_shield_strike",
                "lune.status.kill.no_fire_resistance_potion_fighting_blaze",
                "lune.status.kill.waiting_fire_resistance",
                "lune.status.self_preservation.batting_fireball_back",
                "lune.status.self_preservation.batting_fireball_back_at",
                "lune.status.self_preservation.equipping_weapon_while_retreating_from",
                "lune.status.self_preservation.fighting_creeper_from_unsafe_ground",
                "lune.status.self_preservation.fighting_from_emergency_cover",
                "lune.status.self_preservation.fighting_retreating",
                "lune.status.self_preservation.fighting_strafing",
                "lune.status.self_preservation.freeing_hand_to_bat_fireball",
                "lune.status.self_preservation.holding_ground_fighting",
                "lune.status.self_preservation.lining_up_fireball_return",
                "lune.status.self_preservation.strafing_out_arrow_line_from",
                "lune.status.self_preservation.strafing_out_line_of_fire_from",
                "lune.status.self_preservation.waiting_fireball_reach",
                "lune.status.speedrun.fighting");

        put(FLEE,
                "lune.status.kill.backing_off_from",
                "lune.status.kill.building_enderman_shelter_support",
                "lune.status.kill.built_two_block_enderman_shelter",
                "lune.status.kill.could_not_place_enderman_shelter_roof",
                "lune.status.kill.could_not_place_enderman_shelter_support",
                "lune.status.kill.finishing_enderman_shelter_roof",
                "lune.status.kill.luring_enderman_into_safety_blocks",
                "lune.status.kill.need_solid_block_enderman_shelter",
                "lune.status.kill.no_support_enderman_shelter",
                "lune.status.kill.placing_enderman_shelter_roof",
                "lune.status.kill.placing_enderman_shelter_support",
                "lune.status.kill.retreating_from",
                "lune.status.kill.two_block_enderman_shelter_ready",
                "lune.status.self_preservation.avoiding_enderman_eye_contact_while",
                "lune.status.self_preservation.behind_cover_from_creeper",
                "lune.status.self_preservation.building_cover_from",
                "lune.status.self_preservation.building_enderman_shelter_roof",
                "lune.status.self_preservation.building_learned_cover_before_retreating",
                "lune.status.self_preservation.building_upward_escape_creeper",
                "lune.status.self_preservation.building_upward_while_evading_creeper",
                "lune.status.self_preservation.built_up_above_creeper",
                "lune.status.self_preservation.built_upward_while_evading_creeper",
                "lune.status.self_preservation.escaping_air_while_evading",
                "lune.status.self_preservation.holding_safe_ground_while_evading",
                "lune.status.self_preservation.holding_shield_against",
                "lune.status.self_preservation.moving_away_from_without_building",
                "lune.status.self_preservation.no_safe_enderman_shelter_or_step",
                "lune.status.self_preservation.no_safe_step_or_pillar_while_evading",
                "lune.status.self_preservation.no_solid_block_enderman_shelter",
                "lune.status.self_preservation.nowhere_to_step_from_fireball",
                "lune.status.self_preservation.retreating_behind_cover",
                "lune.status.self_preservation.retreating_from_creeper",
                "lune.status.self_preservation.sprinting_away_behind_cover_from_creeper",
                "lune.status.self_preservation.sprinting_away_while_building_cover_from",
                "lune.status.self_preservation.sprinting_directly_away_from_creeper",
                "lune.status.self_preservation.stepping_out_fireball_crossfire",
                "lune.status.self_preservation.stepping_out_fireball_line",
                "lune.status.self_preservation.under_two_block_shelter_from_enderman");

        put(HURT,
                "lune.status.self_preservation.health_did_not_recover",
                "lune.status.self_preservation.no_food_staying_safe_until_health");

        put(RECOVERING,
                "lune.status.self_preservation.recovering_health",
                "lune.status.self_preservation.waiting_health_regeneration");

        put(INVENTORY_FULL,
                "lune.status.loot.inventory_full_collected");

        put(MISSING_MATERIALS,
                "lune.status.bridge.no_bridge_blocks_inventory",
                "lune.status.bridge.no_bridge_blocks_selected",
                "lune.status.build_portal.nothing_left_light_portal_with",
                "lune.status.ceiling_break.no_tool_ceiling",
                "lune.status.collect_crafting_table.no_tool_break_table",
                "lune.status.craft.missing_materials",
                "lune.status.dragon_egg.no_torch",
                "lune.status.dragon_egg.nothing_to_dig_with",
                "lune.status.eat.no_food",
                "lune.status.ender_eye.no_eyes_ender",
                "lune.status.fish.no_fishing_rod_main_hand",
                "lune.status.kill.no_boat_available",
                "lune.status.kill.no_materials_requested_weapon",
                "lune.status.route.needs_better_tool",
                "lune.status.self_preservation.no_empty_bucket",
                "lune.status.self_preservation.no_food_defending_from",
                "lune.status.self_preservation.no_food_no_recovery_going_back_work",
                "lune.status.self_preservation.no_water_bucket",
                "lune.status.self_preservation.no_water_bucket_hotbar",
                "lune.status.sleep.bed_no_longer_inventory",
                "lune.status.sleep.wool_colours_do_not_match_no_usable",
                "lune.status.smelt.no_fuel",
                "lune.status.smelt.no_furnace",
                "lune.status.speedrun.no_food_visible_village_houses_checked");

        put(BLOCKED,
                "lune.status.boat.cannot_break_ice",
                "lune.status.boat.recipe_never_unlocked",
                "lune.status.bridge.could_not_place_bridge_block",
                "lune.status.build_portal.could_not_place_frame_block",
                "lune.status.build_portal.could_not_place_frame_block_2",
                "lune.status.build_portal.could_not_reach_frame_block",
                "lune.status.build_portal.could_not_reach_portal",
                "lune.status.collect_crafting_table.table_drop_unreachable",
                "lune.status.complete_game.cannot_reach_center",
                "lune.status.complete_game.cannot_reach_exit_portal",
                "lune.status.complete_game.cannot_reach_landing_site",
                "lune.status.complete_game.cannot_reach_portal",
                "lune.status.complete_game.cannot_reach_portal_frames",
                "lune.status.complete_game.cannot_reach_target",
                "lune.status.crafting_table_access.arrived_beside_blocked_table_trying",
                "lune.status.crafting_table_access.cant_reach_table_reconnecting_dry_ground",
                "lune.status.deposit.cannot_open_container",
                "lune.status.deposit.cannot_reach_container",
                "lune.status.dragon_egg.cannot_reach",
                "lune.status.dragon_egg.could_not_pick_up",
                "lune.status.dragon_egg.moved_too_often",
                "lune.status.dragon_egg.none_in_sight",
                "lune.status.explore.previous_route_blocked",
                "lune.status.flint_from_gravel.cannot_break_gravel_here",
                "lune.status.goto.ceiling_recovery_made_no_progress",
                "lune.status.goto.climb_recovery_made_no_progress",
                "lune.status.goto.could_not_clear_ceiling",
                "lune.status.goto.goal_made_no_progress",
                "lune.status.goto.no_route_after_bridge",
                "lune.status.goto.no_route_after_clearing_ceiling",
                "lune.status.goto.no_route_after_climbing_out",
                "lune.status.goto.no_route_found",
                "lune.status.goto.reached_air_but_no_route_remains",
                "lune.status.goto.stuck_cant_recover",
                "lune.status.goto.stuck_no_walking_route_remains",
                "lune.status.goto.walled_building_way_out",
                "lune.status.goto.walled_clearing",
                "lune.status.grid_craft.something_stuck_cursor_nowhere_put",
                "lune.status.kill.cannot_get_any_closer_one_looking",
                "lune.status.kill.could_not_place_enderman_boat",
                "lune.status.mine.no_reachable_place_mine_block_trying",
                "lune.status.mine.tree_unreachable_because",
                "lune.status.pillar_up.could_not_place_block_underfoot",
                "lune.status.place_block.could_not_place_block",
                "lune.status.quick_stone.could_not_reach_another_stone_start",
                "lune.status.quick_stone.could_not_reach_stable_ground_stone",
                "lune.status.quick_stone.no_stable_ground_nearby_stone_stair",
                "lune.status.route.blocked_by",
                "lune.status.smelt.cant_open_furnace",
                "lune.status.smelt.cant_reach_furnace",
                "lune.status.smelt.could_not_place_furnace",
                "lune.status.smelt.could_not_place_furnace_2",
                "lune.status.speedrun.could_not_enter_visible_village_house",
                "lune.status.speedrun.could_not_find_walkable_surface_nearby",
                "lune.status.speedrun.could_not_reach",
                "lune.status.speedrun.could_not_reach_2",
                "lune.status.speedrun.could_not_reach_open_ground_food_search",
                "lune.status.speedrun.could_not_reach_portal",
                "lune.status.speedrun.could_not_reach_visible_chest",
                "lune.status.speedrun.could_not_reach_visible_shipwreck",
                "lune.status.speedrun.could_not_reach_visible_village_doorway",
                "lune.status.speedrun.portal_frame_blocked",
                "lune.status.staircase_prospect.could_not_enter_new_stair",
                "lune.status.staircase_prospect.could_not_reach_stable_ground",
                "lune.status.staircase_prospect.no_stable_ground",
                "lune.status.staircase_prospect.stairs_blocked_by",
                "lune.status.surface_recovery.no_nearby_walkable_surface");

        put(SEARCH,
                "lune.status.crafting_table_access.cant_place_trying_another_spot",
                "lune.status.crafting_table_access.cant_place_trying_another_spot_2",
                "lune.status.crafting_table_access.cant_reach_table_trying_another",
                "lune.status.dragon_egg.fell_further",
                "lune.status.dragon_egg.jumped",
                "lune.status.dragon_egg.looking_around",
                "lune.status.explore.at",
                "lune.status.explore.blocked_ways_out_here_needs_different",
                "lune.status.explore.exploring",
                "lune.status.explore.heading",
                "lune.status.explore.heading_because",
                "lune.status.explore.no_targets_selected",
                "lune.status.explore.nothing_ahead_taking_proper_look_around",
                "lune.status.explore.searched_stops_found_nothing",
                "lune.status.explore.stopping_look_around",
                "lune.status.explore.turning_toward_behind_current_view",
                "lune.status.explore.way_water_choosing_another_walkable",
                "lune.status.harvest.crop_unreachable_checking_next_farm",
                "lune.status.kill.target_unreachable_looking_another",
                "lune.status.mine.could_not_enter_opening_checking_from",
                "lune.status.mine.target_unreachable_trying_next_one",
                "lune.status.speedrun.arrived_beside_blocked_chest_checking");

        put(LOADING,
                "lune.status.craft.waiting_recipe",
                "lune.status.eat.waiting_server_see_food",
                "lune.status.ender_eye.waiting_eye",
                "lune.status.find_map.holding_map_waiting_fill",
                "lune.status.grid_craft.waiting_result");

        put(WAITING,
                "lune.status.collect_crafting_table.waiting_table_drop",
                "lune.status.countdown.remaining",
                "lune.status.fish.waiting_bite_caught",
                "lune.status.fish.waiting_bobber",
                "lune.status.fish.waiting_bobber_reach_water_caught",
                "lune.status.fish.waiting_reel",
                "lune.status.kill.waiting_drop",
                "lune.status.kill.waiting_top_jump",
                "lune.status.pillar_up.waiting_rise_onto_placed_pillar_block",
                "lune.status.self_preservation.waiting_boat_appear",
                "lune.status.self_preservation.waiting_settle",
                "lune.status.sleep.bed_ready_waiting_nightfall",
                "lune.status.sleep.waiting_bed_accept",
                "lune.status.speedrun.waiting_water_bucket",
                "lune.status.stay_near.watching",
                "lune.status.stop_game.pausing",
                "lune.status.task_runner.counter_waiting_pulses",
                "lune.status.task_runner.forwarded_pulse_waiting_next_one",
                "lune.status.task_runner.pausing_2",
                "lune.status.task_runner.timer_forwarded_pulse_waiting_next_one",
                "lune.status.task_runner.timer_waiting_s",
                "lune.status.task_runner.waiting_next_signal",
                "lune.status.task_runner.waiting_s_before_forwarding");

        put(FRAMING,
                "lune.status.build_portal.building_portal_frame",
                "lune.status.build_portal.choose_dirt_or_cobblestone_portal",
                "lune.status.build_portal.moving_place_frame_block",
                "lune.status.build_portal.nether_portal_built_lit",
                "lune.status.build_portal.no_dirt_or_cobblestone_left_portal",
                "lune.status.build_portal.placed_frame_blocks",
                "lune.status.build_portal.placing_frame_block",
                "lune.status.build_portal.ran_out_obsidian_frame_block",
                "lune.status.build_portal.walking_portal");

        put(STAIRS,
                "lune.status.staircase_prospect.cave_with_hostiles_sealing_retreating",
                "lune.status.staircase_prospect.could_not_seal_cave_leaving_anyway",
                "lune.status.staircase_prospect.descended_steps",
                "lune.status.staircase_prospect.digging_stair",
                "lune.status.staircase_prospect.mining_stair_target",
                "lune.status.staircase_prospect.moving_stable_ground_before_prospecting",
                "lune.status.staircase_prospect.moving_stable_ground_before_prospecting_2",
                "lune.status.staircase_prospect.opened_cave_scanning_before_entering",
                "lune.status.staircase_prospect.pulled_away_from_stairwell",
                "lune.status.staircase_prospect.refusing_destroy_existing_staircase",
                "lune.status.staircase_prospect.retreated_from_cave",
                "lune.status.staircase_prospect.sealed_cave_leaving_by_stair",
                "lune.status.staircase_prospect.sealing_cave_entrance",
                "lune.status.staircase_prospect.stair_block_stalled_abandoning_step",
                "lune.status.staircase_prospect.stair_target",
                "lune.status.staircase_prospect.stair_target_stalled_abandoning_step",
                "lune.status.staircase_prospect.staircase_block",
                "lune.status.staircase_prospect.stairs_reached_unsupported_opening",
                "lune.status.staircase_prospect.stairs_would_open_falling",
                "lune.status.staircase_prospect.starting_prospecting_stair_from_stable",
                "lune.status.staircase_prospect.walking_down_stair",
                "lune.status.staircase_prospect.water_ahead_sealing_retreating");

        put(PILLARING,
                "lune.status.pillar_up.building_way_out",
                "lune.status.pillar_up.cannot_place_pillar_block",
                "lune.status.pillar_up.climbed_blocks",
                "lune.status.pillar_up.climbed_blocks_3",
                "lune.status.pillar_up.climbed_blocks_ceiling_above",
                "lune.status.pillar_up.climbed_blocks_out_blocks",
                "lune.status.pillar_up.climbed_blocks_placement_timed_out",
                "lune.status.pillar_up.jumping_make_room_next_pillar_block",
                "lune.status.pillar_up.no_room_above_climb",
                "lune.status.pillar_up.nothing_build_with");

        put(BRIDGING,
                "lune.status.bridge.bridged_blocks",
                "lune.status.bridge.couldnt_step_onto_bridge",
                "lune.status.bridge.crossing_n_of_n",
                "lune.status.bridge.nowhere_place_next_block",
                "lune.status.bridge.placed_block",
                "lune.status.bridge.placing_block");

        put(TRAVEL,
                "lune.status.goto.blocks_left",
                "lune.status.goto.break",
                "lune.status.goto.bridge_failed",
                "lune.status.goto.bridging",
                "lune.status.goto.could_not_climb_out",
                "lune.status.goto.couldnt_reach_breathable_air",
                "lune.status.goto.fluid_changed_no_safe_route_remains",
                "lune.status.goto.looking_route",
                "lune.status.goto.moving_but_never_getting_closer",
                "lune.status.goto.replanning_after_clearing_ceiling",
                "lune.status.goto.replanning_after_movement_stalled",
                "lune.status.goto.replanning_after_reaching_air",
                "lune.status.goto.swimming_breathable_air",
                "lune.status.route.fluid_replanning",
                "lune.status.route.refusing_open_water");
    }
}
