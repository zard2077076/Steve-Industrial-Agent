package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import dev.stevecreate.agent.adapter.api.RuntimeFingerprint;
import dev.stevecreate.agent.core.execution.RetryPolicy;
import dev.stevecreate.agent.core.execution.construction.BotFleetExecutor;
import dev.stevecreate.agent.core.execution.construction.BotFleetCoordinator;
import dev.stevecreate.agent.core.execution.construction.BotInventory;
import dev.stevecreate.agent.core.execution.construction.BotWorker;
import dev.stevecreate.agent.core.execution.construction.BotWorkerCapability;
import dev.stevecreate.agent.core.execution.construction.BotWorkerSnapshot;
import dev.stevecreate.agent.core.execution.construction.BotWorkerStatus;
import dev.stevecreate.agent.core.execution.construction.BoundedMaterialManifest;
import dev.stevecreate.agent.core.execution.construction.CapabilityExecutionDescriptor;
import dev.stevecreate.agent.core.execution.construction.CapabilitySupport;
import dev.stevecreate.agent.core.execution.construction.CleanupPolicy;
import dev.stevecreate.agent.core.execution.construction.CleanupScope;
import dev.stevecreate.agent.core.execution.construction.ConstructionExecutionCommand;
import dev.stevecreate.agent.core.execution.construction.ConstructionExecutionContext;
import dev.stevecreate.agent.core.execution.construction.ConstructionExecutor;
import dev.stevecreate.agent.core.execution.construction.ConstructionFailureCode;
import dev.stevecreate.agent.core.execution.construction.ConstructionTask;
import dev.stevecreate.agent.core.execution.construction.ConstructionTaskClass;
import dev.stevecreate.agent.core.execution.construction.ConstructionTaskGraph;
import dev.stevecreate.agent.core.execution.construction.DirectWorldExecutor;
import dev.stevecreate.agent.core.execution.construction.ExecutionEvidence;
import dev.stevecreate.agent.core.execution.construction.ExecutionEvidenceKind;
import dev.stevecreate.agent.core.execution.construction.ExecutionMode;
import dev.stevecreate.agent.core.execution.construction.PlacementItemBinding;
import dev.stevecreate.agent.core.execution.construction.HybridExecutor;
import dev.stevecreate.agent.core.execution.construction.HybridRoutingPolicy;
import dev.stevecreate.agent.core.execution.construction.ModeCapabilityDeclaration;
import dev.stevecreate.agent.core.execution.construction.RecoveryPolicy;
import dev.stevecreate.agent.core.execution.construction.TaskAssignment;
import dev.stevecreate.agent.core.execution.construction.TaskConditionKind;
import dev.stevecreate.agent.core.execution.construction.TaskDependency;
import dev.stevecreate.agent.core.execution.construction.TaskDependencyKind;
import dev.stevecreate.agent.core.execution.construction.TaskExecutionOutcome;
import dev.stevecreate.agent.core.execution.construction.TaskExecutionResult;
import dev.stevecreate.agent.core.execution.construction.TaskFailure;
import dev.stevecreate.agent.core.execution.construction.TaskKind;
import dev.stevecreate.agent.core.execution.construction.TaskOwnership;
import dev.stevecreate.agent.core.execution.construction.TaskPostcondition;
import dev.stevecreate.agent.core.execution.construction.TaskSourceKind;
import dev.stevecreate.agent.core.execution.construction.VerifiedPlanTaskSource;
import dev.stevecreate.agent.core.execution.readiness.ExecutionReadinessFailureCode;
import dev.stevecreate.agent.core.execution.readiness.ExecutionReadyPlan;
import dev.stevecreate.agent.core.execution.readiness.ExecutionWorldClassification;
import dev.stevecreate.agent.core.layout.VerifiedPhysicalPlan;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.siteprep.PreparedSiteExecutionAuthorization;
import dev.stevecreate.agent.core.plan.FanMediumDeploymentPolicy;
import dev.stevecreate.agent.core.plan.FanProcessingMode;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal;
import dev.stevecreate.agent.forge1201.entity.ConstructionBotEntities;
import dev.stevecreate.agent.forge1201.entity.ConstructionBotEntity;
import dev.stevecreate.agent.forge1201.navigation.BoundedBotNavigation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Isolated C-03/C-04 equivalence harness over the frozen construction-executor contract.
 *
 * <p>Every mode receives the same verified plan and the same exact-input task graph. Each bounded
 * input stack has its own reservation and transport task, and the process task waits for every
 * delivery. The Bot mode does not introduce another Create implementation: a visible controlled
 * worker must first reach a plan-derived verified work cell before it may invoke the same bounded
 * v606 handler used by Direct.</p>
 */
public final class CreateV606ThreeModeExecution {
    public static final String ENABLE_PROPERTY =
            "steve_industrial.test.goalDrivenExecutionGameTest";
    private static final ResourceId CAPABILITY = id("construction:create_c03_c04_three_mode_v1");
    private static final ResourceId IMPLEMENTATION = id("construction:create_v606_three_mode");
    private static final ResourceId ADAPTER = id("construction:forge_create_v606");
    private static final ResourceId DIRECT_EXECUTOR = id("construction:direct_world_v606_three_mode");
    private static final ResourceId BOT_EXECUTOR = id("construction:bot_fleet_v606_three_mode");
    private static final ResourceId HYBRID_EXECUTOR = id("construction:hybrid_v606_three_mode");
    private static final ResourceId DIRECT_CAPABILITY =
            id("construction:bounded_server_world_action");
    private static final ResourceId BOT_CAPABILITY =
            id("construction:bot_create_v606_bounded_action");
    private static final ResourceId HYBRID_CAPABILITY =
            id("construction:hybrid_router_v1");
    private static final ResourceId SCHEMA_RESOURCE = id("schema:resource");
    private static final ResourceId SCHEMA_QUANTITY = id("schema:quantity");
    private static final ResourceId SCHEMA_DELIVERY = id("schema:delivery");
    private static final ResourceId SCHEMA_TARGET = id("schema:target");
    private static final ResourceId SCHEMA_PLAN = id("schema:verified_physical_plan");
    private static final ResourceId SCHEMA_OBSERVED = id("schema:observed_quantity");
    private static final String ENTITY_TAG = "steve_industrial_three_mode_bot";

    /**
     * Records which already-verified authority admitted a session. The test boundary remains
     * property-gated; the player boundary can only be selected by the overload that receives the
     * unforgeable {@link PreparedSiteExecutionAuthorization} produced by the core gate.
     */
    enum ExecutionBoundary {
        ISOLATED_TEST,
        PREPARED_PLAYER_SITE
    }

    private CreateV606ThreeModeExecution() {}

    /** Uses the existing Direct/Bots/Hybrid machinery behind an exact prepared-site gate. */
    public static StartResult start(
            ServerLevel level,
            PreparedSiteExecutionAuthorization authorization,
            String liveWorldIdentity,
            RuntimeFingerprint runtime,
            BlockPos3i sourcePosition,
            BlockPos3i deliveryPosition,
            ExecutionMode mode,
            TestRegion region,
            List<BlockPos3i> workerStarts) {
        return start(
                level,
                authorization,
                liveWorldIdentity,
                runtime,
                sourcePosition,
                deliveryPosition,
                mode,
                region,
                workerStarts,
                null);
    }

    public static StartResult start(
            ServerLevel level,
            PreparedSiteExecutionAuthorization authorization,
            String liveWorldIdentity,
            RuntimeFingerprint runtime,
            BlockPos3i sourcePosition,
            BlockPos3i deliveryPosition,
            ExecutionMode mode,
            TestRegion region,
            List<BlockPos3i> workerStarts,
            CreateV606VerifiedExecutionMetadata executionMetadata) {
        return start(level, authorization, liveWorldIdentity, runtime, sourcePosition,
                deliveryPosition, mode, region, workerStarts, executionMetadata, Map.of());
    }

    public static StartResult start(
            ServerLevel level,
            PreparedSiteExecutionAuthorization authorization,
            String liveWorldIdentity,
            RuntimeFingerprint runtime,
            BlockPos3i sourcePosition,
            BlockPos3i deliveryPosition,
            ExecutionMode mode,
            TestRegion region,
            List<BlockPos3i> workerStarts,
            CreateV606VerifiedExecutionMetadata executionMetadata,
            Map<ResourceId, Long> installationMaterials) {
        Objects.requireNonNull(authorization, "authorization");
        Objects.requireNonNull(liveWorldIdentity, "liveWorldIdentity");
        ResourceId liveDimension = ResourceId.parse(level.dimension().location().toString());
        if (!authorization.worldIdentity().equals(liveWorldIdentity)
                || !authorization.dimension().equals(liveDimension)
                || !Instant.now().isBefore(authorization.expiresAt())) {
            return new Rejected(ExecutionReadinessFailureCode.EXECUTION_NOT_READY,
                    "Prepared-site authorization is expired or belongs to another dimension");
        }
        return startWithinBoundary(level, authorization.executionReadyPlan(), runtime, sourcePosition,
                deliveryPosition, mode, region, workerStarts, executionMetadata,
                installationMaterials, authorization);
    }

    public static StartResult start(
            ServerLevel level,
            ExecutionReadyPlan ready,
            RuntimeFingerprint runtime,
            BlockPos3i sourcePosition,
            BlockPos3i deliveryPosition,
            ExecutionMode mode,
            TestRegion region,
            List<BlockPos3i> workerStarts) {
        return start(
                level,
                ready,
                runtime,
                sourcePosition,
                deliveryPosition,
                mode,
                region,
                workerStarts,
                null);
    }

    public static StartResult start(
            ServerLevel level,
            ExecutionReadyPlan ready,
            RuntimeFingerprint runtime,
            BlockPos3i sourcePosition,
            BlockPos3i deliveryPosition,
            ExecutionMode mode,
            TestRegion region,
            List<BlockPos3i> workerStarts,
            CreateV606VerifiedExecutionMetadata executionMetadata) {
        return start(level, ready, runtime, sourcePosition, deliveryPosition, mode, region,
                workerStarts, executionMetadata, Map.of());
    }

    /**
     * Player-material variant. Installation items remain in the dedicated source as escrow while
     * the verified handler builds, are untouched on cancellation, and are debited only after the
     * live process completes. Test harnesses continue to use the empty-installation overload.
     */
    public static StartResult start(
            ServerLevel level,
            ExecutionReadyPlan ready,
            RuntimeFingerprint runtime,
            BlockPos3i sourcePosition,
            BlockPos3i deliveryPosition,
            ExecutionMode mode,
            TestRegion region,
            List<BlockPos3i> workerStarts,
            CreateV606VerifiedExecutionMetadata executionMetadata,
            Map<ResourceId, Long> installationMaterials) {
        return startWithinBoundary(level, ready, runtime, sourcePosition, deliveryPosition, mode,
                region, workerStarts, executionMetadata, installationMaterials,
                null);
    }

    private static StartResult startWithinBoundary(
            ServerLevel level,
            ExecutionReadyPlan ready,
            RuntimeFingerprint runtime,
            BlockPos3i sourcePosition,
            BlockPos3i deliveryPosition,
            ExecutionMode mode,
            TestRegion region,
            List<BlockPos3i> workerStarts,
            CreateV606VerifiedExecutionMetadata executionMetadata,
            Map<ResourceId, Long> installationMaterials,
            PreparedSiteExecutionAuthorization preparedSiteAuthorization) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(ready, "ready");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(sourcePosition, "sourcePosition");
        Objects.requireNonNull(deliveryPosition, "deliveryPosition");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(region, "region");
        workerStarts = List.copyOf(Objects.requireNonNull(workerStarts, "workerStarts"));
        installationMaterials = Map.copyOf(Objects.requireNonNull(
                installationMaterials, "installationMaterials"));
        ExecutionBoundary executionBoundary = preparedSiteAuthorization == null
                ? ExecutionBoundary.ISOLATED_TEST
                : ExecutionBoundary.PREPARED_PLAYER_SITE;
        if (!level.getServer().isSameThread()) {
            return new Rejected(ExecutionReadinessFailureCode.EXECUTION_NOT_READY,
                    "Three-mode execution must start on the authoritative server thread");
        }
        var guard = CreateExecutionWorldGuard.verify(
                level, ExecutionWorldClassification.ISOLATED_REPOSITORY_TEST);
        if (guard instanceof CreateExecutionWorldGuard.GuardFailure failure) {
            return new Rejected(failure.code(), failure.detail());
        }
        Map<ResourceId, Long> required = requiredInputs(
                ready, executionMetadata);
        BoundedMaterialManifest boundedManifest;
        try {
            boundedManifest = BoundedMaterialManifest.from(required);
        } catch (IllegalArgumentException invalid) {
            return new Rejected(ExecutionReadinessFailureCode.INPUT_RESOURCE_MISSING,
                    "Three-mode input manifest is outside the bounded logistics contract: "
                            + invalid.getMessage());
        }
        if (!region.contains(sourcePosition) || !region.contains(deliveryPosition)) {
            return new Rejected(ExecutionReadinessFailureCode.EXECUTION_NOT_READY,
                    "Material chests are outside the bounded Bot test region");
        }
        if (mode != ExecutionMode.DIRECT
                && (workerStarts.size() < BotFleetCoordinator.MIN_WORKERS
                || workerStarts.size() > BotFleetCoordinator.MAX_WORKERS)) {
            return new Rejected(ExecutionReadinessFailureCode.EXECUTION_NOT_READY,
                    "Bot and Hybrid execution require a bounded 2-5 visible-worker fleet");
        }
        try {
            var materialized = executionMetadata == null
                    ? new CreateV606ExecutionPlanAdapter().materialize(ready)
                    : new CreateV606ExecutionPlanAdapter().materialize(
                            ready, executionMetadata);
            if (materialized
                    instanceof CreateV606ExecutionPlanAdapter.MaterializationFailure failure) {
                return new Rejected(failure.code(), failure.detail());
            }
            Optional<String> fanMediumFallback;
            try {
                fanMediumFallback = decideFanMediumRouting(
                        ((CreateV606ExecutionPlanAdapter.MaterializationSuccess) materialized)
                                .nodes().stream()
                                .filter(CreateV606ExecutionPlanAdapter.FanNode.class::isInstance)
                                .map(CreateV606ExecutionPlanAdapter.FanNode.class::cast)
                                .map(value -> value.plan().process().mode())
                                .toList(),
                        mode);
            } catch (UnsupportedOperationException unsupported) {
                return new Rejected(
                        ExecutionReadinessFailureCode.EXECUTION_NOT_READY,
                        "typed_unsupported: " + unsupported.getMessage());
            }
            MaterialManifest material = materialManifest(ready, boundedManifest);
            List<InstallationStack> installations = installationManifest(installationMaterials);
            requireChest(level, sourcePosition, "source");
            requireChest(level, deliveryPosition, "delivery");
            long expectedSourceItems = material.inputs().stream()
                    .mapToLong(MaterialStack::quantity).sum()
                    + installations.stream().mapToLong(InstallationStack::quantity).sum();
            if (!sourceMatches(level, sourcePosition, material, installations, true)
                    || totalItems(level, sourcePosition) != expectedSourceItems
                    || totalItems(level, deliveryPosition) != 0) {
                return new Rejected(ExecutionReadinessFailureCode.INPUT_RESOURCE_MISSING,
                        "Dedicated source/delivery chests do not match the exact plan input boundary");
            }
            return new Started(new Session(
                    level, ready, runtime, sourcePosition, deliveryPosition,
                    mode, region, workerStarts, material, fanMediumFallback,
                    executionMetadata, installations, executionBoundary,
                    preparedSiteAuthorization));
        } catch (RuntimeException failure) {
            return new Rejected(ExecutionReadinessFailureCode.EXECUTION_NOT_READY,
                    "Three-mode session construction failed closed: " + failure.getMessage());
        }
    }

    public sealed interface StartResult permits Started, Rejected {}

    static Optional<String> decideFanMediumRouting(
            List<FanProcessingMode> modes,
            ExecutionMode requestedMode) {
        Objects.requireNonNull(modes, "modes");
        Objects.requireNonNull(requestedMode, "requestedMode");
        Optional<String> fallback = Optional.empty();
        for (FanProcessingMode mode : List.copyOf(modes)) {
            FanMediumDeploymentPolicy.Decision decision =
                    FanMediumDeploymentPolicy.decide(mode, requestedMode);
            if (decision.fallback()) {
                fallback = Optional.of(decision.reason());
            }
        }
        return fallback;
    }

    static boolean tickBoundaryAccepted(
            boolean authoritativeServerThread,
            ExecutionBoundary executionBoundary,
            boolean isolatedTestPropertyEnabled) {
        Objects.requireNonNull(executionBoundary, "executionBoundary");
        return authoritativeServerThread
                && (executionBoundary == ExecutionBoundary.PREPARED_PLAYER_SITE
                || isolatedTestPropertyEnabled);
    }

    public record Started(Session session) implements StartResult {
        public Started { Objects.requireNonNull(session, "session"); }
    }

    public record Rejected(
            ExecutionReadinessFailureCode code,
            String detail) implements StartResult {
        public Rejected {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(detail, "detail");
        }
    }

    public sealed interface TickResult permits Progress, Completed, Failed {}

    public record Progress(
            ExecutionMode mode,
            ResourceId taskId,
            TaskExecutionOutcome outcome,
            Optional<CreateV606GoalDrivenExecution.Phase> createPhase,
            long elapsedTicks) implements TickResult {
        public Progress {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(outcome, "outcome");
            createPhase = Objects.requireNonNull(createPhase, "createPhase");
            if (elapsedTicks < 0) throw new IllegalArgumentException("elapsedTicks is negative");
        }
    }

    public record Completed(
            ExecutionMode mode,
            ResourceId verifiedPhysicalPlanId,
            ResourceId graphId,
            CreateV606GoalDrivenExecution.Completed process,
            List<TaskExecutionResult> taskResults,
            Map<BlockPos3i, String> finalBlockSnapshot,
            long elapsedTicks,
            boolean reloadReconciled,
            List<ResourceId> workerIds,
            List<WorkerActivity> workerActivities) implements TickResult {
        public Completed {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(verifiedPhysicalPlanId, "verifiedPhysicalPlanId");
            Objects.requireNonNull(graphId, "graphId");
            Objects.requireNonNull(process, "process");
            taskResults = List.copyOf(taskResults);
            finalBlockSnapshot = Collections.unmodifiableMap(new LinkedHashMap<>(finalBlockSnapshot));
            workerIds = List.copyOf(workerIds);
            workerActivities = List.copyOf(workerActivities);
            if (taskResults.size() < 3
                    || taskResults.size()
                            > BoundedMaterialManifest.MAX_DISTINCT_RESOURCES + 2
                    || finalBlockSnapshot.isEmpty() || elapsedTicks < 1
                    || !reloadReconciled) {
                throw new IllegalArgumentException("Three-mode completion evidence is incomplete");
            }
            if (mode == ExecutionMode.DIRECT
                    && (!workerIds.isEmpty() || !workerActivities.isEmpty())) {
                throw new IllegalArgumentException("Direct completion cannot claim Bot activity");
            }
            if (mode != ExecutionMode.DIRECT
                    && (workerIds.size() < BotFleetCoordinator.MIN_WORKERS
                    || workerIds.size() > BotFleetCoordinator.MAX_WORKERS
                    || workerActivities.size() != workerIds.size())) {
                throw new IllegalArgumentException(
                        "Bot-backed completion requires one reconciled bounded fleet");
            }
            if (mode == ExecutionMode.BOTS && workerActivities.stream()
                    .anyMatch(value -> value.assignmentsStarted() < 1
                            || value.movementTicks() < 1)) {
                throw new IllegalArgumentException(
                        "Bots mode requires both role workers to perform visible work");
            }
            if (mode == ExecutionMode.BOTS && workerActivities.stream()
                    .map(WorkerActivity::finalPosition)
                    .distinct()
                    .count() != workerActivities.size()) {
                throw new IllegalArgumentException(
                        "Bots mode requires distinct role-specific completion stations");
            }
        }
    }

    public record WorkerActivity(
            ResourceId workerId,
            String role,
            int assignmentsStarted,
            int movementTicks,
            BlockPos3i startPosition,
            BlockPos3i finalPosition) {
        public WorkerActivity {
            Objects.requireNonNull(workerId, "workerId");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(startPosition, "startPosition");
            Objects.requireNonNull(finalPosition, "finalPosition");
            if (role.isBlank() || assignmentsStarted < 0 || movementTicks < 0) {
                throw new IllegalArgumentException("Bot worker activity is invalid");
            }
        }
    }

    /** Immutable, read-only progress view for plan-owned factory diagnosis. */
    public record DiagnosticSnapshot(
            VerifiedPhysicalPlan physicalPlan,
            ResourceId currentTaskId,
            ConstructionTaskClass currentTaskClass,
            int completedTasks,
            int totalTasks,
            Optional<CreateV606GoalDrivenExecution.Phase> createPhase,
            long startedTick,
            long lastProgressTick,
            long observedTick,
            List<WorkerActivity> workerActivities) {
        public DiagnosticSnapshot {
            Objects.requireNonNull(physicalPlan, "physicalPlan");
            Objects.requireNonNull(currentTaskId, "currentTaskId");
            Objects.requireNonNull(currentTaskClass, "currentTaskClass");
            createPhase = Objects.requireNonNull(createPhase, "createPhase");
            workerActivities = List.copyOf(Objects.requireNonNull(
                    workerActivities, "workerActivities"));
            if (completedTasks < 0 || totalTasks < 1 || completedTasks > totalTasks
                    || startedTick < 0 || lastProgressTick < startedTick
                    || observedTick < lastProgressTick) {
                throw new IllegalArgumentException("three-mode diagnostic snapshot is invalid");
            }
        }
    }

    public record Failed(
            ExecutionMode mode,
            ResourceId taskId,
            TaskExecutionResult taskResult,
            String detail) implements TickResult {
        public Failed {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(taskResult, "taskResult");
            Objects.requireNonNull(detail, "detail");
        }
    }

    public record CleanupReport(int plannedPositions, int clearedPositions, int remainingPositions) {
        public CleanupReport {
            if (plannedPositions < 1 || clearedPositions < 0 || remainingPositions < 0
                    || clearedPositions + remainingPositions != plannedPositions) {
                throw new IllegalArgumentException("Invalid three-mode cleanup counts");
            }
        }
    }

    public record CancellationReport(
            ExecutionMode mode,
            TaskExecutionResult taskResult,
            List<WorldChangeJournal> journals,
            int restoredInputStacks,
            int removedWorkers,
            boolean sourceRestored,
            boolean deliveryEmpty,
            List<WorkerActivity> workerActivities) {
        public CancellationReport {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(taskResult, "taskResult");
            journals = List.copyOf(journals);
            workerActivities = List.copyOf(workerActivities);
            if (taskResult.outcome() != TaskExecutionOutcome.CANCELLED
                    || restoredInputStacks < 0 || removedWorkers < 0
                    || !sourceRestored || !deliveryEmpty
                    || workerActivities.size() != removedWorkers) {
                throw new IllegalArgumentException(
                        "Three-mode cancellation evidence is incomplete");
            }
        }
    }

    public record FailureCleanupReport(
            int journalEntries,
            int restoredPositions,
            int warnings,
            int restoredInputStacks,
            int removedWorkers,
            boolean sourceRestored,
            boolean deliveryEmpty) {
        public FailureCleanupReport {
            if (journalEntries < 0 || restoredPositions < 0 || warnings < 0
                    || restoredInputStacks < 0 || removedWorkers < 0
                    || !sourceRestored || !deliveryEmpty) {
                throw new IllegalArgumentException(
                        "Three-mode failure cleanup evidence is incomplete");
            }
        }
    }

    public record TestRegion(BlockPos3i minimum, BlockPos3i maximum) {
        public TestRegion {
            Objects.requireNonNull(minimum, "minimum");
            Objects.requireNonNull(maximum, "maximum");
            if (minimum.x() > maximum.x() || minimum.y() > maximum.y()
                    || minimum.z() > maximum.z()) {
                throw new IllegalArgumentException("Invalid Bot test region bounds");
            }
        }

        boolean contains(BlockPos3i value) {
            return value.x() >= minimum.x() && value.x() <= maximum.x()
                    && value.y() >= minimum.y() && value.y() <= maximum.y()
                    && value.z() >= minimum.z() && value.z() <= maximum.z();
        }

        boolean contains(BlockPos value) { return contains(position(value)); }
    }

    public static int cleanupWorkers(ServerLevel level, TestRegion region) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(region, "region");
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("Bot cleanup must run on the authoritative server thread");
        }
        AABB bounds = new AABB(
                region.minimum().x(), region.minimum().y(), region.minimum().z(),
                region.maximum().x() + 1.0, region.maximum().y() + 2.0,
                region.maximum().z() + 1.0);
        List<ConstructionBotEntity> workers = level.getEntitiesOfClass(
                ConstructionBotEntity.class, bounds,
                value -> value.getTags().contains(ENTITY_TAG));
        List<ArmorStand> legacyWorkers = level.getEntitiesOfClass(
                ArmorStand.class, bounds, value -> value.getTags().contains(ENTITY_TAG));
        workers.forEach(Entity::discard);
        legacyWorkers.forEach(Entity::discard);
        return workers.size() + legacyWorkers.size();
    }

    public static final class Session {
        private final ServerLevel level;
        private final ExecutionReadyPlan ready;
        private final RuntimeFingerprint runtime;
        private final CreateV606VerifiedExecutionMetadata executionMetadata;
        private final BlockPos3i sourcePosition;
        private final BlockPos3i deliveryPosition;
        private final ExecutionMode mode;
        private final TestRegion region;
        private final MaterialManifest material;
        private final List<InstallationStack> installations;
        private final ExecutionBoundary executionBoundary;
        private final PreparedSiteExecutionAuthorization preparedSiteAuthorization;
        private final Optional<String> fanMediumFallback;
        private final ConstructionTaskGraph graph;
        private final List<ConstructionTask> tasks;
        private final long startedTick;
        private final List<TaskExecutionResult> completedResults = new ArrayList<>();
        private final List<ResourceId> workerIds = new ArrayList<>();
        private ConstructionExecutor executor;
        private DirectWorldExecutor directExecutor;
        private BotFleetExecutor botExecutor;
        private List<PhysicalBotWorker> workers = List.of();
        private int taskIndex;
        private TaskAssignment assignment;
        private TaskExecutionResult prior;
        private ItemStack directCarried = ItemStack.EMPTY;
        private CreateV606GoalDrivenExecution.Session processSession;
        private CreateV606GoalDrivenExecution.Completed processCompleted;
        private CreateV606GoalDrivenExecution.TickResult lastProcessResult;
        private boolean reloadReconciled;
        private TickResult terminal;
        private Map<BlockPos3i, String> finalSnapshot = Map.of();
        private boolean workersRetired;
        private boolean installationsConsumed;
        private long lastDiagnosticProgressTick;
        private int lastDiagnosticTaskIndex;
        private long lastDiagnosticMovementTicks;
        private Optional<CreateV606GoalDrivenExecution.Phase> lastDiagnosticPhase = Optional.empty();

        private Session(
                ServerLevel level,
                ExecutionReadyPlan ready,
                RuntimeFingerprint runtime,
                BlockPos3i sourcePosition,
                BlockPos3i deliveryPosition,
                ExecutionMode mode,
                TestRegion region,
                List<BlockPos3i> workerStarts,
                MaterialManifest material,
                Optional<String> fanMediumFallback,
                CreateV606VerifiedExecutionMetadata executionMetadata,
                List<InstallationStack> installations,
                ExecutionBoundary executionBoundary,
                PreparedSiteExecutionAuthorization preparedSiteAuthorization) {
            this.level = level;
            this.ready = ready;
            this.runtime = runtime;
            this.executionMetadata = executionMetadata;
            this.sourcePosition = sourcePosition;
            this.deliveryPosition = deliveryPosition;
            this.mode = mode;
            this.region = region;
            this.material = material;
            this.installations = List.copyOf(installations);
            this.executionBoundary = Objects.requireNonNull(
                    executionBoundary, "executionBoundary");
            this.preparedSiteAuthorization = preparedSiteAuthorization;
            if ((executionBoundary == ExecutionBoundary.PREPARED_PLAYER_SITE)
                    != (preparedSiteAuthorization != null)) {
                throw new IllegalArgumentException(
                        "Prepared-site execution boundary requires its verified authorization");
            }
            this.fanMediumFallback =
                    Objects.requireNonNull(fanMediumFallback, "fanMediumFallback");
            this.startedTick = level.getGameTime();
            this.lastDiagnosticProgressTick = startedTick;
            this.tasks = createTasks(ready, material, deliveryPosition);
            this.graph = createGraph(ready, tasks);
            if (mode != ExecutionMode.DIRECT) {
                this.workers = spawnWorkers(workerStarts);
                workers.forEach(value -> workerIds.add(value.workerId()));
            }
            rebuildExecutors(false);
        }

        public ConstructionTaskGraph graph() { return graph; }

        public ExecutionMode mode() { return mode; }

        public List<ResourceId> workerIds() { return List.copyOf(workerIds); }

        public boolean reloadReconciled() { return reloadReconciled; }

        /** Returns no session or executor handle and performs no world or order mutation. */
        public DiagnosticSnapshot diagnosticSnapshot() {
            requireServerThread();
            int index = Math.min(taskIndex, tasks.size() - 1);
            ConstructionTask task = tasks.get(index);
            return new DiagnosticSnapshot(ready.physicalPlan(), task.taskId(), task.taskClass(),
                    taskIndex, tasks.size(), currentPhase(), startedTick,
                    lastDiagnosticProgressTick, level.getGameTime(),
                    workers.stream().map(PhysicalBotWorker::activity).toList());
        }

        /**
         * Retires a completed stage fleet before the next composite stage is admitted. The
         * immutable completion record has already captured every worker's activity, so this
         * prevents sequential composites from accumulating inactive duplicate fleets.
         */
        public int retireWorkersAfterCompletion() {
            requireServerThread();
            if (!(terminal instanceof Completed)) {
                throw new IllegalStateException(
                        "Worker retirement requires a completed three-mode session");
            }
            if (workersRetired) return 0;
            workers.forEach(PhysicalBotWorker::discard);
            workersRetired = true;
            return workers.size();
        }

        /**
         * Removes workers created during construction when the caller fails before the first
         * session tick. This cleanup intentionally checks the real server thread without requiring
         * the execution-enable property whose absence may have caused the startup failure.
         */
        public int abortBeforeFirstTick() {
            if (!level.getServer().isSameThread()) {
                throw new IllegalStateException(
                        "Three-mode startup abort left the authoritative server thread");
            }
            if (taskIndex != 0 || assignment != null || processSession != null
                    || terminal != null || !completedResults.isEmpty()
                    || !directCarried.isEmpty()) {
                throw new IllegalStateException(
                        "Three-mode startup abort is only valid before the first task tick");
            }
            int discarded = workers.size();
            workers.forEach(PhysicalBotWorker::discard);
            workers = List.of();
            workerIds.clear();
            return discarded;
        }

        public TickResult tick() {
            if (terminal != null) return terminal;
            requireServerThread();
            ConstructionTask task = tasks.get(taskIndex);
            long tick = level.getGameTime();
            if (assignment == null) assignment = assignment(task, tick);
            ConstructionExecutionCommand command = prior == null
                    ? ConstructionExecutionCommand.START
                    : ConstructionExecutionCommand.CONTINUE;
            ConstructionExecutionContext context = new ConstructionExecutionContext(
                    command, tick,
                    prior == null ? List.of() : prior.evidence(),
                    prior == null ? Optional.empty() : prior.failure(),
                    Optional.empty());
            TaskExecutionResult result = executor.execute(graph, task, assignment, context);
            prior = result;
            recordDiagnosticProgress(result, tick);
            if (result.outcome() == TaskExecutionOutcome.PENDING) {
                return new Progress(mode, task.taskId(), result.outcome(), currentPhase(),
                        Math.max(0, tick - startedTick));
            }
            if (result.outcome() != TaskExecutionOutcome.SUCCEEDED) {
                terminal = new Failed(mode, task.taskId(), result, result.detail());
                return terminal;
            }
            completedResults.add(result);
            taskIndex++;
            lastDiagnosticProgressTick = tick;
            lastDiagnosticTaskIndex = taskIndex;
            assignment = null;
            prior = null;
            if (taskIndex == material.inputs().size()) {
                reconcileIdleReload();
            }
            if (taskIndex < tasks.size()) {
                return new Progress(mode, tasks.get(taskIndex).taskId(), TaskExecutionOutcome.PENDING,
                        currentPhase(), Math.max(0, tick - startedTick));
            }
            finalSnapshot = snapshotPlan();
            terminal = new Completed(
                    mode, ready.physicalPlan().id(), graph.graphId(),
                    Objects.requireNonNull(processCompleted, "processCompleted"),
                    completedResults, finalSnapshot,
                    Math.max(1, tick - startedTick + 1), reloadReconciled, workerIds,
                    workers.stream().map(PhysicalBotWorker::activity).toList());
            return terminal;
        }

        private void recordDiagnosticProgress(TaskExecutionResult result, long tick) {
            long movementTicks = workers.stream().map(PhysicalBotWorker::activity)
                    .mapToLong(WorkerActivity::movementTicks).sum();
            Optional<CreateV606GoalDrivenExecution.Phase> phase = currentPhase();
            if (result.worldMutationCount() > 0 || result.materialMutationCount() > 0
                    || taskIndex != lastDiagnosticTaskIndex
                    || movementTicks != lastDiagnosticMovementTicks
                    || !phase.equals(lastDiagnosticPhase)) {
                lastDiagnosticProgressTick = tick;
            }
            lastDiagnosticTaskIndex = taskIndex;
            lastDiagnosticMovementTicks = movementTicks;
            lastDiagnosticPhase = phase;
        }

        public CleanupReport cleanup() {
            requireServerThread();
            if (!(terminal instanceof Completed)) {
                throw new IllegalStateException("Cleanup requires a completed three-mode session");
            }
            int cleared = 0;
            int remaining = 0;
            for (Map.Entry<BlockPos3i, String> entry : finalSnapshot.entrySet()) {
                if (!stateString(level.getBlockState(block(entry.getKey()))).equals(entry.getValue())) {
                    remaining++;
                }
            }
            if (remaining > 0) {
                workers.forEach(PhysicalBotWorker::discard);
                return new CleanupReport(finalSnapshot.size(), 0, finalSnapshot.size());
            }
            for (Map.Entry<BlockPos3i, String> entry : finalSnapshot.entrySet()) {
                BlockPos target = block(entry.getKey());
                level.setBlockAndUpdate(target, Blocks.AIR.defaultBlockState());
                if (level.getBlockState(target).isAir()) cleared++;
                else remaining++;
            }
            workers.forEach(PhysicalBotWorker::discard);
            return new CleanupReport(finalSnapshot.size(), cleared, remaining);
        }

        public List<WorldChangeJournal> journalsSnapshot() {
            requireServerThread();
            return processSession == null ? List.of() : processSession.journalsSnapshot();
        }

        public List<WorldChangeJournal> cancel(ResourceId reason) {
            return cancelDetailed(reason).journals();
        }

        public CancellationReport cancelDetailed(ResourceId reason) {
            requireServerThread();
            Objects.requireNonNull(reason, "reason");
            if (terminal != null) {
                throw new IllegalStateException(
                        "Three-mode session is already terminal");
            }
            ConstructionTask task = tasks.get(taskIndex);
            long tick = level.getGameTime();
            if (assignment == null) assignment = assignment(task, tick);
            ConstructionExecutionContext context = new ConstructionExecutionContext(
                    ConstructionExecutionCommand.CANCEL,
                    tick,
                    prior == null ? List.of() : prior.evidence(),
                    prior == null ? Optional.empty() : prior.failure(),
                    Optional.of(reason));
            TaskExecutionResult cancelled =
                    executor.execute(graph, task, assignment, context);
            if (cancelled.outcome() != TaskExecutionOutcome.CANCELLED) {
                throw new IllegalStateException(
                        "Three-mode executor refused the typed cancellation: "
                                + cancelled.detail());
            }
            List<WorldChangeJournal> journals = processSession == null
                    ? List.of() : processSession.cancel(reason).journals();
            for (PhysicalBotWorker worker : workers) {
                worker.returnCarriedInput();
            }
            if (!directCarried.isEmpty()) {
                returnExact(level, sourcePosition, directCarried);
                directCarried = ItemStack.EMPTY;
            }
            int restored = restoreTransportedInputs();
            int removedWorkers = workers.size();
            List<WorkerActivity> workerActivities = workers.stream()
                    .map(PhysicalBotWorker::activity)
                    .toList();
            workers.forEach(PhysicalBotWorker::discard);
            boolean sourceRestored = sourceMatches(
                    level, sourcePosition, material, installations, true);
            boolean deliveryEmpty = totalItems(level, deliveryPosition) == 0;
            terminal = new Failed(mode, task.taskId(), cancelled,
                    "Three-mode execution cancelled: " + reason);
            return new CancellationReport(
                    mode, cancelled, journals, restored, removedWorkers,
                    sourceRestored, deliveryEmpty, workerActivities);
        }

        public List<WorkerActivity> workerActivitiesSnapshot() {
            requireServerThread();
            return workers.stream().map(PhysicalBotWorker::activity).toList();
        }

        private int restoreTransportedInputs() {
            int restored = 0;
            for (MaterialStack input : material.inputs()) {
                int source = count(level, sourcePosition, input.item())
                        - installationQuantity(input.item());
                int delivery = count(level, deliveryPosition, input.item());
                if ((long) source + delivery != input.quantity()) {
                    throw new IllegalStateException(
                            "Cancellation cannot prove exact input conservation for "
                                    + input.resourceId() + ": source=" + source
                                    + " delivery=" + delivery
                                    + " required=" + input.quantity());
                }
                if (delivery > 0) {
                    ItemStack returned = removeExact(
                            level, deliveryPosition, input.item(), delivery);
                    returnExact(level, sourcePosition, returned);
                    restored++;
                }
            }
            return restored;
        }

        public FailureCleanupReport cleanupFailed() {
            requireServerThread();
            if (!(terminal instanceof Failed)) {
                throw new IllegalStateException(
                        "Failure cleanup requires a failed three-mode session");
            }
            int entries = 0;
            int restoredPositions = 0;
            int warnings = 0;
            for (WorldChangeJournal journal : journalsSnapshot()) {
                WorldChangeJournal.RollbackReport report =
                        new Create606WorldChangeJournal(
                                level, journal.sessionId(), journal).rollback();
                entries += report.journalEntryCount();
                restoredPositions += report.restoredPositions().size();
                warnings += report.warnings().size();
            }
            for (PhysicalBotWorker worker : workers) {
                worker.returnCarriedInput();
            }
            if (!directCarried.isEmpty()) {
                returnExact(level, sourcePosition, directCarried);
                directCarried = ItemStack.EMPTY;
            }
            int restoredInputs = restoreTransportedInputs();
            int removedWorkers = workers.size();
            workers.forEach(PhysicalBotWorker::discard);
            boolean sourceRestored = sourceMatches(
                    level, sourcePosition, material, installations, true);
            boolean deliveryEmpty = totalItems(level, deliveryPosition) == 0;
            return new FailureCleanupReport(
                    entries, restoredPositions, warnings, restoredInputs,
                    removedWorkers, sourceRestored, deliveryEmpty);
        }

        private Optional<CreateV606GoalDrivenExecution.Phase> currentPhase() {
            if (lastProcessResult instanceof CreateV606GoalDrivenExecution.Progress progress) {
                return Optional.of(progress.phase());
            }
            return Optional.empty();
        }

        private void reconcileIdleReload() {
            if (!directCarried.isEmpty()) {
                throw new IllegalStateException("Direct transport retained material at idle reload boundary");
            }
            if (!sourceMatches(level, sourcePosition, material, installations, false)
                    || material.inputs().stream().anyMatch(input ->
                            count(level, deliveryPosition, input.item()) != input.quantity())
                    || totalItems(level, deliveryPosition)
                            != material.inputs().stream()
                                    .mapToLong(MaterialStack::quantity)
                                    .sum()) {
                throw new IllegalStateException(
                        "Delivery chest did not reconcile the exact transported input manifest");
            }
            if (!workers.isEmpty()) {
                List<PhysicalBotWorker> restored = workers.stream()
                        .map(PhysicalBotWorker::restoreIdle)
                        .toList();
                workers = restored;
            }
            rebuildExecutors(true);
            reloadReconciled = true;
        }

        private void rebuildExecutors(boolean reload) {
            directExecutor = new DirectWorldExecutor(
                    DIRECT_EXECUTOR, Set.of(DIRECT_CAPABILITY), this::executeDirect);
            if (!workers.isEmpty()) {
                botExecutor = new BotFleetExecutor(
                        BOT_EXECUTOR, Set.of(BOT_CAPABILITY), workers);
            }
            executor = switch (mode) {
                case DIRECT -> directExecutor;
                case BOTS -> Objects.requireNonNull(botExecutor, "botExecutor");
                case HYBRID -> new HybridExecutor(
                        HYBRID_EXECUTOR, Set.of(HYBRID_CAPABILITY), directExecutor,
                        Objects.requireNonNull(botExecutor, "botExecutor"),
                        HybridRoutingPolicy.safeDefaults());
            };
            if (reload && mode == ExecutionMode.DIRECT && executor != directExecutor) {
                throw new IllegalStateException("Direct reload changed executor identity");
            }
        }

        private List<PhysicalBotWorker> spawnWorkers(List<BlockPos3i> starts) {
            List<PhysicalBotWorker> values = new ArrayList<>();
            int logisticsWorkers = Math.min(
                    material.inputs().size(), starts.size() - 1);
            if (logisticsWorkers < 1) {
                throw new IllegalStateException(
                        "Bot fleet requires at least one exact material transport worker");
            }
            for (int index = 0; index < starts.size(); index++) {
                ResourceId workerId = childId(ready.sessionId(), "three_mode_bot_" + index);
                values.add(PhysicalBotWorker.spawn(
                        this, workerId, starts.get(index),
                        index < logisticsWorkers
                                ? ConstructionBotEntity.Role.LOGISTICS
                                : ConstructionBotEntity.Role.BUILDER_INSPECTOR));
            }
            return List.copyOf(values);
        }

        private int logisticsWorkerCount() {
            if (workerIds.size() < BotFleetCoordinator.MIN_WORKERS) {
                throw new IllegalStateException("Bot fleet is absent");
            }
            return Math.min(material.inputs().size(), workerIds.size() - 1);
        }

        private TaskExecutionResult executeDirect(
                ConstructionTaskGraph ignoredGraph,
                ConstructionTask task,
                TaskAssignment assignment,
                ConstructionExecutionContext context) {
            if (context.command() == ConstructionExecutionCommand.CANCEL) {
                return cancelResult(task, assignment, context.currentTick(),
                        "Direct three-mode task cancelled at a bounded boundary");
            }
            return switch (task.kind()) {
                case TRANSPORT_MATERIAL -> directTransport(task, assignment, context.currentTick());
                case PLACE_COMPONENT -> build(task, assignment, context.currentTick(), false);
                case VERIFY_OUTPUT -> verify(task, assignment, context.currentTick(),
                        "create-v606:authoritative-direct-output-rescan");
                default -> failureResult(task, assignment, context.currentTick(),
                        ConstructionFailureCode.EXECUTOR_MODE_UNSUPPORTED,
                        "Three-mode Direct backend received an unbound task kind", 0, 0);
            };
        }

        private TaskExecutionResult directTransport(
                ConstructionTask task,
                TaskAssignment assignment,
                long tick) {
            MaterialStack input = materialFor(task);
            if (directCarried.isEmpty()) {
                directCarried = removeExact(
                        level, sourcePosition, input.item(), input.quantity());
                return TaskExecutionResult.pending(
                        task, assignment, tick, List.of(), 0, 1,
                        "Direct removed one exact reserved input stack from the dedicated source");
            }
            insertExact(level, deliveryPosition, directCarried);
            directCarried = ItemStack.EMPTY;
            return success(task, assignment, tick, transportObserved(input), 0, 1,
                    "create-v606:direct-exact-chest-transfer");
        }

        private TaskExecutionResult build(
                ConstructionTask task,
                TaskAssignment assignment,
                long tick,
                boolean bot) {
            TaskExecutionResult startFailure = ensureProcessStarted(task, assignment, tick);
            if (startFailure != null) return startFailure;
            int worldMutations;
            int materialMutations;
            if (bot) {
                CreateV606DirectWorldExecutor.BoundedRootUpdate update =
                        processSession.tickForVerifiedBot();
                lastProcessResult = update.tickResult();
                worldMutations = update.worldMutationCount();
                materialMutations = update.materialMutationCount();
            } else {
                lastProcessResult = processSession.tick();
                TaskExecutionResult inner = processSession.lastDirectTaskResult().orElseThrow();
                worldMutations = inner.worldMutationCount();
                materialMutations = inner.materialMutationCount();
            }
            if (lastProcessResult instanceof CreateV606GoalDrivenExecution.Progress progress) {
                return TaskExecutionResult.pending(
                        task, assignment, tick, List.of(), worldMutations, materialMutations,
                        (bot ? "Bot" : "Direct") + " v606 progress phase=" + progress.phase()
                                + fanMediumFallback
                                        .map(reason -> ";fan_medium_fallback=" + reason)
                                        .orElse(""));
            }
            if (lastProcessResult instanceof CreateV606GoalDrivenExecution.Completed completed) {
                try {
                    consumeInstallations();
                } catch (IllegalStateException failure) {
                    return failureResult(task, assignment, tick,
                            ConstructionFailureCode.RESERVATION_CONFLICT,
                            failure.getMessage(), worldMutations, materialMutations);
                }
                processCompleted = completed;
                return success(task, assignment, tick, buildObserved(completed),
                        worldMutations, materialMutations,
                        bot ? "create-v606:bot-adjacent-bounded-handler"
                                : "create-v606:direct-bounded-handler"
                                        + fanMediumFallback
                                                .map(reason ->
                                                        ";fan_medium_fallback=" + reason)
                                                .orElse(""));
            }
            CreateV606GoalDrivenExecution.Failed failed =
                    (CreateV606GoalDrivenExecution.Failed) lastProcessResult;
            return failureResult(task, assignment, tick, mapFailure(failed.code()),
                    failed.detail(), worldMutations, materialMutations);
        }

        private TaskExecutionResult verify(
                ConstructionTask task,
                TaskAssignment assignment,
                long tick,
                String provenance) {
            if (processCompleted == null
                    || count(level, deliveryPosition, material.targetItem())
                    < material.targetQuantity()) {
                return failureResult(task, assignment, tick,
                        ConstructionFailureCode.VERIFICATION_FAILED,
                        "Authoritative delivery-buffer output rescan did not match the goal", 0, 0);
            }
            return success(task, assignment, tick, verifyObserved(), 0, 0, provenance);
        }

        private TaskExecutionResult ensureProcessStarted(
                ConstructionTask task,
                TaskAssignment assignment,
                long tick) {
            if (processSession != null) return null;
            CreateV606GoalDrivenExecution.StartResult start = preparedSiteAuthorization == null
                    ? CreateV606GoalDrivenExecution.begin(
                            level, ready, runtime, deliveryPosition, executionMetadata)
                    : CreateV606GoalDrivenExecution.begin(
                            level, preparedSiteAuthorization,
                            preparedSiteAuthorization.worldIdentity(), runtime,
                            deliveryPosition, executionMetadata);
            if (start instanceof CreateV606GoalDrivenExecution.Rejected rejected) {
                return failureResult(task, assignment, tick,
                        mapFailure(rejected.code()), rejected.detail(), 0, 0);
            }
            processSession = ((CreateV606GoalDrivenExecution.Started) start).session();
            return null;
        }

        private int installationQuantity(Item item) {
            return installations.stream().filter(value -> value.item().equals(item))
                    .mapToInt(InstallationStack::quantity).sum();
        }

        private void consumeInstallations() {
            if (installationsConsumed) return;
            for (InstallationStack installation : installations) {
                if (count(level, sourcePosition, installation.item())
                        != installation.quantity()) {
                    throw new IllegalStateException(
                            "Installation escrow changed before settlement: "
                                    + installation.resourceId());
                }
            }
            for (InstallationStack installation : installations) {
                removeQuantity(level, sourcePosition, installation.item(), installation.quantity());
            }
            if (totalItems(level, sourcePosition) != 0) {
                throw new IllegalStateException(
                        "Installation settlement left unaccounted source items");
            }
            installationsConsumed = true;
        }

        private TaskExecutionResult botBuild(
                ConstructionTask task,
                TaskAssignment assignment,
                long tick) {
            return build(task, assignment, tick, true);
        }

        private TaskExecutionResult botVerify(
                ConstructionTask task,
                TaskAssignment assignment,
                long tick) {
            return verify(task, assignment, tick,
                    "create-v606:bot-authoritative-output-readback");
        }

        private BlockPos botWorkTarget() {
            BlockPos3i first = ready.physicalPlan().placements().get(0)
                    .components().get(0).position();
            return safeWorkStand(block(first));
        }

        private BlockPos sourceAccess() { return safeWorkStand(block(sourcePosition)); }

        private List<BlockPos> logisticsDeliveryStations() {
            List<BlockPos> stations = new ArrayList<>();
            for (int index = 0; index < logisticsWorkerCount(); index++) {
                stations.add(safeWorkStand(
                        block(deliveryPosition), new LinkedHashSet<>(stations)));
            }
            return List.copyOf(stations);
        }

        private BlockPos deliveryAccess(ResourceId workerId) {
            int ordinal = workerIds.indexOf(workerId);
            if (ordinal < 0 || ordinal >= logisticsWorkerCount()) {
                throw new IllegalStateException(
                        "Material transport is not owned by a logistics worker: " + workerId);
            }
            return logisticsDeliveryStations().get(ordinal);
        }

        private BlockPos deliveryVerifierAccess() {
            return safeWorkStand(
                    block(deliveryPosition), new LinkedHashSet<>(logisticsDeliveryStations()));
        }

        private BlockPos safeWorkStand(BlockPos work) {
            return safeWorkStand(work, Set.of());
        }

        private BlockPos safeWorkStand(BlockPos work, Set<BlockPos> excluded) {
            List<BlockPos> candidates = new ArrayList<>();
            int highest = Math.min(work.getY(), region.maximum().y());
            for (int y = region.minimum().y(); y <= highest; y++) {
                for (int radius = 1; radius <= 5; radius++) {
                    candidates.add(new BlockPos(work.getX() + radius, y, work.getZ()));
                    candidates.add(new BlockPos(work.getX() - radius, y, work.getZ()));
                    candidates.add(new BlockPos(work.getX(), y, work.getZ() + radius));
                    candidates.add(new BlockPos(work.getX(), y, work.getZ() - radius));
                }
            }
            return candidates.stream()
                    .filter(region::contains)
                    .filter(value -> !excluded.contains(value))
                    .filter(value -> canStand(level, value))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "No safe loaded Bot stand cell near verified work position " + work));
        }

        private MaterialStack materialFor(ConstructionTask task) {
            ResourceId subject = task.postconditions().get(0).subjectId();
            return material.inputs().stream()
                    .filter(input -> input.resourceId().equals(subject))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Transport task lacks an exact input-manifest binding: "
                                    + task.taskId()));
        }

        private Map<ResourceId, String> transportObserved(MaterialStack input) {
            return Map.of(
                    SCHEMA_RESOURCE, input.resourceId().toString(),
                    SCHEMA_QUANTITY, Long.toString(input.quantity()),
                    SCHEMA_DELIVERY, deliveryPosition.toString());
        }

        private Map<ResourceId, String> buildObserved(
                CreateV606GoalDrivenExecution.Completed completed) {
            return Map.of(
                    SCHEMA_PLAN, ready.physicalPlan().id().toString(),
                    SCHEMA_TARGET, completed.target().toString(),
                    SCHEMA_QUANTITY, Long.toString(completed.requiredQuantity()),
                    SCHEMA_OBSERVED, Long.toString(completed.observedQuantity()));
        }

        private Map<ResourceId, String> verifyObserved() {
            return Map.of(
                    SCHEMA_PLAN, ready.physicalPlan().id().toString(),
                    SCHEMA_TARGET, material.targetId().toString(),
                    SCHEMA_QUANTITY, Long.toString(material.targetQuantity()),
                    SCHEMA_OBSERVED,
                    Integer.toString(count(level, deliveryPosition, material.targetItem())));
        }

        private TaskExecutionResult success(
                ConstructionTask task,
                TaskAssignment assignment,
                long tick,
                Map<ResourceId, String> observed,
                int worldMutations,
                int materialMutations,
                String provenance) {
            TaskPostcondition condition = task.postconditions().get(0);
            ExecutionEvidence evidence = new ExecutionEvidence(
                    childId(assignment.assignmentId(), "evidence_" + tick),
                    condition.conditionId(), assignment.sessionId(), assignment.graphId(),
                    assignment.taskId(), assignment.assignmentId(), assignment.executorId(),
                    assignment.mode(), condition.requiredEvidenceKind(), condition.subjectId(),
                    observed, condition.expectedValues(), tick, true, provenance);
            return TaskExecutionResult.success(
                    task, assignment, tick, List.of(evidence),
                    worldMutations, materialMutations, provenance);
        }

        private TaskExecutionResult failureResult(
                ConstructionTask task,
                TaskAssignment assignment,
                long tick,
                ConstructionFailureCode code,
                String detail,
                int worldMutations,
                int materialMutations) {
            return TaskExecutionResult.failure(
                    task, assignment, tick, List.of(),
                    failure(code, task, assignment, detail),
                    worldMutations, materialMutations, detail);
        }

        private TaskAssignment assignment(ConstructionTask task, long tick) {
            Optional<ResourceId> worker;
            if (mode == ExecutionMode.DIRECT) {
                worker = Optional.empty();
            } else if (task.kind() == TaskKind.TRANSPORT_MATERIAL) {
                worker = Optional.of(workerIds.get(taskIndex % logisticsWorkerCount()));
            } else {
                int firstBuilder = logisticsWorkerCount();
                int builderCount = workerIds.size() - firstBuilder;
                int builderOffset = task.kind() == TaskKind.VERIFY_OUTPUT
                                && builderCount > 1
                        ? 1 : 0;
                worker = Optional.of(workerIds.get(firstBuilder + builderOffset));
            }
            ResourceId executorId = switch (mode) {
                case DIRECT -> DIRECT_EXECUTOR;
                case BOTS -> BOT_EXECUTOR;
                case HYBRID -> HYBRID_EXECUTOR;
            };
            TaskOwnership ownership = new TaskOwnership(
                    ready.sessionId(), graph.graphId(), task.taskId(), executorId, mode,
                    worker, taskIndex, tick,
                    Math.addExact(tick, ConstructionTask.MAXIMUM_TASK_TICKS + 1),
                    sha256(ready.sessionId() + "|" + graph.graphId() + "|"
                            + task.taskId() + "|" + mode));
            return TaskAssignment.assign(
                    graph, task.taskId(),
                    childId(ready.sessionId(), "assignment_" + mode.serializedName()
                            + "_" + taskIndex),
                    ownership, 1, tick);
        }

        private Map<BlockPos3i, String> snapshotPlan() {
            Set<BlockPos3i> positions = new LinkedHashSet<>();
            ready.physicalPlan().placements().forEach(placement -> placement.components()
                    .forEach(component -> positions.add(component.position())));
            ready.physicalPlan().routes().forEach(route -> positions.addAll(route.positions()));
            Map<BlockPos3i, String> snapshot = new LinkedHashMap<>();
            positions.stream().sorted(Comparator
                    .comparingInt(BlockPos3i::x)
                    .thenComparingInt(BlockPos3i::y)
                    .thenComparingInt(BlockPos3i::z))
                    .forEach(value -> snapshot.put(value, stateString(level.getBlockState(block(value)))));
            return Collections.unmodifiableMap(snapshot);
        }

        private void requireServerThread() {
            if (!tickBoundaryAccepted(level.getServer().isSameThread(), executionBoundary,
                    Boolean.getBoolean(ENABLE_PROPERTY))) {
                throw new IllegalStateException(
                        "Three-mode execution left its admitted authoritative boundary");
            }
        }

        private final class PhysicalBotWorker implements BotWorker {
            private static final int MAX_PATH_VISITS = 2_048;
            private final ResourceId workerId;
            private final UUID entityId;
            private final ConstructionBotEntity.Role role;
            private final BlockPos3i startPosition;
            private TaskAssignment active;
            private List<BlockPos> path = List.of();
            private BlockPos pathTarget;
            private ItemStack carried = ItemStack.EMPTY;
            private MaterialStack carriedMaterial;
            private boolean transportPickedUp;
            private int assignmentsStarted;
            private int movementTicks;
            private long generation;

            private PhysicalBotWorker(
                    ResourceId workerId,
                    UUID entityId,
                    ConstructionBotEntity.Role role,
                    BlockPos3i startPosition,
                    long generation,
                    int assignmentsStarted,
                    int movementTicks) {
                this.workerId = workerId;
                this.entityId = entityId;
                this.role = role;
                this.startPosition = startPosition;
                this.generation = generation;
                this.assignmentsStarted = assignmentsStarted;
                this.movementTicks = movementTicks;
            }

            private static PhysicalBotWorker spawn(
                    Session owner,
                    ResourceId workerId,
                    BlockPos3i start,
                    ConstructionBotEntity.Role role) {
                if (!owner.region.contains(start) || !canStand(owner.level, block(start))) {
                    throw new IllegalArgumentException("Bot start is not a safe region cell: " + start);
                }
                ConstructionBotEntity entity = Objects.requireNonNull(
                        ConstructionBotEntities.CONSTRUCTION_BOT.get().create(owner.level),
                        "Could not create physical construction Bot");
                entity.setRole(role);
                entity.moveTo(start.x() + 0.5, start.y(), start.z() + 0.5, 0.0F, 0.0F);
                entity.setCustomName(Component.literal(
                        role == ConstructionBotEntity.Role.LOGISTICS
                                ? "Steve Logistics Bot"
                                : "Alex Builder & Inspector Bot"));
                entity.setCustomNameVisible(true);
                entity.addTag(ENTITY_TAG);
                if (!owner.level.addFreshEntity(entity)) {
                    throw new IllegalStateException("Could not spawn physical Bot entity");
                }
                return owner.new PhysicalBotWorker(
                        workerId, entity.getUUID(), role, start, 0, 0, 0);
            }

            private PhysicalBotWorker restoreIdle() {
                if (active != null || !carried.isEmpty() || carriedMaterial != null) {
                    throw new IllegalStateException("Bot reload requires an idle empty-hand boundary");
                }
                ConstructionBotEntity entity = entity();
                if (!entity.getTags().contains(ENTITY_TAG)
                        || entity.role() != role
                        || !region.contains(entity.blockPosition())) {
                    throw new IllegalStateException("Bot reload identity/region reconciliation failed");
                }
                return new PhysicalBotWorker(
                        workerId, entityId, role, startPosition, generation + 1,
                        assignmentsStarted, movementTicks);
            }

            @Override
            public ResourceId workerId() { return workerId; }

            @Override
            public BotWorkerSnapshot snapshot(long currentTick) {
                requireServerThread();
                Entity observed = level.getEntity(entityId);
                if (!(observed instanceof ConstructionBotEntity entity) || !entity.isAlive()) {
                    return new BotWorkerSnapshot(
                            workerId, BotWorkerStatus.OFFLINE,
                            observed == null ? region.minimum() : position(observed.blockPosition()),
                            childId(ready.sessionId(), "bot_region"),
                            capabilities(),
                            Optional.empty(), Optional.empty(), Optional.empty(),
                            inventory(currentTick), 1, false, true, generation, currentTick);
                }
                BotWorkerStatus status = active == null ? BotWorkerStatus.IDLE : BotWorkerStatus.BUSY;
                return new BotWorkerSnapshot(
                        workerId, status, position(entity.blockPosition()),
                        childId(ready.sessionId(), "bot_region"),
                        capabilities(),
                        active == null ? Optional.empty() : Optional.of(active.sessionId()),
                        active == null ? Optional.empty() : Optional.of(active.taskId()),
                        active == null ? Optional.empty() : Optional.of(active.assignmentId()),
                        inventory(currentTick), Math.max(1, Math.round(entity.getHealth())),
                        level.hasChunkAt(entity.blockPosition()), true, generation, currentTick);
            }

            @Override
            public TaskExecutionResult execute(
                    ConstructionTaskGraph ignoredGraph,
                    ConstructionTask task,
                    TaskAssignment assignment,
                ConstructionExecutionContext context) {
                requireServerThread();
                if (!accepts(task.kind())) {
                    return failureResult(task, assignment, context.currentTick(),
                            ConstructionFailureCode.BOT_EXECUTION_CAPABILITY_UNSUPPORTED,
                            "Bot role " + role.serializedName()
                                    + " does not own task kind " + task.kind(), 0, 0);
                }
                if (context.command() == ConstructionExecutionCommand.CANCEL) {
                    active = null;
                    path = List.of();
                    return cancelResult(task, assignment, context.currentTick(),
                            "Physical Bot assignment cancelled at a bounded boundary");
                }
                if (context.command() == ConstructionExecutionCommand.START) {
                    if (active != null) {
                        return failureResult(task, assignment, context.currentTick(),
                                ConstructionFailureCode.WORKER_UNAVAILABLE,
                                "Physical Bot already owns another assignment", 0, 0);
                    }
                    active = assignment;
                    path = List.of();
                    pathTarget = null;
                    transportPickedUp = false;
                    assignmentsStarted++;
                    generation++;
                } else if (active == null
                        || !active.assignmentId().equals(assignment.assignmentId())) {
                    return failureResult(task, assignment, context.currentTick(),
                            ConstructionFailureCode.OWNERSHIP_LOST,
                            "Physical Bot CONTINUE lacks the exact active assignment", 0, 0);
                }
                return switch (task.kind()) {
                    case TRANSPORT_MATERIAL -> transport(task, assignment, context.currentTick());
                    case PLACE_COMPONENT -> buildAtWorkCell(task, assignment, context.currentTick());
                    case VERIFY_OUTPUT -> verifyAtDelivery(task, assignment, context.currentTick());
                    default -> finish(failureResult(task, assignment, context.currentTick(),
                            ConstructionFailureCode.BOT_EXECUTION_CAPABILITY_UNSUPPORTED,
                            "Physical v606 Bot received an unbound task kind", 0, 0));
                };
            }

            private TaskExecutionResult transport(
                    ConstructionTask task,
                    TaskAssignment assignment,
                    long tick) {
                MaterialStack input = materialFor(task);
                BlockPos target = !transportPickedUp
                        ? sourceAccess() : deliveryAccess(workerId);
                if (!atTarget(target)) {
                    return move(task, assignment, tick, target,
                            "Physical Bot advanced one adjacent material-route cell");
                }
                if (!transportPickedUp) {
                    if (!carried.isEmpty() || carriedMaterial != null) {
                        return finish(failureResult(
                                task, assignment, tick,
                                ConstructionFailureCode.RESERVATION_CONFLICT,
                                "Physical Bot already carries another reserved input", 0, 0));
                    }
                    carried = removeExact(
                            level, sourcePosition, input.item(), input.quantity());
                    carriedMaterial = input;
                    transportPickedUp = true;
                    entity().setItemSlot(EquipmentSlot.MAINHAND, carried.copy());
                    generation++;
                    return TaskExecutionResult.pending(
                            task, assignment, tick, List.of(), 0, 1,
                            "Physical Bot picked up one exact reserved input stack");
                }
                if (carriedMaterial == null
                        || !carriedMaterial.resourceId().equals(input.resourceId())
                        || !carried.is(input.item())
                        || carried.getCount() != input.quantity()) {
                    return finish(failureResult(
                            task, assignment, tick,
                            ConstructionFailureCode.RESERVATION_CONFLICT,
                            "Physical Bot carried input no longer matches the exact task reservation",
                            0, 0));
                }
                insertExact(level, deliveryPosition, carried);
                carried = ItemStack.EMPTY;
                carriedMaterial = null;
                entity().setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                transportPickedUp = false;
                generation++;
                return finish(success(task, assignment, tick, transportObserved(input), 0, 1,
                        "create-v606:bot-visible-pickup-navigation-delivery"));
            }

            private void returnCarriedInput() {
                if (!carried.isEmpty()) {
                    if (carriedMaterial == null
                            || !carried.is(carriedMaterial.item())
                            || carried.getCount() != carriedMaterial.quantity()) {
                        throw new IllegalStateException(
                                "Bot cancellation found an unbound carried stack");
                    }
                    returnExact(level, sourcePosition, carried);
                    carried = ItemStack.EMPTY;
                    carriedMaterial = null;
                    transportPickedUp = false;
                    entity().setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                    generation++;
                }
            }

            private TaskExecutionResult buildAtWorkCell(
                    ConstructionTask task,
                    TaskAssignment assignment,
                    long tick) {
                BlockPos target;
                try {
                    target = botWorkTarget();
                } catch (IllegalStateException failure) {
                    return finish(failureResult(task, assignment, tick,
                            ConstructionFailureCode.NAVIGATION_BLOCKED,
                            failure.getMessage(), 0, 0));
                }
                if (!atTarget(target)) {
                    return move(task, assignment, tick, target,
                            "Physical Bot advanced one adjacent verified-build-route cell");
                }
                TaskExecutionResult startFailure = ensureProcessStarted(task, assignment, tick);
                if (startFailure != null) return finish(startFailure);
                lookAt(block(processSession.nextVerifiedBotWorkPosition()));
                TaskExecutionResult result = botBuild(task, assignment, tick);
                return result.outcome() == TaskExecutionOutcome.PENDING ? result : finish(result);
            }

            private TaskExecutionResult verifyAtDelivery(
                    ConstructionTask task,
                    TaskAssignment assignment,
                    long tick) {
                BlockPos target = deliveryVerifierAccess();
                if (!atTarget(target)) {
                    return move(task, assignment, tick, target,
                            "Physical Bot advanced to its distinct adjacent output-verification station");
                }
                return finish(botVerify(task, assignment, tick));
            }

            private TaskExecutionResult move(
                    ConstructionTask task,
                    TaskAssignment assignment,
                    long tick,
                    BlockPos target,
                    String detail) {
                BlockPos current = entity().blockPosition();
                if (current.equals(target)) {
                    entity().advanceToward(target);
                    movementTicks++;
                    generation++;
                    return TaskExecutionResult.pending(
                            task, assignment, tick, List.of(), 0, 0, detail);
                }
                if (!target.equals(pathTarget) || path.isEmpty()
                        || !canStand(level, path.get(0))) {
                    pathTarget = target.immutable();
                    path = planPath(current, target);
                }
                if (path.isEmpty()) {
                    return finish(failureResult(task, assignment, tick,
                            ConstructionFailureCode.NAVIGATION_BLOCKED,
                            "No bounded loaded adjacent-cell path reaches the verified work cell"
                                    + " role=" + role.serializedName()
                                    + " task=" + task.kind()
                                    + " start=" + current
                                    + " target=" + target
                                    + " startStand=" + canStand(level, current)
                                    + " targetStand=" + canStand(level, target),
                            0, 0));
                }
                BlockPos next = path.get(0);
                // During a smooth up/down transition blockPosition() can be the
                // intermediate cell.  The queued path was adjacency-validated at
                // planning time; only the destination needs revalidation in flight.
                if (!region.contains(next)
                        || !canStand(level, next)) {
                    path = List.of();
                    return TaskExecutionResult.pending(
                            task, assignment, tick, List.of(), 0, 0,
                            "Physical Bot invalidated one stale path and will repath");
                }
                boolean reached = entity().advanceToward(next);
                if (reached) {
                    path = List.copyOf(path.subList(1, path.size()));
                }
                movementTicks++;
                generation++;
                return TaskExecutionResult.pending(
                        task, assignment, tick, List.of(), 0, 0, detail);
            }

            private List<BlockPos> planPath(BlockPos start, BlockPos target) {
                if (!region.contains(start) || !region.contains(target)
                        || !canStand(level, target)) return List.of();
                ArrayDeque<BlockPos> queue = new ArrayDeque<>();
                Map<BlockPos, BlockPos> parents = new LinkedHashMap<>();
                queue.add(start.immutable());
                parents.put(start.immutable(), null);
                while (!queue.isEmpty() && parents.size() <= MAX_PATH_VISITS) {
                    BlockPos current = queue.removeFirst();
                    if (current.equals(target)) break;
                    for (BlockPos next : BoundedBotNavigation.neighbours(level, current)) {
                        next = next.immutable();
                        if (!parents.containsKey(next) && region.contains(next)
                                && canStand(level, next)) {
                            parents.put(next, current);
                            queue.addLast(next);
                        }
                    }
                }
                if (!parents.containsKey(target)) return List.of();
                List<BlockPos> reversed = new ArrayList<>();
                BlockPos cursor = target;
                while (!cursor.equals(start)) {
                    reversed.add(cursor);
                    cursor = parents.get(cursor);
                    if (cursor == null) return List.of();
                }
                Collections.reverse(reversed);
                return List.copyOf(reversed);
            }

            private void lookAt(BlockPos target) {
                entity().face(target);
            }

            private TaskExecutionResult finish(TaskExecutionResult result) {
                active = null;
                path = List.of();
                pathTarget = null;
                generation++;
                return result;
            }

            private BotInventory inventory(long tick) {
                Map<ResourceId, Long> values = carried.isEmpty()
                        ? Map.of()
                        : Map.of(
                                Objects.requireNonNull(carriedMaterial, "carriedMaterial")
                                        .resourceId(),
                                (long) carried.getCount());
                return new BotInventory(workerId, 64, values, generation, tick);
            }

            private boolean atTarget(BlockPos target) {
                ConstructionBotEntity entity = entity();
                double deltaX = entity.getX() - (target.getX() + 0.5D);
                double deltaY = entity.getY() - target.getY();
                double deltaZ = entity.getZ() - (target.getZ() + 0.5D);
                return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ < 1.0E-6D;
            }

            private boolean accepts(TaskKind kind) {
                return role == ConstructionBotEntity.Role.LOGISTICS
                        ? kind == TaskKind.TRANSPORT_MATERIAL
                        : kind == TaskKind.PLACE_COMPONENT || kind == TaskKind.VERIFY_OUTPUT;
            }

            private Set<BotWorkerCapability> capabilities() {
                return role == ConstructionBotEntity.Role.LOGISTICS
                        ? EnumSet.of(
                                BotWorkerCapability.REGISTRATION,
                                BotWorkerCapability.NAVIGATE_TO,
                                BotWorkerCapability.REACHABILITY,
                                BotWorkerCapability.FETCH_MATERIAL,
                                BotWorkerCapability.CARRY_MATERIAL,
                                BotWorkerCapability.DELIVER_MATERIAL,
                                BotWorkerCapability.RETURN_LEFTOVERS,
                                BotWorkerCapability.REPORT_EVIDENCE,
                                BotWorkerCapability.RETRY_REPATH,
                                BotWorkerCapability.CANCEL,
                                BotWorkerCapability.RELOAD_RECOVERY)
                        : EnumSet.of(
                                BotWorkerCapability.REGISTRATION,
                                BotWorkerCapability.NAVIGATE_TO,
                                BotWorkerCapability.REACHABILITY,
                                BotWorkerCapability.LOOK_AT,
                                BotWorkerCapability.PLACE_BLOCK,
                                BotWorkerCapability.REMOVE_SESSION_OWNED_BLOCK,
                                BotWorkerCapability.INTERACT_SAFE_MACHINE_FACE,
                                BotWorkerCapability.VERIFY_BLOCK_STATE,
                                BotWorkerCapability.REPORT_EVIDENCE,
                                BotWorkerCapability.RETRY_REPATH,
                                BotWorkerCapability.CANCEL,
                                BotWorkerCapability.RELOAD_RECOVERY);
            }

            private WorkerActivity activity() {
                return new WorkerActivity(
                        workerId, role.serializedName(), assignmentsStarted, movementTicks,
                        startPosition, position(entity().blockPosition()));
            }

            private ConstructionBotEntity entity() {
                Entity value = level.getEntity(entityId);
                if (!(value instanceof ConstructionBotEntity entity) || !entity.isAlive()) {
                    throw new IllegalStateException("Physical Bot entity is missing or dead");
                }
                return entity;
            }

            private void discard() {
                Entity value = level.getEntity(entityId);
                if (value != null && value.getTags().contains(ENTITY_TAG)) value.discard();
            }
        }
    }

    private static List<ConstructionTask> createTasks(
            ExecutionReadyPlan ready,
            MaterialManifest material,
            BlockPos3i deliveryPosition) {
        ResourceId placementReservation = childId(ready.sessionId(), "three_mode_placement");
        ResourceId physicalElement = ready.physicalPlan().placements().get(0).logicalNodeId();
        Set<ExecutionMode> modes = EnumSet.allOf(ExecutionMode.class);
        CleanupPolicy cleanup = new CleanupPolicy(
                CleanupScope.SESSION_OWNED_REVERSIBLE_ONLY, true, true,
                CleanupPolicy.MAX_CLEANUP_MUTATIONS);
        List<ConstructionTask> tasks = new ArrayList<>();
        Set<ResourceId> materialReservations = new LinkedHashSet<>();
        for (int index = 0; index < material.inputs().size(); index++) {
            MaterialStack input = material.inputs().get(index);
            ResourceId materialReservation =
                    childId(ready.sessionId(), "three_mode_material_" + index);
            materialReservations.add(materialReservation);
            tasks.add(new ConstructionTask(
                    childId(ready.sessionId(), "three_mode_transport_" + index),
                    new VerifiedPlanTaskSource(
                            ready.physicalPlan().id(),
                            TaskSourceKind.VERIFIED_RESOURCE_REQUIREMENT,
                            physicalElement, index),
                    CAPABILITY, TaskKind.TRANSPORT_MATERIAL,
                    ConstructionTaskClass.MATERIAL_TRANSPORT, modes, List.of(),
                    List.of(new TaskPostcondition(
                            childId(
                                    ready.sessionId(),
                                    "three_mode_material_delivered_" + index),
                            TaskConditionKind.MATERIAL_DELIVERED, input.resourceId(),
                            ExecutionEvidenceKind.MATERIAL_DELIVERED,
                            Map.of(
                                    SCHEMA_RESOURCE, input.resourceId().toString(),
                                    SCHEMA_QUANTITY, Long.toString(input.quantity()),
                                    SCHEMA_DELIVERY, deliveryPosition.toString()))),
                    RetryPolicy.NO_RETRY, true, RecoveryPolicy.REFUSE, cleanup,
                    Set.of(), Set.of(materialReservation), Set.of(),
                    ConstructionTask.MAXIMUM_TASK_TICKS,
                    Map.of(SCHEMA_PLAN, ready.physicalPlan().id().toString())));
        }
        ConstructionTask build = new ConstructionTask(
                childId(ready.sessionId(), "three_mode_build"),
                new VerifiedPlanTaskSource(
                        ready.physicalPlan().id(), TaskSourceKind.VERIFIED_PLACEMENT,
                        physicalElement, 0),
                CAPABILITY, TaskKind.PLACE_COMPONENT,
                ConstructionTaskClass.CREATE_MACHINE, modes, List.of(),
                List.of(new TaskPostcondition(
                        childId(ready.sessionId(), "three_mode_process_output"),
                        TaskConditionKind.OUTPUT_PRESENT, material.targetId(),
                        ExecutionEvidenceKind.OUTPUT_VERIFIED,
                        Map.of(
                                SCHEMA_PLAN, ready.physicalPlan().id().toString(),
                                SCHEMA_TARGET, material.targetId().toString(),
                                SCHEMA_QUANTITY, Long.toString(material.targetQuantity())))),
                RetryPolicy.NO_RETRY, true, RecoveryPolicy.REFUSE, cleanup,
                Set.of(placementReservation), Set.copyOf(materialReservations), Set.of(),
                ConstructionTask.MAXIMUM_TASK_TICKS,
                Map.of(SCHEMA_PLAN, ready.physicalPlan().id().toString()));
        tasks.add(build);
        ConstructionTask verify = new ConstructionTask(
                childId(ready.sessionId(), "three_mode_verify"),
                new VerifiedPlanTaskSource(
                        ready.physicalPlan().id(), TaskSourceKind.VERIFIED_SYSTEM_CHECK,
                        physicalElement, 0),
                CAPABILITY, TaskKind.VERIFY_OUTPUT,
                ConstructionTaskClass.SYSTEM_VERIFICATION, modes, List.of(),
                List.of(new TaskPostcondition(
                        childId(ready.sessionId(), "three_mode_output_rescan"),
                        TaskConditionKind.OUTPUT_PRESENT, material.targetId(),
                        ExecutionEvidenceKind.OUTPUT_VERIFIED,
                        Map.of(
                                SCHEMA_PLAN, ready.physicalPlan().id().toString(),
                                SCHEMA_TARGET, material.targetId().toString(),
                                SCHEMA_QUANTITY, Long.toString(material.targetQuantity())))),
                RetryPolicy.NO_RETRY, true, RecoveryPolicy.REFUSE, cleanup,
                Set.of(), Set.of(), Set.of(),
                ConstructionTask.MAXIMUM_TASK_TICKS,
                Map.of(SCHEMA_PLAN, ready.physicalPlan().id().toString()));
        tasks.add(verify);
        return List.copyOf(tasks);
    }

    private static ConstructionTaskGraph createGraph(
            ExecutionReadyPlan ready,
            List<ConstructionTask> tasks) {
        EnumMap<ExecutionMode, ModeCapabilityDeclaration> modes = new EnumMap<>(ExecutionMode.class);
        modes.put(ExecutionMode.DIRECT, new ModeCapabilityDeclaration(
                ExecutionMode.DIRECT, CapabilitySupport.SUPPORTED,
                Set.of(DIRECT_CAPABILITY), ""));
        modes.put(ExecutionMode.BOTS, new ModeCapabilityDeclaration(
                ExecutionMode.BOTS, CapabilitySupport.SUPPORTED,
                Set.of(BOT_CAPABILITY), ""));
        modes.put(ExecutionMode.HYBRID, new ModeCapabilityDeclaration(
                ExecutionMode.HYBRID, CapabilitySupport.SUPPORTED,
                Set.of(HYBRID_CAPABILITY), ""));
        String runtimeFingerprint = ready.physicalPlan().candidate().boundPlan().graph()
                .runtimeFingerprint();
        CapabilityExecutionDescriptor descriptor = new CapabilityExecutionDescriptor(
                CAPABILITY, IMPLEMENTATION, ADAPTER, runtimeFingerprint,
                EnumSet.of(TaskKind.TRANSPORT_MATERIAL, TaskKind.PLACE_COMPONENT,
                        TaskKind.VERIFY_OUTPUT),
                modes, Map.of(id("construction:contract"), "executor-v1"));
        int buildIndex = tasks.size() - 2;
        int verifyIndex = tasks.size() - 1;
        List<TaskDependency> dependencies = new ArrayList<>();
        for (int index = 0; index < buildIndex; index++) {
            dependencies.add(new TaskDependency(
                    tasks.get(index).taskId(), tasks.get(buildIndex).taskId(),
                    TaskDependencyKind.MATERIAL_DELIVERY,
                    tasks.get(index).postconditionIds()));
        }
        dependencies.add(new TaskDependency(
                tasks.get(buildIndex).taskId(), tasks.get(verifyIndex).taskId(),
                TaskDependencyKind.FINISH_TO_START,
                tasks.get(buildIndex).postconditionIds()));
        return new ConstructionTaskGraph(
                childId(ready.sessionId(), "three_mode_graph"),
                ready.physicalPlan().id(), runtimeFingerprint,
                ready.physicalPlan().candidate().snapshotFingerprint(),
                List.of(descriptor), tasks, List.copyOf(dependencies));
    }

    private static TaskExecutionResult cancelResult(
            ConstructionTask task,
            TaskAssignment assignment,
            long tick,
            String detail) {
        TaskFailure failure = failure(
                ConstructionFailureCode.CANCELLED, task, assignment, detail);
        return TaskExecutionResult.cancelled(
                task, assignment, tick, List.of(), failure, detail);
    }

    private static TaskFailure failure(
            ConstructionFailureCode code,
            ConstructionTask task,
            TaskAssignment assignment,
            String detail) {
        return new TaskFailure(
                code.id(), code.category(), false, Optional.empty(),
                Optional.of(task.taskId()), detail, List.of(assignment.assignmentId()),
                "Inspect the exact three-mode assignment evidence before retrying");
    }

    private static ConstructionFailureCode mapFailure(ExecutionReadinessFailureCode code) {
        return switch (code) {
            case FORMAL_WORLD_FORBIDDEN -> ConstructionFailureCode.HIGH_RISK_INTERACTION_REFUSED;
            case REQUIRED_CHUNK_UNLOADED -> ConstructionFailureCode.CHUNK_NOT_LOADED;
            case INPUT_RESOURCE_MISSING -> ConstructionFailureCode.MATERIAL_INSUFFICIENT;
            case BLOCK_PLACEMENT_BLOCKED, ROUTE_CONSTRUCTION_FAILED,
                    PHYSICAL_PLAN_STALE, TARGET_AREA_CHANGED ->
                    ConstructionFailureCode.WORLD_STATE_CHANGED;
            case EXECUTION_CANCELLED -> ConstructionFailureCode.CANCELLED;
            default -> ConstructionFailureCode.VERIFICATION_FAILED;
        };
    }

    private static Map<ResourceId, Long> requiredInputs(
            ExecutionReadyPlan ready,
            CreateV606VerifiedExecutionMetadata executionMetadata) {
        Map<ResourceId, Long> required = new LinkedHashMap<>();
        var candidate = ready.physicalPlan().candidate().boundPlan().graph().logicalPlan().candidate();
        java.util.stream.Stream.concat(
                        candidate.rawMaterials().stream(), candidate.ownedResourcesUsed().stream())
                .forEach(value -> required.merge(value.resourceId(), value.amount(), Math::addExact));
        if (executionMetadata != null) {
            executionMetadata.fuelReservations().forEach(reservation ->
                    required.merge(
                            reservation.fuel().resourceId(),
                            reservation.fuel().amount(),
                            Math::addExact));
            executionMetadata.fluidInputsByStep().values().stream()
                    .flatMap(List::stream)
                    .forEach(fluid -> {
                        PlacementItemBinding.Bucket bucket = PlacementItemBinding
                                .bucketsFor(fluid.resourceId(), fluid.amount())
                                .orElseThrow(() -> new IllegalArgumentException(
                                        "No reviewed bucket material for " + fluid.resourceId()));
                        required.merge(bucket.item(), bucket.count(), Math::addExact);
                    });
        }
        Map<ResourceId, Long> ordered = new LinkedHashMap<>();
        required.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().toString()))
                .forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
        return Collections.unmodifiableMap(ordered);
    }

    private static MaterialManifest materialManifest(
            ExecutionReadyPlan ready,
            BoundedMaterialManifest required) {
        List<MaterialStack> inputs = new ArrayList<>();
        for (BoundedMaterialManifest.Entry input : required.entries()) {
            Item item = ForgeRegistries.ITEMS.getValue(
                    ResourceLocation.fromNamespaceAndPath(
                            input.resourceId().namespace(),
                            input.resourceId().path()));
            if (item == null) {
                throw new IllegalArgumentException(
                        "Input item is not registered: " + input.resourceId());
            }
            int quantity = Math.toIntExact(input.quantity());
            if (quantity > item.getMaxStackSize()) {
                throw new IllegalArgumentException(
                        "Input exceeds one bounded carried stack: " + input.resourceId()
                                + " quantity=" + quantity
                                + " maxStack=" + item.getMaxStackSize());
            }
            inputs.add(new MaterialStack(input.resourceId(), item, quantity));
        }
        var goal = ready.physicalPlan().candidate().boundPlan().graph().logicalPlan().candidate().goal();
        Item targetItem = ForgeRegistries.ITEMS.getValue(
                ResourceLocation.fromNamespaceAndPath(
                        goal.target().namespace(), goal.target().path()));
        if (targetItem == null) {
            throw new IllegalArgumentException("Target item is not registered: " + goal.target());
        }
        return new MaterialManifest(
                inputs,
                goal.target(), targetItem, Math.toIntExact(goal.quantity()));
    }

    private static List<InstallationStack> installationManifest(
            Map<ResourceId, Long> values) {
        ArrayList<InstallationStack> result = new ArrayList<>();
        values.entrySet().stream().sorted(Map.Entry.comparingByKey(
                        Comparator.comparing(ResourceId::toString)))
                .forEach(value -> {
                    if (value.getValue() == null || value.getValue() < 1
                            || value.getValue() > 1_728) {
                        throw new IllegalArgumentException(
                                "Installation material quantity is outside one chest: "
                                        + value.getKey());
                    }
                    Item item = ForgeRegistries.ITEMS.getValue(
                            ResourceLocation.fromNamespaceAndPath(
                                    value.getKey().namespace(), value.getKey().path()));
                    if (item == null || item == net.minecraft.world.item.Items.AIR) {
                        throw new IllegalArgumentException(
                                "Installation material has no exact registered item: "
                                        + value.getKey());
                    }
                    result.add(new InstallationStack(value.getKey(), item,
                            Math.toIntExact(value.getValue())));
                });
        return List.copyOf(result);
    }

    private static boolean sourceMatches(
            ServerLevel level,
            BlockPos3i source,
            MaterialManifest process,
            List<InstallationStack> installations,
            boolean includeProcess) {
        LinkedHashMap<Item, Integer> expected = new LinkedHashMap<>();
        installations.forEach(value -> expected.merge(
                value.item(), value.quantity(), Math::addExact));
        if (includeProcess) process.inputs().forEach(value -> expected.merge(
                value.item(), value.quantity(), Math::addExact));
        if (expected.entrySet().stream().anyMatch(value ->
                count(level, source, value.getKey()) != value.getValue())) return false;
        return totalItems(level, source) == expected.values().stream()
                .mapToInt(Integer::intValue).sum();
    }

    private static void requireChest(ServerLevel level, BlockPos3i position, String name) {
        if (!(level.getBlockEntity(block(position)) instanceof ChestBlockEntity)) {
            throw new IllegalArgumentException("Dedicated " + name + " is not a real chest");
        }
    }

    private static ItemStack removeExact(
            ServerLevel level,
            BlockPos3i position,
            Item item,
            int quantity) {
        requireChest(level, position, "source");
        ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(block(position));
        if (count(level, position, item) < quantity) {
            throw new IllegalStateException("Source chest no longer contains the exact reserved stack");
        }
        ItemStack removed = new ItemStack(item, quantity);
        removeQuantity(level, position, item, quantity);
        return removed;
    }

    private static void removeQuantity(
            ServerLevel level,
            BlockPos3i position,
            Item item,
            int quantity) {
        requireChest(level, position, "source");
        ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(block(position));
        if (count(level, position, item) < quantity) {
            throw new IllegalStateException("Source chest no longer contains the reserved quantity");
        }
        int remaining = quantity;
        for (int slot = 0; slot < chest.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = chest.getItem(slot);
            if (!stack.is(item)) continue;
            int take = Math.min(remaining, stack.getCount());
            stack.shrink(take);
            remaining -= take;
            if (stack.isEmpty()) chest.setItem(slot, ItemStack.EMPTY);
        }
        if (remaining != 0) throw new IllegalStateException("Exact source removal was incomplete");
        chest.setChanged();
    }

    private static void insertExact(ServerLevel level, BlockPos3i position, ItemStack value) {
        requireChest(level, position, "delivery");
        if (value.isEmpty()) {
            throw new IllegalStateException("Cannot insert an empty exact delivery stack");
        }
        ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(block(position));
        if (count(level, position, value.getItem()) != 0) {
            throw new IllegalStateException(
                    "Delivery chest already contains the reserved input identity");
        }
        int emptySlot = -1;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            if (chest.getItem(slot).isEmpty()) {
                emptySlot = slot;
                break;
            }
        }
        if (emptySlot < 0) {
            throw new IllegalStateException(
                    "Delivery chest has no bounded slot for the exact input stack");
        }
        chest.setItem(emptySlot, value.copy());
        chest.setChanged();
    }

    /**
     * Returns one withdrawn stack to its dedicated source without rejecting legitimate escrow of
     * the same item identity. This is intentionally distinct from delivery insertion: C-10 uses
     * shafts both in its survival transmission and as the deploying input, so cancellation must
     * merge the returned raw shaft with the still-reserved structural shafts.
     */
    private static void returnExact(ServerLevel level, BlockPos3i position, ItemStack value) {
        requireChest(level, position, "source");
        if (value.isEmpty()) {
            throw new IllegalStateException("Cannot return an empty exact source stack");
        }
        ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(block(position));
        int before = count(level, position, value.getItem());
        int remaining = value.getCount();
        for (int slot = 0; slot < chest.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = chest.getItem(slot);
            if (stack.isEmpty() || !ItemStack.isSameItemSameTags(stack, value)) continue;
            int accepted = Math.min(remaining, stack.getMaxStackSize() - stack.getCount());
            if (accepted <= 0) continue;
            stack.grow(accepted);
            remaining -= accepted;
        }
        for (int slot = 0; slot < chest.getContainerSize() && remaining > 0; slot++) {
            if (!chest.getItem(slot).isEmpty()) continue;
            int accepted = Math.min(remaining, value.getMaxStackSize());
            ItemStack inserted = value.copy();
            inserted.setCount(accepted);
            chest.setItem(slot, inserted);
            remaining -= accepted;
        }
        if (remaining != 0
                || count(level, position, value.getItem()) != before + value.getCount()) {
            throw new IllegalStateException(
                    "Source chest cannot accept the exact cancelled input stack");
        }
        chest.setChanged();
    }

    private static int count(ServerLevel level, BlockPos3i position, Item item) {
        requireChest(level, position, "resource buffer");
        ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(block(position));
        int total = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack stack = chest.getItem(slot);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private static int totalItems(ServerLevel level, BlockPos3i position) {
        requireChest(level, position, "resource buffer");
        ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(block(position));
        int total = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            total += chest.getItem(slot).getCount();
        }
        return total;
    }

    private static boolean canStand(ServerLevel level, BlockPos position) {
        if (!level.hasChunkAt(position) || !level.hasChunkAt(position.above())
                || !level.hasChunkAt(position.below())) return false;
        BlockState feet = level.getBlockState(position);
        BlockState head = level.getBlockState(position.above());
        BlockState floor = level.getBlockState(position.below());
        return (feet.isAir() || feet.canBeReplaced())
                && (head.isAir() || head.canBeReplaced())
                && floor.isFaceSturdy(level, position.below(), Direction.UP);
    }

    private static int manhattan(BlockPos left, BlockPos right) {
        return Math.abs(left.getX() - right.getX())
                + Math.abs(left.getY() - right.getY())
                + Math.abs(left.getZ() - right.getZ());
    }

    private static String stateString(BlockState state) {
        ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        StringBuilder value = new StringBuilder(blockId == null ? "unregistered" : blockId.toString());
        List<Map.Entry<Property<?>, Comparable<?>>> properties =
                new ArrayList<>(state.getValues().entrySet());
        properties.sort(Comparator.comparing(entry -> entry.getKey().getName()));
        if (!properties.isEmpty()) {
            value.append('[');
            for (int index = 0; index < properties.size(); index++) {
                if (index > 0) value.append(',');
                Map.Entry<Property<?>, Comparable<?>> entry = properties.get(index);
                value.append(entry.getKey().getName()).append('=').append(entry.getValue());
            }
            value.append(']');
        }
        return value.toString();
    }

    private static ResourceId childId(ResourceId parent, String suffix) {
        return new ResourceId(parent.namespace(), parent.path() + "/" + suffix);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Java runtime lacks SHA-256", failure);
        }
    }

    private static BlockPos block(BlockPos3i value) {
        return new BlockPos(value.x(), value.y(), value.z());
    }

    private static BlockPos3i position(BlockPos value) {
        return new BlockPos3i(value.getX(), value.getY(), value.getZ());
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }

    private record MaterialStack(
            ResourceId resourceId,
            Item item,
            int quantity) {
        private MaterialStack {
            Objects.requireNonNull(resourceId, "resourceId");
            Objects.requireNonNull(item, "item");
            if (quantity <= 0 || quantity > 64) {
                throw new IllegalArgumentException("quantity must be in [1,64]");
            }
        }
    }

    private record InstallationStack(ResourceId resourceId, Item item, int quantity) {
        private InstallationStack {
            Objects.requireNonNull(resourceId, "resourceId");
            Objects.requireNonNull(item, "item");
            if (quantity < 1 || quantity > 1_728) {
                throw new IllegalArgumentException(
                        "installation quantity must fit one dedicated chest");
            }
        }
    }

    private record MaterialManifest(
            List<MaterialStack> inputs,
            ResourceId targetId,
            Item targetItem,
            int targetQuantity) {
        private MaterialManifest {
            inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs"));
            Objects.requireNonNull(targetId, "targetId");
            Objects.requireNonNull(targetItem, "targetItem");
            if (inputs.isEmpty()
                    || inputs.size() > BoundedMaterialManifest.MAX_DISTINCT_RESOURCES) {
                throw new IllegalArgumentException(
                        "inputs must contain 1-"
                                + BoundedMaterialManifest.MAX_DISTINCT_RESOURCES
                                + " exact stacks");
            }
            if (targetQuantity <= 0) {
                throw new IllegalArgumentException("targetQuantity must be positive");
            }
        }
    }
}
