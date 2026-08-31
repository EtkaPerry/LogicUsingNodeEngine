package com.etka.lune.bot.command;

import com.etka.lune.bot.catalog.BlockCatalog;
import com.etka.lune.bot.catalog.BlockTarget;
import com.etka.lune.bot.catalog.ToolCatalog;
import com.etka.lune.bot.path.Goals;
import com.etka.lune.bot.task.TaskRunner;
import com.etka.lune.task.TaskStore;
import com.etka.lune.bot.task.FailTask;
import com.etka.lune.bot.task.EnsureToolTask;
import com.etka.lune.bot.task.FindTask;
import com.etka.lune.bot.task.FishTask;
import com.etka.lune.bot.task.HuntEndermenTask;
import com.etka.lune.bot.task.HuntBlazesTask;
import com.etka.lune.bot.task.HuntCreepersTask;
import com.etka.lune.bot.task.HuntSkeletonsTask;
import com.etka.lune.bot.task.HuntSheepTask;
import com.etka.lune.bot.task.KillTask;
import com.etka.lune.bot.task.KillOptions;
import com.etka.lune.bot.task.LootTask;
import com.etka.lune.bot.task.StripmineTask;
import com.etka.lune.bot.task.TunnelTask;
import com.etka.lune.bot.task.GotoTask;
import com.etka.lune.bot.task.SpeedrunTask;
import com.etka.lune.bot.task.ConditionTask;
import com.etka.lune.bot.task.EatTask;
import com.etka.lune.bot.task.BoatTask;
import com.etka.lune.bot.task.BridgeTask;
import com.etka.lune.bot.task.BuildPortalTask;
import com.etka.lune.bot.task.DepositTask;
import com.etka.lune.bot.task.DirectionalGotoTask;
import com.etka.lune.bot.task.ExploreTask;
import com.etka.lune.bot.util.HeadScanner;
import com.etka.lune.bot.task.SmeltTask;
import com.etka.lune.bot.task.SelectItemTask;
import com.etka.lune.bot.task.StopGameTask;
import com.etka.lune.bot.task.SleepTask;
import com.etka.lune.bot.task.SelfPreservationTask;
import com.etka.lune.bot.task.StayNearTask;
import com.etka.lune.bot.task.HarvestTask;
import com.etka.lune.bot.task.MineTask;
import com.etka.lune.bot.task.TimerTask;
import com.etka.lune.waypoint.WaypointStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Every command the Tasks palette can offer.
 * <p>
 * This is the only place a new command needs to be added: declare its parameters and supply a
 * factory, and the UI builds the editor for it automatically.
 */
public final class CommandRegistry {

    /**
     * Ore choices, scanned from the live registry so another mod's ores appear without Lune
     * knowing about that mod. Resolved on first use, which is when the panel is first opened -
     * comfortably after registries are populated.
     */
    private static List<Block> ores() {
        return BlockCatalog.ores();
    }

    private static List<Item> items() {
        return BuiltInRegistries.ITEM.stream()
                .filter(item -> item != Items.AIR)
                .sorted(Comparator.comparing(item -> BuiltInRegistries.ITEM.getKey(item).toString()))
                .toList();
    }

    private static final List<EntityType<?>> MOBS = List.of(
            EntityType.ZOMBIE, EntityType.ZOMBIE_VILLAGER, EntityType.HUSK, EntityType.DROWNED,
            EntityType.SKELETON, EntityType.STRAY, EntityType.BOGGED, EntityType.WITHER_SKELETON,
            EntityType.CREEPER, EntityType.SPIDER, EntityType.CAVE_SPIDER, EntityType.ENDERMAN,
            EntityType.ENDERMITE, EntityType.SLIME, EntityType.MAGMA_CUBE, EntityType.WITCH,
            EntityType.BLAZE, EntityType.GHAST, EntityType.PHANTOM, EntityType.BREEZE,
            EntityType.PIGLIN, EntityType.PIGLIN_BRUTE, EntityType.ZOMBIFIED_PIGLIN,
            EntityType.HOGLIN, EntityType.ZOGLIN, EntityType.GUARDIAN, EntityType.ELDER_GUARDIAN,
            EntityType.SHULKER, EntityType.SILVERFISH, EntityType.VEX, EntityType.VINDICATOR,
            EntityType.EVOKER, EntityType.PILLAGER, EntityType.RAVAGER, EntityType.WARDEN,
            EntityType.COW, EntityType.PIG, EntityType.SHEEP, EntityType.CHICKEN,
            EntityType.HORSE, EntityType.DONKEY, EntityType.MULE, EntityType.RABBIT,
            EntityType.WOLF, EntityType.CAT, EntityType.GOAT, EntityType.TURTLE,
            EntityType.FOX, EntityType.BEE, EntityType.CAMEL, EntityType.ARMADILLO,
            EntityType.VILLAGER, EntityType.IRON_GOLEM
    );

    private static final List<String> ENDERMAN_SAFETY = List.of(
            "Auto", "Boat", "Two-block shelter", "Direct melee");

    private static final List<String> WEAPONS = List.of(
            "Automatic", "Sword", "Axe", "Bow");

    /**
     * Get Tools can be pointed at a tool directly, or left to work one out from a block. The block
     * mode is first and is the default, so tasks saved before tools could be named keep the
     * behaviour they were built with. Public because a stored task that omits the parameter
     * inherits whatever the palette last held, so anything shipping a Get Tools node has to say
     * which mode it means.
     */
    public static final String TOOL_FROM_BLOCK = "For a block";

    private static final List<String> TOOL_CHOICES = Stream.concat(
            Stream.of(TOOL_FROM_BLOCK), ToolCatalog.kindNames().stream()).toList();

    public static final Set<EntityType<?>> ENDERMAN = Set.of(EntityType.ENDERMAN);

    private static final List<String> COMPARISONS = List.of(
            "Less than", "At most", "Equal to", "At least", "Greater than", "Not equal");

    /** Widths of look, narrowest first. Each one widens on its own when it finds nothing. */
    private static final List<String> SCAN_STYLES = List.of(
            "Glance ahead", "Look around", "Full turn");

    private static HeadScanner.Style scanStyle(String choice) {
        return switch (choice) {
            case "Look around" -> HeadScanner.Style.SWEEP;
            case "Full turn" -> HeadScanner.Style.FULL;
            default -> HeadScanner.Style.GLANCE;
        };
    }

    private record SmeltRecipe(Set<Item> inputs, Item output) {}

    private static final Map<String, SmeltRecipe> SMELT_RECIPES = Map.of(
            "Raw Iron", new SmeltRecipe(Set.of(Items.RAW_IRON, Items.IRON_ORE, Items.DEEPSLATE_IRON_ORE), Items.IRON_INGOT),
            "Raw Gold", new SmeltRecipe(Set.of(Items.RAW_GOLD, Items.GOLD_ORE, Items.DEEPSLATE_GOLD_ORE), Items.GOLD_INGOT),
            "Raw Copper", new SmeltRecipe(Set.of(Items.RAW_COPPER, Items.COPPER_ORE, Items.DEEPSLATE_COPPER_ORE), Items.COPPER_INGOT),
            "Cobblestone", new SmeltRecipe(Set.of(Items.COBBLESTONE), Items.STONE)
    );

    private static final List<CommandDef> COMMANDS = new ArrayList<>();

    /**
     * Presentation groups used by the task node palette. Keeping this beside the command
     * declarations means a new command has one obvious place to add its folder assignment, while
     * commands without an assignment still appear under Other instead of disappearing.
     */
    private static final Map<String, String> COMMAND_CATEGORIES = Map.ofEntries(
            Map.entry("walk", "Movement"),
            Map.entry("run", "Movement"),
            Map.entry("waypoint", "Movement"),
            Map.entry("explore", "Movement"),
            Map.entry("mine", "Gathering"),
            Map.entry("chop", "Gathering"),
            Map.entry("find", "Gathering"),
            Map.entry("harvest", "Gathering"),
            Map.entry("gettool", "Gathering"),
            Map.entry("check_item", "Logic & Conditions"),
            Map.entry("check_player", "Logic & Conditions"),
            Map.entry("check_distance", "Logic & Conditions"),
            Map.entry("tunnel", "Mining & Building"),
            Map.entry("stripmine", "Mining & Building"),
            Map.entry("bridge", "Mining & Building"),
            Map.entry("portal", "Mining & Building"),
            Map.entry("kill", "Combat"),
            Map.entry("huntblazes", "Combat"),
            Map.entry("huntendermen", "Combat"),
            Map.entry("huntcreepers", "Combat"),
            Map.entry("huntskeletons", "Combat"),
            Map.entry("huntsheep", "Gathering"),
            Map.entry("select_item", "Items & Storage"),
            Map.entry("loot", "Items & Storage"),
            Map.entry("deposit", "Items & Storage"),
            Map.entry("smelt", "Items & Storage"),
            Map.entry("fish", "Items & Storage"),
            Map.entry("eat", "Items & Storage"),
            Map.entry("sleep", "Items & Storage"),
            Map.entry("boat", "Movement"),
            Map.entry("task", "Tasks & Missions"),
            Map.entry("self_preservation", "Tasks & Missions"),
            Map.entry("stay_near", "Tasks & Missions"),
            Map.entry("completegame", "Tasks & Missions"),
            Map.entry("timer", "Logic & Conditions"),
            Map.entry("counter", "Logic & Conditions"),
            Map.entry("end", "General"),
            Map.entry("observer", "General"),
            Map.entry("button", "General")
    );

    static {
        register(new CommandDef("mine", "Mine", "Dig out the nearest matching blocks", List.of(
                new Param.BlockSet("targets", "Blocks", "What to mine", BlockCatalog.all(),
                        Set.of(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE)),
                new Param.Ints("radius", "Search radius", "How far to look for targets", 32, 1, 512),
                new Param.Ints("y_min", "Min Y", "Ignore anything below this height", -64, -64, 320),
                new Param.Ints("y_max", "Max Y", "Ignore anything above this height", 320, -64, 320),
                new Param.Ints("limit", "Stop after", "Blocks to mine before stopping (0 = no limit)", 0, 0, 512),
                new Param.Bool("auto_tool", "Get tools first",
                        "Chop wood and craft a pickaxe when the target needs one you don't have", true),
                new Param.Bool("prospect", "Keep searching",
                        "Dig safe descending stairs and keep looking when none are visible", false),
                new Param.Bool("check_around", "Check around before fail",
                        "Turn through four 90 degree views before reporting no visible target", true)
        ), def -> new MineTask(
                def.blockValue("targets").resolvedBlocks(),
                def.intValue("radius"),
                def.intValue("y_min"),
                def.intValue("y_max"),
                def.intValue("limit"),
                def.boolValue("auto_tool"),
                def.boolValue("prospect"), false, def.boolValue("check_around"))));

        register(new CommandDef("chop", "Chop Wood", "Fell nearby trees", List.of(
                new Param.Ints("radius", "Search radius", "How far to look for trees", 64, 1, 512),
                new Param.Ints("limit", "Stop after", "Logs to gather before stopping (0 = no limit)", 8, 0, 512)
        ), def -> new MineTask(new java.util.HashSet<>(BlockCatalog.logs()), def.intValue("radius"),
                -64, 320, def.intValue("limit"), false, false, true)));

        register(new CommandDef("explore", "Explore",
                "Walk around and scan for a target. Succeeds when it sees one.", List.of(
                new Param.BlockSet("targets", "Targets", "What to look for",
                        BlockCatalog.all(),
                        Set.of(Blocks.OAK_LOG, Blocks.SPRUCE_LOG, Blocks.BIRCH_LOG)),
                new Param.Ints("radius", "Search radius", "How far to look around each stop", 48, 1, 512),
                new Param.Ints("attempts", "Max attempts", "How many directions to try before giving up", 6, 1, 32),
                new Param.Ints("step", "Step distance", "How far to walk before scanning again", 12, 1, 64),
                new Param.Bool("check_around", "Look around",
                        "Turn the head to search at each stop instead of only looking straight ahead", true),
                new Param.Choice("scan_style", "First look",
                        "How wide the first look at each stop is. A narrow look still widens on its "
                                + "own when it finds nothing, so this only sets where it starts.",
                        SCAN_STYLES, "Glance ahead"),
                new Param.Bool("smart_direction", "Use biome sense",
                        "Choose which way to walk from what the land looks like - head for the forest "
                                + "when you need wood, don't cross an ocean or wander into a mesa. "
                                + "Off means cycling through the four compass directions instead.", true)
        ), def -> new ExploreTask(
                def.blockValue("targets").resolvedBlocks(),
                def.intValue("radius"),
                def.intValue("attempts"),
                def.intValue("step"),
                def.boolValue("check_around"),
                scanStyle(def.choiceValue("scan_style")),
                def.boolValue("smart_direction"))));

        register(new CommandDef("gettool", "Get Tools",
                "Craft a tool you pick, or whatever a block needs", List.of(
                new Param.Choice("tool", "Tool",
                        "Which tool to make. Leave it on '" + TOOL_FROM_BLOCK + "' to have the bot "
                                + "work out the cheapest pickaxe that harvests the block below.",
                        TOOL_CHOICES, TOOL_FROM_BLOCK),
                new Param.Choice("material", "Material",
                        "Which tier of that tool. Everything above wood gathers its own materials "
                                + "first: stone digs a short staircase, iron smelts what it mines. "
                                + "Ignored when Tool is '" + TOOL_FROM_BLOCK + "'.",
                        ToolCatalog.materialNames(), "Stone"),
                new Param.BlockSet("target", "Want to mine",
                        "What you need to be able to harvest. Only used when Tool is '"
                                + TOOL_FROM_BLOCK + "'.",
                        ores(), Set.of(Blocks.IRON_ORE)),
                new Param.Bool("check_around", "Check around before fail",
                        "Turn through four 90 degree views before a missing material fails", true)
        ), def -> {
            boolean checkAround = def.boolValue("check_around");
            ToolCatalog.Kind kind = ToolCatalog.parseKind(def.choiceValue("tool"));
            if (kind != null) {
                ToolCatalog.Material material = ToolCatalog.parseMaterial(def.choiceValue("material"));
                return material == null
                        ? new FailTask("Get Tools", "pick a material the bot can gather")
                        : new EnsureToolTask(ToolCatalog.item(kind, material), checkAround);
            }
            BlockTarget wanted = def.blockValue("target");
            Block sample = wanted.anyConcreteBlock().orElse(null);
            return wanted.isEmpty() || sample == null
                    ? new FailTask("Get Tools", "pick a block you want to be able to mine")
                    : new EnsureToolTask(sample, checkAround);
        }));

        register(new CommandDef("check_item", "Check Item Count",
                "Branch based on how many of an item are in the inventory", List.of(
                new Param.ItemChoice("item", "Item", "Which inventory item to count",
                        items(), Items.COBBLESTONE),
                new Param.Choice("comparison", "Rule", "How to compare the current count",
                        COMPARISONS, "Less than"),
                new Param.Ints("count", "Count", "The count used by the comparison", 10, 0, 9999)
        ), def -> ConditionTask.itemCount(def.itemValue("item"), def.choiceValue("comparison"),
                def.intValue("count"))));

        register(new CommandDef("check_player", "Check Player",
                "Branch based on health, hunger, or remaining air", List.of(
                new Param.Choice("metric", "Value", "Which player value to inspect",
                        List.of("Health", "Hunger", "Air"), "Health"),
                new Param.Choice("comparison", "Rule", "How to compare the current value",
                        COMPARISONS, "At most"),
                new Param.Ints("threshold", "Threshold", "The value used by the comparison", 8, 0, 300)
        ), def -> ConditionTask.playerValue(def.choiceValue("metric"), def.choiceValue("comparison"),
                def.intValue("threshold"))));

        register(new CommandDef("counter", "Counter",
                "Count incoming pulses and forward every selected number",
                List.of(new Param.Ints("count", "Every", "Forward one pulse after this many incoming pulses",
                        3, 1, 1_000_000)),
                def -> new FailTask("Counter", "Counter is a pulse node")));

        // No parameters: what an Observer watches is the card wired into its Watch pin, not a
        // value picked from a list.
        register(new CommandDef("observer", "Observer",
                "Watch a card and send a pulse whenever its power changes",
                List.of(), def -> new FailTask("Observer", "Observer is a pulse source")));

        register(new CommandDef("end", "End",
                "Consume an incoming pulse and finish that circuit",
                List.of(), def -> new FailTask("End", "End is a pulse sink")));

        register(new CommandDef("button", "Button",
                "Send one pulse when the editor Button control is pressed",
                List.of(), def -> new FailTask("Button", "Button is a pulse source")));

        register(new CommandDef("check_distance", "Check Distance",
                "Branch based on distance to a saved waypoint", List.of(
                new Param.Choice("waypoint", "Waypoint", "Which saved location to measure from",
                        () -> WaypointStore.get().names(), ""),
                new Param.Choice("comparison", "Rule", "How to compare the distance",
                        COMPARISONS, "At most"),
                new Param.Ints("distance", "Blocks", "The distance used by the comparison", 20, 0, 512)
        ), def -> ConditionTask.waypointDistance(def.choiceValue("waypoint"),
                def.choiceValue("comparison"), def.intValue("distance"))));

        register(new CommandDef("find", "Find", "Report the nearest matching block or mob", List.of(
                new Param.Choice("target_kind", "Target type", "Search blocks or living entities",
                        List.of("Blocks", "Mobs"), "Blocks"),
                new Param.BlockSet("targets", "Blocks", "What block to look for", ores(),
                        Set.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE)),
                new Param.EntitySet("entities", "Mobs", "What animal or mob to look for", MOBS,
                        Set.of(EntityType.SHEEP)),
                new Param.Ints("radius", "Search radius", "How far to look", 64, 1, 512),
                new Param.Ints("y_min", "Min Y", "Ignore anything below this height", -64, -64, 320),
                new Param.Ints("y_max", "Max Y", "Ignore anything above this height", 320, -64, 320),
                new Param.Bool("prospect", "Keep searching",
                        "Dig safe descending stairs and keep looking when none are visible", true),
                new Param.Bool("check_around", "Check around before fail",
                        "Turn through four 90 degree views before reporting no visible target", true)
        ), def -> "Mobs".equalsIgnoreCase(def.choiceValue("target_kind"))
                ? FindTask.forEntities(def.entityValue("entities"), def.intValue("radius"),
                def.boolValue("check_around"))
                : new FindTask(def.blockValue("targets").resolvedBlocks(), def.intValue("radius"),
                def.intValue("y_min"), def.intValue("y_max"), def.boolValue("prospect"),
                def.boolValue("check_around"))));

        register(new CommandDef("harvest", "Harvest", "Pick mature crops and replant seeds", List.of(
                new Param.BlockSet("targets", "Crops", "Which crops to harvest", BlockCatalog.crops(),
                        Set.of(Blocks.WHEAT, Blocks.CARROTS, Blocks.POTATOES, Blocks.BEETROOTS)),
                new Param.Ints("radius", "Search radius", "How far to look for crops", 32, 1, 512),
                new Param.Ints("limit", "Stop after", "Crops to harvest before stopping (0 = no limit)", 0, 0, 512),
                new Param.Bool("collect", "Collect drops",
                        "Pick up the crop drops beside each harvested seed", true),
                new Param.Bool("replant", "Replant crops",
                        "Plant the matching seed back after harvesting", true)
        ), def -> new HarvestTask(def.blockValue("targets").resolvedBlocks(), def.intValue("radius"),
                def.intValue("limit"), def.boolValue("collect"), def.boolValue("replant"))));

        register(new CommandDef("walk", "Walk", "Travel in a direction or to coordinates without sprinting", List.of(
                new Param.Choice("direction", "Direction", "Travel relative to where you look or by compass direction",
                        List.of("Facing", "Back", "Left", "Right", "North", "South", "East", "West", "Coordinates"),
                        "Facing"),
                new Param.Ints("distance", "Distance", "How far to travel in directional mode", 32, 1, 512),
                new Param.Pos("target", "Coordinates", "Used only when Direction is Coordinates", null),
                new Param.Ints("tolerance", "Arrival range", "How close is close enough, in blocks", 1, 0, 32)
        ), def -> gotoTask(def, "Walk", false)));

        register(new CommandDef("run", "Run", "Travel in a direction or to coordinates, sprinting where possible", List.of(
                new Param.Choice("direction", "Direction", "Travel relative to where you look or by compass direction",
                        List.of("Facing", "Back", "Left", "Right", "North", "South", "East", "West", "Coordinates"),
                        "Facing"),
                new Param.Ints("distance", "Distance", "How far to travel in directional mode", 32, 1, 512),
                new Param.Pos("target", "Coordinates", "Used only when Direction is Coordinates", null),
                new Param.Ints("tolerance", "Arrival range", "How close is close enough, in blocks", 1, 0, 32)
        ), def -> gotoTask(def, "Run", true)));

        register(new CommandDef("waypoint", "Go to Waypoint", "Travel to a saved location", List.of(
                new Param.Choice("name", "Waypoint", "Which saved location",
                        () -> WaypointStore.get().names(), ""),
                new Param.Ints("tolerance", "Get within", "How close is close enough, in blocks", 2, 0, 32)
        ), def -> {
            String name = def.choiceValue("name");
            return WaypointStore.get().byName(name)
                    .<com.etka.lune.bot.Task>map(waypoint -> WaypointStore.isInCurrentDimension(waypoint)
                            ? new GotoTask(new Goals.Near(waypoint.pos(), def.intValue("tolerance")), true, false)
                            : new FailTask("Go to Waypoint", "waypoint is in another dimension"))
                    .orElseGet(() -> new FailTask("Go to Waypoint",
                            name.isEmpty() ? "no waypoint chosen" : "no waypoint named '" + name + "'"));
        }));

        register(new CommandDef("kill", "Kill", "Hunt down nearby mobs", List.of(
                new Param.EntitySet("targets", "Mobs", "What to attack", MOBS, Set.of(EntityType.ZOMBIE)),
                new Param.Ints("radius", "Search radius", "How far to look for mobs", 16, 1, 64),
                new Param.Bool("fire_resistance", "Use fire resistance",
                        "Drink a carried fire-resistance potion before fighting a Blaze", true),
                new Param.Bool("use_shield", "Use a shield",
                        "Raise a shield against ranged and other dangerous enemies", true),
                new Param.Bool("craft_shield", "Craft a shield",
                        "Craft a shield at a crafting table when none is carried", false),
                new Param.Choice("weapon", "Weapon", "Which weapon to use", WEAPONS, "Automatic"),
                new Param.Bool("craft_weapon", "Craft weapon",
                        "Craft the selected sword, axe, or bow when materials are available", false),
                new Param.Choice("enderman_safety", "Enderman safety",
                        "How to fight Endermen without chasing them in open ground", ENDERMAN_SAFETY,
                        "Auto")
        ), def -> new KillTask(
                def.entityValue("targets"),
                def.intValue("radius"),
                new KillOptions(
                        def.boolValue("fire_resistance"),
                        def.boolValue("use_shield"),
                        def.boolValue("craft_shield"),
                        KillOptions.EndermanSafety.parse(def.choiceValue("enderman_safety")),
                        KillOptions.WeaponPreference.parse(def.choiceValue("weapon")),
                        def.boolValue("craft_weapon")))));

        register(new CommandDef("huntendermen", "Hunt Endermen", "Roam and kill Endermen for pearls", List.of(
                new Param.Ints("count", "Pearls", "How many ender pearls to collect", 12, 1, 64),
                new Param.Choice("enderman_safety", "Enderman safety",
                        "How to fight Endermen without chasing them in open ground", ENDERMAN_SAFETY,
                        "Auto"),
                new Param.Bool("use_shield", "Use a shield",
                        "Keep a shield available while fighting", true),
                new Param.Bool("craft_shield", "Craft a shield",
                        "Craft a shield at a crafting table when none is carried", false),
                new Param.Choice("weapon", "Weapon", "Which weapon to use", WEAPONS, "Sword"),
                new Param.Bool("craft_weapon", "Craft weapon",
                        "Craft the selected sword or axe when materials are available", false)
        ), def -> new HuntEndermenTask(
                def.intValue("count"),
                new KillOptions(
                        false,
                        def.boolValue("use_shield"),
                        def.boolValue("craft_shield"),
                        KillOptions.EndermanSafety.parse(def.choiceValue("enderman_safety")),
                        KillOptions.WeaponPreference.parse(def.choiceValue("weapon")),
                        def.boolValue("craft_weapon")))));

        register(new CommandDef("huntblazes", "Hunt Blazes", "Roam and kill Blazes for rods", List.of(
                new Param.Ints("count", "Blaze rods", "How many blaze rods to collect", 8, 1, 64),
                new Param.Bool("fire_resistance", "Use fire resistance",
                        "Drink a carried fire-resistance potion before fighting", true),
                new Param.Bool("use_shield", "Use a shield",
                        "Raise a shield against Blaze attacks", true),
                new Param.Bool("craft_shield", "Craft a shield",
                        "Craft a shield at a crafting table when none is carried", false),
                new Param.Choice("weapon", "Weapon", "Which weapon to use", WEAPONS, "Bow"),
                new Param.Bool("craft_weapon", "Craft weapon",
                        "Craft the selected sword, axe, or bow when materials are available", false)
        ), def -> new HuntBlazesTask(
                def.intValue("count"),
                new KillOptions(
                        def.boolValue("fire_resistance"),
                        def.boolValue("use_shield"),
                        def.boolValue("craft_shield"),
                        KillOptions.EndermanSafety.DIRECT,
                        KillOptions.WeaponPreference.parse(def.choiceValue("weapon")),
                        def.boolValue("craft_weapon")))));

        register(new CommandDef("huntcreepers", "Hunt Creepers", "Roam and kill Creepers for gunpowder", List.of(
                new Param.Ints("count", "Gunpowder", "How many gunpowder to collect", 16, 1, 128),
                new Param.Bool("use_shield", "Use a shield",
                        "Raise a shield while backing away from Creepers", true),
                new Param.Bool("craft_shield", "Craft a shield",
                        "Craft a shield at a crafting table when none is carried", false),
                new Param.Choice("weapon", "Weapon", "Which weapon to use", WEAPONS, "Sword"),
                new Param.Bool("craft_weapon", "Craft weapon",
                        "Craft the selected sword or axe when materials are available", false)
        ), def -> new HuntCreepersTask(
                def.intValue("count"),
                new KillOptions(
                        false,
                        def.boolValue("use_shield"),
                        def.boolValue("craft_shield"),
                        KillOptions.EndermanSafety.DIRECT,
                        KillOptions.WeaponPreference.parse(def.choiceValue("weapon")),
                        def.boolValue("craft_weapon")))));

        register(new CommandDef("huntskeletons", "Hunt Skeletons", "Roam and kill Skeletons for bones", List.of(
                new Param.Ints("count", "Bones", "How many bones to collect", 16, 1, 128),
                new Param.Bool("use_shield", "Use a shield",
                        "Raise a shield against Skeleton arrows", true),
                new Param.Bool("craft_shield", "Craft a shield",
                        "Craft a shield at a crafting table when none is carried", false),
                new Param.Choice("weapon", "Weapon", "Which weapon to use", WEAPONS, "Sword"),
                new Param.Bool("craft_weapon", "Craft weapon",
                        "Craft the selected sword, axe, or bow when materials are available", false)
        ), def -> new HuntSkeletonsTask(
                def.intValue("count"),
                new KillOptions(
                        false,
                        def.boolValue("use_shield"),
                        def.boolValue("craft_shield"),
                        KillOptions.EndermanSafety.DIRECT,
                        KillOptions.WeaponPreference.parse(def.choiceValue("weapon")),
                        def.boolValue("craft_weapon")))));

        register(new CommandDef("huntsheep", "Hunt Sheep", "Roam and hunt Sheep for wool", List.of(
                new Param.Ints("count", "Wool", "How much wool to collect", 16, 1, 128),
                new Param.Choice("weapon", "Weapon", "Which weapon to use", WEAPONS, "Sword"),
                new Param.Bool("craft_weapon", "Craft weapon",
                        "Craft the selected sword or axe when materials are available", false)
        ), def -> new HuntSheepTask(
                def.intValue("count"),
                new KillOptions(
                        false,
                        false,
                        false,
                        KillOptions.EndermanSafety.DIRECT,
                        KillOptions.WeaponPreference.parse(def.choiceValue("weapon")),
                        def.boolValue("craft_weapon")))));

        register(new CommandDef("loot", "Loot", "Pick up dropped items nearby", List.of(
                new Param.Ints("radius", "Pickup radius", "How far to range for drops", 16, 1, 64)
        ), def -> new LootTask(def.intValue("radius"))));

        register(new CommandDef("tunnel", "Tunnel", "Dig a straight corridor", List.of(
                new Param.Choice("direction", "Direction", "Which way to dig",
                        List.of("Facing", "North", "South", "East", "West"), "Facing"),
                new Param.Ints("length", "Length", "How many blocks to dig", 64, 1, 512),
                new Param.Ints("height", "Height", "Corridor height", 2, 2, 4)
        ), def -> new TunnelTask(def.choiceValue("direction"), def.intValue("length"), def.intValue("height"))));

        register(new CommandDef("stripmine", "Stripmine", "Dig branch corridors off a main shaft", List.of(
                new Param.BlockSet("target", "Ore", "Pick the best Y from ore knowledge", ores(), Set.of()),
                new Param.Ints("y_level", "Y level", "Height to mine at", -59, -64, 320),
                new Param.Ints("branch_length", "Branch length", "Length of each branch", 32, 4, 256),
                new Param.Ints("spacing", "Branch spacing", "Blocks between branches", 3, 2, 16),
                new Param.Ints("branches", "Branch count", "How many branches to dig", 8, 1, 64)
        ), def -> new StripmineTask(def.blockValue("target").resolvedBlocks(), def.intValue("y_level"),
                def.intValue("branch_length"), def.intValue("spacing"), def.intValue("branches"))));

        register(new CommandDef("bridge", "Bridge", "Place blocks to cross a gap", List.of(
                new Param.Choice("direction", "Direction", "Which way to build",
                        List.of("Facing", "North", "South", "East", "West"), "Facing"),
                new Param.Ints("length", "Length", "How many blocks to place", 16, 1, 512),
                new Param.BlockSet("materials", "Blocks", "What to build with", BlockCatalog.buildingBlocks(),
                        Set.of(Blocks.COBBLESTONE))
        ), def -> new BridgeTask(def.choiceValue("direction"), def.intValue("length"), def.blockValue("materials").resolvedBlocks())));

        register(new CommandDef("portal", "Build Nether Portal",
                "Build and light a Nether portal from carried obsidian", List.of(
                new Param.Choice("frame_mode", "Frame mode",
                        "Use ten obsidian with dirt or cobblestone corners, leave corners open, or build every frame block from obsidian",
                        BuildPortalTask.modeChoices(), BuildPortalTask.FrameMode.RESOURCE_SAVING.label()),
                new Param.BlockSet("corner_materials", "Corner blocks",
                        "Blocks used only for the four corners in the resource-saving mode",
                        List.of(Blocks.DIRT, Blocks.COBBLESTONE),
                        Set.of(Blocks.DIRT, Blocks.COBBLESTONE))
        ), def -> new BuildPortalTask(def.choiceValue("frame_mode"),
                def.blockValue("corner_materials").resolvedBlocks())));

        register(new CommandDef("deposit", "Deposit", "Put items into a nearby chest or barrel", List.of(
                new Param.Choice("filter", "Deposit", "What kind of items to deposit",
                        List.of("Ores", "Logs", "Crops", "Stone", "All"), "Ores"),
                new Param.Ints("radius", "Search radius", "How far to look for a container", 16, 1, 64),
                new Param.Bool("optional", "Skip if unavailable",
                        "Continue the task when no usable container is nearby", false)
        ), def -> new DepositTask(def.choiceValue("filter"), def.intValue("radius"),
                def.boolValue("optional"))));

        register(new CommandDef("smelt", "Smelt", "Smelt items in a furnace", List.of(
                new Param.Choice("input", "Input", "What to smelt", () -> List.copyOf(SMELT_RECIPES.keySet()), "Raw Iron"),
                new Param.Ints("count", "Count", "How many to smelt", 8, 1, 256)
        ), def -> {
            String input = def.choiceValue("input");
            SmeltRecipe recipe = SMELT_RECIPES.get(input);
            if (recipe == null) {
                return new FailTask("Smelt", "unknown input " + input);
            }
            return new SmeltTask(recipe.inputs, recipe.output, def.intValue("count"));
        }));

        register(new CommandDef("fish", "Fish", "Cast and reel automatically", List.of(
                new Param.Bool("auto_recast", "Auto recast", "Cast again after each catch", true)
        ), def -> new FishTask(def.boolValue("auto_recast"))));

        // Running a task is itself a command, which is what lets one task hand off to
        // another - "when the mining run is done, switch to harvesting".
        register(new CommandDef("task", "Run Task", "Run one of your saved tasks", List.of(
                new Param.Choice("name", "Task", "Which task to run",
                        () -> TaskStore.get().names(), "")
        ), def -> {
            String name = def.choiceValue("name");
            return TaskStore.get().byName(name)
                    .<com.etka.lune.bot.Task>map(TaskRunner::new)
                    .orElseGet(() -> new FailTask("Run Task",
                            name.isEmpty() ? "no task chosen" : "no task named '" + name + "'"));
        }));

        // Kept as its own entry so tasks saved before Stop the Game grew its endings still load.
        // It builds the same task, so there is one implementation of pausing, not two.
        register(new CommandDef("pause_game", "Pause the Game",
                "Open the pause menu, which also stops the world clock. Singleplayer only",
                List.of(), def -> new StopGameTask(StopGameTask.Ending.PAUSE)));

        register(new CommandDef("timer", "Timer",
                "Delay a received pulse, then forward it",
                List.of(new Param.Ints("seconds", "Time", "How many seconds to wait", 5, 0, 3600)),
                def -> new TimerTask(def.intValue("seconds"))));

        register(new CommandDef("stop_game", "Stop the Game",
                "End the session: pause it, return to the main menu, or quit to desktop",
                List.of(
                new Param.Choice("ending", "Ending", "How far to back out of the session",
                        java.util.Arrays.stream(StopGameTask.Ending.values())
                                .map(StopGameTask.Ending::label).toList(),
                        StopGameTask.Ending.QUIT.label())
        ), def -> new StopGameTask(StopGameTask.Ending.fromLabel(def.choiceValue("ending")))));

        register(new CommandDef("select_item", "Select from Inventory",
                "Put a chosen item in hand, filtered by enchantment and remaining durability",
                List.of(
                new Param.ItemChoice("item", "Item", "Which carried item to hold",
                        items(), Items.DIAMOND_PICKAXE),
                new Param.Choice("enchanting", "Enchantment",
                        "Narrow it to the enchanted copy, such as the Silk Touch pickaxe",
                        java.util.Arrays.stream(SelectItemTask.Enchanting.values())
                                .map(SelectItemTask.Enchanting::label).toList(),
                        SelectItemTask.Enchanting.ANY.label()),
                new Param.Choice("hand", "Hand", "Where to hold it",
                        java.util.Arrays.stream(SelectItemTask.Hand.values())
                                .map(SelectItemTask.Hand::label).toList(),
                        SelectItemTask.Hand.MAIN.label()),
                new Param.Ints("min_durability", "Minimum durability",
                        "Percent of durability a copy must still have to be used; 0 accepts any",
                        0, 0, 100),
                new Param.Choice("prefer", "Prefer",
                        "Which copy to hold when several pass: save the good one, or use up the worn one",
                        java.util.Arrays.stream(SelectItemTask.Preference.values())
                                .map(SelectItemTask.Preference::label).toList(),
                        SelectItemTask.Preference.MOST_DURABLE.label())
        ), def -> new SelectItemTask(
                def.itemValue("item"),
                SelectItemTask.Enchanting.fromLabel(def.choiceValue("enchanting")),
                SelectItemTask.Hand.fromLabel(def.choiceValue("hand")),
                def.intValue("min_durability"),
                SelectItemTask.Preference.fromLabel(def.choiceValue("prefer")))));

        register(new CommandDef("eat", "Eat", "Eat food until hunger is above a threshold", List.of(
                new Param.Ints("minimum_food", "Minimum food", "Stop eating when hunger is at least this", 14, 0, 20)
        ), def -> new EatTask(stack -> true, def.intValue("minimum_food"))));

        register(new CommandDef("boat", "Boat", "Cross water or ice in a boat, crafting one if needed", List.of(
                new Param.Pos("target", "Destination", "Where to end up on the far side", null),
                new Param.Bool("reclaim", "Take the boat",
                        "Break the boat and pick it back up after landing", true)
        ), def -> new BoatTask(def.posValue("target"), def.boolValue("reclaim"))));

        register(new CommandDef("sleep", "Sleep", "Make a bed from nearby sheep and sleep the night away", List.of(
                new Param.Bool("wait_for_night", "Wait for night",
                        "Prepare the bed now and wait for dusk, instead of only sleeping when it is already dark", true),
                new Param.Bool("reclaim", "Take the bed",
                        "Break the bed and pick it back up after waking", true),
                new Param.Ints("radius", "Search radius", "How far to look for sheep and flowers", 32, 8, 128)
        ), def -> new SleepTask(
                def.boolValue("wait_for_night"),
                def.boolValue("reclaim"),
                def.intValue("radius"))));

        register(new CommandDef("self_preservation", "Self Preservation",
                "Safety circuit: escape danger while the other circuits keep running", List.of(
                new Param.Bool("protect_air", "Drowning",
                        "Escape drowning: take control when the air comparison becomes true", true),
                new Param.Choice("air_compare", "Air rule", "Air comparison: compare remaining air ticks",
                        List.of("At most", "Less than", "At least", "Greater than"), "At most"),
                new Param.Ints("air_value", "Air", "Air value: vanilla maximum air is 300 ticks",
                        120, 0, 300),
                new Param.Bool("protect_lava", "Lava",
                        "Escape lava: never voluntarily enter lava; escape if dropped into it", true),
                new Param.Bool("protect_fall", "Fall clutch",
                        "Clutch high falls: pour a water bucket into the landing; with no bucket, board a boat while the fall is still slow, or land on a carried hay bale, slime block, honey block, cobweb, berry bush or scaffolding when it is not", true),
                new Param.Ints("fall_threshold", "Fall limit",
                        "Fall threshold: take control once fall distance reaches this many blocks", 10, 2, 512),
                new Param.Bool("protect_monsters", "Mobs",
                        "Escape monsters: retreat when the nearest hostile matches the distance comparison", true),
                new Param.Choice("monster_compare", "Mob rule",
                        "Monster comparison: compare distance to the nearest hostile",
                        List.of("At most", "Less than", "At least", "Greater than"), "At most"),
                new Param.Ints("monster_distance", "Mob range",
                        "Monster distance: distance in blocks used by the comparison", 8, 1, 64),
                new Param.Bool("protect_health", "Protect",
                        "Protect health: eat, wait, or retreat when the health comparison becomes true", true),
                new Param.Choice("health_compare", "Health rule",
                        "Health comparison: health points; 20 is ten full hearts",
                        List.of("At most", "Less than", "At least", "Greater than"), "At most"),
                new Param.Ints("health_value", "Health",
                        "Health value: health points used by the comparison", 8, 1, 20)
        ), def -> new SelfPreservationTask(
                def.boolValue("protect_air"), def.choiceValue("air_compare"), def.intValue("air_value"),
                def.boolValue("protect_lava"), def.boolValue("protect_monsters"),
                def.choiceValue("monster_compare"), def.intValue("monster_distance"),
                def.boolValue("protect_health"), def.choiceValue("health_compare"),
                def.intValue("health_value"),
                def.boolValue("protect_fall"), def.intValue("fall_threshold"))));

        register(new CommandDef("stay_near", "Stay Near",
                "Boundary circuit: walk back when the bot leaves the area",
                List.of(
                new Param.Choice("anchor", "Measure from",
                        "Where the area is centred. The run's start is taken once, when you press play - not again at each node",
                        List.of(StayNearTask.FROM_RUN_START, StayNearTask.FROM_WAYPOINT),
                        StayNearTask.FROM_RUN_START),
                new Param.Choice("waypoint", "Waypoint",
                        "Which saved location to hold, when measuring from a waypoint",
                        () -> WaypointStore.get().names(), ""),
                new Param.Ints("radius", "Stay within",
                        "How far from the centre the bot may go, in blocks, measured flat: depth does not count against it",
                        60, 8, 512)
        ), def -> new StayNearTask(def.choiceValue("anchor"), def.choiceValue("waypoint"),
                def.intValue("radius"))));

        register(new CommandDef("completegame", "Complete the Game",
                "Speedrun-style route from survival spawn to the Ender Dragon", List.of(),
                def -> new SpeedrunTask()));
    }

    private CommandRegistry() {}

    private static void register(CommandDef def) {
        COMMANDS.add(def);
    }

    /** Shared factory for Walk and Run, which differ only in whether they sprint. */
    private static com.etka.lune.bot.Task gotoTask(CommandDef def, String label, boolean sprint) {
        String direction = def.choiceValue("direction");
        if (!"Coordinates".equals(direction)) {
            return new DirectionalGotoTask(label, direction, def.intValue("distance"),
                    def.intValue("tolerance"), sprint);
        }
        BlockPos target = def.posValue("target");
        if (target == null) {
            return new FailTask(label, "no destination set");
        }
        return new GotoTask(new Goals.Near(target, def.intValue("tolerance")), sprint, false);
    }

    public static List<CommandDef> all() {
        return List.copyOf(COMMANDS);
    }

    /** Returns the task-palette folder for a command. */
    public static String categoryFor(String id) {
        return COMMAND_CATEGORIES.getOrDefault(id, "Other");
    }

    /** Looks up a command by its stored id; null when a task names one that no longer exists. */
    public static CommandDef byId(String id) {
        for (CommandDef def : COMMANDS) {
            if (def.id().equals(id)) {
                return def;
            }
        }
        return null;
    }

    /** Commands matching the Tasks palette search box. */
    public static List<CommandDef> search(String query) {
        return COMMANDS.stream().filter(def -> def.matches(query)).toList();
    }
}
