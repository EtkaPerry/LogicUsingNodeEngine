package com.etka.lune.client.command;

import com.etka.lune.bot.util.Cheats;
import com.etka.lune.bot.util.OmniscientAccess;
import com.etka.lune.util.Lang;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/**
 * {@code /lune} - the cheat switches, and nothing else.
 *
 * <p>Lune's settings are on the panel behind <b>J</b>; a chat command would be a second place to
 * change the same thing. What is here instead is the handful of switches that must <em>not</em> be
 * on that panel: the omniscient modes, which are cheats rather than preferences. A command is the
 * right shape for them because it is typed deliberately, it lasts one session, it leaves nothing on
 * disk to be hand-edited, and on a server the reply names the permission the player does not
 * have.</p>
 *
 * <p>The tree is generic in its source type because the three loaders disagree about what a client
 * command source is - Fabric has {@code FabricClientCommandSource}, NeoForge and Forge use
 * vanilla's {@code CommandSourceStack}. Brigadier is generic already, so the loader modules pass in
 * a {@link Reply} that knows how to talk to their own source and the tree itself stays shared.</p>
 */
public final class LuneChatCommand {

    /** How one loader's command source says something back to the player. */
    public interface Reply<S> {
        /** An answer the player asked for. */
        void success(S source, String message);

        /** A refusal. Rendered as an error by every loader that implements this. */
        void failure(S source, String message);
    }

    /**
     * The reply for loaders whose client source is vanilla's own {@code CommandSourceStack} -
     * NeoForge and Forge both are, so the implementation lives here rather than twice over there.
     * Fabric has its own source type and brings its own.
     */
    public static final Reply<CommandSourceStack> VANILLA = new Reply<>() {
        @Override
        public void success(CommandSourceStack source, String message) {
            source.sendSuccess(() -> Component.literal(message), false);
        }

        @Override
        public void failure(CommandSourceStack source, String message) {
            source.sendFailure(Component.literal(message));
        }
    };

    private LuneChatCommand() {}

    /** The {@code /lune} tree, ready to hand to a client command dispatcher. */
    public static <S> LiteralArgumentBuilder<S> build(Reply<S> reply) {
        LiteralArgumentBuilder<S> omniscient = LiteralArgumentBuilder.<S>literal("omniscient")
                .executes(context -> status(context.getSource(), reply));
        omniscient.then(LiteralArgumentBuilder.<S>literal("off")
                .executes(context -> allOff(context.getSource(), reply)));
        for (Cheats.Mode mode : Cheats.Mode.values()) {
            omniscient.then(LiteralArgumentBuilder.<S>literal(mode.id())
                    .executes(context -> report(context.getSource(), reply, mode))
                    .then(LiteralArgumentBuilder.<S>literal("on")
                            .executes(context -> set(context.getSource(), reply, mode, true)))
                    .then(LiteralArgumentBuilder.<S>literal("off")
                            .executes(context -> set(context.getSource(), reply, mode, false))));
        }

        return LiteralArgumentBuilder.<S>literal("lune")
                .executes(context -> usage(context.getSource(), reply))
                .then(omniscient);
    }

    private static <S> int usage(S source, Reply<S> reply) {
        reply.success(source, line(Lang.get("lune.cheat.usage")));
        return Command.SINGLE_SUCCESS;
    }

    /** Every mode and where it stands, plus why none of it is available when that is the answer. */
    private static <S> int status(S source, Reply<S> reply) {
        boolean allowed = allowed();
        reply.success(source, line(Lang.get("lune.cheat.status")));
        for (Cheats.Mode mode : Cheats.Mode.values()) {
            reply.success(source, Lang.get("lune.cheat.status.line", mode.label(),
                    Lang.get(Cheats.isActive(mode, allowed) ? "lune.cheat.on" : "lune.cheat.off"),
                    mode.about()));
        }
        if (!allowed) {
            reply.success(source, line(Lang.get("lune.cheat.locked")));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static <S> int report(S source, Reply<S> reply, Cheats.Mode mode) {
        reply.success(source, line(Lang.get("lune.cheat.reports", mode.label(),
                Lang.get(Cheats.isActive(mode, allowed()) ? "lune.cheat.on" : "lune.cheat.off"))));
        return Command.SINGLE_SUCCESS;
    }

    private static <S> int set(S source, Reply<S> reply, Cheats.Mode mode, boolean on) {
        if (!Cheats.set(mode, on, allowed())) {
            // Only ever reached for "on": switching a cheat off is allowed to anybody, anywhere.
            reply.failure(source, line(Lang.get("lune.cheat.denied", mode.label())));
            return 0;
        }
        reply.success(source, line(on
                ? Lang.get("lune.cheat.now_on", mode.label())
                : Lang.get("lune.cheat.now_off", mode.label())));
        return Command.SINGLE_SUCCESS;
    }

    private static <S> int allOff(S source, Reply<S> reply) {
        Cheats.clear();
        reply.success(source, line(Lang.get("lune.cheat.all_off")));
        return Command.SINGLE_SUCCESS;
    }

    /** Asked per invocation rather than cached: the world can change under a long-lived tree. */
    private static boolean allowed() {
        return OmniscientAccess.isAllowed(Minecraft.getInstance());
    }

    /** The same "[Lune] ..." prefix the bot's own chat lines carry. */
    private static String line(String message) {
        return Lang.get("lune.gui.bot_context.lune", message);
    }
}
