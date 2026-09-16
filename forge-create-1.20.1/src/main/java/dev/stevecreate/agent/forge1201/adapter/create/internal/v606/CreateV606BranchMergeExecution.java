package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import dev.stevecreate.agent.adapter.api.RuntimeFingerprint;
import dev.stevecreate.agent.core.execution.composite.CompositeProductionCoordinator;
import dev.stevecreate.agent.core.execution.composite.CompositeProductionGraph;
import dev.stevecreate.agent.core.execution.composite.CompositeProductionSnapshot;
import dev.stevecreate.agent.core.execution.construction.ExecutionMode;
import dev.stevecreate.agent.core.execution.readiness.ExecutionReadinessFailureCode;
import dev.stevecreate.agent.core.execution.readiness.ExecutionReadyPlan;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

/** Real bounded split/parallel-branch/physical-merge execution for Composite-03. */
public final class CreateV606BranchMergeExecution {
    private CreateV606BranchMergeExecution() {}

    public static StartResult start(
            ServerLevel level,
            CompositeProductionGraph graph,
            RootSplit split,
            HandlerStage woodBranch,
            HandlerStage alloyBranch,
            HandlerStage merge,
            List<MergeRoute> mergeRoutes,
            ExecutionMode mode,
            CreateV606ThreeModeExecution.TestRegion region) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(split, "split");
        Objects.requireNonNull(woodBranch, "woodBranch");
        Objects.requireNonNull(alloyBranch, "alloyBranch");
        Objects.requireNonNull(merge, "merge");
        mergeRoutes = List.copyOf(Objects.requireNonNull(mergeRoutes, "mergeRoutes"));
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(region, "region");
        if (!level.getServer().isSameThread()) {
            return new Rejected(ExecutionReadinessFailureCode.EXECUTION_NOT_READY,
                    "Branch/merge execution must start on the authoritative server thread");
        }
        try {
            validateTopology(graph, split, woodBranch, alloyBranch, merge, mergeRoutes);
            validatePhysical(level, split, woodBranch, alloyBranch, merge, mergeRoutes, region);
            return new Started(new Session(
                    level, graph, split, woodBranch, alloyBranch, merge,
                    mergeRoutes, mode, region));
        } catch (IllegalArgumentException | IllegalStateException failure) {
            return new Rejected(ExecutionReadinessFailureCode.EXECUTION_NOT_READY,
                    "Branch/merge execution rejected: " + failure.getMessage());
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
            Phase phase,
            boolean woodCompleted,
            boolean alloyCompleted,
            int completedPhysicalRoutes) implements TickResult {
        public Progress {
            Objects.requireNonNull(phase, "phase");
            if (completedPhysicalRoutes < 0 || completedPhysicalRoutes > 4) {
                throw new IllegalArgumentException("Branch/merge route count is invalid");
            }
        }
    }

    public record Completed(
            ResourceId graphId,
            String graphFingerprint,
            ExecutionMode mode,
            List<CreateV606ThreeModeExecution.Completed> handlerCompletions,
            CompositeProductionSnapshot coordinatorSnapshot,
            ResourceId finalResource,
            long finalQuantity,
            int completedPhysicalRoutes,
            int peakConcurrentWorkers,
            int retiredBranchWorkers) implements TickResult {
        public Completed {
            Objects.requireNonNull(graphId, "graphId");
            Objects.requireNonNull(graphFingerprint, "graphFingerprint");
            Objects.requireNonNull(mode, "mode");
            handlerCompletions = List.copyOf(handlerCompletions);
            Objects.requireNonNull(coordinatorSnapshot, "coordinatorSnapshot");
            Objects.requireNonNull(finalResource, "finalResource");
            if (handlerCompletions.size() != 3
                    || handlerCompletions.stream().anyMatch(value ->
                            value.mode() != mode || !value.reloadReconciled())
                    || finalQuantity < 1 || completedPhysicalRoutes != 4
                    || peakConcurrentWorkers < 0 || retiredBranchWorkers < 0
                    || (mode == ExecutionMode.DIRECT
                            && (peakConcurrentWorkers != 0 || retiredBranchWorkers != 0))
                    || (mode != ExecutionMode.DIRECT
                            && (peakConcurrentWorkers != 5 || retiredBranchWorkers != 5))) {
                throw new IllegalArgumentException("Branch/merge completion evidence is incomplete");
            }
        }
    }

    public record Failed(
            Phase phase,
            ResourceId nodeId,
            String detail,
            CompositeProductionSnapshot coordinatorSnapshot) implements TickResult {
        public Failed {
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(detail, "detail");
            Objects.requireNonNull(coordinatorSnapshot, "coordinatorSnapshot");
        }
    }

    public enum Phase {
        SPLIT_WOOD,
        SPLIT_ALLOY,
        BRANCHES,
        MERGE_ROUTING,
        MERGE_PROCESSING,
        COMPLETED,
        FAILED
    }

    public record RootSplit(
            ResourceId nodeId,
            BlockPos3i sourceChest,
            BlockPos3i hopperPosition,
            BlockPos3i lockPosition,
            ResourceId woodResource,
            long woodQuantity,
            ResourceId alloyBaseResource,
            long alloyBaseQuantity,
            ResourceId alloyAdjunctResource,
            long alloyAdjunctQuantity) {
        public RootSplit {
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(sourceChest, "sourceChest");
            Objects.requireNonNull(hopperPosition, "hopperPosition");
            Objects.requireNonNull(lockPosition, "lockPosition");
            Objects.requireNonNull(woodResource, "woodResource");
            Objects.requireNonNull(alloyBaseResource, "alloyBaseResource");
            Objects.requireNonNull(alloyAdjunctResource, "alloyAdjunctResource");
            if (woodQuantity != 1 || alloyBaseQuantity != 1 || alloyAdjunctQuantity != 1) {
                throw new IllegalArgumentException("Composite-03 root split requires three exact units");
            }
        }
    }

    public record HandlerStage(
            ResourceId nodeId,
            ExecutionReadyPlan ready,
            RuntimeFingerprint runtime,
            BlockPos3i sourcePosition,
            BlockPos3i deliveryPosition,
            ResourceId targetResource,
            long targetQuantity,
            List<BlockPos3i> workerStarts,
            Map<ResourceId, Long> installationMaterials) {
        public HandlerStage {
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(ready, "ready");
            Objects.requireNonNull(runtime, "runtime");
            Objects.requireNonNull(sourcePosition, "sourcePosition");
            Objects.requireNonNull(deliveryPosition, "deliveryPosition");
            Objects.requireNonNull(targetResource, "targetResource");
            workerStarts = List.copyOf(Objects.requireNonNull(workerStarts, "workerStarts"));
            installationMaterials = Map.copyOf(Objects.requireNonNull(
                    installationMaterials, "installationMaterials"));
            if (targetQuantity < 1 || targetQuantity > 64) {
                throw new IllegalArgumentException("Composite-03 stage target is outside bounds");
            }
        }
    }

    public record MergeRoute(
            ResourceId edgeId,
            ResourceId fromNodeId,
            ResourceId resourceId,
            BlockPos3i hopperPosition,
            BlockPos3i lockPosition,
            BlockPos3i overflowPosition) {
        public MergeRoute {
            Objects.requireNonNull(edgeId, "edgeId");
            Objects.requireNonNull(fromNodeId, "fromNodeId");
            Objects.requireNonNull(resourceId, "resourceId");
            Objects.requireNonNull(hopperPosition, "hopperPosition");
            Objects.requireNonNull(lockPosition, "lockPosition");
            Objects.requireNonNull(overflowPosition, "overflowPosition");
        }
    }

    public record CleanupReport(
            int clearedMachinePositions,
            int remainingMachinePositions,
            int clearedRouteBlocks,
            int remainingRouteBlocks) {}

    public record FailureCleanupReport(
            int completedSessionCleanups,
            int failedSessionCleanups,
            int cancelledSessionCleanups,
            int remainingMachinePositions,
            int clearedRouteBlocks,
            int remainingRouteBlocks) {
        public FailureCleanupReport {
            if (completedSessionCleanups < 0
                    || failedSessionCleanups < 0
                    || cancelledSessionCleanups < 0
                    || remainingMachinePositions < 0
                    || clearedRouteBlocks < 0
                    || remainingRouteBlocks < 0) {
                throw new IllegalArgumentException(
                        "Branch/merge failure cleanup counts are negative");
            }
        }
    }

    public static final class Session {
        private final ServerLevel level;
        private final CompositeProductionGraph graph;
        private final RootSplit split;
        private final HandlerStage wood;
        private final HandlerStage alloy;
        private final HandlerStage merge;
        private final List<MergeRoute> mergeRoutes;
        private final ExecutionMode mode;
        private final CreateV606ThreeModeExecution.TestRegion region;
        private final CompositeProductionCoordinator coordinator;
        private final List<CreateV606ThreeModeExecution.Session> sessions = new ArrayList<>();
        private final List<CreateV606ThreeModeExecution.Completed> completions = new ArrayList<>();
        private final List<CreateV606ThreeModeExecution.Session> completedSessions =
                new ArrayList<>();
        private CreateV606ThreeModeExecution.Session woodSession;
        private CreateV606ThreeModeExecution.Session alloySession;
        private CreateV606ThreeModeExecution.Session mergeSession;
        private CreateV606ThreeModeExecution.Session failedSession;
        private boolean failedSessionTerminal;
        private boolean woodCompleted;
        private boolean alloyCompleted;
        private int completedRoutes;
        private int mergeRouteIndex;
        private boolean divertingOverflow;
        private int peakConcurrentWorkers;
        private int retiredBranchWorkers;
        private Phase phase = Phase.SPLIT_WOOD;
        private TickResult terminal;

        private Session(
                ServerLevel level,
                CompositeProductionGraph graph,
                RootSplit split,
                HandlerStage wood,
                HandlerStage alloy,
                HandlerStage merge,
                List<MergeRoute> mergeRoutes,
                ExecutionMode mode,
                CreateV606ThreeModeExecution.TestRegion region) {
            this.level = level;
            this.graph = graph;
            this.split = split;
            this.wood = wood;
            this.alloy = alloy;
            this.merge = merge;
            this.mergeRoutes = mergeRoutes;
            this.mode = mode;
            this.region = region;
            this.coordinator = new CompositeProductionCoordinator(graph);
            requireAccepted(coordinator.start(split.nodeId()), split.nodeId());
            unlock(split.lockPosition());
        }

        public TickResult tick() {
            if (terminal != null) return terminal;
            requireServerThread();
            try {
                return switch (phase) {
                    case SPLIT_WOOD -> tickSplitWood();
                    case SPLIT_ALLOY -> tickSplitAlloy();
                    case BRANCHES -> tickBranches();
                    case MERGE_ROUTING -> tickMergeRoutes();
                    case MERGE_PROCESSING -> tickMerge();
                    default -> terminal;
                };
            } catch (IllegalArgumentException | IllegalStateException failure) {
                ResourceId failedNode = coordinator.snapshot().nodeStatuses().entrySet().stream()
                        .filter(entry -> entry.getValue()
                                == CompositeProductionCoordinator.NodeStatus.FAILED)
                        .map(Map.Entry::getKey)
                        .findFirst()
                        .orElseGet(this::activeNode);
                if (coordinator.snapshot().nodeStatuses().get(failedNode)
                        != CompositeProductionCoordinator.NodeStatus.FAILED) {
                    coordinator.fail(failedNode, failure.getMessage());
                }
                phase = Phase.FAILED;
                terminal = new Failed(
                        phase, failedNode, failure.getMessage(), coordinator.snapshot());
                return terminal;
            }
        }

        /** Read-only typed DAG state, for progress reporting during a long run. */
        public CompositeProductionSnapshot snapshot() {
            return coordinator.snapshot();
        }

        public CleanupReport cleanup() {
            requireServerThread();
            if (!(terminal instanceof Completed)) {
                throw new IllegalStateException("Branch/merge cleanup requires completion");
            }
            int machineCleared = 0;
            int machineRemaining = 0;
            for (CreateV606ThreeModeExecution.Session session : sessions) {
                CreateV606ThreeModeExecution.CleanupReport report = session.cleanup();
                machineCleared += report.clearedPositions();
                machineRemaining += report.remainingPositions();
            }
            int routeCleared = 0;
            int routeRemaining = 0;
            List<BlockPos3i> routeBlocks = new ArrayList<>(List.of(
                    split.hopperPosition(), split.lockPosition()));
            mergeRoutes.forEach(route -> {
                routeBlocks.add(route.hopperPosition());
                routeBlocks.add(route.lockPosition());
            });
            for (BlockPos3i position : routeBlocks) {
                BlockPos target = block(position);
                if (level.getBlockState(target).is(Blocks.HOPPER)
                        || level.getBlockState(target).is(Blocks.REDSTONE_BLOCK)) {
                    level.setBlockAndUpdate(target, Blocks.AIR.defaultBlockState());
                }
                if (level.getBlockState(target).isAir()) routeCleared++;
                else routeRemaining++;
            }
            return new CleanupReport(
                    machineCleared, machineRemaining, routeCleared, routeRemaining);
        }

        public FailureCleanupReport cleanupFailed(ResourceId reason) {
            requireServerThread();
            Objects.requireNonNull(reason, "reason");
            if (!(terminal instanceof Failed)) {
                throw new IllegalStateException(
                        "Branch/merge failure cleanup requires one terminal failure");
            }
            int completedCleanups = 0;
            int failedCleanups = 0;
            int cancelledCleanups = 0;
            int machineRemaining = 0;
            for (CreateV606ThreeModeExecution.Session session : sessions) {
                if (completedSessions.contains(session)) {
                    CreateV606ThreeModeExecution.CleanupReport report = session.cleanup();
                    completedCleanups++;
                    machineRemaining += report.remainingPositions();
                } else if (session == failedSession) {
                    if (failedSessionTerminal) {
                        CreateV606ThreeModeExecution.FailureCleanupReport report =
                                session.cleanupFailed();
                        if (report.warnings() != 0
                                || !report.sourceRestored()
                                || !report.deliveryEmpty()) {
                            throw new IllegalStateException(
                                    "Failed branch cleanup did not reconcile exact resources");
                        }
                        failedCleanups++;
                    } else {
                        CreateV606ThreeModeExecution.CancellationReport report =
                                session.cancelDetailed(reason);
                        if (!report.sourceRestored() || !report.deliveryEmpty()) {
                            throw new IllegalStateException(
                                    "Thrown branch failure did not cancel exactly");
                        }
                        cancelledCleanups++;
                    }
                } else {
                    CreateV606ThreeModeExecution.CancellationReport report =
                            session.cancelDetailed(reason);
                    if (!report.sourceRestored() || !report.deliveryEmpty()) {
                        throw new IllegalStateException(
                                "Sibling branch cancellation did not restore exact resources");
                    }
                    cancelledCleanups++;
                }
            }
            int routeCleared = 0;
            int routeRemaining = 0;
            List<BlockPos3i> routeBlocks = new ArrayList<>(List.of(
                    split.hopperPosition(), split.lockPosition()));
            mergeRoutes.forEach(route -> {
                routeBlocks.add(route.hopperPosition());
                routeBlocks.add(route.lockPosition());
            });
            for (BlockPos3i position : routeBlocks) {
                BlockPos target = block(position);
                if (level.getBlockState(target).is(Blocks.HOPPER)
                        || level.getBlockState(target).is(Blocks.REDSTONE_BLOCK)) {
                    level.setBlockAndUpdate(target, Blocks.AIR.defaultBlockState());
                }
                if (level.getBlockState(target).isAir()) routeCleared++;
                else routeRemaining++;
            }
            return new FailureCleanupReport(
                    completedCleanups, failedCleanups, cancelledCleanups,
                    machineRemaining, routeCleared, routeRemaining);
        }

        private TickResult tickSplitWood() {
            assertAllowed(split.sourceChest(), Map.of(
                    split.woodResource(), 1L,
                    split.alloyBaseResource(), 1L,
                    split.alloyAdjunctResource(), 1L), true);
            assertAllowed(wood.sourcePosition(), mergeMaterials(
                    wood.installationMaterials(), Map.of(split.woodResource(), 1L)), true);
            assertAllowedHopper(split.hopperPosition(), Set.of(
                    split.woodResource(), split.alloyBaseResource(), split.alloyAdjunctResource()));
            long delivered = routeDelivered(level, wood.sourcePosition(),
                    split.woodResource(), wood.installationMaterials());
            if (delivered < 1) return progress();
            lock(split.lockPosition());
            setFacing(split.hopperPosition(), direction(
                    split.hopperPosition(), alloy.sourcePosition()));
            unlock(split.lockPosition());
            completedRoutes++;
            phase = Phase.SPLIT_ALLOY;
            return progress();
        }

        private TickResult tickSplitAlloy() {
            assertAllowed(split.sourceChest(), Map.of(
                    split.alloyBaseResource(), 1L,
                    split.alloyAdjunctResource(), 1L), true);
            assertAllowed(alloy.sourcePosition(), mergeMaterials(
                    alloy.installationMaterials(), Map.of(
                            split.alloyBaseResource(), 1L,
                            split.alloyAdjunctResource(), 1L)), true);
            assertAllowedHopper(split.hopperPosition(), Set.of(
                    split.alloyBaseResource(), split.alloyAdjunctResource()));
            boolean ready = routeDelivered(level, alloy.sourcePosition(),
                    split.alloyBaseResource(), alloy.installationMaterials()) == 1
                    && routeDelivered(level, alloy.sourcePosition(),
                            split.alloyAdjunctResource(), alloy.installationMaterials()) == 1
                    && totalItems(level, split.sourceChest()) == 0
                    && totalItemsHopper(level, split.hopperPosition()) == 0;
            if (!ready) return progress();
            lock(split.lockPosition());
            requireAccepted(coordinator.complete(split.nodeId(), Map.of(
                    split.woodResource(), 1L,
                    split.alloyBaseResource(), 1L,
                    split.alloyAdjunctResource(), 1L)), split.nodeId());
            woodSession = startStage(wood);
            alloySession = startStage(alloy);
            peakConcurrentWorkers = Math.max(
                    peakConcurrentWorkers,
                    woodSession.workerIds().size() + alloySession.workerIds().size());
            completedRoutes++;
            phase = Phase.BRANCHES;
            return progress();
        }

        private TickResult tickBranches() {
            if (!woodCompleted) woodCompleted = tickBranch(wood, woodSession);
            if (!alloyCompleted) alloyCompleted = tickBranch(alloy, alloySession);
            if (!woodCompleted || !alloyCompleted) return progress();
            retiredBranchWorkers += woodSession.retireWorkersAfterCompletion();
            retiredBranchWorkers += alloySession.retireWorkersAfterCompletion();
            requireAccepted(coordinator.start(merge.nodeId()), merge.nodeId());
            unlock(mergeRoutes.get(0).lockPosition());
            phase = Phase.MERGE_ROUTING;
            return progress();
        }

        private boolean tickBranch(
                HandlerStage stage, CreateV606ThreeModeExecution.Session session) {
            CreateV606ThreeModeExecution.TickResult result;
            try {
                result = session.tick();
            } catch (IllegalArgumentException | IllegalStateException failure) {
                failedSession = session;
                failedSessionTerminal = false;
                coordinator.fail(stage.nodeId(), failure.getMessage());
                throw new IllegalStateException(
                        "Independent branch failed before merge: " + stage.nodeId()
                                + " detail=" + failure.getMessage(), failure);
            }
            if (result instanceof CreateV606ThreeModeExecution.Failed failed) {
                failedSession = session;
                failedSessionTerminal = true;
                coordinator.fail(stage.nodeId(), failed.detail());
                throw new IllegalStateException(
                        "Independent branch failed before merge: " + stage.nodeId()
                                + " detail=" + failed.detail());
            }
            if (result instanceof CreateV606ThreeModeExecution.Progress) return false;
            CreateV606ThreeModeExecution.Completed completed =
                    (CreateV606ThreeModeExecution.Completed) result;
            if (count(level, stage.deliveryPosition(), stage.targetResource())
                    != stage.targetQuantity()) {
                throw new IllegalStateException("Branch delivery readback differs from exact output");
            }
            requireAccepted(coordinator.complete(
                    stage.nodeId(), Map.of(stage.targetResource(), 1L)), stage.nodeId());
            completions.add(completed);
            completedSessions.add(session);
            return true;
        }

        private TickResult tickMergeRoutes() {
            Map<ResourceId, Long> expectedMerge = mergeMaterials(
                    merge.installationMaterials(), Map.of(
                            wood.targetResource(), 1L, alloy.targetResource(), 1L));
            assertAllowed(merge.sourcePosition(), expectedMerge, true);
            MergeRoute route = mergeRoutes.get(mergeRouteIndex);
            HandlerStage from = route.fromNodeId().equals(wood.nodeId()) ? wood : alloy;
            assertAllowedHopper(route.hopperPosition(), Set.of(route.resourceId()));
            assertAllowed(from.deliveryPosition(),
                    Map.of(route.resourceId(), from.targetQuantity()), true);
            if (!divertingOverflow) {
                long destination = routeDelivered(level, merge.sourcePosition(),
                        route.resourceId(), merge.installationMaterials());
                if (destination < 1) return progress();
                if (destination > 1) {
                    throw new IllegalStateException("Merge route exceeded its exact reservation");
                }
                lock(route.lockPosition());
                long remaining = count(level, from.deliveryPosition(), route.resourceId())
                        + countHopper(level, route.hopperPosition(), route.resourceId());
                long expectedOverflow = from.targetQuantity() - 1;
                if (remaining != expectedOverflow) {
                    throw new IllegalStateException("Merge route conservation changed before salvage");
                }
                if (expectedOverflow > 0) {
                    setFacing(route.hopperPosition(), direction(
                            route.hopperPosition(), route.overflowPosition()));
                    unlock(route.lockPosition());
                    divertingOverflow = true;
                    return progress();
                }
            }
            long expectedOverflow = from.targetQuantity() - 1;
            if (totalItems(level, from.deliveryPosition()) != 0
                    || totalItemsHopper(level, route.hopperPosition()) != 0
                    || count(level, route.overflowPosition(), route.resourceId())
                            != expectedOverflow) {
                return progress();
            }
            lock(route.lockPosition());
            completedRoutes++;
            mergeRouteIndex++;
            divertingOverflow = false;
            if (mergeRouteIndex < mergeRoutes.size()) {
                unlock(mergeRoutes.get(mergeRouteIndex).lockPosition());
                return progress();
            }
            mergeSession = startStage(merge, true);
            phase = Phase.MERGE_PROCESSING;
            return progress();
        }

        private TickResult tickMerge() {
            CreateV606ThreeModeExecution.TickResult result = mergeSession.tick();
            if (result instanceof CreateV606ThreeModeExecution.Failed failed) {
                failedSession = mergeSession;
                failedSessionTerminal = true;
                coordinator.fail(merge.nodeId(), failed.detail());
                throw new IllegalStateException("Merge handler failed: " + failed.detail());
            }
            if (result instanceof CreateV606ThreeModeExecution.Progress) return progress();
            CreateV606ThreeModeExecution.Completed completed =
                    (CreateV606ThreeModeExecution.Completed) result;
            long output = count(level, merge.deliveryPosition(), merge.targetResource());
            if (output != 1) {
                throw new IllegalStateException("Merge output differs from exact target");
            }
            requireAccepted(coordinator.complete(
                    merge.nodeId(), Map.of(merge.targetResource(), output)), merge.nodeId());
            completions.add(completed);
            completedSessions.add(mergeSession);
            phase = Phase.COMPLETED;
            terminal = new Completed(
                    graph.graphId(), graph.fingerprint(), mode, completions,
                    coordinator.snapshot(), merge.targetResource(), output, completedRoutes,
                    peakConcurrentWorkers, retiredBranchWorkers);
            return terminal;
        }

        private CreateV606ThreeModeExecution.Session startStage(HandlerStage stage) {
            return startStage(stage, false);
        }

        private CreateV606ThreeModeExecution.Session startStage(
                HandlerStage stage, boolean coordinatorAlreadyStarted) {
            if (!coordinatorAlreadyStarted) {
                requireAccepted(coordinator.start(stage.nodeId()), stage.nodeId());
            }
            CreateV606ThreeModeExecution.StartResult start = CreateV606ThreeModeExecution.start(
                    level, stage.ready(), stage.runtime(), stage.sourcePosition(),
                    stage.deliveryPosition(), mode, region,
                    mode == ExecutionMode.DIRECT ? List.of() : stage.workerStarts(),
                    null, stage.installationMaterials());
            if (!(start instanceof CreateV606ThreeModeExecution.Started value)) {
                throw new IllegalStateException("Branch/merge stage did not start: " + start);
            }
            sessions.add(value.session());
            peakConcurrentWorkers = Math.max(
                    peakConcurrentWorkers, value.session().workerIds().size());
            return value.session();
        }

        private Progress progress() {
            return new Progress(phase, woodCompleted, alloyCompleted, completedRoutes);
        }

        private ResourceId activeNode() {
            return switch (phase) {
                case SPLIT_WOOD, SPLIT_ALLOY -> split.nodeId();
                case BRANCHES -> woodCompleted ? alloy.nodeId() : wood.nodeId();
                default -> merge.nodeId();
            };
        }

        private void requireServerThread() {
            if (!level.getServer().isSameThread()) {
                throw new IllegalStateException("Branch/merge execution left the server thread");
            }
        }

        private void unlock(BlockPos3i lock) {
            level.setBlockAndUpdate(block(lock), Blocks.AIR.defaultBlockState());
            if (!level.getBlockState(block(lock)).isAir()) {
                throw new IllegalStateException("Physical route lock did not release");
            }
        }

        private void lock(BlockPos3i lock) {
            level.setBlockAndUpdate(block(lock), Blocks.REDSTONE_BLOCK.defaultBlockState());
            if (!level.getBlockState(block(lock)).is(Blocks.REDSTONE_BLOCK)) {
                throw new IllegalStateException("Physical route lock did not engage");
            }
        }

        private void setFacing(BlockPos3i hopper, Direction facing) {
            level.setBlockAndUpdate(block(hopper),
                    level.getBlockState(block(hopper)).setValue(HopperBlock.FACING, facing));
        }

        private void assertAllowed(
                BlockPos3i chest, Map<ResourceId, Long> maxima, boolean allowPartial) {
            Map<ResourceId, Long> observed = inventory(level, chest);
            for (Map.Entry<ResourceId, Long> entry : observed.entrySet()) {
                long maximum = maxima.getOrDefault(entry.getKey(), -1L);
                if (maximum < 0 || entry.getValue() > maximum) {
                    throw new IllegalStateException(
                            "Typed route contamination/backpressure at " + chest
                                    + " resource=" + entry.getKey()
                                    + " quantity=" + entry.getValue());
                }
            }
            if (!allowPartial && !observed.equals(maxima)) {
                throw new IllegalStateException("Typed route lacks exact material at " + chest);
            }
        }

        private void assertAllowedHopper(BlockPos3i hopper, Set<ResourceId> allowed) {
            HopperBlockEntity inventory = (HopperBlockEntity) level.getBlockEntity(block(hopper));
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (stack.isEmpty()) continue;
                if (stack.hasTag() || !allowed.contains(resource(stack))) {
                    throw new IllegalStateException(
                            "Typed Hopper filter refused contamination at " + hopper);
                }
            }
        }
    }

    private static void validateTopology(
            CompositeProductionGraph graph,
            RootSplit split,
            HandlerStage wood,
            HandlerStage alloy,
            HandlerStage merge,
            List<MergeRoute> routes) {
        if (graph.shape() != CompositeProductionGraph.Shape.BRANCH_MERGE
                || graph.nodes().size() != 4 || graph.edges().size() != 5
                || routes.size() != 2) {
            throw new IllegalArgumentException("Composite-03 requires the exact four-node DAG");
        }
        for (HandlerStage stage : List.of(wood, alloy, merge)) {
            ResourceId plannedTarget = stage.ready().physicalPlan().candidate().boundPlan().graph()
                    .logicalPlan().candidate().goal().target();
            if (!plannedTarget.equals(stage.targetResource())) {
                throw new IllegalArgumentException("Stage target differs from verified plan");
            }
            graph.node(stage.nodeId());
        }
        graph.node(split.nodeId());
        requireEdge(graph, split.nodeId(), wood.nodeId(), split.woodResource());
        requireEdge(graph, split.nodeId(), alloy.nodeId(), split.alloyBaseResource());
        requireEdge(graph, split.nodeId(), alloy.nodeId(), split.alloyAdjunctResource());
        requireEdge(graph, wood.nodeId(), merge.nodeId(), wood.targetResource());
        requireEdge(graph, alloy.nodeId(), merge.nodeId(), alloy.targetResource());
        for (MergeRoute route : routes) {
            if (!route.fromNodeId().equals(wood.nodeId())
                    && !route.fromNodeId().equals(alloy.nodeId())) {
                throw new IllegalArgumentException("Merge route has an unknown branch");
            }
            CompositeProductionGraph.MaterialEdge edge = graph.edges().stream()
                    .filter(value -> value.edgeId().equals(route.edgeId())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown merge edge"));
            if (!edge.producerNodeId().equals(route.fromNodeId())
                    || !edge.consumerNodeId().equals(merge.nodeId())
                    || !edge.resourceId().equals(route.resourceId())
                    || edge.quantity() != 1 || edge.capacity() < 1) {
                throw new IllegalArgumentException("Merge route differs from admitted graph edge");
            }
        }
    }

    private static void validatePhysical(
            ServerLevel level,
            RootSplit split,
            HandlerStage wood,
            HandlerStage alloy,
            HandlerStage merge,
            List<MergeRoute> routes,
            CreateV606ThreeModeExecution.TestRegion region) {
        List<BlockPos3i> chests = List.of(
                split.sourceChest(), wood.sourcePosition(), wood.deliveryPosition(),
                alloy.sourcePosition(), alloy.deliveryPosition(),
                merge.sourcePosition(), merge.deliveryPosition());
        for (BlockPos3i chest : chests) {
            requireChest(level, chest);
            if (!region.contains(chest)) {
                throw new IllegalArgumentException("Composite-03 chest is outside the bounded region");
            }
        }
        requireHopperAndLock(level, split.hopperPosition(), split.lockPosition(), region);
        if (!block(split.hopperPosition()).above().equals(block(split.sourceChest()))
                || direction(split.hopperPosition(), wood.sourcePosition())
                        != level.getBlockState(block(split.hopperPosition()))
                                .getValue(HopperBlock.FACING)
                || !adjacent(split.hopperPosition(), alloy.sourcePosition())) {
            throw new IllegalArgumentException("Root splitter geometry is invalid");
        }
        Map<ResourceId, Long> exactRaw = Map.of(
                split.woodResource(), split.woodQuantity(),
                split.alloyBaseResource(), split.alloyBaseQuantity(),
                split.alloyAdjunctResource(), split.alloyAdjunctQuantity());
        if (!inventory(level, split.sourceChest()).equals(exactRaw)
                || !inventory(level, wood.sourcePosition()).equals(
                        wood.installationMaterials())
                || !inventory(level, alloy.sourcePosition()).equals(
                        alloy.installationMaterials())
                || !inventory(level, merge.sourcePosition()).equals(
                        merge.installationMaterials())) {
            throw new IllegalArgumentException(
                    "Root split input or exact branch/merge installation escrow changed");
        }
        for (MergeRoute route : routes) {
            requireHopperAndLock(level, route.hopperPosition(), route.lockPosition(), region);
            requireChest(level, route.overflowPosition());
            HandlerStage from = route.fromNodeId().equals(wood.nodeId()) ? wood : alloy;
            if (!block(route.hopperPosition()).above().equals(block(from.deliveryPosition()))
                    || direction(route.hopperPosition(), merge.sourcePosition())
                            != level.getBlockState(block(route.hopperPosition()))
                                    .getValue(HopperBlock.FACING)
                    || !region.contains(route.overflowPosition())
                    || !adjacent(route.hopperPosition(), route.overflowPosition())
                    || totalItems(level, route.overflowPosition()) != 0) {
                throw new IllegalArgumentException("Merge Hopper geometry is invalid");
            }
        }
    }

    private static void requireEdge(
            CompositeProductionGraph graph,
            ResourceId producer,
            ResourceId consumer,
            ResourceId resource) {
        boolean found = graph.edges().stream().anyMatch(edge ->
                edge.producerNodeId().equals(producer)
                        && edge.consumerNodeId().equals(consumer)
                        && edge.resourceId().equals(resource)
                        && edge.quantity() == 1 && edge.capacity() >= 1);
        if (!found) throw new IllegalArgumentException("Composite-03 graph edge is missing");
    }

    private static void requireHopperAndLock(
            ServerLevel level,
            BlockPos3i hopper,
            BlockPos3i lock,
            CreateV606ThreeModeExecution.TestRegion region) {
        if (!region.contains(hopper) || !region.contains(lock)
                || !(level.getBlockEntity(block(hopper)) instanceof HopperBlockEntity)
                || !level.getBlockState(block(lock)).is(Blocks.REDSTONE_BLOCK)
                || !adjacent(hopper, lock)) {
            throw new IllegalArgumentException("Verified Hopper route or lock is invalid");
        }
    }

    private static void requireChest(ServerLevel level, BlockPos3i position) {
        if (!(level.getBlockEntity(block(position)) instanceof ChestBlockEntity)) {
            throw new IllegalArgumentException("Composite-03 boundary is not a chest at " + position);
        }
    }

    private static void requireAccepted(
            CompositeProductionCoordinator.Transition transition, ResourceId nodeId) {
        if (!transition.accepted()) {
            throw new IllegalStateException(
                    "Composite node transition refused for " + nodeId + ": " + transition.detail());
        }
    }

    private static Map<ResourceId, Long> inventory(ServerLevel level, BlockPos3i position) {
        ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(block(position));
        Map<ResourceId, Long> result = new LinkedHashMap<>();
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack stack = chest.getItem(slot);
            if (stack.isEmpty()) continue;
            if (stack.hasTag()) {
                throw new IllegalStateException("Typed route refuses unknown NBT at " + position);
            }
            result.merge(resource(stack), (long) stack.getCount(), Math::addExact);
        }
        return Map.copyOf(result);
    }

    private static Map<ResourceId, Long> mergeMaterials(
            Map<ResourceId, Long> left, Map<ResourceId, Long> right) {
        LinkedHashMap<ResourceId, Long> result = new LinkedHashMap<>(left);
        right.forEach((resource, quantity) ->
                result.merge(resource, quantity, Math::addExact));
        return Map.copyOf(result);
    }

    /**
     * How much of a resource a physical route has actually delivered into a stage chest.
     *
     * <p>A branch chest is not empty when the split runs: it already holds that stage's own
     * installation materials. Reading the chest's absolute count assumed those two sets never
     * overlap, and they do now — a survival-powered stage bills its own {@code create:shaft},
     * so the alloy branch held three where the split demanded exactly one and the run waited
     * for a number it could never reach. The allowance beside each of these checks was always
     * computed as installation plus delivery; this makes the readiness test agree with it.</p>
     *
     * <p>Deliberately signed: a stage that has already consumed its installation materials
     * reads negative and stays not-ready, which is the same fail-closed answer as before.</p>
     */
    private static long routeDelivered(
            ServerLevel level,
            BlockPos3i position,
            ResourceId resource,
            Map<ResourceId, Long> installationMaterials) {
        return count(level, position, resource)
                - installationMaterials.getOrDefault(resource, 0L);
    }

    private static long count(ServerLevel level, BlockPos3i position, ResourceId resource) {
        return inventory(level, position).getOrDefault(resource, 0L);
    }

    private static int totalItems(ServerLevel level, BlockPos3i position) {
        return Math.toIntExact(inventory(level, position).values().stream()
                .mapToLong(Long::longValue).sum());
    }

    private static int totalItemsHopper(ServerLevel level, BlockPos3i position) {
        HopperBlockEntity hopper = (HopperBlockEntity) level.getBlockEntity(block(position));
        int result = 0;
        for (int slot = 0; slot < hopper.getContainerSize(); slot++) {
            result = Math.addExact(result, hopper.getItem(slot).getCount());
        }
        return result;
    }

    private static long countHopper(
            ServerLevel level, BlockPos3i position, ResourceId resource) {
        HopperBlockEntity hopper = (HopperBlockEntity) level.getBlockEntity(block(position));
        long result = 0;
        for (int slot = 0; slot < hopper.getContainerSize(); slot++) {
            ItemStack stack = hopper.getItem(slot);
            if (!stack.isEmpty() && resource(stack).equals(resource)) {
                result = Math.addExact(result, stack.getCount());
            }
        }
        return result;
    }

    private static ResourceId resource(ItemStack stack) {
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (key == null || stack.getItem() == net.minecraft.world.item.Items.AIR) {
            throw new IllegalStateException("Physical route observed an unregistered item");
        }
        return ResourceId.parse(key.toString());
    }

    private static boolean adjacent(BlockPos3i from, BlockPos3i to) {
        return Math.abs(from.x() - to.x()) + Math.abs(from.y() - to.y())
                + Math.abs(from.z() - to.z()) == 1;
    }

    private static Direction direction(BlockPos3i from, BlockPos3i to) {
        BlockPos start = block(from);
        BlockPos target = block(to);
        for (Direction direction : Direction.values()) {
            if (start.relative(direction).equals(target)) return direction;
        }
        throw new IllegalArgumentException("Physical route boundary is not adjacent");
    }

    private static BlockPos block(BlockPos3i position) {
        return new BlockPos(position.x(), position.y(), position.z());
    }
}
