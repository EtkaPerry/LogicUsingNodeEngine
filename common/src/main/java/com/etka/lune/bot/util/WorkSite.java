package com.etka.lune.bot.util;

import com.etka.lune.bot.BotContext;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;

/**
 * Where the bot is currently working, so it finishes what is in front of it instead of ping-ponging
 * across the room.
 * <p>
 * Tasks normally rank candidates by distance from the player. That sounds local, but it is not:
 * every time the bot steps, walks round an obstacle, or writes one candidate off, the ranking is
 * measured from a different place and the "nearest" answer can flip to something behind it. The
 * result is a bot that digs one side, then the other, for no reason a watcher can see.
 * <p>
 * A work site fixes the ranking to the last thing actually worked on, so the natural next pick is
 * the block beside the last one. The site is dropped when the bot genuinely leaves it, and when
 * lava turns up next to it - nobody methodically clears the ring around a lava pocket, they take
 * what is convenient and get out.
 */
public final class WorkSite {

    /** How far the bot may stray before the site stops being where it is working. */
    private static final int DEFAULT_RADIUS = 10;
    /** How close lava has to be before the site is no longer somewhere to settle in. */
    private static final int LAVA_RADIUS = 4;

    private final int radius;

    private BlockPos anchor;
    /** Cached lava answer; the scan is only worth redoing when the site actually moves. */
    private BlockPos lavaCheckedAt;
    private boolean lavaSeen;

    public WorkSite() {
        this(DEFAULT_RADIUS);
    }

    public WorkSite(int radius) {
        this.radius = Math.max(1, radius);
    }

    /** Records that work happened here. Call this for each block broken or item collected. */
    public void workedAt(BlockPos pos) {
        anchor = pos;
    }

    /** Forgets the site, so the next pick is ranked from the player again. */
    public void leave() {
        anchor = null;
    }

    public boolean isLavaAdjacent() {
        return anchor != null && lavaSeen;
    }

    /**
     * The point candidates should be ranked from.
     * <p>
     * Note this drops a site that is no longer valid, so it both answers the question and keeps the
     * site honest. Callers pass the result straight to whatever picks their next candidate.
     */
    public BlockPos focus(BotContext ctx) {
        return focus(ctx, ctx.player.blockPosition());
    }

    /**
     * As {@link #focus(BotContext)}, but with the caller's own ranking origin to fall back on when
     * there is no site. Callers that already rank from something steadier than the live player
     * position should pass it, or dropping the site would make them *less* stable, not more.
     */
    public BlockPos focus(BotContext ctx, BlockPos fallback) {
        if (anchor == null) {
            return fallback;
        }
        if (anchor.distSqr(ctx.player.blockPosition()) > (double) radius * radius) {
            // The bot has moved on. Wherever the caller measures from is the new centre of gravity.
            anchor = null;
            return fallback;
        }
        if (lavaNearby(ctx, anchor)) {
            anchor = null;
            return fallback;
        }
        return anchor;
    }

    /**
     * Whether lava is close enough to make settling in here a bad idea.
     * <p>
     * Only blocks the client already has are consulted, and only within touching distance of the
     * work - this is the lava you would see and hear at the face you are digging, not a survey of
     * the chunk.
     */
    private boolean lavaNearby(BotContext ctx, BlockPos centre) {
        if (centre.equals(lavaCheckedAt)) {
            return lavaSeen;
        }
        lavaCheckedAt = centre;
        lavaSeen = false;

        for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-LAVA_RADIUS, -LAVA_RADIUS, -LAVA_RADIUS),
                centre.offset(LAVA_RADIUS, LAVA_RADIUS, LAVA_RADIUS))) {
            if (ctx.level.getBlockState(pos).getFluidState().is(FluidTags.LAVA)) {
                lavaSeen = true;
                break;
            }
        }
        return lavaSeen;
    }
}
