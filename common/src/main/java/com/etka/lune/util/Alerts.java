package com.etka.lune.util;

import com.etka.lune.compat.Toasts;
import com.etka.lune.config.BotConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

import java.util.List;
import java.util.function.Supplier;

/**
 * How Lune gets the player's attention when they are not looking at the game.
 *
 * <p>The point of a bot is that nobody is watching it, and until this existed the only way to
 * learn that a run had ended - or that the bot had drowned twenty minutes ago - was to come back
 * and read the panel. Chat was already written for a task finishing, which is no use at all to
 * somebody in another window.</p>
 *
 * <p>An alert is a sound and a toast, both of which survive not having the game focused. The
 * Config tab owns all of it: each channel switches off on its own, the sound has a level, and the
 * three automatic alerts - finished, failed, died - each pick their own {@link Tone}, because to
 * somebody in another room the sound is the entire message and those three are not the same news.
 * The toast goes out under one id on purpose: a new alert replaces the one before it, so an Always
 * circuit that raises one every few seconds leaves a single line on screen rather than a wall of
 * them.</p>
 *
 * <p>The sound needs the same protection and cannot get it the same way. An Always source pulses
 * <em>every tick</em> unless it is given an interval, so {@code Always -> Notify} is twenty alerts
 * a second: twenty overlapping bells is not a louder alert, it is a drone nobody can locate. So
 * anything raised within {@link #QUIET_MS} of the last one keeps its toast - the newest reading is
 * the useful one - and goes without its sound and its chat line, which are the parts that pile
 * up.</p>
 *
 * <p>This says things; it never decides them. Nothing here reads the text back, and the tone is
 * chosen by the caller rather than guessed from the words, so a translated alert sounds the same
 * as an English one.</p>
 */
public final class Alerts {

    /**
     * Every Lune toast carries this, so {@code addOrUpdate} replaces the last one. Seven seconds
     * rather than vanilla's five: an alert is worth reading after walking back to the desk.
     */
    private static final SystemToast.SystemToastId TOAST_ID = new SystemToast.SystemToastId(7000L);

    /**
     * The loudest an alert can be asked to play.
     *
     * <p>A hundred per cent, and not because a round number looked tidy: vanilla's
     * {@code SoundEngine.calculateVolume} clamps the instance volume to {@code [0, 1]} before it
     * multiplies by the player's own sliders, so anything above this would be a setting that reads
     * as louder and is not. Louder than this is the Master slider's job, not Lune's.</p>
     */
    public static final int MAX_VOLUME_PERCENT = 100;

    /**
     * How close together two alerts may sound.
     *
     * <p>Half a second, which is a floor rather than a pace. It exists for one mistake: an Always
     * source with no interval set pulses every tick, so a Notify card behind one asks for twenty
     * sounds a second, and twenty overlapping copies of anything is a drone nobody can locate.</p>
     *
     * <p>It is deliberately not long enough to interfere with a rhythm somebody meant. A card
     * driven by a Pulse every second - a growl while the bot fights, which is what this became
     * useful for - fires every time it is asked. A gap wide enough to swallow half of those would
     * be a card that quietly does not do what its graph says, which is worse than a loud
     * mistake.</p>
     */
    static final long QUIET_MS = 500L;

    /** When the last alert was allowed to make a noise. Wall clock, because ticks stop when paused. */
    private static long lastNoiseAt = Long.MIN_VALUE;

    private Alerts() {}

    /**
     * Whether an alert raised at {@code now} may sound, given the last one that did.
     *
     * <p>Pulled out so the rule can be tested without a client, a sound engine or a clock - which
     * is what caught the plain {@code now - lastNoise >= QUIET_MS}. That overflows against the
     * sentinel this starts at, so the first alert of every session read as too soon and the one
     * event most worth hearing was the one that never made a sound.</p>
     *
     * <p>A negative gap is therefore allowed through rather than refused: it means either the
     * sentinel or a clock that has jumped backwards, and neither is a reason to go quiet for the
     * rest of the session.</p>
     */
    static boolean mayInterrupt(long lastNoise, long now) {
        long since = now - lastNoise;
        return since < 0 || since >= QUIET_MS;
    }

    /**
     * What an alert sounds like.
     *
     * <p>The names are what a Notify card writes into a saved task and what the Config tab keeps
     * for the automatic alerts, so they are English and they do not change. {@link #NONE} is how
     * either of them asks for an alert without a sound.</p>
     *
     * <p>The sound is held as a supplier rather than as the {@code SoundEvent} itself. Naming one
     * directly would load {@code SoundEvents} - and with it the registries - the moment anything
     * touched this enum, and what touches it first is {@link com.etka.lune.config.BotConfig}
     * naming its default. A config that cannot be read without a bootstrapped game is a config
     * that breaks every headless test that goes near it.</p>
     */
    public enum Tone {
        BELL("Bell", () -> SoundEvents.BELL_BLOCK, 1.0F),
        CHIME("Chime", () -> SoundEvents.AMETHYST_BLOCK_CHIME, 1.0F),
        FANFARE("Fanfare", () -> SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0F),
        /** For trouble: an anvil landing is the one alert nobody sleeps through. */
        ALARM("Alarm", () -> SoundEvents.ANVIL_LAND, 0.8F),
        NONE("None", null, 1.0F);

        private final String label;
        private final Supplier<SoundEvent> sound;
        private final float pitch;

        Tone(String label, Supplier<SoundEvent> sound, float pitch) {
            this.label = label;
            this.sound = sound;
            this.pitch = pitch;
        }

        /** The identifier written into a saved task; never translated, never a rendered name. */
        public String label() {
            return label;
        }

        /** The sound itself, resolved now rather than at class-load. Null for {@link #NONE}. */
        SoundEvent sound() {
            return sound == null ? null : sound.get();
        }

        public static List<String> labels() {
            return List.of(BELL.label, CHIME.label, FANFARE.label, ALARM.label, NONE.label);
        }

        /** Parses what a saved task holds, falling back rather than failing a whole run over it. */
        public static Tone fromLabel(String label) {
            if (label != null) {
                for (Tone tone : values()) {
                    if (tone.label.equalsIgnoreCase(label.strip())) {
                        return tone;
                    }
                }
            }
            return BELL;
        }
    }

    /**
     * Raises an alert: a sound, a toast, and a chat line, each one only if the player left it on.
     *
     * @param tone    what it sounds like; {@link Tone#NONE} is silent
     * @param title   the toast's heading, already in the player's language
     * @param message the line below it, or blank for a heading on its own
     */
    public static void raise(Tone tone, String title, String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        BotConfig config = BotConfig.get();
        // The toast first, and unconditionally: it replaces the last one rather than queueing
        // behind it, so a fast loop costs one line on screen and that line is always current.
        if (config.alertToast) {
            Toasts.show(mc, TOAST_ID, Component.literal(title),
                    message == null || message.isBlank() ? null : Component.literal(message));
        }
        long now = System.currentTimeMillis();
        if (!mayInterrupt(lastNoiseAt, now)) {
            return;
        }
        lastNoiseAt = now;
        float volume = volume(config.alertVolume);
        SoundEvent sound = tone == null ? null : tone.sound();
        if (config.alertSound && sound != null && volume > 0.0F) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(sound, tone.pitch, volume));
        }
        if (config.alertChat) {
            chat(mc, message == null || message.isBlank() ? title : title + " - " + message);
        }
    }

    /**
     * A card asking for a sound, with a line on screen only if it was given one.
     *
     * <p>The message is what makes it an alert. A Notify card with something to say puts it up as
     * a toast; one with only a sound chosen is somebody scoring their bot - a growl while it
     * fights, a chime when the chest fills - and a toast every time would be noise on screen to go
     * with the noise in the speakers.</p>
     *
     * @param sound   already resolved by the caller, so this stays out of the registry's business;
     *                null plays nothing, which is what a removed mod's sound comes back as
     * @param message the line to show, or blank for the sound on its own
     */
    public static void notify(SoundEvent sound, String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        BotConfig config = BotConfig.get();
        String line = message == null ? "" : message.strip();
        if (config.alertToast && !line.isEmpty()) {
            Toasts.show(mc, TOAST_ID, Component.literal(Lang.get("lune.alert.notify")),
                    Component.literal(line));
        }
        long now = System.currentTimeMillis();
        if (!mayInterrupt(lastNoiseAt, now)) {
            return;
        }
        lastNoiseAt = now;
        float volume = volume(config.alertVolume);
        if (config.alertSound && sound != null && volume > 0.0F) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(sound, 1.0F, volume));
        }
        if (config.alertChat && !line.isEmpty()) {
            chat(mc, line);
        }
    }

    /**
     * Plays a sound somebody just clicked in a picker.
     *
     * <p>Ignores the quiet window and the alert switches on purpose: this is not an alert, it is
     * the picker answering "what does that one sound like?", and somebody auditioning sounds
     * clicks faster than any alert fires. It keeps the configured volume so what you hear is what
     * the card will do - except at zero, where it plays at full, because a preview button that
     * cannot be heard reads as a broken one rather than as a muted alert.</p>
     */
    public static void preview(SoundEvent sound) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || sound == null) {
            return;
        }
        float volume = volume(BotConfig.get().alertVolume);
        mc.getSoundManager().play(SimpleSoundInstance.forUI(sound, 1.0F, volume > 0.0F ? volume : 1.0F));
    }

    /**
     * The configured level as a volume the sound engine will accept.
     *
     * <p>Separate from the on/off switch on purpose. The switch is what somebody reaches for to
     * stop being alerted at all; the level is for the far commoner case of an alert that is right
     * but too loud at two in the morning.</p>
     */
    static float volume(int percent) {
        return Math.clamp(percent, 0, MAX_VOLUME_PERCENT) / (float) MAX_VOLUME_PERCENT;
    }

    /**
     * The alert for a run that has ended on its own - the one people actually wait for.
     *
     * <p>Finishing and failing have tones of their own, and that is the point of the setting: from
     * another window the sound is the whole message, and "it is done" and "it gave up" must not
     * arrive sounding the same.</p>
     *
     * <p>Chat already carries the detail, so this stays with the headline and lets the existing
     * line say the rest.</p>
     */
    public static void runFinished(String taskName, boolean failed) {
        BotConfig config = BotConfig.get();
        if (!config.alertOnTaskEnd) {
            return;
        }
        raise(Tone.fromLabel(failed ? config.alertToneFailed : config.alertToneFinished),
                Lang.get(failed ? "lune.alert.task_failed" : "lune.alert.task_finished"),
                taskName == null ? "" : taskName);
    }

    /** The alert for the bot dying, which is the one worth interrupting somebody for. */
    public static void died(String where) {
        BotConfig config = BotConfig.get();
        if (!config.alertOnDeath) {
            return;
        }
        raise(Tone.fromLabel(config.alertToneDeath), Lang.get("lune.alert.died"),
                where == null ? "" : where);
    }

    private static void chat(Minecraft mc, String message) {
        LocalPlayer player = mc.player;
        if (player != null) {
            player.sendSystemMessage(Component.literal(Lang.get("lune.gui.bot_context.lune", message)));
        }
    }
}
