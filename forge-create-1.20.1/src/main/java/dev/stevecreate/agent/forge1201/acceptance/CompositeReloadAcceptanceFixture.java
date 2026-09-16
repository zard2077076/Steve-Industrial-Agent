package dev.stevecreate.agent.forge1201.acceptance;

import dev.stevecreate.agent.core.industrial.IndustrialLifecyclePhase;
import dev.stevecreate.agent.core.industrial.IndustrialPlayerOrderV1;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.forge1201.command.CompositePlayerOrderReloadProbe;
import dev.stevecreate.agent.forge1201.industrial.IndustrialPlayerOrderSavedData;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

/**
 * Two-process reload gate for a Composite player order.
 *
 * <p>This proves the documented fail-closed behaviour, not resumption.  A fresh JVM is
 * supposed to pause every non-terminal order at
 * {@code RELOAD_RECONCILIATION_REQUIRED} and replay no adapter, so the read phase
 * asserts exactly that — and, because a paused envelope alone would not prove the
 * ledger stayed honest, it also re-checks the player's own chest and the withdrawn
 * total against numbers the write phase recorded before it exited.</p>
 */
public final class CompositeReloadAcceptanceFixture {
    public static final String PHASE_PROPERTY =
            "steve_industrial.test.compositePlayerOrderReloadPhase";
    private static final int WRITE_SETTLE_TICKS = 120;
    private static final int TIMEOUT_TICKS = 4_000;

    private static Active active;

    private CompositeReloadAcceptanceFixture() {}

    public static void start(MinecraftServer server, String phase, Logger logger) {
        AcceptanceRuntimeGuard.requireDevelopmentRuntime("CompositeReloadAcceptanceFixture");
        if ("write".equals(phase)) {
            active = new Active(server, logger, "write", server.getTickCount(), null);
        } else if ("read".equals(phase)) {
            active = new Active(server, logger, "read", server.getTickCount(), null);
        } else {
            throw new IllegalArgumentException("Unknown composite reload phase: " + phase);
        }
    }

    public static void tick(MinecraftServer server) {
        Active current = active;
        if (current == null || current.server != server) return;
        try {
            int elapsed = server.getTickCount() - current.startTick;
            if (elapsed > TIMEOUT_TICKS) {
                throw new IllegalStateException("composite reload gate exceeded its tick budget");
            }
            if (current.phase.equals("write")) tickWrite(server, current, elapsed);
            else tickRead(server, current);
        } catch (RuntimeException failure) {
            current.logger.error("COMPOSITE_RELOAD_ACCEPTANCE FAIL phase={}", current.phase, failure);
            active = null;
            server.halt(false);
            throw failure;
        }
    }

    /**
     * Starts a real order, lets it physically withdraw and begin, then exits mid-run.
     * The point is to leave a RUNNING envelope and a partly consumed chest behind.
     */
    private static void tickWrite(MinecraftServer server, Active current, int elapsed) {
        ServerLevel level = server.overworld();
        if (current.probe == null) {
            if (elapsed < 20) return;
            current.probe = CompositePlayerOrderReloadProbe.startOrder(server);
            current.logger.info("COMPOSITE_RELOAD write started order project={} source={}",
                    current.probe.projectId(), current.probe.sourcePosition());
            return;
        }
        if (elapsed < 20 + WRITE_SETTLE_TICKS) return;
        IndustrialPlayerOrderV1 order = IndustrialPlayerOrderSavedData.forLevel(level)
                .order(current.probe.projectId()).orElseThrow(() ->
                        new IllegalStateException("composite reload write lost its order"));
        if (order.phase() != IndustrialLifecyclePhase.RUNNING) {
            throw new IllegalStateException(
                    "composite reload write expected a RUNNING order, saw " + order.phase()
                            + ":" + order.stage());
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
                order.phase().name(), order.stage(), order.generation(), contents);
        level.getServer().overworld().getDataStorage().save();
        current.logger.info(
                "COMPOSITE_RELOAD_WRITE PASS project={} orderPhase={} stage={} withdrawn={} chest={}",
                current.probe.projectId(), order.phase(), order.stage(), withdrawn, contents);
        active = null;
        server.halt(false);
    }

    /**
     * A fresh JVM must have paused the order without replaying anything, and must not
     * have moved one more item out of the player's chest.
     */
    private static void tickRead(MinecraftServer server, Active current) {
        ServerLevel level = server.overworld();
        CompositeReloadAcceptanceSavedData expected =
                CompositeReloadAcceptanceSavedData.forLevel(level);
        if (!expected.hasCheckpoint()) {
            throw new IllegalStateException("composite reload read found no write-phase checkpoint");
        }
        UUID projectId = expected.projectId();
        IndustrialPlayerOrderV1 order = IndustrialPlayerOrderSavedData.forLevel(level)
                .order(projectId).orElseThrow(() ->
                        new IllegalStateException("composite reload read lost the order envelope"));
        if (order.phase() != IndustrialLifecyclePhase.PAUSED) {
            throw new IllegalStateException("reloaded composite order is " + order.phase()
                    + ", expected PAUSED");
        }
        if (!"RELOAD_RECONCILIATION_REQUIRED".equals(order.stage())) {
            throw new IllegalStateException(
                    "reloaded composite order stage is " + order.stage());
        }
        if (order.report().isPresent()) {
            throw new IllegalStateException("a reloaded incomplete order fabricated a report");
        }
        if (CompositePlayerOrderReloadProbe.hasActiveRun(projectId)) {
            throw new IllegalStateException("a reloaded order resumed its wrapper without approval");
        }
        Map<String, Long> contents = CompositePlayerOrderReloadProbe.chestContents(level,
                new dev.stevecreate.agent.core.model.BlockPos3i(
                        expected.sourceX(), expected.sourceY(), expected.sourceZ()));
        if (!contents.equals(expected.sourceContents())) {
            throw new IllegalStateException("the player's chest changed across the reload: was "
                    + expected.sourceContents() + " now " + contents);
        }
        long withdrawn = CompositePlayerOrderReloadProbe.withdrawnTotal(level, projectId);
        if (withdrawn != expected.withdrawnTotal()) {
            throw new IllegalStateException("withdrawn total changed across the reload: was "
                    + expected.withdrawnTotal() + " now " + withdrawn);
        }
        // Fail-closed is not enough on its own: a paused order the player cannot cancel
        // has cost them everything it withdrew. Prove a restarted process can give it back.
        long beforeCancel = CompositePlayerOrderReloadProbe.chestContents(level,
                new dev.stevecreate.agent.core.model.BlockPos3i(
                        expected.sourceX(), expected.sourceY(), expected.sourceZ()))
                .values().stream().mapToLong(Long::longValue).sum();
        CompositePlayerOrderReloadProbe.CancelOutcome cancelled =
                CompositePlayerOrderReloadProbe.cancelAfterRestart(server, projectId);
        if (!cancelled.accepted()) {
            throw new IllegalStateException(
                    "a restarted server could not cancel the paused order: " + cancelled.code());
        }
        long afterCancel = CompositePlayerOrderReloadProbe.chestContents(level,
                new dev.stevecreate.agent.core.model.BlockPos3i(
                        expected.sourceX(), expected.sourceY(), expected.sourceZ()))
                .values().stream().mapToLong(Long::longValue).sum();
        if (afterCancel <= beforeCancel) {
            throw new IllegalStateException("cancelling a restarted order returned nothing: "
                    + beforeCancel + " -> " + afterCancel);
        }
        current.logger.info("COMPOSITE_RELOAD_CANCEL PASS code={} chestBefore={} chestAfter={} "
                        + "recovered={}", cancelled.code(), beforeCancel, afterCancel,
                afterCancel - beforeCancel);

        current.logger.info("COMPOSITE_RELOAD_READ PASS graph={} mode={} phase={} stage={} "
                        + "report=false resumed=false duplicateWithdrawal=false "
                        + "chestUnchanged=true withdrawn={} generationWrite={} generationRead={}",
                expected.orderType(), expected.mode(), order.phase(), order.stage(),
                withdrawn, expected.generationAtExit(), order.generation());
        active = null;
        server.halt(false);
    }

    private static final class Active {
        private final MinecraftServer server;
        private final Logger logger;
        private final String phase;
        private final int startTick;
        private CompositePlayerOrderReloadProbe.StartedOrder probe;

        private Active(
                MinecraftServer server,
                Logger logger,
                String phase,
                int startTick,
                CompositePlayerOrderReloadProbe.StartedOrder probe) {
            this.server = server;
            this.logger = logger;
            this.phase = phase;
            this.startTick = startTick;
            this.probe = probe;
        }
    }

    /** Exposed so the runner can name the graph under test in its log line. */
    public static ResourceId defaultOrderType() {
        return CompositePlayerOrderReloadProbe.DEFAULT_ORDER_TYPE;
    }
}
