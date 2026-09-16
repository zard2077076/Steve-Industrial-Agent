package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Complete typed task failure with bounded intervention guidance. */
public record TaskFailure(
        ResourceId code,
        TaskFailureCategory category,
        boolean retryable,
        Optional<BlockPos3i> position,
        Optional<ResourceId> subjectId,
        String detail,
        List<ResourceId> trace,
        String safeNextStep) {
    public TaskFailure {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(category, "category");
        for (ConstructionFailureCode common : ConstructionFailureCode.values()) {
            if (common.id().equals(code) && common.category() != category) {
                throw new IllegalArgumentException("Common construction failure " + common
                        + " requires category " + common.category());
            }
        }
        if (retryable && !category.retryEligible()) {
            throw new IllegalArgumentException("Failure category " + category + " is not retry eligible");
        }
        position = Objects.requireNonNull(position, "position");
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
        detail = ConstructionContractValues.boundedText(
                detail, "detail", ConstructionContractValues.MAX_DETAIL_LENGTH, false);
        trace = List.copyOf(ConstructionContractValues.sortedIds(
                Set.copyOf(Objects.requireNonNull(trace, "trace")), "trace", true));
        safeNextStep = ConstructionContractValues.boundedText(
                safeNextStep, "safeNextStep", 1_024, false);
    }
}
