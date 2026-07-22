package dev.stevecreate.agent.core.binding;

import java.util.List;
import java.util.Objects;

/** One eligible implementation and the complete deterministic score explanation. */
public record ImplementationSelectionCandidate(
        MachineImplementationDescriptor descriptor,
        ImplementationSelectionScore score,
        List<String> reasons) {
    public ImplementationSelectionCandidate {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(score, "score");
        Objects.requireNonNull(reasons, "reasons");
        reasons = List.copyOf(reasons);
        if (reasons.isEmpty() || reasons.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("Selection candidates require nonblank reasons");
        }
    }
}
