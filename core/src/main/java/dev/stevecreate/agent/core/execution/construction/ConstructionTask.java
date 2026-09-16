package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.execution.RetryPolicy;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** One bounded plan-derived operation that any declared backend may execute. */
public record ConstructionTask(
        ResourceId taskId,
        VerifiedPlanTaskSource source,
        ResourceId capabilityId,
        TaskKind kind,
        ConstructionTaskClass taskClass,
        Set<ExecutionMode> allowedModes,
        List<TaskPrecondition> preconditions,
        List<TaskPostcondition> postconditions,
        RetryPolicy retryPolicy,
        boolean cancellable,
        RecoveryPolicy recoveryPolicy,
        CleanupPolicy cleanupPolicy,
        Set<ResourceId> requiredPlacementReservationIds,
        Set<ResourceId> requiredMaterialReservationIds,
        Set<ResourceId> requiredSharedInfrastructureReservationIds,
        long maximumTicks,
        Map<ResourceId, String> parameters) {
    public static final long MAXIMUM_TASK_TICKS = 72_000L;

    public ConstructionTask {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(capabilityId, "capabilityId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(taskClass, "taskClass");
        Objects.requireNonNull(allowedModes, "allowedModes");
        if (allowedModes.isEmpty()) {
            throw new IllegalArgumentException("allowedModes must not be empty");
        }
        allowedModes = Collections.unmodifiableSet(EnumSet.copyOf(allowedModes));
        preconditions = ConstructionContractValues.uniqueSorted(
                preconditions, "preconditions", TaskPrecondition::conditionId, false);
        postconditions = ConstructionContractValues.uniqueSorted(
                postconditions, "postconditions", TaskPostcondition::conditionId, true);
        Objects.requireNonNull(retryPolicy, "retryPolicy");
        Objects.requireNonNull(recoveryPolicy, "recoveryPolicy");
        Objects.requireNonNull(cleanupPolicy, "cleanupPolicy");
        requiredPlacementReservationIds = ConstructionContractValues.sortedIds(
                requiredPlacementReservationIds, "requiredPlacementReservationIds", false);
        requiredMaterialReservationIds = ConstructionContractValues.sortedIds(
                requiredMaterialReservationIds, "requiredMaterialReservationIds", false);
        requiredSharedInfrastructureReservationIds = ConstructionContractValues.sortedIds(
                requiredSharedInfrastructureReservationIds,
                "requiredSharedInfrastructureReservationIds", false);
        if (maximumTicks < 1 || maximumTicks > MAXIMUM_TASK_TICKS) {
            throw new IllegalArgumentException("maximumTicks must be between 1 and "
                    + MAXIMUM_TASK_TICKS);
        }
        parameters = ConstructionContractValues.parameters(parameters, "parameters");

        if (kind.mutatesWorld() && !source.sourceKind().permitsWorldMutation()) {
            throw new IllegalArgumentException("World-mutating tasks require placement, route or port provenance");
        }
        if (kind.mutatesWorld() && requiredPlacementReservationIds.isEmpty()) {
            throw new IllegalArgumentException("World-mutating tasks require a placement reservation");
        }
        if (kind.operatesOnMaterial() && requiredMaterialReservationIds.isEmpty()) {
            throw new IllegalArgumentException("Material tasks require a material reservation");
        }
        if (taskClass == ConstructionTaskClass.SHARED_INFRASTRUCTURE
                && requiredSharedInfrastructureReservationIds.isEmpty()) {
            throw new IllegalArgumentException("Shared infrastructure tasks require a shared reservation");
        }
        if (!cancellable && retryPolicy.allowsRetry()) {
            throw new IllegalArgumentException("A non-cancellable task cannot silently enter a retry loop");
        }
    }

    public Set<ResourceId> postconditionIds() {
        return postconditions.stream()
                .map(TaskPostcondition::conditionId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
