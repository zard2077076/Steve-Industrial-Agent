package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Deterministic loader-neutral router over the existing Direct and Bot executor boundaries.
 *
 * <p>The caller retains one HYBRID assignment. A bounded derived assignment is used only while
 * invoking the selected backend, then every result and evidence item is rebound to the original
 * Hybrid identity. Direct fallback is selected only when Bot support can be disproved before a
 * Bot call and the policy, task and descriptor all declare Direct support. Once a backend is
 * invoked its result is never switched to another backend in the same assignment step.</p>
 */
public final class HybridExecutor implements ConstructionExecutor {
    private static final int MAX_PROVENANCE_LENGTH = 1_024;

    private final ResourceId executorId;
    private final Set<ResourceId> executorCapabilities;
    private final ConstructionExecutor directExecutor;
    private final ConstructionExecutor botExecutor;
    private final HybridRoutingPolicy routingPolicy;

    public HybridExecutor(
            ResourceId executorId,
            Set<ResourceId> executorCapabilities,
            ConstructionExecutor directExecutor,
            ConstructionExecutor botExecutor,
            HybridRoutingPolicy routingPolicy) {
        this.executorId = Objects.requireNonNull(executorId, "executorId");
        Objects.requireNonNull(executorCapabilities, "executorCapabilities");
        LinkedHashSet<ResourceId> copied = new LinkedHashSet<>();
        executorCapabilities.stream()
                .map(value -> Objects.requireNonNull(value, "executorCapabilities element"))
                .sorted(java.util.Comparator.comparing(ResourceId::toString))
                .forEach(copied::add);
        if (copied.isEmpty() || copied.size() > 256) {
            throw new IllegalArgumentException("executorCapabilities count must be between 1 and 256");
        }
        this.executorCapabilities = Collections.unmodifiableSet(copied);
        this.directExecutor = requireDelegate(directExecutor, ExecutionMode.DIRECT, "directExecutor");
        this.botExecutor = requireDelegate(botExecutor, ExecutionMode.BOTS, "botExecutor");
        if (this.directExecutor.executorId().equals(this.botExecutor.executorId())
                || executorId.equals(this.directExecutor.executorId())
                || executorId.equals(this.botExecutor.executorId())) {
            throw new IllegalArgumentException("Hybrid, Direct and Bot executor identities must be distinct");
        }
        this.routingPolicy = Objects.requireNonNull(routingPolicy, "routingPolicy");
    }

    @Override
    public ResourceId executorId() {
        return executorId;
    }

    @Override
    public ExecutionMode mode() {
        return ExecutionMode.HYBRID;
    }

    public Set<ResourceId> executorCapabilities() {
        return executorCapabilities;
    }

    public HybridRoutingPolicy routingPolicy() {
        return routingPolicy;
    }

    @Override
    public boolean supports(
            ConstructionTaskGraph graph,
            ConstructionTask task,
            TaskAssignment assignment) {
        if (!ConstructionExecutor.super.supports(graph, task, assignment)
                || routingPolicy.routeFor(task.taskClass()) == HybridTaskRoute.REFUSE) {
            return false;
        }
        return executorCapabilities.containsAll(graph.descriptor(task.capabilityId())
                .modeCapability(ExecutionMode.HYBRID)
                .requiredExecutorCapabilities());
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
        validatePriorEvidence(assignment, context);

        if (task.taskClass() == ConstructionTaskClass.HIGH_RISK_INTERACTION) {
            if (!matchesHybridAssignment(graph, task, assignment)) {
                return unsupported(task, assignment, context.currentTick(),
                        ConstructionFailureCode.EXECUTOR_MODE_UNSUPPORTED,
                        "Hybrid executor does not own the exact graph/task/assignment");
            }
            return failure(task, assignment, context,
                    ConstructionFailureCode.HIGH_RISK_INTERACTION_REFUSED,
                    "High-risk interaction is permanently outside Hybrid automatic routing",
                    "Use no automatic executor until a separately approved safety gate exists");
        }
        if (!supports(graph, task, assignment)) {
            return unsupported(task, assignment, context.currentTick(),
                    ConstructionFailureCode.EXECUTOR_MODE_UNSUPPORTED,
                    "Hybrid executor does not support the exact graph/task/assignment declaration");
        }
        if (context.command() != ConstructionExecutionCommand.CANCEL
                && !assignment.ownership().validAt(context.currentTick())) {
            return failure(task, assignment, context,
                    ConstructionFailureCode.OWNERSHIP_LOST,
                    "Hybrid task ownership is not current at tick " + context.currentTick(),
                    "Acquire a new exact ownership generation before retrying");
        }
        if (context.command() == ConstructionExecutionCommand.CANCEL && !task.cancellable()) {
            throw new IllegalArgumentException("Hybrid cancellation requires a cancellable task");
        }
        if (context.command() == ConstructionExecutionCommand.RECOVER) {
            TaskExecutionResult refused = validateRecovery(task, assignment, context);
            if (refused != null) {
                return refused;
            }
        }

        HybridTaskRoute route = routingPolicy.routeFor(task.taskClass());
        if (route == HybridTaskRoute.DIRECT) {
            return invokeOrRefuse(
                    directExecutor, ExecutionMode.DIRECT, graph, task, assignment, context,
                    ConstructionFailureCode.EXECUTOR_MODE_UNSUPPORTED,
                    "The selected Hybrid Direct route is not explicitly supported");
        }
        TaskExecutionResult botResult = invokeIfSupported(
                botExecutor, ExecutionMode.BOTS, graph, task, assignment, context);
        if (botResult != null) {
            return botResult;
        }
        if (routingPolicy.allowsDirectFallback(task.taskClass())) {
            TaskExecutionResult direct = invokeIfSupported(
                    directExecutor, ExecutionMode.DIRECT, graph, task, assignment, context);
            if (direct != null) {
                return direct;
            }
        }
        return unsupported(task, assignment, context.currentTick(),
                ConstructionFailureCode.BOT_EXECUTION_CAPABILITY_UNSUPPORTED,
                "Bot route is unavailable and no explicitly declared safe Direct fallback exists");
    }

    private TaskExecutionResult invokeOrRefuse(
            ConstructionExecutor delegate,
            ExecutionMode route,
            ConstructionTaskGraph graph,
            ConstructionTask task,
            TaskAssignment assignment,
            ConstructionExecutionContext context,
            ConstructionFailureCode refusal,
            String detail) {
        TaskExecutionResult result = invokeIfSupported(
                delegate, route, graph, task, assignment, context);
        return result == null
                ? unsupported(task, assignment, context.currentTick(), refusal, detail)
                : result;
    }

    private TaskExecutionResult invokeIfSupported(
            ConstructionExecutor delegate,
            ExecutionMode route,
            ConstructionTaskGraph graph,
            ConstructionTask task,
            TaskAssignment assignment,
            ConstructionExecutionContext context) {
        TaskAssignment routedAssignment = derivedAssignment(graph, task, assignment, delegate, route);
        if (routedAssignment == null || !delegate.supports(graph, task, routedAssignment)) {
            return null;
        }
        requireStablePriorRoute(context.priorEvidence(), route);
        ConstructionExecutionContext routedContext = new ConstructionExecutionContext(
                context.command(),
                context.currentTick(),
                context.priorEvidence().stream()
                        .map(value -> rebindEvidence(value, routedAssignment, route, false))
                        .toList(),
                context.priorFailure(),
                context.reason());
        TaskExecutionResult routed = Objects.requireNonNull(
                delegate.execute(graph, task, routedAssignment, routedContext),
                "Hybrid delegate result");
        validateDelegateResult(routedAssignment, context, routed);
        if (context.command() == ConstructionExecutionCommand.CANCEL
                && routed.outcome() != TaskExecutionOutcome.CANCELLED) {
            throw new IllegalStateException("Hybrid cancellation delegate must return CANCELLED");
        }
        return rebindResult(task, assignment, route, routed);
    }

    private static TaskAssignment derivedAssignment(
            ConstructionTaskGraph graph,
            ConstructionTask task,
            TaskAssignment assignment,
            ConstructionExecutor delegate,
            ExecutionMode route) {
        if (!task.allowedModes().contains(route)
                || !graph.descriptor(task.capabilityId()).supports(route, task.kind())) {
            return null;
        }
        Optional<ResourceId> workerId = route == ExecutionMode.BOTS
                ? assignment.workerId() : Optional.empty();
        if (route == ExecutionMode.BOTS && workerId.isEmpty()) {
            return null;
        }
        TaskOwnership original = assignment.ownership();
        TaskOwnership routedOwnership = new TaskOwnership(
                original.sessionId(), original.graphId(), original.taskId(), delegate.executorId(),
                route, workerId, original.generation(), original.acquiredTick(),
                original.expiresTick(), original.ownershipTokenHash());
        return TaskAssignment.assign(
                graph, task.taskId(), assignment.assignmentId(), routedOwnership,
                assignment.attempt(), assignment.assignedTick());
    }

    private static TaskExecutionResult rebindResult(
            ConstructionTask task,
            TaskAssignment assignment,
            ExecutionMode route,
            TaskExecutionResult routed) {
        List<ExecutionEvidence> evidence = routed.evidence().stream()
                .map(value -> rebindEvidence(value, assignment, route, true))
                .toList();
        String detail = boundedPrefix("Hybrid " + route.serializedName() + " route: ", routed.detail(),
                ConstructionContractValues.MAX_DETAIL_LENGTH);
        return switch (routed.outcome()) {
            case PENDING -> TaskExecutionResult.pending(
                    task, assignment, routed.resultTick(), evidence,
                    routed.worldMutationCount(), routed.materialMutationCount(), detail);
            case SUCCEEDED -> TaskExecutionResult.success(
                    task, assignment, routed.resultTick(), evidence,
                    routed.worldMutationCount(), routed.materialMutationCount(), detail);
            case RETRYABLE_FAILURE, TERMINAL_FAILURE -> TaskExecutionResult.failure(
                    task, assignment, routed.resultTick(), evidence, routed.failure().orElseThrow(),
                    routed.worldMutationCount(), routed.materialMutationCount(), detail);
            case UNSUPPORTED -> TaskExecutionResult.unsupported(
                    task, assignment, routed.resultTick(), routed.failure().orElseThrow(), detail);
            case CANCELLED -> TaskExecutionResult.cancelled(
                    task, assignment, routed.resultTick(), evidence,
                    routed.failure().orElseThrow(), detail);
            case RECOVERY_REQUIRED -> TaskExecutionResult.recoveryRequired(
                    task, assignment, routed.resultTick(), evidence,
                    routed.failure().orElseThrow(), detail);
        };
    }

    private static ExecutionEvidence rebindEvidence(
            ExecutionEvidence evidence,
            TaskAssignment target,
            ExecutionMode route,
            boolean recordRoute) {
        String provenance = recordRoute
                ? routeProvenance(route, evidence.provenance())
                : evidence.provenance();
        return new ExecutionEvidence(
                evidence.evidenceId(), evidence.requirementId(), target.sessionId(), target.graphId(),
                target.taskId(), target.assignmentId(), target.executorId(), target.mode(),
                evidence.kind(), evidence.subjectId(), evidence.observedValues(),
                evidence.expectedValues(), evidence.observedTick(), evidence.passed(), provenance);
    }

    private static String routeProvenance(ExecutionMode route, String provenance) {
        String prefix = "hybrid-route=" + route.serializedName() + ";";
        if (provenance.startsWith("hybrid-route=") && !provenance.startsWith(prefix)) {
            throw new IllegalStateException("Hybrid evidence cannot change its physical backend route");
        }
        return provenance.startsWith(prefix) ? provenance
                : boundedPrefix(prefix, provenance, MAX_PROVENANCE_LENGTH);
    }

    private static String boundedPrefix(String prefix, String value, int maximum) {
        String combined = prefix + value;
        return combined.length() <= maximum
                ? combined
                : combined.substring(0, maximum);
    }

    private static void validatePriorEvidence(
            TaskAssignment assignment,
            ConstructionExecutionContext context) {
        for (ExecutionEvidence evidence : context.priorEvidence()) {
            if (!evidence.belongsTo(assignment) || evidence.observedTick() > context.currentTick()) {
                throw new IllegalArgumentException(
                        "Prior evidence is future-dated or belongs to another Hybrid assignment");
            }
        }
    }

    private static void requireStablePriorRoute(
            List<ExecutionEvidence> priorEvidence,
            ExecutionMode route) {
        String requiredPrefix = "hybrid-route=" + route.serializedName() + ";";
        for (ExecutionEvidence evidence : priorEvidence) {
            if (evidence.provenance().startsWith("hybrid-route=")
                    && !evidence.provenance().startsWith(requiredPrefix)) {
                throw new IllegalArgumentException(
                        "Prior Hybrid evidence belongs to another physical backend route");
            }
        }
    }

    private static TaskExecutionResult validateRecovery(
            ConstructionTask task,
            TaskAssignment assignment,
            ConstructionExecutionContext context) {
        RecoveryPolicy recovery = task.recoveryPolicy();
        if (recovery.strategy() == RecoveryStrategy.REFUSE) {
            String detail = "The Hybrid task recovery policy refuses replay";
            return TaskExecutionResult.recoveryRequired(
                    task, assignment, context.currentTick(), context.priorEvidence(),
                    taskFailure(
                            ConstructionFailureCode.RECOVERY_REFUSED_AFTER_RESOURCE_CONSUMPTION,
                            task, assignment, detail,
                            "Cancel or reconcile through the existing typed recovery path"),
                    detail);
        }
        Set<ResourceId> reconciled = new LinkedHashSet<>();
        for (ExecutionEvidence evidence : context.priorEvidence()) {
            if (evidence.passed()
                    && evidence.kind() == ExecutionEvidenceKind.RECOVERY_RECONCILED) {
                reconciled.add(evidence.requirementId());
            }
        }
        if (!reconciled.containsAll(recovery.requiredReconciliationEvidence())) {
            String detail = "Hybrid recovery is missing exact reconciliation evidence";
            return TaskExecutionResult.recoveryRequired(
                    task, assignment, context.currentTick(), context.priorEvidence(),
                    taskFailure(
                            ConstructionFailureCode.RECOVERY_RECONCILIATION_REQUIRED,
                            task, assignment, detail,
                            "Perform the bounded authoritative rescan before recovery"),
                    detail);
        }
        return null;
    }

    private static void validateDelegateResult(
            TaskAssignment assignment,
            ConstructionExecutionContext context,
            TaskExecutionResult result) {
        if (!result.assignmentId().equals(assignment.assignmentId())
                || !result.sessionId().equals(assignment.sessionId())
                || !result.graphId().equals(assignment.graphId())
                || !result.taskId().equals(assignment.taskId())
                || !result.executorId().equals(assignment.executorId())
                || result.mode() != assignment.mode()
                || result.resultTick() != context.currentTick()) {
            throw new IllegalStateException(
                    "Hybrid delegate returned another assignment, route or tick");
        }
    }

    private boolean matchesHybridAssignment(
            ConstructionTaskGraph graph,
            ConstructionTask task,
            TaskAssignment assignment) {
        return graph.graphId().equals(assignment.graphId())
                && task.taskId().equals(assignment.taskId())
                && executorId.equals(assignment.executorId())
                && assignment.mode() == ExecutionMode.HYBRID;
    }

    private static ConstructionExecutor requireDelegate(
            ConstructionExecutor delegate,
            ExecutionMode expected,
            String name) {
        Objects.requireNonNull(delegate, name);
        if (delegate.mode() != expected) {
            throw new IllegalArgumentException(name + " must use mode " + expected);
        }
        return delegate;
    }

    private static TaskExecutionResult failure(
            ConstructionTask task,
            TaskAssignment assignment,
            ConstructionExecutionContext context,
            ConstructionFailureCode code,
            String detail,
            String safeNextStep) {
        return TaskExecutionResult.failure(
                task, assignment, context.currentTick(), context.priorEvidence(),
                taskFailure(code, task, assignment, detail, safeNextStep),
                0, 0, detail);
    }

    private static TaskExecutionResult unsupported(
            ConstructionTask task,
            TaskAssignment assignment,
            long tick,
            ConstructionFailureCode code,
            String detail) {
        return TaskExecutionResult.unsupported(
                task, assignment, tick,
                taskFailure(code, task, assignment, detail,
                        "Select only an explicitly declared safe Hybrid route"),
                detail);
    }

    private static TaskFailure taskFailure(
            ConstructionFailureCode code,
            ConstructionTask task,
            TaskAssignment assignment,
            String detail,
            String safeNextStep) {
        return new TaskFailure(
                code.id(), code.category(), false, Optional.empty(), Optional.of(task.taskId()),
                detail, List.of(assignment.assignmentId()), safeNextStep);
    }
}
