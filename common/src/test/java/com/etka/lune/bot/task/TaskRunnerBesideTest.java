package com.etka.lune.bot.task;

import com.etka.lune.bot.Beside;
import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.task.TaskGraph;
import com.etka.lune.task.TaskNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A run beside the player: power flows as in any run, and the runner says after each tick whether
 * one of its cards needs the controls.
 */
class TaskRunnerBesideTest {

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void aTaskAsksToRunBesideThePlayerOnlyWhenItsSwitchIsOn() {
        TaskGraph graph = new TaskGraph("Switch");
        assertFalse(new TaskRunner(graph).wantsBesidePlayer());
        graph.beside = true;
        assertTrue(new TaskRunner(graph).wantsBesidePlayer());
        assertFalse(new TaskRunner(graph).besidePlayer(),
                "asking is not running: the engine answers when the run starts");
    }

    @Test
    void aTaskCarriesItsOwnChoiceOfHowToShareTheControls() {
        TaskGraph graph = new TaskGraph("Fireballs");
        graph.beside = true;
        assertTrue(new TaskRunner(graph).besideOptions().isDefault(),
                "a new task starts as every beside task ran: Lune first, with both");
        graph.besideOptions = new com.etka.lune.task.BesideOptions(true, true, false);
        assertEquals(new com.etka.lune.task.BesideOptions(true, true, false),
                new TaskRunner(graph).besideOptions());
    }

    @Test
    void besideThePlayerTheLaneHoldsTheKeysOnlyWhileItsCardWorks() throws ReflectiveOperationException {
        TaskGraph graph = new TaskGraph("Lane");
        TaskNode card = new TaskNode("mine");
        graph.nodes.add(card);
        TaskRunner runner = new TaskRunner(graph);
        runner.runBesidePlayer();
        FakeCard fake = new FakeCard();
        set(runner, "node", card);
        set(runner, "task", fake);

        fake.holds = false;
        assertEquals(TaskStatus.RUNNING, runner.onTick(null));
        assertFalse(runner.holdsControls(), "a card waiting for work in sight leaves the keys alone");

        fake.holds = true;
        assertEquals(TaskStatus.RUNNING, runner.onTick(null));
        assertTrue(runner.holdsControls(), "a card at work has them");

        // Finishing is not working: the card that ends this tick needs nothing more, and whatever
        // is next says for itself once it has ticked.
        fake.result = TaskStatus.SUCCESS;
        runner.onTick(null);
        assertFalse(runner.holdsControls());
    }

    @Test
    void everyCardTheRunBuildsIsToldButNotTheWorkItsCardsDoForThemselves()
            throws ReflectiveOperationException {
        TaskGraph graph = new TaskGraph("Built");
        TaskNode card = new TaskNode("mine");
        card.params.put("targets", "minecraft:iron_ore");
        graph.nodes.add(card);

        TaskRunner beside = new TaskRunner(graph);
        beside.runBesidePlayer();
        assertTrue(besideOf(build(beside, card)).on(), "the card on the canvas waits for sight");

        TaskRunner inPlace = new TaskRunner(graph);
        assertFalse(besideOf(build(inPlace, card)).on(),
                "the same card in a run in the player's place goes and looks");
    }

    @Test
    void cardsThatNeverTouchTheControlsNeverTakeThem() {
        assertFalse(new CountdownTask(1, CountdownTask.Unit.MINUTES).holdsControls());
        assertFalse(new TimerTask(5).holdsControls());
        assertFalse(new NotifyTask("", "").holdsControls());
        assertFalse(ConditionTask.dimension("Overworld").holdsControls());
        assertFalse(new SelectItemTask(net.minecraft.world.item.Items.DIAMOND_PICKAXE,
                        SelectItemTask.Enchanting.ANY, SelectItemTask.Hand.MAIN, 0,
                        SelectItemTask.Preference.MOST_DURABLE).holdsControls(),
                "the hand it chooses would be handed straight back");
        assertTrue(new LootTask(8).holdsControls(), "a card run in the player's place holds them");
    }

    private static Task build(TaskRunner runner, TaskNode node) throws ReflectiveOperationException {
        Method build = TaskRunner.class.getDeclaredMethod("buildTask", TaskNode.class);
        build.setAccessible(true);
        Task built = (Task) build.invoke(runner, node);
        assertNotNull(built);
        return built;
    }

    private static Beside besideOf(Task task) throws ReflectiveOperationException {
        Field field = task.getClass().getDeclaredField("beside");
        field.setAccessible(true);
        return (Beside) field.get(task);
    }

    private static void set(Object target, String name, Object value)
            throws ReflectiveOperationException {
        Field field = TaskRunner.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** A card whose answer to every question is whatever the test last said. */
    private static final class FakeCard implements Task {
        private boolean holds;
        private TaskStatus result = TaskStatus.RUNNING;

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public TaskStatus tick(BotContext ctx) {
            return result;
        }

        @Override
        public TaskStatus onTick(BotContext ctx) {
            return result;
        }

        @Override
        public void stop(BotContext ctx) {
        }

        @Override
        public boolean holdsControls() {
            return holds;
        }
    }
}
