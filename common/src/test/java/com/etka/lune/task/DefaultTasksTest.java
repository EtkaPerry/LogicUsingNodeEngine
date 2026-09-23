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

    private static final List<String> DEMOS = List.of(DefaultTasks.CHOP_WOOD,
            DefaultTasks.STONE_TOOLS, DefaultTasks.GO_FISHING, DefaultTasks.DIG_TUNNEL);
    private static final List<String> CHORES = List.of(DefaultTasks.LUMBER_CAMP,
            DefaultTasks.STONE_QUARRY, DefaultTasks.HOMESTEAD, DefaultTasks.SMELTERY,
            DefaultTasks.NIGHT_WATCH);
    private static final List<String> EXPEDITIONS = List.of(DefaultTasks.STUFF_BACK,
            DefaultTasks.LIT_PORTAL, DefaultTasks.ENDER_DRAGON, DefaultTasks.NETHERITE,
            DefaultTasks.FIND_VILLAGE, DefaultTasks.CHERRY_TIMBER);

    /** The chores that run for hours: guarded by an Always card, ended by a Countdown. */
    private static final List<String> SHIFTS = List.of(DefaultTasks.LUMBER_CAMP,
            DefaultTasks.STONE_QUARRY, DefaultTasks.HOMESTEAD);

    /** Jobs that must end on their own, which is why none of them owns a clock or a Button. */
    private static final List<String> FINISHING = List.of(DefaultTasks.CHOP_WOOD,
            DefaultTasks.STONE_TOOLS, DefaultTasks.GO_FISHING, DefaultTasks.DIG_TUNNEL,
            DefaultTasks.SMELTERY, DefaultTasks.NIGHT_WATCH, DefaultTasks.STUFF_BACK,
            DefaultTasks.FIND_VILLAGE, DefaultTasks.CHERRY_TIMBER);

    /** Quoted in the README's table of what ships, so the doc goes stale loudly rather than quietly. */
    private static final List<Integer> EXPECTED_SIZES =
            List.of(12, 16, 10, 10, 27, 25, 34, 17, 18, 11, 56, 79, 46, 13, 16);

    /** The Task tab's rename box; a seeded name longer than this cannot be typed back. */
    private static final int NAME_BOX_LIMIT = 32;

    @Test
    void shipsFifteenJobsFromDemoToExpedition() {
        List<TaskGraph> tasks = DefaultTasks.create();
        List<String> names = tasks.stream().map(task -> task.name).toList();
        assertEquals(15, tasks.size());
        assertEquals(DEMOS, names.subList(0, 4), "the demos come first, in the order to try them");
        assertEquals(CHORES, names.subList(4, 9));
        assertEquals(EXPEDITIONS, names.subList(9, 15));
        assertEquals(EXPECTED_SIZES, tasks.stream().map(task -> task.nodes.size()).toList(),
                "a job changed size - update the README table and this list together");
        for (int i = 0; i < tasks.size(); i++) {
            TaskGraph task = tasks.get(i);
            assertTrue(task.name.startsWith((i + 1) + ". "),
                    task.name + " should carry its place on the shelf");
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

    /**
     * A card with no Fail wire fails the whole run when it fails. Every card in a seeded job is
     * one whose failure the job has an answer for, so every one of them has the wire.
     */
    @Test
    void everyWorkingCardSaysWhereFailureGoes() {
        for (TaskGraph task : DefaultTasks.create()) {
            for (TaskNode node : task.nodes) {
                if (node.isSourceNode() || node.isPulseNode() || isCompanion(task, node)) {
                    continue;
                }
                assertNotNull(node.onFailure,
                        task.name + ": " + node.id + " would fail the whole run when it fails");
                assertNotNull(node.onSuccess,
                        task.name + ": " + node.id + " falls through to whatever card is next in "
                                + "the list, which is not a wire anybody drew");
            }
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
     * the next. The main lane is one row that only ever moves right, however long the job.
     */
    @Test
    void theMainLaneRunsLeftToRightAndNeverWraps() {
        for (TaskGraph task : DefaultTasks.create()) {
            TaskNode start = TaskWiring.explicitStart(task);
            assertNotNull(start, task.name + " has no START to anchor the lane on");
            int laneY = start.editorY;
            assertEquals(DefaultTasks.LANE_Y, laneY, task.name + ": the lane moved");

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
     * How far back a cable reaches. A chore loops from the chest back to the top of its shift, a
     * dozen columns; a lane that had wrapped would reach back across the whole job.
     */
    @Test
    void noCableSweepsBackAcrossTheCanvas() {
        int longestHopBack = 16;
        for (TaskGraph task : DefaultTasks.create()) {
            for (TaskNode node : task.nodes) {
                for (String targetId : List.of(
                        node.onSuccess == null ? node.id : node.onSuccess,
                        node.onFailure == null ? node.id : node.onFailure)) {
                    TaskNode target = task.nodeById(targetId);
                    int columnsBack = (node.editorX - target.editorX) / 208;
                    assertTrue(columnsBack <= longestHopBack,
                            task.name + ": " + node.id + " -> " + targetId + " reaches back "
                                    + columnsBack + " columns");
                }
            }
        }
    }

    /** A detour belongs directly beneath the card that chooses it, not in whatever column is free. */
    @Test
    void everyDetourSitsUnderTheGateThatChoosesIt() {
        record Anchored(String job, String gate, String detour) {}
        for (Anchored pair : List.of(
                new Anchored(DefaultTasks.CHOP_WOOD, "chop_hands", "look_for_trees"),
                new Anchored(DefaultTasks.STONE_TOOLS, "stone_count", "top_up"),
                new Anchored(DefaultTasks.GO_FISHING, "rod", "no_rod"),
                new Anchored(DefaultTasks.LUMBER_CAMP, "chop", "search"),
                new Anchored(DefaultTasks.LUMBER_CAMP, "eat", "hunt"),
                new Anchored(DefaultTasks.LUMBER_CAMP, "stash", "closing"),
                new Anchored(DefaultTasks.HOMESTEAD, "larder", "stock_meat"),
                new Anchored(DefaultTasks.HOMESTEAD, "bedtime", "yard_watch"),
                new Anchored(DefaultTasks.NIGHT_WATCH, "look", "pause"),
                new Anchored(DefaultTasks.STUFF_BACK, "recover", "nothing"),
                new Anchored(DefaultTasks.LIT_PORTAL, "kit_gate", "earn_kit"),
                new Anchored(DefaultTasks.LIT_PORTAL, "diamond_count", "branch_two"),
                new Anchored(DefaultTasks.LIT_PORTAL, "portal_build", "portal_retry"),
                new Anchored(DefaultTasks.ENDER_DRAGON, "e_scan", "e_branch_one"),
                new Anchored(DefaultTasks.NETHERITE, "debris_count", "strip_two"),
                new Anchored(DefaultTasks.FIND_VILLAGE, "on_foot", "none"))) {
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
        // Named so the check below cannot pass by there being nothing to route: every chore loops
        // back to the top of its shift, and those are the cables this exists for.
        for (String name : SHIFTS) {
            assertTrue(task(name).cableAnchors.size() >= 3, name + "'s loop should be routed");
        }
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
            assertNotNull(start, task.name + " has no START");
            assertNotNull(start.onSuccess, task.name + " has an unconnected START");
        }
    }

    /**
     * An Always, Pulse, Observer or Button card keeps a run alive after its last card has
     * finished. A job the player is told will end has none, so it does end - and the finish alert
     * actually sounds.
     */
    @Test
    void theJobsThatSayTheyFinishOwnNothingThatKeepsARunAlive() {
        for (String name : FINISHING) {
            TaskGraph task = task(name);
            for (TaskNode node : task.nodes) {
                assertFalse(node.isClockNode() || node.isObserverNode() || node.isButtonNode(),
                        name + ": " + node.id + " would keep the run alive after its End");
            }
            assertTrue(TaskSafety.hasMonitor(task), name + " works unguarded");
            TaskNode guard = task.nodeById("guard");
            assertEquals("self_preservation", guard.commandId);
            assertEquals(0, guard.repeat, name + ": a companion's repeat box is not a lifetime");
            assertTrue(task.nodes.stream().anyMatch(node -> "guard".equals(node.onWhile)),
                    name + ": the guard should ride the While pins of the risky cards");
        }
    }

    /**
     * The guard is its own circuit on the long jobs, wired to nothing in the main lane.
     *
     * <p>An Always source holding one Self Preservation card, rather than a While pin ticked on
     * every card that might get hurt. Ten protected cards used to mean ten cables converging on
     * one node, and it only ever covered the cards somebody remembered to tick.</p>
     */
    @Test
    void theLongJobsHoldTheirGuardOnItsOwnAlwaysSource() {
        for (String name : List.of(DefaultTasks.LUMBER_CAMP, DefaultTasks.STONE_QUARRY,
                DefaultTasks.HOMESTEAD, DefaultTasks.LIT_PORTAL, DefaultTasks.ENDER_DRAGON,
                DefaultTasks.NETHERITE)) {
            TaskGraph task = task(name);
            assertTrue(TaskSafety.hasMonitor(task), name + " works unguarded");

            TaskNode guard = task.nodeById("guard");
            assertEquals("self_preservation", guard.commandId);
            assertEquals(0, guard.repeat, name + ": a companion's repeat box is not a lifetime");
            assertNull(guard.onSuccess, name + ": the guard is not a step in the lane");
            assertNull(guard.onFailure);

            TaskNode clock = task.nodeById("safety_clock");
            assertNotNull(clock, name + " has no Always source for its guard");
            assertTrue(clock.isAlwaysNode());
            assertEquals(Set.of("guard"), clock.alwaysTargets,
                    name + ": the safety clock should hold the guard and nothing else");
            assertTrue(TaskSafety.isMonitorNode(task, guard));
            assertTrue(task.nodes.stream().noneMatch(node -> "guard".equals(node.onWhile)),
                    name + ": the guard is still tangled into While pins");
            assertTrue(TaskWiring.isMonitorOnly(task, guard),
                    name + ": the guard should stay out of sequential fall-through");
        }
    }

    /**
     * A shift ends two ways, and both reach the same two cards: the Countdown on its own Always
     * card when the hours are up, and a deposit that fails because the chest will take no more.
     */
    @Test
    void everyShiftEndsItselfAndLeavesTheWorld() {
        for (String name : SHIFTS) {
            TaskGraph task = task(name);
            TaskNode clock = task.nodeById("shift_clock");
            assertTrue(clock.isAlwaysNode());
            assertEquals(Set.of("shift"), clock.alwaysTargets);

            TaskNode shift = task.nodeById("shift");
            assertEquals("countdown", shift.commandId);
            assertEquals("Hours", shift.params.get("unit"));
            assertEquals("closing", shift.onSuccess);

            assertEquals("notify", task.nodeById("closing").commandId);
            TaskNode leave = task.nodeById("closing").onSuccess == null ? null
                    : task.nodeById(task.nodeById("closing").onSuccess);
            assertNotNull(leave);
            assertEquals("stop_game", leave.commandId);
            assertEquals("Return to main menu", leave.params.get("ending"),
                    name + ": saving and leaving is the one ending that works on a server too");
        }
        for (String name : List.of(DefaultTasks.LUMBER_CAMP, DefaultTasks.STONE_QUARRY)) {
            TaskNode stash = task(name).nodeById("stash");
            assertEquals("deposit", stash.commandId);
            assertEquals("false", stash.params.get("optional"),
                    name + ": an optional deposit would carry on past a full chest");
            assertEquals("closing", stash.onFailure, name + ": a full chest ends the shift");
        }
        assertEquals("2", task(DefaultTasks.LUMBER_CAMP).nodeById("shift").params.get("amount"));
        assertEquals("3", task(DefaultTasks.STONE_QUARRY).nodeById("shift").params.get("amount"));
    }

    /** The request that shaped the lumber camp: the trees go back where they came from. */
    @Test
    void theLumberCampReplantsWhereItChopped() {
        TaskGraph camp = task(DefaultTasks.LUMBER_CAMP);
        assertEquals("chop", camp.nodeById("chop").commandId);
        assertEquals("loot", camp.nodeById("chop").onSuccess);
        assertEquals("replant", camp.nodeById("loot").onSuccess,
                "the saplings are swept up before they are planted");
        TaskNode replant = camp.nodeById("replant");
        assertEquals("replant", replant.commandId);
        assertEquals("full", replant.onSuccess);
        assertEquals("full", replant.onFailure,
                "no sapling yet is not a reason to stop the shift; the next trip brings some");
        assertEquals("fence", replant.onWhile, "planting stays inside the camp too");

        TaskNode stash = camp.nodeById("stash");
        assertEquals("Logs", stash.params.get("filter"),
                "only logs go in the chest, so the saplings stay in the pack for replanting");

        TaskGraph grove = task(DefaultTasks.CHERRY_TIMBER);
        assertEquals("replant", grove.nodeById("loot").onSuccess);
        assertEquals("replant", grove.nodeById("replant").commandId);
    }

    /** The first rung: by hand, the axe those logs paid for, then again with it. */
    @Test
    void theChopDemoChopsMakesAnAxeAndChopsAgain() {
        TaskGraph chop = task(DefaultTasks.CHOP_WOOD);
        assertEquals("hello", TaskWiring.explicitStart(chop).onSuccess);
        assertEquals("notify", chop.nodeById("hello").commandId);
        assertEquals("6", chop.nodeById("chop_hands").params.get("limit"));
        assertEquals("look_for_trees", chop.nodeById("chop_hands").onFailure);
        assertEquals("chop_hands", chop.nodeById("look_for_trees").onSuccess);
        assertEquals("Axe", chop.nodeById("make_axe").params.get("tool"));
        assertEquals("Wooden", chop.nodeById("make_axe").params.get("material"));
        assertEquals("chop_axe", chop.nodeById("make_axe").onFailure,
                "no axe is not the end of the demo; it chops on without one");
        assertEquals("6", chop.nodeById("chop_axe").params.get("limit"));
        assertEquals(List.of("look_for_trees -> chop_hands"), backwardWires(chop),
                "only the treeless-clearing search should send the first job backwards");
    }

    /** The second rung: the pickaxe exists before the stone card, and twenty is counted. */
    @Test
    void theStoneDemoCountsItsTwentyCobbleBeforeSpendingIt() {
        TaskGraph stone = task(DefaultTasks.STONE_TOOLS);
        assertEquals("Pickaxe", stone.nodeById("wooden_pick").params.get("tool"));
        assertEquals("Wooden", stone.nodeById("wooden_pick").params.get("material"));
        assertEquals("20", stone.nodeById("stone").params.get("limit"));

        TaskNode count = stone.nodeById("stone_count");
        assertEquals("minecraft:cobblestone", count.params.get("item"));
        assertEquals("At least", count.params.get("comparison"));
        assertEquals("20", count.params.get("count"));
        assertEquals("stone_pick", count.onSuccess);
        assertEquals("top_up", count.onFailure);
        for (String id : List.of("stone_pick", "stone_axe", "stone_sword")) {
            assertEquals("Stone", stone.nodeById(id).params.get("material"));
        }
    }

    /**
     * A card in a flow lane has to be able to finish, or everything wired after it is decoration.
     *
     * <p>Fish with auto recast on is the trap: it is a perfectly good card that deliberately never
     * reports Success. The fishing demo counts ten catches with the repeat box instead.</p>
     */
    @Test
    void noCardInAFlowLaneIsOneThatNeverFinishes() {
        for (TaskGraph task : DefaultTasks.create()) {
            for (TaskNode node : task.nodes) {
                if (!"fish".equals(node.commandId)) {
                    continue;
                }
                assertEquals("false", node.params.get("auto_recast"),
                        task.name + ": " + node.id + " fishes forever");
                assertNotNull(node.onSuccess);
            }
        }
        TaskNode fish = task(DefaultTasks.GO_FISHING).nodeById("fish");
        assertEquals(10, fish.repeat, "ten catches is what the demo promises");
        assertEquals("select_item", task(DefaultTasks.GO_FISHING).nodeById("rod").commandId,
                "Fish only reads the hand, so the rod is put there first");
    }

    /** Smelt asked for more than is carried gives up; one at a time empties the pack instead. */
    @Test
    void theSmelteryEmptiesThePackOneItemAtATime() {
        TaskGraph smeltery = task(DefaultTasks.SMELTERY);
        for (String id : List.of("iron", "gold", "copper")) {
            TaskNode smelt = smeltery.nodeById(id);
            assertEquals("smelt", smelt.commandId);
            assertEquals("1", smelt.params.get("count"));
            assertTrue(smelt.repeat > 1, id + " should repeat until the pack is empty");
            assertEquals(smelt.onSuccess, smelt.onFailure,
                    id + ": running out is the end of that metal, not of the job");
        }
    }

    /** One night, from dark to sunrise, and a post to come back to after every fight. */
    @Test
    void theNightWatchWaitsForDarkAndStopsAtSunrise() {
        TaskGraph watch = task(DefaultTasks.NIGHT_WATCH);
        assertEquals("Night", watch.nodeById("dark").params.get("phase"));
        assertEquals("wait_dark", watch.nodeById("dark").onFailure);
        assertEquals("dark", watch.nodeById("wait_dark").onSuccess);
        assertEquals("Day", watch.nodeById("dawn").params.get("phase"));
        assertEquals("stash", watch.nodeById("dawn").onSuccess, "sunrise ends the watch");
        assertEquals("waypoint", watch.nodeById("return").commandId);
        assertEquals("hungry", watch.nodeById("return").onSuccess);
    }

    /** A rung that arrives unequipped runs the rung below it, so the name has to be a real job. */
    @Test
    void everyRunTaskCardNamesAnEarlierSeededJob() {
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
                        task.name + ": " + node.id + " should hand work down the shelf, not up");
                handOffs++;
                assertNotNull(node.onFailure,
                        task.name + ": " + node.id + " needs a Fail wire, because Run Task is the "
                                + "one card whose target the player can rename out from under it");
            }
        }
        assertEquals(1, handOffs, "the portal run is the one job that falls back on another");
        assertEquals(DefaultTasks.STONE_TOOLS,
                task(DefaultTasks.LIT_PORTAL).nodeById("earn_kit").params.get("name"));
    }

    /** Stay Near is a companion that deliberately covers only some cards. */
    @Test
    void theBoundaryStaysOnThePinsItIsMeantFor() {
        for (String name : SHIFTS) {
            TaskGraph task = task(name);
            TaskNode fence = task.nodeById("fence");
            assertEquals("stay_near", fence.commandId);
            assertEquals(0, fence.repeat);
            List<String> fenced = task.nodes.stream()
                    .filter(node -> "fence".equals(node.onWhile))
                    .map(node -> node.id)
                    .toList();
            assertFalse(fenced.isEmpty(), name + ": the fence covers nothing");
            assertTrue(fenced.size() < task.nodes.size() / 3,
                    name + ": a boundary that covers everything should have been an Always source");
        }
        assertNull(task(DefaultTasks.HOMESTEAD).nodeById("drive_off").onWhile,
                "chasing a mob past the fence is the one thing that may leave the property");
    }

    /** The portal run: one descent, with one ore choice feeding every mining card in it. */
    @Test
    void thePortalRunIsOneDescentWithBoundedLoops() {
        TaskGraph portal = task(DefaultTasks.LIT_PORTAL);
        assertEquals("iron_dig", portal.nodeById("iron_quota").onFailure);
        assertEquals("coal_run", portal.nodeById("iron_dig").onFailure);
        assertEquals("iron_smelt", portal.nodeById("iron_roam").onFailure);

        TaskNode scan = portal.nodeById("diamond_scan");
        assertTrue(scan.exposedOutputs.contains("targets"));
        for (String id : List.of("branch_one", "diamond_mine", "diamond_roam", "branch_two",
                "mine_again")) {
            TaskNode target = portal.nodeById(id);
            String input = target.commandId.equals("stripmine") ? "target" : "targets";
            TaskDataLink link = target.inputLinks.get(input);
            assertNotNull(link, id + " does not receive the shared ore choice");
            assertEquals("diamond_scan", link.sourceNodeId);
        }
        assertEquals("Night", portal.nodeById("night_gate").params.get("phase"));
        assertEquals("10", portal.nodeById("obsidian_count").params.get("count"));
        assertEquals("pearl_gate", portal.nodeById("charge_gate").onFailure,
                "no way to light the frame should skip the build, not fail the job");
        assertEquals("Pause the game", portal.nodeById("rest").params.get("ending"));
    }

    /** The whole game: every band hands forward, and everything below the lane runs beside it. */
    @Test
    void theDragonRunIsTheWholeGameUnderEverySupportCircuit() {
        TaskGraph dragon = task(DefaultTasks.ENDER_DRAGON);
        assertEquals("a_chop", TaskWiring.explicitStart(dragon).onSuccess);
        assertEquals("b_stone_mine", dragon.nodeById("a_top_loot").onSuccess);
        assertEquals("c_food_gate", dragon.nodeById("b_coal_loot").onSuccess);
        assertEquals("d_iron_gate", dragon.nodeById("c_eat_again").onSuccess);
        assertEquals("e_deep_gate", dragon.nodeById("d_iron_sword").onSuccess);
        assertEquals("f_preflight", dragon.nodeById("e_obsidian_loot").onSuccess);
        assertEquals("completegame", dragon.nodeById("f_launch").commandId);

        assertEquals(2, dragon.nodes.stream().filter(TaskNode::isPulseSourceNode).count());
        assertTrue(dragon.nodes.stream().anyMatch(TaskNode::isObserverNode));
        assertTrue(dragon.nodes.stream().anyMatch(TaskNode::isButtonNode));
        assertTrue(dragon.nodes.stream().anyMatch(TaskNode::isCounterNode));
        assertTrue(dragon.nodes.stream().anyMatch(TaskNode::isTimerNode));
        assertTrue(dragon.nodes.stream().anyMatch(TaskNode::isSignalRelayNode));
        assertEquals("mission_end", dragon.nodeById("mission_watch").observedNodeId);
        assertEquals("2", dragon.nodeById("closeout_counter").params.get("count"));
    }

    /** Into the Nether and back through the same frame, with every missing piece answered. */
    @Test
    void theNetheriteRunCrossesOverAndComesBack() {
        TaskGraph run = task(DefaultTasks.NETHERITE);
        assertEquals("check_dimension", run.nodeById("where").commandId);
        assertEquals("Nether", run.nodeById("where").params.get("dimension"));
        assertEquals("use_portal", run.nodeById("cross").commandId);
        assertEquals("obsidian_check", run.nodeById("cross").onFailure,
                "no portal in sight: build one from the obsidian carried");
        assertEquals("use_portal", run.nodeById("enter").commandId);
        assertEquals("save_waypoint", run.nodeById("n_mark").commandId);
        assertEquals("waypoint", run.nodeById("to_portal").commandId);
        assertEquals(run.nodeById("n_mark").params.get("name"),
                run.nodeById("to_portal").params.get("name"),
                "the way home is the waypoint saved on arrival");
        assertEquals("use_portal", run.nodeById("exit").commandId);
        assertEquals("minecraft:ancient_debris", run.nodeById("strip_one").params.get("target"));
        assertEquals("15", run.nodeById("strip_one").params.get("y_level"));
        assertEquals("upgrade_netherite", run.nodeById("upgrade").commandId);
        assertEquals("rest", run.nodeById("short").onSuccess,
                "anything missing ends at the pause, so the player can see how far it got");
    }

    /** Without the compass mod the village search still does something a player would. */
    @Test
    void theVillageSearchFallsBackToWalking() {
        TaskGraph village = task(DefaultTasks.FIND_VILLAGE);
        List<String> kinds = List.of("plains", "taiga", "savanna", "desert", "snowy");
        for (int i = 0; i < kinds.size(); i++) {
            TaskNode card = village.nodeById(kinds.get(i));
            assertEquals("find_structure", card.commandId);
            assertEquals("mark", card.onSuccess);
            assertEquals(i + 1 < kinds.size() ? kinds.get(i + 1) : "on_foot", card.onFailure);
        }
        assertEquals("explore", village.nodeById("on_foot").commandId);
    }

    @Test
    void restoringDefaultsProducesIndependentCopies() {
        TaskGraph first = DefaultTasks.create().getFirst();
        first.name = "edited";
        first.nodes.clear();
        first.notes.clear();

        TaskGraph fresh = DefaultTasks.create().getFirst();
        assertEquals(DefaultTasks.CHOP_WOOD, fresh.name);
        assertFalse(fresh.nodes.isEmpty());
        assertFalse(fresh.notes.isEmpty());
    }

    private static Set<String> commandsOf(TaskGraph task) {
        return task.nodes.stream().map(node -> node.commandId).collect(Collectors.toSet());
    }

    @Test
    void theShelfUsesTheNewCards() {
        assertTrue(commandsOf(task(DefaultTasks.LUMBER_CAMP)).contains("replant"));
        assertTrue(commandsOf(task(DefaultTasks.NETHERITE)).contains("use_portal"));
        assertTrue(commandsOf(task(DefaultTasks.STUFF_BACK)).contains("recover_death"));
        assertTrue(commandsOf(task(DefaultTasks.CHERRY_TIMBER)).contains("find_biome"));
    }

    /** A While companion is not a step: it has neither wire, and that is correct. */
    private static boolean isCompanion(TaskGraph task, TaskNode node) {
        return task.nodes.stream().anyMatch(other -> node.id.equals(other.onWhile)
                || other.alwaysTargets.contains(node.id));
    }

    /** Flow edges that point at a node earlier in the list: the job's deliberate retries. */
    private static List<String> backwardWires(TaskGraph task) {
        List<String> order = task.nodes.stream().map(node -> node.id).toList();
        return task.nodes.stream()
                .flatMap(node -> java.util.stream.Stream.of(node.onSuccess, node.onFailure)
                        .filter(java.util.Objects::nonNull)
                        .filter(target -> order.indexOf(target) <= order.indexOf(node.id))
                        .map(target -> node.id + " -> " + target))
                .distinct()
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
