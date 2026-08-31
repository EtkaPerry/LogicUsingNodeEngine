package com.etka.lune.task;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TaskSuggestionTest {

    @Test
    void offersAndAppliesLargeStoneGoal() {
        TaskGraph task = task("Stone run");
        TaskNode mine = node("mine");
        mine.params.put("targets", "minecraft:stone,minecraft:cobblestone");
        mine.params.put("limit", "0");
        task.nodes.add(mine);

        TaskSuggestion suggestion = TaskSuggestion.firstFor(task, mine, Set.of()).orElseThrow();

        assertEquals(TaskSuggestion.Kind.QUANTITY, suggestion.kind());
        assertEquals(128, suggestion.suggestedAmount());
        assertTrue(suggestion.apply(96));
        assertEquals("96", mine.params.get("limit"));
    }

    @Test
    void explainsThatOnceRunsCanStillHaveAnUnboundedMineBody() {
        TaskGraph task = task("Single mining pass");
        TaskNode mine = node("mine");
        mine.params.put("targets", "minecraft:stone");
        mine.params.put("limit", "0");
        mine.repeat = 1;
        task.nodes.add(mine);

        TaskSuggestion suggestion = TaskSuggestion.firstFor(task, mine, Set.of()).orElseThrow();

        assertTrue(suggestion.prompt().contains("run once"));
        assertTrue(suggestion.prompt().contains("no block goal"));
    }

    @Test
    void startAdviceIsAvailableBeforeWorkAdviceForLegacyTasks() {
        TaskGraph task = task("Legacy route");
        TaskNode mine = node("mine");
        mine.params.put("targets", "minecraft:stone");
        mine.params.put("limit", "0");
        task.nodes.add(mine);

        TaskSuggestion start = TaskSuggestion.startFor(task).orElseThrow();

        assertEquals(TaskSuggestion.Kind.START, start.kind());
        assertTrue(start.apply(0));
        assertEquals(TaskNode.START_COMMAND, task.nodes.get(0).commandId);
        assertEquals(mine.id, task.nodes.get(0).onSuccess);
    }

    @Test
    void startAdviceAlsoRepairsAnEmptyImportedTask() {
        TaskGraph task = task("Empty import");

        TaskSuggestion start = TaskSuggestion.startFor(task).orElseThrow();

        assertTrue(start.apply(0));
        assertEquals(TaskNode.START_COMMAND, task.nodes.get(0).commandId);
        assertNull(task.nodes.get(0).onSuccess);
    }

    @Test
    void alwaysIsAlreadyTheImplicitEntryPoint() {
        TaskGraph task = task("Protected route");
        task.nodes.add(node(TaskNode.ALWAYS_COMMAND));
        task.nodes.add(node("mine"));

        assertTrue(TaskSuggestion.startFor(task).isEmpty());
        assertNull(TaskWiring.addExplicitStart(task));
        assertEquals(0, task.nodes.stream().filter(TaskNode::isStartNode).count());
    }

    @Test
    void finiteSelfPreservationAdviceMakesTheMonitorPermanent() {
        TaskGraph task = task("Protected route");
        TaskNode always = node(TaskNode.ALWAYS_COMMAND);
        TaskNode guard = node(TaskSafety.COMMAND_ID);
        guard.repeat = 1;
        always.alwaysTargets.add(guard.id);
        task.nodes.add(always);
        task.nodes.add(guard);

        TaskSuggestion suggestion = TaskSuggestion.finiteMonitorFor(task).orElseThrow();

        assertEquals(TaskSuggestion.Kind.SAFETY, suggestion.kind());
        assertTrue(suggestion.prompt().contains("x1"));
        assertTrue(suggestion.apply(0));
        assertEquals(0, guard.repeat);
    }

    @Test
    void aGuardWiredIntoOrdinaryFlowGetsPermanenceAdviceAndThenSilence() {
        // The player's own wiring: Always -> Check Player -> (Success) Self Preservation. Lune has
        // exactly one thing left to say about it, and only while it is still a one-shot.
        TaskGraph task = task("Watched route");
        TaskNode always = node(TaskNode.ALWAYS_COMMAND);
        TaskNode check = node("condition");
        TaskNode guard = node(TaskSafety.COMMAND_ID);
        guard.repeat = 1;
        check.onSuccess = guard.id;
        always.alwaysTargets.add(check.id);
        task.nodes.add(always);
        task.nodes.add(check);
        task.nodes.add(guard);

        TaskSuggestion suggestion = TaskSuggestion.finiteMonitorFor(task).orElseThrow();
        assertEquals(TaskSuggestion.Kind.SAFETY, suggestion.kind());
        assertTrue(suggestion.apply(0));
        assertEquals(0, guard.repeat);

        assertTrue(TaskSuggestion.finiteMonitorFor(task).isEmpty());
        assertTrue(TaskSafety.hasMonitor(task));
    }

    @Test
    void doesNotOverwriteWiredOrLoopedAmounts() {
        TaskGraph wiredTask = task("Wired");
        TaskNode counter = node("check_item");
        counter.params.put("count", "64");
        counter.exposedOutputs.add("count");
        TaskNode wiredMine = node("mine");
        wiredMine.params.put("limit", "0");
        wiredMine.exposedInputs.add("limit");
        wiredMine.inputLinks.put("limit", new TaskDataLink(counter.id, "count"));
        wiredTask.nodes.add(counter);
        wiredTask.nodes.add(wiredMine);
        assertTrue(TaskSuggestion.firstFor(wiredTask, wiredMine, Set.of()).isEmpty());

        TaskGraph loopTask = task("Loop");
        TaskNode loopMine = node("mine");
        loopMine.params.put("limit", "0");
        loopMine.onSuccess = loopMine.id;
        loopTask.nodes.add(loopMine);
        assertTrue(TaskSuggestion.firstFor(loopTask, loopMine, Set.of()).isEmpty());
    }

    @Test
    void offersToolPreparationAfterQuantityIsHandled() {
        TaskGraph task = task("Ore run");
        TaskNode mine = node("mine");
        mine.params.put("targets", "minecraft:iron_ore");
        mine.params.put("limit", "0");
        mine.params.put("auto_tool", "false");
        task.nodes.add(mine);

        TaskSuggestion quantity = TaskSuggestion.firstFor(task, mine, Set.of()).orElseThrow();
        TaskSuggestion tool = TaskSuggestion.firstFor(task, mine, Set.of(quantity.key())).orElseThrow();

        assertEquals(TaskSuggestion.Kind.AUTO_TOOL, tool.kind());
        assertTrue(tool.apply(0));
        assertEquals("true", mine.params.get("auto_tool"));
    }

    @Test
    void aMonitorActionIsNeverRewritten() {
        TaskGraph task = task("Monitor");
        TaskNode work = node("chop");
        work.params.put("limit", "32");
        TaskNode monitor = node("mine");
        monitor.params.put("limit", "0");
        work.onWhile = monitor.id;
        task.nodes.add(work);
        task.nodes.add(monitor);

        assertTrue(TaskSuggestion.firstFor(task, monitor, Set.of()).isEmpty());
    }

    @Test
    void taskFingerprintTracksBehaviourButNotCanvasPosition() {
        TaskGraph task = task("Stone run");
        TaskNode mine = node("mine");
        mine.params.put("targets", "minecraft:stone");
        mine.params.put("limit", "0");
        task.nodes.add(mine);

        String originalFingerprint = TaskSuggestion.fingerprint(task);
        String originalKey = TaskSuggestion.firstFor(task, mine, Set.of()).orElseThrow().key();
        mine.editorX = 500;
        mine.editorY = 240;
        assertEquals(originalFingerprint, TaskSuggestion.fingerprint(task));

        mine.params.put("limit", "64");
        assertNotEquals(originalFingerprint, TaskSuggestion.fingerprint(task));
        mine.params.put("limit", "0");
        mine.params.put("targets", "minecraft:deepslate");
        assertNotEquals(originalKey,
                TaskSuggestion.firstFor(task, mine, Set.of()).orElseThrow().key());
    }

    @Test
    void sizesInventoryAdviceAndRespectsDownstreamDeposit() {
        TaskGraph task = task("Wood stock");
        TaskNode chop = node("chop");
        chop.params.put("limit", "128");
        task.nodes.add(chop);
        TaskSuggestion.Context cramped = context(1, 20, 64, 1000, 1000,
                false, "minecraft:overworld", "forest", false);

        TaskSuggestion warning = TaskSuggestion.firstFor(task, chop, Set.of(), cramped)
                .orElseThrow();
        assertEquals(TaskSuggestion.Kind.INVENTORY_SPACE, warning.kind());
        assertTrue(warning.changesTask());
        assertTrue(warning.hasPreview());
        assertTrue(warning.prompt().contains("128-log"));
        assertTrue(warning.prompt().contains("1 and no Deposit"));
        assertTrue(warning.apply(0));
        assertEquals("128", chop.params.get("limit"));
        assertEquals(2, task.nodes.size());
        TaskNode deposit = task.nodes.get(1);
        assertEquals("deposit", deposit.commandId);
        assertEquals("Logs", deposit.params.get("filter"));
        assertEquals("true", deposit.params.get("optional"));
        assertEquals(deposit.id, chop.onSuccess);

        assertTrue(TaskSuggestion.firstFor(task, chop, Set.of(), cramped).isEmpty());
    }

    @Test
    void warnsAboutDurabilityFoodTorchesAndNightOnlyWhenComponentsDoNotCoverThem() {
        TaskGraph tunnelTask = task("Long tunnel");
        TaskNode tunnel = node("tunnel");
        tunnel.params.put("length", "96");
        tunnel.params.put("height", "2");
        tunnelTask.nodes.add(tunnel);
        TaskSuggestion.Context wornTool = context(36, 20, 64, 20, 1000,
                false, "minecraft:overworld", "plains", false);
        assertEquals(TaskSuggestion.Kind.TOOL_DURABILITY,
                TaskSuggestion.firstFor(tunnelTask, tunnel, Set.of(), wornTool)
                        .orElseThrow().kind());
        TaskSuggestion tools = TaskSuggestion.firstFor(tunnelTask, tunnel, Set.of(), wornTool)
                .orElseThrow();
        assertTrue(tools.changesTask());
        assertTrue(tools.apply(0));
        assertEquals("gettool", tunnelTask.nodes.get(0).commandId);
        assertEquals(tunnel.id, tunnelTask.nodes.get(0).onFailure);
        TaskSuggestion.Context darkTunnel = context(36, 20, 0, 1000, 1000,
                false, "minecraft:overworld", "plains", false);
        assertEquals(TaskSuggestion.Kind.TORCHES,
                TaskSuggestion.firstFor(tunnelTask, tunnel, Set.of(), darkTunnel)
                        .orElseThrow().kind());

        TaskGraph surfaceTask = task("Long bridge");
        TaskNode bridge = node("bridge");
        bridge.params.put("length", "96");
        surfaceTask.nodes.add(bridge);
        TaskSuggestion.Context hungry = context(36, 7, 64, 1000, 1000,
                false, "minecraft:overworld", "plains", false);
        assertEquals(TaskSuggestion.Kind.FOOD,
                TaskSuggestion.firstFor(surfaceTask, bridge, Set.of(), hungry)
                        .orElseThrow().kind());
        surfaceTask.nodes.add(0, node("eat"));
        TaskSuggestion.Context nighttime = context(36, 20, 64, 1000, 1000,
                true, "minecraft:overworld", "plains", false);
        assertEquals(TaskSuggestion.Kind.SLEEP,
                TaskSuggestion.firstFor(surfaceTask, bridge, Set.of(), nighttime)
                        .orElseThrow().kind());
        surfaceTask.nodes.add(0, node("sleep"));
        assertTrue(TaskSuggestion.firstFor(surfaceTask, bridge, Set.of(), nighttime).isEmpty());
    }

    @Test
    void catchesDimensionAndBarrenBiomeMismatchesConservatively() {
        TaskGraph netherTask = task("Debris");
        TaskNode mine = node("mine");
        mine.params.put("targets", "minecraft:ancient_debris,minecraft:netherrack");
        mine.params.put("limit", "32");
        mine.params.put("auto_tool", "true");
        netherTask.nodes.add(mine);
        TaskSuggestion.Context overworld = context(36, 20, 64, 1000, 1000,
                false, "minecraft:overworld", "plains", false);
        TaskSuggestion dimensionWarning = TaskSuggestion.firstFor(
                netherTask, mine, Set.of(), overworld).orElseThrow();
        assertEquals(TaskSuggestion.Kind.LOCATION, dimensionWarning.kind());
        assertTrue(dimensionWarning.prompt().contains("Nether"));

        TaskGraph woodTask = task("Desert lumber");
        TaskNode chop = node("chop");
        chop.params.put("limit", "64");
        woodTask.nodes.add(chop);
        TaskSuggestion.Context desert = context(36, 20, 64, 1000, 1000,
                false, "minecraft:overworld", "desert", true);
        TaskSuggestion biomeWarning = TaskSuggestion.firstFor(
                woodTask, chop, Set.of(), desert).orElseThrow();
        assertEquals(TaskSuggestion.Kind.LOCATION, biomeWarning.kind());
        assertTrue(biomeWarning.prompt().contains("almost no trees"));
    }

    @Test
    void insertsEatAndSleepBeforeSafeJobsButOnlyWarnsAroundLoops() {
        TaskGraph foodTask = task("Hungry bridge");
        TaskNode bridge = node("bridge");
        bridge.params.put("length", "96");
        foodTask.nodes.add(bridge);
        TaskSuggestion.Context hungry = context(36, 6, 64, 1000, 1000,
                false, "minecraft:overworld", "plains", false);

        TaskSuggestion food = TaskSuggestion.firstFor(foodTask, bridge, Set.of(), hungry)
                .orElseThrow();
        assertEquals(TaskSuggestion.Kind.FOOD, food.kind());
        assertTrue(food.apply(0));
        assertEquals("eat", foodTask.nodes.get(0).commandId);
        assertEquals("18", foodTask.nodes.get(0).params.get("minimum_food"));

        TaskGraph sleepTask = task("Night bridge");
        TaskNode nightBridge = node("bridge");
        nightBridge.params.put("length", "96");
        sleepTask.nodes.add(nightBridge);
        TaskSuggestion.Context night = context(36, 20, 64, 1000, 1000,
                true, "minecraft:overworld", "plains", false);
        TaskSuggestion sleep = TaskSuggestion.firstFor(sleepTask, nightBridge, Set.of(), night)
                .orElseThrow();
        assertEquals(TaskSuggestion.Kind.SLEEP, sleep.kind());
        assertTrue(sleep.apply(0));
        assertEquals("sleep", sleepTask.nodes.get(0).commandId);
        assertEquals("false", sleepTask.nodes.get(0).params.get("wait_for_night"));

        TaskGraph loopTask = task("Looping bridge");
        TaskNode loop = node("bridge");
        loop.params.put("length", "96");
        loop.onSuccess = loop.id;
        loopTask.nodes.add(loop);
        TaskSuggestion loopFood = TaskSuggestion.firstFor(loopTask, loop, Set.of(), hungry)
                .orElseThrow();
        assertEquals(TaskSuggestion.Kind.FOOD, loopFood.kind());
        assertFalse(loopFood.changesTask());
        assertTrue(loopFood.apply(0));
        assertEquals(1, loopTask.nodes.size());
    }

    private static TaskSuggestion.Context context(int freeSlots, int food, int torches,
                                                       int pickaxe, int axe, boolean night,
                                                       String dimension, String biome,
                                                       boolean barrenForWood) {
        return new TaskSuggestion.Context(freeSlots, food, torches, pickaxe, axe, night,
                dimension, biome, barrenForWood);
    }

    private static TaskGraph task(String name) {
        TaskGraph task = new TaskGraph();
        task.name = name;
        return task;
    }

    private static TaskNode node(String command) {
        return new TaskNode(command);
    }
}
