package dev.stevecreate.agent.core.binding;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.CandidateQuantityConversion;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** A logical process node with one concrete implementation, still without physical authority. */
public record BoundMachineNode(
        ResourceId logicalNodeId,
        ResourceId stepId,
        ResourceId recipeId,
        ResourceId recipeType,
        ResourceId implementationId,
        ResourceId adapterId,
        ResourceId implementationFamily,
        ResourceId processingMode,
        Map<ResourceId, ResourceId> logicalToImplementationPorts,
        List<BoundRecipeInput> recipeInputs,
        CandidateQuantityConversion quantityConversion,
        ImplementationSelectionScore selectionScore,
        List<ResourceId> consideredImplementationIds,
        List<String> selectionReasons,
        List<String> limitations,
        String runtimeFingerprint) {
    public BoundMachineNode {
        Objects.requireNonNull(logicalNodeId, "logicalNodeId");
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(recipeId, "recipeId");
        Objects.requireNonNull(recipeType, "recipeType");
        Objects.requireNonNull(implementationId, "implementationId");
        Objects.requireNonNull(adapterId, "adapterId");
        Objects.requireNonNull(implementationFamily, "implementationFamily");
        Objects.requireNonNull(processingMode, "processingMode");
        Objects.requireNonNull(logicalToImplementationPorts, "logicalToImplementationPorts");
        TreeMap<ResourceId, ResourceId> ports = new TreeMap<>(Comparator.comparing(
                ResourceId::toString));
        logicalToImplementationPorts.forEach((key, value) -> ports.put(
                Objects.requireNonNull(key, "logical port id"),
                Objects.requireNonNull(value, "implementation port id")));
        logicalToImplementationPorts = Collections.unmodifiableMap(new LinkedHashMap<>(ports));
        recipeInputs = sortedInputs(recipeInputs);
        quantityConversion = Objects.requireNonNull(quantityConversion, "quantityConversion");
        selectionScore = Objects.requireNonNull(selectionScore, "selectionScore");
        consideredImplementationIds = sortedIds(consideredImplementationIds);
        if (!consideredImplementationIds.contains(implementationId)) {
            throw new IllegalArgumentException("Considered implementations must contain the selection");
        }
        selectionReasons = copyText(selectionReasons, "selectionReasons", true);
        limitations = copyText(limitations, "limitations", false);
        runtimeFingerprint = requireText(runtimeFingerprint, "runtimeFingerprint");
        if (!stepId.equals(quantityConversion.stepId())
                || !recipeId.equals(quantityConversion.recipeId())) {
            throw new IllegalArgumentException("Bound node identity disagrees with quantity conversion");
        }
    }

    private static List<BoundRecipeInput> sortedInputs(List<BoundRecipeInput> values) {
        Objects.requireNonNull(values, "recipeInputs");
        List<BoundRecipeInput> sorted = new ArrayList<>(values);
        sorted.forEach(value -> Objects.requireNonNull(value, "recipeInputs element"));
        sorted.sort(Comparator.comparingInt(BoundRecipeInput::inputIndex));
        return List.copyOf(sorted);
    }

    private static List<ResourceId> sortedIds(List<ResourceId> values) {
        Objects.requireNonNull(values, "consideredImplementationIds");
        List<ResourceId> sorted = new ArrayList<>(values);
        sorted.forEach(value -> Objects.requireNonNull(value, "consideredImplementationIds element"));
        sorted.sort(Comparator.comparing(ResourceId::toString));
        return List.copyOf(sorted);
    }

    private static List<String> copyText(List<String> values, String name, boolean required) {
        Objects.requireNonNull(values, name);
        List<String> copy = values.stream().map(value -> requireText(value, name + " element"))
                .sorted().toList();
        if (required && copy.isEmpty()) {
            throw new IllegalArgumentException(name + " cannot be empty");
        }
        return copy;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 16_384) {
            throw new IllegalArgumentException(name + " is blank or too long");
        }
        return value;
    }
}
