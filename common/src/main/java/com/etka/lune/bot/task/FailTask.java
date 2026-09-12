package com.etka.lune.bot.task;

import com.etka.lune.util.Lang;
import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;

/**
 * A task that refuses to run and says why - used for commands that aren't implemented yet, and for
 * ones queued with a required parameter left unset.
 * <p>
 * Failing loudly on the first tick is deliberate: a command that quietly does nothing would waste a
 * whole AFK session before anyone noticed.
 */
public final class FailTask implements Task {

    @Override
    public boolean automaticSkillLearning() {
        return false;
    }

    private final String name;
    private String nameKey = "";
    /** Why the card will not run, kept keyed like every other task's status. */
    private final StatusText reason = new StatusText();

    public FailTask(String name, String reasonKey, Object... args) {
        this.name = name;
        this.reason.set(reasonKey, args);
    }

    /** Display identity is separate from the stable English learner identity. */
    public static FailTask forCommand(String commandId, String name, String reasonKey, Object... args) {
        FailTask task = new FailTask(name, reasonKey, args);
        task.nameKey = "lune.command." + commandId + ".name";
        return task;
    }

    /** For a command that exists in the registry but has no behaviour written yet. */
    public static FailTask notImplemented(String commandName) {
        return new FailTask(commandName, "lune.status.fail.not_implemented");
    }

    @Override
    public String name() {
        return Lang.getOr(nameKey, name);
    }

    @Override
    public String learningId() {
        return Task.learningName(name);
    }

    @Override
    public StatusText statusLine() {
        return reason;
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        return TaskStatus.FAILED;
    }
}
