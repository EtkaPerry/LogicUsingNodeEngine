package com.etka.lune.task;

import java.util.List;
import java.util.Map;

/**
 * The six jobs seeded when {@code config/lune-tasks.json} does not exist yet.
 *
 * <p>They are useful jobs first and teaching examples second. The set grows from a short linear
 * cleanup through branching, bounded recovery, shared data, and a complete-game mission with
 * event-driven support circuits, then ends on a long job that adds no new vocabulary at all and
 * instead shows how far the plainest cards carry. Every card, parameter, and wire is available in
 * the Blueprint editor; the defaults have no private runtime shortcuts.</p>
 */
public final class DefaultTasks {

    /** Cards are 124px wide; the seeded lanes leave room for cables and deliberate rightward flow. */
    private static final int TEACHING_STEP_X = 208;
    private static final int TEACHING_STEP_Y = 132;

    /** The four the bot is most likely to meet in its own yard after dark. */
    private static final String NIGHT_MOBS =
            "minecraft:zombie,minecraft:skeleton,minecraft:creeper,minecraft:spider";

    private DefaultTasks() {}

    /** Fresh graphs, so editing a seeded job never mutates a later restore. */
    public static List<TaskGraph> create() {
        List<TaskGraph> tasks = List.of(woodlandCleanup(), regenerativeFarmShift(),
                ironworksSupplyRun(), deepcoreDiamondExpedition(), dragonfallMissionControl(),
                aDayInTheLife());
        tasks.forEach(task -> {
            TaskWiring.addExplicitStart(task);
            teachingLayout(task);
        });
        return tasks;
    }

    // 1. A short, honest linear job.

    private static TaskGraph woodlandCleanup() {
        TaskGraph task = new TaskGraph("Woodland Cleanup");

        TaskNode chop = node("chop", "chop", Map.of(
                "radius", "64",
                "limit", "24"));
        chop.onSuccess = "loot";
        task.nodes.add(chop);

        TaskNode loot = node("loot", "loot", Map.of("radius", "16"));
        loot.onSuccess = "cleanup_end";
        loot.onFailure = "cleanup_end";
        task.nodes.add(loot);

        task.nodes.add(node("cleanup_end", TaskNode.END_COMMAND, Map.of()));
        return task;
    }

    // 2. A branch that remains useful even when the field is not ready.

    private static TaskGraph regenerativeFarmShift() {
        TaskGraph task = new TaskGraph("Regenerative Farm Shift");

        TaskNode harvest = node("harvest", "harvest", Map.of(
                "targets", "minecraft:wheat,minecraft:carrots,minecraft:potatoes,minecraft:beetroots",
                "radius", "32",
                "limit", "32",
                "collect", "true",
                "replant", "true"));
        harvest.onSuccess = "stash";
        harvest.onFailure = "hunger";
        task.nodes.add(harvest);

        TaskNode stash = node("stash", "deposit", Map.of(
                "filter", "Crops",
                "radius", "16",
                "optional", "true"));
        stash.onSuccess = "hunger";
        stash.onFailure = "hunger";
        task.nodes.add(stash);

        TaskNode hunger = node("hunger", "check_player", Map.of(
                "metric", "Hunger",
                "comparison", "At most",
                "threshold", "14"));
        hunger.onSuccess = "eat";
        hunger.onFailure = "farm_end";
        task.nodes.add(hunger);

        TaskNode eat = node("eat", "eat", Map.of("minimum_food", "18"));
        eat.onSuccess = "farm_end";
        eat.onFailure = "farm_end";
        task.nodes.add(eat);

        task.nodes.add(node("farm_end", TaskNode.END_COMMAND, Map.of()));
        return task;
    }

    // 3. A quota loop with a bounded search detour.

    private static TaskGraph ironworksSupplyRun() {
        TaskGraph task = new TaskGraph("Ironworks Supply Run");

        TaskNode stock = node("stock", "check_item", Map.of(
                "item", "minecraft:raw_iron",
                "comparison", "At least",
                "count", "16"));
        stock.onSuccess = "smelt";
        stock.onFailure = "tool";
        task.nodes.add(stock);

        TaskNode tool = node("tool", "gettool", Map.of(
                "tool", "Pickaxe",
                "material", "Stone",
                "check_around", "true"));
        tool.onSuccess = "mine";
        task.nodes.add(tool);

        TaskNode mine = node("mine", "mine", Map.of(
                "targets", "minecraft:iron_ore,minecraft:deepslate_iron_ore",
                "radius", "64",
                "y_min", "-64",
                "y_max", "72",
                "limit", "16",
                "auto_tool", "true",
                "prospect", "true",
                "check_around", "true"));
        mine.onSuccess = "quota";
        mine.onFailure = "roam";
        task.nodes.add(mine);

        TaskNode roam = node("roam", "explore", Map.of(
                "targets", "minecraft:iron_ore,minecraft:deepslate_iron_ore",
                "radius", "48",
                "attempts", "6",
                "step", "12",
                "check_around", "true",
                "scan_style", "Glance ahead",
                "smart_direction", "true"));
        roam.onSuccess = "mine";
        task.nodes.add(roam);

        TaskNode quota = node("quota", "check_item", Map.of(
                "item", "minecraft:raw_iron",
                "comparison", "At least",
                "count", "16"));
        quota.onSuccess = "smelt";
        quota.onFailure = "mine";
        task.nodes.add(quota);

        TaskNode smelt = node("smelt", "smelt", Map.of(
                "input", "Raw Iron",
                "count", "16"));
        smelt.onSuccess = "stash";
        task.nodes.add(smelt);

        TaskNode stash = node("stash", "deposit", Map.of(
                "filter", "Ores",
                "radius", "16",
                "optional", "true"));
        stash.onSuccess = "ironworks_end";
        stash.onFailure = "ironworks_end";
        task.nodes.add(stash);

        task.nodes.add(node("ironworks_end", TaskNode.END_COMMAND, Map.of()));
        return task;
    }

    // 4. A bounded deep expedition whose ore choice travels through data wires.

    private static TaskGraph deepcoreDiamondExpedition() {
        TaskGraph task = new TaskGraph("Deepcore Diamond Expedition");

        TaskNode hunger = node("hunger", "check_player", Map.of(
                "metric", "Hunger",
                "comparison", "At most",
                "threshold", "10"));
        hunger.onSuccess = "eat";
        hunger.onFailure = "select_pick";
        task.nodes.add(hunger);

        TaskNode eat = node("eat", "eat", Map.of("minimum_food", "18"));
        eat.onSuccess = "select_pick";
        eat.onFailure = "select_pick";
        task.nodes.add(eat);

        TaskNode selectPick = node("select_pick", "select_item", Map.of(
                "item", "minecraft:diamond_pickaxe",
                "enchanting", "Enchanted only",
                "hand", "Main hand",
                "min_durability", "20",
                "prefer", "Most durability"));
        selectPick.onSuccess = "scout";
        selectPick.onFailure = "forge_pick";
        task.nodes.add(selectPick);

        TaskNode forgePick = node("forge_pick", "gettool", Map.of(
                "tool", "Pickaxe",
                "material", "Iron",
                "check_around", "true"));
        forgePick.onSuccess = "scout";
        task.nodes.add(forgePick);

        TaskNode scout = node("scout", "find", Map.of(
                "target_kind", "Blocks",
                "targets", diamondTargets(),
                "radius", "48",
                "y_min", "-64",
                "y_max", "16",
                "prospect", "false",
                "check_around", "true"));
        scout.exposedOutputs.add("targets");
        scout.onSuccess = "mine_first";
        scout.onFailure = "first_strip";
        task.nodes.add(scout);

        TaskNode firstStrip = stripmine("first_strip", 4, 24);
        firstStrip.onSuccess = "mine_first";
        firstStrip.onFailure = "cashout";
        shareData(scout, "targets", firstStrip, "target");
        task.nodes.add(firstStrip);

        TaskNode mineFirst = diamondMine("mine_first");
        mineFirst.onSuccess = "loot_first";
        mineFirst.onFailure = "second_strip";
        shareData(scout, "targets", mineFirst, "targets");
        task.nodes.add(mineFirst);

        TaskNode lootFirst = node("loot_first", "loot", Map.of("radius", "16"));
        lootFirst.onSuccess = "quota";
        lootFirst.onFailure = "quota";
        task.nodes.add(lootFirst);

        TaskNode quota = node("quota", "check_item", Map.of(
                "item", "minecraft:diamond",
                "comparison", "At least",
                "count", "8"));
        quota.onSuccess = "cashout";
        quota.onFailure = "second_strip";
        task.nodes.add(quota);

        TaskNode secondStrip = stripmine("second_strip", 8, 32);
        secondStrip.onSuccess = "mine_final";
        secondStrip.onFailure = "cashout";
        shareData(scout, "targets", secondStrip, "target");
        task.nodes.add(secondStrip);

        TaskNode mineFinal = diamondMine("mine_final");
        mineFinal.onSuccess = "loot_final";
        mineFinal.onFailure = "cashout";
        shareData(scout, "targets", mineFinal, "targets");
        task.nodes.add(mineFinal);

        TaskNode lootFinal = node("loot_final", "loot", Map.of("radius", "16"));
        lootFinal.onSuccess = "cashout";
        lootFinal.onFailure = "cashout";
        task.nodes.add(lootFinal);

        TaskNode cashout = node("cashout", "deposit", Map.of(
                "filter", "Ores",
                "radius", "16",
                "optional", "true"));
        cashout.onSuccess = "expedition_end";
        cashout.onFailure = "expedition_end";
        task.nodes.add(cashout);

        task.nodes.add(node("expedition_end", TaskNode.END_COMMAND, Map.of()));

        TaskNode guard = node("guard", "self_preservation", safetyParams());
        guard.repeat = 0;
        protectWith(guard, scout, firstStrip, mineFirst, secondStrip, mineFinal);
        task.nodes.add(guard);
        return task;
    }

    // 5. Full-game autopilot with field support, an emergency control, and a powered debrief.

    private static TaskGraph dragonfallMissionControl() {
        TaskGraph task = new TaskGraph("Dragonfall Mission Control");

        TaskNode start = node("start", TaskNode.START_COMMAND, Map.of());
        start.onSuccess = "preflight";
        task.nodes.add(start);

        TaskNode preflight = node("preflight", "check_player", Map.of(
                "metric", "Hunger",
                "comparison", "At most",
                "threshold", "10"));
        preflight.onSuccess = "preflight_eat";
        preflight.onFailure = "launch";
        task.nodes.add(preflight);

        TaskNode preflightEat = node("preflight_eat", "eat", Map.of("minimum_food", "18"));
        preflightEat.onSuccess = "launch";
        preflightEat.onFailure = "launch";
        task.nodes.add(preflightEat);

        TaskNode launch = node("launch", "completegame", Map.of());
        // Completing the dragon fight is a milestone, not the end of the mission. Give the
        // primary lane one concrete post-fight hand-off before the pulse debrief is released.
        launch.onSuccess = "mission_loot";
        launch.onFailure = "mission_loot";
        task.nodes.add(launch);

        TaskNode missionLoot = node("mission_loot", "loot", Map.of("radius", "32"));
        missionLoot.onSuccess = "mission_end";
        missionLoot.onFailure = "mission_end";
        task.nodes.add(missionLoot);

        task.nodes.add(node("mission_end", TaskNode.END_COMMAND, Map.of()));

        TaskNode guard = node("guard", "self_preservation", safetyParams());
        guard.repeat = 0;
        protectWith(guard, launch, missionLoot);
        task.nodes.add(guard);

        TaskNode fieldPulse = node("field_pulse", TaskNode.PULSE_COMMAND, Map.of());
        fieldPulse.alwaysIntervalSeconds = 45;
        fieldPulse.alwaysTargets.add("field_hunger");
        task.nodes.add(fieldPulse);

        TaskNode fieldHunger = node("field_hunger", "check_player", Map.of(
                "metric", "Hunger",
                "comparison", "At most",
                "threshold", "8"));
        fieldHunger.onSuccess = "field_eat";
        fieldHunger.onFailure = "field_end";
        task.nodes.add(fieldHunger);

        TaskNode fieldEat = node("field_eat", "eat", Map.of("minimum_food", "16"));
        fieldEat.onSuccess = "field_end";
        fieldEat.onFailure = "field_end";
        task.nodes.add(fieldEat);

        task.nodes.add(node("field_end", TaskNode.END_COMMAND, Map.of()));

        // End lights once and then goes dark. Counting both edges delays the debrief until the
        // primary mission circuit has genuinely finished rather than merely reached its last card.
        TaskNode missionWatch = node("mission_watch", TaskNode.OBSERVER_COMMAND, Map.of());
        missionWatch.observedNodeId = "mission_end";
        missionWatch.signalLinks.add(pulseLink(0, "closeout_counter"));
        task.nodes.add(missionWatch);

        TaskNode closeoutCounter = node("closeout_counter", TaskNode.COUNTER_COMMAND,
                Map.of("count", "2"));
        closeoutCounter.signalLinks.add(relayLink(0, "closeout_hub", 0));
        task.nodes.add(closeoutCounter);

        TaskNode closeoutHub = relay("closeout_hub", 1, 2);
        closeoutHub.signalLinks.add(pulseLink(0, "debrief_loot"));
        closeoutHub.signalLinks.add(pulseLink(1, "debrief_delay"));
        task.nodes.add(closeoutHub);

        TaskNode debriefLoot = node("debrief_loot", "loot", Map.of("radius", "32"));
        debriefLoot.onSuccess = "debrief_loot_end";
        debriefLoot.onFailure = "debrief_loot_end";
        task.nodes.add(debriefLoot);
        task.nodes.add(node("debrief_loot_end", TaskNode.END_COMMAND, Map.of()));

        TaskNode debriefDelay = node("debrief_delay", TaskNode.TIMER_COMMAND,
                Map.of("seconds", "5"));
        debriefDelay.signalLinks.add(pulseLink(0, "debrief_pause"));
        task.nodes.add(debriefDelay);

        TaskNode debriefPause = node("debrief_pause", "pause_game", Map.of());
        debriefPause.onSuccess = "debrief_pause_end";
        debriefPause.onFailure = "debrief_pause_end";
        task.nodes.add(debriefPause);
        task.nodes.add(node("debrief_pause_end", TaskNode.END_COMMAND, Map.of()));

        TaskNode emergencyButton = node("emergency_button", TaskNode.BUTTON_COMMAND, Map.of());
        emergencyButton.signalLinks.add(relayLink(0, "emergency_hub", 0));
        task.nodes.add(emergencyButton);

        TaskNode emergencyHub = relay("emergency_hub", 1, 2);
        emergencyHub.signalLinks.add(pulseLink(0, "emergency_eat"));
        emergencyHub.signalLinks.add(pulseLink(1, "emergency_delay"));
        task.nodes.add(emergencyHub);

        TaskNode emergencyEat = node("emergency_eat", "eat", Map.of("minimum_food", "20"));
        emergencyEat.onSuccess = "emergency_eat_end";
        emergencyEat.onFailure = "emergency_eat_end";
        task.nodes.add(emergencyEat);
        task.nodes.add(node("emergency_eat_end", TaskNode.END_COMMAND, Map.of()));

        TaskNode emergencyDelay = node("emergency_delay", TaskNode.TIMER_COMMAND,
                Map.of("seconds", "1"));
        emergencyDelay.signalLinks.add(pulseLink(0, "emergency_pause"));
        task.nodes.add(emergencyDelay);

        TaskNode emergencyPause = node("emergency_pause", "pause_game", Map.of());
        emergencyPause.onSuccess = "emergency_pause_end";
        emergencyPause.onFailure = "emergency_pause_end";
        task.nodes.add(emergencyPause);
        task.nodes.add(node("emergency_pause_end", TaskNode.END_COMMAND, Map.of()));

        return task;
    }

    // 6. The long one. Five phases of a single day, wired only with Success and Fail.

    /**
     * A full day's work as one forward-running chain.
     *
     * <p>Nothing here is new: every card already appears in one of the five jobs above, and there
     * is not a Pulse, Counter, Relay or data wire in it. What it teaches is scale. Each phase hands
     * the day to the next one and never asks for it back, so a branch is a shortcut around work
     * that is already done rather than a loop that has to be escaped. Exactly one wire runs
     * backwards - the search that returns to Chop Wood - and it is the only place the bot can
     * usefully retry rather than move on.</p>
     */
    private static TaskGraph aDayInTheLife() {
        TaskGraph task = new TaskGraph("A Day in the Life");

        // Dawn. Breakfast only if the night left you short, then work the field.
        TaskNode dawnHunger = node("dawn_hunger", "check_player", Map.of(
                "metric", "Hunger",
                "comparison", "At most",
                "threshold", "16"));
        dawnHunger.onSuccess = "dawn_eat";
        dawnHunger.onFailure = "dawn_harvest";
        task.nodes.add(dawnHunger);

        TaskNode dawnEat = node("dawn_eat", "eat", Map.of("minimum_food", "18"));
        dawnEat.onSuccess = "dawn_harvest";
        dawnEat.onFailure = "dawn_harvest";
        task.nodes.add(dawnEat);

        TaskNode dawnHarvest = node("dawn_harvest", "harvest", Map.of(
                "targets", "minecraft:wheat,minecraft:carrots,minecraft:potatoes,minecraft:beetroots",
                "radius", "32",
                "limit", "64",
                "collect", "true",
                "replant", "true"));
        dawnHarvest.onSuccess = "dawn_loot";
        dawnHarvest.onFailure = "dawn_loot";
        task.nodes.add(dawnHarvest);

        task.nodes.add(sweep("dawn_loot", "morning_axe"));

        // Morning. Wood, then stone, then coal - each gate skips the leg that provides it.
        TaskNode morningAxe = node("morning_axe", "check_item", Map.of(
                "item", "minecraft:stone_axe",
                "comparison", "At least",
                "count", "1"));
        morningAxe.onSuccess = "morning_chop";
        morningAxe.onFailure = "morning_forge_axe";
        task.nodes.add(morningAxe);

        // Unlike Ironworks, a provisioning card that comes up empty must not end the run: a day
        // with no axe is still a day. Every Fail here rejoins the chain further along.
        TaskNode morningForgeAxe = node("morning_forge_axe", "gettool", Map.of(
                "tool", "Axe",
                "material", "Stone",
                "check_around", "true"));
        morningForgeAxe.onSuccess = "morning_chop";
        morningForgeAxe.onFailure = "morning_chop";
        task.nodes.add(morningForgeAxe);

        TaskNode morningChop = node("morning_chop", "chop", Map.of(
                "radius", "64",
                "limit", "32"));
        morningChop.onSuccess = "morning_wood_loot";
        morningChop.onFailure = "morning_roam";
        task.nodes.add(morningChop);

        // The day's only backward wire. A treeless clearing is worth walking out of and retrying;
        // everything else in this job is better answered by moving on to the next phase.
        TaskNode morningRoam = node("morning_roam", "explore", Map.of(
                "targets", "minecraft:oak_log,minecraft:birch_log,minecraft:spruce_log",
                "radius", "48",
                "attempts", "5",
                "step", "12",
                "check_around", "true",
                "scan_style", "Glance ahead",
                "smart_direction", "true"));
        morningRoam.onSuccess = "morning_chop";
        morningRoam.onFailure = "morning_wood_loot";
        task.nodes.add(morningRoam);

        task.nodes.add(sweep("morning_wood_loot", "morning_pick"));

        TaskNode morningPick = node("morning_pick", "check_item", Map.of(
                "item", "minecraft:stone_pickaxe",
                "comparison", "At least",
                "count", "1"));
        morningPick.onSuccess = "morning_stone";
        morningPick.onFailure = "morning_forge_pick";
        task.nodes.add(morningPick);

        TaskNode morningForgePick = node("morning_forge_pick", "gettool", Map.of(
                "tool", "Pickaxe",
                "material", "Stone",
                "check_around", "true"));
        morningForgePick.onSuccess = "morning_stone";
        morningForgePick.onFailure = "morning_stone";
        task.nodes.add(morningForgePick);

        TaskNode morningStone = node("morning_stone", "mine", Map.of(
                "targets", "minecraft:stone",
                "radius", "32",
                "y_min", "-64",
                "y_max", "320",
                "limit", "64",
                "auto_tool", "true",
                "prospect", "false",
                "check_around", "true"));
        morningStone.onSuccess = "morning_stone_loot";
        morningStone.onFailure = "morning_coal";
        task.nodes.add(morningStone);

        task.nodes.add(sweep("morning_stone_loot", "morning_coal"));

        TaskNode morningCoal = node("morning_coal", "mine", Map.of(
                "targets", "minecraft:coal_ore,minecraft:deepslate_coal_ore",
                "radius", "48",
                "y_min", "-64",
                "y_max", "96",
                "limit", "16",
                "auto_tool", "true",
                "prospect", "true",
                "check_around", "true"));
        morningCoal.onSuccess = "morning_coal_loot";
        morningCoal.onFailure = "midday_iron";
        task.nodes.add(morningCoal);

        task.nodes.add(sweep("morning_coal_loot", "midday_iron"));

        // Midday. Iron and a furnace, a look at what the morning wore out, then lunch.
        TaskNode middayIron = node("midday_iron", "mine", Map.of(
                "targets", "minecraft:iron_ore,minecraft:deepslate_iron_ore",
                "radius", "48",
                "y_min", "-64",
                "y_max", "72",
                "limit", "16",
                "auto_tool", "true",
                "prospect", "true",
                "check_around", "true"));
        middayIron.onSuccess = "midday_iron_loot";
        middayIron.onFailure = "midday_tool_check";
        task.nodes.add(middayIron);

        task.nodes.add(sweep("midday_iron_loot", "midday_smelt"));

        TaskNode middaySmelt = node("midday_smelt", "smelt", Map.of(
                "input", "Raw Iron",
                "count", "16"));
        middaySmelt.onSuccess = "midday_tool_check";
        middaySmelt.onFailure = "midday_tool_check";
        task.nodes.add(middaySmelt);

        // Select from Inventory as a condition: it fails when every pickaxe is nearly spent, which
        // is the cue to spend the morning's iron on a better one.
        TaskNode middayToolCheck = node("midday_tool_check", "select_item", Map.of(
                "item", "minecraft:stone_pickaxe",
                "enchanting", "Any",
                "hand", "Main hand",
                "min_durability", "25",
                "prefer", "Most durability"));
        middayToolCheck.onSuccess = "midday_food_check";
        middayToolCheck.onFailure = "midday_upgrade";
        task.nodes.add(middayToolCheck);

        TaskNode middayUpgrade = node("midday_upgrade", "gettool", Map.of(
                "tool", "Pickaxe",
                "material", "Iron",
                "check_around", "true"));
        middayUpgrade.onSuccess = "midday_food_check";
        middayUpgrade.onFailure = "midday_food_check";
        task.nodes.add(middayUpgrade);

        TaskNode middayFoodCheck = node("midday_food_check", "check_player", Map.of(
                "metric", "Hunger",
                "comparison", "At most",
                "threshold", "14"));
        middayFoodCheck.onSuccess = "midday_fish";
        middayFoodCheck.onFailure = "dusk_crops";
        task.nodes.add(middayFoodCheck);

        TaskNode middayFish = node("midday_fish", "fish", Map.of("auto_recast", "true"));
        middayFish.onSuccess = "midday_eat";
        middayFish.onFailure = "midday_hunt";
        task.nodes.add(middayFish);

        TaskNode middayHunt = node("midday_hunt", "kill", Map.of(
                "targets", "minecraft:cow,minecraft:pig,minecraft:chicken",
                "radius", "24",
                "fire_resistance", "false",
                "use_shield", "false",
                "craft_shield", "false",
                "weapon", "Sword",
                "craft_weapon", "true",
                "enderman_safety", "Auto"));
        middayHunt.onSuccess = "midday_hunt_loot";
        middayHunt.onFailure = "midday_eat";
        task.nodes.add(middayHunt);

        task.nodes.add(sweep("midday_hunt_loot", "midday_eat"));

        TaskNode middayEat = node("midday_eat", "eat", Map.of("minimum_food", "18"));
        middayEat.onSuccess = "dusk_crops";
        middayEat.onFailure = "dusk_crops";
        task.nodes.add(middayEat);

        // Dusk. Empty the pack one filter at a time, then make sure there is a bed for later.
        task.nodes.add(store("dusk_crops", "Crops", "dusk_ores"));
        task.nodes.add(store("dusk_ores", "Ores", "dusk_logs"));
        task.nodes.add(store("dusk_logs", "Logs", "dusk_stone"));
        task.nodes.add(store("dusk_stone", "Stone", "dusk_bed"));

        TaskNode duskBed = node("dusk_bed", "check_item", Map.of(
                "item", "minecraft:white_bed",
                "comparison", "At least",
                "count", "1"));
        duskBed.onSuccess = "night_scan";
        duskBed.onFailure = "dusk_wool";
        task.nodes.add(duskBed);

        TaskNode duskWool = node("dusk_wool", "huntsheep", Map.of(
                "count", "3",
                "weapon", "Sword",
                "craft_weapon", "true"));
        duskWool.onSuccess = "night_scan";
        duskWool.onFailure = "night_scan";
        task.nodes.add(duskWool);

        // Night. Clear the yard if anything is in it, then sleep and bank the day.
        TaskNode nightScan = node("night_scan", "find", Map.of(
                "target_kind", "Mobs",
                "entities", NIGHT_MOBS,
                "radius", "24",
                "check_around", "true"));
        nightScan.onSuccess = "night_sword";
        nightScan.onFailure = "night_sleep";
        task.nodes.add(nightScan);

        TaskNode nightSword = node("night_sword", "check_item", Map.of(
                "item", "minecraft:stone_sword",
                "comparison", "At least",
                "count", "1"));
        nightSword.onSuccess = "night_fight";
        nightSword.onFailure = "night_forge_sword";
        task.nodes.add(nightSword);

        TaskNode nightForgeSword = node("night_forge_sword", "gettool", Map.of(
                "tool", "Sword",
                "material", "Stone",
                "check_around", "true"));
        nightForgeSword.onSuccess = "night_fight";
        // No sword and no way to make one is a reason to go to bed, not to punch a creeper.
        nightForgeSword.onFailure = "night_sleep";
        task.nodes.add(nightForgeSword);

        TaskNode nightFight = node("night_fight", "kill", Map.of(
                "targets", NIGHT_MOBS,
                "radius", "16",
                "fire_resistance", "false",
                "use_shield", "true",
                "craft_shield", "true",
                "weapon", "Automatic",
                "craft_weapon", "true",
                "enderman_safety", "Auto"));
        nightFight.onSuccess = "night_fight_loot";
        nightFight.onFailure = "night_sleep";
        task.nodes.add(nightFight);

        task.nodes.add(sweep("night_fight_loot", "night_sleep"));

        TaskNode nightSleep = node("night_sleep", "sleep", Map.of(
                "wait_for_night", "true",
                "reclaim", "true",
                "radius", "32"));
        nightSleep.onSuccess = "night_stash";
        nightSleep.onFailure = "night_stash";
        task.nodes.add(nightSleep);

        task.nodes.add(store("night_stash", "All", "day_end"));

        task.nodes.add(node("day_end", TaskNode.END_COMMAND, Map.of()));

        TaskNode guard = node("guard", "self_preservation", safetyParams());
        guard.repeat = 0;
        protectWith(guard, morningCoal, middayIron, middayHunt, nightFight);
        task.nodes.add(guard);
        return task;
    }

    /** A pickup pass that hands the day forward whether or not it found anything. */
    private static TaskNode sweep(String id, String next) {
        TaskNode loot = node(id, "loot", Map.of("radius", "16"));
        loot.onSuccess = next;
        loot.onFailure = next;
        return loot;
    }

    /** An optional drop-off: a base with no container yet should not end the day early. */
    private static TaskNode store(String id, String filter, String next) {
        TaskNode deposit = node(id, "deposit", Map.of(
                "filter", filter,
                "radius", "16",
                "optional", "true"));
        deposit.onSuccess = next;
        deposit.onFailure = next;
        return deposit;
    }

    private static TaskNode diamondMine(String id) {
        return node(id, "mine", Map.of(
                "targets", diamondTargets(),
                "radius", "48",
                "y_min", "-64",
                "y_max", "16",
                "limit", "8",
                "auto_tool", "true",
                "prospect", "false",
                "check_around", "true"));
    }

    private static TaskNode stripmine(String id, int branches, int length) {
        return node(id, "stripmine", Map.of(
                "target", diamondTargets(),
                "y_level", "-59",
                "branch_length", Integer.toString(length),
                "spacing", "3",
                "branches", Integer.toString(branches)));
    }

    private static String diamondTargets() {
        return "minecraft:diamond_ore,minecraft:deepslate_diamond_ore";
    }

    private static Map<String, String> safetyParams() {
        return Map.ofEntries(
                Map.entry("protect_air", "true"),
                Map.entry("air_compare", "At most"),
                Map.entry("air_value", "120"),
                Map.entry("protect_lava", "true"),
                Map.entry("protect_fall", "true"),
                Map.entry("fall_threshold", "10"),
                Map.entry("protect_monsters", "true"),
                Map.entry("monster_compare", "At most"),
                Map.entry("monster_distance", "8"),
                Map.entry("protect_health", "true"),
                Map.entry("health_compare", "At most"),
                Map.entry("health_value", "8"));
    }

    private static void protectWith(TaskNode guard, TaskNode... guarded) {
        for (TaskNode node : guarded) {
            node.onWhile = guard.id;
            // While is execution-significant even though its output is optional in the editor.
            // Seeded jobs should show the protection cable instead of an orphaned guard card.
            node.whileVisible = true;
        }
    }

    private static void shareData(TaskNode source, String sourcePort,
                                  TaskNode target, String targetPort) {
        source.exposedOutputs.add(sourcePort);
        target.exposedInputs.add(targetPort);
        target.inputLinks.put(targetPort, new TaskDataLink(source.id, sourcePort));
    }

    private static TaskNode relay(String id, int inputs, int outputs) {
        TaskNode relay = node(id, TaskNode.SIGNAL_RELAY_COMMAND, Map.of());
        relay.signalInputCount = inputs;
        relay.signalOutputCount = outputs;
        return relay;
    }

    private static TaskSignalLink pulseLink(int outputPort, String targetId) {
        return new TaskSignalLink(outputPort, targetId, -1);
    }

    private static TaskSignalLink relayLink(int outputPort, String targetId, int targetPort) {
        return new TaskSignalLink(outputPort, targetId, targetPort);
    }

    /** Positions the seeded jobs as diagrams, with secondary circuits on their own rows. */
    private static void teachingLayout(TaskGraph task) {
        switch (task.name) {
            case "Ironworks Supply Run" -> {
                row(task, 0, "start", "stock", "tool", "mine", "quota", "smelt", "stash",
                        "ironworks_end");
                place(task, "roam", 3, 2);
            }
            case "A Day in the Life" -> {
                // One band per phase, read left to right, with each phase's shortcuts parked on
                // the row beneath the gate that chooses them. The bands are what make a
                // forty-card job legible: the day runs down the canvas as well as across it.
                row(task, 0, "start", "dawn_hunger", "dawn_harvest", "dawn_loot");
                place(task, "dawn_eat", 1, 1);

                row(task, 3, "morning_axe", "morning_chop", "morning_wood_loot", "morning_pick",
                        "morning_stone", "morning_stone_loot", "morning_coal",
                        "morning_coal_loot");
                place(task, "morning_forge_axe", 0, 4);
                place(task, "morning_roam", 1, 4);
                place(task, "morning_forge_pick", 3, 4);

                row(task, 6, "midday_iron", "midday_iron_loot", "midday_smelt",
                        "midday_tool_check", "midday_food_check", "midday_fish", "midday_eat");
                place(task, "midday_upgrade", 3, 7);
                place(task, "midday_hunt", 5, 7);
                place(task, "midday_hunt_loot", 6, 7);

                row(task, 9, "dusk_crops", "dusk_ores", "dusk_logs", "dusk_stone", "dusk_bed");
                place(task, "dusk_wool", 4, 10);

                row(task, 12, "night_scan", "night_sword", "night_fight", "night_fight_loot",
                        "night_sleep", "night_stash", "day_end");
                place(task, "night_forge_sword", 1, 13);

                place(task, "guard", 3, 15);
            }
            case "Deepcore Diamond Expedition" -> {
                // The main route stays on top. Each detour drops directly beneath the card that
                // selects it, then rejoins at the right-hand cash-out lane.
                row(task, 0, "start", "hunger", "eat", "select_pick", "scout", "mine_first",
                        "loot_first", "quota");
                place(task, "forge_pick", 3, 2);
                place(task, "first_strip", 4, 2);
                place(task, "second_strip", 7, 2);
                place(task, "mine_final", 8, 2);
                place(task, "loot_final", 9, 2);
                place(task, "cashout", 10, 0);
                place(task, "expedition_end", 11, 0);
                place(task, "guard", 6, 4);
            }
            case "Dragonfall Mission Control" -> {
                row(task, 0, "start", "preflight", "preflight_eat", "launch", "mission_loot",
                        "mission_end");
                row(task, 2, "field_pulse", "field_hunger", "field_eat", "field_end");
                place(task, "guard", 4, 2);
                rowAt(task, 4, 5, "mission_watch", "closeout_counter", "closeout_hub",
                        "debrief_loot", "debrief_loot_end");
                rowAt(task, 6, 8, "debrief_delay", "debrief_pause", "debrief_pause_end");
                row(task, 8, "emergency_button", "emergency_hub", "emergency_eat",
                        "emergency_eat_end");
                row(task, 10, "emergency_delay", "emergency_pause", "emergency_pause_end");
            }
            default -> {
                for (int i = 0; i < task.nodes.size(); i++) {
                    place(task, task.nodes.get(i).id, i, 0);
                }
            }
        }
    }

    private static void row(TaskGraph task, int row, String... ids) {
        rowAt(task, row, 0, ids);
    }

    private static void rowAt(TaskGraph task, int row, int startColumn, String... ids) {
        for (int column = 0; column < ids.length; column++) {
            place(task, ids[column], startColumn + column, row);
        }
    }

    private static void place(TaskGraph task, String id, int column, int row) {
        TaskNode node = task.nodeById(id);
        if (node != null) {
            node.editorX = 24 + column * TEACHING_STEP_X;
            node.editorY = 26 + row * TEACHING_STEP_Y;
        }
    }

    private static TaskNode node(String id, String commandId, Map<String, String> params) {
        TaskNode node = new TaskNode(commandId);
        node.id = id;
        node.params.putAll(params);
        return node;
    }
}
