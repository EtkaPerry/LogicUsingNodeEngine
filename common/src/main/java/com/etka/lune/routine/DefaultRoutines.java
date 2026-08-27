package com.etka.lune.routine;

import java.util.List;
import java.util.Map;

/**
 * The routines a new player starts with, seeded once when {@code config/lune-routines.json} does
 * not exist yet.
 * <p>
 * These are teaching material as much as they are useful jobs: they run in order from a single
 * node to a full flow graph with a monitor, a branch and a loop, so opening them in the Blueprint
 * canvas shows what the editor can express. Every command and parameter used here is one the
 * player could have picked from the palette themselves - nothing is special-cased.
 * <p>
 * Unlike {@link RoutineBlueprints}, which drops fragments into a routine being edited, these are
 * complete tasks that appear in the Task tab ready to run.
 */
public final class DefaultRoutines {

    private DefaultRoutines() {}

    /** Fresh copies, so a caller editing one cannot change what the next new profile is seeded with. */
    public static List<Routine> create() {
        List<Routine> routines = List.of(chopLogs(), stonePickaxe(), ironStarterKit(),
                digInForTheNight(), safeMiningTrip(), survivalOperationsCenter());
        routines.forEach(routine -> {
            RoutineGraph.addExplicitStart(routine);
            teachingLayout(routine);
        });
        return routines;
    }

    // --- 1. one node --------------------------------------------------------

    /** The smallest useful routine: a single command with its two numbers changed. */
    private static Routine chopLogs() {
        Routine routine = new Routine("Chop 20 Logs");
        routine.nodes.add(node("wood", "chop", Map.of(
                "radius", "64",
                "limit", "20")));
        return routine;
    }

    // --- 2. two nodes in order ----------------------------------------------

    /**
     * Two steps with no wiring at all. Nodes without edges fall through to the next one in the
     * list, which is what makes "do these in order" the default rather than something to build.
     */
    private static Routine stonePickaxe() {
        Routine routine = new Routine("Craft a Stone Pickaxe");
        RoutineNode wood = node("wood", "chop", Map.of(
                "radius", "64",
                "limit", "8"));
        RoutineNode pick = node("pick", "gettool", Map.of(
                "tool", "Pickaxe",
                "material", "Stone",
                "check_around", "true"));
        wood.onSuccess = pick.id;
        routine.nodes.add(wood);
        routine.nodes.add(pick);
        return routine;
    }

    // --- 3. a longer chain --------------------------------------------------

    /**
     * Get Tools gathers its own materials, so asking for an iron pickaxe pulls in the whole chain:
     * cobblestone, a furnace, mined iron ore, and the smelt. Four steps stand in for about twenty.
     */
    private static Routine ironStarterKit() {
        Routine routine = new Routine("Iron Starter Kit");
        RoutineNode wood = node("wood", "chop", Map.of(
                "radius", "64",
                "limit", "12"));
        RoutineNode stonepick = node("stonepick", "gettool", Map.of(
                "tool", "Pickaxe",
                "material", "Stone",
                "check_around", "true"));
        RoutineNode ironpick = node("ironpick", "gettool", Map.of(
                "tool", "Pickaxe",
                "material", "Iron",
                "check_around", "true"));
        RoutineNode ironsword = node("ironsword", "gettool", Map.of(
                "tool", "Sword",
                "material", "Iron",
                "check_around", "true"));
        wood.onSuccess = stonepick.id;
        stonepick.onSuccess = ironpick.id;
        ironpick.onSuccess = ironsword.id;
        routine.nodes.add(wood);
        routine.nodes.add(stonepick);
        routine.nodes.add(ironpick);
        routine.nodes.add(ironsword);
        return routine;
    }

    // --- 4. gather, then build ----------------------------------------------

    /**
     * Tools, then material, then a dug-out room in the nearest hillside, then a bed for the night.
     * Tunnel digs straight ahead from wherever the player is looking, so this one is worth aiming
     * at a hill before starting it.
     */
    private static Routine digInForTheNight() {
        Routine routine = new Routine("Dig In For The Night");
        RoutineNode wood = node("wood", "chop", Map.of(
                "radius", "64",
                "limit", "8"));
        RoutineNode pick = node("pick", "gettool", Map.of(
                "tool", "Pickaxe",
                "material", "Stone",
                "check_around", "true"));
        RoutineNode stone = node("stone", "mine", Map.of(
                "targets", "minecraft:stone,minecraft:cobblestone",
                "radius", "48",
                "y_min", "-64",
                "y_max", "320",
                "limit", "48",
                "auto_tool", "true",
                "prospect", "false",
                "check_around", "true"));
        RoutineNode room = node("room", "tunnel", Map.of(
                "direction", "Facing",
                "length", "6",
                "height", "2"));
        RoutineNode bed = node("bed", "sleep", Map.of(
                "wait_for_night", "true",
                "reclaim", "true",
                "radius", "32"));
        wood.onSuccess = pick.id;
        pick.onSuccess = stone.id;
        stone.onSuccess = room.id;
        room.onSuccess = bed.id;
        routine.nodes.add(wood);
        routine.nodes.add(pick);
        routine.nodes.add(stone);
        routine.nodes.add(room);
        routine.nodes.add(bed);
        return routine;
    }

    // --- 5. monitor, branch and loop ----------------------------------------

    /**
     * Everything the graph can do, in one routine.
     * <p>
     * An Always node points at Self Preservation, which makes it a monitor for every step rather
     * than a step of its own - it interrupts whatever is running when the player is drowning,
     * burning, falling or cornered, then hands control back. Mine branches on its own outcome:
     * finding nothing goes to Explore, which walks somewhere new and comes back to Mine. Check
     * Item Count closes the loop, sending the run back to Mine until the quota is met and only
     * then moving on to Deposit.
     */
    private static Routine safeMiningTrip() {
        Routine routine = new Routine("Safe Mining Trip");

        RoutineNode start = node("start", RoutineNode.START_COMMAND, Map.of());
        start.onSuccess = "pick";
        routine.nodes.add(start);

        RoutineNode always = node("always", RoutineNode.ALWAYS_COMMAND, Map.of());
        always.alwaysIntervalSeconds = 10;
        always.alwaysTargets.add("guard");
        routine.nodes.add(always);

        RoutineNode guard = node("guard", "self_preservation", Map.of(
                "protect_air", "true",
                "protect_lava", "true",
                "protect_fall", "true",
                "protect_monsters", "true",
                "protect_health", "true"));
        guard.repeat = 1;
        guard.onSuccess = "guard_end";
        guard.onFailure = "guard_end";
        routine.nodes.add(guard);

        routine.nodes.add(node("guard_end", RoutineNode.END_COMMAND, Map.of()));

        RoutineNode pick = node("pick", "gettool", Map.of(
                "tool", "Pickaxe",
                "material", "Stone",
                "check_around", "true"));
        pick.onSuccess = "dig";
        routine.nodes.add(pick);

        RoutineNode dig = node("dig", "mine", Map.of(
                "targets", "minecraft:iron_ore,minecraft:deepslate_iron_ore",
                "radius", "64",
                "y_min", "-64",
                "y_max", "72",
                "limit", "8",
                "auto_tool", "true",
                "prospect", "true",
                "check_around", "true"));
        dig.onSuccess = "quota";
        dig.onFailure = "roam";
        routine.nodes.add(dig);

        RoutineNode roam = node("roam", "explore", Map.of(
                "targets", "minecraft:iron_ore,minecraft:deepslate_iron_ore",
                "radius", "48",
                "attempts", "6",
                "step", "12",
                "check_around", "true",
                "smart_direction", "true"));
        // Found somewhere new to dig; anything else ends the run rather than wandering forever.
        roam.onSuccess = "dig";
        routine.nodes.add(roam);

        RoutineNode quota = node("quota", "check_item", Map.of(
                "item", "minecraft:raw_iron",
                "comparison", "At least",
                "count", "16"));
        quota.onSuccess = "stash";
        quota.onFailure = "dig";
        routine.nodes.add(quota);

        RoutineNode stash = node("stash", "deposit", Map.of(
                "filter", "Ores",
                "radius", "16"));
        stash.onSuccess = "trip_end";
        stash.onFailure = "trip_end";
        routine.nodes.add(stash);
        routine.nodes.add(node("trip_end", RoutineNode.END_COMMAND, Map.of()));

        return routine;
    }

    // --- 6. a full multi-circuit operations task ---------------------------

    /**
     * A deliberately elaborate teaching task for the complete circuit system. The main START
     * route prepares food and tools, opens a short tunnel, searches for visible iron, smelts and
     * stores it, while separate electrical circuits keep watching the player and respond to events
     * without outranking or replacing that route.
     */
    private static Routine survivalOperationsCenter() {
        Routine routine = new Routine("Survival Operations Center");

        // Primary circuit: a bounded survival-and-mining run.
        RoutineNode start = node("start", RoutineNode.START_COMMAND, Map.of());
        start.onSuccess = "hunger_gate";
        start.editorX = 24;
        start.editorY = 360;
        routine.nodes.add(start);

        RoutineNode hungerGate = node("hunger_gate", "check_player", Map.of(
                "metric", "Hunger",
                "comparison", "At most",
                "threshold", "10"));
        hungerGate.onSuccess = "eat";
        hungerGate.onFailure = "tool";
        routine.nodes.add(hungerGate);

        RoutineNode eat = node("eat", "eat", Map.of("minimum_food", "14"));
        eat.onSuccess = "tool";
        eat.onFailure = "harvest_food";
        routine.nodes.add(eat);

        RoutineNode harvestFood = node("harvest_food", "harvest", Map.of(
                "targets", "minecraft:wheat,minecraft:carrots,minecraft:potatoes,minecraft:beetroots",
                "radius", "32",
                "limit", "12"));
        harvestFood.onSuccess = "tool";
        harvestFood.onFailure = "tool";
        routine.nodes.add(harvestFood);

        RoutineNode tool = node("tool", "gettool", Map.of(
                "tool", "Pickaxe",
                "material", "Stone",
                "check_around", "true"));
        tool.onSuccess = "tunnel";
        tool.onFailure = "wood";
        routine.nodes.add(tool);

        RoutineNode tunnel = node("tunnel", "tunnel", Map.of(
                "direction", "Facing",
                "length", "6",
                "height", "2"));
        tunnel.onSuccess = "wood";
        tunnel.onFailure = "wood";
        routine.nodes.add(tunnel);

        RoutineNode wood = node("wood", "chop", Map.of(
                "radius", "64",
                "limit", "16"));
        wood.onSuccess = "ore_probe";
        wood.onFailure = "forest_explore";
        routine.nodes.add(wood);

        RoutineNode forestExplore = node("forest_explore", "explore", Map.of(
                "targets", "minecraft:oak_log,minecraft:spruce_log,minecraft:birch_log",
                "radius", "48",
                "attempts", "5",
                "step", "12",
                "check_around", "true",
                "scan_style", "Glance ahead",
                "smart_direction", "true"));
        forestExplore.onSuccess = "wood";
        forestExplore.onFailure = "ore_probe";
        routine.nodes.add(forestExplore);

        RoutineNode oreProbe = node("ore_probe", "find", Map.of(
                "target_kind", "Blocks",
                "targets", "minecraft:iron_ore,minecraft:deepslate_iron_ore",
                "radius", "48",
                "y_min", "-64",
                "y_max", "72",
                "prospect", "true",
                "check_around", "true"));
        oreProbe.onSuccess = "mine_ore";
        oreProbe.onFailure = "ore_explore";
        routine.nodes.add(oreProbe);

        RoutineNode mineOre = node("mine_ore", "mine", Map.of(
                "targets", "minecraft:iron_ore,minecraft:deepslate_iron_ore",
                "radius", "64",
                "y_min", "-64",
                "y_max", "72",
                "limit", "8",
                "auto_tool", "true",
                "prospect", "true",
                "check_around", "true"));
        mineOre.onSuccess = "smelt_ore";
        mineOre.onFailure = "ore_explore";
        mineOre.onWhile = "mine_leash";
        routine.nodes.add(mineOre);

        RoutineNode oreExplore = node("ore_explore", "explore", Map.of(
                "targets", "minecraft:iron_ore,minecraft:deepslate_iron_ore",
                "radius", "48",
                "attempts", "6",
                "step", "12",
                "check_around", "true",
                "scan_style", "Glance ahead",
                "smart_direction", "true"));
        oreExplore.onSuccess = "ore_probe";
        oreExplore.onFailure = "walk_back";
        routine.nodes.add(oreExplore);

        RoutineNode walkBack = node("walk_back", "walk", Map.of(
                "direction", "Back",
                "distance", "8",
                "tolerance", "1"));
        walkBack.onSuccess = "ore_probe";
        walkBack.onFailure = "finish_main";
        routine.nodes.add(walkBack);

        RoutineNode smeltOre = node("smelt_ore", "smelt", Map.of(
                "input", "Raw Iron",
                "count", "8"));
        smeltOre.onSuccess = "stash_ore";
        smeltOre.onFailure = "stash_ore";
        routine.nodes.add(smeltOre);

        RoutineNode stashOre = node("stash_ore", "deposit", Map.of(
                "filter", "Ores",
                "radius", "16",
                "optional", "true"));
        stashOre.onSuccess = "loot_after_mine";
        stashOre.onFailure = "loot_after_mine";
        routine.nodes.add(stashOre);

        RoutineNode lootAfterMine = node("loot_after_mine", "loot", Map.of("radius", "16"));
        lootAfterMine.onSuccess = "sleep_check";
        lootAfterMine.onFailure = "sleep_check";
        routine.nodes.add(lootAfterMine);

        RoutineNode sleepCheck = node("sleep_check", "sleep", Map.of(
                "wait_for_night", "false",
                "reclaim", "true",
                "radius", "32"));
        sleepCheck.onSuccess = "finish_main";
        sleepCheck.onFailure = "finish_main";
        routine.nodes.add(sleepCheck);

        RoutineNode finishMain = node("finish_main", RoutineNode.END_COMMAND, Map.of());
        routine.nodes.add(finishMain);

        // Always circuit: periodically sample two independent protections. Each branch ends
        // explicitly so a completed safety check cannot fall through into the main route.
        RoutineNode heartbeat = node("heartbeat", RoutineNode.ALWAYS_COMMAND, Map.of());
        heartbeat.alwaysIntervalSeconds = 10;
        heartbeat.alwaysTargets.add("safety_hub");
        heartbeat.alwaysTargetInputPorts.put("safety_hub", 0);
        routine.nodes.add(heartbeat);

        RoutineNode safetyHub = relay("safety_hub", 1, 2);
        safetyHub.signalLinks.add(pulseLink(0, "background_guard"));
        safetyHub.signalLinks.add(pulseLink(1, "background_leash"));
        routine.nodes.add(safetyHub);

        RoutineNode backgroundGuard = node("background_guard", "self_preservation", safetyParams());
        backgroundGuard.repeat = 1;
        backgroundGuard.onSuccess = "background_guard_end";
        backgroundGuard.onFailure = "background_guard_end";
        routine.nodes.add(backgroundGuard);

        RoutineNode backgroundGuardEnd = node("background_guard_end", RoutineNode.END_COMMAND, Map.of());
        routine.nodes.add(backgroundGuardEnd);

        RoutineNode backgroundLeash = node("background_leash", "stay_near", Map.of(
                "anchor", "Where the run started",
                "waypoint", "",
                "radius", "48"));
        backgroundLeash.repeat = 1;
        backgroundLeash.onSuccess = "background_leash_end";
        backgroundLeash.onFailure = "background_leash_end";
        routine.nodes.add(backgroundLeash);

        RoutineNode backgroundLeashEnd = node("background_leash_end", RoutineNode.END_COMMAND, Map.of());
        routine.nodes.add(backgroundLeashEnd);

        // The While companion is live only while the mining node is active.
        RoutineNode mineLeash = node("mine_leash", "stay_near", Map.of(
                "anchor", "Where the run started",
                "waypoint", "",
                "radius", "30"));
        mineLeash.repeat = 0;
        routine.nodes.add(mineLeash);

        // Health event: pause before a critical-health death, then terminate that event circuit.
        RoutineNode healthWatch = node("health_watch", RoutineNode.OBSERVER_COMMAND, Map.of(
                "watch", "Health crosses below",
                "threshold", "4"));
        healthWatch.signalLinks.add(pulseLink(0, "critical_delay"));
        routine.nodes.add(healthWatch);

        RoutineNode criticalDelay = node("critical_delay", RoutineNode.TIMER_COMMAND,
                Map.of("seconds", "1"));
        criticalDelay.signalLinks.add(pulseLink(0, "critical_pause"));
        routine.nodes.add(criticalDelay);

        RoutineNode criticalPause = node("critical_pause", "pause_game", Map.of());
        criticalPause.onSuccess = "critical_end";
        criticalPause.onFailure = "critical_end";
        routine.nodes.add(criticalPause);

        RoutineNode criticalEnd = node("critical_end", RoutineNode.END_COMMAND, Map.of());
        routine.nodes.add(criticalEnd);

        // Mob event: require two arrivals before spending time fighting; retreat if the fight
        // cannot be started, and close either outcome with End.
        RoutineNode mobWatch = node("mob_watch", RoutineNode.OBSERVER_COMMAND, Map.of(
                "watch", "Mob enters range",
                "entities", "minecraft:zombie,minecraft:skeleton,minecraft:creeper",
                "radius", "14"));
        mobWatch.signalLinks.add(pulseLink(0, "threat_counter"));
        routine.nodes.add(mobWatch);

        RoutineNode threatCounter = node("threat_counter", RoutineNode.COUNTER_COMMAND,
                Map.of("count", "2"));
        threatCounter.signalLinks.add(pulseLink(0, "threat_delay"));
        routine.nodes.add(threatCounter);

        RoutineNode threatDelay = node("threat_delay", RoutineNode.TIMER_COMMAND,
                Map.of("seconds", "2"));
        threatDelay.signalLinks.add(pulseLink(0, "fight_threat"));
        routine.nodes.add(threatDelay);

        RoutineNode fightThreat = node("fight_threat", "kill", Map.of(
                "targets", "minecraft:zombie,minecraft:skeleton,minecraft:creeper",
                "radius", "16",
                "fire_resistance", "true",
                "use_shield", "true",
                "craft_shield", "false",
                "weapon", "Automatic",
                "craft_weapon", "false",
                "enderman_safety", "Auto"));
        fightThreat.onSuccess = "threat_end";
        fightThreat.onFailure = "retreat_threat";
        routine.nodes.add(fightThreat);

        RoutineNode retreatThreat = node("retreat_threat", "run", Map.of(
                "direction", "Back",
                "distance", "12",
                "tolerance", "1"));
        retreatThreat.onSuccess = "threat_end";
        retreatThreat.onFailure = "threat_end";
        routine.nodes.add(retreatThreat);

        RoutineNode threatEnd = node("threat_end", RoutineNode.END_COMMAND, Map.of());
        routine.nodes.add(threatEnd);

        // Inventory event: four bread-count changes trigger a delayed optional deposit.
        RoutineNode inventoryWatch = node("inventory_watch", RoutineNode.OBSERVER_COMMAND, Map.of(
                "watch", "Item count changes",
                "item", "minecraft:bread"));
        inventoryWatch.signalLinks.add(pulseLink(0, "inventory_counter"));
        routine.nodes.add(inventoryWatch);

        RoutineNode inventoryCounter = node("inventory_counter", RoutineNode.COUNTER_COMMAND,
                Map.of("count", "4"));
        inventoryCounter.signalLinks.add(pulseLink(0, "inventory_delay"));
        routine.nodes.add(inventoryCounter);

        RoutineNode inventoryDelay = node("inventory_delay", RoutineNode.TIMER_COMMAND,
                Map.of("seconds", "5"));
        inventoryDelay.signalLinks.add(pulseLink(0, "inventory_stash"));
        routine.nodes.add(inventoryDelay);

        RoutineNode inventoryStash = node("inventory_stash", "deposit", Map.of(
                "filter", "All",
                "radius", "16",
                "optional", "true"));
        inventoryStash.onSuccess = "inventory_end";
        inventoryStash.onFailure = "inventory_end";
        routine.nodes.add(inventoryStash);

        RoutineNode inventoryEnd = node("inventory_end", RoutineNode.END_COMMAND, Map.of());
        routine.nodes.add(inventoryEnd);

        // Manual button: one press fans out through a two-output relay into delayed stash and
        // bridge circuits, showing that a source can be useful without entering START.
        RoutineNode button = node("manual_button", RoutineNode.BUTTON_COMMAND, Map.of());
        button.signalLinks.add(new RoutineSignalLink(0, "control_hub", 0));
        routine.nodes.add(button);

        RoutineNode controlHub = relay("control_hub", 2, 2);
        controlHub.signalLinks.add(pulseLink(0, "manual_stash_delay"));
        controlHub.signalLinks.add(pulseLink(1, "manual_bridge_delay"));
        routine.nodes.add(controlHub);

        RoutineNode manualStashDelay = node("manual_stash_delay", RoutineNode.TIMER_COMMAND,
                Map.of("seconds", "2"));
        manualStashDelay.signalLinks.add(pulseLink(0, "manual_stash"));
        routine.nodes.add(manualStashDelay);

        RoutineNode manualStash = node("manual_stash", "deposit", Map.of(
                "filter", "All",
                "radius", "16",
                "optional", "true"));
        manualStash.onSuccess = "manual_stash_end";
        manualStash.onFailure = "manual_stash_end";
        routine.nodes.add(manualStash);

        RoutineNode manualStashEnd = node("manual_stash_end", RoutineNode.END_COMMAND, Map.of());
        routine.nodes.add(manualStashEnd);

        RoutineNode manualBridgeDelay = node("manual_bridge_delay", RoutineNode.TIMER_COMMAND,
                Map.of("seconds", "1"));
        manualBridgeDelay.signalLinks.add(pulseLink(0, "manual_bridge"));
        routine.nodes.add(manualBridgeDelay);

        RoutineNode manualBridge = node("manual_bridge", "bridge", Map.of(
                "direction", "Facing",
                "length", "8",
                "materials", "minecraft:cobblestone"));
        manualBridge.onSuccess = "manual_bridge_end";
        manualBridge.onFailure = "manual_bridge_end";
        routine.nodes.add(manualBridge);

        RoutineNode manualBridgeEnd = node("manual_bridge_end", RoutineNode.END_COMMAND, Map.of());
        routine.nodes.add(manualBridgeEnd);

        return routine;
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

    private static RoutineNode relay(String id, int inputs, int outputs) {
        RoutineNode relay = node(id, RoutineNode.SIGNAL_RELAY_COMMAND, Map.of());
        relay.signalInputCount = inputs;
        relay.signalOutputCount = outputs;
        return relay;
    }

    private static RoutineSignalLink pulseLink(int outputPort, String targetId) {
        return new RoutineSignalLink(outputPort, targetId, -1);
    }

    /** Positions the seeded examples as diagrams, not as a hidden horizontal list. */
    private static void teachingLayout(Routine routine) {
        if (routine == null) {
            return;
        }
        if (routine.name.equals("Safe Mining Trip")) {
            place(routine, "start", 0, 0);
            place(routine, "pick", 1, 0);
            place(routine, "dig", 2, 0);
            place(routine, "quota", 3, 0);
            place(routine, "stash", 4, 0);
            place(routine, "trip_end", 5, 0);
            place(routine, "always", 0, 2);
            place(routine, "guard", 1, 2);
            place(routine, "guard_end", 2, 2);
            place(routine, "roam", 3, 2);
            return;
        }
        if (routine.name.equals("Survival Operations Center")) {
            // Main START route runs across the top. Background and event circuits are deliberately
            // below it, so the eye can read the primary job without mistaking a monitor for a step.
            String[] main = {"start", "hunger_gate", "eat", "tool", "tunnel", "wood",
                    "ore_probe", "mine_ore", "smelt_ore", "stash_ore", "loot_after_mine",
                    "sleep_check", "finish_main"};
            for (int i = 0; i < main.length; i++) {
                place(routine, main[i], i, 0);
            }
            place(routine, "harvest_food", 2, 2);
            place(routine, "forest_explore", 5, 2);
            place(routine, "ore_explore", 7, 2);
            place(routine, "walk_back", 9, 2);

            place(routine, "heartbeat", 0, 4);
            place(routine, "safety_hub", 1, 4);
            place(routine, "background_guard", 2, 4);
            place(routine, "background_guard_end", 3, 4);
            place(routine, "background_leash", 2, 6);
            place(routine, "background_leash_end", 3, 6);
            place(routine, "mine_leash", 5, 4);

            place(routine, "health_watch", 0, 8);
            place(routine, "critical_delay", 1, 8);
            place(routine, "critical_pause", 2, 8);
            place(routine, "critical_end", 3, 8);
            place(routine, "mob_watch", 5, 8);
            place(routine, "threat_counter", 6, 8);
            place(routine, "threat_delay", 7, 8);
            place(routine, "fight_threat", 8, 8);
            place(routine, "retreat_threat", 8, 10);
            place(routine, "threat_end", 9, 8);

            place(routine, "inventory_watch", 0, 12);
            place(routine, "inventory_counter", 1, 12);
            place(routine, "inventory_delay", 2, 12);
            place(routine, "inventory_stash", 3, 12);
            place(routine, "inventory_end", 4, 12);
            place(routine, "manual_button", 6, 12);
            place(routine, "control_hub", 7, 12);
            place(routine, "manual_stash_delay", 8, 12);
            place(routine, "manual_stash", 9, 12);
            place(routine, "manual_stash_end", 10, 12);
            place(routine, "manual_bridge_delay", 8, 14);
            place(routine, "manual_bridge", 9, 14);
            place(routine, "manual_bridge_end", 10, 14);
            return;
        }

        // The smaller examples are straight teaching chains. Their explicit Success wires are
        // easy to read when they share one row; longer examples naturally wrap below.
        for (int i = 0; i < routine.nodes.size(); i++) {
            RoutineNode node = routine.nodes.get(i);
            place(routine, node.id, i, 0);
        }
    }

    private static void place(Routine routine, String id, int column, int row) {
        RoutineNode node = routine.nodeById(id);
        if (node == null) {
            return;
        }
        node.editorX = 24 + column * 154;
        node.editorY = 26 + row * 112;
    }

    // --- helpers ------------------------------------------------------------

    /**
     * Ids are written out rather than generated, because the edges above refer to them. They only
     * have to be unique inside one routine.
     */
    private static RoutineNode node(String id, String commandId, Map<String, String> params) {
        RoutineNode node = new RoutineNode(commandId);
        node.id = id;
        node.params.putAll(params);
        return node;
    }
}
