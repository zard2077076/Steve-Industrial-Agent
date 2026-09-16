package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import dev.stevecreate.agent.adapter.api.AdapterFailureCode;
import dev.stevecreate.agent.adapter.api.AdapterResult;
import dev.stevecreate.agent.adapter.api.RuntimeFingerprint;
import dev.stevecreate.agent.core.execution.construction.ConstructionExecutionCommand;
import dev.stevecreate.agent.core.execution.construction.ConstructionExecutionContext;
import dev.stevecreate.agent.core.execution.construction.ConstructionFailureCode;
import dev.stevecreate.agent.core.execution.construction.ConstructionTask;
import dev.stevecreate.agent.core.execution.construction.ConstructionTaskGraph;
import dev.stevecreate.agent.core.execution.construction.DirectWorldExecutor;
import dev.stevecreate.agent.core.execution.construction.ExecutionEvidence;
import dev.stevecreate.agent.core.execution.construction.ExecutionEvidenceKind;
import dev.stevecreate.agent.core.execution.construction.ExecutionMode;
import dev.stevecreate.agent.core.execution.construction.OwnedWorkpieceApplicationTaskGraphFactory;
import dev.stevecreate.agent.core.execution.construction.TaskAssignment;
import dev.stevecreate.agent.core.execution.construction.TaskExecutionResult;
import dev.stevecreate.agent.core.execution.construction.TaskFailure;
import dev.stevecreate.agent.core.execution.construction.TaskKind;
import dev.stevecreate.agent.core.execution.construction.TaskPostcondition;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.plan.OwnedWorkpieceApplicationPlan;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;

/**
 * Exact Direct backend for the reviewed C-10 workpiece graph.
 *
 * <p>The material task only proves that the plan-owned delivery buffer is already ready; it does
 * not claim a transfer. The interaction task delegates exclusively to the allowlisted v606
 * handler, and the verification task performs a fresh terminal rescan.</p>
 */
final class CreateV606OwnedWorkpieceDirectBackend
        implements DirectWorldExecutor.BoundedDirectWorldBackend {
    static final ResourceId EXECUTOR_ID = id("construction:c10_direct_world_v606");

    private final ServerLevel level;
    private final OwnedWorkpieceApplicationPlan plan;
    private final RuntimeFingerprint runtime;
    private final ConstructionTaskGraph graph;
    private final ConstructionTask deliverTask;
    private final ConstructionTask applyTask;
    private final ConstructionTask verifyTask;
    private Create606OwnedWorkpieceApplicationHandler handler;
    private Create606OwnedWorkpieceApplicationHandler.Phase lastPhase;
    private boolean deliveryVerified;
    private boolean applyCompleted;
    private boolean terminalVerified;

    CreateV606OwnedWorkpieceDirectBackend(
            ServerLevel level,
            OwnedWorkpieceApplicationPlan plan,
            RuntimeFingerprint runtime,
            ConstructionTaskGraph graph) {
        this.level = Objects.requireNonNull(level, "level");
        this.plan = Objects.requireNonNull(plan, "plan");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.graph = Objects.requireNonNull(graph, "graph");
        ConstructionTaskGraph expected = new OwnedWorkpieceApplicationTaskGraphFactory().create(
                plan, runtime.canonicalIdentity(), graph.worldSnapshotFingerprint());
        if (!graph.graphId().equals(expected.graphId())
                || !graph.verifiedPhysicalPlanId().equals(expected.verifiedPhysicalPlanId())
                || !graph.runtimeFingerprint().equals(expected.runtimeFingerprint())
                || !graph.descriptors().equals(expected.descriptors())
                || !graph.topologicalOrder().equals(expected.topologicalOrder())
                || !graph.dependencies().equals(expected.dependencies())) {
            throw new IllegalArgumentException(
                    "C-10 Direct backend requires the exact plan-derived task graph");
        }
        this.deliverTask = task(TaskKind.TRANSPORT_MATERIAL);
        this.applyTask = task(TaskKind.SAFE_MACHINE_INTERACTION);
        this.verifyTask = task(TaskKind.VERIFY_OUTPUT);
    }

    @Override
    public TaskExecutionResult execute(
            ConstructionTaskGraph actualGraph,
            ConstructionTask task,
            TaskAssignment assignment,
            ConstructionExecutionContext context) {
        return executeAuthorized(
                actualGraph, task, assignment, context,
                ExecutionMode.DIRECT, EXECUTOR_ID);
    }

    TaskExecutionResult executeForBot(
            ConstructionTaskGraph actualGraph,
            ConstructionTask task,
            TaskAssignment assignment,
            ConstructionExecutionContext context,
            ResourceId botExecutorId) {
        return executeAuthorized(
                actualGraph, task, assignment, context,
                ExecutionMode.BOTS, Objects.requireNonNull(botExecutorId, "botExecutorId"));
    }

    private TaskExecutionResult executeAuthorized(
            ConstructionTaskGraph actualGraph,
            ConstructionTask task,
            TaskAssignment assignment,
            ConstructionExecutionContext context,
            ExecutionMode expectedMode,
            ResourceId expectedExecutorId) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(assignment, "assignment");
        Objects.requireNonNull(context, "context");
        if (actualGraph != graph
                || graph.task(task.taskId()) != task
                || !assignment.sessionId().equals(plan.policy().sessionId())
                || !assignment.executorId().equals(expectedExecutorId)
                || assignment.mode() != expectedMode) {
            throw new IllegalArgumentException(
                    "C-10 Direct backend graph, task, session or assignment identity changed");
        }
        if (context.command() == ConstructionExecutionCommand.RECOVER) {
            throw new IllegalStateException(
                    "C-10 task graph recovery is refused; use the exact handler checkpoint path");
        }
        if (context.command() == ConstructionExecutionCommand.CANCEL) {
            return cancel(task, assignment, context);
        }
        if (task == deliverTask) {
            return verifyDelivery(task, assignment, context, expectedMode);
        }
        if (task == applyTask) return apply(task, assignment, context, expectedMode);
        if (task == verifyTask) {
            return verifyTerminal(task, assignment, context, expectedMode);
        }
        throw new IllegalArgumentException("C-10 Direct backend received an unknown task");
    }

    private TaskExecutionResult verifyDelivery(
            ConstructionTask task,
            TaskAssignment assignment,
            ConstructionExecutionContext context,
            ExecutionMode expectedMode) {
        if ((expectedMode == ExecutionMode.DIRECT
                        && context.command() != ConstructionExecutionCommand.START)
                || deliveryVerified) {
            return failure(task, assignment, context, AdapterFailureCode.PLAN_REJECTED,
                    "C-10 delivery readiness may be established exactly once");
        }
        Create606WorldResourceBuffer buffer =
                new Create606WorldResourceBuffer(level, plan.resourceBufferPosition());
        AdapterResult<Integer> clean = buffer.availableExactNbtFree(plan.policy().heldItem());
        AdapterResult<Map<ResourceId, Long>> snapshot = buffer.snapshot();
        if (clean instanceof AdapterResult.Failure<Integer> failed) {
            return failure(task, assignment, context, failed.code(), failed.detail());
        }
        if (snapshot instanceof AdapterResult.Failure<Map<ResourceId, Long>> failed) {
            return failure(task, assignment, context, failed.code(), failed.detail());
        }
        if (((AdapterResult.Success<Integer>) clean).value() != 1
                || !((AdapterResult.Success<Map<ResourceId, Long>>) snapshot).value()
                        .equals(Map.of(plan.policy().heldItem(), 1L))) {
            return failure(task, assignment, context, AdapterFailureCode.PLAN_REJECTED,
                    "plan-owned delivery buffer is not exactly one NBT-free held item");
        }
        deliveryVerified = true;
        return TaskExecutionResult.success(
                task, assignment, context.currentTick(),
                evidence(task, assignment, context.currentTick(),
                        "delivery_buffer_ready", "create-v606:exact-plan-owned-buffer-readback"),
                0, 0,
                "Exact plan-owned delivery buffer is ready; no transfer was performed");
    }

    private TaskExecutionResult apply(
            ConstructionTask task,
            TaskAssignment assignment,
            ConstructionExecutionContext context,
            ExecutionMode expectedMode) {
        if (!deliveryVerified || applyCompleted) {
            return failure(task, assignment, context, AdapterFailureCode.PLAN_REJECTED,
                    "C-10 application requires one completed delivery readiness gate");
        }
        if (handler == null) {
            if (expectedMode == ExecutionMode.DIRECT
                    && context.command() != ConstructionExecutionCommand.START) {
                return failure(task, assignment, context, AdapterFailureCode.PLAN_REJECTED,
                        "C-10 application must start before it can continue");
            }
            AdapterResult<Create606OwnedWorkpieceApplicationHandler> started =
                    Create606OwnedWorkpieceApplicationHandler.begin(level, plan, runtime);
            if (started instanceof AdapterResult.Failure<
                    Create606OwnedWorkpieceApplicationHandler> failed) {
                return failure(task, assignment, context, failed.code(), failed.detail());
            }
            handler = ((AdapterResult.Success<
                    Create606OwnedWorkpieceApplicationHandler>) started).value();
        } else if (context.command() != ConstructionExecutionCommand.CONTINUE) {
            return failure(task, assignment, context, AdapterFailureCode.PLAN_REJECTED,
                    "C-10 application already started and accepts only CONTINUE");
        }

        AdapterResult<Create606OwnedWorkpieceApplicationHandler.Update> tick = handler.tick();
        if (tick instanceof AdapterResult.Failure<
                Create606OwnedWorkpieceApplicationHandler.Update> failed) {
            return failure(task, assignment, context, failed.code(), failed.detail());
        }
        Create606OwnedWorkpieceApplicationHandler.Update update =
                ((AdapterResult.Success<
                        Create606OwnedWorkpieceApplicationHandler.Update>) tick).value();
        int worldMutations = (lastPhase == null
                        && update.phase()
                        == Create606OwnedWorkpieceApplicationHandler.Phase.BUILD_DEPLOYER)
                || (lastPhase == Create606OwnedWorkpieceApplicationHandler.Phase.BUILD_DEPLOYER
                        && update.phase()
                        == Create606OwnedWorkpieceApplicationHandler.Phase.AWAIT_POWER)
                ? 1 : 0;
        int materialMutations = lastPhase
                        == Create606OwnedWorkpieceApplicationHandler.Phase.STAGE_HELD_ITEM
                && update.phase()
                        == Create606OwnedWorkpieceApplicationHandler.Phase.OBSERVE_APPLICATION
                ? 1 : 0;
        lastPhase = update.phase();
        if (update.phase() != Create606OwnedWorkpieceApplicationHandler.Phase.COMPLETED) {
            return TaskExecutionResult.pending(
                    task, assignment, context.currentTick(), List.of(),
                    worldMutations, materialMutations,
                    "Real allowlisted Deployer progress phase=" + update.phase());
        }
        if (!update.realDeployerCycleObserved()
                || !update.exactWorkpieceTransitionObserved()
                || !update.unrelatedWorldStateUnchanged()
                || update.heldBefore() != 1
                || update.heldAfter() != 0) {
            return failure(task, assignment, context, AdapterFailureCode.PROCESSING_FAILED,
                    "real Deployer completion evidence did not match the exact C-10 contract");
        }
        AdapterResult<Boolean> cleanup = handler.cleanupMachine();
        if (!(cleanup instanceof AdapterResult.Success<Boolean> success) || !success.value()) {
            AdapterFailureCode code = cleanup instanceof AdapterResult.Failure<Boolean> failed
                    ? failed.code() : AdapterFailureCode.PROCESSING_FAILED;
            String detail = cleanup instanceof AdapterResult.Failure<Boolean> failed
                    ? failed.detail() : "owned machine cleanup returned false";
            return failure(task, assignment, context, code, detail);
        }
        applyCompleted = true;
        return TaskExecutionResult.success(
                task, assignment, context.currentTick(),
                evidence(task, assignment, context.currentTick(),
                        "application", "create-v606:real-deployer-cycle-and-cleanup-readback"),
                1, 0,
                "Real Deployer cycle transformed the exact workpiece and removed owned machinery");
    }

    private TaskExecutionResult verifyTerminal(
            ConstructionTask task,
            TaskAssignment assignment,
            ConstructionExecutionContext context,
            ExecutionMode expectedMode) {
        if ((expectedMode == ExecutionMode.DIRECT
                        && context.command() != ConstructionExecutionCommand.START)
                || !applyCompleted
                || handler == null
                || terminalVerified) {
            return failure(task, assignment, context, AdapterFailureCode.PLAN_REJECTED,
                    "C-10 terminal rescan requires completed application and cleanup");
        }
        AdapterResult<Boolean> verified = handler.verifyTerminalBoundary();
        if (!(verified instanceof AdapterResult.Success<Boolean> success) || !success.value()) {
            AdapterFailureCode code = verified instanceof AdapterResult.Failure<Boolean> failed
                    ? failed.code() : AdapterFailureCode.PROCESSING_FAILED;
            String detail = verified instanceof AdapterResult.Failure<Boolean> failed
                    ? failed.detail() : "terminal boundary verification returned false";
            return failure(task, assignment, context, code, detail);
        }
        terminalVerified = true;
        return TaskExecutionResult.success(
                task, assignment, context.currentTick(),
                evidence(task, assignment, context.currentTick(),
                        "terminal_rescan", "create-v606:fresh-output-buffer-boundary-rescan"),
                0, 0,
                "Fresh rescan verified output, empty delivery buffer and absent owned machinery");
    }

    private TaskExecutionResult cancel(
            ConstructionTask task,
            TaskAssignment assignment,
            ConstructionExecutionContext context) {
        if (task == applyTask && handler != null && !applyCompleted) {
            AdapterResult<dev.stevecreate.agent.core.recovery.WorldChangeJournal.RollbackReport>
                    cancelled = handler.cancel();
            if (!(cancelled instanceof AdapterResult.Success<
                    dev.stevecreate.agent.core.recovery.WorldChangeJournal.RollbackReport> success)
                    || !success.value().warnings().isEmpty()) {
                throw new IllegalStateException(
                        "C-10 physical cancellation could not prove warning-free exact rollback: "
                                + cancelled);
            }
        }
        TaskFailure cancellation = new TaskFailure(
                ConstructionFailureCode.CANCELLED.id(),
                ConstructionFailureCode.CANCELLED.category(),
                false,
                Optional.empty(),
                Optional.of(task.taskId()),
                "C-10 " + assignment.mode().serializedName()
                        + " task cancelled by exact reason " + context.reason().orElseThrow(),
                List.of(assignment.assignmentId()),
                "Rescan the exact plan-owned boundary before assigning another task");
        ExecutionEvidence evidence = new ExecutionEvidence(
                child(assignment.assignmentId(), "cancelled"),
                child(task.taskId(), "cancelled"),
                assignment.sessionId(), assignment.graphId(), assignment.taskId(),
                assignment.assignmentId(), assignment.executorId(), assignment.mode(),
                ExecutionEvidenceKind.CANCELLATION_CONFIRMED,
                plan.policy().sessionId(),
                Map.of(id("schema:state"), "cancelled"),
                Map.of(id("schema:state"), "cancelled"),
                context.currentTick(), true,
                "create-v606:typed-cancel-warning-free-rollback");
        return TaskExecutionResult.cancelled(
                task, assignment, context.currentTick(), List.of(evidence), cancellation,
                "C-10 physical cancellation completed without hidden resource mutation");
    }

    private List<ExecutionEvidence> evidence(
            ConstructionTask task,
            TaskAssignment assignment,
            long tick,
            String suffix,
            String provenance) {
        return task.postconditions().stream()
                .map(postcondition -> evidence(
                        postcondition, assignment, tick, suffix, provenance))
                .toList();
    }

    private ExecutionEvidence evidence(
            TaskPostcondition postcondition,
            TaskAssignment assignment,
            long tick,
            String suffix,
            String provenance) {
        Map<ResourceId, String> observed = new LinkedHashMap<>(postcondition.expectedValues());
        observed.put(id("schema:execution_mode"), assignment.mode().serializedName());
        return new ExecutionEvidence(
                child(assignment.assignmentId(), suffix + "/" + postcondition.conditionId().path()),
                postcondition.conditionId(),
                assignment.sessionId(), assignment.graphId(), assignment.taskId(),
                assignment.assignmentId(), assignment.executorId(), assignment.mode(),
                postcondition.requiredEvidenceKind(), postcondition.subjectId(),
                Map.copyOf(observed), postcondition.expectedValues(), tick, true, provenance);
    }

    private TaskExecutionResult failure(
            ConstructionTask task,
            TaskAssignment assignment,
            ConstructionExecutionContext context,
            AdapterFailureCode adapterCode,
            String detail) {
        ConstructionFailureCode code = switch (adapterCode) {
            case WRONG_THREAD -> ConstructionFailureCode.WRONG_THREAD;
            case CHUNK_NOT_LOADED -> ConstructionFailureCode.CHUNK_NOT_LOADED;
            case FORMAL_WORLD_EXECUTION_FORBIDDEN ->
                    ConstructionFailureCode.HIGH_RISK_INTERACTION_REFUSED;
            default -> ConstructionFailureCode.VERIFICATION_FAILED;
        };
        TaskFailure failure = new TaskFailure(
                code.id(), code.category(), false, Optional.empty(), Optional.of(task.taskId()),
                detail, List.of(assignment.assignmentId()),
                "Inspect the typed v606 refusal and exact world boundary before retrying");
        return TaskExecutionResult.failure(
                task, assignment, context.currentTick(), List.of(), failure, 0, 0, detail);
    }

    private ConstructionTask task(TaskKind kind) {
        List<ConstructionTask> matches = graph.topologicalOrder().stream()
                .filter(value -> value.kind() == kind)
                .toList();
        if (matches.size() != 1) {
            throw new IllegalArgumentException("C-10 graph must contain exactly one " + kind);
        }
        return matches.get(0);
    }

    private static ResourceId child(ResourceId parent, String suffix) {
        return new ResourceId(parent.namespace(), parent.path() + "/" + suffix);
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
