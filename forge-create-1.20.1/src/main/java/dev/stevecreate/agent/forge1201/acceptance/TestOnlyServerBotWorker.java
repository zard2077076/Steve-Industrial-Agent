package dev.stevecreate.agent.forge1201.acceptance;

import dev.stevecreate.agent.core.execution.construction.BotInventory;
import dev.stevecreate.agent.core.execution.construction.BotWorker;
import dev.stevecreate.agent.core.execution.construction.BotWorkerCapability;
import dev.stevecreate.agent.core.execution.construction.BotWorkerSnapshot;
import dev.stevecreate.agent.core.execution.construction.BotWorkerStatus;
import dev.stevecreate.agent.core.execution.construction.ConstructionExecutionCommand;
import dev.stevecreate.agent.core.execution.construction.ConstructionExecutionContext;
import dev.stevecreate.agent.core.execution.construction.ConstructionFailureCode;
import dev.stevecreate.agent.core.execution.construction.ConstructionTask;
import dev.stevecreate.agent.core.execution.construction.ConstructionTaskGraph;
import dev.stevecreate.agent.core.execution.construction.ExecutionEvidence;
import dev.stevecreate.agent.core.execution.construction.ExecutionEvidenceKind;
import dev.stevecreate.agent.core.execution.construction.ExecutionMode;
import dev.stevecreate.agent.core.execution.construction.MaterialDelivery;
import dev.stevecreate.agent.core.execution.construction.MaterialLedgerResult;
import dev.stevecreate.agent.core.execution.construction.MaterialReservation;
import dev.stevecreate.agent.core.execution.construction.MaterialReservationRequest;
import dev.stevecreate.agent.core.execution.construction.MaterialReturnPolicy;
import dev.stevecreate.agent.core.execution.construction.MaterialSource;
import dev.stevecreate.agent.core.execution.construction.MaterialSourceScope;
import dev.stevecreate.agent.core.execution.construction.ReservationLedger;
import dev.stevecreate.agent.core.execution.construction.TaskAssignment;
import dev.stevecreate.agent.core.execution.construction.TaskExecutionResult;
import dev.stevecreate.agent.core.execution.construction.TaskFailure;
import dev.stevecreate.agent.core.execution.construction.TaskFailureCategory;
import dev.stevecreate.agent.core.execution.construction.TaskKind;
import dev.stevecreate.agent.core.execution.construction.TaskPostcondition;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.forge1201.entity.ConstructionBotEntities;
import dev.stevecreate.agent.forge1201.entity.ConstructionBotEntity;
import dev.stevecreate.agent.forge1201.navigation.BoundedBotNavigation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Repository-owned, visible, test-only Bot worker.
 *
 * <p>The visible identity is the existing Phase III {@link ConstructionBotEntity}; no second Bot
 * entity type or scheduler exists. Movement is smooth inside one adjacent prevalidated cell per
 * executor call; every material and block action is an explicit server-thread allowlist operation.
 * This class is disabled outside the dedicated Bot GameTest path proof.</p>
 */
final class TestOnlyServerBotWorker implements BotWorker {
    static final String ENTITY_TAG = "steve_industrial_test_only_bot";
    static final int MAX_PATH_VISITS = 256;
    static final int MAX_REPATHS = 3;

    private static final Set<BotWorkerCapability> CAPABILITIES = Collections.unmodifiableSet(
            EnumSet.allOf(BotWorkerCapability.class));
    private static final Set<Block> PLACE_ALLOWLIST = Set.of(Blocks.COBBLESTONE, Blocks.LEVER);

    private final ServerLevel level;
    private final ResourceId workerId;
    private final ResourceId sessionId;
    private final ResourceId authorizedRegionId;
    private final TestRegion region;
    private final ConstructionBotEntity entity;
    private final Map<ResourceId, TestOnlyBotTaskSpec> taskSpecs;
    private final ReservationLedger ledger;
    private final Map<BlockPos, TestOnlyServerBotWorkerState.OwnedBlock> ownedBlocks;
    private BotWorkerStatus status = BotWorkerStatus.IDLE;
    private TaskAssignment activeAssignment;
    private List<BlockPos> remainingPath = List.of();
    private int repaths;
    private long generation;
    private long lastTick = -1;

    private TestOnlyServerBotWorker(
            ServerLevel level,
            ResourceId workerId,
            ResourceId sessionId,
            ResourceId authorizedRegionId,
            TestRegion region,
            ConstructionBotEntity entity,
            Map<ResourceId, TestOnlyBotTaskSpec> taskSpecs,
            ReservationLedger ledger,
            Map<BlockPos, TestOnlyServerBotWorkerState.OwnedBlock> ownedBlocks,
            long generation,
            long lastTick) {
        this.level = Objects.requireNonNull(level, "level");
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.authorizedRegionId = Objects.requireNonNull(authorizedRegionId, "authorizedRegionId");
        this.region = Objects.requireNonNull(region, "region");
        this.entity = Objects.requireNonNull(entity, "entity");
        this.taskSpecs = copyTaskSpecs(taskSpecs);
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.ownedBlocks = new LinkedHashMap<>(Objects.requireNonNull(ownedBlocks, "ownedBlocks"));
        this.generation = generation;
        this.lastTick = lastTick;
    }

    static TestOnlyServerBotWorker spawn(
            ServerLevel level,
            ResourceId workerId,
            ResourceId sessionId,
            ResourceId authorizedRegionId,
            TestRegion region,
            BlockPos start,
            Map<ResourceId, TestOnlyBotTaskSpec> taskSpecs,
            int inventoryCapacity,
            long tick) {
        AcceptanceRuntimeGuard.requireDevelopmentRuntime("TestOnlyServerBotWorker");
        TestOnlyBotWorldGuard.requireAuthorized(level);
        Objects.requireNonNull(start, "start");
        if (tick < 0 || !region.contains(start) || !canStand(level, start)) {
            throw new IllegalArgumentException("Bot spawn position is not one safe loaded region cell");
        }
        ConstructionBotEntity entity = Objects.requireNonNull(
                ConstructionBotEntities.CONSTRUCTION_BOT.get().create(level),
                "Failed to create existing construction Bot entity");
        boolean logistics = Math.floorMod(workerId.toString().hashCode(), 2) == 0;
        entity.setRole(logistics
                ? ConstructionBotEntity.Role.LOGISTICS
                : ConstructionBotEntity.Role.BUILDER_INSPECTOR);
        entity.moveTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5, 0.0F, 0.0F);
        entity.setCustomName(Component.literal(
                (logistics ? "Steve" : "Alex") + " Fleet Bot " + workerId.path()));
        entity.setCustomNameVisible(true);
        entity.addTag(ENTITY_TAG);
        if (!level.addFreshEntity(entity)) {
            throw new IllegalStateException("Failed to spawn test-only Bot entity");
        }

        Map<ResourceId, TestOnlyBotTaskSpec> specs = copyTaskSpecs(taskSpecs);
        ReservationLedger ledger = new ReservationLedger(
                MaterialReturnPolicy.RETURN_TO_ORIGINAL_SOURCE);
        ledger.registerInventory(new BotInventory(workerId, inventoryCapacity, Map.of(), 0, tick));
        registerMaterialSources(level, sessionId, region, specs, ledger, tick);
        return new TestOnlyServerBotWorker(
                level, workerId, sessionId, authorizedRegionId, region, entity,
                specs, ledger, Map.of(), 0, tick);
    }

    static TestOnlyServerBotWorker restore(
            ServerLevel level,
            ResourceId authorizedRegionId,
            TestRegion region,
            Map<ResourceId, TestOnlyBotTaskSpec> taskSpecs,
            TestOnlyServerBotWorkerState state) {
        AcceptanceRuntimeGuard.requireDevelopmentRuntime("TestOnlyServerBotWorker");
        TestOnlyBotWorldGuard.requireAuthorized(level);
        Objects.requireNonNull(state, "state");
        Entity restored = level.getEntity(state.entityId());
        if (!(restored instanceof ConstructionBotEntity entity)
                || !entity.getTags().contains(ENTITY_TAG)
                || !entity.isAlive()
                || !region.contains(entity.blockPosition())) {
            throw new IllegalArgumentException("Bot reload identity/entity/region did not reconcile exactly");
        }
        ReservationLedger ledger = ReservationLedger.restore(state.ledgerSnapshot());
        BotInventory inventory = ledger.inventories().get(state.workerId());
        if (inventory == null) {
            throw new IllegalArgumentException("Bot reload ledger lacks its exact inventory");
        }
        verifyHeldInventory(entity, inventory);
        verifyPhysicalSources(level, region, taskSpecs, state.ledgerSnapshot());
        state.ownedBlocks().forEach((position, owned) -> {
            if (!region.contains(position)
                    || !level.hasChunkAt(position)
                    || !level.getBlockState(position).equals(owned.afterState())
                    || !owned.sessionId().equals(state.sessionId())) {
                throw new IllegalArgumentException(
                        "Bot reload owned block did not reconcile with the authoritative world");
            }
        });
        return new TestOnlyServerBotWorker(
                level, state.workerId(), state.sessionId(), authorizedRegionId, region,
                entity, taskSpecs, ledger, state.ownedBlocks(),
                state.generation(), state.savedTick());
    }

    @Override
    public ResourceId workerId() {
        return workerId;
    }

    @Override
    public BotWorkerSnapshot snapshot(long currentTick) {
        requireThreadAndTick(currentTick);
        Entity live = level.getEntity(entity.getUUID());
        if (!(live instanceof ConstructionBotEntity stand)) {
            return new BotWorkerSnapshot(
                    workerId, BotWorkerStatus.OFFLINE, position(entity.blockPosition()),
                    authorizedRegionId, CAPABILITIES, Optional.empty(), Optional.empty(),
                    Optional.empty(), inventory(), 1, false, true, generation, currentTick);
        }
        boolean loaded = level.hasChunkAt(stand.blockPosition());
        BotWorkerStatus observed = stand.isAlive() ? status : BotWorkerStatus.DEAD;
        int health = stand.isAlive() ? Math.max(1, Math.round(stand.getHealth())) : 0;
        return new BotWorkerSnapshot(
                workerId,
                observed,
                position(stand.blockPosition()),
                authorizedRegionId,
                CAPABILITIES,
                observed == BotWorkerStatus.BUSY
                        ? Optional.of(activeAssignment.sessionId()) : Optional.empty(),
                observed == BotWorkerStatus.BUSY
                        ? Optional.of(activeAssignment.taskId()) : Optional.empty(),
                observed == BotWorkerStatus.BUSY
                        ? Optional.of(activeAssignment.assignmentId()) : Optional.empty(),
                inventory(),
                health,
                loaded,
                true,
                generation,
                currentTick);
    }

    @Override
    public TaskExecutionResult execute(
            ConstructionTaskGraph graph,
            ConstructionTask task,
            TaskAssignment assignment,
            ConstructionExecutionContext context) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(assignment, "assignment");
        Objects.requireNonNull(context, "context");
        requireThreadAndTick(context.currentTick());
        lastTick = context.currentTick();
        try {
            TestOnlyBotWorldGuard.requireAuthorized(level);
        } catch (IllegalStateException failure) {
            return fail(task, assignment, context.currentTick(),
                    ConstructionFailureCode.PATH_OUTSIDE_AUTHORIZED_REGION, false,
                    failure.getMessage(), 0, 0);
        }
        TestOnlyBotTaskSpec spec = taskSpecs.get(task.taskId());
        if (spec == null || !spec.taskId().equals(task.taskId())) {
            return fail(task, assignment, context.currentTick(),
                    ConstructionFailureCode.BOT_EXECUTION_CAPABILITY_UNSUPPORTED, false,
                    "No exact test-only game action binding exists for the task", 0, 0);
        }
        if (!assignment.sessionId().equals(sessionId)) {
            return fail(task, assignment, context.currentTick(),
                    ConstructionFailureCode.OWNERSHIP_LOST, false,
                    "Bot assignment session differs from the authorized test session", 0, 0);
        }
        if (context.command() == ConstructionExecutionCommand.CANCEL) {
            return cancel(task, assignment, context);
        }
        if (context.command() == ConstructionExecutionCommand.RECOVER) {
            return TaskExecutionResult.recoveryRequired(
                    task, assignment, context.currentTick(), context.priorEvidence(),
                    failure(ConstructionFailureCode.RECOVERY_RECONCILIATION_REQUIRED,
                            task, assignment, false,
                            "Game-owned Bot recovery requires an idle snapshot or exact external reconciliation"),
                    "Bot recovery refused without an exact idle-boundary rescan");
        }
        if (context.command() == ConstructionExecutionCommand.START) {
            if (activeAssignment != null) {
                return fail(task, assignment, context.currentTick(),
                        ConstructionFailureCode.WORKER_UNAVAILABLE, false,
                        "Bot already owns another exact assignment", 0, 0);
            }
            Optional<TaskExecutionResult> invalid = validateStart(task, assignment, context, spec);
            if (invalid.isPresent()) return invalid.orElseThrow();
            activeAssignment = assignment;
            status = BotWorkerStatus.BUSY;
            remainingPath = List.of();
            repaths = 0;
            nextGeneration();
        } else if (activeAssignment == null
                || !activeAssignment.assignmentId().equals(assignment.assignmentId())) {
            return fail(task, assignment, context.currentTick(),
                    ConstructionFailureCode.OWNERSHIP_LOST, false,
                    "Bot CONTINUE command lacks the exact active assignment", 0, 0);
        }

        if (!atCellCenter(spec.navigationTarget())) {
            Optional<TaskExecutionResult> movementFailure = advanceOneCell(
                    task, assignment, context, spec.navigationTarget());
            if (movementFailure.isPresent()) return movementFailure.orElseThrow();
            return TaskExecutionResult.pending(
                    task, assignment, context.currentTick(), List.of(), 0, 0,
                    "Test-only Bot advanced one adjacent path cell");
        }

        ActionResult action = perform(task, assignment, context.currentTick(), spec);
        if (action.failure().isPresent()) {
            clearActive();
            return action.failure().orElseThrow();
        }
        List<ExecutionEvidence> evidence = evidence(
                task, assignment, context.currentTick(), spec, action.observedValues());
        if (evidence.stream().anyMatch(item -> !item.passed())) {
            clearActive();
            return TaskExecutionResult.failure(
                    task, assignment, context.currentTick(), evidence,
                    failure(ConstructionFailureCode.VERIFICATION_FAILED, task, assignment, false,
                            "Authoritative Bot readback did not match a task postcondition"),
                    action.worldMutations(), action.materialMutations(),
                    "Bot action completed but objective readback failed");
        }
        clearActive();
        return TaskExecutionResult.success(
                task, assignment, context.currentTick(), evidence,
                action.worldMutations(), action.materialMutations(),
                "Test-only Bot action and authoritative readback succeeded");
    }

    TestOnlyServerBotWorkerState snapshotState(long tick) {
        requireThreadAndTick(tick);
        if (status != BotWorkerStatus.IDLE || activeAssignment != null) {
            throw new IllegalStateException(
                    "Bot worker state may persist only at an idle reconciled boundary");
        }
        verifyHeldInventory(entity, inventory());
        lastTick = tick;
        return new TestOnlyServerBotWorkerState(
                workerId, sessionId, entity.getUUID(), ledger.snapshot(tick),
                ownedBlocks, generation, tick);
    }

    ReservationLedger ledger() {
        return ledger;
    }

    UUID entityId() {
        return entity.getUUID();
    }

    void discardEntity(long tick) {
        requireThreadAndTick(tick);
        if (status != BotWorkerStatus.IDLE || activeAssignment != null
                || inventory().usedCapacity() != 0) {
            throw new IllegalStateException(
                    "Fleet Bot entity cleanup requires an exact idle empty boundary");
        }
        entity.discard();
        lastTick = tick;
    }

    private Optional<TaskExecutionResult> validateStart(
            ConstructionTask task,
            TaskAssignment assignment,
            ConstructionExecutionContext context,
            TestOnlyBotTaskSpec spec) {
        if (!region.contains(spec.navigationTarget())
                || !level.hasChunkAt(spec.navigationTarget())) {
            return Optional.of(fail(task, assignment, context.currentTick(),
                    ConstructionFailureCode.PATH_OUTSIDE_AUTHORIZED_REGION, false,
                    "Bot navigation target is outside the loaded authorized region", 0, 0));
        }
        if (spec.blockTarget().isPresent()) {
            BlockPos target = spec.blockTarget().orElseThrow();
            if (!region.contains(target) || !level.hasChunkAt(target)) {
                return Optional.of(fail(task, assignment, context.currentTick(),
                        ConstructionFailureCode.PATH_OUTSIDE_AUTHORIZED_REGION, false,
                        "Bot block target is outside the loaded authorized region", 0, 0));
            }
        }
        if (!supportedBinding(task, spec)) {
            return Optional.of(fail(task, assignment, context.currentTick(),
                    ConstructionFailureCode.BOT_EXECUTION_CAPABILITY_UNSUPPORTED, false,
                    "Task kind and exact test-only action binding are inconsistent", 0, 0));
        }
        return Optional.empty();
    }

    private boolean supportedBinding(ConstructionTask task, TestOnlyBotTaskSpec spec) {
        boolean block = spec.blockTarget().isPresent();
        boolean material = spec.material().isPresent();
        return switch (task.kind()) {
            case FETCH_MATERIAL, TRANSPORT_MATERIAL, RETURN_MATERIAL -> material && !block;
            case PLACE_COMPONENT -> block && material
                    && PLACE_ALLOWLIST.contains(spec.expectedBlockState().orElseThrow().getBlock());
            case REMOVE_SESSION_OWNED_COMPONENT, VERIFY_STATE -> block && !material;
            case SAFE_MACHINE_INTERACTION -> block && !material
                    && spec.expectedBlockState().orElseThrow().is(Blocks.LEVER);
            default -> false;
        };
    }

    private Optional<TaskExecutionResult> advanceOneCell(
            ConstructionTask task,
            TaskAssignment assignment,
            ConstructionExecutionContext context,
            BlockPos target) {
        BlockPos current = entity.blockPosition();
        if (remainingPath.isEmpty() || !canStand(level, remainingPath.get(0))) {
            if (repaths >= MAX_REPATHS) {
                clearActive();
                return Optional.of(fail(task, assignment, context.currentTick(),
                        ConstructionFailureCode.NAVIGATION_BLOCKED,
                        mayRetry(task, assignment, ConstructionFailureCode.NAVIGATION_BLOCKED),
                        "Bot exhausted its bounded adjacent-cell repath budget", 0, 0));
            }
            remainingPath = planPath(current, target);
            repaths++;
            if (remainingPath.isEmpty()) {
                clearActive();
                return Optional.of(fail(task, assignment, context.currentTick(),
                        ConstructionFailureCode.NAVIGATION_BLOCKED,
                        mayRetry(task, assignment, ConstructionFailureCode.NAVIGATION_BLOCKED),
                        "No bounded loaded path exists inside the authorized region", 0, 0));
            }
        }
        BlockPos next = remainingPath.get(0);
        // Do not derive adjacency from blockPosition() while the entity is halfway
        // through a smooth stair move; the path queue already proved that edge.
        if (!region.contains(next) || !canStand(level, next)) {
            remainingPath = List.of();
            return Optional.empty();
        }
        lookAt(next);
        if (entity.advanceToward(next)) {
            remainingPath = List.copyOf(remainingPath.subList(1, remainingPath.size()));
        }
        nextGeneration();
        return Optional.empty();
    }

    private List<BlockPos> planPath(BlockPos start, BlockPos target) {
        if (!region.contains(start) || !region.contains(target) || !canStand(level, target)) {
            return List.of();
        }
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Map<BlockPos, BlockPos> parents = new LinkedHashMap<>();
        queue.add(start.immutable());
        parents.put(start.immutable(), null);
        while (!queue.isEmpty() && parents.size() <= MAX_PATH_VISITS) {
            BlockPos current = queue.removeFirst();
            if (current.equals(target)) break;
            for (BlockPos next : BoundedBotNavigation.neighbours(level, current)) {
                if (parents.size() >= MAX_PATH_VISITS) break;
                next = next.immutable();
                if (!parents.containsKey(next) && region.contains(next) && canStand(level, next)) {
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

    private ActionResult perform(
            ConstructionTask task,
            TaskAssignment assignment,
            long tick,
            TestOnlyBotTaskSpec spec) {
        return switch (task.kind()) {
            case FETCH_MATERIAL -> fetch(task, assignment, tick, spec.material().orElseThrow());
            case TRANSPORT_MATERIAL -> observeOnlyMaterial(task, assignment, tick,
                    spec.material().orElseThrow(), ExecutionEvidenceKind.NAVIGATION_REACHED);
            case PLACE_COMPONENT -> place(task, assignment, tick, spec);
            case REMOVE_SESSION_OWNED_COMPONENT -> removeOwned(task, assignment, tick, spec);
            case SAFE_MACHINE_INTERACTION -> interactLever(task, assignment, tick, spec);
            case VERIFY_STATE -> verifyBlock(task, assignment, tick, spec);
            case RETURN_MATERIAL -> returnMaterial(task, assignment, tick,
                    spec.material().orElseThrow());
            default -> actionFailure(fail(task, assignment, tick,
                    ConstructionFailureCode.BOT_EXECUTION_CAPABILITY_UNSUPPORTED, false,
                    "Task kind is outside the test-only Bot action allowlist", 0, 0));
        };
    }

    private ActionResult fetch(
            ConstructionTask task,
            TaskAssignment assignment,
            long tick,
            TestOnlyBotTaskSpec.MaterialBinding binding) {
        ChestBlockEntity chest = exactChest(binding.sourcePosition());
        if (chest == null || !entity.blockPosition().equals(taskSpecs.get(task.taskId()).navigationTarget())) {
            return actionFailure(fail(task, assignment, tick,
                    ConstructionFailureCode.MATERIAL_SOURCE_FORBIDDEN, false,
                    "Exact authorized test chest is absent or not reached", 0, 0));
        }
        if (!physicalSourceMatches(binding, tick)) {
            return actionFailure(fail(task, assignment, tick,
                    ConstructionFailureCode.WORLD_STATE_CHANGED, false,
                    "Physical test chest drifted from the authoritative material ledger", 0, 0));
        }
        MaterialReservation existing = ledger.reservations().get(binding.reservationId());
        if (existing == null) {
            MaterialLedgerResult reserved = ledger.reserve(new MaterialReservationRequest(
                    binding.reservationId(), sessionId, task.taskId(), binding.sourceId(),
                    binding.resourceId(), binding.quantity(), tick));
            if (!(reserved instanceof MaterialLedgerResult.Success)) {
                return actionFailure(materialFailure(task, assignment, tick, reserved));
            }
        } else if (!existing.sessionId().equals(sessionId)
                || !existing.taskId().equals(task.taskId())
                || !existing.sourceId().equals(binding.sourceId())
                || !existing.resourceId().equals(binding.resourceId())
                || existing.reservedQuantity() != binding.quantity()
                || existing.withdrawnQuantity() != 0
                || existing.status()
                != dev.stevecreate.agent.core.execution.construction.ReservationStatus.ACTIVE) {
            return actionFailure(fail(task, assignment, tick,
                    ConstructionFailureCode.RECOVERY_RECONCILIATION_REQUIRED, false,
                    "Existing material reservation cannot be replayed or rebound", 0, 0));
        }
        if (count(chest, binding.item()) < binding.quantity()
                || !remove(chest, binding.item(), binding.quantity())) {
            return actionFailure(fail(task, assignment, tick,
                    ConstructionFailureCode.MATERIAL_INSUFFICIENT, false,
                    "Physical authorized chest lacks the exact reserved material", 0, 0));
        }
        MaterialLedgerResult withdrawn = ledger.withdraw(
                binding.reservationId(), binding.deliveryId(), workerId,
                binding.quantity(), tick);
        if (!(withdrawn instanceof MaterialLedgerResult.Success)) {
            insert(chest, binding.item(), binding.quantity());
            return actionFailure(materialFailure(task, assignment, tick, withdrawn));
        }
        entity.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(binding.item(), binding.quantity()));
        nextGeneration();
        return actionSuccess(materialObservation(binding), 0, 1);
    }

    private ActionResult observeOnlyMaterial(
            ConstructionTask task,
            TaskAssignment assignment,
            long tick,
            TestOnlyBotTaskSpec.MaterialBinding binding,
            ExecutionEvidenceKind ignored) {
        MaterialDelivery delivery = ledger.deliveries().get(binding.deliveryId());
        if (delivery == null || delivery.outstandingQuantity() < binding.quantity()
                || inventory().quantity(binding.resourceId()) < binding.quantity()) {
            return actionFailure(fail(task, assignment, tick,
                    ConstructionFailureCode.WORLD_STATE_CHANGED, false,
                    "Bot no longer carries the exact material delivery", 0, 0));
        }
        return actionSuccess(materialObservation(binding), 0, 0);
    }

    private ActionResult place(
            ConstructionTask task,
            TaskAssignment assignment,
            long tick,
            TestOnlyBotTaskSpec spec) {
        BlockPos target = spec.blockTarget().orElseThrow();
        BlockState expected = spec.expectedBlockState().orElseThrow();
        TestOnlyBotTaskSpec.MaterialBinding binding = spec.material().orElseThrow();
        BlockState before = level.getBlockState(target);
        if (!before.canBeReplaced() || before.hasBlockEntity()
                || !PLACE_ALLOWLIST.contains(expected.getBlock())
                || inventory().quantity(binding.resourceId()) < binding.quantity()) {
            return actionFailure(fail(task, assignment, tick,
                    ConstructionFailureCode.WORLD_STATE_CHANGED, false,
                    "Bot placement target/material no longer matches the exact safe precondition", 0, 0));
        }
        lookAt(target);
        if (!level.setBlock(target, expected, Block.UPDATE_ALL)
                || !level.getBlockState(target).equals(expected)) {
            return actionFailure(fail(task, assignment, tick,
                    ConstructionFailureCode.VERIFICATION_FAILED, false,
                    "Bot placement did not read back the exact expected BlockState", 0, 0));
        }
        MaterialLedgerResult delivered = ledger.deliver(binding.deliveryId(), binding.quantity(), tick);
        if (!(delivered instanceof MaterialLedgerResult.Success)) {
            level.setBlock(target, before, Block.UPDATE_ALL);
            return actionFailure(materialFailure(task, assignment, tick, delivered));
        }
        ownedBlocks.put(target.immutable(), new TestOnlyServerBotWorkerState.OwnedBlock(
                target, sessionId, task.taskId(), before, expected));
        updateHeldItem(binding);
        nextGeneration();
        return actionSuccess(blockObservation(target), 1, 1);
    }

    private ActionResult removeOwned(
            ConstructionTask task,
            TaskAssignment assignment,
            long tick,
            TestOnlyBotTaskSpec spec) {
        BlockPos target = spec.blockTarget().orElseThrow();
        TestOnlyServerBotWorkerState.OwnedBlock owned = ownedBlocks.get(target);
        if (owned == null
                || !owned.sessionId().equals(sessionId)
                || !level.getBlockState(target).equals(owned.afterState())) {
            return actionFailure(fail(task, assignment, tick,
                    ConstructionFailureCode.CLEANUP_CONFLICT, false,
                    "Bot may remove only an unchanged block owned by this exact session", 0, 0));
        }
        lookAt(target);
        if (!level.setBlock(target, owned.beforeState(), Block.UPDATE_ALL)
                || !level.getBlockState(target).equals(owned.beforeState())) {
            return actionFailure(fail(task, assignment, tick,
                    ConstructionFailureCode.VERIFICATION_FAILED, false,
                    "Session-owned block removal did not read back its exact before-state", 0, 0));
        }
        ownedBlocks.remove(target);
        nextGeneration();
        return actionSuccess(blockObservation(target), 1, 0);
    }

    private ActionResult interactLever(
            ConstructionTask task,
            TaskAssignment assignment,
            long tick,
            TestOnlyBotTaskSpec spec) {
        BlockPos target = spec.blockTarget().orElseThrow();
        BlockState current = level.getBlockState(target);
        BlockState expected = spec.expectedBlockState().orElseThrow();
        if (!current.is(Blocks.LEVER)
                || !current.hasProperty(BlockStateProperties.POWERED)
                || !expected.is(Blocks.LEVER)
                || !expected.equals(current.cycle(BlockStateProperties.POWERED))) {
            return actionFailure(fail(task, assignment, tick,
                    ConstructionFailureCode.HIGH_RISK_INTERACTION_REFUSED, false,
                    "Only the exact known lever face/state transition is allowed", 0, 0));
        }
        lookAt(target);
        if (!level.setBlock(target, expected, Block.UPDATE_ALL)
                || !level.getBlockState(target).equals(expected)) {
            return actionFailure(fail(task, assignment, tick,
                    ConstructionFailureCode.VERIFICATION_FAILED, false,
                    "Safe lever interaction did not read back the exact toggled state", 0, 0));
        }
        nextGeneration();
        return actionSuccess(blockObservation(target), 1, 0);
    }

    private ActionResult verifyBlock(
            ConstructionTask task,
            TaskAssignment assignment,
            long tick,
            TestOnlyBotTaskSpec spec) {
        BlockPos target = spec.blockTarget().orElseThrow();
        if (!level.getBlockState(target).equals(spec.expectedBlockState().orElseThrow())) {
            return actionFailure(fail(task, assignment, tick,
                    ConstructionFailureCode.VERIFICATION_FAILED, false,
                    "Bot BlockState verification observed a different exact state", 0, 0));
        }
        lookAt(target);
        return actionSuccess(blockObservation(target), 0, 0);
    }

    private ActionResult returnMaterial(
            ConstructionTask task,
            TaskAssignment assignment,
            long tick,
            TestOnlyBotTaskSpec.MaterialBinding binding) {
        ChestBlockEntity chest = exactChest(binding.sourcePosition());
        MaterialDelivery delivery = ledger.deliveries().get(binding.deliveryId());
        if (chest == null || delivery == null
                || delivery.outstandingQuantity() < binding.quantity()
                || inventory().quantity(binding.resourceId()) < binding.quantity()
                || capacity(chest, binding.item()) < binding.quantity()
                || !physicalSourceMatches(binding, tick)) {
            return actionFailure(fail(task, assignment, tick,
                    ConstructionFailureCode.WORLD_STATE_CHANGED, false,
                    "Bot cannot return the exact carried material to its original source", 0, 0));
        }
        if (!insert(chest, binding.item(), binding.quantity())) {
            return actionFailure(fail(task, assignment, tick,
                    ConstructionFailureCode.MATERIAL_INSUFFICIENT, false,
                    "Authorized test chest lacks capacity for the exact return", 0, 0));
        }
        MaterialLedgerResult returned = ledger.returnToSource(
                binding.deliveryId(), binding.quantity(), tick);
        if (!(returned instanceof MaterialLedgerResult.Success)) {
            remove(chest, binding.item(), binding.quantity());
            return actionFailure(materialFailure(task, assignment, tick, returned));
        }
        updateHeldItem(binding);
        nextGeneration();
        return actionSuccess(materialObservation(binding), 0, 1);
    }

    private List<ExecutionEvidence> evidence(
            ConstructionTask task,
            TaskAssignment assignment,
            long tick,
            TestOnlyBotTaskSpec spec,
            Map<ResourceId, String> observed) {
        List<ExecutionEvidence> result = new ArrayList<>();
        for (TaskPostcondition postcondition : task.postconditions()) {
            boolean passed = observed.entrySet().containsAll(postcondition.expectedValues().entrySet());
            result.add(new ExecutionEvidence(
                    new ResourceId("steve_industrial", "bot_evidence/"
                            + assignment.assignmentId().namespace() + "/"
                            + assignment.assignmentId().path() + "/"
                            + postcondition.conditionId().namespace() + "/"
                            + postcondition.conditionId().path() + "/" + tick),
                    postcondition.conditionId(), assignment.sessionId(), assignment.graphId(),
                    assignment.taskId(), assignment.assignmentId(), assignment.executorId(),
                    ExecutionMode.BOTS, postcondition.requiredEvidenceKind(),
                    postcondition.subjectId(), observed, postcondition.expectedValues(),
                    tick, passed, "forge1201:test-only-server-bot-authoritative-readback"));
        }
        return List.copyOf(result);
    }

    private Map<ResourceId, String> materialObservation(
            TestOnlyBotTaskSpec.MaterialBinding binding) {
        Map<ResourceId, String> values = baseObservation();
        values.put(id("schema:resource"), binding.resourceId().toString());
        values.put(id("schema:carried_quantity"),
                Long.toString(inventory().quantity(binding.resourceId())));
        ChestBlockEntity chest = exactChest(binding.sourcePosition());
        values.put(id("schema:source_quantity"),
                Integer.toString(chest == null ? -1 : count(chest, binding.item())));
        return Map.copyOf(values);
    }

    private Map<ResourceId, String> blockObservation(BlockPos target) {
        Map<ResourceId, String> values = baseObservation();
        BlockState state = level.getBlockState(target);
        var blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        values.put(id("schema:block"), blockId == null ? "unregistered" : blockId.toString());
        values.put(id("schema:block_state"), stateString(state));
        values.put(id("schema:target"), positionString(target));
        return Map.copyOf(values);
    }

    private Map<ResourceId, String> baseObservation() {
        Map<ResourceId, String> values = new LinkedHashMap<>();
        values.put(id("schema:worker"), workerId.toString());
        values.put(id("schema:position"), positionString(entity.blockPosition()));
        values.put(id("schema:test_only_movement"), "true");
        return values;
    }

    private TaskExecutionResult cancel(
            ConstructionTask task,
            TaskAssignment assignment,
            ConstructionExecutionContext context) {
        if (activeAssignment != null
                && !activeAssignment.assignmentId().equals(assignment.assignmentId())) {
            return fail(task, assignment, context.currentTick(),
                    ConstructionFailureCode.OWNERSHIP_LOST, false,
                    "Cancellation does not own the active Bot assignment", 0, 0);
        }
        clearActive();
        Map<ResourceId, String> values = baseObservation();
        values.put(id("schema:cancelled"), "true");
        ExecutionEvidence evidence = new ExecutionEvidence(
                new ResourceId("steve_industrial", "bot_cancel/"
                        + assignment.assignmentId().namespace() + "/"
                        + assignment.assignmentId().path() + "/" + context.currentTick()),
                id("condition:bot_cancelled"), assignment.sessionId(), assignment.graphId(),
                assignment.taskId(), assignment.assignmentId(), assignment.executorId(),
                ExecutionMode.BOTS, ExecutionEvidenceKind.CANCELLATION_CONFIRMED,
                workerId, values, Map.of(id("schema:cancelled"), "true"),
                context.currentTick(), true,
                "forge1201:test-only-server-bot-cancel-readback");
        TaskFailure cancellation = new TaskFailure(
                ConstructionFailureCode.CANCELLED.id(), TaskFailureCategory.CANCELLED,
                false, Optional.empty(), Optional.of(task.taskId()),
                "Test-only Bot stopped its exact assignment without another action",
                List.of(assignment.assignmentId()), "Return any carried material separately");
        return TaskExecutionResult.cancelled(
                task, assignment, context.currentTick(), List.of(evidence), cancellation,
                "Test-only Bot cancellation confirmed");
    }

    private TaskExecutionResult materialFailure(
            ConstructionTask task,
            TaskAssignment assignment,
            long tick,
            MaterialLedgerResult result) {
        MaterialLedgerResult.Failure failure = (MaterialLedgerResult.Failure) result;
        return fail(task, assignment, tick, failure.code(), false, failure.detail(), 0, 0);
    }

    private TaskExecutionResult fail(
            ConstructionTask task,
            TaskAssignment assignment,
            long tick,
            ConstructionFailureCode code,
            boolean retryable,
            String detail,
            int worldMutations,
            int materialMutations) {
        return TaskExecutionResult.failure(
                task, assignment, tick, List.of(),
                failure(code, task, assignment, retryable, detail),
                worldMutations, materialMutations, detail);
    }

    private static TaskFailure failure(
            ConstructionFailureCode code,
            ConstructionTask task,
            TaskAssignment assignment,
            boolean retryable,
            String detail) {
        return new TaskFailure(
                code.id(), code.category(), retryable, Optional.empty(), Optional.of(task.taskId()),
                detail, List.of(assignment.assignmentId()),
                "Inspect exact region, path, worker, material and world readback evidence");
    }

    private static boolean mayRetry(
            ConstructionTask task,
            TaskAssignment assignment,
            ConstructionFailureCode code) {
        return assignment.attempt() < task.retryPolicy().maximumAttempts()
                && task.retryPolicy().retryableFailures().contains(code.id());
    }

    private ActionResult actionFailure(TaskExecutionResult failure) {
        return new ActionResult(Map.of(), 0, 0, Optional.of(failure));
    }

    private ActionResult actionSuccess(
            Map<ResourceId, String> observed,
            int worldMutations,
            int materialMutations) {
        return new ActionResult(observed, worldMutations, materialMutations, Optional.empty());
    }

    private void clearActive() {
        status = BotWorkerStatus.IDLE;
        activeAssignment = null;
        remainingPath = List.of();
        repaths = 0;
        nextGeneration();
    }

    private void updateHeldItem(TestOnlyBotTaskSpec.MaterialBinding binding) {
        long carried = inventory().quantity(binding.resourceId());
        entity.setItemSlot(EquipmentSlot.MAINHAND,
                carried == 0 ? ItemStack.EMPTY
                        : new ItemStack(binding.item(), Math.toIntExact(carried)));
    }

    private BotInventory inventory() {
        BotInventory inventory = ledger.inventories().get(workerId);
        if (inventory == null) throw new IllegalStateException("Bot ledger lost its inventory");
        return inventory;
    }

    private boolean physicalSourceMatches(
            TestOnlyBotTaskSpec.MaterialBinding binding,
            long tick) {
        ChestBlockEntity chest = exactChest(binding.sourcePosition());
        MaterialSource source = ledger.snapshot(tick).sources().get(binding.sourceId());
        return chest != null
                && source != null
                && source.available(binding.resourceId()) == count(chest, binding.item());
    }

    private ChestBlockEntity exactChest(BlockPos position) {
        if (!region.contains(position) || !level.hasChunkAt(position)) return null;
        return level.getBlockEntity(position) instanceof ChestBlockEntity chest ? chest : null;
    }

    private void lookAt(BlockPos target) {
        entity.face(target);
    }

    private boolean atCellCenter(BlockPos target) {
        return entity.blockPosition().equals(target)
                && Math.abs(entity.getX() - (target.getX() + 0.5D)) < 1.0E-5D
                && Math.abs(entity.getZ() - (target.getZ() + 0.5D)) < 1.0E-5D;
    }

    private void requireThreadAndTick(long tick) {
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("Bot worker must run on the authoritative server thread");
        }
        if (tick < 0 || tick < lastTick) {
            throw new IllegalArgumentException("Bot worker tick cannot be negative or move backwards");
        }
    }

    private long nextGeneration() {
        generation = Math.addExact(generation, 1);
        return generation;
    }

    private static void registerMaterialSources(
            ServerLevel level,
            ResourceId sessionId,
            TestRegion region,
            Map<ResourceId, TestOnlyBotTaskSpec> specs,
            ReservationLedger ledger,
            long tick) {
        Map<ResourceId, SourceBuilder> sources = new LinkedHashMap<>();
        specs.values().stream()
                .flatMap(value -> value.material().stream())
                .forEach(binding -> {
                    if (!region.contains(binding.sourcePosition())
                            || !level.hasChunkAt(binding.sourcePosition())
                            || !(level.getBlockEntity(binding.sourcePosition())
                            instanceof ChestBlockEntity chest)) {
                        throw new IllegalArgumentException(
                                "Every Bot material binding requires one loaded in-region test chest");
                    }
                    SourceBuilder builder = sources.computeIfAbsent(
                            binding.sourceId(), ignored -> new SourceBuilder(binding.sourcePosition()));
                    if (!builder.position().equals(binding.sourcePosition())) {
                        throw new IllegalArgumentException("One material source identity names two chests");
                    }
                    builder.quantities().put(binding.resourceId(), (long) count(chest, binding.item()));
                });
        sources.forEach((sourceId, source) -> ledger.registerSource(new MaterialSource(
                sourceId, MaterialSourceScope.TEST_ONLY_BOUNDED_SOURCE, position(source.position()),
                Set.of(sessionId), source.quantities(), true, 0, tick)));
    }

    private static void verifyPhysicalSources(
            ServerLevel level,
            TestRegion region,
            Map<ResourceId, TestOnlyBotTaskSpec> taskSpecs,
            dev.stevecreate.agent.core.execution.construction.ReservationLedgerSnapshot snapshot) {
        Map<ResourceId, Map<ResourceId, TestOnlyBotTaskSpec.MaterialBinding>> bindings =
                new LinkedHashMap<>();
        taskSpecs.values().stream()
                .flatMap(spec -> spec.material().stream())
                .forEach(binding -> bindings
                        .computeIfAbsent(binding.sourceId(), ignored -> new LinkedHashMap<>())
                        .putIfAbsent(binding.resourceId(), binding));
        if (!bindings.keySet().equals(snapshot.sources().keySet())) {
            throw new IllegalArgumentException(
                    "Bot reload material sources differ from the exact task bindings");
        }
        snapshot.sources().forEach((sourceId, source) -> {
            Map<ResourceId, TestOnlyBotTaskSpec.MaterialBinding> resources = bindings.get(sourceId);
            if (!resources.keySet().equals(source.availableQuantities().keySet())) {
                throw new IllegalArgumentException(
                        "Bot reload source resources differ from the exact task bindings");
            }
            resources.forEach((resourceId, binding) -> {
                if (!region.contains(binding.sourcePosition())
                        || !(level.getBlockEntity(binding.sourcePosition())
                        instanceof ChestBlockEntity chest)
                        || source.available(resourceId) != count(chest, binding.item())) {
                    throw new IllegalArgumentException(
                            "Bot reload physical source does not match its authoritative ledger");
                }
            });
        });
    }

    private static void verifyHeldInventory(
            ConstructionBotEntity entity, BotInventory inventory) {
        ItemStack held = entity.getItemBySlot(EquipmentSlot.MAINHAND);
        if (inventory.quantities().isEmpty()) {
            if (!held.isEmpty()) {
                throw new IllegalArgumentException(
                        "Bot visible held item disagrees with its empty ledger inventory");
            }
            return;
        }
        if (inventory.quantities().size() != 1) {
            throw new IllegalArgumentException(
                    "Test-only visible Bot supports one exact carried resource at reload");
        }
        Map.Entry<ResourceId, Long> carried = inventory.quantities().entrySet().iterator().next();
        var heldId = ForgeRegistries.ITEMS.getKey(held.getItem());
        if (heldId == null
                || !heldId.toString().equals(carried.getKey().toString())
                || held.getCount() != carried.getValue()) {
            throw new IllegalArgumentException(
                    "Bot visible held item disagrees with its exact ledger inventory");
        }
    }

    private static Map<ResourceId, TestOnlyBotTaskSpec> copyTaskSpecs(
            Map<ResourceId, TestOnlyBotTaskSpec> values) {
        Objects.requireNonNull(values, "taskSpecs");
        if (values.isEmpty() || values.size() > 256) {
            throw new IllegalArgumentException("Bot task spec count must be between 1 and 256");
        }
        List<Map.Entry<ResourceId, TestOnlyBotTaskSpec>> ordered = values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceId::toString)))
                .toList();
        Map<ResourceId, TestOnlyBotTaskSpec> copied = new LinkedHashMap<>();
        for (Map.Entry<ResourceId, TestOnlyBotTaskSpec> entry : ordered) {
            ResourceId id = Objects.requireNonNull(entry.getKey(), "task spec id");
            TestOnlyBotTaskSpec spec = Objects.requireNonNull(entry.getValue(), "task spec");
            if (!id.equals(spec.taskId())) {
                throw new IllegalArgumentException("Bot task spec map key does not match task identity");
            }
            copied.put(id, spec);
        }
        return Map.copyOf(copied);
    }

    private static boolean canStand(ServerLevel level, BlockPos position) {
        return level.hasChunkAt(position)
                && level.getBlockState(position).isAir()
                && level.getBlockState(position.above()).isAir()
                && !level.getBlockState(position.below()).isAir();
    }

    private static int manhattan(BlockPos left, BlockPos right) {
        return Math.abs(left.getX() - right.getX())
                + Math.abs(left.getY() - right.getY())
                + Math.abs(left.getZ() - right.getZ());
    }

    private static int count(Container container, Item item) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(item)) total = Math.addExact(total, stack.getCount());
        }
        return total;
    }

    private static int capacity(Container container, Item item) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty()) {
                total = Math.addExact(total, Math.min(container.getMaxStackSize(), item.getMaxStackSize()));
            } else if (stack.is(item)) {
                total = Math.addExact(total,
                        Math.min(container.getMaxStackSize(), stack.getMaxStackSize()) - stack.getCount());
            }
        }
        return total;
    }

    private static boolean remove(Container container, Item item, int quantity) {
        if (count(container, item) < quantity) return false;
        int remaining = quantity;
        for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.is(item)) continue;
            int removed = Math.min(remaining, stack.getCount());
            stack.shrink(removed);
            if (stack.isEmpty()) container.setItem(slot, ItemStack.EMPTY);
            remaining -= removed;
        }
        container.setChanged();
        return remaining == 0;
    }

    private static boolean insert(Container container, Item item, int quantity) {
        if (capacity(container, item) < quantity) return false;
        int remaining = quantity;
        for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.is(item)) continue;
            int added = Math.min(remaining,
                    Math.min(container.getMaxStackSize(), stack.getMaxStackSize()) - stack.getCount());
            stack.grow(added);
            remaining -= added;
        }
        for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
            if (!container.getItem(slot).isEmpty()) continue;
            int added = Math.min(remaining, Math.min(container.getMaxStackSize(), item.getMaxStackSize()));
            container.setItem(slot, new ItemStack(item, added));
            remaining -= added;
        }
        container.setChanged();
        return remaining == 0;
    }

    private static String stateString(BlockState state) {
        var blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        StringBuilder value = new StringBuilder(blockId == null ? "unregistered" : blockId.toString());
        List<Map.Entry<Property<?>, Comparable<?>>> properties = new ArrayList<>(
                state.getValues().entrySet());
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

    private static String positionString(BlockPos position) {
        return position.getX() + "," + position.getY() + "," + position.getZ();
    }

    private static BlockPos3i position(BlockPos position) {
        return new BlockPos3i(position.getX(), position.getY(), position.getZ());
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }

    record TestRegion(BlockPos minimum, BlockPos maximum) {
        TestRegion {
            minimum = Objects.requireNonNull(minimum, "minimum").immutable();
            maximum = Objects.requireNonNull(maximum, "maximum").immutable();
            if (minimum.getX() > maximum.getX()
                    || minimum.getY() > maximum.getY()
                    || minimum.getZ() > maximum.getZ()) {
                throw new IllegalArgumentException("Bot test region bounds are inverted");
            }
            long volume = Math.multiplyExact(
                    Math.multiplyExact(maximum.getX() - (long) minimum.getX() + 1,
                            maximum.getY() - (long) minimum.getY() + 1),
                    maximum.getZ() - (long) minimum.getZ() + 1);
            if (volume > 32_768) {
                throw new IllegalArgumentException("Bot test region exceeds its bounded volume");
            }
        }

        boolean contains(BlockPos position) {
            return position.getX() >= minimum.getX() && position.getX() <= maximum.getX()
                    && position.getY() >= minimum.getY() && position.getY() <= maximum.getY()
                    && position.getZ() >= minimum.getZ() && position.getZ() <= maximum.getZ();
        }
    }

    private record ActionResult(
            Map<ResourceId, String> observedValues,
            int worldMutations,
            int materialMutations,
            Optional<TaskExecutionResult> failure) {
        private ActionResult {
            observedValues = Map.copyOf(Objects.requireNonNull(observedValues, "observedValues"));
            failure = Objects.requireNonNull(failure, "failure");
            if (worldMutations < 0 || worldMutations > 1
                    || materialMutations < 0 || materialMutations > 1
                    || (failure.isPresent() && !observedValues.isEmpty())) {
                throw new IllegalArgumentException("Bot action result is inconsistent");
            }
        }
    }

    private record SourceBuilder(BlockPos position, Map<ResourceId, Long> quantities) {
        private SourceBuilder(BlockPos position) {
            this(position.immutable(), new LinkedHashMap<>());
        }
    }
}
