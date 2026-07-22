package dev.stevecreate.agent.core.binding;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.verification.VerificationEvidenceKind;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable implementation snapshot scoped to one runtime fingerprint and reload generation. */
public final class ImmutableMachineImplementationCatalog implements MachineImplementationCatalog {
    public static final int MAX_IMPLEMENTATIONS = 256;

    private final List<MachineImplementationDescriptor> implementations;
    private final Map<ResourceId, MachineImplementationDescriptor> byId;
    private final String runtimeFingerprint;
    private final long reloadGeneration;

    public ImmutableMachineImplementationCatalog(
            List<MachineImplementationDescriptor> implementations,
            String runtimeFingerprint,
            long reloadGeneration) {
        Objects.requireNonNull(implementations, "implementations");
        if (implementations.isEmpty() || implementations.size() > MAX_IMPLEMENTATIONS) {
            throw new IllegalArgumentException(
                    "implementation count must be between 1 and " + MAX_IMPLEMENTATIONS);
        }
        this.runtimeFingerprint = requireText(runtimeFingerprint);
        if (reloadGeneration < 0) {
            throw new IllegalArgumentException("reloadGeneration cannot be negative");
        }
        this.reloadGeneration = reloadGeneration;

        List<MachineImplementationDescriptor> sorted = new ArrayList<>(implementations.size());
        for (MachineImplementationDescriptor value : implementations) {
            MachineImplementationDescriptor descriptor = Objects.requireNonNull(
                    value, "implementations element");
            if (!descriptor.runtimeFingerprint().equals(this.runtimeFingerprint)) {
                throw new IllegalArgumentException(
                        "Implementation runtime fingerprint does not match its catalog snapshot");
            }
            sorted.add(descriptor);
        }
        sorted.sort(Comparator.comparing(value -> value.implementationId().toString()));
        Map<ResourceId, MachineImplementationDescriptor> indexed = new LinkedHashMap<>();
        for (MachineImplementationDescriptor descriptor : sorted) {
            if (indexed.putIfAbsent(descriptor.implementationId(), descriptor) != null) {
                throw new IllegalArgumentException(
                        "Duplicate implementation ID: " + descriptor.implementationId());
            }
        }
        this.implementations = List.copyOf(sorted);
        this.byId = Collections.unmodifiableMap(indexed);
    }

    @Override
    public List<MachineImplementationDescriptor> implementations() {
        return implementations;
    }

    @Override
    public Optional<MachineImplementationDescriptor> find(ResourceId implementationId) {
        return Optional.ofNullable(byId.get(Objects.requireNonNull(
                implementationId, "implementationId")));
    }

    @Override
    public List<MachineImplementationDescriptor> implementationsForCapability(
            ResourceId capabilityId) {
        Objects.requireNonNull(capabilityId, "capabilityId");
        return implementations.stream().filter(value -> value.capabilityIds().contains(capabilityId))
                .toList();
    }

    @Override
    public List<MachineImplementationDescriptor> implementationsForRecipeType(
            ResourceId recipeType) {
        Objects.requireNonNull(recipeType, "recipeType");
        return implementations.stream()
                .filter(value -> value.supportedRecipeTypes().contains(recipeType)).toList();
    }

    @Override
    public List<MachineImplementationDescriptor> implementationsForAdapter(ResourceId adapterId) {
        Objects.requireNonNull(adapterId, "adapterId");
        return implementations.stream().filter(value -> value.adapterId().equals(adapterId)).toList();
    }

    @Override
    public List<MachineImplementationDescriptor> implementationsForRuntimeFingerprint(
            String runtimeFingerprint) {
        Objects.requireNonNull(runtimeFingerprint, "runtimeFingerprint");
        return implementations.stream()
                .filter(value -> value.runtimeFingerprint().equals(runtimeFingerprint)).toList();
    }

    @Override
    public List<MachineImplementationDescriptor> implementationsForMod(String modId) {
        Objects.requireNonNull(modId, "modId");
        return implementations.stream().filter(value -> value.modId().equals(modId)).toList();
    }

    @Override
    public List<MachineImplementationDescriptor> implementationsWithEvidence(
            Set<VerificationEvidenceKind> requiredEvidence) {
        Objects.requireNonNull(requiredEvidence, "requiredEvidence");
        return implementations.stream()
                .filter(value -> value.verificationEvidence().containsAll(requiredEvidence)).toList();
    }

    @Override
    public List<MachineImplementationDescriptor> implementationsWithExecutionSupport(
            ImplementationExecutionSupport support) {
        Objects.requireNonNull(support, "support");
        return implementations.stream().filter(value -> value.executionSupport() == support).toList();
    }

    @Override
    public String runtimeFingerprint() {
        return runtimeFingerprint;
    }

    @Override
    public long reloadGeneration() {
        return reloadGeneration;
    }

    private static String requireText(String value) {
        Objects.requireNonNull(value, "runtimeFingerprint");
        if (value.isBlank() || value.length() > MachineImplementationDescriptor.MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("runtimeFingerprint is blank or too long");
        }
        return value;
    }
}
