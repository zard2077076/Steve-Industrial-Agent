package dev.stevecreate.agent.core.binding;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.verification.VerificationEvidenceKind;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Explicit bounded policy used for deterministic implementation selection. */
public record BindingConstraints(
        Set<ResourceId> allowedAdapterIds,
        Optional<ResourceId> preferredAdapterId,
        Set<ResourceId> forbiddenImplementationIds,
        Map<String, String> availableModVersions,
        Set<VerificationEvidenceKind> requiredEvidence,
        boolean requirePhysicallyVerifiedExecution,
        boolean requireBindingAllowed,
        boolean allowCanonicalTieBreak,
        String runtimeFingerprint) {
    public BindingConstraints {
        allowedAdapterIds = copyIds(allowedAdapterIds, "allowedAdapterIds");
        preferredAdapterId = Objects.requireNonNull(preferredAdapterId, "preferredAdapterId");
        if (preferredAdapterId.isPresent()
                && !allowedAdapterIds.contains(preferredAdapterId.get())) {
            throw new IllegalArgumentException("Preferred Adapter must be allowed");
        }
        forbiddenImplementationIds = copyIds(
                forbiddenImplementationIds, "forbiddenImplementationIds");
        Objects.requireNonNull(availableModVersions, "availableModVersions");
        TreeMap<String, String> mods = new TreeMap<>();
        availableModVersions.forEach((key, value) -> mods.put(
                requireText(key, "mod id"), requireText(value, "mod version")));
        availableModVersions = Collections.unmodifiableMap(new LinkedHashMap<>(mods));
        Objects.requireNonNull(requiredEvidence, "requiredEvidence");
        EnumSet<VerificationEvidenceKind> evidence = EnumSet.noneOf(
                VerificationEvidenceKind.class);
        requiredEvidence.forEach(value -> evidence.add(Objects.requireNonNull(
                value, "requiredEvidence element")));
        requiredEvidence = Collections.unmodifiableSet(new LinkedHashSet<>(evidence));
        runtimeFingerprint = requireText(runtimeFingerprint, "runtimeFingerprint");
    }

    public static BindingConstraints forRuntime(
            ResourceId adapterId,
            Map<String, String> availableModVersions,
            String runtimeFingerprint) {
        return new BindingConstraints(
                Set.of(Objects.requireNonNull(adapterId, "adapterId")),
                Optional.of(adapterId),
                Set.of(),
                availableModVersions,
                Set.of(),
                true,
                true,
                true,
                runtimeFingerprint);
    }

    private static Set<ResourceId> copyIds(Set<ResourceId> values, String name) {
        Objects.requireNonNull(values, name);
        TreeSet<ResourceId> sorted = new TreeSet<>(Comparator.comparing(ResourceId::toString));
        values.forEach(value -> sorted.add(Objects.requireNonNull(value, name + " element")));
        return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > MachineImplementationDescriptor.MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(name + " is blank or too long");
        }
        return value;
    }
}
