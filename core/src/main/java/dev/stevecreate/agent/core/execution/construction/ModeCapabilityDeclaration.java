package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;
import java.util.Set;

/** One explicit Direct/Bot/Hybrid support declaration for an implementation capability. */
public record ModeCapabilityDeclaration(
        ExecutionMode mode,
        CapabilitySupport support,
        Set<ResourceId> requiredExecutorCapabilities,
        String limitation) {
    public ModeCapabilityDeclaration {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(support, "support");
        requiredExecutorCapabilities = ConstructionContractValues.sortedIds(
                requiredExecutorCapabilities, "requiredExecutorCapabilities", support.executable());
        limitation = ConstructionContractValues.boundedText(
                limitation, "limitation", 1_024, support == CapabilitySupport.SUPPORTED);
        if (support != CapabilitySupport.SUPPORTED && limitation.isBlank()) {
            throw new IllegalArgumentException("Constrained or unsupported modes require a limitation");
        }
        if (support == CapabilitySupport.UNSUPPORTED && !requiredExecutorCapabilities.isEmpty()) {
            throw new IllegalArgumentException("An unsupported mode cannot claim required executable capabilities");
        }
    }
}
