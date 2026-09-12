package com.etka.lune.task;

import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Structural checks for the jobs every new profile receives. */
class DefaultTasksTest {

    private static final String LOGS_JOB = "1. Chop 12 Logs";
    private static final String STONE_JOB = "2. Wood, Pickaxe, 20 Stone";
    private static final String HOMESTEAD_JOB = "3. Homestead: Farm and Guard";
    private static final String NIGHTFALL_JOB = "4. Fish Till Dusk, Then Sleep";
    private static final String PORTAL_JOB = "5. Stone Tools to a Lit Portal";
    private static final String DRAGON_JOB = "6. New World to Ender Dragon";

    private static final List<String> EXPECTED_NAMES = List.of(LOGS_JOB, STONE_JOB, HOMESTEAD_JOB,
            NIGHTFALL_JOB, PORTAL_JOB, DRAGON_JOB);

    /** Quoted in the README's table of what ships, so the doc goes stale loudly rather than quietly. */
    private static final List<Integer> EXPECTED_SIZES = List.of(8, 17, 22, 27, 53, 79);

    /** The Task tab's rename box; a seeded name longer than this cannot be typed back. */
    private static final int NAME_BOX_LIMIT = 32;

    @Test
    void shipsExactlyTheSixLadderJobsInRungOrder() {
        List<TaskGraph> tasks = DefaultTasks.create();
        assertEquals(EXPECTED_NAMES, tasks.stream().map(task -> task.name).toList());
        assertEquals(EXPECTED_SIZES, tasks.stream().map(task -> task.nodes.size()).toList());

        int previousSize = 0;
        for (TaskGraph task : tasks) {
            assertTrue(task.nodes.size() > previousSize,
                    task.name + " should be more structurally ambitious than the rung below it");
            previousSize = task.nodes.size();
            assertTrue(task.name.length() <= NAME_BOX_LIMIT,
                    task.name + " is longer than the rename box allows");
        }
    }

    @Test
    void everyConnectionIsInternalAndEveryGraphPassesTheSharedAudit() {
        for (TaskGraph task : DefaultTasks.create()) {
            for (TaskNode node : task.nodes) {
                assertTarget(task, node, "Success", node.onSuccess);
                assertTarget(task, node, "Fail", node.onFailure);
                assertTarget(task, node, "While", node.onWhile);
                if (node.onWhile != null) {
                    assertTrue(node.whileVisible,
                            task.name + ": " + node.id + " hides its While protection output");
                }

                for (String target : node.alwaysTargets) {
                    assertNotNull(task.nodeById(target),
                            task.name + ": clock points at missing node " + target);
                }
                if (node.observedNodeId != null) {
                    assertNotNull(task.nodeById(node.observedNodeId),
                            task.name + ": Observer watches missing node " + node.observedNodeId);
                }
                for (TaskSignalLink link : node.signalLinks) {
                    assertNotNull(task.nodeById(link.targetNodeId),
                            task.name + ": pulse points at missing node " + link.targetNodeId);
                }
                for (TaskDataLink link : node.inputLinks.values()) {
                    assertNotNull(task.nodeById(link.sourceNodeId),
                            task.name + ": data wire reads missing node " + link.sourceNodeId);
                }
            }
            assertTrue(TaskConnectionAudit.firstIssue(task).isEmpty(),
                    () -> task.name + ": " + TaskConnectionAudit.firstIssue(task)
                            .map(TaskConnectionAudit.Issue::message).orElse("unknown issue"));
        }
    }

    @Test
    void nodeIdsAreUniqueAndCardsAreRunnable() {
        for (TaskGraph task : DefaultTasks.create()) {
            Set<String> seen = new HashSet<>();
            long actions = 0;
            for (TaskNode node : task.nodes) {
                assertTrue(seen.add(node.id), task.name + " reuses node id " + node.id);
                assertFalse(node.commandId.isBlank(), task.name + ": " + node.id + " has no command");
                if (!node.isSourceNode() && !node.isPulseNode()
                        && !"self_preservation".equals(node.commandId)) {
                    actions++;
                }
                assertNotNull(node.editorX, task.name + ": " + node.id + " has no canvas X");
                assertNotNull(node.editorY, task.name + ": " + node.id + " has no canvas Y");
            }
            assertTrue(actions > 0, task.name + " has no real work card");
        }
    }

    /**
     * The complaint that produced this layout: bands used to restart at column zero on a new row,
     * so the cable out of one band swept all the way back across the canvas to reach the start of
     * the next - a typewriter carriage return drawn in dotted line. The main lane is now one row
     * that only ever moves right, with detours hanging underneath it.
     */
    @Test
    void theMainLaneRunsLeftToRightAndNeverWraps() {
        for (TaskGraph task : DefaultTasks.create()) {
            TaskNode start = TaskWiring.explicitStart(task);
            assertNotNull(start, task.name + " has no START to anchor the lane on");
            int laneY = start.editorY;

            List<TaskNode> lane = task.nodes.stream()
                    .filter(node -> node.editorY == laneY)
                    .sorted(Comparator.comparingInt(node -> node.editorX))
                    .toList();
            assertEquals(start, lane.getFirst(),
                    task.name + ": START should be the leftmost card in its lane");
            for (int i = 1; i < lane.size(); i++) {
                assertTrue(lane.get(i).editorX > lane.get(i - 1).editorX,
                        task.name + ": " + lane.get(i).id + " does not sit right of "
                                + lane.get(i - 1).id);
            }

            for (TaskNode node : task.nodes) {
                assertTrue(node.editorY >= laneY,
                        task.name + ": " + node.id + " sits above the main lane");
                assertTrue(node.editorX >= start.editorX,
                        task.name + ": " + node.id + " sits left of START, so something wrapped");
            }
        }
    }

    /**
     * The measurement that actually catches a wrap: how far back a cable has to reach.
     *
     * <p>A retry wire reaches back a column or two, and job 4's day loop reaches back five. A band
     * that restarted at column zero made the cable into it reach back across the entire job -
     * forty columns, in the case of the dragon run. Anything past a short hop is a lane that wrapped
     * rather than a loop that was meant.</p>
     */
    @Test
    void noCableSweepsBackAcrossTheCanvas() {
        int longestHopBack = 8;
        for (TaskGraph task : DefaultTasks.create()) {
            for (TaskNode node : task.nodes) {
                for (String targetId : List.of(
                        node.onSuccess == null ? node.id : node.onSuccess,
                        node.onFailure == null ? node.id : node.onFailure)) {
                    TaskNode target = task.nodeById(targetId);
                    int columnsBack = (node.editorX - target.editorX) / 208;
                    assertTrue(columnsBack <= longestHopBack,
                            task.name + ": " + node.id + " -> " + targetId + " reaches back "
                                    + columnsBack + " columns, which is a wrapped lane rather than "
                                    + "a deliberate loop");
                }
            }
        }
    }

    /** A detour belongs directly beneath the card that chooses it, not in whatever column is free. */
    @Test
    void everyDetourSitsUnderTheGateThatChoosesIt() {
        record Anchored(String job, String gate, String detour) {}
        for (Anchored pair : List.of(
                new Anchored(LOGS_JOB, "chop_12", "tree_search"),
                new Anchored(STONE_JOB, "stone_count", "stone_top_up"),
                new Anchored(HOMESTEAD_JOB, "larder", "stock_meat"),
                new Anchored(HOMESTEAD_JOB, "arm_gate", "forge_sword"),
                new Anchored(NIGHTFALL_JOB, "rod_gate", "shore_hunt"),
                new Anchored(NIGHTFALL_JOB, "bedtime_gate", "chore_wood"),
                new Anchored(PORTAL_JOB, "kit_gate", "earn_kit"),
                new Anchored(PORTAL_JOB, "diamond_count", "branch_two"),
                new Anchored(PORTAL_JOB, "portal_build", "portal_retry"),
                new Anchored(DRAGON_JOB, "e_scan", "e_branch_one"),
                new Anchored(DRAGON_JOB, "mission_end", "mission_watch"))) {
            TaskGraph task = task(pair.job());
            TaskNode gate = task.nodeById(pair.gate());
            TaskNode detour = task.nodeById(pair.detour());
            assertNotNull(detour, pair.job() + " has no card " + pair.detour());
            assertEquals(gate.editorX, detour.editorX,
                    pair.job() + ": " + pair.detour() + " should sit under " + pair.gate());
            assertTrue(detour.editorY > gate.editorY,
                    pair.job() + ": " + pair.detour() + " should sit below " + pair.gate());
        }
    }

    /**
     * A cable that runs a long way backwards is walked through the gutter instead of bowing back
     * along its own row, straight across every card in between.
     */
    @Test
    void everyLongReturnCableIsRoutedAroundTheCards() {
        // Named so the check below cannot pass by there being nothing to route: job 4's day loop
        // and its three returns out of the yard watch are the reason this exists.
        assertTrue(task(NIGHTFALL_JOB).cableAnchors.size() >= 4,
                "the nightfall job's long returns should all be routed");
        assertTrue(task(HOMESTEAD_JOB).cableAnchors.size() >= 1,
                "the homestead's boundary cables reach back across the shift");

        for (TaskGraph task : DefaultTasks.create()) {
            for (TaskNode source : task.nodes) {
                for (String kind : List.of("success", "failure", "while")) {
                    String targetId = switch (kind) {
                        case "success" -> source.onSuccess;
                        case "failure" -> source.onFailure;
                        default -> source.onWhile;
                    };
                    TaskNode target = task.nodeById(targetId);
                    if (target == null) {
                        continue;
                    }
                    boolean longReturn = TaskCanvas.outputX(source) - TaskCanvas.inputX(target)
                            >= 2 * 208;
                    TaskCableRoute cable = task.cableAnchors
                            .get(TaskCableAnchor.key(kind, source.id, target.id));
                    assertEquals(longReturn, cable != null,
                            task.name + ": " + kind + " " + source.id + " -> " + targetId
                                    + (longReturn ? " needs a route around the cards"
                                            : " does not need one"));
                }
            }
        }
    }

    /** A routing point drawn on top of a node is the thing the routing exists to avoid. */
    @Test
    void noSeededRoutingPointLandsOnACard() {
        for (TaskGraph task : DefaultTasks.create()) {
            for (var entry : task.cableAnchors.entrySet()) {
                for (TaskCableAnchor point : entry.getValue().points) {
                    for (TaskNode node : task.nodes) {
                        assertFalse(TaskCanvas.insideCard(node, point.x, point.y),
                                task.name + ": the route for " + entry.getKey() + " puts a point at "
                                        + point.x + "," + point.y + " on top of " + node.id);
                    }
                }
            }
        }
    }

    /** The stubs are what keep a routed cable leaving and arriving horizontally. */
    @Test
    void everySeededRouteLeavesAndArrivesLevelWithItsPins() {
        for (TaskGraph task : DefaultTasks.create()) {
            for (var entry : task.cableAnchors.entrySet()) {
                String[] parts = entry.getKey().split("\\|");
                TaskNode source = task.nodeById(parts[1]);
                TaskNode target = task.nodeById(parts[2]);
                List<TaskCableAnchor> points = entry.getValue().points;
                assertNotNull(source);
                assertNotNull(target);

                int pinY = switch (parts[0]) {
                    case "success" -> TaskCanvas.successY(source);
                    case "failure" -> TaskCanvas.failureY(source);
                    default -> TaskCanvas.whileY(source);
                };
                assertEquals(pinY, points.getFirst().y,
                        task.name + ": " + entry.getKey() + " leaves its pin at an angle");
                assertTrue(points.getFirst().x > TaskCanvas.outputX(source),
                        task.name + ": " + entry.getKey() + " leaves backwards out of the pin");
                assertEquals(TaskCanvas.inputY(target), points.getLast().y,
                        task.name + ": " + entry.getKey() + " arrives at its pin at an angle");
                assertTrue(points.getLast().x < TaskCanvas.inputX(target),
                        task.name + ": " + entry.getKey() + " arrives from the wrong side");
            }
        }
    }

    /**
     * The layout is written by hand, so the way it fails is by forgetting a card - which silently
     * stacks it on top of another one rather than raising anything.
     */
    @Test
    void noTwoCardsShareACanvasPosition() {
        for (TaskGraph task : DefaultTasks.create()) {
            Set<String> taken = new HashSet<>();
            for (TaskNode node : task.nodes) {
                assertTrue(taken.add(node.editorX + "," + node.editorY),
                        task.name + ": " + node.id + " sits on top of another card at "
                                + node.editorX + "," + node.editorY);
            }
        }
    }

    @Test
    void everyJobHasARealPowerSource() {
        for (TaskGraph task : DefaultTasks.create()) {
            TaskNode start = TaskWiring.explicitStart(task);
            assertTrue(start != null || TaskWiring.hasAlwaysNode(task),
                    task.name + " has neither START nor a clock source");
            if (start != null) {
                assertNotNull(start.onSuccess, task.name + " has an unconnected START");
            }
        }
    }

    /** The first rung: chop, sweep, craft the axe those logs paid for, chop again. */
    @Test
    void theLogsJobIsAStraightLineWithOneRetryWire() {
        TaskGraph logs = task(LOGS_JOB);
        assertEquals(8, logs.nodes.size());
        assertEquals("chop_12", TaskWiring.explicitStart(logs).onSuccess);
        assertEquals("12", logs.nodeById("chop_12").params.get("limit"));
        assertEquals("logs_loot", logs.nodeById("chop_12").onSuccess);
        assertEquals("wooden_axe", logs.nodeById("logs_loot").onSuccess);
        assertEquals("Axe", logs.nodeById("wooden_axe").params.get("tool"));
        assertEquals("Wooden", logs.nodeById("wooden_axe").params.get("material"));
        assertEquals(List.of("tree_search -> chop_12"), backwardWires(logs),
                "only the treeless-clearing search should send the first job backwards");
    }

    /** The second rung: the pickaxe exists before the stone card, and twenty is counted. */
    @Test
    void theStoneJobCountsItsTwentyCobbleBeforeSpendingIt() {
        TaskGraph stone = task(STONE_JOB);

        TaskNode woodenPick = stone.nodeById("wooden_pick");
        assertEquals("Pickaxe", woodenPick.params.get("tool"));
        assertEquals("Wooden", woodenPick.params.get("material"));

        TaskNode quarry = stone.nodeById("stone_20");
        assertEquals("20", quarry.params.get("limit"));
        assertEquals("minecraft:stone", quarry.params.get("targets"));

        TaskNode count = stone.nodeById("stone_count");
        assertEquals("minecraft:cobblestone", count.params.get("item"));
        assertEquals("At least", count.params.get("comparison"));
        assertEquals("20", count.params.get("count"));
        assertEquals("stone_pick", count.onSuccess);
        assertEquals("stone_top_up", count.onFailure,
                "coming up short should take the top-up detour, not spend what isn't there");

        for (String id : List.of("stone_pick", "stone_axe", "stone_sword")) {
            assertEquals("Stone", stone.nodeById(id).params.get("material"),
                    id + " should spend the twenty cobble the job just counted");
        }
    }

    /**
     * The third rung: the one that never goes underground.
     *
     * <p>Two monitors, and the point is that they are on different cards. A While pin holds one
     * companion, so a job that wants a boundary around its field work and a guard on its fighting
     * has to say which card gets which - and a set where every middle job was a mining run would
     * never have shown that.</p>
     */
    @Test
    void theHomesteadJobWorksByDaylightUnderTwoDifferentMonitors() {
        TaskGraph home = task(HOMESTEAD_JOB);

        TaskNode dayGate = home.nodeById("day_gate");
        assertEquals("check_time", dayGate.commandId);
        assertEquals("Day", dayGate.params.get("phase"));
        assertEquals("field", dayGate.onSuccess);
        assertEquals("yard_watch", dayGate.onFailure,
                "after dark the shift is a watch, not a harvest");
        assertEquals("day_gate", TaskWiring.explicitStart(home).onSuccess);

        assertEquals("stay_near", home.nodeById("fence").commandId);
        assertEquals("Where the run started", home.nodeById("fence").params.get("anchor"));
        for (String id : List.of("field", "stock_meat", "wood_run")) {
            assertEquals("fence", home.nodeById(id).onWhile,
                    id + " should be held inside the property");
            assertTrue(home.nodeById(id).whileVisible);
        }
        assertNull(home.nodeById("drive_off").onWhile,
                "chasing a mob past the fence is the one thing that may leave the property");
        assertTrue(TaskSafety.hasMonitor(home));

        // Looking before arming means the sword is only made on a night that needs one.
        assertEquals("arm_gate", home.nodeById("yard_watch").onSuccess);
        assertEquals("supper_gate", home.nodeById("yard_watch").onFailure);
        assertEquals("forge_sword", home.nodeById("arm_gate").onFailure);
    }

    /** The fourth rung: three loops, and the clock closes every one of them. */
    @Test
    void theNightfallJobClosesEveryLoopWithTheClock() {
        TaskGraph night = task(NIGHTFALL_JOB);

        // Fish until Day stops being true.
        assertEquals("Day", night.nodeById("day_gate").params.get("phase"));
        assertEquals("day_gate", night.nodeById("stash_catch").onSuccess);
        assertEquals("bedtime_gate", night.nodeById("day_gate").onFailure);

        // Chores and a yard watch until a bed would actually accept.
        TaskNode bedtime = night.nodeById("bedtime_gate");
        assertEquals("check_time", bedtime.commandId);
        assertEquals("Dark enough to sleep", bedtime.params.get("phase"));
        assertEquals("bed_gate", bedtime.onSuccess);
        assertEquals("chore_wood", bedtime.onFailure);
        assertEquals("bedtime_gate", night.nodeById("yard_watch").onFailure);

        // A night that did not pass sends the job back to the watch instead of to bed again.
        assertEquals("morning_gate", night.nodeById("turn_in").onSuccess);
        assertEquals("Day", night.nodeById("morning_gate").params.get("phase"));
        assertEquals("morning_stash", night.nodeById("morning_gate").onSuccess);
        assertEquals("yard_watch", night.nodeById("morning_gate").onFailure);

        // Fish reads the main hand and no card crafts a rod, so the gate is load-bearing.
        TaskNode rod = night.nodeById("rod_gate");
        assertEquals("select_item", rod.commandId);
        assertEquals("minecraft:fishing_rod", rod.params.get("item"));
        assertEquals("Main hand", rod.params.get("hand"));
        assertEquals("cast", rod.onSuccess);
        assertEquals("shore_hunt", rod.onFailure);

        assertEquals("fence", night.nodeById("cast").onWhile,
                "a lake worth fishing can be a long way from the property");
        assertEquals("fence", night.nodeById("shore_hunt").onWhile);
    }

    /** The fifth rung: one descent, with one ore choice feeding every mining card in it. */
    @Test
    void thePortalJobIsOneDescentWithBoundedLoops() {
        TaskGraph portal = task(PORTAL_JOB);

        // The iron quota loop, with both ways out of it bounded.
        assertEquals("iron_dig", portal.nodeById("iron_quota").onFailure);
        assertEquals("iron_mine", portal.nodeById("iron_dig").onSuccess);
        assertEquals("iron_roam", portal.nodeById("iron_mine").onFailure);
        assertEquals("iron_mine", portal.nodeById("iron_roam").onSuccess);
        assertEquals("coal_run", portal.nodeById("iron_dig").onFailure,
                "a stripmine that cannot even start should hand the job forward");
        assertEquals("iron_smelt", portal.nodeById("iron_roam").onFailure,
                "an exhausted search should smelt what it has instead of circling underground");

        // One Find decides what "ore" means for the rest of the descent.
        TaskNode scan = portal.nodeById("diamond_scan");
        assertTrue(scan.exposedOutputs.contains("targets"));
        assertEquals("#minecraft:diamond_ores", scan.params.get("targets"));
        for (String id : List.of("branch_one", "diamond_mine", "diamond_roam", "branch_two",
                "mine_again")) {
            TaskNode target = portal.nodeById(id);
            String input = target.commandId.equals("stripmine") ? "target" : "targets";
            TaskDataLink link = target.inputLinks.get(input);
            assertNotNull(link, id + " does not receive the shared ore choice");
            assertEquals("diamond_scan", link.sourceNodeId);
            assertEquals("targets", link.sourcePort);
        }
        assertTrue(Integer.parseInt(portal.nodeById("branch_two").params.get("branches"))
                        > Integer.parseInt(portal.nodeById("branch_one").params.get("branches")),
                "the second pass should dig harder than the first, not the same");

        // Endermen are a night job, so the hunt asks the clock instead of roaming at noon.
        assertEquals("night_gate", portal.nodeById("pearl_gate").onFailure);
        assertEquals("Night", portal.nodeById("night_gate").params.get("phase"));
        assertEquals("pearl_hunt", portal.nodeById("night_gate").onSuccess);
        assertEquals("haul_stash", portal.nodeById("night_gate").onFailure);
        assertEquals("12", portal.nodeById("pearl_hunt").params.get("count"),
                "twelve pearls is what an End portal frame wants");

        assertEquals("minecraft:obsidian", portal.nodeById("obsidian_count").params.get("item"));
        assertEquals("10", portal.nodeById("obsidian_count").params.get("count"),
                "ten is the corner-saving frame's exact bill of materials");
        assertEquals("lighter_gate", portal.nodeById("obsidian_count").onSuccess);

        assertEquals("minecraft:flint_and_steel", portal.nodeById("lighter_gate").params.get("item"));
        assertEquals("minecraft:fire_charge", portal.nodeById("charge_gate").params.get("item"));
        assertEquals("portal_build", portal.nodeById("lighter_gate").onSuccess);
        assertEquals("portal_build", portal.nodeById("charge_gate").onSuccess);
        assertEquals("pearl_gate", portal.nodeById("charge_gate").onFailure,
                "no way to light the frame should skip the build, not fail the job");
        assertEquals("portal_retry", portal.nodeById("portal_build").onFailure);

        // Select from Inventory used as a condition rather than as an action.
        TaskNode wear = portal.nodeById("pick_wear");
        assertEquals("select_item", wear.commandId);
        assertEquals("25", wear.params.get("min_durability"));
        assertEquals("spare_pick", wear.onFailure);
    }

    /** The top rung: the whole ladder in one graph, plus everything that runs beside it. */
    @Test
    void theDragonJobIsTheWholeLadderUnderEverySupportCircuit() {
        TaskGraph dragon = task(DRAGON_JOB);
        assertTrue(dragon.nodes.size() >= 60,
                "the closing job earns its place by covering the whole game, not by being tidy");

        // Every band hands the run to the next one and never asks for it back.
        assertEquals("a_chop", TaskWiring.explicitStart(dragon).onSuccess);
        assertEquals("b_stone_mine", dragon.nodeById("a_top_loot").onSuccess);
        assertEquals("c_food_gate", dragon.nodeById("b_coal_loot").onSuccess);
        assertEquals("d_iron_gate", dragon.nodeById("c_eat_again").onSuccess);
        assertEquals("e_deep_gate", dragon.nodeById("d_iron_sword").onSuccess);
        assertEquals("f_preflight", dragon.nodeById("e_obsidian_loot").onSuccess);

        // Killing the dragon is a milestone, not the last card.
        assertEquals("completegame", dragon.nodeById("f_launch").commandId);
        assertEquals("f_win_loot", dragon.nodeById("f_launch").onSuccess);
        assertEquals("f_win_loot", dragon.nodeById("f_launch").onFailure);
        assertEquals("mission_end", dragon.nodeById("f_win_stash").onSuccess);

        // Both gates that can skip a whole band actually skip forward.
        assertEquals("e_deep_gate", dragon.nodeById("d_iron_gate").onSuccess);
        assertEquals("e_diamond_pick", dragon.nodeById("e_deep_gate").onSuccess);

        assertEquals(2, dragon.nodes.stream().filter(TaskNode::isPulseSourceNode).count(),
                "the field meal and the broom are separate clocks running at separate rates");
        assertTrue(dragon.nodes.stream().anyMatch(TaskNode::isObserverNode));
        assertTrue(dragon.nodes.stream().anyMatch(TaskNode::isButtonNode));
        assertTrue(dragon.nodes.stream().anyMatch(TaskNode::isCounterNode));
        assertTrue(dragon.nodes.stream().anyMatch(TaskNode::isTimerNode));
        assertTrue(dragon.nodes.stream().anyMatch(TaskNode::isSignalRelayNode));
        assertTrue(dragon.nodes.stream().anyMatch(TaskNode::isEndNode));
        assertTrue(TaskSafety.hasMonitor(dragon));

        assertEquals("mission_end", dragon.nodeById("mission_watch").observedNodeId);
        assertEquals("2", dragon.nodeById("closeout_counter").params.get("count"),
                "End lights once and goes dark, so the debrief waits for both edges");
        assertEquals(3, dragon.nodeById("closeout_hub").signalOutputCount);
        assertEquals(2, dragon.nodeById("panic_hub").signalOutputCount);
    }

    /** A rung that arrives unequipped runs the rung below it, so the name has to be a real job. */
    @Test
    void everyRunTaskCardNamesAnotherSeededJob() {
        List<String> names = DefaultTasks.create().stream().map(task -> task.name).toList();
        long handOffs = 0;
        for (TaskGraph task : DefaultTasks.create()) {
            for (TaskNode node : task.nodes) {
                if (!"task".equals(node.commandId)) {
                    continue;
                }
                String called = node.params.get("name");
                assertTrue(names.contains(called),
                        task.name + ": " + node.id + " runs '" + called + "', which is not a job");
                assertTrue(names.indexOf(called) < names.indexOf(task.name),
                        task.name + ": " + node.id + " should hand work down the ladder, not up");
                handOffs++;
                assertNotNull(node.onFailure,
                        task.name + ": " + node.id + " needs a Fail wire, because Run Task is the "
                                + "one card whose target the player can rename out from under it");
            }
        }
        assertEquals(1, handOffs, "the descent is the one job that falls back a rung");
    }

    /**
     * The guard is its own circuit, wired to nothing in the main lane.
     *
     * <p>An Always source holding one Self Preservation card, rather than a While pin ticked on
     * every card that might get hurt. Ten protected cards used to mean ten cables converging on one
     * node, and it only ever covered the cards somebody remembered to tick - a new leg added later
     * was silently unprotected. Always is held on for the whole run.</p>
     */
    @Test
    void theSafetyGuardHangsOffItsOwnAlwaysSourceAndNotOffWhilePins() {
        assertFalse(TaskSafety.hasMonitor(task(LOGS_JOB)),
                "a job that only fells trees in daylight does not need a guard");
        assertFalse(TaskSafety.hasMonitor(task(STONE_JOB)));

        for (String name : List.of(HOMESTEAD_JOB, NIGHTFALL_JOB, PORTAL_JOB, DRAGON_JOB)) {
            TaskGraph task = task(name);
            assertTrue(TaskSafety.hasMonitor(task), name + " works unguarded");

            TaskNode guard = task.nodeById("guard");
            assertEquals("self_preservation", guard.commandId);
            assertEquals(0, guard.repeat,
                    name + ": a companion's repeat box is not a lifetime");
            assertNull(guard.onSuccess, name + ": the guard is not a step in the lane");
            assertNull(guard.onFailure);

            TaskNode clock = task.nodeById("safety_clock");
            assertNotNull(clock, name + " has no Always source for its guard");
            assertTrue(clock.isAlwaysNode());
            assertEquals(Set.of("guard"), clock.alwaysTargets,
                    name + ": the safety clock should hold the guard and nothing else");
            assertTrue(TaskSafety.isMonitorNode(task, guard),
                    name + ": the guard should read as a live-beside-the-work monitor");

            // Nothing in the lane wires to it, which is the whole point.
            assertTrue(task.nodes.stream().noneMatch(node -> "guard".equals(node.onWhile)),
                    name + ": the guard is still tangled into While pins");
            assertTrue(TaskWiring.isMonitorOnly(task, guard),
                    name + ": the guard should stay out of sequential fall-through");
        }
    }

    /** Stay Near is the counter-example: a companion that deliberately covers only some cards. */
    @Test
    void theBoundaryCompanionStaysOnThePinsItIsMeantFor() {
        for (String name : List.of(HOMESTEAD_JOB, NIGHTFALL_JOB)) {
            TaskGraph task = task(name);
            TaskNode fence = task.nodeById("fence");
            assertEquals("stay_near", fence.commandId);
            assertEquals(0, fence.repeat);

            List<String> fenced = task.nodes.stream()
                    .filter(node -> "fence".equals(node.onWhile))
                    .map(node -> node.id)
                    .toList();
            assertFalse(fenced.isEmpty(), name + ": the fence covers nothing");
            assertTrue(fenced.size() < task.nodes.size() - 1,
                    name + ": a boundary that covers everything should have been an Always source");
        }
    }

    /**
     * The set earns its length by being different jobs, not one job with the ore name swapped.
     *
     * <p>The failure this guards against is the one the set already had once: three consecutive
     * middle jobs that were all "provision a pickaxe, find an ore, dig until the quota, smelt".
     * Counting distinct commands per job is a crude measure, but a job that is a re-skin of its
     * neighbour cannot pass it.</p>
     */
    @Test
    void theMiddleJobsAreDifferentWorkAndNotOneJobReskinned() {
        Set<String> homestead = commandsOf(task(HOMESTEAD_JOB));
        Set<String> nightfall = commandsOf(task(NIGHTFALL_JOB));
        Set<String> descent = commandsOf(task(PORTAL_JOB));

        assertTrue(homestead.contains("harvest") && homestead.contains("stay_near"),
                "the homestead should be the job that farms and holds a boundary");
        assertTrue(nightfall.contains("fish") && nightfall.contains("sleep"),
                "the nightfall job should be the one that fishes and goes to bed");
        assertTrue(descent.contains("stripmine") && descent.contains("smelt"),
                "the descent should be the job that digs and smelts");

        assertFalse(homestead.contains("stripmine") || homestead.contains("mine"),
                "the homestead should not have become another mining run");
        assertFalse(nightfall.contains("stripmine") || nightfall.contains("mine"),
                "the nightfall job should not have become another mining run");
        assertFalse(descent.contains("fish") || descent.contains("harvest"),
                "the descent should not have absorbed the surface jobs");

        // Check Time is what made two of these jobs possible, so all three should reach for it.
        for (String name : List.of(HOMESTEAD_JOB, NIGHTFALL_JOB, PORTAL_JOB)) {
            assertTrue(commandsOf(task(name)).contains("check_time"),
                    name + " never asks the clock anything");
        }
    }

    /**
     * A card in a flow lane has to be able to finish, or everything wired after it is decoration.
     *
     * <p>Fish with auto recast on is the trap: it is a perfectly good card that deliberately never
     * reports Success, because "fish until something stops me" is what it is for. Wired into a lane
     * it silently swallows the run - the day loop in job 4 would never turn, and job 6 would fish
     * instead of fighting the dragon. Every other seeded gathering card is bounded by a count.</p>
     */
    @Test
    void noCardInAFlowLaneIsOneThatNeverFinishes() {
        for (TaskGraph task : DefaultTasks.create()) {
            for (TaskNode node : task.nodes) {
                if (!"fish".equals(node.commandId)) {
                    continue;
                }
                assertEquals("false", node.params.get("auto_recast"),
                        task.name + ": " + node.id + " fishes forever, so its Success wire and "
                                + "everything after it can never run");
                assertNotNull(node.onSuccess,
                        task.name + ": " + node.id + " has nowhere to go once it catches something");
            }
        }
    }

    private static Set<String> commandsOf(TaskGraph task) {
        return task.nodes.stream().map(node -> node.commandId).collect(Collectors.toSet());
    }

    @Test
    void restoringDefaultsProducesIndependentCopies() {
        TaskGraph first = DefaultTasks.create().getFirst();
        first.name = "edited";
        first.nodes.clear();

        TaskGraph fresh = DefaultTasks.create().getFirst();
        assertEquals(LOGS_JOB, fresh.name);
        assertFalse(fresh.nodes.isEmpty());
    }

    /** Flow edges that point at a node earlier in the list: the job's deliberate retries. */
    private static List<String> backwardWires(TaskGraph task) {
        List<String> order = task.nodes.stream().map(node -> node.id).toList();
        return task.nodes.stream()
                .flatMap(node -> java.util.stream.Stream.of(node.onSuccess, node.onFailure)
                        .filter(java.util.Objects::nonNull)
                        .filter(target -> order.indexOf(target) <= order.indexOf(node.id))
                        .map(target -> node.id + " -> " + target))
                .toList();
    }

    private static TaskGraph task(String name) {
        return DefaultTasks.create().stream()
                .filter(task -> task.name.equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static void assertTarget(TaskGraph task, TaskNode source, String kind, String target) {
        if (target != null) {
            assertNotNull(task.nodeById(target),
                    task.name + ": " + source.id + " " + kind + " points at missing node " + target);
        }
    }
}
