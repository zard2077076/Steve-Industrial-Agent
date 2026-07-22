package dev.stevecreate.agent.core.kinetics;

import java.util.Objects;
import java.util.Set;

/** Normalized Create kinetic network measurements captured by a version adapter. */
public record KineticNetworkTelemetry(
        String networkId,
        Set<ComponentId> members,
        double speedRpm,
        boolean stressEnabled,
        double stressCapacity,
        double stressLoad) {
    public KineticNetworkTelemetry {
        Objects.requireNonNull(networkId, "networkId");
        if (networkId.isBlank()) {
            throw new IllegalArgumentException("Network id must not be blank");
        }
        members = Set.copyOf(Objects.requireNonNull(members, "members"));
        if (!Double.isFinite(speedRpm)) {
            throw new IllegalArgumentException("Speed must be finite");
        }
        if (!Double.isFinite(stressCapacity) || stressCapacity < 0) {
            throw new IllegalArgumentException("Stress capacity must be finite and non-negative");
        }
        if (!Double.isFinite(stressLoad) || stressLoad < 0) {
            throw new IllegalArgumentException("Stress load must be finite and non-negative");
        }
    }
}
