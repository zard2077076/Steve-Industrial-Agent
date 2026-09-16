package dev.stevecreate.agent.core.industrial;

import dev.stevecreate.agent.core.electrical.ElectricalNetworkGraph;
import dev.stevecreate.agent.core.execution.composite.CompositeProductionGraph;
import dev.stevecreate.agent.core.execution.construction.ExecutionMode;
import dev.stevecreate.agent.core.execution.construction.VerifiedProjectMaterialPlan;
import dev.stevecreate.agent.core.fluid.FluidNetworkGraph;
import dev.stevecreate.agent.core.model.ResourceId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Common order-definition envelope. Mod-specific planners provide the optional
 * physical/composite/resource graphs; the material ledger and execution mode
 * remain one shared contract.
 */
public record IndustrialPlayerOrderPlanV1(
        ResourceId orderType,
        ResourceId projectId,
        ResourceId target,
        long targetQuantity,
        VerifiedProjectMaterialPlan materialPlan,
        Optional<IndustrialProductionPlan> physicalPlan,
        Optional<CompositeProductionGraph> compositeGraph,
        Optional<ElectricalNetworkGraph> electricalNetwork,
        Optional<FluidNetworkGraph> fluidNetwork,
        Optional<IndustrialResourceBindingV1> resourceBinding,
        List<IndustrialCapability> capabilities,
        ExecutionMode executionMode,
        int botWorkers,
        String runtimeFingerprint,
        String planSha256) {
    public static final int MAX_CAPABILITIES = 16;

    public IndustrialPlayerOrderPlanV1 {
        Objects.requireNonNull(orderType, "orderType");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(target, "target");
        if (targetQuantity < 1 || targetQuantity > 1_000_000L) {
            throw new IllegalArgumentException("industrial player target quantity is invalid");
        }
        materialPlan = Objects.requireNonNull(materialPlan, "materialPlan");
        if (!materialPlan.projectId().equals(projectId)
                || !materialPlan.target().equals(target)
                || materialPlan.targetQuantity() != targetQuantity) {
            throw new IllegalArgumentException("material plan identity differs from player order");
        }
        physicalPlan = Objects.requireNonNull(physicalPlan, "physicalPlan");
        compositeGraph = Objects.requireNonNull(compositeGraph, "compositeGraph");
        electricalNetwork = Objects.requireNonNull(electricalNetwork, "electricalNetwork");
        fluidNetwork = Objects.requireNonNull(fluidNetwork, "fluidNetwork");
        resourceBinding = Objects.requireNonNull(resourceBinding, "resourceBinding");
        capabilities = List.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        if (capabilities.isEmpty() || capabilities.size() > MAX_CAPABILITIES
                || capabilities.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("industrial player capabilities are invalid");
        }
        capabilities = capabilities.stream().distinct().sorted(Comparator.comparing(Enum::name)).toList();
        Objects.requireNonNull(executionMode, "executionMode");
        if (botWorkers < 0 || botWorkers > 5 || executionMode != ExecutionMode.DIRECT && botWorkers < 2) {
            throw new IllegalArgumentException("industrial player fleet size is invalid");
        }
        Objects.requireNonNull(runtimeFingerprint, "runtimeFingerprint");
        if (runtimeFingerprint.isBlank() || runtimeFingerprint.length() > 16_384) {
            throw new IllegalArgumentException("industrial player runtime fingerprint is invalid");
        }
        Objects.requireNonNull(planSha256, "planSha256");
        if (!planSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("industrial player plan hash is invalid");
        }
        compositeGraph.ifPresent(graph -> {
            Objects.requireNonNull(graph.fingerprint(), "composite fingerprint");
        });
        electricalNetwork.ifPresent(graph -> {
            Objects.requireNonNull(graph.dimension(), "electrical dimension");
        });
        fluidNetwork.ifPresent(graph -> {
            if (graph.nodes().isEmpty()) throw new IllegalArgumentException("fluid graph is empty");
        });
        if (resourceBinding.isPresent()) {
            IndustrialResourceBindingV1 binding = resourceBinding.orElseThrow();
            if (!binding.projectId().equals(projectId)) {
                throw new IllegalArgumentException("resource binding belongs to another project");
            }
            if (!sameNetwork(electricalNetwork, binding.electricalNetwork(),
                    ElectricalNetworkGraph::fingerprint)
                    || !sameNetwork(fluidNetwork, binding.fluidNetwork(),
                    FluidNetworkGraph::fingerprint)) {
                throw new IllegalArgumentException("resource binding networks differ from order plan");
            }
        }
    }

    /** Backward-compatible plan constructor for orders that have no IPO-03 resource binding. */
    public IndustrialPlayerOrderPlanV1(
            ResourceId orderType,
            ResourceId projectId,
            ResourceId target,
            long targetQuantity,
            VerifiedProjectMaterialPlan materialPlan,
            Optional<IndustrialProductionPlan> physicalPlan,
            Optional<CompositeProductionGraph> compositeGraph,
            Optional<ElectricalNetworkGraph> electricalNetwork,
            Optional<FluidNetworkGraph> fluidNetwork,
            List<IndustrialCapability> capabilities,
            ExecutionMode executionMode,
            int botWorkers,
            String runtimeFingerprint,
            String planSha256) {
        this(orderType, projectId, target, targetQuantity, materialPlan, physicalPlan,
                compositeGraph, electricalNetwork, fluidNetwork, Optional.empty(), capabilities,
                executionMode, botWorkers, runtimeFingerprint, planSha256);
    }

    /** Stable envelope identity used by persistence and runtime re-registration. */
    public String fingerprint() {
        String canonical = orderType + "|" + projectId + "|" + target + "|" + targetQuantity
                + "|material=" + materialPlan.planSha256()
                + "|physical=" + physicalPlan.map(IndustrialProductionPlan::planSha256).orElse("")
                + "|composite=" + compositeGraph.map(CompositeProductionGraph::fingerprint).orElse("")
                + "|electrical=" + electricalNetwork.map(ElectricalNetworkGraph::fingerprint).orElse("")
                + "|fluid=" + fluidNetwork.map(FluidNetworkGraph::fingerprint).orElse("")
                + "|resources=" + resourceBinding.map(IndustrialResourceBindingV1::fingerprint).orElse("")
                + "|mode=" + executionMode + "|workers=" + botWorkers
                + "|runtime=" + runtimeFingerprint + "|plan=" + planSha256;
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static <T> boolean sameNetwork(
            Optional<T> planned, Optional<T> bound, java.util.function.Function<T, String> identity) {
        return planned.map(identity).equals(bound.map(identity));
    }
}
