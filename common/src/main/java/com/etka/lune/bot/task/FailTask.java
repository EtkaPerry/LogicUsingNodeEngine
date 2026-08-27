package com.etka.lune.bot.task;

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
    private final String reason;

    public FailTask(String name, String reason) {
        this.name = name;
        this.reason = reason;
    }

    /** For a command that exists in the registry but has no behaviour written yet. */
    public static FailTask notImplemented(String commandName) {
        return new FailTask(commandName, "not implemented yet");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String status() {
        return reason;
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        return TaskStatus.FAILED;
    }
}
