package com.etka.lune.routine;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RoutineSuggestionTest {

    @Test
    void offersAndAppliesLargeStoneGoal() {
        Routine routine = routine("Stone run");
        RoutineNode mine = node("mine");
        mine.params.put("targets", "minecraft:stone,minecraft:cobblestone");
        mine.params.put("limit", "0");
        routine.nodes.add(mine);

        RoutineSuggestion suggestion = RoutineSuggestion.firstFor(routine, mine, Set.of()).orElseThrow();

        assertEquals(RoutineSuggestion.Kind.QUANTITY, suggestion.kind());
        assertEquals(128, suggestion.suggestedAmount());
        assertTrue(suggestion.apply(96));
        assertEquals("96", mine.params.get("limit"));
    }

    @Test
    void explainsThatOnceRunsCanStillHaveAnUnboundedMineBody() {
        Routine routine = routine("Single mining pass");
        RoutineNode mine = node("mine");
        mine.params.put("targets", "minecraft:stone");
        mine.params.put("limit", "0");
        mine.repeat = 1;
        routine.nodes.add(mine);

        RoutineSuggestion suggestion = RoutineSuggestion.firstFor(routine, mine, Set.of()).orElseThrow();

        assertTrue(suggestion.prompt().contains("run once"));
        assertTrue(suggestion.prompt().contains("no block goal"));
    }

    @Test
    void startAdviceIsAvailableBeforeWorkAdviceForLegacyRoutines() {
        Routine routine = routine("Legacy route");
        RoutineNode mine = node("mine");
        mine.params.put("targets", "minecraft:stone");
        mine.params.put("limit", "0");
        routine.nodes.add(mine);

        RoutineSuggestion start = RoutineSuggestion.startFor(routine).orElseThrow();

        assertEquals(RoutineSuggestion.Kind.START, start.kind());
        assertTrue(start.apply(0));
        assertEquals(RoutineNode.START_COMMAND, routine.nodes.get(0).commandId);
        assertEquals(mine.id, routine.nodes.get(0).onSuccess);
    }

    @Test
    void startAdviceAlsoRepairsAnEmptyImportedRoutine() {
        Routine routine = routine("Empty import");

        RoutineSuggestion start = RoutineSuggestion.startFor(routine).orElseThrow();

        assertTrue(start.apply(0));
        assertEquals(RoutineNode.START_COMMAND, routine.nodes.get(0).commandId);
        assertNull(routine.nodes.get(0).onSuccess);
    }

    @Test
    void alwaysIsAlreadyTheImplicitEntryPoint() {
        Routine routine = routine("Protected route");
        routine.nodes.add(node(RoutineNode.ALWAYS_COMMAND));
        routine.nodes.add(node("mine"));

        assertTrue(RoutineSuggestion.startFor(routine).isEmpty());
        assertNull(RoutineGraph.addExplicitStart(routine));
        assertEquals(0, routine.nodes.stream().filter(RoutineNode::isStartNode).count());
    }

    @Test
    void finiteSelfPreservationAdviceMakesTheMonitorPermanent() {
        Routine routine = routine("Protected route");
        RoutineNode always = node(RoutineNode.ALWAYS_COMMAND);
        RoutineNode guard = node(RoutineSafety.COMMAND_ID);
        guard.repeat = 1;
        always.alwaysTargets.add(guard.id);
        routine.nodes.add(always);
        routine.nodes.add(guard);

        RoutineSuggestion suggestion = RoutineSuggestion.finiteMonitorFor(routine).orElseThrow();

        assertEquals(RoutineSuggestion.Kind.SAFETY, suggestion.kind());
        assertTrue(suggestion.prompt().contains("x1"));
        assertTrue(suggestion.apply(0));
        assertEquals(0, guard.repeat);
    }

    @Test
    void doesNotOverwriteWiredOrLoopedAmounts() {
        Routine wiredRoutine = routine("Wired");
        RoutineNode counter = node("check_item");
        counter.params.put("count", "64");
        counter.exposedOutputs.add("count");
        RoutineNode wiredMine = node("mine");
        wiredMine.params.put("limit", "0");
        wiredMine.exposedInputs.add("limit");
        wiredMine.inputLinks.put("limit", new RoutineDataLink(counter.id, "count"));
        wiredRoutine.nodes.add(counter);
        wiredRoutine.nodes.add(wiredMine);
        assertTrue(RoutineSuggestion.firstFor(wiredRoutine, wiredMine, Set.of()).isEmpty());

        Routine loopRoutine = routine("Loop");
        RoutineNode loopMine = node("mine");
        loopMine.params.put("limit", "0");
        loopMine.onSuccess = loopMine.id;
        loopRoutine.nodes.add(loopMine);
        assertTrue(RoutineSuggestion.firstFor(loopRoutine, loopMine, Set.of()).isEmpty());
    }

    @Test
    void offersToolPreparationAfterQuantityIsHandled() {
        Routine routine = routine("Ore run");
        RoutineNode mine = node("mine");
        mine.params.put("targets", "minecraft:iron_ore");
        mine.params.put("limit", "0");
        mine.params.put("auto_tool", "false");
        routine.nodes.add(mine);

        RoutineSuggestion quantity = RoutineSuggestion.firstFor(routine, mine, Set.of()).orElseThrow();
        RoutineSuggestion tool = RoutineSuggestion.firstFor(routine, mine, Set.of(quantity.key())).orElseThrow();

        assertEquals(RoutineSuggestion.Kind.AUTO_TOOL, tool.kind());
        assertTrue(tool.apply(0));
        assertEquals("true", mine.params.get("auto_tool"));
    }

    @Test
    void aMonitorActionIsNeverRewritten() {
        Routine routine = routine("Monitor");
        RoutineNode work = node("chop");
        work.params.put("limit", "32");
        RoutineNode monitor = node("mine");
        monitor.params.put("limit", "0");
        work.onWhile = monitor.id;
        routine.nodes.add(work);
        routine.nodes.add(monitor);

        assertTrue(RoutineSuggestion.firstFor(routine, monitor, Set.of()).isEmpty());
    }

    @Test
    void routineFingerprintTracksBehaviourButNotCanvasPosition() {
        Routine routine = routine("Stone run");
        RoutineNode mine = node("mine");
        mine.params.put("targets", "minecraft:stone");
        mine.params.put("limit", "0");
        routine.nodes.add(mine);

        String originalFingerprint = RoutineSuggestion.fingerprint(routine);
        String originalKey = RoutineSuggestion.firstFor(routine, mine, Set.of()).orElseThrow().key();
        mine.editorX = 500;
        mine.editorY = 240;
        assertEquals(originalFingerprint, RoutineSuggestion.fingerprint(routine));

        mine.params.put("limit", "64");
        assertNotEquals(originalFingerprint, RoutineSuggestion.fingerprint(routine));
        mine.params.put("limit", "0");
        mine.params.put("targets", "minecraft:deepslate");
        assertNotEquals(originalKey,
                RoutineSuggestion.firstFor(routine, mine, Set.of()).orElseThrow().key());
    }

    @Test
    void sizesInventoryAdviceAndRespectsDownstreamDeposit() {
        Routine routine = routine("Wood stock");
        RoutineNode chop = node("chop");
        chop.params.put("limit", "128");
        routine.nodes.add(chop);
        RoutineSuggestion.Context cramped = context(1, 20, 64, 1000, 1000,
                false, "minecraft:overworld", "forest", false);

        RoutineSuggestion warning = RoutineSuggestion.firstFor(routine, chop, Set.of(), cramped)
                .orElseThrow();
        assertEquals(RoutineSuggestion.Kind.INVENTORY_SPACE, warning.kind());
        assertTrue(warning.changesRoutine());
        assertTrue(warning.hasPreview());
        assertTrue(warning.prompt().contains("128-log"));
        assertTrue(warning.prompt().contains("1 and no Deposit"));
        assertTrue(warning.apply(0));
        assertEquals("128", chop.params.get("limit"));
        assertEquals(2, routine.nodes.size());
        RoutineNode deposit = routine.nodes.get(1);
        assertEquals("deposit", deposit.commandId);
        assertEquals("Logs", deposit.params.get("filter"));
        assertEquals("true", deposit.params.get("optional"));
        assertEquals(deposit.id, chop.onSuccess);

        assertTrue(RoutineSuggestion.firstFor(routine, chop, Set.of(), cramped).isEmpty());
    }

    @Test
    void warnsAboutDurabilityFoodTorchesAndNightOnlyWhenComponentsDoNotCoverThem() {
        Routine tunnelRoutine = routine("Long tunnel");
        RoutineNode tunnel = node("tunnel");
        tunnel.params.put("length", "96");
        tunnel.params.put("height", "2");
        tunnelRoutine.nodes.add(tunnel);
        RoutineSuggestion.Context wornTool = context(36, 20, 64, 20, 1000,
                false, "minecraft:overworld", "plains", false);
        assertEquals(RoutineSuggestion.Kind.TOOL_DURABILITY,
                RoutineSuggestion.firstFor(tunnelRoutine, tunnel, Set.of(), wornTool)
                        .orElseThrow().kind());
        RoutineSuggestion tools = RoutineSuggestion.firstFor(tunnelRoutine, tunnel, Set.of(), wornTool)
                .orElseThrow();
        assertTrue(tools.changesRoutine());
        assertTrue(tools.apply(0));
        assertEquals("gettool", tunnelRoutine.nodes.get(0).commandId);
        assertEquals(tunnel.id, tunnelRoutine.nodes.get(0).onFailure);
        RoutineSuggestion.Context darkTunnel = context(36, 20, 0, 1000, 1000,
                false, "minecraft:overworld", "plains", false);
        assertEquals(RoutineSuggestion.Kind.TORCHES,
                RoutineSuggestion.firstFor(tunnelRoutine, tunnel, Set.of(), darkTunnel)
                        .orElseThrow().kind());

        Routine surfaceRoutine = routine("Long bridge");
        RoutineNode bridge = node("bridge");
        bridge.params.put("length", "96");
        surfaceRoutine.nodes.add(bridge);
        RoutineSuggestion.Context hungry = context(36, 7, 64, 1000, 1000,
                false, "minecraft:overworld", "plains", false);
        assertEquals(RoutineSuggestion.Kind.FOOD,
                RoutineSuggestion.firstFor(surfaceRoutine, bridge, Set.of(), hungry)
                        .orElseThrow().kind());
        surfaceRoutine.nodes.add(0, node("eat"));
        RoutineSuggestion.Context nighttime = context(36, 20, 64, 1000, 1000,
                true, "minecraft:overworld", "plains", false);
        assertEquals(RoutineSuggestion.Kind.SLEEP,
                RoutineSuggestion.firstFor(surfaceRoutine, bridge, Set.of(), nighttime)
                        .orElseThrow().kind());
        surfaceRoutine.nodes.add(0, node("sleep"));
        assertTrue(RoutineSuggestion.firstFor(surfaceRoutine, bridge, Set.of(), nighttime).isEmpty());
    }

    @Test
    void catchesDimensionAndBarrenBiomeMismatchesConservatively() {
        Routine netherRoutine = routine("Debris");
        RoutineNode mine = node("mine");
        mine.params.put("targets", "minecraft:ancient_debris,minecraft:netherrack");
        mine.params.put("limit", "32");
        mine.params.put("auto_tool", "true");
        netherRoutine.nodes.add(mine);
        RoutineSuggestion.Context overworld = context(36, 20, 64, 1000, 1000,
                false, "minecraft:overworld", "plains", false);
        RoutineSuggestion dimensionWarning = RoutineSuggestion.firstFor(
                netherRoutine, mine, Set.of(), overworld).orElseThrow();
        assertEquals(RoutineSuggestion.Kind.LOCATION, dimensionWarning.kind());
        assertTrue(dimensionWarning.prompt().contains("Nether"));

        Routine woodRoutine = routine("Desert lumber");
        RoutineNode chop = node("chop");
        chop.params.put("limit", "64");
        woodRoutine.nodes.add(chop);
        RoutineSuggestion.Context desert = context(36, 20, 64, 1000, 1000,
                false, "minecraft:overworld", "desert", true);
        RoutineSuggestion biomeWarning = RoutineSuggestion.firstFor(
                woodRoutine, chop, Set.of(), desert).orElseThrow();
        assertEquals(RoutineSuggestion.Kind.LOCATION, biomeWarning.kind());
        assertTrue(biomeWarning.prompt().contains("almost no trees"));
    }

    @Test
    void insertsEatAndSleepBeforeSafeJobsButOnlyWarnsAroundLoops() {
        Routine foodRoutine = routine("Hungry bridge");
        RoutineNode bridge = node("bridge");
        bridge.params.put("length", "96");
        foodRoutine.nodes.add(bridge);
        RoutineSuggestion.Context hungry = context(36, 6, 64, 1000, 1000,
                false, "minecraft:overworld", "plains", false);

        RoutineSuggestion food = RoutineSuggestion.firstFor(foodRoutine, bridge, Set.of(), hungry)
                .orElseThrow();
        assertEquals(RoutineSuggestion.Kind.FOOD, food.kind());
        assertTrue(food.apply(0));
        assertEquals("eat", foodRoutine.nodes.get(0).commandId);
        assertEquals("18", foodRoutine.nodes.get(0).params.get("minimum_food"));

        Routine sleepRoutine = routine("Night bridge");
        RoutineNode nightBridge = node("bridge");
        nightBridge.params.put("length", "96");
        sleepRoutine.nodes.add(nightBridge);
        RoutineSuggestion.Context night = context(36, 20, 64, 1000, 1000,
                true, "minecraft:overworld", "plains", false);
        RoutineSuggestion sleep = RoutineSuggestion.firstFor(sleepRoutine, nightBridge, Set.of(), night)
                .orElseThrow();
        assertEquals(RoutineSuggestion.Kind.SLEEP, sleep.kind());
        assertTrue(sleep.apply(0));
        assertEquals("sleep", sleepRoutine.nodes.get(0).commandId);
        assertEquals("false", sleepRoutine.nodes.get(0).params.get("wait_for_night"));

        Routine loopRoutine = routine("Looping bridge");
        RoutineNode loop = node("bridge");
        loop.params.put("length", "96");
        loop.onSuccess = loop.id;
        loopRoutine.nodes.add(loop);
        RoutineSuggestion loopFood = RoutineSuggestion.firstFor(loopRoutine, loop, Set.of(), hungry)
                .orElseThrow();
        assertEquals(RoutineSuggestion.Kind.FOOD, loopFood.kind());
        assertFalse(loopFood.changesRoutine());
        assertTrue(loopFood.apply(0));
        assertEquals(1, loopRoutine.nodes.size());
    }

    private static RoutineSuggestion.Context context(int freeSlots, int food, int torches,
                                                       int pickaxe, int axe, boolean night,
                                                       String dimension, String biome,
                                                       boolean barrenForWood) {
        return new RoutineSuggestion.Context(freeSlots, food, torches, pickaxe, axe, night,
                dimension, biome, barrenForWood, true);
    }

    private static Routine routine(String name) {
        Routine routine = new Routine();
        routine.name = name;
        return routine;
    }

    private static RoutineNode node(String command) {
        return new RoutineNode(command);
    }
}
