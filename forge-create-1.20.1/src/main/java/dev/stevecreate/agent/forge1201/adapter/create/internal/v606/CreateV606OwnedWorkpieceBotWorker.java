package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

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
import dev.stevecreate.agent.core.execution.construction.TaskAssignment;
import dev.stevecreate.agent.core.execution.construction.TaskExecutionOutcome;
import dev.stevecreate.agent.core.execution.construction.TaskExecutionResult;
import dev.stevecreate.agent.core.execution.construction.TaskFailure;
import dev.stevecreate.agent.core.execution.construction.TaskFailureCategory;
import dev.stevecreate.agent.core.execution.construction.TaskKind;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.plan.OwnedWorkpieceApplicationPlan;
import dev.stevecreate.agent.forge1201.entity.ConstructionBotEntities;
import dev.stevecreate.agent.forge1201.entity.ConstructionBotEntity;
import dev.stevecreate.agent.forge1201.navigation.BoundedBotNavigation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * C-10 adapter for the existing Phase III Steve/Alex entity and BotWorker contract.
 *
 * <p>This class does not schedule tasks and owns no generic interaction primitive. It moves the
 * existing controlled entity through adjacent safe cells, proves exact arrival, then delegates
 * the authorized world operation to the same bounded physical backend used by Direct.</p>
 */
final class CreateV606OwnedWorkpieceBotWorker implements BotWorker {
    private static final String ENTITY_TAG = "steve_industrial_three_mode_bot";
    private static final int MAX_PATH_VISITS = 2_048;
    private final ServerLevel level;
    private final ConstructionTaskGraph graph;
    private final OwnedWorkpieceApplicationPlan plan;
    private final CreateV606OwnedWorkpieceDirectBackend physicalBackend;
    private final ResourceId botExecutorId;
    private final ResourceId workerId;
    private final UUID entityId;
    private final ConstructionBotEntity.Role role;
    private final ResourceId authorizedRegionId;
    private TaskAssignment active;
    private List<BlockPos> path = List.of();
    private BlockPos pathTarget;
    private long generation;
    private int movementTicks;
    private int assignmentsStarted;

    private CreateV606OwnedWorkpieceBotWorker(
            ServerLevel level,
            ConstructionTaskGraph graph,
            OwnedWorkpieceApplicationPlan plan,
            CreateV606OwnedWorkpieceDirectBackend physicalBackend,
            ResourceId botExecutorId,
            ResourceId workerId,
            UUID entityId,
            ConstructionBotEntity.Role role) {
        this.level = Objects.requireNonNull(level, "level");
        this.graph = Objects.requireNonNull(graph, "graph");
        this.plan = Objects.requireNonNull(plan, "plan");
        this.physicalBackend = Objects.requireNonNull(physicalBackend, "physicalBackend");
        this.botExecutorId = Objects.requireNonNull(botExecutorId, "botExecutorId");
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.entityId = Objects.requireNonNull(entityId, "entityId");
        this.role = Objects.requireNonNull(role, "role");
        this.authorizedRegionId = child(plan.policy().sessionId(), "bot_region");
    }

    static CreateV606OwnedWorkpieceBotWorker spawn(
            ServerLevel level,
            ConstructionTaskGraph graph,
            OwnedWorkpieceApplicationPlan plan,
            CreateV606OwnedWorkpieceDirectBackend physicalBackend,
            ResourceId botExecutorId,
            ResourceId workerId,
            BlockPos3i start,
            ConstructionBotEntity.Role role) {
        Objects.requireNonNull(start, "start");
        if (!level.getServer().isSameThread()
                || !plan.policy().contains(start)
                || !canStand(level, block(start))) {
            throw new IllegalArgumentException(
                    "C-10 Bot start must be one safe loaded cell inside the verified region");
        }
        ConstructionBotEntity entity = Objects.requireNonNull(
                ConstructionBotEntities.CONSTRUCTION_BOT.get().create(level),
                "Could not create existing construction Bot entity");
        entity.setRole(role);
        entity.moveTo(start.x() + 0.5D, start.y(), start.z() + 0.5D, 0.0F, 0.0F);
        entity.setCustomName(Component.literal(
                role == ConstructionBotEntity.Role.LOGISTICS
                        ? "Steve Logistics Bot"
                        : "Alex Builder & Inspector Bot"));
        entity.setCustomNameVisible(true);
        entity.addTag(ENTITY_TAG);
        if (!level.addFreshEntity(entity)) {
            throw new IllegalStateException("Could not spawn existing construction Bot entity");
        }
        return new CreateV606OwnedWorkpieceBotWorker(
                level, graph, plan, physicalBackend, botExecutorId,
                workerId, entity.getUUID(), role);
    }

    @Override
    public ResourceId workerId() {
        return workerId;
    }

    @Override
    public BotWorkerSnapshot snapshot(long currentTick) {
        requireServerThread();
        Entity observed = level.getEntity(entityId);
        if (!(observed instanceof ConstructionBotEntity entity) || !entity.isAlive()) {
            return new BotWorkerSnapshot(
                    workerId, BotWorkerStatus.OFFLINE,
                    observed == null
                            ? plan.policy().regionMinimum()
                            : position(observed.blockPosition()),
                    authorizedRegionId, capabilities(),
                    Optional.empty(), Optional.empty(), Optional.empty(),
                    inventory(currentTick), 1, false, true, generation, currentTick);
        }
        BotWorkerStatus status = active == null ? BotWorkerStatus.IDLE : BotWorkerStatus.BUSY;
        return new BotWorkerSnapshot(
                workerId, status, position(entity.blockPosition()), authorizedRegionId,
                capabilities(),
                active == null ? Optional.empty() : Optional.of(active.sessionId()),
                active == null ? Optional.empty() : Optional.of(active.taskId()),
                active == null ? Optional.empty() : Optional.of(active.assignmentId()),
                inventory(currentTick), Math.max(1, Math.round(entity.getHealth())),
                level.hasChunkAt(entity.blockPosition()), true, generation, currentTick);
    }

    @Override
    public TaskExecutionResult execute(
            ConstructionTaskGraph actualGraph,
            ConstructionTask task,
            TaskAssignment assignment,
            ConstructionExecutionContext context) {
        requireServerThread();
        if (actualGraph != graph || graph.task(task.taskId()) != task) {
            throw new IllegalArgumentException("C-10 Bot received another graph or task object");
        }
        if (!accepts(task.kind())) {
            return unsupported(task, assignment, context.currentTick());
        }
        if (context.command() == ConstructionExecutionCommand.RECOVER) {
            throw new IllegalStateException("C-10 Bot recovery must be refused by BotFleetExecutor");
        }
        if (context.command() == ConstructionExecutionCommand.START) {
            if (active != null) {
                return failure(task, assignment, context.currentTick(),
                        ConstructionFailureCode.WORKER_UNAVAILABLE,
                        "C-10 Bot already owns another assignment");
            }
            active = assignment;
            path = List.of();
            pathTarget = null;
            assignmentsStarted++;
            generation++;
        } else if (active == null
                || !active.assignmentId().equals(assignment.assignmentId())) {
            return failure(task, assignment, context.currentTick(),
                    ConstructionFailureCode.OWNERSHIP_LOST,
                    "C-10 Bot command lacks the exact active assignment");
        }
        if (context.command() == ConstructionExecutionCommand.CANCEL) {
            return finish(physicalBackend.executeForBot(
                    graph, task, assignment, context, botExecutorId));
        }

        BlockPos target = accessFor(task.kind());
        if (!atTarget(target)) {
            return move(task, assignment, context.currentTick(), target);
        }
        entity().face(task.kind() == TaskKind.TRANSPORT_MATERIAL
                ? block(plan.resourceBufferPosition())
                : block(plan.policy().workpiecePosition()));
        TaskExecutionResult result = physicalBackend.executeForBot(
                graph, task, assignment, context, botExecutorId);
        return result.outcome() == TaskExecutionOutcome.PENDING ? result : finish(result);
    }

    private TaskExecutionResult move(
            ConstructionTask task,
            TaskAssignment assignment,
            long tick,
            BlockPos target) {
        BlockPos current = entity().blockPosition();
        if (current.equals(target)) {
            entity().advanceToward(target);
            movementTicks++;
            generation++;
            return TaskExecutionResult.pending(
                    task, assignment, tick, List.of(), 0, 0,
                    "C-10 Bot centered inside the reached work-station cell");
        }
        if (!target.equals(pathTarget) || path.isEmpty() || !canStand(level, path.get(0))) {
            pathTarget = target.immutable();
            path = planPath(current, target);
        }
        if (path.isEmpty()) {
            return finish(failure(
                    task, assignment, tick, ConstructionFailureCode.NAVIGATION_BLOCKED,
                    "No bounded adjacent-cell path reaches the exact C-10 work station"
                            + " start=" + current
                            + " target=" + target
                            + " startInside=" + contains(current)
                            + " targetInside=" + contains(target)
                            + " startStand=" + canStand(level, current)
                            + " targetStand=" + canStand(level, target)));
        }
        BlockPos next = path.get(0);
        // A smooth stair transition briefly has a fractional blockPosition(); the
        // path planner already proved adjacency, so rechecking the integer position
        // mid-flight would reject the legal transition as a zero-horizontal move.
        if (!contains(next)
                || !canStand(level, next)) {
            path = List.of();
            return TaskExecutionResult.pending(
                    task, assignment, tick, List.of(), 0, 0,
                    "C-10 Bot invalidated a stale path and will repath");
        }
        if (entity().advanceToward(next)) {
            path = List.copyOf(path.subList(1, path.size()));
        }
        movementTicks++;
        generation++;
        return TaskExecutionResult.pending(
                task, assignment, tick, List.of(), 0, 0,
                "C-10 Bot advanced toward the exact reviewed work station");
    }

    private List<BlockPos> planPath(BlockPos start, BlockPos target) {
        if (!contains(start) || !contains(target) || !canStand(level, target)) {
            return List.of();
        }
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Map<BlockPos, BlockPos> parents = new LinkedHashMap<>();
        BlockPos immutableStart = start.immutable();
        queue.add(immutableStart);
        parents.put(immutableStart, null);
        while (!queue.isEmpty() && parents.size() <= MAX_PATH_VISITS) {
            BlockPos current = queue.removeFirst();
            if (current.equals(target)) break;
            for (BlockPos next : BoundedBotNavigation.neighbours(level, current)) {
                next = next.immutable();
                if (!parents.containsKey(next) && contains(next) && canStand(level, next)) {
                    parents.put(next, current);
                    queue.addLast(next);
                }
            }
        }
        if (!parents.containsKey(target)) return List.of();
        List<BlockPos> reversed = new ArrayList<>();
        BlockPos cursor = target;
        while (!cursor.equals(immutableStart)) {
            reversed.add(cursor);
            cursor = parents.get(cursor);
            if (cursor == null) return List.of();
        }
        Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    private BlockPos accessFor(TaskKind kind) {
        BlockPos3i origin = kind == TaskKind.TRANSPORT_MATERIAL
                ? plan.resourceBufferPosition()
                : plan.policy().workpiecePosition();
        int distance = kind == TaskKind.TRANSPORT_MATERIAL ? 1 : 2;
        List<BlockPos3i> candidates = List.of(
                origin.translate(distance, -1, 0),
                origin.translate(-distance, -1, 0),
                origin.translate(0, -1, distance),
                origin.translate(0, -1, -distance));
        return candidates.stream()
                .filter(plan.policy()::contains)
                .map(CreateV606OwnedWorkpieceBotWorker::block)
                .filter(value -> canStand(level, value))
                .sorted((left, right) -> Integer.compare(
                        manhattan(entity().blockPosition(), left),
                        manhattan(entity().blockPosition(), right)))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No safe reviewed C-10 Bot access cell is available"));
    }

    private boolean accepts(TaskKind kind) {
        return role == ConstructionBotEntity.Role.LOGISTICS
                ? kind == TaskKind.TRANSPORT_MATERIAL
                : kind == TaskKind.SAFE_MACHINE_INTERACTION
                        || kind == TaskKind.VERIFY_OUTPUT;
    }

    private Set<BotWorkerCapability> capabilities() {
        return role == ConstructionBotEntity.Role.LOGISTICS
                ? EnumSet.of(
                        BotWorkerCapability.REGISTRATION,
                        BotWorkerCapability.NAVIGATE_TO,
                        BotWorkerCapability.REACHABILITY,
                        BotWorkerCapability.CARRY_MATERIAL,
                        BotWorkerCapability.DELIVER_MATERIAL,
                        BotWorkerCapability.REPORT_EVIDENCE,
                        BotWorkerCapability.RETRY_REPATH,
                        BotWorkerCapability.CANCEL,
                        BotWorkerCapability.RELOAD_RECOVERY)
                : EnumSet.of(
                        BotWorkerCapability.REGISTRATION,
                        BotWorkerCapability.NAVIGATE_TO,
                        BotWorkerCapability.REACHABILITY,
                        BotWorkerCapability.LOOK_AT,
                        BotWorkerCapability.INTERACT_SAFE_MACHINE_FACE,
                        BotWorkerCapability.VERIFY_BLOCK_STATE,
                        BotWorkerCapability.REPORT_EVIDENCE,
                        BotWorkerCapability.RETRY_REPATH,
                        BotWorkerCapability.CANCEL,
                        BotWorkerCapability.RELOAD_RECOVERY);
    }

    private BotInventory inventory(long tick) {
        return new BotInventory(workerId, 64, Map.of(), generation, tick);
    }

    private TaskExecutionResult finish(TaskExecutionResult result) {
        active = null;
        path = List.of();
        pathTarget = null;
        generation++;
        return result;
    }

    private TaskExecutionResult unsupported(
            ConstructionTask task, TaskAssignment assignment, long tick) {
        TaskFailure failure = new TaskFailure(
                ConstructionFailureCode.BOT_EXECUTION_CAPABILITY_UNSUPPORTED.id(),
                TaskFailureCategory.UNSUPPORTED_CAPABILITY,
                false, Optional.empty(), Optional.of(task.taskId()),
                "C-10 Bot role does not own task kind " + task.kind(),
                List.of(assignment.assignmentId()),
                "Assign the task through the existing fleet capability matcher");
        return TaskExecutionResult.unsupported(
                task, assignment, tick, failure,
                "C-10 Bot role lacks the exact safe action vocabulary");
    }

    private TaskExecutionResult failure(
            ConstructionTask task,
            TaskAssignment assignment,
            long tick,
            ConstructionFailureCode code,
            String detail) {
        TaskFailure failure = new TaskFailure(
                code.id(), code.category(), false, Optional.empty(), Optional.of(task.taskId()),
                detail, List.of(assignment.assignmentId()),
                "Rescan the exact worker, path and plan-owned boundary before reassignment");
        return TaskExecutionResult.failure(
                task, assignment, tick, List.of(), failure, 0, 0, detail);
    }

    private boolean atTarget(BlockPos target) {
        ConstructionBotEntity entity = entity();
        double deltaX = entity.getX() - (target.getX() + 0.5D);
        double deltaY = entity.getY() - target.getY();
        double deltaZ = entity.getZ() - (target.getZ() + 0.5D);
        return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ < 1.0E-6D;
    }

    private ConstructionBotEntity entity() {
        Entity value = level.getEntity(entityId);
        if (!(value instanceof ConstructionBotEntity entity) || !entity.isAlive()) {
            throw new IllegalStateException("Existing construction Bot entity is missing or dead");
        }
        return entity;
    }

    int movementTicks() {
        return movementTicks;
    }

    int assignmentsStarted() {
        return assignmentsStarted;
    }

    ConstructionBotEntity.Role role() {
        return role;
    }

    void discard() {
        Entity value = level.getEntity(entityId);
        if (value != null && value.getTags().contains(ENTITY_TAG)) value.discard();
    }

    private void requireServerThread() {
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("C-10 Bot left the authoritative server thread");
        }
    }

    private boolean contains(BlockPos position) {
        return plan.policy().contains(position(position));
    }

    private static boolean canStand(ServerLevel level, BlockPos position) {
        if (!level.hasChunkAt(position)
                || !level.hasChunkAt(position.above())
                || !level.hasChunkAt(position.below())) {
            return false;
        }
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

    private static BlockPos block(BlockPos3i value) {
        return new BlockPos(value.x(), value.y(), value.z());
    }

    private static BlockPos3i position(BlockPos value) {
        return new BlockPos3i(value.getX(), value.getY(), value.getZ());
    }

    private static ResourceId child(ResourceId parent, String suffix) {
        return new ResourceId(parent.namespace(), parent.path() + "/" + suffix);
    }
}
