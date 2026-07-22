package dev.stevecreate.agent.core.binding;

import dev.stevecreate.agent.core.graph.PortMode;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.CapabilityResourceRequirement;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.core.verification.VerificationEvidenceKind;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;

/** A functional implementation port contract without side, position, direction or geometry. */
public record ImplementationPortContract(
        ResourceId portId,
        ImplementationPortRole role,
        Optional<GenericResourceType> resourceType,
        PortMode mode,
        long minimumAmount,
        OptionalLong maximumThroughput,
        boolean required,
        boolean multiplexable,
        PortTemporalSemantics temporalSemantics,
        Set<ResourceId> compatibleConnectionTypes,
        Set<VerificationEvidenceKind> collectibleEvidence) {
    public static final int MAX_CONNECTION_TYPES = 64;

    public ImplementationPortContract {
        Objects.requireNonNull(portId, "portId");
        Objects.requireNonNull(role, "role");
        resourceType = Objects.requireNonNull(resourceType, "resourceType");
        mode = Objects.requireNonNull(mode, "mode");
        maximumThroughput = Objects.requireNonNull(maximumThroughput, "maximumThroughput");
        temporalSemantics = Objects.requireNonNull(temporalSemantics, "temporalSemantics");
        compatibleConnectionTypes = copyIds(compatibleConnectionTypes);
        collectibleEvidence = copyEvidence(collectibleEvidence);

        if (!role.expectedResourceType().equals(resourceType)) {
            throw new IllegalArgumentException(
                    "Port role resource type does not match its logical contract: " + role);
        }
        if (mode != role.expectedMode()) {
            throw new IllegalArgumentException(
                    "Port role mode does not match its logical contract: " + role);
        }
        if (resourceType.isPresent()) {
            if (minimumAmount < 1
                    || minimumAmount > CapabilityResourceRequirement.MAXIMUM_AMOUNT) {
                throw new IllegalArgumentException("minimumAmount must be positive and bounded");
            }
        } else if (minimumAmount != 0) {
            throw new IllegalArgumentException(
                    "Control, signal and special ports must use zero resource amount");
        }
        if (maximumThroughput.isPresent()
                && (maximumThroughput.getAsLong() < Math.max(1, minimumAmount)
                || maximumThroughput.getAsLong()
                        > CapabilityResourceRequirement.MAXIMUM_AMOUNT)) {
            throw new IllegalArgumentException(
                    "maximum throughput must cover minimum amount and remain bounded");
        }
    }

    private static Set<ResourceId> copyIds(Set<ResourceId> values) {
        Objects.requireNonNull(values, "compatibleConnectionTypes");
        if (values.size() > MAX_CONNECTION_TYPES) {
            throw new IllegalArgumentException("compatibleConnectionTypes exceeds its bound");
        }
        TreeSet<ResourceId> sorted = new TreeSet<>(Comparator.comparing(ResourceId::toString));
        for (ResourceId value : values) {
            sorted.add(Objects.requireNonNull(value, "compatibleConnectionTypes element"));
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }

    private static Set<VerificationEvidenceKind> copyEvidence(
            Set<VerificationEvidenceKind> values) {
        Objects.requireNonNull(values, "collectibleEvidence");
        EnumSet<VerificationEvidenceKind> sorted = EnumSet.noneOf(
                VerificationEvidenceKind.class);
        for (VerificationEvidenceKind value : values) {
            sorted.add(Objects.requireNonNull(value, "collectibleEvidence element"));
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }
}
