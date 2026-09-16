package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.execution.RetryPolicy;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class ConstructionContractFixtures {
    static final ResourceId PLAN_ID = id("test:verified_plan");
    static final String RUNTIME = "runtime-fixture-v1";
    static final String SNAPSHOT = "snapshot-fixture-v1";
    static final String TOKEN_HASH = "a".repeat(64);

    private ConstructionContractFixtures() {
    }

    static ResourceId id(String value) {
        return ResourceId.parse(value);
    }

    static CapabilityExecutionDescriptor descriptor() {
        EnumMap<ExecutionMode, ModeCapabilityDeclaration> modes = new EnumMap<>(ExecutionMode.class);
        modes.put(ExecutionMode.DIRECT, new ModeCapabilityDeclaration(
                ExecutionMode.DIRECT,
                CapabilitySupport.SUPPORTED,
                Set.of(id("executor:bounded_world_action")),
                ""));
        modes.put(ExecutionMode.BOTS, new ModeCapabilityDeclaration(
                ExecutionMode.BOTS,
                CapabilitySupport.SUPPORTED,
                Set.of(id("executor:navigation"), id("executor:material_carry")),
                ""));
        modes.put(ExecutionMode.HYBRID, new ModeCapabilityDeclaration(
                ExecutionMode.HYBRID,
                CapabilitySupport.SUPPORTED,
                Set.of(id("executor:mode_router")),
                ""));
        return new CapabilityExecutionDescriptor(
                id("capability:test_build"),
                id("implementation:test_machine"),
                id("adapter:test"),
                RUNTIME,
                EnumSet.allOf(TaskKind.class),
                modes,
                Map.of(id("contract:version"), "1"));
    }

    static ConstructionTaskGraph simpleGraph() {
        ConstructionTask fetch = task(
                "task:fetch",
                TaskKind.FETCH_MATERIAL,
                ConstructionTaskClass.MATERIAL_TRANSPORT,
                TaskSourceKind.VERIFIED_RESOURCE_REQUIREMENT,
                Set.of(),
                Set.of(id("reservation:material")),
                post("condition:material_ready", ExecutionEvidenceKind.MATERIAL_DELIVERED,
                        "subject:material", "delivered"));
        ConstructionTask place = task(
                "task:place",
                TaskKind.PLACE_COMPONENT,
                ConstructionTaskClass.ORIENTATION_SENSITIVE_COMPONENT,
                TaskSourceKind.VERIFIED_PLACEMENT,
                Set.of(id("reservation:position")),
                Set.of(id("reservation:material")),
                post("condition:block_state", ExecutionEvidenceKind.BLOCK_STATE_VERIFIED,
                        "subject:block", "create:shaft[axis=x]"));
        ConstructionTask verify = task(
                "task:verify",
                TaskKind.VERIFY_STATE,
                ConstructionTaskClass.SYSTEM_VERIFICATION,
                TaskSourceKind.VERIFIED_SYSTEM_CHECK,
                Set.of(),
                Set.of(),
                post("condition:verified", ExecutionEvidenceKind.BLOCK_STATE_VERIFIED,
                        "subject:block", "create:shaft[axis=x]"));
        return new ConstructionTaskGraph(
                id("graph:test"),
                PLAN_ID,
                RUNTIME,
                SNAPSHOT,
                List.of(descriptor()),
                List.of(verify, place, fetch),
                List.of(
                        new TaskDependency(
                                fetch.taskId(),
                                place.taskId(),
                                TaskDependencyKind.MATERIAL_DELIVERY,
                                Set.of(id("condition:material_ready"))),
                        new TaskDependency(
                                place.taskId(),
                                verify.taskId(),
                                TaskDependencyKind.VERIFICATION_GATE,
                                Set.of(id("condition:block_state")))));
    }

    static ConstructionTask task(
            String taskId,
            TaskKind kind,
            ConstructionTaskClass taskClass,
            TaskSourceKind sourceKind,
            Set<ResourceId> placementReservations,
            Set<ResourceId> materialReservations,
            TaskPostcondition postcondition) {
        return new ConstructionTask(
                id(taskId),
                new VerifiedPlanTaskSource(
                        PLAN_ID,
                        sourceKind,
                        id("physical:" + taskId.substring(taskId.indexOf(':') + 1)),
                        0),
                descriptor().capabilityId(),
                kind,
                taskClass,
                EnumSet.allOf(ExecutionMode.class),
                List.of(),
                List.of(postcondition),
                RetryPolicy.NO_RETRY,
                true,
                RecoveryPolicy.REFUSE,
                kind.mutatesWorld()
                        ? new CleanupPolicy(
                                CleanupScope.SESSION_OWNED_REVERSIBLE_ONLY,
                                kind.operatesOnMaterial(),
                                true,
                                1)
                        : CleanupPolicy.NONE,
                placementReservations,
                materialReservations,
                Set.of(),
                1_200,
                Map.of(id("task:source"), "verified-physical-plan"));
    }

    static TaskPostcondition post(
            String conditionId,
            ExecutionEvidenceKind evidenceKind,
            String subjectId,
            String expectedState) {
        return new TaskPostcondition(
                id(conditionId),
                TaskConditionKind.CURRENT_STATE_MATCHES,
                id(subjectId),
                evidenceKind,
                Map.of(id("value:state"), expectedState));
    }

    static TaskOwnership ownership(
            ConstructionTaskGraph graph,
            ConstructionTask task,
            ExecutionMode mode,
            long acquiredTick,
            long expiresTick) {
        return new TaskOwnership(
                id("session:test"),
                graph.graphId(),
                task.taskId(),
                id("executor:" + mode.serializedName()),
                mode,
                mode == ExecutionMode.BOTS ? Optional.of(id("worker:one")) : Optional.empty(),
                1,
                acquiredTick,
                expiresTick,
                TOKEN_HASH);
    }

    static TaskAssignment assignment(
            ConstructionTaskGraph graph,
            ConstructionTask task,
            ExecutionMode mode,
            int attempt,
            long tick) {
        return TaskAssignment.assign(
                graph,
                task.taskId(),
                id("assignment:" + task.taskId().path().replace('/', '_') + "_" + attempt),
                ownership(graph, task, mode, tick, tick + 100),
                attempt,
                tick);
    }

    static ExecutionEvidence passingEvidence(
            TaskAssignment assignment,
            TaskPostcondition postcondition,
            long tick) {
        return new ExecutionEvidence(
                id("evidence:" + postcondition.conditionId().path()),
                postcondition.conditionId(),
                assignment.sessionId(),
                assignment.graphId(),
                assignment.taskId(),
                assignment.assignmentId(),
                assignment.executorId(),
                assignment.mode(),
                postcondition.requiredEvidenceKind(),
                postcondition.subjectId(),
                postcondition.expectedValues(),
                postcondition.expectedValues(),
                tick,
                true,
                "fixture:objective-readback");
    }
}
