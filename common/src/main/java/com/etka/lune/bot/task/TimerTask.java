package com.etka.lune.bot.task;

import com.etka.lune.util.Lang;
import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskProgress;
import com.etka.lune.bot.TaskStatus;

/** Waits for a configured number of real game ticks, then emits Success. */
public final class TimerTask implements Task {

    @Override
    public boolean automaticSkillLearning() {
        return false;
    }

    private final int seconds;
    private int remainingTicks;

    public TimerTask(int seconds) {
        this.seconds = Math.max(0, seconds);
    }

    @Override
    public String name() {
        return Lang.get("lune.gui.tasks.timer");
    }

    /** The English this used to be, so the learner's rows survive being translated. */
    @Override
    public String learningId() {
        return Task.learningName("Timer");
    }

    @Override
    public void onStart(BotContext ctx) {
        remainingTicks = seconds * 20;
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        if (remainingTicks <= 0) {
            return TaskStatus.SUCCESS;
        }
        remainingTicks--;
        return remainingTicks <= 0 ? TaskStatus.SUCCESS : TaskStatus.RUNNING;
    }

    @Override
    public String status() {
        return remainingTicks <= 0
                ? "finished"
                : "waiting " + Math.ceilDiv(remainingTicks, 20) + "s";
    }

    @Override
    public TaskProgress progress() {
        int total = Math.max(1, seconds * 20);
        return new TaskProgress(total - Math.min(total, remainingTicks), total, Lang.get("lune.unit.ticks"));
    }
}
