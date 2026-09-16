package dev.stevecreate.agent.forge1201.acceptance;

import dev.stevecreate.agent.core.industrial.CompositeResumePlanV1;
import dev.stevecreate.agent.core.industrial.IndustrialLifecyclePhase;
import dev.stevecreate.agent.core.industrial.IndustrialPlayerOrderV1;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.forge1201.command.CompositePlayerOrderReloadProbe;
import dev.stevecreate.agent.forge1201.industrial.IndustrialPlayerOrderSavedData;
import dev.stevecreate.agent.core.execution.construction.ExecutionMode;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

/**
 * Two-process gate for finishing an interrupted Composite order instead of refunding it.
 *
 * <p>The reload gate proves the order fails closed and can be cancelled, which leaves the
 * player whole but throws away everything the run consumed and the time it took. This
 * proves the other outcome: a fresh JVM picks the run up where the site says it stopped
 * and carries it to a real product.
 *
 * <p>The write phase does not stop after a fixed number of ticks. It stops when the
 * resume decision reports a stage boundary has been crossed, because a tick budget can
 * land mid-recipe — input consumed, output not yet made — and a gate that fails on timing
 * rather than on behaviour teaches nothing. What it records is the stage it stopped past,
 * so the read phase can assert the run resumed further along than it started rather than
 * quietly rerunning from the beginning, which would produce the same item while charging
 * the player twice.</p>
 */
public final class CompositeResumeAcceptanceFixture {
    public static final String PHASE_PROPERTY =
            "steve_industrial.test.compositePlayerOrderResumePhase";
    private static final int TIMEOUT_TICKS = 6_000;

    private static Active active;

    private CompositeResumeAcceptanceFixture() {}

    public static void start(MinecraftServer server, String phase, Logger logger) {
        AcceptanceRuntimeGuard.requireDevelopmentRuntime("CompositeResumeAcceptanceFixture");
        if (!"write".equals(phase) && !"read".equals(phase)) {
            throw new IllegalArgumentException("Unknown composite resume phase: " + phase);
        }
        active = new Active(server, logger, phase, server.getTickCount());
    }

    public static void tick(MinecraftServer server) {
        Active current = active;
        if (current == null || current.server != server) return;
        try {
            int elapsed = server.getTickCount() - current.startTick;
            if (elapsed > TIMEOUT_TICKS) {
                throw new IllegalStateException(
                        "composite resume gate exceeded its tick budget in phase " + current.phase);
            }
            if (current.phase.equals("write")) tickWrite(server, current, elapsed);
            else tickRead(server, current, elapsed);
        } catch (RuntimeException failure) {
            current.logger.error("COMPOSITE_RESUME_ACCEPTANCE FAIL phase={}", current.phase,
                    failure);
            active = null;
            server.halt(false);
            throw failure;
        }
    }

    /**
     * Starts a real order and exits once material has demonstrably moved past the first
     * stage, leaving a RUNNING envelope and a half-built product behind.
     */
    private static void tickWrite(MinecraftServer server, Active current, int elapsed) {
        ServerLevel level = server.overworld();
        if (current.probe == null) {
            if (elapsed < 20) return;
            current.probe = CompositePlayerOrderReloadProbe.startOrder(server);
            current.logger.info("COMPOSITE_RESUME write started order project={}",
                    current.probe.projectId());
            return;
        }
        Optional<CompositeResumePlanV1> plan =
                CompositePlayerOrderReloadProbe.resumePlan(server, current.probe.projectId());
        if (plan.isEmpty()) {
            if (!"gone".equals(current.lastReason)) {
                current.lastReason = "gone";
                current.logger.info("COMPOSITE_RESUME write observed tick={} plan=absent", elapsed);
            }
            return;
        }
        CompositeResumePlanV1 decision = plan.get();
        // Every distinct reading is logged once. How long a resumable boundary lasts, and
        // whether one occurs at all, is the thing this gate is really asking about, and a
        // silent poll would answer it with a timeout and no information.
        if (!decision.reason().equals(current.lastReason)) {
            current.lastReason = decision.reason();
            current.logger.info("COMPOSITE_RESUME write observed tick={} decision={} reason={}",
                    elapsed, decision.decision(), decision.reason());
        }
        // Wait for a boundary the run has actually crossed. Stage zero is where every
        // order begins, so stopping there would prove nothing about resuming.
        if (decision.decision() != CompositeResumePlanV1.Decision.RESUME_AT_STAGE
                || decision.stageIndex() < 1) {
            return;
        }
        IndustrialPlayerOrderV1 order = IndustrialPlayerOrderSavedData.forLevel(level)
                .order(current.probe.projectId()).orElseThrow(() ->
                        new IllegalStateException("composite resume write lost its order"));
        if (order.phase() != IndustrialLifecyclePhase.RUNNING) {
            throw new IllegalStateException(
                    "composite resume write expected a RUNNING order, saw " + order.phase());
        }
        Map<String, Long> contents = CompositePlayerOrderReloadProbe.chestContents(
                level, current.probe.sourcePosition());
        long withdrawn = CompositePlayerOrderReloadProbe.withdrawnTotal(
                level, current.probe.projectId());
        CompositeReloadAcceptanceSavedData.forLevel(level).record(
                current.probe.projectId(), current.probe.orderType().toString(),
                current.probe.mode().name(),
                current.probe.sourcePosition().x(), current.probe.sourcePosition().y(),
                current.probe.sourcePosition().z(), withdrawn,
                order.phase().name(), String.valueOf(decision.stageIndex()), order.generation(),
                contents);
        level.getServer().overworld().getDataStorage().save();
        current.logger.info("COMPOSITE_RESUME_WRITE PASS project={} stoppedPastStage={} "
                        + "withdrawn={} ticks={}",
                current.probe.projectId(), decision.stageIndex(), withdrawn, elapsed);
        active = null;
        server.halt(false);
    }

    /** A fresh JVM must resume from the recorded stage and finish the product. */
    private static void tickRead(MinecraftServer server, Active current, int elapsed) {
        ServerLevel level = server.overworld();
        CompositeReloadAcceptanceSavedData expected =
                CompositeReloadAcceptanceSavedData.forLevel(level);
        if (!expected.hasCheckpoint()) {
            throw new IllegalStateException("composite resume read found no write checkpoint");
        }
        UUID projectId = expected.projectId();
        if (current.resumedFrom < 0) {
            if (elapsed < 20) return;
            // Fail-closed first: the restart must not have resumed anything on its own.
            if (CompositePlayerOrderReloadProbe.hasActiveRun(projectId)) {
                throw new IllegalStateException("a reloaded order resumed without being asked");
            }
            Optional<CompositeResumePlanV1> plan =
                    CompositePlayerOrderReloadProbe.resumePlan(server, projectId);
            CompositeResumePlanV1 decision = plan.orElseThrow(() ->
                    new IllegalStateException("a restarted process could not read the site"));
            if (decision.decision() != CompositeResumePlanV1.Decision.RESUME_AT_STAGE) {
                throw new IllegalStateException(
                        "a restarted process refused a site it built itself: " + decision.reason());
            }
            int recorded = Integer.parseInt(expected.stageAtExit());
            if (decision.stageIndex() != recorded) {
                throw new IllegalStateException("the site reads differently across a restart: was "
                        + recorded + " now " + decision.stageIndex());
            }
            CompositePlayerOrderReloadProbe.ResumeOutcome resumed =
                    CompositePlayerOrderReloadProbe.resumeAfterRestart(server, projectId,
                            ExecutionMode.valueOf(expected.mode()));
            if (!resumed.accepted()) {
                throw new IllegalStateException("a restarted server could not resume: "
                        + resumed.code());
            }
            current.resumedFrom = decision.stageIndex();
            current.logger.info("COMPOSITE_RESUME read resumed project={} fromStage={} code={}",
                    projectId, current.resumedFrom, resumed.code());
            return;
        }

        IndustrialPlayerOrderV1 order = IndustrialPlayerOrderSavedData.forLevel(level)
                .order(projectId).orElseThrow(() ->
                        new IllegalStateException("composite resume read lost the envelope"));
        if (order.report().isEmpty()) {
            if (order.phase() == IndustrialLifecyclePhase.PAUSED) {
                throw new IllegalStateException("a resumed order paused again at " + order.stage());
            }
            return;
        }
        Map<ResourceId, Long> outputs = order.report().orElseThrow().outputs();
        if (outputs.isEmpty()) {
            throw new IllegalStateException("a resumed order reported no output");
        }
        // The withdrawn total must not have grown: resuming reuses material the original
        // run already paid for, and a second withdrawal is the double spend this whole
        // design refuses ambiguous sites to avoid.
        long withdrawn = CompositePlayerOrderReloadProbe.withdrawnTotal(level, projectId);
        if (withdrawn != expected.withdrawnTotal()) {
            throw new IllegalStateException("resuming withdrew again: was "
                    + expected.withdrawnTotal() + " now " + withdrawn);
        }
        current.logger.info("COMPOSITE_RESUME_READ PASS graph={} mode={} resumedFromStage={} "
                        + "output={} withdrawnUnchanged=true phase={} ticks={}",
                expected.orderType(), expected.mode(), current.resumedFrom, outputs,
                order.phase(), elapsed);
        active = null;
        server.halt(false);
    }

    private static final class Active {
        private final MinecraftServer server;
        private final Logger logger;
        private final String phase;
        private final int startTick;
        private CompositePlayerOrderReloadProbe.StartedOrder probe;
        private int resumedFrom = -1;
        private String lastReason;

        private Active(MinecraftServer server, Logger logger, String phase, int startTick) {
            this.server = server;
            this.logger = logger;
            this.phase = phase;
            this.startTick = startTick;
        }
    }
}
