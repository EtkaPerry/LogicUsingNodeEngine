package com.etka.lune.task.runner;

import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.TaskStatus;

/**
 * Lets an ordinary card stand on a While pin: it always wants control, and it is started the first
 * time it gets it and stopped when it finishes or control goes back.
 */
final class StepGuard<C> implements Guard<C> {

    private final Step<C> delegate;
    private boolean started;

    StepGuard(Step<C> delegate) {
        this.delegate = delegate;
    }

    @Override
    public void start(C ctx) {
        startDelegate(ctx);
    }

    @Override
    public boolean shouldTakeControl(C ctx) {
        return true;
    }

    @Override
    public TaskStatus tick(C ctx) {
        if (!started) {
            startDelegate(ctx);
        }
        TaskStatus result = delegate.tick(ctx);
        if (result != TaskStatus.RUNNING) {
            stopDelegate(ctx);
        }
        return result;
    }

    @Override
    public void onControlReleased(C ctx) {
        stopDelegate(ctx);
    }

    @Override
    public void stop(C ctx) {
        stopDelegate(ctx);
    }

    @Override
    public StatusText statusLine() {
        return delegate.statusLine();
    }

    @Override
    public boolean holdsControls() {
        return started && delegate.holdsControls();
    }

    private void startDelegate(C ctx) {
        if (!started) {
            delegate.start(ctx);
            started = true;
        }
    }

    private void stopDelegate(C ctx) {
        if (started) {
            delegate.stop(ctx);
            started = false;
        }
    }
}
