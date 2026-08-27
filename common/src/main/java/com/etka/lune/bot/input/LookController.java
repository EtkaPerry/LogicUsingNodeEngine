package com.etka.lune.bot.input;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Turns the player toward a target. Rotation is rate-limited rather than snapped, because an
 * instant 180° flip both looks wrong and is the most obvious tell to anti-cheat on servers.
 * <p>
 * The turn is also eased rather than run at a flat rate. A fixed degrees-per-tick clamp starts and
 * stops the head dead, which reads as mechanical even at a slow speed; a real player's head
 * accelerates, coasts, and slows into place. So the controller carries an angular velocity per axis
 * and limits how fast that velocity may change.
 */
public final class LookController {

    /** Max degrees turned per tick once up to speed. ~15 keeps a full turn under a second. */
    private float maxTurnPerTick = 15.0F;
    /**
     * How quickly the turn may speed up or slow down, in degrees per tick per tick. This is what
     * rounds off both ends of the movement; higher values approach the old flat-rate feel.
     */
    private float acceleration = 2.0F;

    /**
     * Aim error small enough to ignore, in degrees.
     *
     * <p>The easing above smooths a turn but cannot stop one from starting, and the aim target is
     * recomputed every tick from a walking player's position - so a route that is already being
     * followed correctly still produces a fraction of a degree of correction twenty times a second.
     * Individually invisible, together they are the constant shimmer that makes the camera look
     * wrong. A person holds still once they are looking at the thing; nothing needs sub-degree
     * accuracy, since every interaction test here allows several degrees of slack.
     */
    private static final float AIM_DEADZONE = 1.0F;

    /** How much faster an urgent turn is allowed to be than a relaxed one. */
    private static final float URGENT_TURN_MULTIPLIER = 4.0F;

    /**
     * Horizontal distance, in blocks, below which a target has no meaningful yaw.
     *
     * <p>Something directly underfoot is reached by pitch alone: every yaw looks at it equally.
     * Deriving one anyway from {@code atan2} of two near-zero offsets produces a different answer
     * every tick as the player drifts a hundredth of a block, so the head chases a target that
     * keeps moving and {@link #isLookingAt} never returns true. That is a permanent stall, not a
     * slow turn - a bucket clutch stood over its own water for forty-seven ticks "looking at" it.
     * Inside this radius the current yaw is kept and only the pitch is aimed.
     */
    private static final double YAW_MEANINGLESS_WITHIN = 0.15;

    private float yawVelocity;
    private float pitchVelocity;
    /** Counts down to zero, so urgency has to be renewed each tick that needs it. */
    private int urgentTicks;

    public void setMaxTurnPerTick(float degrees) {
        this.maxTurnPerTick = Math.max(1.0F, degrees);
    }

    /** Sets the ease-in/ease-out rate. Lower is smoother and lazier, higher is snappier. */
    public void setAcceleration(float degreesPerTickSquared) {
        this.acceleration = Math.max(0.25F, degreesPerTickSquared);
    }

    /**
     * Drops the carried momentum. Call when the bot stops looking at something for a while, so the
     * next turn starts from rest instead of lurching off with stale velocity.
     */
    public void relax() {
        yawVelocity = 0.0F;
        pitchVelocity = 0.0F;
    }

    /**
     * Turns at whatever speed the situation deserves for one tick.
     *
     * <p>Human smoothness is the right default and the wrong answer in an emergency. A creeper
     * closing, an arrow already in flight, lava underfoot - a player snaps round for those, and
     * easing through a leisurely arc while something is killing you is neither human nor useful.
     * Everything else keeps the eased turn, because that is what makes the camera look like a
     * person rather than a turret.
     *
     * <p>Set per tick and cleared automatically, so an urgent turn cannot leak into ordinary
     * travel and quietly make the whole run look mechanical again.
     */
    public void urgent() {
        urgentTicks = 1;
    }

    private float currentMaxTurn() {
        return urgentTicks > 0 ? maxTurnPerTick * URGENT_TURN_MULTIPLIER : maxTurnPerTick;
    }

    private float currentAcceleration() {
        return urgentTicks > 0 ? acceleration * URGENT_TURN_MULTIPLIER : acceleration;
    }

    /** Aims at the centre of a block. */
    public void lookAt(LocalPlayer player, BlockPos pos) {
        lookAt(player, Vec3.atCenterOf(pos));
    }

    /** Aims at an exact point, stepping at most {@link #maxTurnPerTick} degrees this tick. */
    public void lookAt(LocalPlayer player, Vec3 target) {
        Vec3 eye = player.getEyePosition();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;

        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float wantYaw = yawToward(player, dx, dz, horizontal);
        float wantPitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));

        player.setYRot(approachYaw(player.getYRot(), wantYaw));
        player.setXRot(clampPitch(approachPitch(player.getXRot(), wantPitch)));
        expireUrgency();
    }

    /**
     * Turns toward an absolute camera rotation without inventing a world target. This is useful
     * for a deliberate head scan: changing the view must not replace or otherwise mutate the
     * task's actual block target.
     */
    public void lookAtRotation(LocalPlayer player, float yaw, float pitch) {
        player.setYRot(approachYaw(player.getYRot(), yaw));
        player.setXRot(clampPitch(approachPitch(player.getXRot(), clampPitch(pitch))));
        expireUrgency();
    }

    /** True once the camera has settled at an absolute rotation. */
    public boolean isLookingAtRotation(LocalPlayer player, float yaw, float pitch,
                                       float toleranceDegrees) {
        return Math.abs(wrapDegrees(yaw - player.getYRot())) <= toleranceDegrees
                && Math.abs(clampPitch(pitch) - player.getXRot()) <= toleranceDegrees;
    }

    /** True once the player is aimed close enough at {@code target} to interact with it. */
    public boolean isLookingAt(LocalPlayer player, Vec3 target, float toleranceDegrees) {
        Vec3 eye = player.getEyePosition();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float wantYaw = yawToward(player, dx, dz, horizontal);
        float wantPitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        return Math.abs(wrapDegrees(wantYaw - player.getYRot())) <= toleranceDegrees
                && Math.abs(wantPitch - player.getXRot()) <= toleranceDegrees;
    }

    /** The yaw that faces a target, or the one already held when the target is straight up or down. */
    private static float yawToward(LocalPlayer player, double dx, double dz, double horizontal) {
        return horizontal < YAW_MEANINGLESS_WITHIN
                ? player.getYRot()
                : (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
    }

    private void expireUrgency() {
        if (urgentTicks > 0) {
            urgentTicks--;
        }
    }

    private float approachYaw(float current, float want) {
        float delta = wrapDegrees(want - current);
        if (Math.abs(delta) < AIM_DEADZONE) {
            yawVelocity = 0.0F;
            return current;
        }
        yawVelocity = nextVelocity(yawVelocity, delta);
        return current + step(yawVelocity, delta);
    }

    private float approachPitch(float current, float want) {
        float delta = want - current;
        if (Math.abs(delta) < AIM_DEADZONE) {
            pitchVelocity = 0.0F;
            return current;
        }
        pitchVelocity = nextVelocity(pitchVelocity, delta);
        return current + step(pitchVelocity, delta);
    }

    /**
     * Eases the turn by treating it as a body with momentum rather than a servo.
     * <p>
     * The target speed comes from the braking curve {@code v = sqrt(2 * a * d)} - the fastest the
     * head could be moving and still come to rest exactly on target under this deceleration. That
     * gives the ease-out for free. Limiting how far the velocity may move toward that target in one
     * tick gives the ease-in, and stops the head snapping to full speed the instant a new target
     * appears.
     */
    private float nextVelocity(float velocity, float delta) {
        float accel = currentAcceleration();
        float braking = (float) Math.sqrt(2.0 * accel * Math.abs(delta));
        float desired = Math.signum(delta) * Math.min(currentMaxTurn(), braking);
        return Math.clamp(desired, velocity - accel, velocity + accel);
    }

    /** Never step past the target - overshoot would have to be corrected and reads as a twitch. */
    private static float step(float velocity, float delta) {
        float limit = Math.abs(delta);
        return Math.clamp(velocity, -limit, limit);
    }

    private static float clampPitch(float pitch) {
        return Math.clamp(pitch, -90.0F, 90.0F);
    }

    /** Normalises an angle difference into [-180, 180) so turns take the short way round. */
    private static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0F;
        if (wrapped >= 180.0F) {
            wrapped -= 360.0F;
        }
        if (wrapped < -180.0F) {
            wrapped += 360.0F;
        }
        return wrapped;
    }
}
