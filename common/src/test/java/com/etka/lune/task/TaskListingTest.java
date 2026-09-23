package com.etka.lune.task;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which tasks a player is offered when a mod is missing.
 *
 * <p>Whether a mod is installed is the loader's answer, and there is no loader here, so the answer
 * is passed in - the same four cards {@code ModCardGateTest} pins as the ones gated on a mod.</p>
 */
class TaskListingTest {

    private static final String EXPLORERS = "find_structure";
    private static final String NATURES = "find_biome";
    private static final Set<String> MOD_CARDS =
            Set.of(NATURES, EXPLORERS, "backpack_deposit", "backpack_take");

    /** A game with none of the mods installed. */
    private static final Predicate<String> VANILLA = id -> !MOD_CARDS.contains(id);

    @Test
    void aStarterJobBuiltOnAMissingModsCardIsNotListed() {
        TaskGraph job = task("find_village", EXPLORERS);
        assertFalse(job.isListed(VANILLA));
        assertTrue(job.isListed(id -> true), "with the mod installed it is listed again");
    }

    /** Hiding somebody's own work because a mod went missing would look exactly like losing it. */
    @Test
    void thePlayersOwnTaskIsListedWhateverItHolds() {
        TaskGraph mine = task(null, EXPLORERS);
        assertTrue(mine.isListed(VANILLA));
    }

    @Test
    void aStarterJobOfOrdinaryCardsIsAlwaysListed() {
        assertTrue(task("chop_wood", "chop").isListed(VANILLA));
    }

    /**
     * On the shelf it is exactly the two compass jobs, and they are the last two - so a pack without
     * the compass mods sees one to thirteen with no gap in the numbers.
     */
    @Test
    void onTheShelfOnlyTheCompassJobsWaitOnAMod() {
        List<String> hidden = DefaultTasks.create().stream()
                .filter(task -> !task.isListed(VANILLA))
                .map(task -> task.name)
                .toList();
        assertEquals(List.of(DefaultTasks.FIND_VILLAGE, DefaultTasks.CHERRY_TIMBER), hidden);

        List<TaskGraph> shelf = DefaultTasks.create();
        List<String> lastTwo = shelf.subList(shelf.size() - 2, shelf.size()).stream()
                .map(task -> task.name).toList();
        assertEquals(hidden, lastTwo);
    }

    /** Each compass hides only its own job. */
    @Test
    void eachCompassModAnswersForItsOwnJob() {
        Predicate<String> onlyNatures = id -> !id.equals(EXPLORERS) && !id.startsWith("backpack");
        Predicate<String> onlyExplorers = id -> !id.equals(NATURES) && !id.startsWith("backpack");
        assertEquals(List.of(DefaultTasks.FIND_VILLAGE), hiddenNames(onlyNatures));
        assertEquals(List.of(DefaultTasks.CHERRY_TIMBER), hiddenNames(onlyExplorers));
    }

    private static List<String> hiddenNames(Predicate<String> offered) {
        return DefaultTasks.create().stream()
                .filter(task -> !task.isListed(offered))
                .map(task -> task.name)
                .toList();
    }

    private static TaskGraph task(String seededId, String card) {
        TaskGraph task = new TaskGraph("Listing");
        task.seededId = seededId;
        TaskWiring.addExplicitStart(task);
        TaskNode node = new TaskNode(card);
        task.nodes.add(node);
        return task;
    }
}
