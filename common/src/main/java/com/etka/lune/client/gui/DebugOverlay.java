package com.etka.lune.client.gui;

import com.etka.lune.bot.BotEngine;
import com.etka.lune.bot.DebugInfo;
import com.etka.lune.config.BotConfig;
import com.etka.lune.platform.BuildFeatures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Top-left telemetry readout: what the bot thinks it is doing, and why it might not be doing it.
 * <p>
 * Lives in {@code common} and takes a {@link GuiGraphicsExtractor}, which is exactly what both
 * NeoForge's {@code RenderGuiEvent} and Fabric's {@code HudElement} hand over - so the loaders only
 * have to forward the call.
 */
public final class DebugOverlay {

    private static final int MARGIN = 4;
    private static final int LINE_HEIGHT = 10;
    private static final int BACKGROUND = 0xB0000000;

    private static final int HEADING = 0xFF4C9EFF;
    private static final int LABEL = 0xFF9A9AA4;
    private static final int VALUE = 0xFFE0E0E0;
    private static final int GOOD = 0xFF69E08A;
    private static final int WARN = 0xFFFFB347;
    private static final int BAD = 0xFFFF6B6B;
    private static final int MOVE_MARKER = 0xFF55D6FF;
    private static final int PLACE_MARKER = 0xFFFFD166;
    private static final int BREAK_MARKER = 0xFFFF6666;

    private DebugOverlay() {}

    public static void render(GuiGraphicsExtractor extractor) {
        LuneStatusOverlay.render(extractor);
        BotConfig config = BotConfig.get();
        if (!config.showDebug) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        // Hide while a screen is open: the panel already shows this, and it would sit under the UI.
        if (mc.player == null || mc.screen != null) {
            return;
        }

        DebugInfo debug = BotEngine.get().getDebug();
        List<Line> lines = buildLines(mc, debug, config);

        int width = 0;
        for (Line line : lines) {
            width = Math.max(width, mc.font.width(line.text()));
        }
        int height = lines.size() * LINE_HEIGHT + MARGIN;

        extractor.fill(2, 2, MARGIN * 2 + width + 4, height + 6, BACKGROUND);

        var text = extractor.textRenderer();
        int y = MARGIN + 2;
        for (Line line : lines) {
            text.accept(MARGIN + 2, y, Component.literal(line.text()).withColor(line.colour()));
            y += LINE_HEIGHT;
        }
    }

    private record Line(String text, int colour) {}

    private static List<Line> buildLines(Minecraft mc, DebugInfo debug, BotConfig config) {
        List<Line> lines = new ArrayList<>();
        lines.add(new Line("Lune  " + debug.state, HEADING));
        lines.add(new Line("task    " + debug.taskName, VALUE));

        if (debug.taskTicks > 0) {
            lines.add(new Line("age     " + debug.taskTicks + " ticks", LABEL));
        }

        if (!debug.nextTask.isEmpty()) {
            lines.add(new Line("queued  " + debug.nextTask, LABEL));
        }

        // Thought history: newest first, no consecutive duplicates, capped at 7. This is the bot's
        // short-term memory of what it was trying to do, which is much easier to read than a single
        // line that flickers every tick.
        List<String> thoughts = new ArrayList<>(debug.thoughts);
        Collections.reverse(thoughts);
        boolean hasActiveTask = !"-".equals(debug.taskName);
        for (int i = 0; i < thoughts.size(); i++) {
            int colour = i == 0 ? VALUE : LABEL;
            String text = thoughts.get(i);
            if (i == 0 && hasActiveTask && debug.thoughtRepeat > 1) {
                text += " x" + debug.thoughtRepeat;
            }
            String prefix = i == 0 ? (hasActiveTask ? "now     " : "last    ") : "        ";
            lines.add(new Line(prefix + text, colour));
        }

        if (debug.queueSize > 0) {
            lines.add(new Line("queue   " + debug.queueSize, LABEL));
        }

        if (!debug.learningAction.isEmpty() || !debug.learningMemory.isEmpty()) {
            String policy = debug.learningAction.isEmpty() ? "-" : debug.learningAction;
            lines.add(new Line("learn   " + policy + "  reward "
                    + String.format(java.util.Locale.ROOT, "%.1f", debug.learningReward)
                    + "  updates " + debug.learningUpdates
                    + (debug.learningBestTicksPerUnit > 0.0
                        ? "  best " + String.format(java.util.Locale.ROOT, "%.1f",
                            debug.learningBestTicksPerUnit) + " t/unit"
                        : ""), VALUE));
        }
        if (BuildFeatures.approvalFeedback() && !debug.automaticVerdict.isEmpty()) {
            String timing = debug.automaticUsualTicks > 0
                    ? " " + debug.automaticTicks + "/" + debug.automaticUsualTicks + " ticks"
                    : " " + debug.automaticTicks + " ticks (baseline)";
            lines.add(new Line("auto    " + debug.automaticVerdict + timing
                    + (debug.automaticBestTicks > 0
                        ? "  best " + debug.automaticBestTicks + " ticks" : "")
                    + (debug.automaticReason.isEmpty() ? "" : " - " + debug.automaticReason),
                    "approved".equals(debug.automaticVerdict) ? GOOD : BAD));
        }

        BlockPos feet = mc.player.blockPosition();
        lines.add(new Line("at      " + feet.getX() + " " + feet.getY() + " " + feet.getZ()
                + "   yaw " + Mth.floor(Mth.wrapDegrees(mc.player.getYRot())), LABEL));

        if (config.debugPathDetail) {
            if (!debug.intent.isEmpty()) {
                lines.add(new Line("intent  " + debug.intent, VALUE));
            }
            if (debug.movementTarget != null) {
                String label = debug.movementLabel.isEmpty() ? "route waypoint" : debug.movementLabel;
                lines.add(new Line("move    " + label + " " + debug.movementTarget.toShortString()
                        + "  " + distance(feet, debug.movementTarget), MOVE_MARKER));
            }
            if (debug.placementTarget != null) {
                String placement = debug.placementBlock + " "
                        + debug.placementTarget.toShortString();
                if (!debug.placementVerdict.isEmpty()) {
                    placement += " - " + debug.placementVerdict;
                }
                lines.add(new Line("place   " + placement, PLACE_MARKER));
            }
            if (debug.breakTarget != null) {
                String breaking = debug.breakBlock + " " + debug.breakTarget.toShortString();
                if (!debug.breakVerdict.isEmpty()) {
                    breaking += " - " + debug.breakVerdict;
                }
                lines.add(new Line("break   " + breaking, BREAK_MARKER));
            }
            if (!debug.targetLabel.isEmpty() || debug.targetPos != null) {
                String target = debug.targetLabel.isEmpty() ? "candidate" : debug.targetLabel;
                if (debug.targetPos != null) {
                    target += " " + debug.targetPos.toShortString();
                }
                if (!debug.targetVerdict.isEmpty()) {
                    target += " - " + debug.targetVerdict;
                }
                lines.add(new Line("target  " + target, debug.targetVerdict.startsWith("visible")
                        ? VALUE : WARN));
            }
            if (debug.searchAnchor != null || debug.searchLimit > 0) {
                String search = "anchor " + (debug.searchAnchor == null ? "-"
                        : debug.searchAnchor.toShortString());
                if (debug.searchLimit > 0) {
                    search += "  " + debug.searchAttempt + "/" + debug.searchLimit + " stops";
                }
                if (debug.searchViewCount > 0) {
                    search += "  view " + debug.searchView + "/" + debug.searchViewCount
                            + "  tick " + debug.scanTicks;
                }
                if (debug.searchCandidates > 0) {
                    search += "  candidates " + debug.searchCandidates;
                }
                if (!debug.searchHeading.isEmpty()) {
                    search += "  " + debug.searchHeading;
                }
                lines.add(new Line("scan    " + search, LABEL));
            }
            if (!debug.memory.isEmpty()) {
                lines.add(new Line("memory  " + debug.memory, LABEL));
            }
            if (!debug.learningContext.isEmpty()) {
                lines.add(new Line("state   " + debug.learningContext, LABEL));
            }
            if (!debug.missionProgress.isEmpty()) {
                lines.add(new Line("mission " + debug.missionProgress, VALUE));
            }
            if (!debug.missionMemory.isEmpty()) {
                lines.add(new Line("remember " + debug.missionMemory, LABEL));
            }
            if (!debug.missionLoop.isEmpty()) {
                lines.add(new Line("loop    " + debug.missionLoop,
                        debug.missionLoop.startsWith("possible loop") ? BAD : WARN));
            }
            if (!debug.giveUp.isEmpty()) {
                lines.add(new Line("limits  " + debug.giveUp, WARN));
            }
            if (!debug.nextDecision.isEmpty()) {
                lines.add(new Line("next    " + debug.nextDecision + debug.decisionRepeatSuffix(), VALUE));
            }
            if (!debug.obstruction.isEmpty()) {
                String obstruction = debug.obstruction;
                if (debug.obstructionPos != null) {
                    obstruction += " " + debug.obstructionPos.toShortString();
                }
                lines.add(new Line("block   " + obstruction, WARN));
            }
        }

        lines.add(new Line("goal    " + debug.goal, VALUE));

        if (debug.currentNode != null) {
            BlockPos node = debug.currentNode;
            lines.add(new Line("node    " + node.getX() + " " + node.getY() + " " + node.getZ()
                    + "   [" + debug.pathIndex + "/" + debug.pathLength + "]", VALUE));
        }

        // The single most useful number when the bot looks frozen: how long since it last got
        // closer to its current waypoint.
        if (debug.noProgressTicks > 0) {
            int colour = debug.noProgressTicks > 20 ? BAD : WARN;
            lines.add(new Line("stalled " + debug.noProgressTicks + " ticks", colour));
        }
        if (debug.goalNoProgressTicks > 0) {
            int colour = debug.goalNoProgressTicks > 180 ? BAD : WARN;
            lines.add(new Line("goalstall " + debug.goalNoProgressTicks + " ticks", colour));
        }

        lines.add(new Line("keys    " + debug.keys, VALUE));

        if (config.debugPathDetail) {
            lines.add(new Line("astar   " + debug.nodesExpanded + "/" + debug.nodeBudget + " nodes  "
                    + String.format("%.1f", debug.searchMillis) + " ms  "
                    + (debug.reachedGoal ? "full" : "partial"),
                    debug.reachedGoal ? LABEL : WARN));
            lines.add(new Line("repaths " + debug.repaths, LABEL));
        }

        if (!debug.lastEvent.isEmpty()) {
            lines.add(new Line("last    " + debug.lastEvent, WARN));
        }

        if (!debug.runTraceFile.isEmpty()) {
            lines.add(new Line("trace   " + debug.runTraceFile, LABEL));
        }

        if (config.debugPathDetail && !debug.decisions.isEmpty()) {
            List<String> decisions = new ArrayList<>(debug.decisions);
            Collections.reverse(decisions);
            for (int i = 1; i < Math.min(4, decisions.size()); i++) {
                lines.add(new Line("trace   " + decisions.get(i), LABEL));
            }
        }
        return lines;
    }

    private static String distance(BlockPos from, BlockPos to) {
        double dx = to.getX() + 0.5 - (from.getX() + 0.5);
        double dy = to.getY() - from.getY();
        double dz = to.getZ() + 0.5 - (from.getZ() + 0.5);
        return "d=" + String.format("%.1f", Math.sqrt(dx * dx + dy * dy + dz * dz));
    }
}
