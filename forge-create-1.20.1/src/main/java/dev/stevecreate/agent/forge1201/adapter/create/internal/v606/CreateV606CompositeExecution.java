package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import dev.stevecreate.agent.adapter.api.RuntimeFingerprint;
import dev.stevecreate.agent.core.execution.composite.CompositeProductionCoordinator;
import dev.stevecreate.agent.core.execution.composite.RouteBackpressure;
import dev.stevecreate.agent.core.execution.composite.CompositeProductionGraph;
import dev.stevecreate.agent.core.execution.composite.CompositeProductionSnapshot;
import dev.stevecreate.agent.core.execution.construction.ExecutionMode;
import dev.stevecreate.agent.core.execution.readiness.ExecutionReadinessFailureCode;
import dev.stevecreate.agent.core.execution.readiness.ExecutionReadyPlan;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

/**
 * Bounded v606 multi-stage coordinator over real Create handlers and locked vanilla-hopper routes.
 *
 * <p>The coordinator never inserts an intermediate. A completed handler must leave the exact
 * output in its delivery chest, then a preverified locked hopper is released and authoritative
 * chest readback must prove movement before the next handler can start.</p>
 */
public final class CreateV606CompositeExecution {
    private CreateV606CompositeExecution() {}

    public static StartResult start(
            ServerLevel level,
            CompositeProductionGraph graph,
            List<Stage> stages,
            List<HopperRoute> routes,
            ExecutionMode mode,
            CreateV606ThreeModeExecution.TestRegion region) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(graph, "graph");
        stages = List.copyOf(Objects.requireNonNull(stages, "stages"));
        routes = List.copyOf(Objects.requireNonNull(routes, "routes"));
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(region, "region");
        if (!level.getServer().isSameThread()) {
            return new Rejected(ExecutionReadinessFailureCode.EXECUTION_NOT_READY,
                    "Composite execution must start on the authoritative server thread");
        }
        if (graph.shape() != CompositeProductionGraph.Shape.LINEAR_CHAIN
                || stages.size() != graph.nodes().size()
                || routes.size() != stages.size() - 1) {
            return new Rejected(ExecutionReadinessFailureCode.EXECUTION_NOT_READY,
                    "Composite-01 requires one admitted linear graph and one route per edge");
        }
        try {
            validateTopology(graph, stages, routes);
            validatePhysicalRoutes(level, stages, routes, region);
            return new Started(new Session(level, graph, stages, routes, mode, region));
        } catch (IllegalArgumentException | IllegalStateException failure) {
            return new Rejected(ExecutionReadinessFailureCode.EXECUTION_NOT_READY,
                    "Composite execution rejected: " + failure.getMessage());
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
            ResourceId nodeId, Phase phase, int completedStages, int completedRoutes)
            implements TickResult {
        public Progress {
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(phase, "phase");
            if (completedStages < 0 || completedRoutes < 0) {
                throw new IllegalArgumentException("Composite progress counters are negative");
            }
        }
    }

    public record Completed(
            ResourceId graphId,
            String graphFingerprint,
            ExecutionMode mode,
            List<CreateV606ThreeModeExecution.Completed> stages,
            List<RouteEvidence> routes,
            CompositeProductionSnapshot coordinatorSnapshot,
            int peakConcurrentWorkers,
            int retiredWorkerCount)
            implements TickResult {
        public Completed {
            Objects.requireNonNull(graphId, "graphId");
            Objects.requireNonNull(graphFingerprint, "graphFingerprint");
            Objects.requireNonNull(mode, "mode");
            stages = List.copyOf(stages);
            routes = List.copyOf(routes);
            Objects.requireNonNull(coordinatorSnapshot, "coordinatorSnapshot");
            // The retirement count was written as the literal 6, which is what a
            // three-stage run produces: completion evidence is built before the final
            // stage retires, so exactly stages-1 fleets of three have been let go. Stated
            // as that formula it is the same assertion on the verified path — 3*(3-1)==6
            // — and it stays a real assertion for a two-stage chain instead of a second
            // hand-picked number nobody has checked.
            int expectedRetired = 3 * (stages.size() - 1);
            // One stage is allowed, and the arithmetic below was already consistent with
            // it: a single stage has no routes (0 == 1-1) and retires no fleet before
            // completion (0 == 3*(1-1)). The bound was the only thing refusing it, and
            // refusing it kept single-machine products off this path entirely.
            if (stages.isEmpty() || routes.size() != stages.size() - 1
                    || stages.stream().anyMatch(value -> value.mode() != mode)
                    || peakConcurrentWorkers < 0 || retiredWorkerCount < 0
                    || (mode == ExecutionMode.DIRECT
                            && (peakConcurrentWorkers != 0 || retiredWorkerCount != 0))
                    || (mode != ExecutionMode.DIRECT
                            && (peakConcurrentWorkers != 3
                                    || retiredWorkerCount != expectedRetired))) {
                throw new IllegalArgumentException("Composite completion evidence is incomplete");
            }
        }
    }

    public record Failed(ResourceId nodeId, Phase phase, String detail) implements TickResult {
        public Failed {
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(detail, "detail");
        }
    }

    public enum Phase { PROCESSING, ROUTING, COMPLETED, FAILED, CANCELLED }

    public record Stage(
            ResourceId nodeId,
            ExecutionReadyPlan ready,
            RuntimeFingerprint runtime,
            BlockPos3i sourcePosition,
            BlockPos3i deliveryPosition,
            ResourceId targetResource,
            long targetQuantity,
            List<BlockPos3i> workerStarts,
            Map<ResourceId, Long> installationMaterials) {
        public Stage {
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(ready, "ready");
            Objects.requireNonNull(runtime, "runtime");
            Objects.requireNonNull(sourcePosition, "sourcePosition");
            Objects.requireNonNull(deliveryPosition, "deliveryPosition");
            Objects.requireNonNull(targetResource, "targetResource");
            if (targetQuantity < 1 || targetQuantity > 64) {
                throw new IllegalArgumentException("Stage target quantity must be between 1 and 64");
            }
            workerStarts = List.copyOf(Objects.requireNonNull(workerStarts, "workerStarts"));
            installationMaterials = Map.copyOf(Objects.requireNonNull(
                    installationMaterials, "installationMaterials"));
        }
    }

    public record HopperRoute(
            ResourceId edgeId,
            ResourceId fromNodeId,
            ResourceId toNodeId,
            ResourceId resourceId,
            long quantity,
            BlockPos3i hopperPosition,
            BlockPos3i lockPosition,
            Optional<BlockPos3i> overflowPosition) {
        public HopperRoute {
            Objects.requireNonNull(edgeId, "edgeId");
            Objects.requireNonNull(fromNodeId, "fromNodeId");
            Objects.requireNonNull(toNodeId, "toNodeId");
            Objects.requireNonNull(resourceId, "resourceId");
            Objects.requireNonNull(hopperPosition, "hopperPosition");
            Objects.requireNonNull(lockPosition, "lockPosition");
            overflowPosition = Objects.requireNonNull(overflowPosition, "overflowPosition");
            if (quantity < 1 || quantity > 64) {
                throw new IllegalArgumentException("Route quantity must be between 1 and 64");
            }
        }
    }

    public record RouteEvidence(
            ResourceId edgeId,
            ResourceId resourceId,
            BlockPos3i fromChest,
            BlockPos3i hopperPosition,
            BlockPos3i toChest,
            Optional<BlockPos3i> overflowChest,
            long expectedQuantity,
            long observedSourceBefore,
            long observedSourceAfter,
            long observedDestinationBefore,
            long observedDestinationAfter,
            long observedOverflowAfter,
            long observedTicks) {
        public RouteEvidence {
            Objects.requireNonNull(edgeId, "edgeId");
            Objects.requireNonNull(resourceId, "resourceId");
            Objects.requireNonNull(fromChest, "fromChest");
            Objects.requireNonNull(hopperPosition, "hopperPosition");
            Objects.requireNonNull(toChest, "toChest");
            overflowChest = Objects.requireNonNull(overflowChest, "overflowChest");
            if (expectedQuantity < 1 || observedSourceBefore < expectedQuantity
                    || observedSourceAfter != 0
                    || observedDestinationBefore != 0
                    || observedDestinationAfter != expectedQuantity
                    || observedOverflowAfter != observedSourceBefore - expectedQuantity
                    || (observedOverflowAfter > 0 && overflowChest.isEmpty())
                    || observedTicks < 1) {
                throw new IllegalArgumentException("Physical hopper route evidence is incomplete");
            }
        }
    }

    public record CleanupReport(
            int plannedMachinePositions,
            int clearedMachinePositions,
            int remainingMachinePositions,
            int clearedRouteBlocks,
            int remainingRouteBlocks) {}

    public record CancellationReport(
            ResourceId graphId,
            ResourceId activeNodeId,
            CompositeProductionSnapshot coordinatorSnapshot,
            CreateV606ThreeModeExecution.CancellationReport activeStage,
            CleanupReport cleanup) {
        public CancellationReport {
            Objects.requireNonNull(graphId, "graphId");
            Objects.requireNonNull(activeNodeId, "activeNodeId");
            Objects.requireNonNull(coordinatorSnapshot, "coordinatorSnapshot");
            Objects.requireNonNull(activeStage, "activeStage");
            Objects.requireNonNull(cleanup, "cleanup");
        }
    }

    public static final class Session {
        private final ServerLevel level;
        private final CompositeProductionGraph graph;
        private final List<Stage> stages;
        private final List<HopperRoute> routes;
        private final ExecutionMode mode;
        private final CreateV606ThreeModeExecution.TestRegion region;
        private final CompositeProductionCoordinator coordinator;
        private final List<CreateV606ThreeModeExecution.Session> sessions = new ArrayList<>();
        private final List<CreateV606ThreeModeExecution.Completed> completions = new ArrayList<>();
        private final List<RouteEvidence> routeEvidence = new ArrayList<>();
        private CreateV606ThreeModeExecution.Session active;
        private int stageIndex;
        private Phase phase = Phase.PROCESSING;
        private long routeStartedTick;
        private long routeSourceBefore;
        private long routeDestinationBefore;
        private long routeOverflowBefore;
        private boolean divertingOverflow;
        private int peakConcurrentWorkers;
        private int retiredWorkerCount;
        private long routeLastDelivered = -1;
        private int routeStillTicks;
        private TickResult terminal;

        private Session(
                ServerLevel level,
                CompositeProductionGraph graph,
                List<Stage> stages,
                List<HopperRoute> routes,
                ExecutionMode mode,
                CreateV606ThreeModeExecution.TestRegion region) {
            this.level = level;
            this.graph = graph;
            this.stages = stages;
            this.routes = routes;
            this.mode = mode;
            this.region = region;
            this.coordinator = new CompositeProductionCoordinator(graph);
            startStage();
        }

        public TickResult tick() {
            if (terminal != null) return terminal;
            requireServerThread();
            Stage stage = stages.get(stageIndex);
            if (phase == Phase.PROCESSING) {
                CreateV606ThreeModeExecution.TickResult result = active.tick();
                if (result instanceof CreateV606ThreeModeExecution.Failed failed) {
                    coordinator.fail(stage.nodeId(), failed.detail());
                    phase = Phase.FAILED;
                    terminal = new Failed(stage.nodeId(), phase, failed.detail());
                    return terminal;
                }
                if (result instanceof CreateV606ThreeModeExecution.Progress) {
                    return progress();
                }
                CreateV606ThreeModeExecution.Completed completed =
                        (CreateV606ThreeModeExecution.Completed) result;
                long output = count(level, stage.deliveryPosition(), stage.targetResource());
                if (output < stage.targetQuantity()) {
                    phase = Phase.FAILED;
                    terminal = new Failed(stage.nodeId(), phase,
                            "Authoritative delivery chest lacks the exact stage output");
                    return terminal;
                }
                CompositeProductionCoordinator.Transition committed = coordinator.complete(
                        stage.nodeId(), Map.of(stage.targetResource(), output));
                if (!committed.accepted()) {
                    phase = Phase.FAILED;
                    terminal = new Failed(stage.nodeId(), phase, committed.detail());
                    return terminal;
                }
                completions.add(completed);
                if (stageIndex == stages.size() - 1) {
                    phase = Phase.COMPLETED;
                    terminal = new Completed(
                            graph.graphId(), graph.fingerprint(), mode,
                            completions, routeEvidence, coordinator.snapshot(),
                            peakConcurrentWorkers, retiredWorkerCount);
                    return terminal;
                }
                retiredWorkerCount += active.retireWorkersAfterCompletion();
                beginRoute();
                return progress();
            }
            HopperRoute route = routes.get(stageIndex);
            Stage destination = stages.get(stageIndex + 1);
            long source = count(level, stage.deliveryPosition(), route.resourceId());
            long target = count(level, destination.sourcePosition(), route.resourceId());
            long inHopper = countHopper(level, route.hopperPosition(), route.resourceId());
            if (divertingOverflow) {
                BlockPos3i overflow = route.overflowPosition().orElseThrow();
                long overflowNow = count(level, overflow, route.resourceId());
                long expectedOverflow = routeSourceBefore - route.quantity();
                if (source != 0 || inHopper != 0
                        || overflowNow - routeOverflowBefore < expectedOverflow) {
                    return progress();
                }
                if (overflowNow - routeOverflowBefore != expectedOverflow) {
                    phase = Phase.FAILED;
                    terminal = new Failed(destination.nodeId(), phase,
                            "Physical salvage route exceeded its exact excess quantity");
                    return terminal;
                }
                lockRoute(route);
                finishRoute(route, stage, destination, source, target, expectedOverflow);
                return progress();
            }
            if (target < route.quantity()) {
                // A route that cannot finish used to report progress until the whole
                // session timed out, which told an operator only that something was slow.
                // Stillness alone is not evidence — a hopper moves on its own schedule —
                // so this asks for stillness AND a destination with nowhere to put more.
                // Judged against the previous reading, then the reading is updated.
                // Updating first would make the "still arriving" branch unreachable from
                // here, leaving a limb of the decision that no real caller exercises.
                RouteBackpressure.Verdict verdict = RouteBackpressure.judge(
                        target, routeLastDelivered, routeStillTicks,
                        hasRoomFor(level, destination.sourcePosition(), route.resourceId()));
                routeStillTicks = target == routeLastDelivered ? routeStillTicks + 1 : 0;
                routeLastDelivered = target;
                if (verdict == RouteBackpressure.Verdict.BLOCKED) {
                    phase = Phase.FAILED;
                    terminal = new Failed(destination.nodeId(), phase,
                            "Physical route is blocked: the destination has no room for "
                                    + route.resourceId() + " and has taken nothing for "
                                    + routeStillTicks + " ticks (edge=" + route.edgeId()
                                    + " delivered=" + target + "/" + route.quantity() + ")");
                    return terminal;
                }
                return progress();
            }
            if (target > route.quantity()) {
                phase = Phase.FAILED;
                terminal = new Failed(destination.nodeId(), phase,
                        "Physical route exceeded its exact material reservation: edge="
                                + route.edgeId() + " sourceBefore=" + routeSourceBefore
                                + " sourceNow=" + source + " destinationNow=" + target
                                + " expected=" + route.quantity());
                return terminal;
            }
            lockRoute(route);
            long excess = Math.addExact(source, inHopper);
            if (excess > 0) {
                BlockPos3i overflow = route.overflowPosition().orElse(null);
                if (overflow == null || excess != routeSourceBefore - route.quantity()) {
                    phase = Phase.FAILED;
                    terminal = new Failed(destination.nodeId(), phase,
                            "Physical route lacks an exact salvage path for excess=" + excess);
                    return terminal;
                }
                routeOverflowBefore = count(level, overflow, route.resourceId());
                Direction overflowFacing = adjacentDirection(
                        block(route.hopperPosition()), block(overflow));
                level.setBlockAndUpdate(block(route.hopperPosition()),
                        level.getBlockState(block(route.hopperPosition()))
                                .setValue(HopperBlock.FACING, overflowFacing));
                level.setBlockAndUpdate(block(route.lockPosition()), Blocks.AIR.defaultBlockState());
                divertingOverflow = true;
                return progress();
            }
            finishRoute(route, stage, destination, source, target, 0);
            return progress();
        }

        private void finishRoute(
                HopperRoute route,
                Stage stage,
                Stage destination,
                long source,
                long target,
                long overflow) {
            routeEvidence.add(new RouteEvidence(
                    route.edgeId(), route.resourceId(), stage.deliveryPosition(),
                    route.hopperPosition(), destination.sourcePosition(),
                    route.overflowPosition(), route.quantity(),
                    routeSourceBefore, source, routeDestinationBefore, target,
                    overflow,
                    Math.max(1, level.getGameTime() - routeStartedTick)));
            CompositeProductionCoordinator.BufferObservation observation =
                    coordinator.observeBuffer(route.edgeId(), route.resourceId(), target);
            if (!observation.accepted()) {
                phase = Phase.FAILED;
                terminal = new Failed(destination.nodeId(), phase,
                        "Physical route readback rejected: " + observation.failure());
                return;
            }
            stageIndex++;
            phase = Phase.PROCESSING;
            divertingOverflow = false;
            startStage();
        }

        public CancellationReport cancel(ResourceId reason) {
            requireServerThread();
            Objects.requireNonNull(reason, "reason");
            if (terminal != null || phase != Phase.PROCESSING) {
                throw new IllegalStateException(
                        "Composite cancellation requires one active processing stage");
            }
            Stage stage = stages.get(stageIndex);
            CreateV606ThreeModeExecution.CancellationReport cancellation =
                    active.cancelDetailed(reason);
            coordinator.cancelLine(graph.node(stage.nodeId()).lineId());
            phase = Phase.CANCELLED;
            CleanupReport cleanup = cleanupCompletedAndRoutes();
            terminal = new Failed(stage.nodeId(), phase, "Composite cancelled: " + reason);
            return new CancellationReport(
                    graph.graphId(), stage.nodeId(), coordinator.snapshot(), cancellation, cleanup);
        }

        public CleanupReport cleanup() {
            requireServerThread();
            if (!(terminal instanceof Completed)) {
                throw new IllegalStateException("Composite cleanup requires successful completion");
            }
            return cleanupCompletedAndRoutes();
        }

        public CompositeProductionSnapshot snapshot() { return coordinator.snapshot(); }

        private void startStage() {
            Stage stage = stages.get(stageIndex);
            CompositeProductionCoordinator.Transition started = coordinator.start(stage.nodeId());
            if (!started.accepted()) {
                throw new IllegalStateException("Composite node is not ready: " + started.detail());
            }
            CreateV606ThreeModeExecution.StartResult result = CreateV606ThreeModeExecution.start(
                    level, stage.ready(), stage.runtime(), stage.sourcePosition(),
                    stage.deliveryPosition(), mode, region,
                    mode == ExecutionMode.DIRECT ? List.of() : stage.workerStarts(),
                    null, stage.installationMaterials());
            if (!(result instanceof CreateV606ThreeModeExecution.Started value)) {
                throw new IllegalStateException("Composite stage did not start: " + result);
            }
            active = value.session();
            sessions.add(active);
            peakConcurrentWorkers = Math.max(
                    peakConcurrentWorkers, active.workerIds().size());
        }

        /** Whether one more of this resource could physically land in the container. */
        private static boolean hasRoomFor(
                ServerLevel level, BlockPos3i position, ResourceId resource) {
            Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(resource.toString()));
            if (item == null) return false;
            ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(block(position));
            if (chest == null) return false;
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                ItemStack stack = chest.getItem(slot);
                if (stack.isEmpty()) return true;
                if (stack.is(item) && stack.getCount() < stack.getMaxStackSize()) return true;
            }
            return false;
        }

        private void beginRoute() {
            HopperRoute route = routes.get(stageIndex);
            Stage stage = stages.get(stageIndex);
            Stage destination = stages.get(stageIndex + 1);
            routeLastDelivered = -1;
            routeStillTicks = 0;
            routeSourceBefore = count(level, stage.deliveryPosition(), route.resourceId());
            routeDestinationBefore = count(
                    level, destination.sourcePosition(), route.resourceId());
            if (routeSourceBefore < route.quantity() || routeDestinationBefore != 0) {
                throw new IllegalStateException("Route boundary changed before hopper release");
            }
            BlockPos lock = block(route.lockPosition());
            if (!level.getBlockState(lock).is(Blocks.REDSTONE_BLOCK)) {
                throw new IllegalStateException("Verified hopper lock is missing");
            }
            level.setBlockAndUpdate(lock, Blocks.AIR.defaultBlockState());
            if (!level.getBlockState(lock).isAir()) {
                throw new IllegalStateException("Verified hopper lock could not be released");
            }
            routeStartedTick = level.getGameTime();
            divertingOverflow = false;
            phase = Phase.ROUTING;
        }

        private Progress progress() {
            return new Progress(
                    stages.get(stageIndex).nodeId(), phase,
                    completions.size(), routeEvidence.size());
        }

        private void lockRoute(HopperRoute route) {
            BlockPos lock = block(route.lockPosition());
            level.setBlockAndUpdate(lock, Blocks.REDSTONE_BLOCK.defaultBlockState());
            if (!level.getBlockState(lock).is(Blocks.REDSTONE_BLOCK)) {
                throw new IllegalStateException(
                        "Physical route could not re-lock at its exact quantity");
            }
        }

        private CleanupReport cleanupCompletedAndRoutes() {
            int planned = 0;
            int cleared = 0;
            int remaining = 0;
            for (CreateV606ThreeModeExecution.Session session : sessions) {
                if (!completions.stream().anyMatch(value ->
                        value.verifiedPhysicalPlanId().equals(
                                session.graph().verifiedPhysicalPlanId()))) {
                    continue;
                }
                CreateV606ThreeModeExecution.CleanupReport report = session.cleanup();
                planned += report.plannedPositions();
                cleared += report.clearedPositions();
                remaining += report.remainingPositions();
            }
            int routeCleared = 0;
            int routeRemaining = 0;
            for (HopperRoute route : routes) {
                for (BlockPos position : List.of(
                        block(route.hopperPosition()), block(route.lockPosition()))) {
                    if (level.getBlockState(position).isAir()) continue;
                    if (level.getBlockState(position).is(Blocks.HOPPER)
                            || level.getBlockState(position).is(Blocks.REDSTONE_BLOCK)) {
                        level.setBlockAndUpdate(position, Blocks.AIR.defaultBlockState());
                    }
                    if (level.getBlockState(position).isAir()) routeCleared++;
                    else routeRemaining++;
                }
            }
            return new CleanupReport(planned, cleared, remaining, routeCleared, routeRemaining);
        }

        private void requireServerThread() {
            if (!level.getServer().isSameThread()) {
                throw new IllegalStateException("Composite execution left the server thread");
            }
        }
    }

    private static void validateTopology(
            CompositeProductionGraph graph,
            List<Stage> stages,
            List<HopperRoute> routes) {
        for (int index = 0; index < stages.size(); index++) {
            Stage stage = stages.get(index);
            if (!graph.nodes().get(index).nodeId().equals(stage.nodeId())) {
                throw new IllegalArgumentException("Stage order differs from the admitted graph");
            }
            ResourceId plannedTarget = stage.ready().physicalPlan().candidate().boundPlan().graph()
                    .logicalPlan().candidate().goal().target();
            if (!plannedTarget.equals(stage.targetResource())) {
                throw new IllegalArgumentException("Stage target differs from its verified plan");
            }
            if (index < routes.size()) {
                HopperRoute route = routes.get(index);
                CompositeProductionGraph.MaterialEdge edge = graph.edges().get(index);
                if (!route.edgeId().equals(edge.edgeId())
                        || !route.fromNodeId().equals(stage.nodeId())
                        || !route.toNodeId().equals(stages.get(index + 1).nodeId())
                        || !route.resourceId().equals(edge.resourceId())
                        || route.quantity() != edge.quantity()
                        || !stage.targetResource().equals(route.resourceId())) {
                    throw new IllegalArgumentException("Physical route differs from its graph edge");
                }
            }
        }
    }

    private static void validatePhysicalRoutes(
            ServerLevel level,
            List<Stage> stages,
            List<HopperRoute> routes,
            CreateV606ThreeModeExecution.TestRegion region) {
        for (int index = 0; index < stages.size(); index++) {
            Stage stage = stages.get(index);
            requireChest(level, stage.sourcePosition());
            requireChest(level, stage.deliveryPosition());
            if (!region.contains(stage.sourcePosition()) || !region.contains(stage.deliveryPosition())) {
                throw new IllegalArgumentException("Composite chest is outside the bounded region");
            }
            if (index >= routes.size()) continue;
            HopperRoute route = routes.get(index);
            Stage destination = stages.get(index + 1);
            if (!region.contains(route.hopperPosition()) || !region.contains(route.lockPosition())
                    || !block(route.hopperPosition()).above().equals(block(stage.deliveryPosition()))) {
                throw new IllegalArgumentException("Hopper route is outside or detached from delivery");
            }
            if (!(level.getBlockEntity(block(route.hopperPosition())) instanceof HopperBlockEntity)
                    || !level.getBlockState(block(route.lockPosition())).is(Blocks.REDSTONE_BLOCK)) {
                throw new IllegalArgumentException("Hopper route or its physical lock is missing");
            }
            Direction facing = level.getBlockState(block(route.hopperPosition()))
                    .getValue(HopperBlock.FACING);
            if (!block(route.hopperPosition()).relative(facing)
                    .equals(block(destination.sourcePosition()))) {
                throw new IllegalArgumentException("Hopper does not face the next stage source");
            }
            if (count(level, destination.sourcePosition(), route.resourceId()) != 0) {
                throw new IllegalArgumentException("Intermediate destination was pre-seeded");
            }
            route.overflowPosition().ifPresent(overflow -> {
                requireChest(level, overflow);
                if (!region.contains(overflow)
                        || adjacentDirection(block(route.hopperPosition()), block(overflow))
                                == facing
                        || count(level, overflow, route.resourceId()) != 0) {
                    throw new IllegalArgumentException(
                            "Overflow salvage boundary is invalid or pre-seeded");
                }
            });
        }
    }

    private static void requireChest(ServerLevel level, BlockPos3i position) {
        if (!(level.getBlockEntity(block(position)) instanceof ChestBlockEntity)) {
            throw new IllegalArgumentException("Composite boundary is not a chest at " + position);
        }
    }

    private static long count(ServerLevel level, BlockPos3i position, ResourceId resource) {
        Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(resource.toString()));
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            throw new IllegalArgumentException("Unknown item resource " + resource);
        }
        ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(block(position));
        long result = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack stack = chest.getItem(slot);
            if (stack.is(item)) result = Math.addExact(result, stack.getCount());
        }
        return result;
    }

    private static long countHopper(
            ServerLevel level, BlockPos3i position, ResourceId resource) {
        Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(resource.toString()));
        HopperBlockEntity hopper = (HopperBlockEntity) level.getBlockEntity(block(position));
        long result = 0;
        for (int slot = 0; slot < hopper.getContainerSize(); slot++) {
            ItemStack stack = hopper.getItem(slot);
            if (stack.is(item)) result = Math.addExact(result, stack.getCount());
        }
        return result;
    }

    private static Direction adjacentDirection(BlockPos from, BlockPos to) {
        for (Direction direction : Direction.values()) {
            if (from.relative(direction).equals(to)) return direction;
        }
        throw new IllegalArgumentException("Composite route boundary is not adjacent");
    }

    private static BlockPos block(BlockPos3i position) {
        return new BlockPos(position.x(), position.y(), position.z());
    }
}
