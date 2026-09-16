package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import dev.stevecreate.agent.adapter.api.RuntimeFingerprint;
import dev.stevecreate.agent.core.execution.composite.CompositeProductionCoordinator;
import dev.stevecreate.agent.core.execution.composite.CompositeProductionGraph;
import dev.stevecreate.agent.core.execution.composite.CompositeProductionSnapshot;
import dev.stevecreate.agent.core.execution.composite.SharedInfrastructureLeaseRegistry;
import dev.stevecreate.agent.core.execution.construction.ExecutionMode;
import dev.stevecreate.agent.core.execution.construction.ReservationStatus;
import dev.stevecreate.agent.core.execution.construction.SharedInfrastructureReservation;
import dev.stevecreate.agent.core.execution.readiness.ExecutionReadinessFailureCode;
import dev.stevecreate.agent.core.execution.readiness.ExecutionReadyPlan;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraftforge.registries.ForgeRegistries;

/** Two independent real lines sharing one reference-counted physical output interlock. */
public final class CreateV606ConcurrentExecution {
    private CreateV606ConcurrentExecution() {}

    public static StartResult start(
            ServerLevel level,
            Line lineA,
            Line lineB,
            SharedOutputInterlock interlock,
            SharedInfrastructureReservation reservation,
            ExecutionMode mode,
            CreateV606ThreeModeExecution.TestRegion region) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(lineA, "lineA");
        Objects.requireNonNull(lineB, "lineB");
        Objects.requireNonNull(interlock, "interlock");
        Objects.requireNonNull(reservation, "reservation");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(region, "region");
        if (!level.getServer().isSameThread()) {
            return new Rejected(ExecutionReadinessFailureCode.EXECUTION_NOT_READY,
                    "Concurrent execution must start on the authoritative server thread");
        }
        try {
            validateDistinct(lineA, lineB);
            validateInterlock(level, lineA, lineB, interlock, reservation, region);
            SharedInfrastructureLeaseRegistry leases =
                    new SharedInfrastructureLeaseRegistry(reservation);
            leases.acquire(reservation.reservationId(), lineB.sessionId(), level.getGameTime());
            CompositeProductionGraph wrapperGraph = wrapperGraph(
                    lineA, lineB, reservation.reservationId(), reservation.infrastructureId());
            CreateV606ThreeModeExecution.Session first = startLine(level, lineA, mode, region);
            CreateV606ThreeModeExecution.Session second;
            try {
                second = startLine(level, lineB, mode, region);
            } catch (RuntimeException failure) {
                first.abortBeforeFirstTick();
                throw failure;
            }
            return new Started(new Session(
                    level, lineA, lineB, interlock, leases, reservation.reservationId(),
                    wrapperGraph, mode, first, second));
        } catch (IllegalArgumentException | IllegalStateException failure) {
            return new Rejected(ExecutionReadinessFailureCode.EXECUTION_NOT_READY,
                    "Concurrent execution rejected: " + failure.getMessage());
        }
    }

    public sealed interface StartResult permits Started, Rejected {}

    public record Started(Session session) implements StartResult {
        public Started { Objects.requireNonNull(session, "session"); }
    }

    public record Rejected(ExecutionReadinessFailureCode code, String detail)
            implements StartResult {
        public Rejected {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(detail, "detail");
        }
    }

    public sealed interface TickResult permits Progress, Completed, Failed {}

    public record Progress(
            boolean lineACancelled,
            boolean lineBCompleted,
            int sharedReferenceCount,
            boolean interlockPresent,
            boolean wrapperReloadReconciled) implements TickResult {}

    public record Completed(
            ExecutionMode mode,
            ResourceId lineASessionId,
            ResourceId lineBSessionId,
            CreateV606ThreeModeExecution.CancellationReport lineACancellation,
            CreateV606ThreeModeExecution.Completed lineBCompletion,
            SharedInfrastructureReservation finalReservation,
            ResourceId wrapperGraphId,
            String wrapperGraphFingerprint,
            long lineBFinalOutput,
            boolean sharedPreservedAfterLineACancel,
            boolean independentGraphs,
            boolean independentWorkers,
            boolean wrapperReloadReconciled,
            int peakConcurrentWorkers,
            boolean allBotsActive) implements TickResult {
        public Completed {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(lineASessionId, "lineASessionId");
            Objects.requireNonNull(lineBSessionId, "lineBSessionId");
            Objects.requireNonNull(lineACancellation, "lineACancellation");
            Objects.requireNonNull(lineBCompletion, "lineBCompletion");
            Objects.requireNonNull(finalReservation, "finalReservation");
            Objects.requireNonNull(wrapperGraphId, "wrapperGraphId");
            Objects.requireNonNull(wrapperGraphFingerprint, "wrapperGraphFingerprint");
            if (lineASessionId.equals(lineBSessionId)
                    || finalReservation.status() != ReservationStatus.RELEASED
                    || lineBFinalOutput < 1 || !sharedPreservedAfterLineACancel
                    || !independentGraphs || !independentWorkers
                    || !wrapperReloadReconciled
                    || (mode == ExecutionMode.DIRECT
                            && (peakConcurrentWorkers != 0 || !allBotsActive))
                    || (mode != ExecutionMode.DIRECT && peakConcurrentWorkers != 5)
                    || (mode == ExecutionMode.BOTS && !allBotsActive)
                    || !wrapperGraphFingerprint.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Concurrent completion evidence is incomplete");
            }
        }
    }

    public record Failed(String detail) implements TickResult {
        public Failed { Objects.requireNonNull(detail, "detail"); }
    }

    public record Line(
            ResourceId sessionId,
            ExecutionReadyPlan ready,
            RuntimeFingerprint runtime,
            BlockPos3i sourcePosition,
            BlockPos3i deliveryPosition,
            BlockPos3i finalOutputPosition,
            ResourceId outputResource,
            long outputQuantity,
            List<BlockPos3i> workerStarts,
            Map<ResourceId, Long> installationMaterials) {
        public Line {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(ready, "ready");
            Objects.requireNonNull(runtime, "runtime");
            Objects.requireNonNull(sourcePosition, "sourcePosition");
            Objects.requireNonNull(deliveryPosition, "deliveryPosition");
            Objects.requireNonNull(finalOutputPosition, "finalOutputPosition");
            Objects.requireNonNull(outputResource, "outputResource");
            if (outputQuantity < 1 || outputQuantity > 64) {
                throw new IllegalArgumentException("Concurrent output quantity is outside bounds");
            }
            workerStarts = List.copyOf(Objects.requireNonNull(workerStarts, "workerStarts"));
            installationMaterials = Map.copyOf(Objects.requireNonNull(
                    installationMaterials, "installationMaterials"));
        }
    }

    public record SharedOutputInterlock(
            BlockPos3i sharedPowerPosition,
            BlockPos3i lineAHopperPosition,
            BlockPos3i lineBHopperPosition) {
        public SharedOutputInterlock {
            Objects.requireNonNull(sharedPowerPosition, "sharedPowerPosition");
            Objects.requireNonNull(lineAHopperPosition, "lineAHopperPosition");
            Objects.requireNonNull(lineBHopperPosition, "lineBHopperPosition");
        }
    }

    public record CleanupReport(
            CreateV606ThreeModeExecution.CleanupReport lineB,
            int clearedSharedBlocks,
            int remainingSharedBlocks) {
        public CleanupReport { Objects.requireNonNull(lineB, "lineB"); }
    }

    public static final class Session {
        private final ServerLevel level;
        private final Line lineA;
        private final Line lineB;
        private final SharedOutputInterlock interlock;
        private SharedInfrastructureLeaseRegistry leases;
        private final ResourceId reservationId;
        private final CompositeProductionGraph wrapperGraph;
        private CompositeProductionCoordinator wrapperCoordinator;
        private final ExecutionMode mode;
        private final CreateV606ThreeModeExecution.Session sessionA;
        private final CreateV606ThreeModeExecution.Session sessionB;
        private CreateV606ThreeModeExecution.CancellationReport cancellationA;
        private CreateV606ThreeModeExecution.Completed completionB;
        private boolean sharedPreservedAfterCancel;
        private boolean wrapperReloadReconciled;
        private TickResult terminal;

        private Session(
                ServerLevel level,
                Line lineA,
                Line lineB,
                SharedOutputInterlock interlock,
                SharedInfrastructureLeaseRegistry leases,
                ResourceId reservationId,
                CompositeProductionGraph wrapperGraph,
                ExecutionMode mode,
                CreateV606ThreeModeExecution.Session sessionA,
                CreateV606ThreeModeExecution.Session sessionB) {
            this.level = level;
            this.lineA = lineA;
            this.lineB = lineB;
            this.interlock = interlock;
            this.leases = leases;
            this.reservationId = reservationId;
            this.wrapperGraph = wrapperGraph;
            this.wrapperCoordinator = new CompositeProductionCoordinator(wrapperGraph);
            if (!wrapperCoordinator.start(lineA.sessionId()).accepted()
                    || !wrapperCoordinator.start(lineB.sessionId()).accepted()) {
                throw new IllegalStateException(
                        "Concurrent wrapper could not start both exact physical lines");
            }
            this.mode = mode;
            this.sessionA = sessionA;
            this.sessionB = sessionB;
        }

        public TickResult tick() {
            if (terminal != null) return terminal;
            requireServerThread();
            if (cancellationA == null) {
                CreateV606ThreeModeExecution.TickResult resultA = sessionA.tick();
                if (resultA instanceof CreateV606ThreeModeExecution.Failed failed) {
                    terminal = new Failed("Line A failed before typed cancellation: " + failed.detail());
                    return terminal;
                }
                if (resultA instanceof CreateV606ThreeModeExecution.Completed) {
                    terminal = new Failed("Line A completed before the required cancellation");
                    return terminal;
                }
            }
            if (completionB == null) {
                CreateV606ThreeModeExecution.TickResult resultB = sessionB.tick();
                if (resultB instanceof CreateV606ThreeModeExecution.Failed failed) {
                    terminal = new Failed("Line B failed: " + failed.detail());
                    return terminal;
                }
                if (resultB instanceof CreateV606ThreeModeExecution.Completed completed) {
                    if (!wrapperReloadReconciled) {
                        terminal = new Failed(
                                "Line B completed before exact concurrent wrapper recovery");
                        return terminal;
                    }
                    completionB = completed;
                    if (!wrapperCoordinator.complete(lineB.sessionId(), java.util.Map.of())
                            .accepted()) {
                        terminal = new Failed(
                                "Line B physical completion did not commit to its wrapper node");
                        return terminal;
                    }
                    if (cancellationA == null) {
                        terminal = new Failed("Line B completed before line A cancellation evidence");
                        return terminal;
                    }
                    releaseLineB();
                }
            }
            reconcileWrapperReloadIfReady();
            if (completionB != null) {
                long finalCount = count(
                        level, lineB.finalOutputPosition(), lineB.outputResource());
                if (finalCount < lineB.outputQuantity()) return progress();
                boolean independentGraphs = !sessionA.graph().graphId()
                        .equals(sessionB.graph().graphId());
                boolean independentWorkers = sessionA.workerIds().stream()
                        .noneMatch(sessionB.workerIds()::contains);
                int peakWorkers = sessionA.workerIds().size() + sessionB.workerIds().size();
                boolean allBotsActive = mode != ExecutionMode.BOTS
                        || java.util.stream.Stream.concat(
                                        cancellationA.workerActivities().stream(),
                                        completionB.workerActivities().stream())
                                .allMatch(value -> value.assignmentsStarted() > 0
                                        && value.movementTicks() > 0);
                terminal = new Completed(
                        mode, lineA.sessionId(), lineB.sessionId(), cancellationA,
                        completionB, leases.reservation(reservationId), wrapperGraph.graphId(),
                        wrapperGraph.fingerprint(), finalCount, sharedPreservedAfterCancel,
                        independentGraphs, independentWorkers, wrapperReloadReconciled,
                        peakWorkers, allBotsActive);
                return terminal;
            }
            return progress();
        }

        public CreateV606ThreeModeExecution.CancellationReport cancelLineA(ResourceId reason) {
            requireServerThread();
            Objects.requireNonNull(reason, "reason");
            if (terminal != null || cancellationA != null || completionB != null) {
                throw new IllegalStateException("Line A cancellation is no longer available");
            }
            cancellationA = sessionA.cancelDetailed(reason);
            if (!wrapperCoordinator.cancelLine(lineA.sessionId())
                    .contains(lineA.sessionId())) {
                throw new IllegalStateException(
                        "Line A cancellation did not enter the exact wrapper node");
            }
            SharedInfrastructureLeaseRegistry.ReleaseResult released =
                    leases.release(reservationId, lineA.sessionId(), level.getGameTime());
            sharedPreservedAfterCancel = !released.cleanupAllowed()
                    && released.reservation().owningSessionIds().contains(lineB.sessionId())
                    && level.getBlockState(block(interlock.sharedPowerPosition()))
                            .is(Blocks.REDSTONE_BLOCK);
            if (!sharedPreservedAfterCancel) {
                throw new IllegalStateException(
                        "Line A cancellation removed line B shared infrastructure");
            }
            return cancellationA;
        }

        public List<CreateV606ThreeModeExecution.WorkerActivity> lineAWorkerActivities() {
            return sessionA.workerActivitiesSnapshot();
        }

        public CleanupReport cleanup() {
            requireServerThread();
            if (!(terminal instanceof Completed)) {
                throw new IllegalStateException("Concurrent cleanup requires completion");
            }
            CreateV606ThreeModeExecution.CleanupReport lineBCleanup = sessionB.cleanup();
            int cleared = 0;
            int remaining = 0;
            for (BlockPos position : List.of(
                    block(interlock.lineAHopperPosition()),
                    block(interlock.lineBHopperPosition()),
                    block(interlock.sharedPowerPosition()))) {
                if (level.getBlockState(position).isAir()) continue;
                if (level.getBlockState(position).is(Blocks.HOPPER)
                        || level.getBlockState(position).is(Blocks.REDSTONE_BLOCK)) {
                    level.setBlockAndUpdate(position, Blocks.AIR.defaultBlockState());
                }
                if (level.getBlockState(position).isAir()) cleared++;
                else remaining++;
            }
            return new CleanupReport(lineBCleanup, cleared, remaining);
        }

        private void releaseLineB() {
            SharedInfrastructureLeaseRegistry.ReleaseResult released =
                    leases.release(reservationId, lineB.sessionId(), level.getGameTime());
            if (!released.cleanupAllowed()
                    || released.reservation().status() != ReservationStatus.RELEASED) {
                throw new IllegalStateException("Line B final release retained a shared owner");
            }
            BlockPos power = block(interlock.sharedPowerPosition());
            level.setBlockAndUpdate(power, Blocks.AIR.defaultBlockState());
            if (!level.getBlockState(power).isAir()) {
                throw new IllegalStateException("Shared output interlock did not release");
            }
        }

        private Progress progress() {
            return new Progress(
                    cancellationA != null, completionB != null,
                    leases.reservation(reservationId).referenceCount(),
                    level.getBlockState(block(interlock.sharedPowerPosition()))
                            .is(Blocks.REDSTONE_BLOCK),
                    wrapperReloadReconciled);
        }

        private void reconcileWrapperReloadIfReady() {
            if (wrapperReloadReconciled || cancellationA == null || completionB != null
                    || !sessionB.reloadReconciled()) {
                return;
            }
            CompositeProductionSnapshot snapshot = wrapperCoordinator.snapshot();
            SharedInfrastructureReservation sharedSnapshot = leases.reservation(reservationId);
            if (!sharedSnapshot.owningSessionIds().equals(java.util.Set.of(lineB.sessionId()))
                    || sharedSnapshot.status() != ReservationStatus.ACTIVE
                    || !level.getBlockState(block(interlock.sharedPowerPosition()))
                    .is(Blocks.REDSTONE_BLOCK)) {
                throw new IllegalStateException(
                        "Concurrent wrapper reload lost line B shared ownership/interlock");
            }
            wrapperCoordinator = CompositeProductionCoordinator.restore(wrapperGraph, snapshot);
            leases = new SharedInfrastructureLeaseRegistry(sharedSnapshot);
            if (wrapperCoordinator.statuses().get(lineA.sessionId())
                    != CompositeProductionCoordinator.NodeStatus.CANCELLED
                    || wrapperCoordinator.statuses().get(lineB.sessionId())
                    != CompositeProductionCoordinator.NodeStatus.RECOVERY_REQUIRED) {
                throw new IllegalStateException(
                        "Concurrent wrapper reload changed cancellation/running state");
            }
            CompositeProductionCoordinator.RecoveryEvidence evidence =
                    new CompositeProductionCoordinator.RecoveryEvidence(
                            wrapperGraph.graphId(), wrapperGraph.fingerprint(), lineB.sessionId(),
                            snapshot.generation(), true, true, java.util.Map.of(),
                            "forge:create_v606/concurrent-wrapper+handler-authoritative-rescan");
            if (!wrapperCoordinator.reconcileRunningNode(lineB.sessionId(), evidence).accepted()) {
                throw new IllegalStateException(
                        "Concurrent wrapper exact recovery evidence was rejected");
            }
            wrapperReloadReconciled = true;
        }

        private void requireServerThread() {
            if (!level.getServer().isSameThread()) {
                throw new IllegalStateException("Concurrent execution left the server thread");
            }
        }
    }

    private static CreateV606ThreeModeExecution.Session startLine(
            ServerLevel level,
            Line line,
            ExecutionMode mode,
            CreateV606ThreeModeExecution.TestRegion region) {
        CreateV606ThreeModeExecution.StartResult start = CreateV606ThreeModeExecution.start(
                level, line.ready(), line.runtime(), line.sourcePosition(), line.deliveryPosition(),
                mode, region, mode == ExecutionMode.DIRECT ? List.of() : line.workerStarts(),
                null, line.installationMaterials());
        if (start instanceof CreateV606ThreeModeExecution.Started value) return value.session();
        throw new IllegalStateException("Concurrent line did not start: " + start);
    }

    private static CompositeProductionGraph wrapperGraph(
            Line lineA, Line lineB, ResourceId reservationId,
            ResourceId infrastructureId) {
        CompositeProductionGraph.Node nodeA = new CompositeProductionGraph.Node(
                lineA.sessionId(), ResourceId.parse("steve_industrial:composite/real_line"),
                lineA.sessionId(), java.util.Set.of(infrastructureId));
        CompositeProductionGraph.Node nodeB = new CompositeProductionGraph.Node(
                lineB.sessionId(), ResourceId.parse("steve_industrial:composite/real_line"),
                lineB.sessionId(), java.util.Set.of(infrastructureId));
        ResourceId graphId = new ResourceId(
                reservationId.namespace(), reservationId.path() + "/wrapper_graph");
        return new CompositeProductionGraph(
                graphId, CompositeProductionGraph.Shape.CONCURRENT_LINES,
                List.of(nodeA, nodeB), List.of());
    }

    private static void validateDistinct(Line lineA, Line lineB) {
        if (lineA.sessionId().equals(lineB.sessionId())
                || lineA.ready().physicalPlan().id().equals(lineB.ready().physicalPlan().id())
                || lineA.sourcePosition().equals(lineB.sourcePosition())
                || lineA.deliveryPosition().equals(lineB.deliveryPosition())
                || lineA.finalOutputPosition().equals(lineB.finalOutputPosition())) {
            throw new IllegalArgumentException("Concurrent lines are not independently identified");
        }
    }

    private static void validateInterlock(
            ServerLevel level,
            Line lineA,
            Line lineB,
            SharedOutputInterlock interlock,
            SharedInfrastructureReservation reservation,
            CreateV606ThreeModeExecution.TestRegion region) {
        if (reservation.status() != ReservationStatus.ACTIVE
                || !reservation.verifiedPhysicalPlanId()
                        .equals(lineA.ready().physicalPlan().id())
                || !reservation.owningSessionIds().equals(java.util.Set.of(lineA.sessionId()))
                || !reservation.positions().equals(List.of(interlock.sharedPowerPosition()))) {
            throw new IllegalArgumentException(
                    "Shared reservation is not bound to line A's verified plan and ownership");
        }
        if (!region.contains(interlock.sharedPowerPosition())
                || !region.contains(interlock.lineAHopperPosition())
                || !region.contains(interlock.lineBHopperPosition())
                || !level.getBlockState(block(interlock.sharedPowerPosition()))
                        .is(Blocks.REDSTONE_BLOCK)) {
            throw new IllegalArgumentException("Shared physical interlock is missing or out of bounds");
        }
        validateLineHopper(level, lineA, interlock.lineAHopperPosition(), Direction.WEST);
        validateLineHopper(level, lineB, interlock.lineBHopperPosition(), Direction.EAST);
        if (!block(interlock.lineAHopperPosition()).east()
                        .equals(block(interlock.sharedPowerPosition()))
                || !block(interlock.lineBHopperPosition()).west()
                        .equals(block(interlock.sharedPowerPosition()))) {
            throw new IllegalArgumentException("Both line Hoppers must share the same physical lock");
        }
    }

    private static void validateLineHopper(
            ServerLevel level, Line line, BlockPos3i hopperPosition, Direction expectedFacing) {
        BlockPos hopper = block(hopperPosition);
        if (!(level.getBlockEntity(hopper) instanceof HopperBlockEntity)
                || !level.getBlockState(hopper).getValue(HopperBlock.FACING).equals(expectedFacing)
                || !hopper.above().equals(block(line.deliveryPosition()))
                || !hopper.relative(expectedFacing).equals(block(line.finalOutputPosition()))
                || !(level.getBlockEntity(block(line.finalOutputPosition()))
                        instanceof ChestBlockEntity)) {
            throw new IllegalArgumentException("Concurrent line output Hopper is invalid");
        }
    }

    private static long count(ServerLevel level, BlockPos3i position, ResourceId resource) {
        Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(resource.toString()));
        if (item == null) {
            throw new IllegalStateException("Concurrent output resource is not registered: " + resource);
        }
        if (!(level.getBlockEntity(block(position)) instanceof ChestBlockEntity chest)) {
            throw new IllegalStateException("Concurrent output chest is missing at " + position);
        }
        long result = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack stack = chest.getItem(slot);
            if (stack.is(item)) result = Math.addExact(result, stack.getCount());
        }
        return result;
    }

    private static BlockPos block(BlockPos3i position) {
        return new BlockPos(position.x(), position.y(), position.z());
    }
}
