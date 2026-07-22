package dev.stevecreate.agent.core.planning;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Loader-neutral identity of one item ingredient before deterministic candidate resolution.
 * Runtime registry or game Ingredient objects are never retained.
 */
public sealed interface RecipeIngredient permits RecipeIngredient.ExactResource,
        RecipeIngredient.AnyOfResources,
        RecipeIngredient.TagReference,
        RecipeIngredient.UnsupportedComplexIngredient {
    int MAX_CANDIDATES = 4_096;
    long MAX_AMOUNT = 1_000_000_000L;
    int MAX_IDENTITY_LENGTH = 2_048;
    int MAX_DETAIL_LENGTH = 2_048;

    RecipeIngredientKind kind();

    long amount();

    List<ResourceId> runtimeCandidates();

    String canonicalIdentity();

    record ExactResource(ResourceId resourceId, long amount) implements RecipeIngredient {
        public ExactResource {
            Objects.requireNonNull(resourceId, "resourceId");
            requireAmount(amount);
        }

        @Override
        public RecipeIngredientKind kind() {
            return RecipeIngredientKind.EXACT_RESOURCE;
        }

        @Override
        public List<ResourceId> runtimeCandidates() {
            return List.of(resourceId);
        }

        @Override
        public String canonicalIdentity() {
            return "exact:" + resourceId + "@" + amount;
        }
    }

    record AnyOfResources(List<ResourceId> resources, long amount) implements RecipeIngredient {
        public AnyOfResources {
            resources = copyCandidates(resources, 2, "resources");
            requireAmount(amount);
        }

        @Override
        public RecipeIngredientKind kind() {
            return RecipeIngredientKind.ANY_OF_RESOURCES;
        }

        @Override
        public List<ResourceId> runtimeCandidates() {
            return resources;
        }

        @Override
        public String canonicalIdentity() {
            return "any_of:[" + join(resources) + "]@" + amount;
        }
    }

    record TagReference(
            ResourceId tagId,
            List<ResourceId> runtimeCandidates,
            String runtimeFingerprint,
            long amount) implements RecipeIngredient {
        public TagReference {
            Objects.requireNonNull(tagId, "tagId");
            runtimeCandidates = copyCandidates(runtimeCandidates, 0, "runtimeCandidates");
            runtimeFingerprint = requireText(
                    runtimeFingerprint, "runtimeFingerprint", MAX_IDENTITY_LENGTH);
            requireAmount(amount);
        }

        @Override
        public RecipeIngredientKind kind() {
            return RecipeIngredientKind.TAG_REFERENCE;
        }

        @Override
        public String canonicalIdentity() {
            return "tag:" + tagId + "=[" + join(runtimeCandidates) + "]@" + amount
                    + "#" + runtimeFingerprint;
        }
    }

    record UnsupportedComplexIngredient(
            String identity,
            String detail,
            long amount) implements RecipeIngredient {
        public UnsupportedComplexIngredient {
            identity = requireText(identity, "identity", MAX_IDENTITY_LENGTH);
            detail = requireText(detail, "detail", MAX_DETAIL_LENGTH);
            requireAmount(amount);
        }

        @Override
        public RecipeIngredientKind kind() {
            return RecipeIngredientKind.UNSUPPORTED_COMPLEX_INGREDIENT;
        }

        @Override
        public List<ResourceId> runtimeCandidates() {
            return List.of();
        }

        @Override
        public String canonicalIdentity() {
            return "unsupported:" + identity + "@" + amount;
        }
    }

    private static List<ResourceId> copyCandidates(
            List<ResourceId> values,
            int minimum,
            String name) {
        Objects.requireNonNull(values, name);
        if (values.size() < minimum || values.size() > MAX_CANDIDATES) {
            throw new IllegalArgumentException(
                    name + " count must be between " + minimum + " and " + MAX_CANDIDATES);
        }
        List<ResourceId> sorted = new ArrayList<>(values.size());
        Set<ResourceId> unique = new LinkedHashSet<>();
        for (ResourceId value : values) {
            ResourceId candidate = Objects.requireNonNull(value, name + " element");
            if (!unique.add(candidate)) {
                throw new IllegalArgumentException(name + " contains duplicate resource: " + candidate);
            }
            sorted.add(candidate);
        }
        sorted.sort(Comparator.comparing(ResourceId::toString));
        return List.copyOf(sorted);
    }

    private static String join(List<ResourceId> values) {
        StringJoiner result = new StringJoiner(",");
        values.forEach(value -> result.add(value.toString()));
        return result.toString();
    }

    private static void requireAmount(long amount) {
        if (amount < 1 || amount > MAX_AMOUNT) {
            throw new IllegalArgumentException("amount must be between 1 and " + MAX_AMOUNT);
        }
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(
                    name + " must contain 1 to " + maximumLength + " characters");
        }
        return value;
    }
}
