package dev.stevecreate.agent.core.planning;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.core.verification.VerificationEvidenceKind;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Immutable loader-neutral declaration of one adapter-owned processing capability. */
public record MachineCapability(
        ResourceId capabilityId,
        ResourceId adapterId,
        Set<ResourceId> supportedRecipeTypes,
        Set<GenericResourceType> inputPortTypes,
        Set<GenericResourceType> outputPortTypes,
        Set<CapabilityResourceRequirement> requiredResources,
        Set<ResourceId> availableActions,
        Set<VerificationEvidenceKind> collectibleEvidence,
        Set<ResourceId> supportedDiagnostics,
        CapabilityVersionLimits versionLimits) {
    public static final int MAX_IDS_PER_FIELD = 64;

    private static final Comparator<ResourceId> ID_ORDER = Comparator.comparing(ResourceId::toString);

    public MachineCapability {
        Objects.requireNonNull(capabilityId, "capabilityId");
        Objects.requireNonNull(adapterId, "adapterId");
        supportedRecipeTypes = copyIds(supportedRecipeTypes, "supportedRecipeTypes", true);
        inputPortTypes = copyTypes(inputPortTypes, "inputPortTypes", true);
        outputPortTypes = copyTypes(outputPortTypes, "outputPortTypes", true);
        requiredResources = copyRequirements(requiredResources);
        availableActions = copyIds(availableActions, "availableActions", true);
        collectibleEvidence = copyEvidence(collectibleEvidence);
        supportedDiagnostics = copyIds(supportedDiagnostics, "supportedDiagnostics", false);
        versionLimits = Objects.requireNonNull(versionLimits, "versionLimits");
    }

    /** Checks only declared typed compatibility; it performs no runtime or world access. */
    public boolean isCompatibleWith(CatalogRecipe recipe) {
        Objects.requireNonNull(recipe, "recipe");
        if (!recipe.requiredMachineCapabilities().contains(capabilityId)
                || !supportedRecipeTypes.contains(recipe.recipeType())) {
            return false;
        }
        for (ProcessResource input : recipe.inputs()) {
            if (!inputPortTypes.contains(input.resourceType())) {
                return false;
            }
        }
        for (ProcessResource output : recipe.outputs()) {
            if (!outputPortTypes.contains(output.resourceType())) {
                return false;
            }
        }
        for (ProcessResource byproduct : recipe.optionalByproducts()) {
            if (!outputPortTypes.contains(byproduct.resourceType())) {
                return false;
            }
        }
        return requiredResources.stream()
                .allMatch(value -> recipe.requiredResourceTypes().contains(value.resourceType()));
    }

    private static Set<ResourceId> copyIds(Set<ResourceId> values, String name, boolean required) {
        Objects.requireNonNull(values, name);
        if ((required && values.isEmpty()) || values.size() > MAX_IDS_PER_FIELD) {
            throw new IllegalArgumentException(name + " count violates capability bounds");
        }
        TreeSet<ResourceId> sorted = new TreeSet<>(ID_ORDER);
        for (ResourceId value : values) {
            sorted.add(Objects.requireNonNull(value, name + " element"));
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }

    private static Set<GenericResourceType> copyTypes(
            Set<GenericResourceType> values,
            String name,
            boolean required) {
        Objects.requireNonNull(values, name);
        if ((required && values.isEmpty()) || values.size() > GenericResourceType.values().length) {
            throw new IllegalArgumentException(name + " count violates capability bounds");
        }
        EnumSet<GenericResourceType> sorted = EnumSet.noneOf(GenericResourceType.class);
        for (GenericResourceType value : values) {
            sorted.add(Objects.requireNonNull(value, name + " element"));
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }

    private static Set<CapabilityResourceRequirement> copyRequirements(
            Set<CapabilityResourceRequirement> values) {
        Objects.requireNonNull(values, "requiredResources");
        if (values.size() > GenericResourceType.values().length) {
            throw new IllegalArgumentException("requiredResources exceeds its bound");
        }
        List<CapabilityResourceRequirement> sorted = new ArrayList<>(values.size());
        Set<GenericResourceType> types = new HashSet<>();
        for (CapabilityResourceRequirement value : values) {
            CapabilityResourceRequirement requirement = Objects.requireNonNull(
                    value, "requiredResources element");
            if (!types.add(requirement.resourceType())) {
                throw new IllegalArgumentException(
                        "Duplicate required resource type: " + requirement.resourceType());
            }
            sorted.add(requirement);
        }
        sorted.sort(Comparator.comparing(value -> value.resourceType().ordinal()));
        return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }

    private static Set<VerificationEvidenceKind> copyEvidence(
            Set<VerificationEvidenceKind> values) {
        Objects.requireNonNull(values, "collectibleEvidence");
        if (values.isEmpty() || values.size() > VerificationEvidenceKind.values().length) {
            throw new IllegalArgumentException("collectibleEvidence count violates capability bounds");
        }
        EnumSet<VerificationEvidenceKind> sorted = EnumSet.noneOf(VerificationEvidenceKind.class);
        for (VerificationEvidenceKind value : values) {
            sorted.add(Objects.requireNonNull(value, "collectibleEvidence element"));
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }
}
