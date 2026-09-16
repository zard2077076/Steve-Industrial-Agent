package dev.stevecreate.agent.core.industrial;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Exact terminal evidence for the Bot assignments frozen by an entity-logistics binding. */
public record EntityLogisticsSettlementV1(
        ResourceId sessionId,
        String taskGraphFingerprint,
        List<ResourceId> completedAssignmentIds,
        int duplicateCompletions,
        long unaccountedCarriedItems,
        long privateItemsTouched) {
    public EntityLogisticsSettlementV1 {
        Objects.requireNonNull(sessionId, "sessionId");
        if (taskGraphFingerprint == null || !taskGraphFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("entity-logistics settlement fingerprint is invalid");
        }
        Objects.requireNonNull(completedAssignmentIds, "completedAssignmentIds");
        List<ResourceId> ordered = completedAssignmentIds.stream()
                .map(value -> Objects.requireNonNull(value, "completedAssignmentId"))
                .distinct().sorted(Comparator.comparing(ResourceId::toString)).toList();
        if (ordered.size() != completedAssignmentIds.size() || ordered.size() > 4_096
                || duplicateCompletions < 0 || unaccountedCarriedItems < 0
                || privateItemsTouched < 0) {
            throw new IllegalArgumentException("entity-logistics settlement is invalid");
        }
        completedAssignmentIds = ordered;
    }

    public boolean exactlySettles(EntityLogisticsBindingV1 binding) {
        Objects.requireNonNull(binding, "binding");
        return sessionId.equals(binding.sessionId())
                && taskGraphFingerprint.equals(binding.taskGraphFingerprint())
                && completedAssignmentIds.equals(binding.assignmentIds())
                && duplicateCompletions == 0 && unaccountedCarriedItems == 0
                && privateItemsTouched == 0;
    }
}
