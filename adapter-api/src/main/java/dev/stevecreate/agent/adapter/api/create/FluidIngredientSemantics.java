package dev.stevecreate.agent.adapter.api.create;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Loader-neutral fluid ingredient identity retained for census and typed Phase-I rejection. */
public record FluidIngredientSemantics(
        Kind kind,
        Optional<ResourceId> identity,
        List<ResourceId> runtimeCandidates,
        long amountMilliBuckets,
        Optional<String> runtimeFingerprint,
        Optional<String> unsupportedDetail) {
    public enum Kind {
        EXACT_RESOURCE,
        TAG_REFERENCE,
        ANY_OF_RESOURCES,
        UNSUPPORTED_COMPLEX
    }

    public FluidIngredientSemantics {
        Objects.requireNonNull(kind, "kind");
        identity = Objects.requireNonNull(identity, "identity");
        identity.ifPresent(value -> Objects.requireNonNull(value, "identity value"));
        runtimeCandidates = List.copyOf(CapabilityContracts.sortedSet(
                runtimeCandidates,
                Comparator.comparing(ResourceId::toString),
                "runtimeCandidates"));
        if (amountMilliBuckets < 1 || amountMilliBuckets > 1_000_000_000L) {
            throw new IllegalArgumentException("amountMilliBuckets is outside its bound");
        }
        runtimeFingerprint = copyText(runtimeFingerprint, "runtimeFingerprint");
        unsupportedDetail = copyText(unsupportedDetail, "unsupportedDetail");
        switch (kind) {
            case EXACT_RESOURCE -> require(
                    identity.isPresent() && runtimeCandidates.size() == 1
                            && runtimeFingerprint.isEmpty() && unsupportedDetail.isEmpty(),
                    "Exact fluid semantics are inconsistent");
            case TAG_REFERENCE -> require(
                    identity.isPresent() && runtimeFingerprint.isPresent()
                            && unsupportedDetail.isEmpty(),
                    "Fluid tag semantics are inconsistent");
            case ANY_OF_RESOURCES -> require(
                    identity.isEmpty() && runtimeCandidates.size() >= 2
                            && runtimeFingerprint.isPresent() && unsupportedDetail.isEmpty(),
                    "Fluid any-of semantics are inconsistent");
            case UNSUPPORTED_COMPLEX -> require(
                    identity.isEmpty() && runtimeCandidates.isEmpty()
                            && unsupportedDetail.isPresent(),
                    "Unsupported fluid semantics are incomplete");
        }
    }

    private static Optional<String> copyText(Optional<String> value, String name) {
        Objects.requireNonNull(value, name);
        return value.map(text -> CapabilityContracts.text(text, name));
    }

    private static void require(boolean condition, String detail) {
        if (!condition) {
            throw new IllegalArgumentException(detail);
        }
    }
}
