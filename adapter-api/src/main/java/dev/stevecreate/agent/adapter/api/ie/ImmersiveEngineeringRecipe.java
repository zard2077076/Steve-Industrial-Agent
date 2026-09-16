package dev.stevecreate.agent.adapter.api.ie;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.process.GenericProcessSpec;
import dev.stevecreate.agent.core.process.InputConsumptionRequirement;
import dev.stevecreate.agent.core.process.OutputVerificationRequirement;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Map;
import java.util.Set;

/** Loader-neutral IE recipe retaining energy, mold/tool and deterministic output semantics. */
public record ImmersiveEngineeringRecipe(
        ResourceId recipeId,
        ResourceId recipeType,
        List<ProcessResource> inputs,
        List<ProcessResource> outputs,
        List<ProcessResource> byproducts,
        Optional<ProcessResource> retainedMoldOrTool,
        long energyRequiredFe,
        boolean deterministicOutput,
        String sourceFingerprint,
        List<String> limitations) {
    public ImmersiveEngineeringRecipe {
        Objects.requireNonNull(recipeId, "recipeId");
        Objects.requireNonNull(recipeType, "recipeType");
        inputs = resources(inputs, "inputs", true);
        outputs = resources(outputs, "outputs", true);
        byproducts = resources(byproducts, "byproducts", false);
        retainedMoldOrTool = Objects.requireNonNull(retainedMoldOrTool, "retainedMoldOrTool");
        if (energyRequiredFe < 1 || energyRequiredFe > 1_000_000_000_000L
                || !deterministicOutput) {
            throw new IllegalArgumentException("IE recipe energy/output contract is unsupported");
        }
        Objects.requireNonNull(sourceFingerprint, "sourceFingerprint");
        if (!sourceFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("IE recipe source fingerprint is invalid");
        }
        Objects.requireNonNull(limitations, "limitations");
        if (limitations.size() > 64) throw new IllegalArgumentException("IE limitations exceed bound");
        limitations = limitations.stream().sorted().toList();
    }

    private static List<ProcessResource> resources(
            List<ProcessResource> values, String name, boolean required) {
        Objects.requireNonNull(values, name);
        if ((required && values.isEmpty()) || values.size() > 64) {
            throw new IllegalArgumentException(name + " is empty or unbounded");
        }
        return values.stream().sorted(Comparator.comparing(value -> value.resourceId().toString()))
                .toList();
    }

    /** Lossless loader-neutral projection consumed by the industrial planner. */
    public GenericProcessSpec toGenericProcessSpec(ResourceId implementationId) {
        Objects.requireNonNull(implementationId, "implementationId");
        return new GenericProcessSpec(recipeId, recipeType, inputs, outputs, byproducts,
                Set.of(implementationId), Set.of(
                        ResourceId.parse("industrial:evidence_input_consumed"),
                        ResourceId.parse("industrial:evidence_output_verified"),
                        ResourceId.parse("industrial:evidence_energy_debited")),
                GenericProcessSpec.MAX_WAIT_TICKS,
                InputConsumptionRequirement.EXACT_DECLARED,
                OutputVerificationRequirement.EXACT_DECLARED,
                Map.of(
                        ResourceId.parse("industrial:energy_fe"), Long.toString(energyRequiredFe),
                        ResourceId.parse("industrial:source_fingerprint"), sourceFingerprint,
                        ResourceId.parse("industrial:retained_tool"), retainedMoldOrTool
                                .map(value -> value.resourceId().toString()).orElse("none")));
    }
}
