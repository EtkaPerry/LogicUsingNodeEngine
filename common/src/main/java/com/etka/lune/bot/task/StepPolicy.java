package com.etka.lune.bot.task;

import net.minecraft.core.Direction;

import java.util.List;

/**
 * The geometry behind stepping without turning: which way "right" is, and which keys move you that
 * way while you keep looking where you are looking.
 * <p>
 * Pure, and separate from {@link StepTask}, because it is the part that is easy to get backwards.
 * Movement keys are read in the player's own frame and rotated by the yaw before they become world
 * motion, so walking a fixed world direction while the head stays put is that rotation run
 * backwards - and getting the sign wrong gives a bot that strafes confidently into the wrong wall.
 */
public final class StepPolicy {

    /** Which way to go, read from where the bot is already facing. */
    public enum Side {
        RIGHT("Right"),
        LEFT("Left"),
        FORWARD("Forward"),
        BACK("Back");

        private final String label;

        Side(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public static List<String> labels() {
            return java.util.Arrays.stream(values()).map(Side::label).toList();
        }

        public static Side fromLabel(String label) {
            for (Side side : values()) {
                if (side.label.equalsIgnoreCase(label)) {
                    return side;
                }
            }
            return RIGHT;
        }
    }

    /** The keys held this tick. All four can be false, which is how the bot coasts to a stop. */
    public record Impulse(boolean forward, boolean backward, boolean left, boolean right) {

        public static final Impulse NONE = new Impulse(false, false, false, false);

        public boolean any() {
            return forward || backward || left || right;
        }
    }

    private StepPolicy() {}

    /**
     * The world direction a side means for a bot facing {@code facing}.
     * <p>
     * Clockwise is the player's right: facing south, clockwise is west, and west is where your right
     * hand points when you look south.
     */
    public static Direction axis(Direction facing, Side side) {
        Direction horizontal = facing.getAxis().isHorizontal() ? facing : Direction.NORTH;
        return switch (side) {
            case RIGHT -> horizontal.getClockWise();
            case LEFT -> horizontal.getCounterClockWise();
            case FORWARD -> horizontal;
            case BACK -> horizontal.getOpposite();
        };
    }

    /**
     * The keys that carry the bot along a world offset without changing where it looks.
     * <p>
     * Vanilla turns the pressed keys into motion with a rotation by the yaw: a forward press of
     * {@code f} and a left press of {@code s} become
     * {@code (s·cos y − f·sin y, f·cos y + s·sin y)}. This is that rotation inverted, so a wanted
     * world offset comes back as the presses that produce it. Two keys at once is normal and is
     * what lets a bot facing north-east strafe due east.
     *
     * @param dx        wanted world movement along X
     * @param dz        wanted world movement along Z
     * @param yawDegrees where the bot is looking, and stays looking
     * @param deadZone  offsets smaller than this are treated as arrived on that axis, which is what
     *                  stops the bot juddering between two keys either side of the target
     */
    public static Impulse toward(double dx, double dz, float yawDegrees, double deadZone) {
        double yaw = Math.toRadians(yawDegrees);
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        double forward = -dx * sin + dz * cos;
        double left = dx * cos + dz * sin;
        return new Impulse(
                forward > deadZone,
                forward < -deadZone,
                left > deadZone,
                left < -deadZone);
    }
}
