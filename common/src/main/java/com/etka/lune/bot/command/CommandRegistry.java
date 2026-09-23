package com.etka.lune.bot.command;

import com.etka.lune.bot.util.InventoryHelper;
import com.etka.lune.compat.Mobs;
import com.etka.lune.util.Lang;
import com.etka.lune.bot.catalog.BlockCatalog;
import com.etka.lune.bot.catalog.SoundCatalog;
import com.etka.lune.bot.catalog.BlockTarget;
import com.etka.lune.bot.catalog.CraftRecipe;
import com.etka.lune.bot.catalog.NetheriteUpgrades;
import com.etka.lune.bot.catalog.ToolCatalog;
import com.etka.lune.bot.path.Goals;
import com.etka.lune.bot.task.TaskRunner;
import com.etka.lune.task.TaskStore;
import com.etka.lune.bot.task.CraftTask;
import com.etka.lune.bot.task.FailTask;
import com.etka.lune.bot.task.EnsureToolTask;
import com.etka.lune.bot.task.GridCraftTask;
import com.etka.lune.bot.task.NetheriteUpgradeTask;
import com.etka.lune.bot.task.FindMapTask;
import com.etka.lune.bot.task.FindTask;
import com.etka.lune.bot.task.BackpackDepositTask;
import com.etka.lune.bot.task.BackpackTakeTask;
import com.etka.lune.bot.task.CompassFindTask;
import com.etka.lune.bot.util.ItemFilters;
import com.etka.lune.mods.Backpacks;
import com.etka.lune.mods.CompassHook;
import com.etka.lune.waypoint.Discovery;
import com.etka.lune.waypoint.DiscoveryStore;
import com.etka.lune.bot.task.FishTask;
import com.etka.lune.bot.task.HuntEndermenTask;
import com.etka.lune.bot.task.DragonEggTask;
import com.etka.lune.bot.task.HuntBlazesTask;
import com.etka.lune.bot.task.HuntCreepersTask;
import com.etka.lune.bot.task.HuntSkeletonsTask;
import com.etka.lune.bot.task.HuntSheepTask;
import com.etka.lune.bot.task.KillTask;
import com.etka.lune.bot.task.KillOptions;
import com.etka.lune.bot.task.LootTask;
import com.etka.lune.bot.task.NotifyTask;
import com.etka.lune.bot.task.RecoverDeathTask;
import com.etka.lune.bot.task.ReplantTask;
import com.etka.lune.bot.task.UsePortalTask;
import com.etka.lune.bot.task.StripmineTask;
import com.etka.lune.bot.task.TunnelTask;
import com.etka.lune.bot.task.GotoTask;
import com.etka.lune.bot.task.SpeedrunTask;
import com.etka.lune.bot.task.ConditionTask;
import com.etka.lune.bot.task.CountdownTask;
import com.etka.lune.bot.task.EatTask;
import com.etka.lune.bot.task.EquipTask;
import com.etka.lune.bot.task.BoatTask;
import com.etka.lune.bot.task.BridgeTask;
import com.etka.lune.bot.task.BuildPortalTask;
import com.etka.lune.bot.task.DepositTask;
import com.etka.lune.bot.task.DirectionalGotoTask;
import com.etka.lune.bot.task.ExploreTask;
import com.etka.lune.bot.util.ClockSource;
import com.etka.lune.bot.util.HeadScanner;
import com.etka.lune.bot.util.PlayerMetric;
import com.etka.lune.bot.util.Weather;
import com.etka.lune.bot.util.WorldClock;
import com.etka.lune.bot.util.WorldDimension;
import com.etka.lune.bot.task.SmeltTask;
import com.etka.lune.bot.task.SaveWaypointTask;
import com.etka.lune.bot.task.SelectItemTask;
import com.etka.lune.bot.task.StopGameTask;
import com.etka.lune.bot.task.SleepTask;
import com.etka.lune.bot.task.SafetyOptions;
import com.etka.lune.bot.task.SelfPreservationTask;
import com.etka.lune.bot.task.StayNearTask;
import com.etka.lune.bot.task.StepPolicy;
import com.etka.lune.bot.task.StepTask;
import com.etka.lune.bot.task.HarvestTask;
import com.etka.lune.bot.task.MineTask;
import com.etka.lune.bot.task.PlaceBlockTask;
import com.etka.lune.bot.task.TimerTask;
import com.etka.lune.waypoint.WaypointStore;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
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
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;
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

    /**
     * Every biome or structure the world knows, by id, sorted by what it is called. Nothing
     * outside a world: the registries are the world's, and the dropdown is only ever opened in one.
     */
    private static List<String> biomeIds() {
        return registryIds(Registries.BIOME, CommandRegistry::biomeLabel);
    }

    private static List<String> structureIds() {
        return registryIds(Registries.STRUCTURE, CommandRegistry::structureLabel);
    }

    private static <T> List<String> registryIds(ResourceKey<? extends Registry<? extends T>> registry,
                                                UnaryOperator<String> label) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return List.of();
        }
        return mc.level.registryAccess().lookupOrThrow(registry).keySet().stream()
                .map(Identifier::toString)
                .sorted(Comparator.comparing(label, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** A biome by the game's own name for it; the value stays the id, which is what is saved. */
    private static String biomeLabel(String id) {
        return DiscoveryStore.displayName(Discovery.BIOME, id);
    }

    private static String structureLabel(String id) {
        return DiscoveryStore.displayName(Discovery.STRUCTURE, id);
    }

    private static List<Item> items() {
        return BuiltInRegistries.ITEM.stream()
                .filter(item -> item != Items.AIR)
                .sorted(Comparator.comparing(item -> BuiltInRegistries.ITEM.getKey(item).toString()))
                .toList();
    }

    private static final List<EntityType<?>> MOBS = List.of(
            Mobs.ZOMBIE, Mobs.ZOMBIE_VILLAGER, Mobs.HUSK, Mobs.DROWNED,
            Mobs.SKELETON, Mobs.STRAY, Mobs.BOGGED, Mobs.WITHER_SKELETON,
            Mobs.CREEPER, Mobs.SPIDER, Mobs.CAVE_SPIDER, Mobs.ENDERMAN,
            Mobs.ENDERMITE, Mobs.SLIME, Mobs.MAGMA_CUBE, Mobs.WITCH,
            Mobs.BLAZE, Mobs.GHAST, Mobs.PHANTOM, Mobs.BREEZE,
            Mobs.PIGLIN, Mobs.PIGLIN_BRUTE, Mobs.ZOMBIFIED_PIGLIN,
            Mobs.HOGLIN, Mobs.ZOGLIN, Mobs.GUARDIAN, Mobs.ELDER_GUARDIAN,
            Mobs.SHULKER, Mobs.SILVERFISH, Mobs.VEX, Mobs.VINDICATOR,
            Mobs.EVOKER, Mobs.PILLAGER, Mobs.RAVAGER, Mobs.WARDEN,
            Mobs.COW, Mobs.PIG, Mobs.SHEEP, Mobs.CHICKEN,
            Mobs.HORSE, Mobs.DONKEY, Mobs.MULE, Mobs.RABBIT,
            Mobs.WOLF, Mobs.CAT, Mobs.GOAT, Mobs.TURTLE,
            Mobs.FOX, Mobs.BEE, Mobs.CAMEL, Mobs.ARMADILLO,
            Mobs.VILLAGER, Mobs.IRON_GOLEM
    );

    /**
     * What a fresh Notify card is set to: the sound the Bell alert has always used.
     *
     * <p>A full id rather than a bare path, because that is what the registry hands back and what
     * a saved task holds, and a modded sound has a namespace of its own.</p>
     */
    private static final String DEFAULT_NOTIFY_SOUND = "minecraft:block.bell.use";

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

    public static final Set<EntityType<?>> ENDERMAN = Set.of(Mobs.ENDERMAN);

    private static final List<String> COMPARISONS = List.of(
            "Less than", "At most", "Equal to", "At least", "Greater than", "Not equal");

    /** A clock is asked whether it has got there yet, not by how much. */
    private static final List<String> CLOCK_COMPARISONS = List.of(
            ConditionTask.BEFORE, ConditionTask.AT_OR_AFTER);

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

    /**
     * @param named the item the dropdown is showing, which is also what the option is called
     */
    private record SmeltRecipe(Item named, Set<Item> inputs, Item output) {}

    private static final Map<String, SmeltRecipe> SMELT_RECIPES = Map.of(
            "Raw Iron", new SmeltRecipe(Items.RAW_IRON, Set.of(Items.RAW_IRON, Items.IRON_ORE, Items.DEEPSLATE_IRON_ORE), Items.IRON_INGOT),
            "Raw Gold", new SmeltRecipe(Items.RAW_GOLD, Set.of(Items.RAW_GOLD, Items.GOLD_ORE, Items.DEEPSLATE_GOLD_ORE), Items.GOLD_INGOT),
            "Raw Copper", new SmeltRecipe(Items.RAW_COPPER, Set.of(Items.RAW_COPPER, Items.COPPER_ORE, Items.DEEPSLATE_COPPER_ORE), Items.COPPER_INGOT),
            "Cobblestone", new SmeltRecipe(Items.COBBLESTONE, Set.of(Items.COBBLESTONE), Items.STONE),
            // The one smelt that is not an ingot: four of these and four gold make the ingot that
            // Upgrade to Netherite then puts in a smithing table. Ancient debris is the block and
            // the item both, so there is no raw form to name beside it.
            "Ancient Debris", new SmeltRecipe(Items.ANCIENT_DEBRIS, Set.of(Items.ANCIENT_DEBRIS), Items.NETHERITE_SCRAP)
    );

    /**
     * What a smelting option is called, asked of the item rather than written down.
     *
     * <p>These four options name real items, and the game already knows what those are called in
     * every language it ships. Keeping a second copy of the words in Lune's own file means a
     * translator writes them again, a player in a language nobody has translated Lune into reads
     * "Raw Iron" where their furnace says "Rohes Eisen", and the two can drift. The stored value
     * stays the English key it always was - it is written into saved tasks.</p>
     */
    private static String smeltOptionLabel(String value) {
        SmeltRecipe recipe = SMELT_RECIPES.get(value);
        return recipe == null ? Param.Choice.optionLabel(value)
                : Lang.get(recipe.named().getDescriptionId());
    }

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
            Map.entry("save_waypoint", "Movement"),
            Map.entry("step", "Movement"),
            Map.entry("explore", "Movement"),
            Map.entry("find_biome", "Movement"),
            Map.entry("find_structure", "Movement"),
            Map.entry("use_portal", "Movement"),
            Map.entry("mine", "Gathering"),
            Map.entry("chop", "Gathering"),
            Map.entry("replant", "Gathering"),
            Map.entry("find", "Gathering"),
            Map.entry("harvest", "Gathering"),
            Map.entry("dragon_egg", "Gathering"),
            Map.entry("gettool", "Gathering"),
            Map.entry("check_item", "Logic & Conditions"),
            Map.entry("check_player", "Logic & Conditions"),
            Map.entry("check_distance", "Logic & Conditions"),
            Map.entry("check_time", "Logic & Conditions"),
            Map.entry("check_clock", "Logic & Conditions"),
            Map.entry("check_weather", "Logic & Conditions"),
            Map.entry("check_dimension", "Logic & Conditions"),
            Map.entry("countdown", "Logic & Conditions"),
            Map.entry("tunnel", "Mining & Building"),
            Map.entry("stripmine", "Mining & Building"),
            Map.entry("bridge", "Mining & Building"),
            Map.entry("place", "Mining & Building"),
            Map.entry("portal", "Mining & Building"),
            Map.entry("kill", "Combat"),
            Map.entry("huntblazes", "Combat"),
            Map.entry("huntendermen", "Combat"),
            Map.entry("huntcreepers", "Combat"),
            Map.entry("huntskeletons", "Combat"),
            Map.entry("huntsheep", "Gathering"),
            Map.entry("select_item", "Items & Storage"),
            Map.entry("equip", "Items & Storage"),
            Map.entry("loot", "Items & Storage"),
            Map.entry("recover_death", "Items & Storage"),
            Map.entry("notify", "General"),
            Map.entry("deposit", "Items & Storage"),
            Map.entry("backpack_deposit", "Items & Storage"),
            Map.entry("backpack_take", "Items & Storage"),
            Map.entry("smelt", "Items & Storage"),
            Map.entry("upgrade_netherite", "Items & Storage"),
            Map.entry("craft", "Items & Storage"),
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

    /**
     * The cards that only exist because another mod does, and the question that decides each.
     *
     * <p>A gated card is registered like every other one and is still found by {@link #byId}: a
     * task built where the mod is installed still loads, still draws and still runs where it is
     * not, and fails there the way it would have failed anyway. The gate decides one thing only -
     * whether the card is <em>offered</em> - because a palette that hands Deposit to Backpack to
     * a player with no backpack mod is handing them a card that can do nothing but fail.</p>
     *
     * <p>Asked through a supplier rather than answered here, because this map is built while the
     * class loads and the answer comes from the loader: the registry is also built in headless
     * tests, where there is no loader to ask.</p>
     */
    private static final Map<String, BooleanSupplier> MOD_CARDS = Map.of(
            "find_biome", () -> CompassHook.NATURE.installed(),
            "find_structure", () -> CompassHook.EXPLORER.installed(),
            "backpack_deposit", Backpacks::anyInstalled,
            "backpack_take", Backpacks::anyInstalled);

    static {
        register(new CommandDef("mine", List.of(
                new Param.BlockSet("targets", BlockCatalog.all(),
                        Set.of(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE)),
                new Param.Ints("radius", 32, 1, 512),
                new Param.Ints("y_min", -64, -64, 320),
                new Param.Ints("y_max", 320, -64, 320),
                new Param.Ints("limit", 0, 0, 512),
                new Param.Bool("auto_tool", true),
                new Param.Bool("prospect", false),
                new Param.Bool("check_around", true)
        ), def -> new MineTask(
                def.blockValue("targets").resolvedBlocks(),
                def.intValue("radius"),
                def.intValue("y_min"),
                def.intValue("y_max"),
                def.intValue("limit"),
                def.boolValue("auto_tool"),
                def.boolValue("prospect"), false, def.boolValue("check_around"))));

        register(new CommandDef("chop", List.of(
                new Param.Ints("radius", 64, 1, 512),
                new Param.Ints("limit", 8, 0, 512)
        ), def -> new MineTask(new java.util.HashSet<>(BlockCatalog.logs()), def.intValue("radius"),
                -64, 320, def.intValue("limit"), false, false, true)));

        // The other half of Chop Wood, kept as its own card: felling writes down where each tree
        // stood, and planting it back is a separate job the task has to ask for.
        register(new CommandDef("replant", List.of(
                new Param.Ints("radius", 32, 1, 128)
        ), def -> new ReplantTask(def.intValue("radius"))));

        register(new CommandDef("explore", List.of(
                new Param.BlockSet("targets", BlockCatalog.all(),
                        Set.of(Blocks.OAK_LOG, Blocks.SPRUCE_LOG, Blocks.BIRCH_LOG)),
                new Param.Ints("radius", 48, 1, 512),
                new Param.Ints("attempts", 6, 1, 32),
                new Param.Ints("step", 12, 1, 64),
                new Param.Bool("check_around", true),
                new Param.Choice("scan_style", SCAN_STYLES, "Glance ahead"),
                new Param.Bool("smart_direction", true)
        ), def -> new ExploreTask(
                def.blockValue("targets").resolvedBlocks(),
                def.intValue("radius"),
                def.intValue("attempts"),
                def.intValue("step"),
                def.boolValue("check_around"),
                scanStyle(def.choiceValue("scan_style")),
                def.boolValue("smart_direction"))));

        register(new CommandDef("gettool", List.of(
                new Param.Choice("tool", TOOL_CHOICES, TOOL_FROM_BLOCK),
                new Param.Choice("material", ToolCatalog.materialNames(), "Stone"),
                new Param.BlockSet("target", ores(), Set.of(Blocks.IRON_ORE)),
                new Param.Bool("check_around", true)
        ), def -> {
            boolean checkAround = def.boolValue("check_around");
            ToolCatalog.Kind kind = ToolCatalog.parseKind(def.choiceValue("tool"));
            if (kind != null) {
                ToolCatalog.Material material = ToolCatalog.parseMaterial(def.choiceValue("material"));
                return material == null
                        ? FailTask.forCommand("gettool", "Get Tools", "lune.status.fail.pick_material")
                        : new EnsureToolTask(ToolCatalog.item(kind, material), checkAround);
            }
            BlockTarget wanted = def.blockValue("target");
            Block sample = wanted.anyConcreteBlock().orElse(null);
            return wanted.isEmpty() || sample == null
                    ? FailTask.forCommand("gettool", "Get Tools", "lune.status.fail.pick_block")
                    : new EnsureToolTask(sample, checkAround);
        }));

        register(new CommandDef("check_item", List.of(
                new Param.ItemChoice("item", items(), Items.COBBLESTONE),
                new Param.Choice("comparison", COMPARISONS, "Less than"),
                new Param.Ints("count", 10, 0, 9999),
                new Param.Choice("where", List.of(ConditionTask.INVENTORY, ConditionTask.INVENTORY_AND_WORN),
                        ConditionTask.INVENTORY)
        ), def -> ConditionTask.itemCount(def.itemValue("item"), def.choiceValue("comparison"),
                def.intValue("count"), ConditionTask.INVENTORY_AND_WORN.equals(def.choiceValue("where")))));

        // The threshold reaches well past the 300 that Air maxes out at, because the same box now
        // holds an XP level and a distance to another player.
        register(new CommandDef("check_player", List.of(
                new Param.Choice("metric", PlayerMetric.labels(), PlayerMetric.HEALTH.label()),
                new Param.Choice("comparison", COMPARISONS, "At most"),
                new Param.Ints("threshold", 8, 0, 9999)
        ), def -> ConditionTask.playerValue(def.choiceValue("metric"), def.choiceValue("comparison"),
                def.intValue("threshold"))));

        register(new CommandDef("counter", List.of(new Param.Ints("count", 3, 1, 1_000_000)),
                def -> FailTask.forCommand("counter", "Counter", "lune.status.fail.counter_is_pulse_node")));

        // No parameters: what an Observer watches is the card wired into its Watch pin, not a
        // value picked from a list.
        register(new CommandDef("observer", List.of(), def -> FailTask.forCommand("observer", "Observer", "lune.status.fail.observer_is_pulse_source")));

        register(new CommandDef("end", List.of(), def -> FailTask.forCommand("end", "End", "lune.status.fail.end_is_pulse_sink")));

        register(new CommandDef("button", List.of(), def -> FailTask.forCommand("button", "Button", "lune.status.fail.button_is_pulse_source")));

        register(new CommandDef("check_time", List.of(
                new Param.Choice("phase", WorldClock.phaseNames(), WorldClock.Phase.DAY.label())
        ), def -> ConditionTask.worldTime(def.choiceValue("phase"))));

        // Weather and dimension are names rather than numbers, so they ask which one rather than
        // carrying the six comparisons the other conditions do - the same shape as Check Time.
        register(new CommandDef("check_weather", List.of(
                new Param.Choice("weather", Weather.labels(), Weather.CLEAR.label())
        ), def -> ConditionTask.weather(def.choiceValue("weather"))));

        register(new CommandDef("check_dimension", List.of(
                new Param.Choice("dimension", WorldDimension.labels(), WorldDimension.OVERWORLD.label())
        ), def -> ConditionTask.dimension(def.choiceValue("dimension"))));

        // The hour is asked for as two numbers rather than a typed "20:00", so there is no format
        // to get wrong and no way to save a card that reads 25:70.
        register(new CommandDef("check_clock", List.of(
                new Param.Choice("clock", ClockSource.labels(), ClockSource.SYSTEM.label()),
                new Param.Choice("comparison", CLOCK_COMPARISONS, ConditionTask.BEFORE),
                new Param.Ints("hour", 20, 0, 23),
                new Param.Ints("minute", 0, 0, 59)
        ), def -> ConditionTask.clockTime(def.choiceValue("clock"), def.choiceValue("comparison"),
                def.intValue("hour"), def.intValue("minute"))));

        register(new CommandDef("countdown", List.of(
                new Param.Ints("amount", 20, 1, 9999),
                new Param.Choice("unit", CountdownTask.Unit.labels(), CountdownTask.Unit.MINUTES.label())
        ), def -> new CountdownTask(def.intValue("amount"),
                CountdownTask.Unit.fromLabel(def.choiceValue("unit")))));

        register(new CommandDef("check_distance", List.of(
                new Param.Choice("waypoint", () -> WaypointStore.get().names(), ""),
                new Param.Choice("comparison", COMPARISONS, "At most"),
                new Param.Ints("distance", 20, 0, 512)
        ), def -> ConditionTask.waypointDistance(def.choiceValue("waypoint"),
                def.choiceValue("comparison"), def.intValue("distance"))));

        register(new CommandDef("find", List.of(
                new Param.Choice("target_kind", List.of("Blocks", "Mobs"), "Blocks"),
                new Param.BlockSet("targets", ores(),
                        Set.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE)),
                new Param.EntitySet("entities", MOBS,
                        Set.of(Mobs.SHEEP)),
                new Param.Ints("radius", 64, 1, 512),
                new Param.Ints("y_min", -64, -64, 320),
                new Param.Ints("y_max", 320, -64, 320),
                new Param.Bool("prospect", true),
                new Param.Bool("check_around", true)
        ), def -> "Mobs".equalsIgnoreCase(def.choiceValue("target_kind"))
                ? FindTask.forEntities(def.entityValue("entities"), def.intValue("radius"),
                def.boolValue("check_around"))
                : new FindTask(def.blockValue("targets").resolvedBlocks(), def.intValue("radius"),
                def.intValue("y_min"), def.intValue("y_max"), def.boolValue("prospect"),
                def.boolValue("check_around"))));

        register(new CommandDef("harvest", List.of(
                new Param.BlockSet("targets", BlockCatalog.crops(),
                        Set.of(Blocks.WHEAT, Blocks.CARROTS, Blocks.POTATOES, Blocks.BEETROOTS)),
                new Param.Ints("radius", 32, 1, 512),
                new Param.Ints("limit", 0, 0, 512),
                new Param.Bool("collect", true),
                new Param.Bool("replant", true)
        ), def -> new HarvestTask(def.blockValue("targets").resolvedBlocks(), def.intValue("radius"),
                def.intValue("limit"), def.boolValue("collect"), def.boolValue("replant"))));

        register(new CommandDef("walk", List.of(
                new Param.Choice("direction", List.of("Facing", "Back", "Left", "Right", "North", "South", "East", "West", "Coordinates"),
                        "Facing"),
                new Param.Ints("distance", 32, 1, 512),
                new Param.Pos("target", null),
                new Param.Ints("tolerance", 1, 0, 32)
        ), def -> gotoTask(def, "Walk", false)));

        register(new CommandDef("run", List.of(
                new Param.Choice("direction", List.of("Facing", "Back", "Left", "Right", "North", "South", "East", "West", "Coordinates"),
                        "Facing"),
                new Param.Ints("distance", 32, 1, 512),
                new Param.Pos("target", null),
                new Param.Ints("tolerance", 1, 0, 32)
        ), def -> gotoTask(def, "Run", true)));

        register(new CommandDef("step", List.of(
                new Param.Choice("side", StepPolicy.Side.labels(), StepPolicy.Side.RIGHT.label()),
                new Param.Ints("blocks", 1, 1, 16),
                new Param.Bool("careful", true)
        ), def -> new StepTask(StepPolicy.Side.fromLabel(def.choiceValue("side")),
                def.intValue("blocks"), def.boolValue("careful"))));

        register(new CommandDef("find_biome", List.of(
                new Param.Choice("biome", CommandRegistry::biomeIds, "minecraft:jungle",
                        CommandRegistry::biomeLabel),
                new Param.Choice("then", CompassFindTask.THEN_OPTIONS, CompassFindTask.WALK_THERE),
                new Param.Ints("tolerance", 16, 1, 64),
                new Param.Bool("fresh", false)
        ), def -> {
            String biome = def.choiceValue("biome");
            if (biome == null || biome.isBlank()) {
                return FailTask.forCommand("find_biome", "Find Biome", "lune.status.fail.no_biome_chosen");
            }
            return new CompassFindTask(CompassHook.NATURE, biome, def.choiceValue("then"),
                    def.intValue("tolerance"), def.boolValue("fresh"));
        }));

        register(new CommandDef("find_structure", List.of(
                new Param.Choice("structure", CommandRegistry::structureIds, "minecraft:village_plains",
                        CommandRegistry::structureLabel),
                new Param.Choice("then", CompassFindTask.THEN_OPTIONS, CompassFindTask.WALK_THERE),
                new Param.Ints("tolerance", 16, 1, 64),
                new Param.Bool("fresh", false)
        ), def -> {
            String structure = def.choiceValue("structure");
            if (structure == null || structure.isBlank()) {
                return FailTask.forCommand("find_structure", "Find Structure", "lune.status.fail.no_structure_chosen");
            }
            return new CompassFindTask(CompassHook.EXPLORER, structure, def.choiceValue("then"),
                    def.intValue("tolerance"), def.boolValue("fresh"));
        }));

        register(new CommandDef("waypoint", List.of(
                new Param.Choice("name", () -> WaypointStore.get().names(), ""),
                new Param.Ints("tolerance", 2, 0, 32)
        ), def -> {
            String name = def.choiceValue("name");
            return WaypointStore.get().byName(name)
                    .<com.etka.lune.bot.Task>map(waypoint -> WaypointStore.isInCurrentDimension(waypoint)
                            ? new GotoTask(new Goals.Near(waypoint.pos(), def.intValue("tolerance")), true, false)
                            : FailTask.forCommand("waypoint", "Go to Waypoint", "lune.status.fail.waypoint_other_dimension"))
                    .orElseGet(() -> FailTask.forCommand("waypoint", "Go to Waypoint",
                            name.isEmpty() ? "lune.status.fail.no_waypoint_chosen"
                                    : "lune.status.fail.no_waypoint_named", name));
        }));

        register(new CommandDef("save_waypoint", List.of(
                new Param.Choice("action", SaveWaypointTask.Action.labels(), SaveWaypointTask.Action.ADD.label()),
                new Param.Text("name", "", 32),
                new Param.Choice("waypoint", () -> WaypointStore.get().names(), "")
        ), def -> {
            SaveWaypointTask.Action action = SaveWaypointTask.Action.fromLabel(def.choiceValue("action"));
            String name = action == SaveWaypointTask.Action.ADD
                    ? def.textValue("name") : def.choiceValue("waypoint");
            return new SaveWaypointTask(action, name);
        }));

        register(new CommandDef("kill", List.of(
                new Param.EntitySet("targets", MOBS, Set.of(Mobs.ZOMBIE)),
                new Param.Ints("radius", 16, 1, 64),
                new Param.Bool("fire_resistance", true),
                new Param.Bool("use_shield", true),
                new Param.Bool("craft_shield", false),
                new Param.Choice("weapon", WEAPONS, "Automatic"),
                new Param.Bool("craft_weapon", false),
                new Param.Choice("enderman_safety", ENDERMAN_SAFETY,
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

        register(new CommandDef("huntendermen", List.of(
                new Param.Ints("count", 12, 1, 64),
                new Param.Choice("enderman_safety", ENDERMAN_SAFETY,
                        "Auto"),
                new Param.Bool("use_shield", true),
                new Param.Bool("craft_shield", false),
                new Param.Choice("weapon", WEAPONS, "Sword"),
                new Param.Bool("craft_weapon", false)
        ), def -> new HuntEndermenTask(
                def.intValue("count"),
                new KillOptions(
                        false,
                        def.boolValue("use_shield"),
                        def.boolValue("craft_shield"),
                        KillOptions.EndermanSafety.parse(def.choiceValue("enderman_safety")),
                        KillOptions.WeaponPreference.parse(def.choiceValue("weapon")),
                        def.boolValue("craft_weapon")))));

        register(new CommandDef("huntblazes", List.of(
                new Param.Ints("count", 8, 1, 64),
                new Param.Bool("fire_resistance", true),
                new Param.Bool("use_shield", true),
                new Param.Bool("craft_shield", false),
                new Param.Choice("weapon", WEAPONS, "Bow"),
                new Param.Bool("craft_weapon", false)
        ), def -> new HuntBlazesTask(
                def.intValue("count"),
                new KillOptions(
                        def.boolValue("fire_resistance"),
                        def.boolValue("use_shield"),
                        def.boolValue("craft_shield"),
                        KillOptions.EndermanSafety.DIRECT,
                        KillOptions.WeaponPreference.parse(def.choiceValue("weapon")),
                        def.boolValue("craft_weapon")))));

        register(new CommandDef("huntcreepers", List.of(
                new Param.Ints("count", 16, 1, 128),
                new Param.Bool("use_shield", true),
                new Param.Bool("craft_shield", false),
                new Param.Choice("weapon", WEAPONS, "Sword"),
                new Param.Bool("craft_weapon", false)
        ), def -> new HuntCreepersTask(
                def.intValue("count"),
                new KillOptions(
                        false,
                        def.boolValue("use_shield"),
                        def.boolValue("craft_shield"),
                        KillOptions.EndermanSafety.DIRECT,
                        KillOptions.WeaponPreference.parse(def.choiceValue("weapon")),
                        def.boolValue("craft_weapon")))));

        register(new CommandDef("huntskeletons", List.of(
                new Param.Ints("count", 16, 1, 128),
                new Param.Bool("use_shield", true),
                new Param.Bool("craft_shield", false),
                new Param.Choice("weapon", WEAPONS, "Sword"),
                new Param.Bool("craft_weapon", false)
        ), def -> new HuntSkeletonsTask(
                def.intValue("count"),
                new KillOptions(
                        false,
                        def.boolValue("use_shield"),
                        def.boolValue("craft_shield"),
                        KillOptions.EndermanSafety.DIRECT,
                        KillOptions.WeaponPreference.parse(def.choiceValue("weapon")),
                        def.boolValue("craft_weapon")))));

        register(new CommandDef("huntsheep", List.of(
                new Param.Ints("count", 16, 1, 128),
                new Param.Choice("weapon", WEAPONS, "Sword"),
                new Param.Bool("craft_weapon", false)
        ), def -> new HuntSheepTask(
                def.intValue("count"),
                new KillOptions(
                        false,
                        false,
                        false,
                        KillOptions.EndermanSafety.DIRECT,
                        KillOptions.WeaponPreference.parse(def.choiceValue("weapon")),
                        def.boolValue("craft_weapon")))));

        register(new CommandDef("loot", List.of(
                new Param.Ints("radius", 16, 1, 64)
        ), def -> new LootTask(def.intValue("radius"))));

        register(new CommandDef("recover_death", List.of(
                new Param.Choice("which", RecoverDeathTask.WHICH_OPTIONS, RecoverDeathTask.NEAREST),
                new Param.Ints("radius", 8, 1, 32)
        ), def -> new RecoverDeathTask(def.choiceValue("which"), def.intValue("radius"))));

        register(new CommandDef("dragon_egg", List.of(
                new Param.Ints("radius", 24, 1, 64)
        ), def -> new DragonEggTask(def.intValue("radius"))));

        register(new CommandDef("tunnel", List.of(
                new Param.Choice("direction", List.of("Facing", "North", "South", "East", "West"), "Facing"),
                new Param.Ints("length", 64, 1, 512),
                new Param.Ints("height", 2, 2, 4)
        ), def -> new TunnelTask(def.choiceValue("direction"), def.intValue("length"), def.intValue("height"))));

        register(new CommandDef("stripmine", List.of(
                new Param.BlockSet("target", ores(), Set.of()),
                new Param.Ints("y_level", -59, -64, 320),
                new Param.Ints("branch_length", 32, 4, 256),
                new Param.Ints("spacing", 3, 2, 16),
                new Param.Ints("branches", 8, 1, 64)
        ), def -> new StripmineTask(def.blockValue("target").resolvedBlocks(), def.intValue("y_level"),
                def.intValue("branch_length"), def.intValue("spacing"), def.intValue("branches"))));

        register(new CommandDef("bridge", List.of(
                new Param.Choice("direction", List.of("Facing", "North", "South", "East", "West"), "Facing"),
                new Param.Ints("length", 16, 1, 512),
                new Param.BlockSet("materials", BlockCatalog.buildingBlocks(),
                        Set.of(Blocks.COBBLESTONE))
        ), def -> new BridgeTask(def.choiceValue("direction"), def.intValue("length"), def.blockValue("materials").resolvedBlocks())));

        register(new CommandDef("place", List.of(
                new Param.BlockSet("blocks", BlockCatalog.buildingBlocks(), Set.of(Blocks.COBBLESTONE)),
                new Param.Choice("where", PlaceBlockTask.Where.labels(), PlaceBlockTask.Where.IN_FRONT.label()),
                new Param.Pos("target", null),
                new Param.Choice("waypoint", () -> WaypointStore.get().names(), "")
        ), def -> {
            PlaceBlockTask.Where where = PlaceBlockTask.Where.fromLabel(def.choiceValue("where"));
            java.util.Set<Block> blocks = def.blockValue("blocks").resolvedBlocks();
            if (blocks.isEmpty()) {
                return FailTask.forCommand("place", "Place Block", "lune.status.fail.pick_block_to_place");
            }
            BlockPos spot = switch (where) {
                case COORDINATES -> def.posValue("target");
                case WAYPOINT -> WaypointStore.get().byName(def.choiceValue("waypoint"))
                        .filter(WaypointStore::isInCurrentDimension)
                        .map(com.etka.lune.waypoint.Waypoint::pos)
                        .orElse(null);
                default -> null;
            };
            if (!where.isRelative() && spot == null) {
                return FailTask.forCommand("place", "Place Block", where == PlaceBlockTask.Where.WAYPOINT
                        ? "lune.status.fail.choose_waypoint_here"
                        : "lune.status.fail.set_coordinates");
            }
            return new PlaceBlockTask(blocks, where, spot);
        }));

        register(new CommandDef("use_portal", List.of(
                new Param.Ints("radius", 32, 4, 128)
        ), def -> UsePortalTask.card(def.intValue("radius"))));

        register(new CommandDef("portal", List.of(
                new Param.Choice("frame_mode", BuildPortalTask.modeChoices(), BuildPortalTask.FrameMode.RESOURCE_SAVING.label()),
                new Param.BlockSet("corner_materials", List.of(Blocks.DIRT, Blocks.COBBLESTONE),
                        Set.of(Blocks.DIRT, Blocks.COBBLESTONE))
        ), def -> new BuildPortalTask(def.choiceValue("frame_mode"),
                def.blockValue("corner_materials").resolvedBlocks())));

        register(new CommandDef("deposit", List.of(
                new Param.Choice("filter", ItemFilters.DEPOSIT, ItemFilters.ORES),
                new Param.Ints("radius", 16, 1, 64),
                new Param.Bool("optional", false)
        ), def -> new DepositTask(def.choiceValue("filter"), def.intValue("radius"),
                def.boolValue("optional"))));

        register(new CommandDef("backpack_deposit", List.of(
                new Param.Choice("filter", ItemFilters.DEPOSIT, ItemFilters.ORES),
                new Param.Bool("optional", false)
        ), def -> new BackpackDepositTask(def.choiceValue("filter"), def.boolValue("optional"))));

        register(new CommandDef("backpack_take", List.of(
                new Param.Choice("filter", ItemFilters.TAKE, ItemFilters.ITEM),
                new Param.ItemChoice("item", items(), Items.BREAD),
                new Param.Ints("count", 16, 0, 2304),
                new Param.Bool("optional", false)
        ), def -> new BackpackTakeTask(def.choiceValue("filter"), def.itemValue("item"),
                def.intValue("count"), def.boolValue("optional"))));

        register(new CommandDef("smelt", List.of(
                new Param.Choice("input", () -> List.copyOf(SMELT_RECIPES.keySet()), "Raw Iron",
                        CommandRegistry::smeltOptionLabel),
                new Param.Ints("count", 8, 1, 256)
        ), def -> {
            String input = def.choiceValue("input");
            SmeltRecipe recipe = SMELT_RECIPES.get(input);
            if (recipe == null) {
                return FailTask.forCommand("smelt", "Smelt", "lune.status.fail.unknown_input", input);
            }
            return new SmeltTask(recipe.inputs, recipe.output, def.intValue("count"));
        }));

        // The gear is asked for as an item rather than as a tool-and-tier the way Get Tools asks,
        // because the smithing table's answer is not a ladder: it upgrades armour, a horse's armour
        // and whatever else a version has added beside the five hand tools, and the pairing comes
        // out of the registry rather than out of a list here.
        register(new CommandDef("upgrade_netherite", List.of(
                new Param.ItemChoice("gear", NetheriteUpgrades.bases(), Items.DIAMOND_PICKAXE)
        ), def -> {
            Item gear = def.itemValue("gear");
            return gear == null
                    ? FailTask.forCommand("upgrade_netherite", "Upgrade to Netherite",
                            "lune.status.fail.pick_gear_to_upgrade")
                    : new NetheriteUpgradeTask(gear);
        }));

        register(new CommandDef("fish", List.of(
                new Param.Bool("auto_recast", true)
        ), def -> new FishTask(def.boolValue("auto_recast"))));

        // Running a task is itself a command, which is what lets one task hand off to
        // another - "when the mining run is done, switch to harvesting".
        register(new CommandDef("task", List.of(
                // Listed and stored by the name the task is saved under, so the card still finds it
                // after a language change; shown by whatever that task's title reads as today.
                new Param.Choice("name", () -> TaskStore.get().listedNames(), "", TaskStore::displayNameOf)
        ), def -> {
            String name = def.choiceValue("name");
            return TaskStore.get().byName(name)
                    .<com.etka.lune.bot.Task>map(TaskRunner::new)
                    .orElseGet(() -> FailTask.forCommand("task", "Run Task",
                            name.isEmpty() ? "lune.status.fail.no_task_chosen" : "lune.status.fail.no_task_named", name));
        }));

        // Kept as its own entry so tasks saved before Stop the Game grew its endings still load.
        // It builds the same task, so there is one implementation of pausing, not two.
        register(new CommandDef("pause_game", List.of(), def -> new StopGameTask(StopGameTask.Ending.PAUSE)));

        register(new CommandDef("timer", List.of(new Param.Ints("seconds", 5, 0, 3600)),
                def -> new TimerTask(def.intValue("seconds"))));

        // Any sound the game has, not a shortlist Lune keeps: a registry of a thousand-odd is why
        // this one is edited by a picker with a search box rather than by the inline dropdown.
        register(new CommandDef("notify", List.of(
                new Param.Text("message", "", 64),
                new Param.Choice("sound", SoundCatalog::ids, DEFAULT_NOTIFY_SOUND, SoundCatalog::label)
                        .editedBy(Param.Choice.SOUND_PICKER)
        ), def -> new NotifyTask(def.textValue("message"), def.choiceValue("sound"))));

        register(new CommandDef("stop_game", List.of(
                new Param.Choice("ending", java.util.Arrays.stream(StopGameTask.Ending.values())
                                .map(StopGameTask.Ending::label).toList(),
                        StopGameTask.Ending.QUIT.label())
        ), def -> new StopGameTask(StopGameTask.Ending.fromLabel(def.choiceValue("ending")))));

        register(new CommandDef("find_map", List.of(
                new Param.ItemChoice("map", items(),
                        Items.FILLED_MAP),
                new Param.Choice("aim", java.util.Arrays.stream(FindMapTask.Aim.values())
                                .map(FindMapTask.Aim::label).toList(),
                        FindMapTask.Aim.MARKER.label()),
                new Param.Ints("arrive_within", 16, 1, 128)
        ), def -> new FindMapTask(
                def.itemValue("map"),
                FindMapTask.Aim.fromLabel(def.choiceValue("aim")),
                def.intValue("arrive_within"))));

        register(new CommandDef("select_item", List.of(
                new Param.ItemChoice("item", items(), Items.DIAMOND_PICKAXE),
                new Param.Choice("enchanting", java.util.Arrays.stream(SelectItemTask.Enchanting.values())
                                .map(SelectItemTask.Enchanting::label).toList(),
                        SelectItemTask.Enchanting.ANY.label()),
                new Param.Choice("hand", java.util.Arrays.stream(SelectItemTask.Hand.values())
                                .map(SelectItemTask.Hand::label).toList(),
                        SelectItemTask.Hand.MAIN.label()),
                new Param.Ints("min_durability", 0, 0, 100),
                new Param.Choice("prefer", java.util.Arrays.stream(SelectItemTask.Preference.values())
                                .map(SelectItemTask.Preference::label).toList(),
                        SelectItemTask.Preference.MOST_DURABLE.label())
        ), def -> new SelectItemTask(
                def.itemValue("item"),
                SelectItemTask.Enchanting.fromLabel(def.choiceValue("enchanting")),
                SelectItemTask.Hand.fromLabel(def.choiceValue("hand")),
                def.intValue("min_durability"),
                SelectItemTask.Preference.fromLabel(def.choiceValue("prefer")))));

        // Two rows, because there are two questions and the second one is only sometimes asked:
        // a whole-kit swap needs no item, and CommandDef.isRelevant hides the row while that is
        // what is being asked for.
        register(new CommandDef("equip", List.of(
                new Param.Choice("what", java.util.Arrays.stream(EquipTask.What.values())
                                .map(EquipTask.What::label).toList(),
                        EquipTask.What.BEST_ARMOR.label()),
                new Param.ItemChoice("item", items(), Items.ELYTRA)
        ), def -> new EquipTask(
                EquipTask.What.fromLabel(def.choiceValue("what")),
                def.itemValue("item"))));

        register(new CommandDef("craft", List.of(
                // One row, because "which recipe?" is one question. Clicking it opens the editor,
                // where naming an item and drawing a grid are two tabs of the same choice.
                new Param.Recipe("recipe"),
                new Param.Ints("count", 1, 1, 512),
                new Param.Bool("table", true)
        ), def -> {
            CraftRecipe recipe = def.recipeValue("recipe");
            if (recipe.isDrawn()) {
                return recipe.pattern().isEmpty()
                        ? FailTask.forCommand("craft", "Craft", "lune.status.fail.draw_ingredients")
                        : new GridCraftTask(recipe.pattern(), def.intValue("count"));
            }
            return recipe.item() == null
                    ? FailTask.forCommand("craft", "Craft", "lune.status.fail.choose_craft")
                    // make, not of: a card asked for four planks makes four planks, rather than
                    // finding sixty-four in the bag and finishing without doing anything.
                    : CraftTask.make(recipe.item(), def.intValue("count"), def.boolValue("table"));
        }));

        register(new CommandDef("eat", List.of(
                new Param.Ints("minimum_food", 14, 0, 20)
        ), def -> new EatTask(stack -> true, def.intValue("minimum_food"))));

        register(new CommandDef("boat", List.of(
                new Param.Pos("target", null),
                new Param.Bool("reclaim", true)
        ), def -> new BoatTask(def.posValue("target"), def.boolValue("reclaim"))));

        register(new CommandDef("sleep", List.of(
                new Param.Bool("wait_for_night", true),
                new Param.Bool("reclaim", true),
                new Param.Ints("radius", 32, 8, 128)
        ), def -> new SleepTask(
                def.boolValue("wait_for_night"),
                def.boolValue("reclaim"),
                def.intValue("radius"))));

        // The threat switches say when to step in; the tactic switches under each of them say how.
        // Each sits directly beneath the danger it belongs to, and CommandDef.isRelevant hides it
        // while that danger is switched off - a row that is read in one state and ignored in the
        // other invites an answer and then throws it away.
        register(new CommandDef("self_preservation", List.of(
                new Param.Bool("protect_air", true),
                new Param.Choice("air_compare", List.of("At most", "Less than", "At least", "Greater than"), "At most"),
                new Param.Ints("air_value", 120, 0, 300),
                new Param.Bool("protect_lava", true),
                new Param.Bool("protect_fire", true),
                new Param.Bool("protect_fall", true),
                new Param.Ints("fall_threshold", 10, 2, 512),
                new Param.Bool("clutch_water", true),
                new Param.Bool("clutch_boat", true),
                new Param.Bool("clutch_cushion", true),
                new Param.Bool("protect_monsters", true),
                new Param.Choice("monster_compare", List.of("At most", "Less than", "At least", "Greater than"), "At most"),
                new Param.Ints("monster_distance", 8, 1, 64),
                new Param.Bool("protect_fireballs", true),
                new Param.Bool("build_cover", true),
                new Param.Bool("protect_health", true),
                new Param.Choice("health_compare", List.of("At most", "Less than", "At least", "Greater than"), "At most"),
                new Param.Ints("health_value", 8, 1, 20)
        ), def -> new SelfPreservationTask(
                def.boolValue("protect_air"), def.choiceValue("air_compare"), def.intValue("air_value"),
                def.boolValue("protect_lava"), def.boolValue("protect_monsters"),
                def.choiceValue("monster_compare"), def.intValue("monster_distance"),
                def.boolValue("protect_health"), def.choiceValue("health_compare"),
                def.intValue("health_value"),
                def.boolValue("protect_fall"), def.intValue("fall_threshold"),
                def.boolValue("protect_fire"),
                new SafetyOptions(
                        def.boolValue("clutch_water"),
                        def.boolValue("clutch_boat"),
                        def.boolValue("clutch_cushion"),
                        def.boolValue("protect_fireballs"),
                        def.boolValue("build_cover")))));

        register(new CommandDef("stay_near", List.of(
                new Param.Choice("anchor", List.of(StayNearTask.FROM_RUN_START, StayNearTask.FROM_WAYPOINT),
                        StayNearTask.FROM_RUN_START),
                new Param.Choice("waypoint", () -> WaypointStore.get().names(), ""),
                new Param.Ints("radius", 60, 8, 512)
        ), def -> new StayNearTask(def.choiceValue("anchor"), def.choiceValue("waypoint"),
                def.intValue("radius"))));

        register(new CommandDef("completegame", List.of(),
                def -> new SpeedrunTask()));
    }

    private CommandRegistry() {}

    private static void register(CommandDef def) {
        COMMANDS.add(def);
    }

    /**
     * Shared factory for Walk and Run, which differ only in whether they sprint.
     *
     * <p>{@code label} is not what the player reads any more: a title is drawn from the card's
     * own {@code lune.command.*.name} line, so the id goes across too. The English word stays
     * because it is the learner's row key, and those rows are already on disk. The same split
     * {@link FailTask#forCommand} makes below, for the same reason - which is also why the id
     * is asked of the card rather than worked out a second time from {@code sprint}.</p>
     */
    private static com.etka.lune.bot.Task gotoTask(CommandDef def, String label, boolean sprint) {
        String direction = def.choiceValue("direction");
        if (!"Coordinates".equals(direction)) {
            return new DirectionalGotoTask(def.id(), label, direction, def.intValue("distance"),
                    def.intValue("tolerance"), sprint);
        }
        BlockPos target = def.posValue("target");
        if (target == null) {
            return FailTask.forCommand(def.id(), label, "lune.status.fail.no_destination");
        }
        return new GotoTask(new Goals.Near(target, def.intValue("tolerance")), sprint, false);
    }

    public static List<CommandDef> all() {
        return List.copyOf(COMMANDS);
    }

    /**
     * Whether a card is worth offering here: true unless it needs a mod this game does not have.
     *
     * <p>Not a question about the world or the inventory. Find Biome is offered to anyone with
     * Nature's Compass installed, whether or not they have crafted one yet - crafting it is an
     * Ensure Tool card in front of it, and a card that tells you to go and get the compass is
     * useful in a way that a card that is not there is not.</p>
     */
    public static boolean isAvailable(String id) {
        BooleanSupplier gate = MOD_CARDS.get(id);
        return gate == null || gate.getAsBoolean();
    }

    /** Every command the palette should offer, in registration order. */
    public static List<CommandDef> available() {
        return COMMANDS.stream().filter(def -> isAvailable(def.id())).toList();
    }

    /**
     * The cards that are only offered when another mod is installed, by id.
     *
     * <p>The ids alone, so they can be named without asking the loader anything. A gate whose id
     * no longer matches a card is a gate that quietly stops gating, which looks exactly like the
     * card having been un-gated on purpose.</p>
     */
    public static Set<String> modCards() {
        return MOD_CARDS.keySet();
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
