package com.etka.lune.bot.task;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.catalog.SoundCatalog;
import com.etka.lune.util.Alerts;
import com.etka.lune.util.Lang;

/**
 * Makes a noise, and puts a line on screen if it was given one.
 *
 * <p>This is the card for everything the engine has no business deciding on its own. Wire it
 * behind a Check Item Count to be told when the chest is full, behind a Check Player to be told
 * the bot is down to three hearts, or in front of an End to be told the job is done - the
 * condition is a card, the alert is a card, and neither is a reflex hidden in the engine.</p>
 *
 * <p>The sound is any sound the game has, picked from the live registry, which is what turns it
 * from an alarm into an instrument: a growl wired behind a While that watches for mobs, a chime
 * when the quarry finishes, a note block pitch per stage of a long route. That is also why a
 * message is optional and why the toast only appears when there is one - somebody scoring their
 * bot wants the sound, not a banner every few seconds.</p>
 *
 * <p>It fires once and finishes immediately, which is not by itself enough: an Always source with
 * no interval set pulses every tick, and this card behind one would ask for twenty sounds a
 * second. {@link Alerts} is what makes that survivable - the toast replaces the last one rather
 * than stacking, and a sound asked for within half a second of the last is dropped - while
 * leaving a deliberate rhythm from a Pulse card intact.</p>
 */
public final class NotifyTask implements Task {

    private final String message;
    private final String soundId;
    private final StatusText status = new StatusText();

    public NotifyTask(String message, String soundId) {
        this.message = message == null ? "" : message.strip();
        this.soundId = soundId == null ? "" : soundId.strip();
    }

    @Override
    public String name() {
        return message.isEmpty() ? Lang.get("lune.task.notify.name")
                : Lang.get("lune.task.notify.named", message);
    }

    /** The English this is, so the learner's rows survive being translated. */
    @Override
    public String learningId() {
        return Task.learningName("Notify");
    }

    /** Saying something is not a skill; there is nothing here to get better at. */
    @Override
    public boolean automaticSkillLearning() {
        return false;
    }

    @Override
    public StatusText statusLine() {
        return status;
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        // Resolved here rather than held from when the card was built: a resource reload between
        // editing the graph and running it can replace the registry underneath a cached one.
        Alerts.notify(SoundCatalog.sound(soundId), message);
        status.set("lune.status.notify.raised");
        return TaskStatus.SUCCESS;
    }
}
