package dev.stevecreate.agent.core.industrial;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Version-fingerprinted capability declaration; it is descriptive and grants no mutation. */
public record IndustrialCapabilityProfile(
        ResourceId adapterId,
        Set<IndustrialCapability> capabilities,
        String runtimeFingerprint,
        boolean readOnlyDiscovery,
        boolean physicalExecutionImplemented,
        String limitation) {
    public IndustrialCapabilityProfile {
        Objects.requireNonNull(adapterId, "adapterId");
        Objects.requireNonNull(capabilities, "capabilities");
        if (capabilities.isEmpty()) {
            throw new IllegalArgumentException("industrial capabilities are empty");
        }
        capabilities = Set.copyOf(EnumSet.copyOf(capabilities));
        Objects.requireNonNull(runtimeFingerprint, "runtimeFingerprint");
        if (runtimeFingerprint.isBlank() || runtimeFingerprint.length() > 16_384) {
            throw new IllegalArgumentException("industrial runtime fingerprint is blank or unbounded");
        }
        Objects.requireNonNull(limitation, "limitation");
        if (limitation.isBlank() || limitation.length() > 2_048) {
            throw new IllegalArgumentException("industrial capability limitation is blank or unbounded");
        }
        if (physicalExecutionImplemented && readOnlyDiscovery) {
            throw new IllegalArgumentException("read-only capability profile cannot claim execution");
        }
    }

    public boolean supports(IndustrialCapability capability) {
        return capabilities.contains(Objects.requireNonNull(capability, "capability"));
    }
}
