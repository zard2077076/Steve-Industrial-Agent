package dev.stevecreate.agent.adapter.api.create;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Per-capability runtime counts and deterministic acceptance candidates. */
public record CapabilityCensusSummary(
        CreateCapabilityId capability,
        long discovered,
        long supportedPhaseI,
        long semanticsOnly,
        long unsupported,
        boolean notPresent,
        List<ResourceId> acceptanceCandidates) {
    public CapabilityCensusSummary {
        Objects.requireNonNull(capability, "capability");
        if (discovered < 0 || supportedPhaseI < 0 || semanticsOnly < 0 || unsupported < 0
                || discovered != supportedPhaseI + semanticsOnly + unsupported
                || notPresent != (discovered == 0)) {
            throw new IllegalArgumentException("Capability census counts are inconsistent");
        }
        acceptanceCandidates = List.copyOf(CapabilityContracts.sortedSet(
                acceptanceCandidates,
                Comparator.comparing(ResourceId::toString),
                "acceptanceCandidates"));
        if (supportedPhaseI == 0 != acceptanceCandidates.isEmpty()) {
            throw new IllegalArgumentException(
                    "Acceptance candidates must exist exactly when Phase-I support exists");
        }
    }
}
