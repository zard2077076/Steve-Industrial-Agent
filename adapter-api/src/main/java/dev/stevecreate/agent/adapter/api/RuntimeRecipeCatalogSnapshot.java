package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.planning.RuntimeRecipeCatalog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Immutable loader-neutral catalog snapshot; it never retains a game recipe object. */
public record RuntimeRecipeCatalogSnapshot(
        RuntimeRecipeCatalog catalog,
        RuntimeFingerprint runtime,
        String runtimeFingerprint,
        String worldIdentity,
        long reloadGeneration,
        int discoveredRecipeCount,
        int mappedRecipeCount,
        List<RuntimeKnowledgeFailure> limitations,
        List<RuntimeRecipeMappingWarning> warnings) {
    public static final int MAX_DISCOVERED_RECIPES = 65_536;
    public static final int MAX_LIMITATIONS = 4_096;
    public static final int MAX_WARNINGS = 4_096;

    private static final Comparator<RuntimeKnowledgeFailure> LIMITATION_ORDER = Comparator
            .comparing((RuntimeKnowledgeFailure value) -> value.recipeId()
                    .map(Object::toString).orElse(""))
            .thenComparing(value -> value.code().ordinal())
            .thenComparing(value -> value.ingredientIdentity().orElse(""));
    private static final Comparator<RuntimeRecipeMappingWarning> WARNING_ORDER = Comparator
            .comparing((RuntimeRecipeMappingWarning value) -> value.recipeId().toString())
            .thenComparing(value -> value.code().ordinal())
            .thenComparing(value -> String.join("|", value.trace()));

    public RuntimeRecipeCatalogSnapshot {
        Objects.requireNonNull(catalog, "catalog");
        if (catalog.recipes().isEmpty()) {
            throw new IllegalArgumentException("Runtime recipe catalog success must not be empty");
        }
        runtime = Objects.requireNonNull(runtime, "runtime");
        runtimeFingerprint = requireText(runtimeFingerprint, "runtimeFingerprint", 2_048);
        worldIdentity = requireText(worldIdentity, "worldIdentity", 256);
        if (reloadGeneration < 0) {
            throw new IllegalArgumentException("reloadGeneration must not be negative");
        }
        if (discoveredRecipeCount < 1 || discoveredRecipeCount > MAX_DISCOVERED_RECIPES) {
            throw new IllegalArgumentException("discoveredRecipeCount is outside its bound");
        }
        if (mappedRecipeCount != catalog.recipes().size()
                || mappedRecipeCount < 1
                || mappedRecipeCount > discoveredRecipeCount) {
            throw new IllegalArgumentException(
                    "mappedRecipeCount must equal the nonempty catalog size and not exceed discovery");
        }
        Objects.requireNonNull(limitations, "limitations");
        if (limitations.size() > MAX_LIMITATIONS
                || mappedRecipeCount + limitations.size() > discoveredRecipeCount) {
            throw new IllegalArgumentException("limitations are inconsistent with discovery counts");
        }
        List<RuntimeKnowledgeFailure> sorted = new ArrayList<>(limitations.size());
        for (RuntimeKnowledgeFailure limitation : limitations) {
            sorted.add(Objects.requireNonNull(limitation, "limitations element"));
        }
        sorted.sort(LIMITATION_ORDER);
        limitations = List.copyOf(sorted);
        Objects.requireNonNull(warnings, "warnings");
        if (warnings.size() > MAX_WARNINGS) {
            throw new IllegalArgumentException("warnings exceed their bound");
        }
        List<RuntimeRecipeMappingWarning> sortedWarnings = new ArrayList<>(warnings.size());
        for (RuntimeRecipeMappingWarning warning : warnings) {
            sortedWarnings.add(Objects.requireNonNull(warning, "warnings element"));
        }
        sortedWarnings.sort(WARNING_ORDER);
        warnings = List.copyOf(sortedWarnings);
    }

    public String canonicalRuntimeFingerprint() {
        return runtimeFingerprint;
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
