package dev.stevecreate.agent.core.industrial;

import dev.stevecreate.agent.core.execution.construction.ProjectMaterialLine;
import dev.stevecreate.agent.core.fluid.FluidIdentity;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.GenericProcessSpec;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Complete immutable authority input for a later Direct/Bot/Hybrid industrial executor. */
public record IndustrialProductionPlan(
        ResourceId planId,
        ResourceId projectId,
        ResourceId target,
        long targetQuantity,
        long batches,
        GenericProcessSpec process,
        ResourceId implementationId,
        ResourceId adapterId,
        BlockPos3i anchor,
        QuarterTurn orientation,
        List<IndustrialComponentPlacement> components,
        List<ProjectMaterialLine> itemMaterialLines,
        Map<FluidIdentity, Long> fluidRequirementsMb,
        IndustrialPowerRequirement powerRequirement,
        List<IndustrialExecutionStep> executionSteps,
        int botWorkers,
        String runtimeFingerprint,
        String planSha256) {
    public IndustrialProductionPlan {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(target, "target");
        if (targetQuantity < 1 || batches < 1) {
            throw new IllegalArgumentException("industrial production quantities are invalid");
        }
        Objects.requireNonNull(process, "process");
        Objects.requireNonNull(implementationId, "implementationId");
        Objects.requireNonNull(adapterId, "adapterId");
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(orientation, "orientation");
        components = List.copyOf(Objects.requireNonNull(components, "components"));
        itemMaterialLines = List.copyOf(Objects.requireNonNull(
                itemMaterialLines, "itemMaterialLines"));
        fluidRequirementsMb = Map.copyOf(Objects.requireNonNull(
                fluidRequirementsMb, "fluidRequirementsMb"));
        Objects.requireNonNull(powerRequirement, "powerRequirement");
        executionSteps = List.copyOf(Objects.requireNonNull(executionSteps, "executionSteps"));
        if (components.isEmpty() || itemMaterialLines.isEmpty() || executionSteps.isEmpty()
                || botWorkers < 2 || botWorkers > 5) {
            throw new IllegalArgumentException("industrial physical/material/fleet plan is incomplete");
        }
        Objects.requireNonNull(runtimeFingerprint, "runtimeFingerprint");
        if (runtimeFingerprint.isBlank() || !hash(planSha256)) {
            throw new IllegalArgumentException("industrial plan identity is invalid");
        }
    }

    /** Compatibility/readability accessor for existing FE-specific callers. */
    public long requiredEnergyFe() { return powerRequirement.requiredEnergyFe(); }

    public ResourceId electricalNetworkId() {
        return powerRequirement.electricalNetworkId().orElseThrow(() ->
                new IllegalStateException("industrial plan is not electrically powered"));
    }

    public String electricalNetworkFingerprint() {
        return powerRequirement.electricalNetworkFingerprint().orElseThrow(() ->
                new IllegalStateException("industrial plan is not electrically powered"));
    }

    private static boolean hash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
}
