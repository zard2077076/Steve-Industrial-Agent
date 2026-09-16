package dev.stevecreate.agent.adapter.api.ie;

import dev.stevecreate.agent.adapter.api.RuntimeFingerprint;
import dev.stevecreate.agent.core.industrial.IndustrialPhysicalDescriptor;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record ImmersiveEngineeringMultiblockCatalogSnapshot(
        RuntimeFingerprint runtime,
        long reloadGeneration,
        String catalogSha256,
        List<IndustrialPhysicalDescriptor> implementations) {
    public ImmersiveEngineeringMultiblockCatalogSnapshot {
        Objects.requireNonNull(runtime, "runtime");
        if (reloadGeneration < 0) throw new IllegalArgumentException("IE reload generation is negative");
        if (catalogSha256 == null || !catalogSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("IE multiblock catalog fingerprint is invalid");
        }
        Objects.requireNonNull(implementations, "implementations");
        if (implementations.isEmpty() || implementations.size() > 1_024) {
            throw new IllegalArgumentException("IE multiblock catalog is empty or unbounded");
        }
        implementations = implementations.stream().sorted(Comparator.comparing(
                value -> value.implementationId().toString())).toList();
    }
}
