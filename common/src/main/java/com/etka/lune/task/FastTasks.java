package com.etka.lune.task;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static com.etka.lune.task.DefaultTasks.AMBER;
import static com.etka.lune.task.DefaultTasks.CROPS;
import static com.etka.lune.task.DefaultTasks.IRON_ORES;
import static com.etka.lune.task.DefaultTasks.LOGS;
import static com.etka.lune.task.DefaultTasks.RED;
import static com.etka.lune.task.DefaultTasks.caption;
import static com.etka.lune.task.DefaultTasks.carrying;
import static com.etka.lune.task.DefaultTasks.chop;
import static com.etka.lune.task.DefaultTasks.deposit;
import static com.etka.lune.task.DefaultTasks.end;
import static com.etka.lune.task.DefaultTasks.forge;
import static com.etka.lune.task.DefaultTasks.guardOnPins;
import static com.etka.lune.task.DefaultTasks.meal;
import static com.etka.lune.task.DefaultTasks.mine;
import static com.etka.lune.task.DefaultTasks.node;
import static com.etka.lune.task.DefaultTasks.routeReturnCables;
import static com.etka.lune.task.DefaultTasks.search;
import static com.etka.lune.task.DefaultTasks.sleep;
import static com.etka.lune.task.DefaultTasks.start;
import static com.etka.lune.task.DefaultTasks.sweep;

/**
 * Lune's fast tasks: ten small jobs that are ready to go - Mine 25 Deepslate, Craft Stone Tools,
 * Go to Last Death - for the moments a whole task of your own would be too much.
 *
 * <p>They are tasks like any other, built from the same cards and the same shorthands as the
 * starter shelf ({@link DefaultTasks}), and they explain themselves the same way: a title and one
 * note, drawn in the player's language from the id each carries. What sets them apart is where
 * they live. The starter shelf is seeded into the player's task list; these never are. The user
 * asked for exactly that (2026-10-15): small jobs to add to what is running, which do not crowd the
 * list. So a fast task is run straight from the picker, added to the queue behind the run that is
 * going, or - only when the player asks - copied into their list, where it is theirs from then on
 * ({@link #forTaskList}).</p>
 *
 * <p>Every one of them keeps three rules, and {@code FastTasksTest} holds it to them:</p>
 * <ul>
 *   <li><b>Small.</b> A handful of cards on one lane, never a circuit to study.</li>
 *   <li><b>It finishes.</b> No Always, Pulse, Observer or Button card, so the run ends with its last
 *       card and the finish alert sounds. A card whose failure means the job cannot be done has no
 *       Fail wire: the run fails with that card's own reason, rather than finishing as though it
 *       had done something.</li>
 *   <li><b>Guarded where it goes out.</b> Self Preservation rides the While pins of the cards that
 *       walk, dig or gather outdoors, the way the demos are guarded. The jobs that stay where they
 *       are started have no guard to configure.</li>
 * </ul>
 *
 * <p>A new one is a builder here, a line in {@link #SHELF}, and its title and note in both
 * language files. The title is {@code lune.task.seeded.} plus its id and the note
 * {@code lune.task.note.} plus its id and {@code .intro}, as for a starter job, and the note is
 * what the picker shows as its description.</p>
 */
public final class FastTasks {

    /**
     * One fast task: the id its title and note are drawn from, the item the picker shows beside it,
     * and how it is built.
     *
     * <p>The icon is an item id rather than an item, so the shelf can be read without the game's
     * registries - by the tests, and by anything that only wants the names.</p>
     */
    public record Fast(String id, String icon, Supplier<TaskGraph> build) {

        /** A fresh graph every time, so a run, a copy and the picker never share a card. */
        public TaskGraph graph() {
            return build.get();
        }
    }

    /** The id of the one note every fast task carries, which is also its description. */
    static final String INTRO = "intro";

    /**
     * Names, as a fast task run straight from the picker carries them.
     *
     * <p>English, and staying English, for the reason a starter job's name does: it is the key the
     * learner files the run under ({@code TaskRunner.learningId}), and a key in the player's
     * language would start a fresh row for every language they play in. The player reads the
     * {@code lune.task.seeded.*} line the id opposite resolves to.</p>
     */
    static final String CHOP_LOGS = "Chop 16 Logs";
    static final String MINE_DEEPSLATE = "Mine 25 Deepslate";
    static final String MINE_IRON = "Mine 10 Iron Ore";
    static final String SMELT_IRON = "Smelt Raw Iron";
    static final String STONE_TOOLS = "Craft Stone Tools";
    static final String HARVEST = "Harvest Crops";
    static final String EAT = "Eat Until Full";
    static final String SLEEP = "Sleep Through the Night";
    static final String STORE_LOOT = "Store Loot in a Chest";
    static final String LAST_DEATH = "Go to Last Death";

    /**
     * The id each is known by. Prefixed, because the titles share {@code lune.task.seeded.} with the
     * starter shelf, and job 2 already answers to {@code stone_tools}.
     */
    static final String CHOP_LOGS_ID = "fast_chop_logs";
    static final String MINE_DEEPSLATE_ID = "fast_mine_deepslate";
    static final String MINE_IRON_ID = "fast_mine_iron";
    static final String SMELT_IRON_ID = "fast_smelt_iron";
    static final String STONE_TOOLS_ID = "fast_stone_tools";
    static final String HARVEST_ID = "fast_harvest";
    static final String EAT_ID = "fast_eat";
    static final String SLEEP_ID = "fast_sleep";
    static final String STORE_LOOT_ID = "fast_store_loot";
    static final String LAST_DEATH_ID = "fast_last_death";

    private static final String DEEPSLATE = "minecraft:deepslate";

    /**
     * The shelf, in the order the picker lays it out: two to a row, gathering first, then making,
     * the farm, looking after yourself, and putting things away or getting them back.
     */
    private static final List<Fast> SHELF = List.of(
            new Fast(CHOP_LOGS_ID, "minecraft:oak_log", FastTasks::chopLogs),
            new Fast(MINE_DEEPSLATE_ID, "minecraft:deepslate", FastTasks::mineDeepslate),
            new Fast(MINE_IRON_ID, "minecraft:iron_ore", FastTasks::mineIron),
            new Fast(SMELT_IRON_ID, "minecraft:iron_ingot", FastTasks::smeltIron),
            new Fast(STONE_TOOLS_ID, "minecraft:stone_pickaxe", FastTasks::stoneTools),
            new Fast(HARVEST_ID, "minecraft:wheat", FastTasks::harvest),
            new Fast(EAT_ID, "minecraft:bread", FastTasks::eat),
            new Fast(SLEEP_ID, "minecraft:red_bed", FastTasks::sleepTheNight),
            new Fast(STORE_LOOT_ID, "minecraft:chest", FastTasks::storeLoot),
            new Fast(LAST_DEATH_ID, "minecraft:recovery_compass", FastTasks::lastDeath));

    private FastTasks() {}

    public static List<Fast> all() {
        return SHELF;
    }

    public static Optional<Fast> byId(String id) {
        return SHELF.stream().filter(fast -> fast.id().equals(id)).findFirst();
    }

    /** A fresh graph of every fast task, in shelf order. */
    public static List<TaskGraph> create() {
        return SHELF.stream().map(Fast::graph).toList();
    }

    /**
     * The copy Add to my tasks puts in the player's list.
     *
     * <p>Their own task from then on, the way New Task is: named in their language at the moment it
     * was added, and carrying no id. A title drawn from the id would read the same on every copy, so
     * a second copy would be a second row nobody could tell from the first. The note keeps its id
     * and stays in their language until they edit it, as a starter job's notes do.</p>
     */
    public static TaskGraph forTaskList(Fast fast) {
        TaskGraph task = fast.graph();
        task.name = task.displayName();
        task.seededId = null;
        return task;
    }

    /** What a fast task does, in the player's language: its note, the one the canvas shows too. */
    public static String about(TaskGraph task) {
        TaskNote intro = task == null ? null : task.noteById(INTRO);
        return intro == null ? "" : intro.displayText();
    }

    // =============================================================================================
    // Gathering
    // =============================================================================================

    private static TaskGraph chopLogs() {
        TaskGraph task = fast(CHOP_LOGS, CHOP_LOGS_ID);
        task.nodes.add(start("chop"));
        task.nodes.add(chop("chop", 16, "loot", "look"));
        // Explore hands back the moment a tree is in sight. Five walks with none is the job's
        // honest answer, so its Fail wire is left for the run to report.
        task.nodes.add(search("look", LOGS, 5, "chop", null));
        task.nodes.add(sweep("loot", "end"));
        task.nodes.add(end("end"));
        task.nodes.add(guardOnPins(task, "chop", "look", "loot"));

        DefaultTasks.Lane lane = new DefaultTasks.Lane(task);
        lane.plain("start", "chop", "loot", "end");
        lane.place("look", lane.column("chop"), 1);
        lane.support("safety", "Safety", RED, 2, lane.column("chop"), "guard");
        intro(task, "Chops sixteen logs and picks up what falls. With no tree in sight, Explore "
                + "walks off to find one first. No axe needed: punching a tree is slow, not "
                + "impossible.");
        return finish(lane, task);
    }

    /**
     * Twenty-five deepslate from wherever it is started.
     *
     * <p>Deepslate has a depth in OreKnowledge, so with none in sight Mine digs its staircase down
     * toward Y 0. One Mine card's staircase is eight short segments or two minutes, though, and
     * from a sea-level surface that runs out around Y 20 - where Mine finishes, because "nothing in
     * sight" is a finding to it, not a failure. So the job counts, the way job 2 counts its stone,
     * and a second card carries on down from where the first stopped. With Silk Touch the drops are
     * deepslate rather than cobbled, the count stays short, and the second card mines another
     * twenty-five: more than asked for, never a run that fails over it.</p>
     */
    private static TaskGraph mineDeepslate() {
        TaskGraph task = fast(MINE_DEEPSLATE, MINE_DEEPSLATE_ID);
        task.nodes.add(start("mine"));
        task.nodes.add(mine("mine", DEEPSLATE, 32, -64, 320, 25, true, "count", null));
        task.nodes.add(carrying("count", "minecraft:cobbled_deepslate", 25, "end", "deeper"));
        task.nodes.add(mine("deeper", DEEPSLATE, 32, -64, 320, 25, true, "end", null));
        task.nodes.add(end("end"));
        task.nodes.add(guardOnPins(task, "mine", "deeper"));

        DefaultTasks.Lane lane = new DefaultTasks.Lane(task);
        lane.plain("start", "mine", "count", "end");
        lane.place("deeper", lane.column("count"), 1);
        lane.support("safety", "Safety", RED, 2, lane.column("mine"), "guard");
        intro(task, "Mines twenty-five deepslate. With none in sight it digs a staircase down toward "
                + "Y 0, where deepslate begins, looking as it goes. Fewer than twenty-five cobbled "
                + "deepslate in the pack after that, and a second Mine card digs on from where the "
                + "first stopped.");
        return finish(lane, task);
    }

    private static TaskGraph mineIron() {
        TaskGraph task = fast(MINE_IRON, MINE_IRON_ID);
        task.nodes.add(start("mine"));
        // Iron has a depth, so with none in sight Mine digs a staircase down to it, and it makes
        // the stone pickaxe iron needs on its own (auto tool). Unlike deepslate it needs no second
        // card: its band starts at Y 64 and peaks at 16, which one card's staircase reaches from
        // sea level, seeing iron in its walls most of the way.
        task.nodes.add(mine("mine", IRON_ORES, 32, -64, 320, 10, true, "end", null));
        task.nodes.add(end("end"));
        task.nodes.add(guardOnPins(task, "mine"));

        DefaultTasks.Lane lane = new DefaultTasks.Lane(task);
        lane.plain("start", "mine", "end");
        lane.support("safety", "Safety", RED, 1, lane.column("mine"), "guard");
        intro(task, "Mines ten iron ore it can see. With none in sight it digs a staircase down to "
                + "the iron layer, looking as it goes. Makes a stone pickaxe first if it needs one.");
        return finish(lane, task);
    }

    // =============================================================================================
    // Making
    // =============================================================================================

    /**
     * Every raw iron in the pack, one at a time.
     *
     * <p>Not the Smeltery's repeat box. There a failed pass means the pack is empty and both wires
     * lead on, which is right for a job smelting three metals in turn - but it reads a missing
     * furnace as "all done" too, and a fast task that finishes having smelted nothing is lying. So
     * Smelt has no Fail wire here, and running out is asked about separately: the check under it
     * loops back while raw iron is left and ends the job when none is. The first check makes an
     * empty pack a failure with its own reason, rather than a finish.</p>
     */
    private static TaskGraph smeltIron() {
        TaskGraph task = fast(SMELT_IRON, SMELT_IRON_ID);
        task.nodes.add(start("raw_iron"));
        task.nodes.add(carrying("raw_iron", "minecraft:raw_iron", 1, "smelt", null));
        TaskNode smelt = node("smelt", "smelt", Map.of(
                "input", "Raw Iron",
                "count", "1"));
        smelt.onSuccess = "more";
        task.nodes.add(smelt);
        task.nodes.add(carrying("more", "minecraft:raw_iron", 1, "smelt", "end"));
        task.nodes.add(end("end"));

        DefaultTasks.Lane lane = new DefaultTasks.Lane(task);
        lane.plain("start", "raw_iron", "smelt", "end");
        lane.place("more", lane.column("smelt"), 1);
        intro(task, "Smelts all the raw iron in the pack into ingots, at a furnace nearby or one "
                + "placed from the pack. Bring fuel: coal, charcoal or wood will do.");
        return finish(lane, task);
    }

    /**
     * Four Get Tools cards, each of which gathers what it is missing: trees, a crafting table,
     * stone. A tool as good or better already carried answers its card at once.
     */
    private static TaskGraph stoneTools() {
        TaskGraph task = fast(STONE_TOOLS, STONE_TOOLS_ID);
        task.nodes.add(start("pickaxe"));
        task.nodes.add(forge("pickaxe", "Pickaxe", "Stone", "axe", null));
        task.nodes.add(forge("axe", "Axe", "Stone", "shovel", null));
        task.nodes.add(forge("shovel", "Shovel", "Stone", "sword", null));
        task.nodes.add(forge("sword", "Sword", "Stone", "end", null));
        task.nodes.add(end("end"));
        task.nodes.add(guardOnPins(task, "pickaxe", "axe", "shovel", "sword"));

        DefaultTasks.Lane lane = new DefaultTasks.Lane(task);
        lane.plain("start", "pickaxe", "axe", "shovel", "sword", "end");
        lane.support("safety", "Safety", RED, 1, lane.column("pickaxe"), "guard");
        intro(task, "Makes a stone pickaxe, axe, shovel and sword, gathering the wood and stone "
                + "they need on the way. A tool as good or better already in the pack is kept, "
                + "not made again.");
        return finish(lane, task);
    }

    // =============================================================================================
    // The farm, and looking after yourself
    // =============================================================================================

    private static TaskGraph harvest() {
        TaskGraph task = fast(HARVEST, HARVEST_ID);
        task.nodes.add(start("harvest"));
        TaskNode harvest = node("harvest", "harvest", Map.of(
                "targets", CROPS,
                "radius", "32",
                "limit", "0",
                "collect", "true",
                "replant", "true"));
        harvest.onSuccess = "loot";
        task.nodes.add(harvest);
        // The homestead's sweep after the field, for the seeds that bounced out of reach.
        task.nodes.add(sweep("loot", "end"));
        task.nodes.add(end("end"));
        task.nodes.add(guardOnPins(task, "harvest", "loot"));

        DefaultTasks.Lane lane = new DefaultTasks.Lane(task);
        lane.plain("start", "harvest", "loot", "end");
        lane.support("safety", "Safety", RED, 1, lane.column("harvest"), "guard");
        intro(task, "Harvests the ripe wheat, carrots, potatoes and beetroots in sight, picks them "
                + "up and plants each one back. Stand by the farm and press Run.");
        return finish(lane, task);
    }

    private static TaskGraph eat() {
        TaskGraph task = fast(EAT, EAT_ID);
        task.nodes.add(start("eat"));
        task.nodes.add(meal("eat", 20, "end", null));
        task.nodes.add(end("end"));

        DefaultTasks.Lane lane = new DefaultTasks.Lane(task);
        lane.plain("start", "eat", "end");
        intro(task, "Eats from the pack until the hunger bar is full. With nothing to eat, it stops "
                + "and says so.");
        return finish(lane, task);
    }

    /** Sleep makes its own bed from sheep when there is none, which is a walk outdoors at dusk. */
    private static TaskGraph sleepTheNight() {
        TaskGraph task = fast(SLEEP, SLEEP_ID);
        task.nodes.add(start("sleep"));
        task.nodes.add(sleep("sleep", "end", null));
        task.nodes.add(end("end"));
        task.nodes.add(guardOnPins(task, "sleep"));

        DefaultTasks.Lane lane = new DefaultTasks.Lane(task);
        lane.plain("start", "sleep", "end");
        lane.support("safety", "Safety", RED, 1, lane.column("sleep"), "guard");
        intro(task, "Finds a bed, or makes one from the wool of three sheep, waits for dark if it "
                + "has to and sleeps until morning, then takes back any bed it put down.");
        return finish(lane, task);
    }

    // =============================================================================================
    // Putting things away, and getting them back
    // =============================================================================================

    private static TaskGraph storeLoot() {
        TaskGraph task = fast(STORE_LOOT, STORE_LOOT_ID);
        task.nodes.add(start("store"));
        task.nodes.add(deposit("store", "Loot", false, "end", null));
        task.nodes.add(end("end"));

        DefaultTasks.Lane lane = new DefaultTasks.Lane(task);
        lane.plain("start", "store", "end");
        intro(task, "Walks to the nearest chest or barrel and puts away everything but tools, "
                + "weapons, armor and food.");
        return finish(lane, task);
    }

    /** The walk Recover Death Drop takes may dig, and whatever killed you may still be there. */
    private static TaskGraph lastDeath() {
        TaskGraph task = fast(LAST_DEATH, LAST_DEATH_ID);
        task.nodes.add(start("recover"));
        TaskNode recover = node("recover", "recover_death", Map.of(
                "which", "Most recent",
                "radius", "8"));
        recover.onSuccess = "end";
        task.nodes.add(recover);
        task.nodes.add(end("end"));
        task.nodes.add(guardOnPins(task, "recover"));

        DefaultTasks.Lane lane = new DefaultTasks.Lane(task);
        lane.plain("start", "recover", "end");
        lane.support("safety", "Safety", RED, 1, lane.column("recover"), "guard");
        intro(task, "Walks back to where you last died, digging if it has to, and picks up what is "
                + "still there. Dropped items vanish five minutes after a death, so be quick.");
        return finish(lane, task);
    }

    // --- shared ----------------------------------------------------------------------------------

    private static TaskGraph fast(String name, String id) {
        TaskGraph task = new TaskGraph(name);
        task.seededId = id;
        return task;
    }

    /** The one note: above the lane, where the canvas opens, and the picker's description. */
    private static void intro(TaskGraph task, String english) {
        caption(task, INTRO, 0, 2, AMBER, english);
    }

    private static TaskGraph finish(DefaultTasks.Lane lane, TaskGraph task) {
        lane.finish();
        routeReturnCables(task);
        return task;
    }
}
