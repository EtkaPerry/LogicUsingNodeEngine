package com.etka.lune.bot.input;

import net.minecraft.client.player.ClientInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Drives the player from {@link BotInput} instead of the keyboard.
 * <p>
 * The player's {@code input} field is ticked once per client tick, and vanilla's
 * {@link net.minecraft.client.player.KeyboardInput} rebuilds {@code keyPresses} from the real key
 * states every time - so simply assigning {@code player.input.keyPresses} from a tick event gets
 * overwritten a frame later. Instead Lune swaps in this subclass, which delegates to the original
 * keyboard input while the bot is idle and takes over completely while it is driving. No mixin
 * required, which is what keeps the movement layer identical across loaders.
 */
public final class BotClientInput extends ClientInput {

    private final ClientInput vanilla;
    private final BooleanSupplier driving;
    private final Supplier<BotInput> botInput;

    public BotClientInput(ClientInput vanilla, BooleanSupplier driving, Supplier<BotInput> botInput) {
        this.vanilla = vanilla;
        this.driving = driving;
        this.botInput = botInput;
    }

    /** The keyboard input this instance replaced, so control can be handed back cleanly. */
    public ClientInput getVanilla() {
        return vanilla;
    }

    @Override
    public void tick() {
        // Always tick the real keyboard: the player may be steering manually, and vanilla state
        // (sprint toggles, held keys) must stay current for when the bot hands control back.
        vanilla.tick();

        if (!driving.getAsBoolean()) {
            this.keyPresses = vanilla.keyPresses;
            this.moveVector = vanilla.getMoveVector();
            return;
        }

        BotInput in = botInput.get();
        this.keyPresses = new Input(in.forward, in.backward, in.left, in.right, in.jump, in.sneak, in.sprint);
        // Mirrors KeyboardInput#tick: impulse per axis, then normalised so diagonals aren't faster.
        float forwardImpulse = impulse(in.forward, in.backward);
        float leftImpulse = impulse(in.left, in.right);
        this.moveVector = new Vec2(leftImpulse, forwardImpulse).normalized();
    }

    private static float impulse(boolean positive, boolean negative) {
        if (positive == negative) {
            return 0.0F;
        }
        return positive ? 1.0F : -1.0F;
    }
}
