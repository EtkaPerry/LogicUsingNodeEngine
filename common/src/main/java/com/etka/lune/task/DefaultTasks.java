package com.etka.lune.task;

import java.util.List;
import java.util.Map;

/**
 * The six jobs seeded when {@code config/lune-tasks.json} does not exist yet.
 *
 * <p>They climb Minecraft's own chain of unlocks - bare hands buy logs, logs buy a wooden pickaxe,
 * that pickaxe buys stone, stone buys iron, iron buys diamond, diamond buys the Nether - but a set
 * that only ever climbed it would be six jobs of the same shape with a different ore name in each.
 * So two of them do something else entirely: job 3 keeps a property, and job 4 spends a whole day
 * and night on the clock. Read in order they teach the game; run one at a time they are still
 * useful work.</p>
 *
 * <p>The Blueprint vocabulary grows on the same slope. Job 1 is a straight line with a single
 * retry wire. Job 2 adds a counted quota. Job 3 adds Check Time, a Stay Near boundary and a Self
 * Preservation guard on the cards that need them. Job 4 closes three loops with nothing but the
 * clock. Job 5 adds a job called by name, data wires, and Select from Inventory used as a
 * durability condition. Job 6 is seventy-eight cards: the whole ladder in one graph, handed to
 * Complete the Game once the pack is stocked, with clocks, an Observer, a Counter, Relays and a
 * panic Button running beside it. Every card, parameter and wire is available in the editor; the
 * defaults have no private runtime shortcuts.</p>
 */
public final class DefaultTasks {

    /** Cards are 124px wide; the seeded lanes leave room for cables and deliberate rightward flow. */
    private static final int TEACHING_STEP_X = 208;
    private static final int TEACHING_STEP_Y = 132;

    // Tags rather than lists of ids: a task that asks for #minecraft:iron_ores keeps working in
    // the deepslate layer, and picks up whatever a mod adds to the tag without being edited.
    private static final String LOGS = "#minecraft:logs";
    private static final String COAL_ORES = "#minecraft:coal_ores";
    private static final String IRON_ORES = "#minecraft:iron_ores";
    private static final String DIAMOND_ORES = "#minecraft:diamond_ores";
    private static final String DEEP_ORES =
            "#minecraft:redstone_ores,#minecraft:lapis_ores,#minecraft:gold_ores";
    private static final String STONE = "minecraft:stone";
    private static final String OBSIDIAN = "minecraft:obsidian";

    /** What a homestead grows, and what Harvest replants behind itself. */
    private static final String CROPS =
            "minecraft:wheat,minecraft:carrots,minecraft:potatoes,minecraft:beetroots";

    /** What a hungry bot can catch on the surface without a plan. */
    private static final String LIVESTOCK =
            "minecraft:cow,minecraft:pig,minecraft:sheep,minecraft:chicken";

    /** The four the bot is most likely to meet in its own yard after dark. */
    private static final String NIGHT_MOBS =
            "minecraft:zombie,minecraft:skeleton,minecraft:creeper,minecraft:spider";

    /**
     * Job names, kept here because job 5 hands work back to the rung below it.
     *
     * <p>English, and staying English: this is what the job is called on disk, what a Run Task card
     * elsewhere points at, and what {@link TaskStore#restoreMissingDefaults()} recognises in a save
     * written before ids existed. What the player reads is the {@code lune.task.seeded.*} line the
     * matching id opposite resolves to.</p>
     */
    private static final String LOGS_JOB = "1. Chop 12 Logs";
    private static final String STONE_JOB = "2. Wood, Pickaxe, 20 Stone";
    private static final String HOMESTEAD_JOB = "3. Homestead: Farm and Guard";
    private static final String NIGHTFALL_JOB = "4. Fish Till Dusk, Then Sleep";
    private static final String PORTAL_JOB = "5. Stone Tools to a Lit Portal";
    private static final String DRAGON_JOB = "6. New World to Ender Dragon";

    /** The id each of those jobs is known by, once and for all, in every language. */
    static final String LOGS_ID = "logs";
    static final String STONE_ID = "stone";
    static final String HOMESTEAD_ID = "homestead";
    static final String NIGHTFALL_ID = "nightfall";
    static final String PORTAL_ID = "portal";
    static final String DRAGON_ID = "dragon";

    /**
     * The English name each id shipped under, for reading saves that predate the id.
     *
     * <p>A player who has been running these since before this change has six tasks on disk with
     * no id on them. Matching the name once, on load, is what stops the restore adding a second
     * copy of every starter job beside the ones they have already edited.</p>
     */
    static final Map<String, String> SEEDED_NAMES = Map.of(
            LOGS_JOB, LOGS_ID,
            STONE_JOB, STONE_ID,
            HOMESTEAD_JOB, HOMESTEAD_ID,
            NIGHTFALL_JOB, NIGHTFALL_ID,
            PORTAL_JOB, PORTAL_ID,
            DRAGON_JOB, DRAGON_ID);

    private DefaultTasks() {}

    /** A starter job, named for the disk and identified for everything else. */
    private static TaskGraph seeded(String name, String id) {
        TaskGraph task = new TaskGraph(name);
        task.seededId = id;
        return task;
    }

    /** Fresh graphs, so editing a seeded job never mutates a later restore. */
    public static List<TaskGraph> create() {
        List<TaskGraph> tasks = List.of(chopLogs(), woodToStone(), homesteadShift(), fishTillDusk(),
                stoneToPortal(), newWorldToDragon());
        tasks.forEach(task -> {
            TaskWiring.addExplicitStart(task);
            teachingLayout(task);
            routeReturnCables(task);
        });
        return tasks;
    }

    /** How far right of a card's output pin the cable turns down into the gutter. */
    private static final int GUTTER_ENTRY_X = 24;

    /** Lanes inside the gutter, so cables sharing a return run do not stack on one line. */
    private static final int GUTTER_LANE_HEIGHT = 12;
    private static final int GUTTER_LANES = 4;

    /** Ignore the short hop a detour makes rejoining the lane; that one already looks fine. */
    private static final int SHORTEST_ROUTED_RETURN = 2 * TEACHING_STEP_X;

    /**
     * Walks the cables that run backwards down into the gutter between the rows.
     *
     * <p>A cable with no routing points leaves its pin sideways and arrives sideways, which is the
     * right shape when the target is to the right. When it is a long way to the <em>left</em> - the
     * fishing loop in job 4 goes back five columns - that same shape becomes a wide flat bow lying
     * directly along the row it came from, straight across every card in between. Nothing is
     * misdrawn; it is just unreadable.</p>
     *
     * <p>So those cables get four points: a stub out of the pin, a corner down into the empty band
     * below the row, the run back, and a stub into the target. The stubs are what keep the ends
     * horizontal, since a routed cable aims at the point the player - or this method - put there.
     * Every x sits in the sixty-pixel gap between two columns and every y in the gap between two
     * rows, so a route never crosses a card it is trying to avoid.</p>
     */
    private static void routeReturnCables(TaskGraph task) {
        int lane = 0;
        for (TaskNode source : task.nodes) {
            lane = route(task, source, "success", TaskCanvas.successY(source),
                    source.onSuccess, lane);
            lane = route(task, source, "failure", TaskCanvas.failureY(source),
                    source.onFailure, lane);
            lane = route(task, source, "while", TaskCanvas.whileY(source), source.onWhile, lane);
        }
    }

    private static int route(TaskGraph task, TaskNode source, String kind, int pinY,
                             String targetId, int lane) {
        TaskNode target = task.nodeById(targetId);
        if (target == null || source.editorX == null || target.editorX == null
                || TaskCanvas.outputX(source) - TaskCanvas.inputX(target) < SHORTEST_ROUTED_RETURN) {
            return lane;
        }
        int gutter = Math.max(TaskCanvas.top(source), TaskCanvas.top(target))
                + TaskCanvas.CARD_HEIGHT + GUTTER_LANE_HEIGHT * (1 + lane % GUTTER_LANES);
        int exitX = TaskCanvas.outputX(source) + GUTTER_ENTRY_X;
        int entryX = TaskCanvas.inputX(target) - GUTTER_ENTRY_X;

        TaskCableRoute cable = new TaskCableRoute();
        cable.points.add(new TaskCableAnchor(exitX, pinY));
        cable.points.add(new TaskCableAnchor(exitX, gutter));
        cable.points.add(new TaskCableAnchor(entryX, gutter));
        cable.points.add(new TaskCableAnchor(entryX, TaskCanvas.inputY(target)));
        task.cableAnchors.put(TaskCableAnchor.key(kind, source.id, target.id), cable);
        return lane + 1;
    }

    // 1. Bare hands to twelve logs. The whole game starts here, so the job that teaches it is
    // allowed to be four cards long.

    private static TaskGraph chopLogs() {
        TaskGraph task = seeded(LOGS_JOB, LOGS_ID);

        // Chop Wood needs no tool at all - punching a tree is slow, not impossible - which is why
        // the ladder can start with nothing in the pack.
        task.nodes.add(chop("chop_12", 12, "logs_loot", "tree_search"));

        // The one wire in this job that runs backwards. A clearing with no trees in it is worth
        // walking out of and trying again; nothing else here is.
        task.nodes.add(search("tree_search", LOGS, 5, "chop_12", "logs_loot"));

        task.nodes.add(sweep("logs_loot", "wooden_axe"));

        // Twelve logs is two planks short of an axe plus a spare handle, so this is the first
        // moment in the game the bot can make anything at all.
        task.nodes.add(forge("wooden_axe", "Axe", "Wooden", "axe_top_up", "axe_top_up"));

        // With the axe in hand the same twelve logs cost about a third of the time. That
        // difference is the entire lesson of job 1.
        task.nodes.add(chop("axe_top_up", 12, "logs_stash", "logs_stash"));

        task.nodes.add(store("logs_stash", "Logs", "wood_end"));
        task.nodes.add(end("wood_end"));
        return task;
    }

    // 2. Twelve logs, a wooden pickaxe, twenty stone, and the stone kit those twenty buy.

    private static TaskGraph woodToStone() {
        TaskGraph task = seeded(STONE_JOB, STONE_ID);

        task.nodes.add(chop("chop_12", 12, "logs_loot", "tree_search"));
        task.nodes.add(search("tree_search", LOGS, 5, "chop_12", "logs_loot"));
        task.nodes.add(sweep("logs_loot", "wooden_pick"));

        // The rung that matters: stone is not minable by hand, so the pickaxe has to exist before
        // the mining card is worth running. Fail still walks on, because Mine can provision its
        // own tool - the explicit card is here to be read, not to be the only way through.
        task.nodes.add(forge("wooden_pick", "Pickaxe", "Wooden", "wooden_axe", "stone_20"));
        task.nodes.add(forge("wooden_axe", "Axe", "Wooden", "stone_20", "stone_20"));

        task.nodes.add(mine("stone_20", STONE, 32, -64, 320, 20, false, "stone_loot", "stone_search"));
        task.nodes.add(search("stone_search", STONE, 4, "stone_20", "stone_loot"));
        task.nodes.add(sweep("stone_loot", "stone_count"));

        // Twenty is the number the job promises, so the job checks it rather than assuming the
        // mining card hit its limit. Anything short takes the top-up detour and comes back.
        task.nodes.add(carrying("stone_count", "minecraft:cobblestone", 20,
                "stone_pick", "stone_top_up"));
        task.nodes.add(mine("stone_top_up", STONE, 48, -64, 320, 12, false,
                "stone_more_loot", "stone_pick"));
        task.nodes.add(sweep("stone_more_loot", "stone_pick"));

        // Twenty cobble is a pickaxe, an axe, a sword and change. Spending it here is what job 5
        // comes back for when it arrives at the descent empty-handed.
        task.nodes.add(forge("stone_pick", "Pickaxe", "Stone", "stone_axe", "kit_stash"));
        task.nodes.add(forge("stone_axe", "Axe", "Stone", "stone_sword", "stone_sword"));
        task.nodes.add(forge("stone_sword", "Sword", "Stone", "kit_stash", "kit_stash"));

        task.nodes.add(store("kit_stash", "Stone", "kit_end"));
        task.nodes.add(end("kit_end"));
        return task;
    }

    // 3. A day on the property: farm it, restock the larder, then hold the yard after dark.

    /**
     * The rung that is not about digging.
     *
     * <p>Two monitors, each on the cards it belongs to. Stay Near keeps the field work inside the
     * property - a Harvest that chases one last wheat stalk over the hill is how an unattended bot
     * ends its shift a thousand blocks from the chest it was filling. Self Preservation rides the
     * one card that swings a sword. Neither could share a pin with the other, which is the point:
     * a While companion belongs to a card, not to a job.</p>
     */
    private static TaskGraph homesteadShift() {
        TaskGraph task = seeded(HOMESTEAD_JOB, HOMESTEAD_ID);
        task.nodes.add(start("day_gate"));

        // Check Time is what turns one job into a day shift and a night watch. Field work in the
        // dark is a bot standing in a wheat row while a creeper walks up behind it.
        task.nodes.add(timeIs("day_gate", "Day", "field", "yard_watch"));

        TaskNode field = node("field", "harvest", Map.of(
                "targets", CROPS,
                "radius", "32",
                "limit", "64",
                "collect", "true",
                "replant", "true"));
        field.onSuccess = "field_loot";
        field.onFailure = "larder";
        task.nodes.add(field);

        task.nodes.add(sweep("field_loot", "store_crops"));
        task.nodes.add(store("store_crops", "Crops", "larder"));

        // Meat keeps; a hunt that happens every shift does not. The gate is the difference between
        // a homestead and a slaughterhouse.
        task.nodes.add(carrying("larder", "minecraft:beef", 8, "wood_run", "stock_meat"));
        TaskNode stockMeat = hunt("stock_meat", LIVESTOCK, 24, "meat_loot", "wood_run");
        task.nodes.add(stockMeat);
        task.nodes.add(sweep("meat_loot", "wood_run"));

        // Eight logs a shift is fence posts, tool handles and furnace fuel, not a logging camp.
        TaskNode woodRun = chop("wood_run", 8, "wood_loot", "store_all");
        woodRun.params.put("radius", "48");
        task.nodes.add(woodRun);
        task.nodes.add(sweep("wood_loot", "store_all"));
        task.nodes.add(store("store_all", "All", "yard_watch"));

        // The watch. Looking before arming means the sword is only made on a night that needs one.
        task.nodes.add(findMobs("yard_watch", NIGHT_MOBS, 24, "arm_gate", "supper_gate"));
        task.nodes.add(carrying("arm_gate", "minecraft:stone_sword", 1, "drive_off", "forge_sword"));
        task.nodes.add(forge("forge_sword", "Sword", "Stone", "drive_off", "supper_gate"));
        TaskNode driveOff = fight("drive_off", NIGHT_MOBS, 16, "drive_loot", "supper_gate");
        task.nodes.add(driveOff);
        task.nodes.add(sweep("drive_loot", "supper_gate"));

        task.nodes.add(hungry("supper_gate", 14, "supper", "home_end"));
        task.nodes.add(meal("supper", 18, "home_end"));
        task.nodes.add(end("home_end"));

        // The fence is on the field work only. Chasing a zombie twenty blocks past the line is the
        // one thing on this shift that should be allowed to leave the property.
        TaskNode fence = stayNear("fence", 48);
        protectWith(fence, field, stockMeat, woodRun);
        task.nodes.add(fence);

        addSafetyCircuit(task);
        return task;
    }

    // 4. A whole day and night on the clock: fish while it is light, hold the yard through dusk,
    // sleep once a bed would actually accept.

    /**
     * The rung that is about time rather than about materials.
     *
     * <p>Three loops, and every one of them is closed by the clock rather than by a counter: fish
     * until Day stops being true, do chores and watch the yard until it is dark enough to sleep,
     * and if the night does not pass, go back to watching until morning. Nothing here counts
     * anything, which is exactly what makes it read differently from the mining jobs.</p>
     */
    private static TaskGraph fishTillDusk() {
        TaskGraph task = seeded(NIGHTFALL_JOB, NIGHTFALL_ID);
        task.nodes.add(start("day_gate"));

        task.nodes.add(timeIs("day_gate", "Day", "hunger_gate", "bedtime_gate"));
        task.nodes.add(hungry("hunger_gate", 14, "eat_catch", "rod_gate"));
        task.nodes.add(meal("eat_catch", 18, "rod_gate"));

        // Fish reads the main hand and does not equip for itself, and no card crafts a rod. Select
        // from Inventory is therefore not decoration here: it is the difference between fishing and
        // standing on a beach waving.
        task.nodes.add(hold("rod_gate", "minecraft:fishing_rod", 0, "cast", "shore_hunt"));

        // Auto recast off, deliberately. With it on, Fish never reports Success - it is a card that
        // fishes until something stops it - and this job's whole premise is that the clock decides
        // when to stop. One catch per pass hands the run back to Check Time, and the loop around
        // the outside is the recast.
        TaskNode cast = node("cast", "fish", Map.of("auto_recast", "false"));
        cast.onSuccess = "catch_loot";
        cast.onFailure = "shore_hunt";
        task.nodes.add(cast);

        // No rod, or no water worth casting at: the day still has to produce a meal.
        TaskNode shoreHunt = hunt("shore_hunt", LIVESTOCK, 32, "catch_loot", "stash_catch");
        task.nodes.add(shoreHunt);
        task.nodes.add(sweep("catch_loot", "stash_catch"));
        task.nodes.add(store("stash_catch", "All", "day_gate"));

        // Dusk is not bedtime. A bed refuses for the first five hundred ticks of it, so the job
        // fills them instead of standing at the pillow failing.
        task.nodes.add(timeIs("bedtime_gate", "Dark enough to sleep", "bed_gate", "chore_wood"));
        TaskNode choreWood = chop("chore_wood", 8, "chore_loot", "yard_watch");
        choreWood.params.put("radius", "48");
        task.nodes.add(choreWood);
        task.nodes.add(sweep("chore_loot", "yard_watch"));

        task.nodes.add(findMobs("yard_watch", NIGHT_MOBS, 24, "yard_arm", "bedtime_gate"));
        task.nodes.add(carrying("yard_arm", "minecraft:stone_sword", 1, "yard_fight", "yard_forge"));
        task.nodes.add(forge("yard_forge", "Sword", "Stone", "yard_fight", "bedtime_gate"));
        TaskNode yardFight = fight("yard_fight", NIGHT_MOBS, 16, "yard_loot", "bedtime_gate");
        task.nodes.add(yardFight);
        task.nodes.add(sweep("yard_loot", "bedtime_gate"));

        task.nodes.add(carrying("bed_gate", "minecraft:white_bed", 1, "turn_in", "shear"));
        TaskNode shear = node("shear", "huntsheep", Map.of(
                "count", "3",
                "weapon", "Sword",
                "craft_weapon", "true"));
        shear.onSuccess = "shear_loot";
        shear.onFailure = "turn_in";
        task.nodes.add(shear);
        task.nodes.add(sweep("shear_loot", "turn_in"));

        TaskNode turnIn = node("turn_in", "sleep", Map.of(
                "wait_for_night", "true",
                "reclaim", "true",
                "radius", "32"));
        turnIn.onSuccess = "morning_gate";
        turnIn.onFailure = "morning_gate";
        task.nodes.add(turnIn);

        // Sleep reports success whether or not the night actually passed - another player awake on
        // the server is enough to keep it. Asking the clock afterwards is how the job tells the
        // difference, and the answer decides between packing up and standing watch until dawn.
        task.nodes.add(timeIs("morning_gate", "Day", "morning_stash", "yard_watch"));
        task.nodes.add(store("morning_stash", "All", "night_end"));
        task.nodes.add(end("night_end"));

        // Everything that could walk off looking for its work gets the fence: a lake, a herd, a
        // tree line, a flock. The yard fight does not, because the yard is already the property.
        TaskNode fence = stayNear("fence", 64);
        protectWith(fence, cast, shoreHunt, choreWood, shear);
        task.nodes.add(fence);

        addSafetyCircuit(task);
        return task;
    }

    // 5. The long descent, in one job: stone tools to iron to diamond to obsidian to a lit portal.

    /**
     * Everything underground, once.
     *
     * <p>This was three jobs that differed only in which ore they named, which taught the shape
     * three times and the game once. As one descent it reads as the trip it actually is, and the
     * vocabulary it adds has room to matter: a job called by name when the pack is empty, one Find
     * whose choice travels to four mining cards through data wires, Select from Inventory used as a
     * durability condition, and a clock check that refuses to hunt endermen at noon.</p>
     */
    private static TaskGraph stoneToPortal() {
        TaskGraph task = seeded(PORTAL_JOB, PORTAL_ID);
        task.nodes.add(start("kit_gate"));

        // Nothing below an iron pickaxe touches diamond, and nothing below stone touches iron, so
        // the descent starts by making sure the first rung is under it. Run Task fails cleanly when
        // job 2 has been renamed or deleted, and the Fail wire forges the kit in place instead.
        task.nodes.add(carrying("kit_gate", "minecraft:stone_pickaxe", 1, "lunch_gate", "earn_kit"));
        TaskNode earnKit = node("earn_kit", "task", Map.of("name", STONE_JOB));
        earnKit.onSuccess = "lunch_gate";
        earnKit.onFailure = "forge_kit";
        task.nodes.add(earnKit);
        task.nodes.add(forge("forge_kit", "Pickaxe", "Stone", "forge_blade", "lunch_gate"));
        task.nodes.add(forge("forge_blade", "Sword", "Stone", "lunch_gate", "lunch_gate"));

        task.nodes.add(hungry("lunch_gate", 12, "lunch", "iron_gate"));
        task.nodes.add(meal("lunch", 18, "iron_gate"));

        // Iron: look, then dig, then count. Both exits from the quota loop are bounded - a
        // stripmine that cannot start hands the job to coal, an exhausted search smelts what it
        // has - so an unlucky biome ends the leg instead of circling underground.
        task.nodes.add(carrying("iron_gate", "minecraft:iron_ingot", 12, "deep_food", "iron_scan"));
        task.nodes.add(find("iron_scan", IRON_ORES, 64, -64, 72, "iron_mine", "iron_dig"));
        TaskNode ironDig = branchMine("iron_dig", IRON_ORES, 12, 24, 4, "iron_mine", "coal_run");
        TaskNode ironMine = mine("iron_mine", IRON_ORES, 64, -64, 72, 18, true,
                "iron_loot", "iron_roam");
        TaskNode ironRoam = search("iron_roam", IRON_ORES, 5, "iron_mine", "iron_smelt");
        task.nodes.add(ironDig);
        task.nodes.add(ironMine);
        task.nodes.add(ironRoam);
        task.nodes.add(sweep("iron_loot", "iron_quota"));
        task.nodes.add(carrying("iron_quota", "minecraft:raw_iron", 16, "coal_run", "iron_dig"));

        // A furnace burns something. Coal is on the way up and pays for the smelt twice over.
        TaskNode coalRun = mine("coal_run", COAL_ORES, 48, -64, 96, 16, true,
                "coal_loot", "iron_smelt");
        task.nodes.add(coalRun);
        task.nodes.add(sweep("coal_loot", "iron_smelt"));
        TaskNode smelt = node("iron_smelt", "smelt", Map.of(
                "input", "Raw Iron",
                "count", "16"));
        smelt.onSuccess = "iron_pick";
        smelt.onFailure = "iron_pick";
        task.nodes.add(smelt);
        task.nodes.add(forge("iron_pick", "Pickaxe", "Iron", "iron_sword", "deep_food"));
        task.nodes.add(forge("iron_sword", "Sword", "Iron", "deep_food", "deep_food"));

        // Diamond. One Find decides what "ore" means for the rest of the descent, and its Blocks
        // output feeds both stripmines, both Mine cards and the search between them.
        task.nodes.add(hungry("deep_food", 10, "deep_eat", "diamond_scan"));
        task.nodes.add(meal("deep_eat", 18, "diamond_scan"));

        TaskNode scan = find("diamond_scan", DIAMOND_ORES, 48, -64, 16, "diamond_mine", "branch_one");
        TaskNode branchOne = branchMine("branch_one", DIAMOND_ORES, -59, 24, 4,
                "diamond_mine", "deep_ore_run");
        TaskNode diamondMine = mine("diamond_mine", DIAMOND_ORES, 48, -64, 16, 8, false,
                "diamond_loot", "diamond_roam");
        TaskNode diamondRoam = search("diamond_roam", DIAMOND_ORES, 4,
                "diamond_mine", "diamond_count");
        task.nodes.add(scan);
        task.nodes.add(branchOne);
        task.nodes.add(diamondMine);
        task.nodes.add(diamondRoam);
        task.nodes.add(sweep("diamond_loot", "diamond_count"));
        task.nodes.add(carrying("diamond_count", "minecraft:diamond", 8,
                "diamond_pick", "branch_two"));

        // Short pass first, long pass only if the short one came up empty.
        TaskNode branchTwo = branchMine("branch_two", DIAMOND_ORES, -59, 32, 8,
                "mine_again", "deep_ore_run");
        TaskNode mineAgain = mine("mine_again", DIAMOND_ORES, 48, -64, 16, 8, false,
                "loot_again", "deep_ore_run");
        task.nodes.add(branchTwo);
        task.nodes.add(mineAgain);
        task.nodes.add(sweep("loot_again", "diamond_pick"));

        shareData(scan, "targets", branchOne, "target");
        shareData(scan, "targets", diamondMine, "targets");
        shareData(scan, "targets", diamondRoam, "targets");
        shareData(scan, "targets", branchTwo, "target");
        shareData(scan, "targets", mineAgain, "targets");

        task.nodes.add(forge("diamond_pick", "Pickaxe", "Diamond", "diamond_sword", "deep_ore_run"));
        task.nodes.add(forge("diamond_sword", "Sword", "Diamond", "deep_ore_run", "deep_ore_run"));

        // Already standing on the diamond layer, so the redstone, lapis and gold on the way back
        // up cost nothing but the walk.
        TaskNode deepOreRun = mine("deep_ore_run", DEEP_ORES, 48, -64, 32, 24, false,
                "deep_ore_loot", "pick_wear");
        task.nodes.add(deepOreRun);
        task.nodes.add(sweep("deep_ore_loot", "pick_wear"));

        // Select from Inventory used as a condition rather than as an action: it fails when every
        // carried diamond pickaxe is below a quarter of its durability. Ten obsidian is roughly
        // two hundred and fifty seconds of mining, and a pickaxe that breaks partway through
        // leaves the block behind as well as the tool.
        task.nodes.add(hold("pick_wear", "minecraft:diamond_pickaxe", 25,
                "obsidian_mine", "spare_pick"));
        task.nodes.add(forge("spare_pick", "Pickaxe", "Diamond", "obsidian_mine", "obsidian_mine"));

        // Natural obsidian sits where lava met water, which is a lake ceiling or a deep pocket, so
        // this card prospects rather than assuming the block is already in sight.
        TaskNode obsidianMine = mine("obsidian_mine", OBSIDIAN, 64, -64, 48, 14, true,
                "obsidian_loot", "lava_dig");
        TaskNode lavaDig = branchMine("lava_dig", OBSIDIAN, -12, 24, 6,
                "obsidian_mine", "obsidian_count");
        task.nodes.add(obsidianMine);
        task.nodes.add(lavaDig);
        task.nodes.add(sweep("obsidian_loot", "obsidian_count"));

        // Ten is the corner-saving frame's exact bill of materials, so it is the number to ask for.
        task.nodes.add(carrying("obsidian_count", "minecraft:obsidian", 10,
                "lighter_gate", "lava_dig"));

        // Build Nether Portal lights the frame with something already in the pack and does not
        // craft one. Asking first turns "nothing left to light the portal with" from a failed card
        // into a branch that simply carries on.
        task.nodes.add(carrying("lighter_gate", "minecraft:flint_and_steel", 1,
                "portal_build", "charge_gate"));
        task.nodes.add(carrying("charge_gate", "minecraft:fire_charge", 1,
                "portal_build", "pearl_gate"));

        TaskNode portal = node("portal_build", "portal", Map.of(
                "frame_mode", "10 obsidian + dirt/cobblestone corners",
                "corner_materials", "minecraft:dirt,minecraft:cobblestone"));
        portal.onSuccess = "portal_stash";
        portal.onFailure = "portal_retry";
        task.nodes.add(portal);

        // No dirt and no cobble left is not a reason to abandon ten obsidian. The speedrun shape
        // needs the same ten and no corners at all.
        TaskNode retry = node("portal_retry", "portal", Map.of(
                "frame_mode", "10 obsidian, open corners",
                "corner_materials", "minecraft:dirt,minecraft:cobblestone"));
        retry.onSuccess = "portal_stash";
        retry.onFailure = "portal_stash";
        task.nodes.add(retry);

        task.nodes.add(store("portal_stash", "Ores", "pearl_gate"));

        // Twelve pearls is what an End portal frame wants, and endermen are a night job. Asking the
        // clock is the difference between a hunt and a walk: at noon there is nothing to find, and
        // the card would spend its whole roam proving it.
        task.nodes.add(carrying("pearl_gate", "minecraft:ender_pearl", 12,
                "haul_stash", "night_gate"));
        task.nodes.add(timeIs("night_gate", "Night", "pearl_hunt", "haul_stash"));
        TaskNode pearls = node("pearl_hunt", "huntendermen", Map.of(
                "count", "12",
                "enderman_safety", "Auto",
                "use_shield", "true",
                "craft_shield", "true",
                "weapon", "Sword",
                "craft_weapon", "true"));
        pearls.onSuccess = "pearl_loot";
        pearls.onFailure = "pearl_loot";
        task.nodes.add(pearls);
        task.nodes.add(sweep("pearl_loot", 24, "haul_stash"));

        task.nodes.add(store("haul_stash", "All", "portal_end"));
        task.nodes.add(end("portal_end"));

        // Thirteen of this job's cards are underground or in a fight. Ticking a While pin on each
        // of them would be thirteen cables into one node; the Always circuit is one.
        addSafetyCircuit(task);
        return task;
    }

    // 6. The long one: the whole ladder in a single graph, ending on the dragon.

    /**
     * Seventy-eight cards, from punching a tree to leaving the End.
     *
     * <p>Bands A to E are jobs 1 to 5 rewritten as one forward-running chain, so a gate that
     * passes skips the leg that would have supplied it and the work never repeats. Band F hands
     * the stocked pack to Complete the Game, which reads the inventory and resumes at the furthest
     * rung it can prove - that is the whole reason the first five bands are worth running rather
     * than pressing the one card on its own.</p>
     *
     * <p>Everything below the primary lane runs beside it rather than after it: a Self
     * Preservation guard on every card that goes underground or swings a sword, a Pulse that eats
     * every forty-five seconds, a second Pulse that sweeps up drops the main lane walked past, an
     * Observer on the mission's End that counts both its edges before releasing a three-way
     * debrief, and a Button wired to eat and pause when a run needs stopping by hand.</p>
     */
    private static TaskGraph newWorldToDragon() {
        TaskGraph task = seeded(DRAGON_JOB, DRAGON_ID);

        task.nodes.add(start("a_chop"));

        // Band A - wood. Nothing in the pack, so the first card has to be one that needs nothing.
        task.nodes.add(chop("a_chop", 16, "a_loot", "a_tree_search"));
        task.nodes.add(search("a_tree_search", LOGS, 5, "a_chop", "a_loot"));
        task.nodes.add(sweep("a_loot", "a_axe"));
        task.nodes.add(forge("a_axe", "Axe", "Wooden", "a_pick", "a_pick"));
        task.nodes.add(forge("a_pick", "Pickaxe", "Wooden", "a_top_up", "a_top_up"));
        task.nodes.add(chop("a_top_up", 16, "a_top_loot", "b_stone_mine"));
        task.nodes.add(sweep("a_top_loot", "b_stone_mine"));

        // Band B - stone and the coal that lights everything below it.
        task.nodes.add(mine("b_stone_mine", STONE, 32, -64, 320, 32, false,
                "b_stone_loot", "b_stone_search"));
        task.nodes.add(search("b_stone_search", STONE, 4, "b_stone_mine", "b_stone_loot"));
        task.nodes.add(sweep("b_stone_loot", "b_pick"));
        task.nodes.add(forge("b_pick", "Pickaxe", "Stone", "b_axe", "b_axe"));
        task.nodes.add(forge("b_axe", "Axe", "Stone", "b_sword", "b_sword"));
        task.nodes.add(forge("b_sword", "Sword", "Stone", "b_coal", "b_coal"));
        task.nodes.add(mine("b_coal", COAL_ORES, 48, -64, 96, 16, true, "b_coal_loot", "c_food_gate"));
        task.nodes.add(sweep("b_coal_loot", "c_food_gate"));

        // Band C - food, with three sources tried in order of how long each one takes.
        task.nodes.add(hungry("c_food_gate", 14, "c_eat", "d_iron_gate"));
        task.nodes.add(meal("c_eat", 18, "d_iron_gate", "c_hunt"));
        task.nodes.add(hunt("c_hunt", LIVESTOCK, 32, "c_hunt_loot", "c_fish"));
        // One catch, not a fishing trip: with auto recast on, Fish never reports Success, and a
        // band that never finishes is a dragon run that never starts.
        TaskNode fish = node("c_fish", "fish", Map.of("auto_recast", "false"));
        fish.onSuccess = "c_hunt_loot";
        fish.onFailure = "d_iron_gate";
        task.nodes.add(fish);
        task.nodes.add(sweep("c_hunt_loot", "c_eat_again"));
        task.nodes.add(meal("c_eat_again", 18, "d_iron_gate"));

        // Band D - iron, as job 3 does it: look, then dig, then count.
        task.nodes.add(carrying("d_iron_gate", "minecraft:iron_ingot", 12,
                "e_deep_gate", "d_iron_scan"));
        task.nodes.add(find("d_iron_scan", IRON_ORES, 64, -64, 72, "d_iron_mine", "d_iron_dig"));
        task.nodes.add(branchMine("d_iron_dig", IRON_ORES, 12, 24, 4, "d_iron_mine", "d_iron_smelt"));
        task.nodes.add(mine("d_iron_mine", IRON_ORES, 64, -64, 72, 18, true,
                "d_iron_loot", "d_iron_roam"));
        task.nodes.add(search("d_iron_roam", IRON_ORES, 5, "d_iron_mine", "d_iron_smelt"));
        task.nodes.add(sweep("d_iron_loot", "d_iron_quota"));
        task.nodes.add(carrying("d_iron_quota", "minecraft:raw_iron", 18, "d_iron_smelt", "d_iron_dig"));
        TaskNode smelt = node("d_iron_smelt", "smelt", Map.of(
                "input", "Raw Iron",
                "count", "18"));
        smelt.onSuccess = "d_iron_pick";
        smelt.onFailure = "d_iron_pick";
        task.nodes.add(smelt);
        task.nodes.add(forge("d_iron_pick", "Pickaxe", "Iron", "d_iron_sword", "e_deep_gate"));
        task.nodes.add(forge("d_iron_sword", "Sword", "Iron", "e_deep_gate", "e_deep_gate"));

        // Band E - diamond, then the obsidian only diamond can cut.
        task.nodes.add(carrying("e_deep_gate", "minecraft:diamond", 3,
                "e_diamond_pick", "e_deep_food"));
        task.nodes.add(hungry("e_deep_food", 10, "e_deep_eat", "e_scan"));
        task.nodes.add(meal("e_deep_eat", 18, "e_scan"));
        task.nodes.add(find("e_scan", DIAMOND_ORES, 48, -64, 16, "e_mine", "e_branch_one"));
        task.nodes.add(branchMine("e_branch_one", DIAMOND_ORES, -59, 24, 4, "e_mine", "e_branch_two"));
        task.nodes.add(mine("e_mine", DIAMOND_ORES, 48, -64, 16, 8, false, "e_loot", "e_roam"));
        task.nodes.add(search("e_roam", DIAMOND_ORES, 4, "e_mine", "e_count"));
        task.nodes.add(sweep("e_loot", "e_count"));

        // Three diamonds is a pickaxe. That is all this band actually owes the rest of the job.
        task.nodes.add(carrying("e_count", "minecraft:diamond", 3, "e_diamond_pick", "e_branch_two"));
        task.nodes.add(branchMine("e_branch_two", DIAMOND_ORES, -59, 32, 8,
                "e_mine_again", "e_diamond_pick"));
        task.nodes.add(mine("e_mine_again", DIAMOND_ORES, 48, -64, 16, 8, false,
                "e_loot_again", "e_diamond_pick"));
        task.nodes.add(sweep("e_loot_again", "e_diamond_pick"));
        task.nodes.add(forge("e_diamond_pick", "Pickaxe", "Diamond",
                "e_obsidian_mine", "e_obsidian_mine"));
        task.nodes.add(mine("e_obsidian_mine", OBSIDIAN, 64, -64, 48, 14, true,
                "e_obsidian_loot", "f_preflight"));
        task.nodes.add(sweep("e_obsidian_loot", "f_preflight"));

        // Band F - the run. Complete the Game reads the pack and resumes at the furthest rung it
        // can prove, so five bands of preparation are five phases it does not have to repeat.
        task.nodes.add(hungry("f_preflight", 12, "f_preflight_eat", "f_launch"));
        task.nodes.add(meal("f_preflight_eat", 20, "f_launch"));

        TaskNode launch = node("f_launch", "completegame", Map.of());
        // Killing the dragon is a milestone, not the end of the mission: there is still a floor
        // covered in drops and a pack to empty.
        launch.onSuccess = "f_win_loot";
        launch.onFailure = "f_win_loot";
        task.nodes.add(launch);

        task.nodes.add(sweep("f_win_loot", 32, "f_win_stash"));
        task.nodes.add(store("f_win_stash", "All", "mission_end"));
        task.nodes.add(end("mission_end"));

        // One circuit for the whole run, including the End - where the cards belong to Complete
        // the Game and there is no While pin to tick even if you wanted to.
        addSafetyCircuit(task);

        // A clock, not a step: forty-five seconds is short enough to catch the slide from full to
        // starving and long enough not to interrupt a swing.
        TaskNode fieldPulse = node("field_pulse", TaskNode.PULSE_COMMAND, Map.of());
        fieldPulse.alwaysIntervalSeconds = 45;
        fieldPulse.alwaysTargets.add("field_hunger");
        task.nodes.add(fieldPulse);
        task.nodes.add(hungry("field_hunger", 8, "field_eat", "field_end"));
        task.nodes.add(meal("field_eat", 16, "field_end"));
        task.nodes.add(end("field_end"));

        // A broom. Mining cards stop at their limit, not at the last dropped block, so a periodic
        // short-range Loot picks up what the lane walked past. It is the one support circuit that
        // can move the bot, which is why the radius is eight and the rate is a minute and a half:
        // far enough to reach a dropped stack, short enough not to walk out of a dragon fight.
        TaskNode sweepPulse = node("sweep_pulse", TaskNode.PULSE_COMMAND, Map.of());
        sweepPulse.alwaysIntervalSeconds = 90;
        sweepPulse.alwaysTargets.add("sweep_loot");
        task.nodes.add(sweepPulse);
        task.nodes.add(sweep("sweep_loot", 8, "sweep_end"));
        task.nodes.add(end("sweep_end"));

        // End lights once and then goes dark. Counting both edges holds the debrief until the
        // primary lane has genuinely finished rather than merely reached its last card.
        TaskNode missionWatch = node("mission_watch", TaskNode.OBSERVER_COMMAND, Map.of());
        missionWatch.observedNodeId = "mission_end";
        missionWatch.signalLinks.add(pulseLink(0, "closeout_counter"));
        task.nodes.add(missionWatch);

        TaskNode closeoutCounter = node("closeout_counter", TaskNode.COUNTER_COMMAND,
                Map.of("count", "2"));
        closeoutCounter.signalLinks.add(relayLink(0, "closeout_hub", 0));
        task.nodes.add(closeoutCounter);

        TaskNode closeoutHub = relay("closeout_hub", 1, 3);
        closeoutHub.signalLinks.add(pulseLink(0, "debrief_loot"));
        closeoutHub.signalLinks.add(pulseLink(1, "debrief_stash"));
        closeoutHub.signalLinks.add(pulseLink(2, "debrief_delay"));
        task.nodes.add(closeoutHub);

        task.nodes.add(sweep("debrief_loot", 32, "debrief_loot_end"));
        task.nodes.add(end("debrief_loot_end"));
        task.nodes.add(store("debrief_stash", "All", "debrief_stash_end"));
        task.nodes.add(end("debrief_stash_end"));

        TaskNode debriefDelay = node("debrief_delay", TaskNode.TIMER_COMMAND,
                Map.of("seconds", "5"));
        debriefDelay.signalLinks.add(pulseLink(0, "debrief_pause"));
        task.nodes.add(debriefDelay);
        TaskNode debriefPause = node("debrief_pause", "pause_game", Map.of());
        debriefPause.onSuccess = "debrief_pause_end";
        debriefPause.onFailure = "debrief_pause_end";
        task.nodes.add(debriefPause);
        task.nodes.add(end("debrief_pause_end"));

        // The brake. A pressed Button eats immediately and pauses a second later, which is long
        // enough for the food to land and short enough to be a brake.
        TaskNode panicButton = node("panic_button", TaskNode.BUTTON_COMMAND, Map.of());
        panicButton.signalLinks.add(relayLink(0, "panic_hub", 0));
        task.nodes.add(panicButton);

        TaskNode panicHub = relay("panic_hub", 1, 2);
        panicHub.signalLinks.add(pulseLink(0, "panic_eat"));
        panicHub.signalLinks.add(pulseLink(1, "panic_delay"));
        task.nodes.add(panicHub);

        task.nodes.add(meal("panic_eat", 20, "panic_eat_end"));
        task.nodes.add(end("panic_eat_end"));

        TaskNode panicDelay = node("panic_delay", TaskNode.TIMER_COMMAND, Map.of("seconds", "1"));
        panicDelay.signalLinks.add(pulseLink(0, "panic_pause"));
        task.nodes.add(panicDelay);
        TaskNode panicPause = node("panic_pause", "pause_game", Map.of());
        panicPause.onSuccess = "panic_pause_end";
        panicPause.onFailure = "panic_pause_end";
        task.nodes.add(panicPause);
        task.nodes.add(end("panic_pause_end"));

        return task;
    }

    // --- card shorthands -----------------------------------------------------
    // Every one of these builds an ordinary node with ordinary parameters. They exist so a band
    // reads as the sentence it is meant to be - "chop sixteen, sweep, forge a stone pickaxe" -
    // rather than as forty lines of map literals.

    private static TaskNode chop(String id, int logs, String onSuccess, String onFailure) {
        TaskNode node = node(id, "chop", Map.of(
                "radius", "64",
                "limit", Integer.toString(logs)));
        node.onSuccess = onSuccess;
        node.onFailure = onFailure;
        return node;
    }

    private static TaskNode mine(String id, String targets, int radius, int yMin, int yMax,
                                 int limit, boolean prospect, String onSuccess, String onFailure) {
        TaskNode node = node(id, "mine", Map.of(
                "targets", targets,
                "radius", Integer.toString(radius),
                "y_min", Integer.toString(yMin),
                "y_max", Integer.toString(yMax),
                "limit", Integer.toString(limit),
                "auto_tool", "true",
                "prospect", Boolean.toString(prospect),
                "check_around", "true"));
        node.onSuccess = onSuccess;
        node.onFailure = onFailure;
        return node;
    }

    /** Walk-and-look. Succeeds the moment it sees one, which is what makes it a retry and not a loop. */
    private static TaskNode search(String id, String targets, int attempts,
                                   String onSuccess, String onFailure) {
        TaskNode node = node(id, "explore", Map.of(
                "targets", targets,
                "radius", "48",
                "attempts", Integer.toString(attempts),
                "step", "12",
                "check_around", "true",
                "scan_style", "Glance ahead",
                "smart_direction", "true"));
        node.onSuccess = onSuccess;
        node.onFailure = onFailure;
        return node;
    }

    private static TaskNode find(String id, String targets, int radius, int yMin, int yMax,
                                 String onSuccess, String onFailure) {
        TaskNode node = node(id, "find", Map.of(
                "target_kind", "Blocks",
                "targets", targets,
                "radius", Integer.toString(radius),
                "y_min", Integer.toString(yMin),
                "y_max", Integer.toString(yMax),
                "prospect", "false",
                "check_around", "true"));
        node.onSuccess = onSuccess;
        node.onFailure = onFailure;
        return node;
    }

    private static TaskNode findMobs(String id, String entities, int radius,
                                     String onSuccess, String onFailure) {
        TaskNode node = node(id, "find", Map.of(
                "target_kind", "Mobs",
                "entities", entities,
                "radius", Integer.toString(radius),
                "check_around", "true"));
        node.onSuccess = onSuccess;
        node.onFailure = onFailure;
        return node;
    }

    private static TaskNode branchMine(String id, String target, int yLevel, int branchLength,
                                       int branches, String onSuccess, String onFailure) {
        TaskNode node = node(id, "stripmine", Map.of(
                "target", target,
                "y_level", Integer.toString(yLevel),
                "branch_length", Integer.toString(branchLength),
                "spacing", "3",
                "branches", Integer.toString(branches)));
        node.onSuccess = onSuccess;
        node.onFailure = onFailure;
        return node;
    }

    /** Get Tools always names its tool and tier: a stored node that omits them inherits the palette. */
    private static TaskNode forge(String id, String tool, String material,
                                  String onSuccess, String onFailure) {
        TaskNode node = node(id, "gettool", Map.of(
                "tool", tool,
                "material", material,
                "check_around", "true"));
        node.onSuccess = onSuccess;
        node.onFailure = onFailure;
        return node;
    }

    private static TaskNode carrying(String id, String item, int count, String yes, String no) {
        TaskNode node = node(id, "check_item", Map.of(
                "item", item,
                "comparison", "At least",
                "count", Integer.toString(count)));
        node.onSuccess = yes;
        node.onFailure = no;
        return node;
    }

    /** Asks the overworld clock a yes-or-no question; see {@link com.etka.lune.bot.util.WorldClock}. */
    private static TaskNode timeIs(String id, String phase, String yes, String no) {
        TaskNode node = node(id, "check_time", Map.of("phase", phase));
        node.onSuccess = yes;
        node.onFailure = no;
        return node;
    }

    /**
     * Puts a carried item in the main hand, or fails when no copy is good enough.
     *
     * <p>Both of its uses in the defaults are as a condition rather than as an action, which is
     * why the Fail wire is the interesting one: no fishing rod at all, or no diamond pickaxe with
     * enough life left in it to be worth taking to an obsidian face.</p>
     */
    private static TaskNode hold(String id, String item, int minDurability,
                                 String yes, String no) {
        TaskNode node = node(id, "select_item", Map.of(
                "item", item,
                "enchanting", "Any",
                "hand", "Main hand",
                "min_durability", Integer.toString(minDurability),
                "prefer", "Most durability"));
        node.onSuccess = yes;
        node.onFailure = no;
        return node;
    }

    /**
     * A boundary companion: walks the bot back when the work wanders off the property.
     *
     * <p>The waypoint parameter is deliberately left unset. It only applies when the anchor is a
     * saved location, and a seeded job cannot know the names of waypoints the player has not made
     * yet - so the anchor is named explicitly and the unused field is left to the palette.</p>
     */
    private static TaskNode stayNear(String id, int radius) {
        TaskNode fence = node(id, "stay_near", Map.of(
                // A choice value, not a label: the card that reads it compares against
                // this exact string, and the palette translates only how it is drawn.
                "anchor", "Where the run started",
                "radius", Integer.toString(radius)));
        // A While companion runs beside its card, so its repeat box is not a lifetime.
        fence.repeat = 0;
        return fence;
    }

    private static TaskNode hungry(String id, int threshold, String yes, String no) {
        TaskNode node = node(id, "check_player", Map.of(
                "metric", "Hunger",
                "comparison", "At most",
                "threshold", Integer.toString(threshold)));
        node.onSuccess = yes;
        node.onFailure = no;
        return node;
    }

    private static TaskNode meal(String id, int minimumFood, String next) {
        return meal(id, minimumFood, next, next);
    }

    private static TaskNode meal(String id, int minimumFood, String onSuccess, String onFailure) {
        TaskNode node = node(id, "eat", Map.of("minimum_food", Integer.toString(minimumFood)));
        node.onSuccess = onSuccess;
        node.onFailure = onFailure;
        return node;
    }

    private static TaskNode hunt(String id, String mobs, int radius,
                                 String onSuccess, String onFailure) {
        TaskNode node = node(id, "kill", Map.of(
                "targets", mobs,
                "radius", Integer.toString(radius),
                "fire_resistance", "false",
                "use_shield", "false",
                "craft_shield", "false",
                "weapon", "Sword",
                "craft_weapon", "true",
                "enderman_safety", "Auto"));
        node.onSuccess = onSuccess;
        node.onFailure = onFailure;
        return node;
    }

    private static TaskNode fight(String id, String mobs, int radius,
                                  String onSuccess, String onFailure) {
        TaskNode node = node(id, "kill", Map.of(
                "targets", mobs,
                "radius", Integer.toString(radius),
                "fire_resistance", "false",
                "use_shield", "true",
                "craft_shield", "true",
                "weapon", "Automatic",
                "craft_weapon", "true",
                "enderman_safety", "Auto"));
        node.onSuccess = onSuccess;
        node.onFailure = onFailure;
        return node;
    }

    /** A pickup pass that hands the job forward whether or not it found anything. */
    private static TaskNode sweep(String id, String next) {
        return sweep(id, 16, next);
    }

    private static TaskNode sweep(String id, int radius, String next) {
        TaskNode loot = node(id, "loot", Map.of("radius", Integer.toString(radius)));
        loot.onSuccess = next;
        loot.onFailure = next;
        return loot;
    }

    /** An optional drop-off: a base with no container yet should not end the job early. */
    private static TaskNode store(String id, String filter, String next) {
        TaskNode deposit = node(id, "deposit", Map.of(
                "filter", filter,
                "radius", "16",
                "optional", "true"));
        deposit.onSuccess = next;
        deposit.onFailure = next;
        return deposit;
    }

    private static TaskNode end(String id) {
        return node(id, TaskNode.END_COMMAND, Map.of());
    }

    /**
     * Adds the safety monitor as its own circuit, wired to nothing in the main lane.
     *
     * <p>An Always source holding one Self Preservation card. Hanging the guard off a While pin
     * instead works and is what the editor offers first, but it scales badly in both directions: a
     * job with ten protected cards ends up with ten cables converging on one node and a canvas
     * nobody can read, and - worse - it only protects the cards somebody remembered to tick. Always
     * is held on for the whole run, so one wire covers everything the job does, including the leg
     * added next week.</p>
     *
     * <p>Stay Near is deliberately not moved here. A boundary that applies to every card would
     * also stop the bot chasing a creeper twenty blocks past the fence, which is exactly the moment
     * you want it to. That one belongs on the cards it is meant for.</p>
     */
    private static void addSafetyCircuit(TaskGraph task) {
        TaskNode clock = node("safety_clock", TaskNode.ALWAYS_COMMAND, Map.of());
        clock.alwaysTargets.add("guard");
        task.nodes.add(clock);

        TaskNode guard = node("guard", "self_preservation", safetyParams());
        // A companion runs beside the work rather than after it, so its repeat box is not a
        // lifetime and x1 would be wrong.
        guard.repeat = 0;
        task.nodes.add(guard);
    }

    /**
     * The explicit entry marker.
     *
     * <p>{@link TaskWiring#addExplicitStart} only adds one to a job that has no clock, because a
     * clock is already a power source. Every job below that owns an Always or a Pulse therefore
     * writes its own, so the main lane still has one obvious beginning.</p>
     */
    private static TaskNode start(String firstCard) {
        TaskNode start = node("start", TaskNode.START_COMMAND, Map.of());
        start.onSuccess = firstCard;
        return start;
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

    /**
     * Hangs a companion circuit off the While pin of the cards it applies to.
     *
     * <p>Only Stay Near uses this now. A companion that should cover everything belongs on an
     * Always source instead - see {@link #addSafetyCircuit} - and a While pin is for the case
     * where "everything" is the wrong answer.</p>
     */
    private static void protectWith(TaskNode companion, TaskNode... covered) {
        for (TaskNode node : covered) {
            node.onWhile = companion.id;
            // While is execution-significant even though its output is optional in the editor.
            // Seeded jobs should show the cable instead of an orphaned companion card.
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

    /**
     * Positions the seeded jobs as diagrams.
     *
     * <p>One lane, running left to right, and it never wraps. Laying a long job out as bands that
     * each restarted at column zero made every band boundary a cable sweeping right back across
     * the whole canvas - a typewriter carriage return drawn in dotted line. The lane is now as wide
     * as the job is long, detours hang directly beneath the card that chooses them, and the
     * independent circuits get their own rows underneath.</p>
     */
    private static void teachingLayout(TaskGraph task) {
        switch (task.seededId) {
            case LOGS_ID -> {
                row(task, 0, "start", "chop_12", "logs_loot", "wooden_axe", "axe_top_up",
                        "logs_stash", "wood_end");
                below(task, "chop_12", 1, "tree_search");
            }
            case STONE_ID -> {
                row(task, 0, "start", "chop_12", "logs_loot", "wooden_pick", "wooden_axe",
                        "stone_20", "stone_loot", "stone_count", "stone_pick", "stone_axe",
                        "stone_sword", "kit_stash", "kit_end");
                below(task, "chop_12", 1, "tree_search");
                below(task, "stone_20", 1, "stone_search");
                below(task, "stone_count", 1, "stone_top_up", "stone_more_loot");
            }
            case HOMESTEAD_ID -> {
                row(task, 0, "start", "day_gate", "field", "field_loot", "store_crops", "larder",
                        "wood_run", "wood_loot", "store_all", "yard_watch", "arm_gate", "drive_off",
                        "drive_loot", "supper_gate", "supper", "home_end");
                below(task, "larder", 1, "stock_meat", "meat_loot");
                below(task, "arm_gate", 1, "forge_sword");
                below(task, "field", 2, "fence");
                rowAt(task, 3, 0, "safety_clock", "guard");
            }
            case NIGHTFALL_ID -> {
                row(task, 0, "start", "day_gate", "hunger_gate", "rod_gate", "cast", "catch_loot",
                        "stash_catch", "bedtime_gate", "bed_gate", "turn_in", "morning_gate",
                        "morning_stash", "night_end");
                below(task, "hunger_gate", 1, "eat_catch");
                below(task, "rod_gate", 1, "shore_hunt");
                below(task, "bedtime_gate", 1, "chore_wood", "chore_loot", "yard_watch", "yard_arm",
                        "yard_fight", "yard_loot");
                below(task, "bed_gate", 2, "shear", "shear_loot");
                below(task, "yard_arm", 2, "yard_forge");
                below(task, "rod_gate", 3, "fence");
                rowAt(task, 4, 0, "safety_clock", "guard");
            }
            case PORTAL_ID -> {
                row(task, 0, "start", "kit_gate", "lunch_gate", "lunch", "iron_gate", "iron_scan",
                        "iron_mine", "iron_loot", "iron_quota", "coal_run", "coal_loot",
                        "iron_smelt", "iron_pick", "iron_sword", "deep_food", "deep_eat",
                        "diamond_scan", "diamond_mine", "diamond_loot", "diamond_count",
                        "diamond_pick", "diamond_sword", "deep_ore_run", "deep_ore_loot",
                        "pick_wear", "obsidian_mine", "obsidian_loot", "obsidian_count",
                        "lighter_gate", "charge_gate", "portal_build", "portal_stash", "pearl_gate",
                        "night_gate", "pearl_hunt", "pearl_loot", "haul_stash", "portal_end");
                below(task, "kit_gate", 1, "earn_kit");
                below(task, "kit_gate", 2, "forge_kit", "forge_blade");
                below(task, "iron_scan", 1, "iron_dig");
                below(task, "iron_mine", 1, "iron_roam");
                below(task, "diamond_scan", 1, "branch_one");
                below(task, "diamond_mine", 1, "diamond_roam");
                below(task, "diamond_count", 1, "branch_two", "mine_again", "loot_again");
                below(task, "pick_wear", 1, "spare_pick");
                below(task, "obsidian_mine", 1, "lava_dig");
                below(task, "portal_build", 1, "portal_retry");
                rowAt(task, 3, 0, "safety_clock", "guard");
            }
            case DRAGON_ID -> {
                row(task, 0, "start", "a_chop", "a_loot", "a_axe", "a_pick", "a_top_up",
                        "a_top_loot", "b_stone_mine", "b_stone_loot", "b_pick", "b_axe", "b_sword",
                        "b_coal", "b_coal_loot", "c_food_gate", "c_eat", "c_hunt_loot",
                        "c_eat_again", "d_iron_gate", "d_iron_scan", "d_iron_mine", "d_iron_loot",
                        "d_iron_quota", "d_iron_smelt", "d_iron_pick", "d_iron_sword",
                        "e_deep_gate", "e_deep_food", "e_scan", "e_mine", "e_loot", "e_count",
                        "e_diamond_pick", "e_obsidian_mine", "e_obsidian_loot", "f_preflight",
                        "f_preflight_eat", "f_launch", "f_win_loot", "f_win_stash", "mission_end");
                below(task, "a_chop", 1, "a_tree_search");
                below(task, "b_stone_mine", 1, "b_stone_search");
                below(task, "c_eat", 1, "c_hunt", "c_fish");
                below(task, "d_iron_scan", 1, "d_iron_dig");
                below(task, "d_iron_mine", 1, "d_iron_roam");
                below(task, "e_deep_food", 1, "e_deep_eat");
                below(task, "e_scan", 1, "e_branch_one");
                below(task, "e_mine", 1, "e_roam");
                below(task, "e_count", 1, "e_branch_two", "e_mine_again", "e_loot_again");

                // The debrief sits under the End it watches, so the Observer's cable is short.
                below(task, "mission_end", 3, "mission_watch", "closeout_counter", "closeout_hub",
                        "debrief_loot", "debrief_loot_end");
                below(task, "mission_end", 4, "debrief_stash");
                place(task, "debrief_stash_end", laneColumn(task, "mission_end") + 1, 4);
                below(task, "mission_end", 5, "debrief_delay", "debrief_pause",
                        "debrief_pause_end");

                // The independent circuits keep to the left, where they are not chasing the lane.
                rowAt(task, 3, 0, "safety_clock", "guard");
                rowAt(task, 4, 0, "field_pulse", "field_hunger", "field_eat", "field_end");
                rowAt(task, 5, 0, "sweep_pulse", "sweep_loot", "sweep_end");
                rowAt(task, 6, 0, "panic_button", "panic_hub", "panic_eat", "panic_eat_end");
                rowAt(task, 7, 2, "panic_delay", "panic_pause", "panic_pause_end");
            }
            case null, default -> {
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

    /**
     * Places a detour directly beneath the card that chooses it, running rightward from there.
     *
     * <p>Anchoring to the card rather than to a column number is what keeps the diagram honest
     * when the lane above it changes: insert one card into the main lane and every detour still
     * lines up under its own gate instead of under whatever ended up in that column.</p>
     */
    private static void below(TaskGraph task, String anchorId, int rowsDown, String... ids) {
        TaskNode anchor = task.nodeById(anchorId);
        if (anchor == null || anchor.editorX == null || anchor.editorY == null) {
            return;
        }
        for (int i = 0; i < ids.length; i++) {
            TaskNode node = task.nodeById(ids[i]);
            if (node != null) {
                node.editorX = anchor.editorX + i * TEACHING_STEP_X;
                node.editorY = anchor.editorY + rowsDown * TEACHING_STEP_Y;
            }
        }
    }

    /** The column an already-placed card occupies, for the rare detour that needs to do arithmetic. */
    private static int laneColumn(TaskGraph task, String id) {
        TaskNode node = task.nodeById(id);
        return node == null || node.editorX == null ? 0 : (node.editorX - 24) / TEACHING_STEP_X;
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
