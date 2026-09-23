package com.etka.lune.task;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fifteen jobs seeded when {@code config/lune-tasks.json} does not exist yet, and brought back
 * one by one from the Config tab. The last two are built on a compass mod's card, and are listed
 * only while that mod is installed ({@link TaskGraph#isListed}) - which is why they come last: a
 * pack without them sees one to thirteen with no gap.
 *
 * <p>They are a shelf in three tiers, read left to right like the game itself:</p>
 * <ul>
 *   <li><b>Demos, jobs 1 to 4.</b> A few minutes each, from an empty pack, and they end on their
 *       own. None of them owns an Always, Pulse, Observer or Button card, because any of those
 *       keeps a run alive after its last card - so their guard rides the While pins of the cards
 *       that work outdoors, and the run finishes and says so.</li>
 *   <li><b>Chores, jobs 5 to 9.</b> Hours of unattended work that stop by themselves. The long
 *       ones hold Self Preservation on an Always card for the whole shift, and a second Always card
 *       runs a Countdown that saves and leaves the world when the time is up; a full chest ends the
 *       shift the same way.</li>
 *   <li><b>Expeditions, jobs 10 to 15.</b> What the engine can be asked to do: walking back to a
 *       death, crossing into the Nether and back, the whole game in one graph, and the two jobs
 *       that lean on another mod's compass. The long ones end by pausing the world.</li>
 * </ul>
 *
 * <h2>Explained on the canvas</h2>
 *
 * <p>Every job carries sticky notes and card frames. The notes sit in a caption band above the
 * lane, each one over the section it explains, so a job reads left to right like a comic strip;
 * the frames name the sections. Both are drawn in the player's language: a note stores its English
 * as {@link TaskNote#text} and is drawn from {@code lune.task.note.} plus its {@link
 * TaskNote#seededId}, the same bargain as the job's own title. The English here must match the
 * English line, which a test checks.</p>
 *
 * <p>Every card, parameter and wire is available in the editor; the jobs have no private runtime
 * shortcuts. The one lane never wraps: however long a job is, it runs right, with detours hanging
 * under the card that chooses them and the circuits that run beside it in rows underneath.</p>
 */
public final class DefaultTasks {

    /** Cards are 124px wide; the seeded lanes leave room for cables and deliberate rightward flow. */
    private static final int STEP_X = 208;
    private static final int STEP_Y = 132;
    private static final int LEFT = 24;
    /**
     * The row the lane runs along. Low enough to leave a caption band above it - a note, then a
     * frame's title bar - so the first thing on screen at the canvas origin is the job's first
     * note rather than its first card.
     */
    static final int LANE_Y = 182;
    /** Where caption notes start and how tall they are: clear of every frame's title bar below. */
    static final int CAPTION_TOP = 20;
    static final int CAPTION_HEIGHT = 124;
    /**
     * Notes beside the circuits under the lane stop short of the gap below their row, which is
     * where a routed cable runs back along the gutter - a taller note would have cables drawn
     * through its last lines.
     */
    static final int ROW_NOTE_HEIGHT = 80;
    /** A note is a column wide less the gap between columns, so two never touch. */
    private static final int NOTE_GAP = STEP_X - TaskCanvas.CARD_WIDTH - 28;

    /** The editor's note palette: see {@code NodePalette.PAPERS}. */
    private static final int AMBER = 0;
    private static final int RED = 1;
    private static final int GREEN = 2;
    private static final int BLUE = 3;
    private static final int PURPLE = 4;
    private static final int SLATE = 5;

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
    private static final String ANCIENT_DEBRIS = "minecraft:ancient_debris";

    /** What a homestead grows, and what Harvest replants behind itself. */
    private static final String CROPS =
            "minecraft:wheat,minecraft:carrots,minecraft:potatoes,minecraft:beetroots";

    /** What a hungry bot can catch on the surface without a plan. */
    private static final String LIVESTOCK =
            "minecraft:cow,minecraft:pig,minecraft:sheep,minecraft:chicken";

    /** The four the bot is most likely to meet in its own yard after dark. */
    private static final String NIGHT_MOBS =
            "minecraft:zombie,minecraft:skeleton,minecraft:creeper,minecraft:spider";

    /** What a village has that a forest does not, for looking for one without a compass. */
    private static final String VILLAGE_SIGNS = "minecraft:bell,minecraft:hay_block";

    // Sounds from the game's own registry, one meaning each, so a job can be followed by ear.
    private static final String SOUND_START = "minecraft:block.note_block.chime";
    private static final String SOUND_STEP = "minecraft:entity.experience_orb.pickup";
    private static final String SOUND_DONE = "minecraft:ui.toast.challenge_complete";
    private static final String SOUND_PROBLEM = "minecraft:block.note_block.bass";
    private static final String SOUND_MORNING = "minecraft:block.bell.use";
    private static final String SOUND_SHIFT_OVER = "minecraft:block.note_block.pling";

    // Waypoint names are identifiers too: a Go to Waypoint card finds the place by this string in
    // every language, so they stay English the way job names do.
    private static final String CAMP = "Lumber Camp";
    private static final String QUARRY = "Quarry";
    private static final String FARM = "Homestead";
    private static final String POST = "Watch Post";
    private static final String TUNNEL_MOUTH = "Tunnel Entrance";
    private static final String RECOVERY_START = "Recovery Start";
    private static final String NETHER_SIDE = "Nether Portal";
    private static final String VILLAGE = "Village";
    private static final String HOME = "Home";
    private static final String GROVE = "Cherry Grove";

    /**
     * Job names, as they are saved.
     *
     * <p>English, and staying English: this is what the job is called on disk, what a Run Task card
     * elsewhere points at, and what {@link TaskStore#restoreMissingDefaults()} recognises. What the
     * player reads is the {@code lune.task.seeded.*} line the matching id opposite resolves to.</p>
     */
    static final String CHOP_WOOD = "1. Chop Wood";
    static final String STONE_TOOLS = "2. Stone Tools from Scratch";
    static final String GO_FISHING = "3. Go Fishing";
    static final String DIG_TUNNEL = "4. Dig a Tunnel";
    static final String LUMBER_CAMP = "5. Lumber Camp";
    static final String STONE_QUARRY = "6. Stone Quarry";
    static final String HOMESTEAD = "7. Homestead, Every Day";
    static final String SMELTERY = "8. Smeltery";
    static final String NIGHT_WATCH = "9. Night Watch";
    static final String STUFF_BACK = "10. Get My Stuff Back";
    static final String LIT_PORTAL = "11. Stone Tools to a Lit Portal";
    static final String ENDER_DRAGON = "12. New World to Ender Dragon";
    static final String NETHERITE = "13. Netherite from the Nether";
    static final String FIND_VILLAGE = "14. Find a Village";
    static final String CHERRY_TIMBER = "15. Cherry Grove Timber";

    /** The id each of those jobs is known by, once and for all, in every language. */
    static final String CHOP_WOOD_ID = "chop_wood";
    static final String STONE_TOOLS_ID = "stone_tools";
    static final String GO_FISHING_ID = "go_fishing";
    static final String DIG_TUNNEL_ID = "dig_tunnel";
    static final String LUMBER_CAMP_ID = "lumber_camp";
    static final String STONE_QUARRY_ID = "stone_quarry";
    static final String HOMESTEAD_ID = "homestead_day";
    static final String SMELTERY_ID = "smeltery";
    static final String NIGHT_WATCH_ID = "night_watch";
    static final String STUFF_BACK_ID = "stuff_back";
    static final String LIT_PORTAL_ID = "lit_portal";
    static final String ENDER_DRAGON_ID = "ender_dragon";
    static final String NETHERITE_ID = "netherite";
    static final String FIND_VILLAGE_ID = "find_village";
    static final String CHERRY_TIMBER_ID = "cherry_timber";

    /**
     * The English name each id shipped under, for reading saves that predate the id.
     *
     * <p>The first six are the ladder this shelf replaced. They are no longer seeded, but a player
     * who has been running them still has them on disk, and matching the old name to its old id is
     * what keeps their titles translated. Their ids differ from every new one, so a restore adds the
     * new shelf beside them rather than deciding it is already there.</p>
     */
    static final Map<String, String> SEEDED_NAMES = Map.ofEntries(
            Map.entry("1. Chop 12 Logs", "logs"),
            Map.entry("2. Wood, Pickaxe, 20 Stone", "stone"),
            Map.entry("3. Homestead: Farm and Guard", "homestead"),
            Map.entry("4. Fish Till Dusk, Then Sleep", "nightfall"),
            Map.entry("5. Stone Tools to a Lit Portal", "portal"),
            Map.entry("6. New World to Ender Dragon", "dragon"),
            Map.entry(CHOP_WOOD, CHOP_WOOD_ID),
            Map.entry(STONE_TOOLS, STONE_TOOLS_ID),
            Map.entry(GO_FISHING, GO_FISHING_ID),
            Map.entry(DIG_TUNNEL, DIG_TUNNEL_ID),
            Map.entry(LUMBER_CAMP, LUMBER_CAMP_ID),
            Map.entry(STONE_QUARRY, STONE_QUARRY_ID),
            Map.entry(HOMESTEAD, HOMESTEAD_ID),
            Map.entry(SMELTERY, SMELTERY_ID),
            Map.entry(NIGHT_WATCH, NIGHT_WATCH_ID),
            Map.entry(STUFF_BACK, STUFF_BACK_ID),
            Map.entry(LIT_PORTAL, LIT_PORTAL_ID),
            Map.entry(ENDER_DRAGON, ENDER_DRAGON_ID),
            Map.entry(NETHERITE, NETHERITE_ID),
            Map.entry(FIND_VILLAGE, FIND_VILLAGE_ID),
            Map.entry(CHERRY_TIMBER, CHERRY_TIMBER_ID));

    private DefaultTasks() {}

    /** A starter job, named for the disk and identified for everything else. */
    private static TaskGraph seeded(String name, String id) {
        TaskGraph task = new TaskGraph(name);
        task.seededId = id;
        return task;
    }

    /** Fresh graphs, so editing a seeded job never mutates a later restore. */
    public static List<TaskGraph> create() {
        List<TaskGraph> tasks = List.of(
                chopWood(), stoneTools(), goFishing(), digTunnel(),
                lumberCamp(), stoneQuarry(), homestead(), smeltery(), nightWatch(),
                stuffBack(), litPortal(), enderDragon(), netherite(), findVillage(),
                cherryTimber());
        tasks.forEach(DefaultTasks::routeReturnCables);
        return tasks;
    }

    // =============================================================================================
    // Demos: a few minutes each, from an empty pack, and they finish on their own.
    // =============================================================================================

    /** Job 1: the first thing a new player presses Run on. */
    private static TaskGraph chopWood() {
        TaskGraph task = seeded(CHOP_WOOD, CHOP_WOOD_ID);
        task.nodes.add(start("hello"));
        task.nodes.add(notify("hello", SOUND_START, "chop_hands"));

        // Chop Wood needs no tool at all - punching a tree is slow, not impossible - which is why
        // the whole shelf can start with nothing in the pack.
        task.nodes.add(chop("chop_hands", 6, "loot_hands", "look_for_trees"));
        task.nodes.add(search("look_for_trees", LOGS, 5, "chop_hands", "loot_hands"));
        task.nodes.add(sweep("loot_hands", "make_axe"));
        task.nodes.add(forge("make_axe", "Axe", "Wooden", "axe_made", "chop_axe"));
        task.nodes.add(notify("axe_made", SOUND_STEP, "chop_axe"));

        // With the axe in hand the same six logs cost about a third of the time. That difference
        // is the entire lesson of the job.
        task.nodes.add(chop("chop_axe", 6, "loot_axe", "loot_axe"));
        task.nodes.add(sweep("loot_axe", "done"));
        task.nodes.add(notify("done", SOUND_DONE, "end"));
        task.nodes.add(end("end"));
        task.nodes.add(guardOnPins(task, "chop_hands", "look_for_trees", "chop_axe"));

        Lane lane = new Lane(task);
        lane.plain("start", null, "hello");
        lane.band("wood_by_hand", "By hand", GREEN, cards("chop_hands", "loot_hands"),
                under("chop_hands", 1, "look_for_trees"));
        lane.band("tools", "Tools", BLUE, cards("make_axe", "axe_made"));
        lane.band("wood_with_axe", "With the axe", GREEN, cards("chop_axe", "loot_axe"));
        lane.plain("done", "end");
        lane.support("safety", "Safety", RED, 2, lane.start("tools"), "guard");

        caption(task, "intro", 0, 2, AMBER,
                "Start here. This job punches six logs by hand, makes a wooden axe from them and "
                        + "chops six more with it. It needs nothing in the pack and finishes in a "
                        + "few minutes. Each note explains the cards under it, left to right.");
        caption(task, "by_hand", lane.start("wood_by_hand"), 2, GREEN,
                "Chop Wood needs no tool: punching a tree is slow, not impossible. When no tree is "
                        + "in sight it fails, and the red Fail wire sends Explore to walk and look "
                        + "for one. Explore then hands back to Chop Wood.");
        caption(task, "axe", lane.start("tools"), 2, BLUE,
                "Get Tools makes a wooden axe from the logs just gathered, working out the planks, "
                        + "sticks and crafting table by itself. Notify plays a sound so you hear "
                        + "the moment it happens.");
        caption(task, "faster", lane.start("wood_with_axe"), 2, GREEN,
                "The same six logs again, now with the axe in hand: they take about a third of the "
                        + "time. Loot picks up anything that fell out of reach.");
        caption(task, "finish", lane.column("done"), 2, AMBER,
                "A last sound and the run finishes by itself. Next, try 2. Stone Tools from "
                        + "Scratch, which carries on from here.");
        rowNote(task, "guard", lane.start("tools") + 1, 2, 2, RED,
                "Self Preservation watches only while the cards wired to its While pins are "
                        + "running, so this job can still finish. Chores that run for hours use an "
                        + "Always card instead.");
        lane.finish();
        return task;
    }

    /** Job 2: from nothing to a stone kit, the rung everything else stands on. */
    private static TaskGraph stoneTools() {
        TaskGraph task = seeded(STONE_TOOLS, STONE_TOOLS_ID);
        task.nodes.add(start("chop"));
        task.nodes.add(chop("chop", 12, "logs_loot", "tree_search"));
        task.nodes.add(search("tree_search", LOGS, 5, "chop", "logs_loot"));
        task.nodes.add(sweep("logs_loot", "wooden_pick"));

        // Stone is not minable by hand, so the pickaxe has to exist before the mining card is
        // worth running. Fail still walks on, because Mine can provision its own tool.
        task.nodes.add(forge("wooden_pick", "Pickaxe", "Wooden", "stone", "stone"));
        task.nodes.add(mine("stone", STONE, 32, -64, 320, 20, false, "stone_loot", "stone_search"));
        task.nodes.add(search("stone_search", STONE, 4, "stone", "stone_loot"));
        task.nodes.add(sweep("stone_loot", "stone_count"));

        // Twenty is the number the job promises, so the job counts it rather than assuming the
        // mining card hit its limit. Anything short takes the top-up detour and comes back.
        task.nodes.add(carrying("stone_count", "minecraft:cobblestone", 20, "stone_pick", "top_up"));
        task.nodes.add(mine("top_up", STONE, 48, -64, 320, 12, false, "stone_pick", "stone_pick"));

        task.nodes.add(forge("stone_pick", "Pickaxe", "Stone", "stone_axe", "stone_axe"));
        task.nodes.add(forge("stone_axe", "Axe", "Stone", "stone_sword", "stone_sword"));
        task.nodes.add(forge("stone_sword", "Sword", "Stone", "done", "done"));
        task.nodes.add(notify("done", SOUND_DONE, "end"));
        task.nodes.add(end("end"));
        task.nodes.add(guardOnPins(task, "chop", "tree_search", "stone", "stone_search", "top_up"));

        Lane lane = new Lane(task);
        lane.plain("start", null);
        lane.band("wood", "Wood", GREEN, cards("chop", "logs_loot"), under("chop", 1, "tree_search"));
        lane.band("tools", "Tools", BLUE, cards("wooden_pick"));
        lane.band("stone", "Stone", SLATE, cards("stone", "stone_loot", "stone_count"),
                under("stone", 1, "stone_search"), under("stone_count", 1, "top_up"));
        lane.band("stone_kit", "Stone kit", BLUE, cards("stone_pick", "stone_axe", "stone_sword"));
        lane.plain("done", "end");
        lane.support("safety", "Safety", RED, 2, lane.start("wood"), "guard");

        caption(task, "intro", 0, 2, AMBER,
                "From nothing to a full stone kit: logs, a wooden pickaxe, twenty stone, then a "
                        + "stone pickaxe, axe and sword. About five minutes on a fresh world.");
        caption(task, "wood", lane.start("wood"), 2, GREEN,
                "Twelve logs pay for the wooden pickaxe and every handle still to come. No tree "
                        + "in sight? Explore goes looking and hands back.");
        caption(task, "pickaxe", lane.start("tools"), 1, BLUE,
                "Stone cannot be mined by hand, so the wooden pickaxe comes first.");
        caption(task, "stone", lane.start("stone"), 3, SLATE,
                "Mine digs stone it can see. Check Item Count then counts the cobblestone: twenty "
                        + "is what the job promises, so anything short takes the top-up detour "
                        + "below and comes back.");
        caption(task, "kit", lane.start("stone_kit"), 3, BLUE,
                "Twenty cobblestone buy a stone pickaxe, axe and sword. Each Get Tools card makes "
                        + "one; a failure skips to the next instead of ending the job.");
        rowNote(task, "guard", lane.start("wood") + 1, 2, 2, RED,
                "The guard rides the While pins of the cards that work outdoors, so the job still "
                        + "ends on its own once the kit is made.");
        lane.finish();
        return task;
    }

    /** Job 3: ten catches at the nearest water, and a clear answer when there is no rod. */
    private static TaskGraph goFishing() {
        TaskGraph task = seeded(GO_FISHING, GO_FISHING_ID);
        task.nodes.add(start("rod"));

        // Fish reads the main hand and does not equip for itself, and no card crafts a rod.
        // Select from Inventory is therefore not decoration here: it is the difference between
        // fishing and standing on a beach waving.
        task.nodes.add(hold("rod", "minecraft:fishing_rod", 0, "fish", "no_rod"));
        task.nodes.add(notify("no_rod", SOUND_PROBLEM, "stop_no_rod"));
        task.nodes.add(end("stop_no_rod"));

        // Auto recast off, deliberately: with it on, Fish never reports Success. One catch per
        // pass and ten passes on the repeat box is how the job counts.
        TaskNode fish = node("fish", "fish", Map.of("auto_recast", "false"));
        fish.repeat = 10;
        fish.onSuccess = "done";
        fish.onFailure = "no_water";
        task.nodes.add(fish);
        task.nodes.add(notify("no_water", SOUND_PROBLEM, "stop_no_water"));
        task.nodes.add(end("stop_no_water"));
        task.nodes.add(notify("done", SOUND_DONE, "end"));
        task.nodes.add(end("end"));
        task.nodes.add(guardOnPins(task, "fish"));

        Lane lane = new Lane(task);
        lane.plain("start", null);
        lane.band("rod", "The rod", BLUE, cards("rod"), under("rod", 1, "no_rod"),
                under("rod", 2, "stop_no_rod"));
        lane.band("fishing", "Fishing", PURPLE, cards("fish"), under("fish", 1, "no_water"),
                under("fish", 2, "stop_no_water"));
        lane.plain("done", "end");
        lane.support("safety", "Safety", RED, 3, lane.start("fishing"), "guard");

        caption(task, "intro", 0, 2, AMBER,
                "Stand by a lake or the sea with a fishing rod in your pack and press Run. Lune "
                        + "reels in ten catches and finishes. Fish, junk and treasure all count.");
        caption(task, "rod", lane.start("rod"), 1, BLUE,
                "Fish only uses the rod in the hand, so Select from Inventory puts it there first. "
                        + "No rod: a low note, and the job ends.");
        caption(task, "fish", lane.start("fishing"), 1, PURPLE,
                "One Fish card, repeated ten times: cast, wait for a bite, reel in. No water in "
                        + "sight ends the job the same way.");
        caption(task, "finish", lane.column("done"), 2, AMBER,
                "The repeat box on a card - here x10 - runs it that many times before its Success "
                        + "wire moves on. Raise it to fish for longer.");
        rowNote(task, "guard", lane.start("fishing") + 1, 3, 2, RED,
                "Self Preservation watches only while Fish runs. Night fishing is safer with it; "
                        + "it can step in against a mob and then hand the rod back.");
        lane.finish();
        return task;
    }

    /** Job 4: a straight corridor into whatever the player is facing, and back out. */
    private static TaskGraph digTunnel() {
        TaskGraph task = seeded(DIG_TUNNEL, DIG_TUNNEL_ID);
        task.nodes.add(start("pick"));
        task.nodes.add(forge("pick", "Pickaxe", "Stone", "entrance", "no_pick"));
        task.nodes.add(notify("no_pick", SOUND_PROBLEM, "stop_no_pick"));
        task.nodes.add(end("stop_no_pick"));
        task.nodes.add(saveWaypoint("entrance", TUNNEL_MOUTH, "dig"));
        TaskNode dig = node("dig", "tunnel", Map.of(
                "direction", "Facing",
                "length", "32",
                "height", "2"));
        dig.onSuccess = "walk_out";
        dig.onFailure = "walk_out";
        task.nodes.add(dig);
        task.nodes.add(goTo("walk_out", TUNNEL_MOUTH, 2, "done"));
        task.nodes.add(notify("done", SOUND_DONE, "end"));
        task.nodes.add(end("end"));
        task.nodes.add(guardOnPins(task, "pick", "dig", "walk_out"));

        Lane lane = new Lane(task);
        lane.plain("start", null);
        lane.band("tools", "Tools", BLUE, cards("pick"), under("pick", 1, "no_pick"),
                under("pick", 2, "stop_no_pick"));
        lane.band("tunnel", "The tunnel", SLATE, cards("entrance", "dig", "walk_out"));
        lane.plain("done", "end");
        lane.support("safety", "Safety", RED, 3, lane.start("tunnel"), "guard");

        caption(task, "intro", 0, 2, AMBER,
                "Face the way you want to dig, then press Run. Lune makes a stone pickaxe if she "
                        + "has none, digs a tunnel thirty-two blocks long and two high, and walks "
                        + "back out.");
        caption(task, "pickaxe", lane.start("tools"), 1, BLUE,
                "Get Tools makes a stone pickaxe from nothing if needed. If it cannot, a low note "
                        + "and the job stops.");
        caption(task, "dig", lane.start("tunnel"), 3, SLATE,
                "Save Waypoint remembers the entrance before digging starts. Tunnel clears each "
                        + "column before stepping into it, so the tunnel stays straight. Go to "
                        + "Waypoint walks back out, even when the tunnel stopped early.");
        rowNote(task, "guard", lane.start("tunnel") + 1, 3, 2, RED,
                "A tunnel is dark, and dark is where monsters spawn. Self Preservation rides the "
                        + "While pins of the three cards that do the work.");
        lane.finish();
        return task;
    }

    // =============================================================================================
    // Chores: hours of unattended work that stop by themselves.
    // =============================================================================================

    /** Job 5: fell, replant and store, for two hours or until the chest is full. */
    private static TaskGraph lumberCamp() {
        TaskGraph task = seeded(LUMBER_CAMP, LUMBER_CAMP_ID);
        task.nodes.add(start("camp"));
        task.nodes.add(saveWaypoint("camp", CAMP, "armor"));
        task.nodes.add(wearArmor("armor", "axe"));
        // The top of the loop. Get Tools answers at once while the axe is carried, and makes a
        // new one the pass after the old one broke.
        task.nodes.add(forge("axe", "Axe", "Stone", "hungry", "hungry"));
        addMeals(task, "hungry", "bedtime");
        task.nodes.add(timeIs("bedtime", "Dark enough to sleep", "bed", "chop"));
        task.nodes.add(sleep("bed", "axe", "chop"));

        TaskNode chop = chop("chop", 16, "loot", "search");
        chop.params.put("radius", "48");
        task.nodes.add(chop);
        task.nodes.add(search("search", LOGS, 6, "chop", "regrow"));
        // A forest cut back to the fence is not a reason to spin: give the saplings a minute.
        task.nodes.add(countdown("regrow", 1, "Minutes", "axe"));
        task.nodes.add(sweep("loot", "replant"));
        task.nodes.add(replant("replant", 32, "full"));

        task.nodes.add(playerAtMost("full", "Free slots", 4, "home", "axe"));
        task.nodes.add(goTo("home", CAMP, 2, "stash"));
        task.nodes.add(deposit("stash", "Logs", false, "axe", "closing"));
        addShiftEnd(task, 2);

        TaskNode fence = stayNear("fence", 48);
        protectWith(fence, task, "chop", "search", "loot", "replant", "hunt");
        task.nodes.add(fence);
        addSafetyCircuit(task);

        Lane lane = new Lane(task);
        lane.plain("start", null);
        lane.band("setup", "Setting up", AMBER, cards("camp", "armor", "axe"));
        lane.band("needs", "Food and sleep", SLATE, cards("hungry", "eat", "bedtime"),
                under("eat", 1, "hunt", "hunt_loot", "eat_catch"), under("bedtime", 2, "bed"));
        lane.band("lumber", "Chop and replant", GREEN, cards("chop", "loot", "replant"),
                under("chop", 1, "search", "regrow"));
        lane.band("storage", "Back to the chest", SLATE, cards("full", "home", "stash"),
                under("stash", 1, "closing", "leave", "shift_end"));
        // The clocks sit under the chest, beside the end of the shift they run towards, so the
        // Countdown's cable to it is a short one rather than a line across the whole job.
        int support = lane.lowestRow() + 2;
        int clocks = lane.column("closing") - 2;
        lane.support("boundary", "Boundary", PURPLE, support, lane.start("lumber"), "fence");
        lane.support("safety", "Safety", RED, support + 1, clocks, "safety_clock", "guard");
        lane.support("shift_clock", "Shift clock", AMBER, support + 2, clocks, "shift_clock", "shift");

        caption(task, "intro", 0, 2, AMBER,
                "An unattended lumber shift. Stand beside an empty chest in a forest and press "
                        + "Run. Lune fells trees, plants saplings where they stood and stores the "
                        + "logs, until the chest is full or two hours pass. Then she saves and "
                        + "leaves the world.");
        caption(task, "setup", lane.start("setup"), 3, AMBER,
                "Once at the start: the camp is saved as a waypoint and the best armor carried is "
                        + "put on. Get Tools is the top of the loop, so an axe that breaks is "
                        + "replaced on the next pass.");
        caption(task, "needs", lane.start("needs"), 3, SLATE,
                "Eat when hungry; with nothing to eat, hunt the nearest animal. When it is dark "
                        + "enough, sleep - a bed is made from nearby sheep if needed. No bed means "
                        + "the work simply carries on.");
        caption(task, "lumber", lane.start("lumber"), 3, GREEN,
                "Chop sixteen logs, sweep up the drops, then Replant Trees. Chop Wood notes where "
                        + "each tree stood, and Replant Trees puts a sapling back in every spot. "
                        + "Saplings fall from the leaves a little later, so a spot missed now is "
                        + "planted on the next trip.");
        caption(task, "storage", lane.start("storage"), 3, SLATE,
                "Four free slots left: walk back to the camp and put the logs in the chest. "
                        + "Saplings, apples and tools stay in the pack. A full chest, or no chest "
                        + "at all, fails the deposit and ends the shift.");
        rowNote(task, "forest", lane.start("lumber") + 1, support, 2, GREEN,
                "No tree in reach? Explore looks further. When the forest is cut back to the "
                        + "boundary, Countdown waits a minute for the saplings to grow.");
        rowNote(task, "clock", clocks + 2, support + 1, 3, AMBER,
                "Two Always cards run beside the loop. One keeps Self Preservation on for the "
                        + "whole shift. The other starts a two-hour Countdown, then saves and "
                        + "returns to the main menu. Change the hours on the Countdown card.");
        rowNote(task, "fence", lane.start("lumber") + 3, support, 2, PURPLE,
                "Stay Near keeps the forest work within forty-eight blocks of where the shift "
                        + "started. Only the cards out in the forest are wired to it.");
        lane.finish();
        return task;
    }

    /** Job 6: stone for the chest, for three hours or until the chest is full. */
    private static TaskGraph stoneQuarry() {
        TaskGraph task = seeded(STONE_QUARRY, STONE_QUARRY_ID);
        task.nodes.add(start("camp"));
        task.nodes.add(saveWaypoint("camp", QUARRY, "armor"));
        task.nodes.add(wearArmor("armor", "pick"));
        task.nodes.add(forge("pick", "Pickaxe", "Stone", "hungry", "hungry"));
        addMeals(task, "hungry", "bedtime");
        task.nodes.add(timeIs("bedtime", "Dark enough to sleep", "bed", "dig"));
        task.nodes.add(sleep("bed", "pick", "dig"));

        // Prospect on: when no stone is in sight, Mine cuts safe stairs down to more of it.
        task.nodes.add(mine("dig", STONE, 24, -64, 320, 64, true, "loot", "rest"));
        task.nodes.add(countdown("rest", 30, "Seconds", "home"));
        task.nodes.add(sweep("loot", "full"));
        task.nodes.add(playerAtMost("full", "Free slots", 4, "home", "pick"));
        task.nodes.add(goTo("home", QUARRY, 2, "stash"));
        task.nodes.add(deposit("stash", "Stone", false, "pick", "closing"));
        addShiftEnd(task, 3);

        TaskNode fence = stayNear("fence", 32);
        protectWith(fence, task, "dig", "loot", "hunt");
        task.nodes.add(fence);
        addSafetyCircuit(task);

        Lane lane = new Lane(task);
        lane.plain("start", null);
        lane.band("setup", "Setting up", AMBER, cards("camp", "armor", "pick"));
        lane.band("needs", "Food and sleep", SLATE, cards("hungry", "eat", "bedtime"),
                under("eat", 1, "hunt", "hunt_loot", "eat_catch"), under("bedtime", 2, "bed"));
        lane.band("quarry", "Quarry", SLATE, cards("dig", "loot"), under("dig", 1, "rest"));
        lane.band("storage", "Back to the chest", SLATE, cards("full", "home", "stash"),
                under("stash", 1, "closing", "leave", "shift_end"));
        int support = lane.lowestRow() + 2;
        int clocks = lane.column("closing") - 2;
        lane.support("boundary", "Boundary", PURPLE, support, lane.start("quarry"), "fence");
        lane.support("safety", "Safety", RED, support + 1, clocks, "safety_clock", "guard");
        lane.support("shift_clock", "Shift clock", AMBER, support + 2, clocks, "shift_clock", "shift");

        caption(task, "intro", 0, 2, AMBER,
                "An unattended quarry. Stand beside an empty chest and press Run. Lune digs stone "
                        + "and carries the cobblestone back to the chest for three hours, or until "
                        + "the chest is full.");
        caption(task, "setup", lane.start("setup"), 3, AMBER,
                "The quarry is saved as a waypoint, armor goes on, and a stone pickaxe is made if "
                        + "none is carried. The pickaxe is checked on every pass, so a broken one "
                        + "is replaced.");
        caption(task, "needs", lane.start("needs"), 3, SLATE,
                "The same meals and bedtime as the Lumber Camp: eat, hunt when the food runs out, "
                        + "and sleep once a bed will accept.");
        caption(task, "dig", lane.start("quarry"), 2, SLATE,
                "Mine takes sixty-four stone within twenty-four blocks. Keep searching is on, so "
                        + "with no stone in sight it digs safe stairs down to find more.");
        caption(task, "storage", lane.start("storage"), 3, SLATE,
                "With four slots free, back to the quarry waypoint; the stone goes in the chest and "
                        + "the loop starts again. If the chest is full, the shift ends.");
        rowNote(task, "rest", lane.start("quarry") + 1, support, 2, SLATE,
                "Nothing left to dig? Countdown waits thirty seconds, and the next pass starts from "
                        + "the chest.");
        rowNote(task, "clock", clocks + 2, support + 1, 3, AMBER,
                "Beside the loop: an Always card that keeps Self Preservation on, and another that "
                        + "starts a three-hour Countdown and then saves and leaves the world.");
        rowNote(task, "fence", lane.start("quarry") + 3, support, 2, PURPLE,
                "Stay Near keeps the digging within thirty-two blocks of the chest.");
        lane.finish();
        return task;
    }

    /** Job 7: the farm by day, bed or the yard by night, for three hours. */
    private static TaskGraph homestead() {
        TaskGraph task = seeded(HOMESTEAD, HOMESTEAD_ID);
        task.nodes.add(start("farm"));
        task.nodes.add(saveWaypoint("farm", FARM, "armor"));
        task.nodes.add(wearArmor("armor", "hungry"));
        task.nodes.add(hungry("hungry", 14, "eat", "day_gate"));
        task.nodes.add(meal("eat", 18, "day_gate"));

        // Check Time turns one job into a day shift and a night watch. Field work in the dark is
        // a bot standing in a wheat row while a creeper walks up behind it.
        task.nodes.add(timeIs("day_gate", "Day", "field", "bedtime"));
        TaskNode field = node("field", "harvest", Map.of(
                "targets", CROPS,
                "radius", "32",
                "limit", "64",
                "collect", "true",
                "replant", "true"));
        field.onSuccess = "field_loot";
        field.onFailure = "larder";
        task.nodes.add(field);
        task.nodes.add(sweep("field_loot", "larder"));

        // Meat keeps; a hunt that happens every shift does not. The gate is the difference between
        // a homestead and a slaughterhouse.
        task.nodes.add(carrying("larder", "minecraft:beef", 8, "wood", "stock_meat"));
        task.nodes.add(hunt("stock_meat", LIVESTOCK, 24, "meat_loot", "wood"));
        task.nodes.add(sweep("meat_loot", "wood"));
        TaskNode wood = chop("wood", 8, "wood_loot", "home");
        wood.params.put("radius", "48");
        task.nodes.add(wood);
        task.nodes.add(sweep("wood_loot", "home"));
        task.nodes.add(goTo("home", FARM, 2, "stash_crops"));
        task.nodes.add(store("stash_crops", "Crops", "stash_logs"));
        task.nodes.add(store("stash_logs", "Logs", "hungry"));

        task.nodes.add(timeIs("bedtime", "Dark enough to sleep", "bed", "yard_watch"));
        task.nodes.add(sleep("bed", "morning", "yard_watch"));
        task.nodes.add(notify("morning", SOUND_MORNING, "hungry"));
        task.nodes.add(findMobs("yard_watch", NIGHT_MOBS, 24, "arm_gate", "wait"));
        task.nodes.add(countdown("wait", 10, "Seconds", "hungry"));
        task.nodes.add(carrying("arm_gate", "minecraft:stone_sword", 1, "drive_off", "forge_sword"));
        task.nodes.add(forge("forge_sword", "Sword", "Stone", "drive_off", "wait"));
        task.nodes.add(fight("drive_off", NIGHT_MOBS, 16, "drive_loot", "back_to_yard"));
        task.nodes.add(sweep("drive_loot", "back_to_yard"));
        task.nodes.add(goTo("back_to_yard", FARM, 3, "hungry"));
        addShiftEnd(task, 3);

        // The fence is on the field work only. Chasing a zombie past the line is the one thing on
        // this shift that should be allowed to leave the property.
        TaskNode fence = stayNear("fence", 48);
        protectWith(fence, task, "field", "stock_meat", "wood");
        task.nodes.add(fence);
        addSafetyCircuit(task);

        Lane lane = new Lane(task);
        lane.plain("start", null);
        lane.band("setup", "Setting up", AMBER, cards("farm", "armor"));
        lane.band("food", "Food", SLATE, cards("hungry", "eat"));
        lane.band("farm", "The field", GREEN, cards("day_gate", "field", "field_loot"));
        lane.band("larder", "Meat and wood", GREEN, cards("larder", "wood", "wood_loot"),
                under("larder", 1, "stock_meat", "meat_loot"));
        lane.band("storage", "Back to the chest", SLATE, cards("home", "stash_crops", "stash_logs"));
        lane.band("night", "The night", PURPLE, cards("bedtime", "bed", "morning"),
                under("bedtime", 1, "yard_watch", "arm_gate", "drive_off", "drive_loot",
                        "back_to_yard"),
                under("yard_watch", 2, "wait", "forge_sword"));
        int support = lane.lowestRow() + 2;
        lane.support("safety", "Safety", RED, support, 0, "safety_clock", "guard");
        lane.support("shift_clock", "Shift clock", AMBER, support + 1, 0, "shift_clock", "shift",
                "closing", "leave", "shift_end");
        lane.support("boundary", "Boundary", PURPLE, support, lane.start("farm"), "fence");

        caption(task, "intro", 0, 2, AMBER,
                "A farm that runs itself for three hours. Stand beside a chest near your fields and "
                        + "press Run. By day Lune harvests and replants, keeps a stock of meat and "
                        + "cuts some wood. At night she sleeps, or guards the yard when there is no "
                        + "bed.");
        caption(task, "field", lane.start("farm"), 3, GREEN,
                "Check Time decides the shift. By day, Harvest picks ripe wheat, carrots, potatoes "
                        + "and beetroot and plants the seed straight back.");
        caption(task, "larder", lane.start("larder"), 3, GREEN,
                "Eight beef is enough. Below that, hunt a cow, pig, sheep or chicken nearby. Then "
                        + "eight logs for fences, handles and fuel.");
        caption(task, "storage", lane.start("storage"), 3, SLATE,
                "Back to the chest: the harvest and the logs go in. The chest is optional here - "
                        + "with none, everything stays in the pack.");
        caption(task, "night", lane.start("night"), 3, PURPLE,
                "Dark enough to sleep: go to bed, and a bell rings in the morning. No bed: watch the "
                        + "yard, make a sword if needed, drive monsters off and walk back. Nothing "
                        + "to fight means a ten-second wait before the next look.");
        rowNote(task, "clock", 2, support, 3, AMBER,
                "Beside the loop: an Always card for Self Preservation, and a three-hour Countdown "
                        + "that saves and leaves the world when the shift is over.");
        rowNote(task, "fence", lane.start("farm") + 1, support, 2, PURPLE,
                "Stay Near keeps the field work, the hunt and the wood run within forty-eight "
                        + "blocks. The night fight is left free to chase.");
        lane.finish();
        return task;
    }

    /** Job 8: every raw metal in the pack, turned into ingots. */
    private static TaskGraph smeltery() {
        TaskGraph task = seeded(SMELTERY, SMELTERY_ID);
        task.nodes.add(start("furnace_check"));
        task.nodes.add(carrying("furnace_check", "minecraft:furnace", 1, "coal_check", "cobble_check"));
        task.nodes.add(carrying("cobble_check", "minecraft:cobblestone", 8, "make_furnace", "pick"));
        task.nodes.add(forge("pick", "Pickaxe", "Wooden", "quarry", "quarry"));
        task.nodes.add(mine("quarry", STONE, 32, -64, 320, 8, false, "make_furnace", "make_furnace"));
        task.nodes.add(craft("make_furnace", "minecraft:furnace", "coal_check", "no_furnace"));
        task.nodes.add(notify("no_furnace", SOUND_PROBLEM, "stop_no_furnace"));
        task.nodes.add(end("stop_no_furnace"));

        task.nodes.add(carrying("coal_check", "minecraft:coal", 8, "iron", "fuel_wood"));
        task.nodes.add(chop("fuel_wood", 12, "fuel_loot", "iron"));
        task.nodes.add(sweep("fuel_loot", "iron"));

        // One item a pass, sixty-four passes: Smelt asked for more than is carried gives up with
        // the rest still in the furnace, so the card asks for one and the repeat box carries on
        // until the pack is empty - which is the Fail wire, and a perfectly good end.
        task.nodes.add(smeltAll("iron", "Raw Iron", "gold"));
        task.nodes.add(smeltAll("gold", "Raw Gold", "copper"));
        task.nodes.add(smeltAll("copper", "Raw Copper", "done"));
        task.nodes.add(notify("done", SOUND_DONE, "end"));
        task.nodes.add(end("end"));
        task.nodes.add(guardOnPins(task, "quarry", "fuel_wood"));

        Lane lane = new Lane(task);
        lane.plain("start", null);
        lane.band("furnace", "The furnace", SLATE,
                cards("furnace_check", "cobble_check", "pick", "quarry", "make_furnace"),
                under("make_furnace", 1, "no_furnace"), under("make_furnace", 2, "stop_no_furnace"));
        lane.band("fuel", "Fuel", GREEN, cards("coal_check", "fuel_wood", "fuel_loot"));
        lane.band("smelting", "Smelting", AMBER, cards("iron", "gold", "copper"));
        lane.plain("done", "end");
        lane.support("safety", "Safety", RED, 3, lane.start("fuel"), "guard");

        caption(task, "intro", 0, 2, AMBER,
                "Turns the raw iron, gold and copper in your pack into ingots. Bring coal for big "
                        + "batches; without it Lune chops wood to burn. The ingots stay in the "
                        + "pack.");
        caption(task, "furnace", lane.start("furnace"), 3, SLATE,
                "Smelt uses a furnace nearby, or places one from the pack. With neither, Craft "
                        + "makes one from eight cobblestone - mining the stone first when there is "
                        + "not enough. A furnace that cannot be made ends the job.");
        caption(task, "fuel", lane.start("fuel"), 3, GREEN,
                "Eight coal smelt sixty-four items. With less, twelve logs are chopped and burned "
                        + "instead - enough for eighteen.");
        caption(task, "smelting", lane.start("smelting"), 3, AMBER,
                "One Smelt card per metal, each set to one item and repeated up to sixty-four "
                        + "times. When the pack has none left the card fails, and the red wire "
                        + "moves on to the next metal.");
        rowNote(task, "guard", lane.start("fuel") + 1, 3, 2, RED,
                "Self Preservation watches the two cards that go outside for stone and wood.");
        lane.finish();
        return task;
    }

    /** Job 9: one night on guard, from dark to sunrise, then home. */
    private static TaskGraph nightWatch() {
        TaskGraph task = seeded(NIGHT_WATCH, NIGHT_WATCH_ID);
        task.nodes.add(start("post"));
        task.nodes.add(saveWaypoint("post", POST, "armor"));
        task.nodes.add(wearArmor("armor", "sword"));
        task.nodes.add(forge("sword", "Sword", "Stone", "dark", "dark"));
        task.nodes.add(timeIs("dark", "Night", "hungry", "wait_dark"));
        task.nodes.add(countdown("wait_dark", 30, "Seconds", "dark"));

        task.nodes.add(hungry("hungry", 14, "eat", "dawn"));
        task.nodes.add(meal("eat", 18, "dawn"));
        task.nodes.add(timeIs("dawn", "Day", "stash", "look"));
        task.nodes.add(findMobs("look", NIGHT_MOBS, 24, "fight", "pause"));
        task.nodes.add(countdown("pause", 5, "Seconds", "hungry"));
        task.nodes.add(fight("fight", NIGHT_MOBS, 16, "fight_loot", "return"));
        task.nodes.add(sweep("fight_loot", "return"));
        task.nodes.add(goTo("return", POST, 2, "hungry"));

        task.nodes.add(store("stash", "Loot", "done"));
        task.nodes.add(notify("done", SOUND_MORNING, "end"));
        task.nodes.add(end("end"));
        task.nodes.add(guardOnPins(task, "look", "fight", "fight_loot", "return"));

        Lane lane = new Lane(task);
        lane.plain("start", null);
        lane.band("setup", "Setting up", AMBER, cards("post", "armor", "sword"));
        lane.band("waiting", "Waiting for dark", SLATE, cards("dark"), under("dark", 1, "wait_dark"));
        lane.band("watch", "The watch", PURPLE,
                cards("hungry", "eat", "dawn", "look", "fight", "fight_loot", "return"),
                under("look", 1, "pause"));
        lane.band("sunrise", "Sunrise", AMBER, cards("stash", "done"));
        lane.plain("end");
        lane.support("safety", "Safety", RED, 3, lane.start("watch") + 3, "guard");

        caption(task, "intro", 0, 2, AMBER,
                "Guards one spot through one night. Stand where you want the watch and press Run, "
                        + "at any time of day. Lune waits for dark, fights what comes near, goes "
                        + "back to her post after each fight and stops at sunrise.");
        caption(task, "setup", lane.start("setup"), 3, AMBER,
                "The post is saved as a waypoint. Armor goes on, and a stone sword is made if none "
                        + "is carried.");
        caption(task, "waiting", lane.start("waiting"), 1, SLATE,
                "Before dark there is nothing to do: Countdown waits thirty seconds between looks "
                        + "at the clock.");
        caption(task, "watch", lane.start("watch"), 3, PURPLE,
                "Each pass: eat if hungry, stop if the sun is up, then look for monsters within "
                        + "twenty-four blocks. Kill fights them, behind a shield if one is carried; "
                        + "Loot takes the drops, and Go to Waypoint returns to the post.");
        caption(task, "quiet", lane.start("watch") + 3, 3, PURPLE,
                "Nothing in sight? Countdown waits five seconds before the next look, so the watch "
                        + "does not spin through its checks.");
        caption(task, "sunrise", lane.start("sunrise"), 2, AMBER,
                "Sunrise ends the watch: the drops go in a chest if there is one, and a bell says "
                        + "the night is over.");
        rowNote(task, "guard", lane.start("watch") + 4, 3, 2, RED,
                "Self Preservation rides the While pins of the fighting cards, so it only acts "
                        + "during the watch and the job still ends at sunrise.");
        lane.finish();
        return task;
    }

    // =============================================================================================
    // Expeditions: what the engine can be asked to do.
    // =============================================================================================

    /** Job 10: back to the last death, pick it all up, put it back on, come home. */
    private static TaskGraph stuffBack() {
        TaskGraph task = seeded(STUFF_BACK, STUFF_BACK_ID);
        task.nodes.add(start("here"));
        task.nodes.add(saveWaypoint("here", RECOVERY_START, "recover"));
        TaskNode recover = node("recover", "recover_death", Map.of(
                "which", "Most recent",
                "radius", "8"));
        recover.onSuccess = "sweep";
        recover.onFailure = "nothing";
        task.nodes.add(recover);
        task.nodes.add(notify("nothing", SOUND_PROBLEM, "stop_nothing"));
        task.nodes.add(end("stop_nothing"));
        task.nodes.add(sweep("sweep", 12, "dress"));
        task.nodes.add(wearArmor("dress", "back"));
        task.nodes.add(goTo("back", RECOVERY_START, 2, "done"));
        task.nodes.add(notify("done", SOUND_DONE, "end"));
        task.nodes.add(end("end"));
        task.nodes.add(guardOnPins(task, "recover", "sweep", "back"));

        Lane lane = new Lane(task);
        lane.plain("start", null);
        lane.band("recovery", "Recovery", PURPLE, cards("here", "recover", "sweep"),
                under("recover", 1, "nothing"), under("recover", 2, "stop_nothing"));
        lane.band("return", "The way back", AMBER, cards("dress", "back"));
        lane.plain("done", "end");
        lane.support("safety", "Safety", RED, 3, lane.start("return"), "guard");

        caption(task, "intro", 0, 2, AMBER,
                "Just died? Run this from where you respawned. Lune walks back to your most recent "
                        + "death, picks up what is still there, puts the armor back on and comes "
                        + "back. Dropped items vanish five minutes after a death, so be quick.");
        caption(task, "recover", lane.start("recovery"), 3, PURPLE,
                "Lune writes down where every death happens. Recover Death Drop walks back to the "
                        + "latest one, digging if the death was in a cave, and sweeps up the drops. "
                        + "No death on file: a low note and the job ends.");
        caption(task, "return", lane.start("return"), 2, AMBER,
                "Equip puts on the best armor carried. Go to Waypoint walks back to where the run "
                        + "started.");
        rowNote(task, "guard", lane.start("return") + 1, 3, 2, RED,
                "Whatever killed you may still be there. Self Preservation watches the walk out, "
                        + "the sweep and the walk home.");
        lane.finish();
        return task;
    }

    /**
     * Job 11: the long descent - stone tools to iron to diamond to obsidian to a lit portal.
     *
     * <p>The vocabulary it adds has room to matter: a job called by name when the pack is empty,
     * one Find whose choice travels to four mining cards through data wires, Select from Inventory
     * used as a durability condition, and a clock check that refuses to hunt endermen at noon.</p>
     */
    private static TaskGraph litPortal() {
        TaskGraph task = seeded(LIT_PORTAL, LIT_PORTAL_ID);
        task.nodes.add(start("kit_gate"));

        // Nothing below an iron pickaxe touches diamond, and nothing below stone touches iron, so
        // the descent starts by making sure the first rung is under it. Run Task fails cleanly when
        // job 2 has been renamed or deleted, and the Fail wire forges the kit in place instead.
        task.nodes.add(carrying("kit_gate", "minecraft:stone_pickaxe", 1, "lunch_gate", "earn_kit"));
        TaskNode earnKit = node("earn_kit", "task", Map.of("name", STONE_TOOLS));
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
        task.nodes.add(branchMine("iron_dig", IRON_ORES, 12, 24, 4, "iron_mine", "coal_run"));
        task.nodes.add(mine("iron_mine", IRON_ORES, 64, -64, 72, 18, true, "iron_loot", "iron_roam"));
        task.nodes.add(search("iron_roam", IRON_ORES, 5, "iron_mine", "iron_smelt"));
        task.nodes.add(sweep("iron_loot", "iron_quota"));
        task.nodes.add(carrying("iron_quota", "minecraft:raw_iron", 16, "coal_run", "iron_dig"));

        // A furnace burns something. Coal is on the way up and pays for the smelt twice over.
        task.nodes.add(mine("coal_run", COAL_ORES, 48, -64, 96, 16, true, "coal_loot", "iron_smelt"));
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
        TaskNode diamondRoam = search("diamond_roam", DIAMOND_ORES, 4, "diamond_mine", "diamond_count");
        task.nodes.add(scan);
        task.nodes.add(branchOne);
        task.nodes.add(diamondMine);
        task.nodes.add(diamondRoam);
        task.nodes.add(sweep("diamond_loot", "diamond_count"));
        task.nodes.add(carrying("diamond_count", "minecraft:diamond", 8, "diamond_pick", "branch_two"));

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
        task.nodes.add(mine("deep_ore_run", DEEP_ORES, 48, -64, 32, 24, false,
                "deep_ore_loot", "pick_wear"));
        task.nodes.add(sweep("deep_ore_loot", "pick_wear"));

        // Select from Inventory used as a condition rather than as an action: it fails when every
        // carried diamond pickaxe is below a quarter of its durability. A pickaxe that breaks
        // partway through an obsidian block leaves the block behind as well as the tool.
        task.nodes.add(hold("pick_wear", "minecraft:diamond_pickaxe", 25, "obsidian_mine", "spare_pick"));
        task.nodes.add(forge("spare_pick", "Pickaxe", "Diamond", "obsidian_mine", "obsidian_mine"));
        task.nodes.add(mine("obsidian_mine", OBSIDIAN, 64, -64, 48, 14, true,
                "obsidian_loot", "lava_dig"));
        task.nodes.add(branchMine("lava_dig", OBSIDIAN, -12, 24, 6, "obsidian_mine", "obsidian_count"));
        task.nodes.add(sweep("obsidian_loot", "obsidian_count"));
        // Ten is the corner-saving frame's exact bill of materials, so it is the number to ask for.
        task.nodes.add(carrying("obsidian_count", "minecraft:obsidian", 10, "lighter_gate", "lava_dig"));

        // Build Nether Portal lights the frame with something already in the pack and does not
        // craft one. Asking first turns "nothing to light it with" from a failed card into a
        // branch that simply carries on.
        task.nodes.add(carrying("lighter_gate", "minecraft:flint_and_steel", 1,
                "portal_build", "charge_gate"));
        task.nodes.add(carrying("charge_gate", "minecraft:fire_charge", 1, "portal_build", "pearl_gate"));
        task.nodes.add(portal("portal_build", "10 obsidian + dirt/cobblestone corners",
                "portal_lit", "portal_retry"));
        // No dirt and no cobble left is not a reason to abandon ten obsidian. The speedrun shape
        // needs the same ten and no corners at all.
        task.nodes.add(portal("portal_retry", "10 obsidian, open corners", "portal_lit", "portal_stash"));
        task.nodes.add(notify("portal_lit", SOUND_STEP, "portal_stash"));
        task.nodes.add(store("portal_stash", "Ores", "pearl_gate"));

        // Twelve pearls is what an End portal frame wants, and endermen are a night job.
        task.nodes.add(carrying("pearl_gate", "minecraft:ender_pearl", 12, "haul_stash", "night_gate"));
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

        task.nodes.add(store("haul_stash", "All", "done"));
        task.nodes.add(notify("done", SOUND_DONE, "rest"));
        task.nodes.add(stopGame("rest", "Pause the game", "portal_end"));
        task.nodes.add(end("portal_end"));
        addSafetyCircuit(task);

        Lane lane = new Lane(task);
        lane.plain("start", null);
        lane.band("stone_kit", "Stone kit", BLUE, cards("kit_gate"),
                under("kit_gate", 1, "earn_kit"), under("kit_gate", 2, "forge_kit", "forge_blade"));
        lane.band("food", "Food", SLATE, cards("lunch_gate", "lunch"));
        lane.band("iron", "Iron", SLATE,
                cards("iron_gate", "iron_scan", "iron_mine", "iron_loot", "iron_quota"),
                under("iron_scan", 1, "iron_dig"), under("iron_mine", 1, "iron_roam"));
        lane.band("coal", "Coal and smelting", AMBER,
                cards("coal_run", "coal_loot", "iron_smelt", "iron_pick", "iron_sword"));
        lane.band("diamonds", "Diamonds", BLUE,
                cards("deep_food", "deep_eat", "diamond_scan", "diamond_mine", "diamond_loot",
                        "diamond_count", "diamond_pick", "diamond_sword"),
                under("diamond_scan", 1, "branch_one"), under("diamond_mine", 1, "diamond_roam"),
                under("diamond_count", 1, "branch_two", "mine_again", "loot_again"));
        lane.band("deep_ores", "On the way up", SLATE, cards("deep_ore_run", "deep_ore_loot"));
        lane.band("obsidian", "Obsidian", PURPLE,
                cards("pick_wear", "obsidian_mine", "obsidian_loot", "obsidian_count"),
                under("pick_wear", 1, "spare_pick"), under("obsidian_mine", 1, "lava_dig"));
        lane.band("portal", "The portal", PURPLE,
                cards("lighter_gate", "charge_gate", "portal_build", "portal_lit", "portal_stash"),
                under("portal_build", 1, "portal_retry"));
        lane.band("pearls", "Ender pearls", GREEN,
                cards("pearl_gate", "night_gate", "pearl_hunt", "pearl_loot"));
        lane.band("finish", "Finish", AMBER, cards("haul_stash", "done", "rest", "portal_end"));
        lane.support("safety", "Safety", RED, lane.lowestRow() + 2, 0, "safety_clock", "guard");

        caption(task, "intro", 0, 2, AMBER,
                "A long run underground: stone tools, iron, diamonds and obsidian, ending at a lit "
                        + "Nether portal and a stock of ender pearls. It may stop early in some "
                        + "worlds, but every leg has a way out, so it never circles forever.");
        caption(task, "kit", lane.start("stone_kit"), 2, BLUE,
                "No stone pickaxe? Run Task calls 2. Stone Tools from Scratch. If that job was "
                        + "renamed or deleted, the red wire makes the kit here instead.");
        caption(task, "food", lane.start("food"), 2, SLATE,
                "Eat before going down. Hunger is checked again at the diamond layer.");
        caption(task, "iron", lane.start("iron"), 3, SLATE,
                "Iron: look, dig, count. Find decides whether ore is in sight. Stripmine digs when "
                        + "none is, and the count loops until sixteen raw iron are carried.");
        caption(task, "smelting", lane.start("coal"), 3, AMBER,
                "Coal from the way back up fuels the furnace, and the iron becomes a pickaxe and a "
                        + "sword.");
        caption(task, "diamonds", lane.start("diamonds") + 2, 3, BLUE,
                "One Find card chooses the diamond ore, and its data wires hand that choice to "
                        + "every mining card after it. Change the ore once and the whole leg "
                        + "follows.");
        caption(task, "obsidian", lane.start("obsidian"), 3, PURPLE,
                "Select from Inventory checks the diamond pickaxe still has a quarter of its life "
                        + "before ten obsidian are mined; otherwise a spare is made first.");
        caption(task, "portal", lane.start("portal"), 3, PURPLE,
                "Build Nether Portal lights the frame with flint and steel or a fire charge. With "
                        + "neither, the job skips the build rather than failing.");
        caption(task, "pearls", lane.start("pearls"), 3, GREEN,
                "Twelve ender pearls for the End - but only at night. By day Check Time skips the "
                        + "hunt.");
        caption(task, "finish", lane.start("finish"), 3, AMBER,
                "Everything goes in a chest if one is near. Then Stop the Game pauses the world, "
                        + "so nothing can happen to Lune while you are away. A server cannot "
                        + "pause, so there the job just ends.");
        rowNote(task, "guard", 2, lane.lowestRow() + 2, 3, RED,
                "Self Preservation hangs off its own Always card, so one wire covers every card in "
                        + "the job - including any you add later.");
        lane.finish();
        return task;
    }

    /**
     * Job 12: the whole game in one graph, from punching a tree to leaving the End.
     *
     * <p>Bands A to E are the ladder rewritten as one forward-running chain, so a gate that passes
     * skips the leg that would have supplied it. Band F hands the stocked pack to Complete the
     * Game, which reads the inventory and resumes at the furthest rung it can prove. Everything
     * below the lane runs beside it rather than after it.</p>
     */
    private static TaskGraph enderDragon() {
        TaskGraph task = seeded(ENDER_DRAGON, ENDER_DRAGON_ID);
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

        // Band D - iron: look, then dig, then count.
        task.nodes.add(carrying("d_iron_gate", "minecraft:iron_ingot", 12, "e_deep_gate", "d_iron_scan"));
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
        task.nodes.add(carrying("e_deep_gate", "minecraft:diamond", 3, "e_diamond_pick", "e_deep_food"));
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
        task.nodes.add(forge("e_diamond_pick", "Pickaxe", "Diamond", "e_obsidian_mine", "e_obsidian_mine"));
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
        // short-range Loot picks up what the lane walked past - short enough not to walk out of a
        // dragon fight.
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

        // The brake. A pressed Button eats immediately and pauses a second later.
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

        Lane lane = new Lane(task);
        lane.plain("start", null);
        lane.band("wood", "Wood", GREEN,
                cards("a_chop", "a_loot", "a_axe", "a_pick", "a_top_up", "a_top_loot"),
                under("a_chop", 1, "a_tree_search"));
        lane.band("stone_coal", "Stone and coal", SLATE,
                cards("b_stone_mine", "b_stone_loot", "b_pick", "b_axe", "b_sword", "b_coal",
                        "b_coal_loot"),
                under("b_stone_mine", 1, "b_stone_search"));
        lane.band("food", "Food", GREEN, cards("c_food_gate", "c_eat", "c_hunt_loot", "c_eat_again"),
                under("c_eat", 1, "c_hunt", "c_fish"));
        lane.band("iron", "Iron", SLATE,
                cards("d_iron_gate", "d_iron_scan", "d_iron_mine", "d_iron_loot", "d_iron_quota",
                        "d_iron_smelt", "d_iron_pick", "d_iron_sword"),
                under("d_iron_scan", 1, "d_iron_dig"), under("d_iron_mine", 1, "d_iron_roam"));
        lane.band("diamonds_obsidian", "Diamonds and obsidian", BLUE,
                cards("e_deep_gate", "e_deep_food", "e_scan", "e_mine", "e_loot", "e_count",
                        "e_diamond_pick", "e_obsidian_mine", "e_obsidian_loot"),
                under("e_deep_food", 1, "e_deep_eat"), under("e_scan", 1, "e_branch_one"),
                under("e_mine", 1, "e_roam"),
                under("e_count", 1, "e_branch_two", "e_mine_again", "e_loot_again"));
        lane.band("the_end", "The End", PURPLE,
                cards("f_preflight", "f_preflight_eat", "f_launch", "f_win_loot", "f_win_stash"));
        lane.plain("mission_end");

        int support = lane.lowestRow() + 2;
        lane.support("debrief", "Debrief", AMBER, support, lane.column("mission_end"),
                "mission_watch", "closeout_counter", "closeout_hub", "debrief_loot",
                "debrief_loot_end");
        lane.place("debrief_stash", lane.column("mission_end") + 3, support + 1);
        lane.place("debrief_stash_end", lane.column("mission_end") + 4, support + 1);
        lane.place("debrief_delay", lane.column("mission_end") + 2, support + 2);
        lane.place("debrief_pause", lane.column("mission_end") + 3, support + 2);
        lane.place("debrief_pause_end", lane.column("mission_end") + 4, support + 2);
        lane.join("debrief", "debrief_stash", "debrief_stash_end", "debrief_delay",
                "debrief_pause", "debrief_pause_end");

        lane.support("safety", "Safety", RED, support, 0, "safety_clock", "guard");
        lane.support("meals", "Meals on a clock", SLATE, support + 1, 0,
                "field_pulse", "field_hunger", "field_eat", "field_end");
        lane.support("broom", "Broom", GREEN, support + 2, 0, "sweep_pulse", "sweep_loot", "sweep_end");
        lane.support("panic", "Panic button", RED, support + 3, 0,
                "panic_button", "panic_hub", "panic_eat", "panic_eat_end");
        lane.place("panic_delay", 2, support + 4);
        lane.place("panic_pause", 3, support + 4);
        lane.place("panic_pause_end", 4, support + 4);
        lane.join("panic", "panic_delay", "panic_pause", "panic_pause_end");

        caption(task, "intro", 0, 2, AMBER,
                "The whole game in one graph, from the first tree to the Ender Dragon. The first "
                        + "five sections stock the pack; then Complete the Game takes it the rest "
                        + "of the way. Experimental: in our tests it has not yet reached the Nether.");
        caption(task, "wood", lane.start("wood"), 3, GREEN,
                "Wood, a wooden axe and pickaxe, then more wood with the axe.");
        caption(task, "stone", lane.start("stone_coal"), 3, SLATE,
                "Stone tools, and the coal that lights everything below.");
        caption(task, "food", lane.start("food"), 3, GREEN,
                "Food, quickest source first - eat what is carried, hunt an animal, then fish "
                        + "once.");
        caption(task, "iron", lane.start("iron"), 3, SLATE,
                "Iron for a pickaxe and a sword. A gate that already passes skips the whole "
                        + "section.");
        caption(task, "diamonds", lane.start("diamonds_obsidian"), 3, BLUE,
                "Three diamonds for a pickaxe, then the obsidian only a diamond pickaxe can cut.");
        caption(task, "the_end", lane.start("the_end"), 3, PURPLE,
                "Complete the Game reads the pack and resumes from the furthest step it can prove, "
                        + "so nothing before it is repeated.");
        rowNote(task, "support", 5, support, 3, SLATE,
                "The rows below run beside the lane: the guard, a meal check every forty-five "
                        + "seconds, and a broom that sweeps up drops every ninety.");
        rowNote(task, "panic", 5, support + 3, 3, RED,
                "Press the Button on the canvas to eat at once and pause the game a second later.");
        rowNote(task, "debrief", lane.column("mission_end") + 5, support, 3, AMBER,
                "The Observer watches the last End card and counts both of its edges before the "
                        + "debrief: sweep, store, and pause five seconds later.");
        lane.finish();
        return task;
    }

    /** Job 13: into the Nether, ancient debris, home, and a netherite pickaxe at the end of it. */
    private static TaskGraph netherite() {
        TaskGraph task = seeded(NETHERITE, NETHERITE_ID);
        task.nodes.add(start("where"));
        task.nodes.add(dimensionIs("where", "Nether", "n_mark", "cross"));
        task.nodes.add(usePortal("cross", 32, "n_mark", "obsidian_check"));
        task.nodes.add(carrying("obsidian_check", "minecraft:obsidian", 10, "lighter_check", "cant_cross"));
        task.nodes.add(carrying("lighter_check", "minecraft:flint_and_steel", 1, "build", "cant_cross"));
        task.nodes.add(portal("build", "10 obsidian + dirt/cobblestone corners", "enter", "build_open"));
        task.nodes.add(portal("build_open", "10 obsidian, open corners", "enter", "cant_cross"));
        task.nodes.add(usePortal("enter", 16, "n_mark", "cant_cross"));
        task.nodes.add(notify("cant_cross", SOUND_PROBLEM, "stop_cross"));
        task.nodes.add(end("stop_cross"));

        // The Nether side of the portal is the one waypoint this job cannot do without: the way
        // home is through the same frame.
        task.nodes.add(saveWaypoint("n_mark", NETHER_SIDE, "armor"));
        task.nodes.add(wearArmor("armor", "pick_check"));
        task.nodes.add(hold("pick_check", "minecraft:diamond_pickaxe", 10, "n_food", "no_pick"));
        task.nodes.add(notify("no_pick", SOUND_PROBLEM, "stop_pick"));
        task.nodes.add(end("stop_pick"));
        task.nodes.add(hungry("n_food", 12, "n_eat", "strip_one"));
        task.nodes.add(meal("n_eat", 18, "strip_one"));
        task.nodes.add(branchMine("strip_one", ANCIENT_DEBRIS, 15, 24, 6, "loot_one", "loot_one"));
        task.nodes.add(sweep("loot_one", "debris_count"));
        task.nodes.add(carrying("debris_count", ANCIENT_DEBRIS, 4, "to_portal", "strip_two"));
        task.nodes.add(branchMine("strip_two", ANCIENT_DEBRIS, 15, 40, 10, "loot_two", "to_portal"));
        task.nodes.add(sweep("loot_two", "to_portal"));
        task.nodes.add(goTo("to_portal", NETHER_SIDE, 2, "exit"));
        task.nodes.add(usePortal("exit", 32, "furnace_check", "stranded"));
        task.nodes.add(notify("stranded", SOUND_PROBLEM, "rest"));

        task.nodes.add(carrying("furnace_check", "minecraft:furnace", 1, "coal_check", "cobble_check"));
        task.nodes.add(carrying("cobble_check", "minecraft:cobblestone", 8, "make_furnace", "quarry"));
        task.nodes.add(mine("quarry", STONE, 32, -64, 320, 8, false, "make_furnace", "make_furnace"));
        task.nodes.add(craft("make_furnace", "minecraft:furnace", "coal_check", "short"));
        task.nodes.add(carrying("coal_check", "minecraft:coal", 1, "smelt", "fuel_wood"));
        task.nodes.add(chop("fuel_wood", 6, "fuel_loot", "smelt"));
        task.nodes.add(sweep("fuel_loot", "smelt"));
        TaskNode smelt = node("smelt", "smelt", Map.of(
                "input", "Ancient Debris",
                "count", "4"));
        smelt.onSuccess = "scrap_check";
        smelt.onFailure = "short";
        task.nodes.add(smelt);

        task.nodes.add(carrying("scrap_check", "minecraft:netherite_scrap", 4, "gold_check", "short"));
        task.nodes.add(carrying("gold_check", "minecraft:gold_ingot", 4, "make_ingot", "short"));
        task.nodes.add(craft("make_ingot", "minecraft:netherite_ingot", "template_check", "short"));
        task.nodes.add(carrying("template_check", "minecraft:netherite_upgrade_smithing_template", 1,
                "table_check", "short"));
        task.nodes.add(carrying("table_check", "minecraft:smithing_table", 1, "upgrade", "make_table"));
        task.nodes.add(craft("make_table", "minecraft:smithing_table", "upgrade", "short"));
        TaskNode upgrade = node("upgrade", "upgrade_netherite", Map.of("gear", "minecraft:diamond_pickaxe"));
        upgrade.onSuccess = "done";
        upgrade.onFailure = "short";
        task.nodes.add(upgrade);

        task.nodes.add(notify("done", SOUND_DONE, "rest"));
        task.nodes.add(notify("short", SOUND_PROBLEM, "rest"));
        task.nodes.add(stopGame("rest", "Pause the game", "finish"));
        task.nodes.add(end("finish"));
        addSafetyCircuit(task);

        Lane lane = new Lane(task);
        lane.plain("start", null);
        lane.band("crossing", "Crossing over", PURPLE,
                cards("where", "cross", "obsidian_check", "lighter_check", "build", "enter"),
                under("build", 1, "build_open"), under("enter", 1, "cant_cross"),
                under("enter", 2, "stop_cross"));
        lane.band("debris", "Ancient debris", RED,
                cards("n_mark", "armor", "pick_check", "n_food", "strip_one", "loot_one",
                        "debris_count"),
                under("pick_check", 1, "no_pick"), under("pick_check", 2, "stop_pick"),
                under("n_food", 1, "n_eat"), under("debris_count", 1, "strip_two", "loot_two"));
        lane.band("home", "Home again", PURPLE, cards("to_portal", "exit"),
                under("exit", 1, "stranded"));
        lane.band("furnace", "The furnace", SLATE,
                cards("furnace_check", "cobble_check", "quarry", "make_furnace"));
        lane.band("smelting", "Smelting", AMBER, cards("coal_check", "fuel_wood", "fuel_loot", "smelt"));
        lane.band("upgrade", "Upgrade", BLUE,
                cards("scrap_check", "gold_check", "make_ingot", "template_check", "table_check",
                        "make_table", "upgrade"));
        lane.band("finish", "Finish", AMBER, cards("done", "rest", "finish"), under("done", 1, "short"));
        lane.support("safety", "Safety", RED, lane.lowestRow() + 2, 0, "safety_clock", "guard");

        caption(task, "intro", 0, 2, AMBER,
                "Go to the Nether, mine ancient debris and come home to make a netherite pickaxe. "
                        + "Bring a diamond pickaxe, food, four gold ingots, and ten obsidian with "
                        + "flint and steel. The upgrade template is found in bastion chests.");
        caption(task, "crossing", lane.start("crossing"), 3, PURPLE,
                "Already in the Nether? Straight on. Otherwise Use Nether Portal walks into a lit "
                        + "portal in sight; with none, Build Nether Portal makes one from the "
                        + "obsidian carried.");
        caption(task, "debris", lane.start("debris"), 3, RED,
                "The portal is saved as a waypoint. Ancient debris is most common at height "
                        + "fifteen, so Stripmine digs a shaft and branches there, and a longer "
                        + "second pass runs when fewer than four were found.");
        caption(task, "home", lane.start("home"), 2, PURPLE,
                "Back to the saved portal and through it. A portal that will not take her home "
                        + "ends the job.");
        caption(task, "furnace", lane.start("furnace"), 3, SLATE,
                "A furnace is placed from the pack, or made from eight cobblestone.");
        caption(task, "smelting", lane.start("smelting"), 3, AMBER,
                "Wood is chopped when there is no coal, then Smelt turns four debris into four "
                        + "netherite scrap.");
        caption(task, "upgrade", lane.start("upgrade"), 3, BLUE,
                "Four scrap and four gold ingots make a netherite ingot. With the template and a "
                        + "smithing table, Upgrade to Netherite turns the diamond pickaxe into "
                        + "netherite.");
        caption(task, "finish", lane.start("finish"), 3, AMBER,
                "Anything missing ends the run with a low note, and the world is paused either way "
                        + "so you can see how far it got.");
        rowNote(task, "guard", 2, lane.lowestRow() + 2, 3, RED,
                "Self Preservation holds for the whole trip on its own Always card: lava, ghast "
                        + "fireballs and long falls are the Nether's usual ways to end a run.");
        lane.finish();
        return task;
    }

    /** Job 14: the nearest village, by compass when there is one and on foot when not. */
    private static TaskGraph findVillage() {
        TaskGraph task = seeded(FIND_VILLAGE, FIND_VILLAGE_ID);
        task.nodes.add(start("plains"));
        task.nodes.add(findStructure("plains", "minecraft:village_plains", "mark", "taiga"));
        task.nodes.add(findStructure("taiga", "minecraft:village_taiga", "mark", "savanna"));
        task.nodes.add(findStructure("savanna", "minecraft:village_savanna", "mark", "desert"));
        task.nodes.add(findStructure("desert", "minecraft:village_desert", "mark", "snowy"));
        task.nodes.add(findStructure("snowy", "minecraft:village_snowy", "mark", "on_foot"));
        task.nodes.add(explore("on_foot", VILLAGE_SIGNS, 12, 16, "mark", "none"));
        task.nodes.add(notify("none", SOUND_PROBLEM, "stop_none"));
        task.nodes.add(end("stop_none"));
        task.nodes.add(saveWaypoint("mark", VILLAGE, "done"));
        task.nodes.add(notify("done", SOUND_DONE, "end"));
        task.nodes.add(end("end"));
        task.nodes.add(guardOnPins(task, "plains", "taiga", "savanna", "desert", "snowy", "on_foot"));

        Lane lane = new Lane(task);
        lane.plain("start", null);
        lane.band("compass", "Asking the compass", PURPLE,
                cards("plains", "taiga", "savanna", "desert", "snowy"));
        lane.band("on_foot", "On foot", GREEN, cards("on_foot"), under("on_foot", 1, "none"),
                under("on_foot", 2, "stop_none"));
        lane.band("arrival", "Arrival", AMBER, cards("mark", "done"));
        lane.plain("end");
        lane.support("safety", "Safety", RED, 3, lane.start("compass") + 2, "guard");

        caption(task, "intro", 0, 2, AMBER,
                "Finds the nearest village and walks there. The Explorer's Compass in your pack is "
                        + "asked for each kind of village in turn; with no compass carried, Lune "
                        + "explores on foot looking for a bell or hay bales. Listed only while the "
                        + "Explorer's Compass mod is installed.");
        caption(task, "compass", lane.start("compass"), 3, PURPLE,
                "Each Find Structure card asks the compass for one kind of village. A card that "
                        + "finds none fails, and its red wire tries the next kind.");
        caption(task, "on_foot", lane.start("on_foot"), 1, GREEN,
                "Explore walks one way for a while and looks around at every stop.");
        caption(task, "arrival", lane.start("arrival"), 2, AMBER,
                "The spot is saved as the Village waypoint, so Go to Waypoint finds it again.");
        rowNote(task, "guard", lane.start("compass") + 3, 3, 2, RED,
                "Long walks cross strange ground at night. Self Preservation rides every card that "
                        + "does the walking.");
        lane.finish();
        return task;
    }

    /** Job 15: to a cherry grove and back, leaving the grove as it was found. */
    private static TaskGraph cherryTimber() {
        TaskGraph task = seeded(CHERRY_TIMBER, CHERRY_TIMBER_ID);
        task.nodes.add(start("home_mark"));
        task.nodes.add(saveWaypoint("home_mark", HOME, "find"));
        TaskNode find = node("find", "find_biome", Map.of(
                "biome", "minecraft:cherry_grove",
                "then", "Walk there",
                "tolerance", "16",
                "fresh", "false"));
        find.onSuccess = "grove_mark";
        find.onFailure = "on_foot";
        task.nodes.add(find);
        task.nodes.add(explore("on_foot", "minecraft:cherry_log", 10, 16, "grove_mark", "none"));
        task.nodes.add(notify("none", SOUND_PROBLEM, "stop_none"));
        task.nodes.add(end("stop_none"));
        task.nodes.add(saveWaypoint("grove_mark", GROVE, "axe"));
        task.nodes.add(forge("axe", "Axe", "Stone", "chop", "chop"));
        TaskNode chop = chop("chop", 24, "loot", "loot");
        chop.params.put("radius", "32");
        task.nodes.add(chop);
        task.nodes.add(sweep("loot", "replant"));
        task.nodes.add(replant("replant", 32, "go_home"));
        task.nodes.add(goTo("go_home", HOME, 3, "stash"));
        task.nodes.add(store("stash", "Logs", "done"));
        task.nodes.add(notify("done", SOUND_DONE, "end"));
        task.nodes.add(end("end"));
        task.nodes.add(guardOnPins(task, "find", "on_foot", "chop", "go_home"));

        Lane lane = new Lane(task);
        lane.plain("start", null);
        lane.band("search", "Finding the grove", PURPLE, cards("home_mark", "find", "on_foot"),
                under("on_foot", 1, "none"), under("on_foot", 2, "stop_none"));
        lane.band("lumber", "Chop and replant", GREEN, cards("grove_mark", "axe", "chop", "loot", "replant"));
        lane.band("home", "Home again", AMBER, cards("go_home", "stash", "done"));
        lane.plain("end");
        lane.support("safety", "Safety", RED, 3, lane.start("lumber"), "guard");

        caption(task, "intro", 0, 2, AMBER,
                "Travels to a cherry grove, cuts twenty-four logs, plants the trees back and brings "
                        + "the wood home. The Nature's Compass in your pack finds a grove far away; "
                        + "with no compass carried, Lune only finds one nearby. Listed only while "
                        + "the Nature's Compass mod is installed.");
        caption(task, "search", lane.start("search"), 3, PURPLE,
                "Home is saved first. Find Biome asks the compass for the nearest cherry grove and "
                        + "walks there. If that fails, Explore looks for cherry logs on foot.");
        caption(task, "lumber", lane.start("lumber"), 3, GREEN,
                "The Lumber Camp's chop, sweep and replant, so the grove is still a grove after "
                        + "Lune leaves.");
        caption(task, "home", lane.start("home"), 3, AMBER,
                "Go to Waypoint walks back Home, and the logs go in a chest if there is one.");
        rowNote(task, "guard", lane.start("lumber") + 1, 3, 2, RED,
                "Self Preservation rides the long walks and the chopping, so the trip still ends "
                        + "on its own.");
        lane.finish();
        return task;
    }

    // --- shared pieces of the chores -------------------------------------------------------------

    /**
     * Eat when hungry, and hunt when there is nothing to eat. Wired as a detour, so a full belly
     * costs one condition card and nothing else.
     */
    private static void addMeals(TaskGraph task, String id, String next) {
        task.nodes.add(hungry(id, 14, "eat", next));
        task.nodes.add(meal("eat", 18, next, "hunt"));
        task.nodes.add(hunt("hunt", LIVESTOCK, 32, "hunt_loot", next));
        task.nodes.add(sweep("hunt_loot", "eat_catch"));
        task.nodes.add(meal("eat_catch", 18, next));
    }

    /**
     * The end of a shift, reached two ways: from the clock when the hours are up, and from the
     * lane when the chest will take nothing more. Either way Notify sounds, and Stop the Game saves
     * and goes back to the main menu - the one ending that means the same thing on a server.
     */
    private static void addShiftEnd(TaskGraph task, int hours) {
        TaskNode clock = node("shift_clock", TaskNode.ALWAYS_COMMAND, Map.of());
        clock.alwaysTargets.add("shift");
        task.nodes.add(clock);
        task.nodes.add(countdown("shift", hours, "Hours", "closing"));
        task.nodes.add(notify("closing", SOUND_SHIFT_OVER, "leave"));
        task.nodes.add(stopGame("leave", "Return to main menu", "shift_end"));
        task.nodes.add(end("shift_end"));
    }

    // --- card shorthands -------------------------------------------------------------------------
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
        return explore(id, targets, attempts, 12, onSuccess, onFailure);
    }

    private static TaskNode explore(String id, String targets, int attempts, int step,
                                    String onSuccess, String onFailure) {
        TaskNode node = node(id, "explore", Map.of(
                "targets", targets,
                "radius", "48",
                "attempts", Integer.toString(attempts),
                "step", Integer.toString(step),
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

    private static TaskNode dimensionIs(String id, String dimension, String yes, String no) {
        TaskNode node = node(id, "check_dimension", Map.of("dimension", dimension));
        node.onSuccess = yes;
        node.onFailure = no;
        return node;
    }

    /** A reading of the player, true when it is at or below the threshold. */
    private static TaskNode playerAtMost(String id, String metric, int threshold,
                                         String yes, String no) {
        TaskNode node = node(id, "check_player", Map.of(
                "metric", metric,
                "comparison", "At most",
                "threshold", Integer.toString(threshold)));
        node.onSuccess = yes;
        node.onFailure = no;
        return node;
    }

    /**
     * Puts a carried item in the main hand, or fails when no copy is good enough.
     *
     * <p>Every use in the starter jobs is as a condition rather than as an action, which is why the
     * Fail wire is the interesting one: no fishing rod at all, or no diamond pickaxe with enough
     * life left in it to be worth taking to an obsidian face.</p>
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
     * saved location, and the anchor here is where the run started - which is the camp, because
     * every chore saves its camp as its first card.</p>
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
        return playerAtMost(id, "Hunger", threshold, yes, no);
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
        return deposit(id, filter, true, next, next);
    }

    /**
     * A drop-off whose failure means something. Not optional, so a chest that is full - or not
     * there at all - fails the card and the Fail wire decides what that means for the shift.
     */
    private static TaskNode deposit(String id, String filter, boolean optional,
                                    String onSuccess, String onFailure) {
        TaskNode deposit = node(id, "deposit", Map.of(
                "filter", filter,
                "radius", "16",
                "optional", Boolean.toString(optional)));
        deposit.onSuccess = onSuccess;
        deposit.onFailure = onFailure;
        return deposit;
    }

    /** A sound and nothing else. The note beside it says what the sound means, in every language. */
    private static TaskNode notify(String id, String sound, String next) {
        TaskNode node = node(id, "notify", Map.of("sound", sound));
        node.onSuccess = next;
        node.onFailure = next;
        return node;
    }

    /** Saves where the bot is standing under a fixed name, replacing an older one of that name. */
    private static TaskNode saveWaypoint(String id, String name, String next) {
        TaskNode node = node(id, "save_waypoint", Map.of(
                "action", "Save here",
                "name", name));
        node.onSuccess = next;
        node.onFailure = next;
        return node;
    }

    private static TaskNode goTo(String id, String waypoint, int tolerance, String next) {
        TaskNode node = node(id, "waypoint", Map.of(
                "name", waypoint,
                "tolerance", Integer.toString(tolerance)));
        node.onSuccess = next;
        node.onFailure = next;
        return node;
    }

    /** Wears the best armor carried. Carrying none fails, and that is fine: the job goes on. */
    private static TaskNode wearArmor(String id, String next) {
        TaskNode node = node(id, "equip", Map.of("what", "Best armor"));
        node.onSuccess = next;
        node.onFailure = next;
        return node;
    }

    private static TaskNode sleep(String id, String onSuccess, String onFailure) {
        TaskNode node = node(id, "sleep", Map.of(
                "wait_for_night", "true",
                "reclaim", "true",
                "radius", "32"));
        node.onSuccess = onSuccess;
        node.onFailure = onFailure;
        return node;
    }

    private static TaskNode countdown(String id, int amount, String unit, String next) {
        TaskNode node = node(id, "countdown", Map.of(
                "amount", Integer.toString(amount),
                "unit", unit));
        node.onSuccess = next;
        node.onFailure = next;
        return node;
    }

    private static TaskNode stopGame(String id, String ending, String next) {
        TaskNode node = node(id, "stop_game", Map.of("ending", ending));
        node.onSuccess = next;
        node.onFailure = next;
        return node;
    }

    private static TaskNode replant(String id, int radius, String next) {
        TaskNode node = node(id, "replant", Map.of("radius", Integer.toString(radius)));
        node.onSuccess = next;
        node.onFailure = next;
        return node;
    }

    private static TaskNode usePortal(String id, int radius, String onSuccess, String onFailure) {
        TaskNode node = node(id, "use_portal", Map.of("radius", Integer.toString(radius)));
        node.onSuccess = onSuccess;
        node.onFailure = onFailure;
        return node;
    }

    private static TaskNode portal(String id, String frameMode, String onSuccess, String onFailure) {
        TaskNode node = node(id, "portal", Map.of(
                "frame_mode", frameMode,
                "corner_materials", "minecraft:dirt,minecraft:cobblestone"));
        node.onSuccess = onSuccess;
        node.onFailure = onFailure;
        return node;
    }

    /** Crafts one of an item by name, at a crafting table it makes if it has to. */
    private static TaskNode craft(String id, String item, String onSuccess, String onFailure) {
        TaskNode node = node(id, "craft", Map.of(
                "recipe", "item|" + item + "|3;,,,,,,,,",
                "count", "1",
                "table", "true"));
        node.onSuccess = onSuccess;
        node.onFailure = onFailure;
        return node;
    }

    /**
     * Everything of one kind that is carried, a single item at a time.
     *
     * <p>Smelt asked for more than the pack holds puts in what there is and gives up, leaving the
     * rest in the furnace. Asking for one and repeating is what empties the pack: each pass
     * smelts one more, and the pass that finds none fails - which here is simply the end of that
     * metal, so both wires lead on.</p>
     */
    private static TaskNode smeltAll(String id, String input, String next) {
        TaskNode node = node(id, "smelt", Map.of(
                "input", input,
                "count", "1"));
        node.repeat = 64;
        node.onSuccess = next;
        node.onFailure = next;
        return node;
    }

    private static TaskNode findStructure(String id, String structure, String onSuccess,
                                          String onFailure) {
        TaskNode node = node(id, "find_structure", Map.of(
                "structure", structure,
                "then", "Walk there",
                "tolerance", "24",
                "fresh", "false"));
        node.onSuccess = onSuccess;
        node.onFailure = onFailure;
        return node;
    }

    private static TaskNode end(String id) {
        return node(id, TaskNode.END_COMMAND, Map.of());
    }

    /**
     * The explicit entry marker. Every job writes its own, so the main lane has one obvious
     * beginning whether or not the job also owns a clock.
     */
    private static TaskNode start(String firstCard) {
        TaskNode start = node("start", TaskNode.START_COMMAND, Map.of());
        start.onSuccess = firstCard;
        return start;
    }

    /**
     * Adds the safety monitor as its own circuit, wired to nothing in the main lane.
     *
     * <p>An Always source holding one Self Preservation card. Hanging the guard off While pins
     * instead scales badly in both directions on a long job: ten protected cards end up with ten
     * cables converging on one node, and it only protects the cards somebody remembered to tick.
     * Always is held on for the whole run, so one wire covers everything the job does, including
     * the leg added next week. The price is that the run never finishes by itself, which is why
     * only the chores and the expeditions use it.</p>
     */
    private static void addSafetyCircuit(TaskGraph task) {
        TaskNode clock = node("safety_clock", TaskNode.ALWAYS_COMMAND, Map.of());
        clock.alwaysTargets.add("guard");
        task.nodes.add(clock);
        task.nodes.add(guardCard());
    }

    /**
     * A guard for a job that should still finish: Self Preservation on the While pins of the
     * cards that do the risky work, and on nothing else.
     *
     * <p>A While companion runs beside its card rather than holding the run open, so a job guarded
     * this way ends when its lane does. The short jobs have few enough such cards that the cables
     * stay readable.</p>
     */
    private static TaskNode guardOnPins(TaskGraph task, String... covered) {
        TaskNode guard = guardCard();
        protectWith(guard, task, covered);
        return guard;
    }

    private static TaskNode guardCard() {
        TaskNode guard = node("guard", "self_preservation", safetyParams());
        // A companion runs beside the work rather than after it, so its repeat box is not a
        // lifetime and x1 would be wrong.
        guard.repeat = 0;
        return guard;
    }

    private static Map<String, String> safetyParams() {
        return Map.ofEntries(
                Map.entry("protect_air", "true"),
                Map.entry("air_compare", "At most"),
                Map.entry("air_value", "120"),
                Map.entry("protect_lava", "true"),
                Map.entry("protect_fire", "true"),
                Map.entry("protect_fall", "true"),
                Map.entry("fall_threshold", "10"),
                Map.entry("protect_monsters", "true"),
                Map.entry("monster_compare", "At most"),
                Map.entry("monster_distance", "8"),
                Map.entry("protect_health", "true"),
                Map.entry("health_compare", "At most"),
                Map.entry("health_value", "8"));
    }

    /** Hangs a companion circuit off the While pin of each named card. */
    private static void protectWith(TaskNode companion, TaskGraph task, String... covered) {
        for (String id : covered) {
            TaskNode node = task.nodeById(id);
            if (node == null) {
                throw new IllegalStateException(task.name + " has no card " + id + " to protect");
            }
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

    private static TaskNode node(String id, String commandId, Map<String, String> params) {
        TaskNode node = new TaskNode(commandId);
        node.id = id;
        node.params.putAll(params);
        return node;
    }

    // --- notes -----------------------------------------------------------------------------------

    /**
     * A note in the caption band, over the section it explains.
     *
     * <p>The English is stored as the note's own text - what a player sees if the language line is
     * ever missing - and the id is what draws it in their language: {@code lune.task.note.} plus
     * the job's id and this one.</p>
     */
    private static void caption(TaskGraph task, String id, int column, int span, int colour,
                                String english) {
        addNote(task, id, LEFT + column * STEP_X, CAPTION_TOP, span, CAPTION_HEIGHT, colour, english);
    }

    /** A note in one of the rows under the lane, beside the circuit it explains. */
    private static void rowNote(TaskGraph task, String id, int column, int row, int span,
                                int colour, String english) {
        addNote(task, id, LEFT + column * STEP_X, LANE_Y + row * STEP_Y, span, ROW_NOTE_HEIGHT,
                colour, english);
    }

    private static void addNote(TaskGraph task, String id, int x, int y, int span, int height,
                                int colour, String english) {
        TaskNote note = new TaskNote(x, y);
        note.id = id;
        note.seededId = task.seededId + "." + id;
        note.width = span * STEP_X - NOTE_GAP;
        note.height = height;
        note.colour = colour;
        note.text = english;
        task.notes.add(note);
    }

    // --- layout ----------------------------------------------------------------------------------

    private static String[] cards(String... ids) {
        return ids;
    }

    private static Detour under(String anchor, int rowsDown, String... ids) {
        return new Detour(anchor, rowsDown, ids);
    }

    /** Cards hung under a lane card, running right from it. */
    private record Detour(String anchor, int rowsDown, String[] ids) {}

    /**
     * Lays a job out as one lane that only ever moves right.
     *
     * <p>A band is a section of the lane with a frame round it: its cards in order along the lane,
     * and the detours hanging directly beneath the card that chooses them. A band that hangs wider
     * than its own lane cards pushes the next band right, so frames never overlap and a detour is
     * never sitting under somebody else's section. {@code null} in a plain run leaves an empty
     * column - after START, to give the job's first note room.</p>
     *
     * <p>Laying a long job out as bands that each restarted at column zero made every band
     * boundary a cable sweeping back across the whole canvas - a typewriter carriage return drawn
     * in dotted line. That is the one thing this layout is built never to do.</p>
     */
    private static final class Lane {
        private final TaskGraph task;
        private final Map<String, int[]> bands = new LinkedHashMap<>();
        private final Map<String, TaskGroup> groups = new LinkedHashMap<>();
        private int next;
        private int lowest;

        Lane(TaskGraph task) {
            this.task = task;
        }

        /** Cards along the lane with no frame: START, the last sound, End. */
        void plain(String... ids) {
            for (String id : ids) {
                if (id != null) {
                    place(id, next, 0);
                }
                next++;
            }
        }

        void band(String group, String title, int colour, String[] lane, Detour... detours) {
            int first = next;
            int last = next + lane.length - 1;
            List<String> members = new ArrayList<>();
            for (String id : lane) {
                place(id, next++, 0);
                members.add(id);
            }
            for (Detour detour : detours) {
                int column = column(detour.anchor());
                for (int i = 0; i < detour.ids().length; i++) {
                    place(detour.ids()[i], column + i, detour.rowsDown());
                    members.add(detour.ids()[i]);
                    last = Math.max(last, column + i);
                }
            }
            next = last + 1;
            bands.put(group, new int[]{first, last});
            frame(group, title, colour, members);
        }

        /** A circuit that runs beside the lane, in its own row and its own frame. */
        void support(String group, String title, int colour, int row, int column, String... ids) {
            List<String> members = new ArrayList<>();
            for (int i = 0; i < ids.length; i++) {
                place(ids[i], column + i, row);
                members.add(ids[i]);
            }
            frame(group, title, colour, members);
        }

        /** Adds cards placed by hand to a frame already drawn. */
        void join(String group, String... ids) {
            groups.get(group).members.addAll(List.of(ids));
        }

        int start(String group) {
            return bands.get(group)[0];
        }

        int column(String id) {
            TaskNode node = require(id);
            return (node.editorX - LEFT) / STEP_X;
        }

        /** The lowest row any card has been placed in so far, for starting the rows beneath it. */
        int lowestRow() {
            return lowest;
        }

        void place(String id, int column, int row) {
            TaskNode node = require(id);
            node.editorX = LEFT + column * STEP_X;
            node.editorY = LANE_Y + row * STEP_Y;
            lowest = Math.max(lowest, row);
        }

        /** Measures every frame round its cards, so a fresh job opens with its frames in place. */
        void finish() {
            for (TaskNode node : task.nodes) {
                if (node.editorX == null || node.editorY == null) {
                    throw new IllegalStateException(task.name + ": " + node.id + " was never placed");
                }
            }
            for (TaskGroup group : task.groups) {
                group.fit(task, node -> TaskCanvas.CARD_HEIGHT);
            }
        }

        private void frame(String group, String title, int colour, List<String> members) {
            TaskGroup frame = new TaskGroup(title);
            frame.id = group;
            frame.seededId = group;
            frame.colour = colour;
            frame.members.addAll(members);
            task.groups.add(frame);
            groups.put(group, frame);
        }

        private TaskNode require(String id) {
            TaskNode node = task.nodeById(id);
            if (node == null) {
                throw new IllegalStateException(task.name + " has no card " + id);
            }
            return node;
        }
    }

    // --- cables ----------------------------------------------------------------------------------

    /** How far right of a card's output pin the cable turns down into the gutter. */
    private static final int GUTTER_ENTRY_X = 24;

    /** Lanes inside the gutter, so cables sharing a return run do not stack on one line. */
    private static final int GUTTER_LANE_HEIGHT = 12;
    private static final int GUTTER_LANES = 4;

    /** Ignore the short hop a detour makes rejoining the lane; that one already looks fine. */
    private static final int SHORTEST_ROUTED_RETURN = 2 * STEP_X;

    /**
     * Walks the cables that run backwards down into the gutter between the rows.
     *
     * <p>A cable with no routing points leaves its pin sideways and arrives sideways, which is the
     * right shape when the target is to the right. When it is a long way to the <em>left</em> - a
     * chore's loop back to the top of its shift - that same shape becomes a wide flat bow lying
     * along the row it came from, straight across every card in between. So those cables get four
     * points: a stub out of the pin, a corner down into the empty band below the row, the run back,
     * and a stub into the target. Every x sits in the gap between two columns and every y in the
     * gap between two rows, so a route never crosses a card it is trying to avoid.</p>
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
}
