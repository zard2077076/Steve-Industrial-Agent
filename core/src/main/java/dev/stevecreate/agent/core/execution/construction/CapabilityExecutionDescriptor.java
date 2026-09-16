package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Loader-neutral bridge from Adapter-owned capability semantics to construction tasks. */
public record CapabilityExecutionDescriptor(
        ResourceId capabilityId,
        ResourceId implementationId,
        ResourceId adapterId,
        String runtimeFingerprint,
        Set<TaskKind> supportedTaskKinds,
        Map<ExecutionMode, ModeCapabilityDeclaration> modeCapabilities,
        Map<ResourceId, String> parameters) {
    public CapabilityExecutionDescriptor {
        Objects.requireNonNull(capabilityId, "capabilityId");
        Objects.requireNonNull(implementationId, "implementationId");
        Objects.requireNonNull(adapterId, "adapterId");
        runtimeFingerprint = ConstructionContractValues.fingerprint(
                runtimeFingerprint, "runtimeFingerprint");
        Objects.requireNonNull(supportedTaskKinds, "supportedTaskKinds");
        if (supportedTaskKinds.isEmpty()) {
            throw new IllegalArgumentException("supportedTaskKinds must not be empty");
        }
        supportedTaskKinds = Collections.unmodifiableSet(EnumSet.copyOf(supportedTaskKinds));

        Objects.requireNonNull(modeCapabilities, "modeCapabilities");
        EnumMap<ExecutionMode, ModeCapabilityDeclaration> indexed = new EnumMap<>(ExecutionMode.class);
        for (Map.Entry<ExecutionMode, ModeCapabilityDeclaration> entry : modeCapabilities.entrySet()) {
            ExecutionMode mode = Objects.requireNonNull(entry.getKey(), "modeCapabilities key");
            ModeCapabilityDeclaration declaration = Objects.requireNonNull(
                    entry.getValue(), "modeCapabilities value");
            if (declaration.mode() != mode) {
                throw new IllegalArgumentException("Mode declaration key/value mismatch for " + mode);
            }
            indexed.put(mode, declaration);
        }
        if (indexed.size() != ExecutionMode.values().length) {
            throw new IllegalArgumentException("Every Direct/Bots/Hybrid mode must be declared exactly once");
        }
        Map<ExecutionMode, ModeCapabilityDeclaration> ordered = new LinkedHashMap<>();
        for (ExecutionMode mode : ExecutionMode.values()) {
            ordered.put(mode, indexed.get(mode));
        }
        modeCapabilities = Collections.unmodifiableMap(ordered);
        parameters = ConstructionContractValues.parameters(parameters, "parameters");
    }

    public ModeCapabilityDeclaration modeCapability(ExecutionMode mode) {
        return modeCapabilities.get(Objects.requireNonNull(mode, "mode"));
    }

    public boolean supports(ExecutionMode mode, TaskKind kind) {
        return supportedTaskKinds.contains(Objects.requireNonNull(kind, "kind"))
                && modeCapability(mode).support().executable();
    }
}
