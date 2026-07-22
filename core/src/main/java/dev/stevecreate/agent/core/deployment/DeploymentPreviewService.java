package dev.stevecreate.agent.core.deployment;

import dev.stevecreate.agent.core.binding.BoundMachineNode;
import dev.stevecreate.agent.core.graph.MachineNode;
import dev.stevecreate.agent.core.layout.PhysicalMachinePlacement;
import dev.stevecreate.agent.core.layout.VerifiedPhysicalPlan;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.CandidatePlan;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Pure deterministic projection from an already verified physical chain to a dry-run artifact. */
public final class DeploymentPreviewService {
    private static final Comparator<BlockPos3i> POSITION_ORDER = Comparator
            .comparingInt(BlockPos3i::x).thenComparingInt(BlockPos3i::y).thenComparingInt(BlockPos3i::z);
    private static final Comparator<ResourceId> ID_ORDER = Comparator.comparing(ResourceId::toString);
    private static final ResourceId AIR = ResourceId.parse("minecraft:air");

    public DeploymentPreview preview(VerifiedPhysicalPlan physical, DeploymentPreviewContext context) {
        Objects.requireNonNull(physical, "physical");
        Objects.requireNonNull(context, "context");
        CandidatePlan candidate = physical.candidate().boundPlan().graph().logicalPlan().candidate();

        List<DeploymentBlockPlacement> placements = physical.placements().stream()
                .flatMap(placement -> placement.components().stream())
                .map(component -> new DeploymentBlockPlacement(
                        component.position(), component.blockId(), component.blockState()))
                .sorted(Comparator.comparing(DeploymentBlockPlacement::position, POSITION_ORDER)
                        .thenComparing(value -> value.blockId().toString()))
                .toList();
        Map<BlockPos3i, DeploymentBlockPlacement> placementByPosition = new LinkedHashMap<>();
        placements.forEach(value -> placementByPosition.put(value.position(), value));

        List<DeploymentRoutePreview> itemRoutes = physical.routes().stream()
                .map(route -> new DeploymentRoutePreview(
                        route.id(), route.resourceType(), route.requiredAmount(), route.capacity(), route.positions()))
                .sorted(Comparator.comparing(value -> value.id().toString()))
                .toList();
        List<PhysicalMachinePlacement> orderedMachines = physical.placements().stream()
                .sorted(Comparator.comparing(value -> value.logicalNodeId().toString())).toList();
        List<DeploymentRoutePreview> powerRoutes = new ArrayList<>();
        for (int index = 0; index < orderedMachines.size(); index++) {
            PhysicalMachinePlacement placement = orderedMachines.get(index);
            powerRoutes.add(new DeploymentRoutePreview(
                    ResourceId.parse("deployment:power_route_" + index),
                    GenericResourceType.ROTATIONAL_POWER, placement.stressImpact(),
                    Math.max(placement.stressImpact(), 1), placement.rotationalPowerRoute()));
        }

        TreeSet<BlockPos3i> affected = new TreeSet<>(POSITION_ORDER);
        placements.forEach(value -> affected.add(value.position()));
        itemRoutes.forEach(value -> affected.addAll(value.positions()));
        powerRoutes.forEach(value -> affected.addAll(value.positions()));
        DeploymentBoundingBox bounds = DeploymentBoundingBox.enclosing(affected);

        List<DeploymentBlockObservation> observations = context.observations().values().stream()
                .filter(value -> affected.contains(value.position()))
                .sorted(Comparator.comparing(DeploymentBlockObservation::position, POSITION_ORDER))
                .toList();
        List<DeploymentBlockReplacement> replacements = observations.stream()
                .filter(value -> !value.blockId().equals(AIR))
                .filter(value -> placementByPosition.containsKey(value.position()))
                .map(value -> new DeploymentBlockReplacement(
                        value.position(), value.blockId(), placementByPosition.get(value.position()).blockId()))
                .toList();
        List<DeploymentBlockObservation> protectedBlocks = observations.stream()
                .filter(DeploymentBlockObservation::protectedBlock).toList();
        List<DeploymentBlockObservation> blockEntities = observations.stream()
                .filter(DeploymentBlockObservation::blockEntity).toList();

        Map<ResourceId, Long> machineMaterials = totals(placements.stream()
                .map(value -> new ProcessResource(value.blockId(), GenericResourceType.ITEM, 1)).toList());
        TreeMap<ResourceId, Long> combinedMaterials = new TreeMap<>(ID_ORDER);
        combinedMaterials.putAll(machineMaterials);
        context.routeAndPowerConstructionMaterials().forEach((resource, quantity) ->
                combinedMaterials.merge(resource, quantity, Math::addExact));
        Map<ResourceId, Long> materials = new LinkedHashMap<>(combinedMaterials);
        List<ProcessResource> inputs = new ArrayList<>(candidate.rawMaterials());
        inputs.addAll(candidate.ownedResourcesUsed());
        Map<ResourceId, Long> requiredInputs = totals(inputs);
        Map<ResourceId, Long> output = Map.of(candidate.goal().target(), candidate.goal().quantity());
        long stress = orderedMachines.stream().mapToLong(PhysicalMachinePlacement::stressImpact)
                .reduce(0, Math::addExact);

        List<ResourceId> recipeIds = candidate.selectedRecipes().stream()
                .map(value -> value.recipeId()).sorted(ID_ORDER).toList();
        List<ResourceId> implementationIds = orderedMachines.stream()
                .map(PhysicalMachinePlacement::implementationId).distinct().sorted(ID_ORDER).toList();
        List<QuarterTurn> orientations = orderedMachines.stream()
                .map(PhysicalMachinePlacement::orientation).distinct()
                .sorted(Comparator.comparingInt(Enum::ordinal)).toList();
        List<String> powerAssumptions = powerAssumptions(physical, context);
        List<String> policyViolations = policyViolations(
                physical, context, bounds, affected.size(), materials, stress, itemRoutes,
                protectedBlocks, blockEntities, replacements);
        List<String> approvals = requiredApprovals(context.policy());
        List<String> risks = riskFindings(
                context.environment().environmentType(), protectedBlocks, blockEntities,
                observations, replacements, policyViolations);

        DeploymentPreview draft = new DeploymentPreview(
                candidate.goal().target(), candidate.goal().quantity(), recipeIds, implementationIds,
                orderedMachines.get(0).anchor(), orientations, bounds, placements, List.of(), replacements,
                protectedBlocks, blockEntities, itemRoutes, powerRoutes, materials, requiredInputs, machineMaterials,
                output, candidate.estimatedProcessingTicks(), stress, powerAssumptions, affected.size(),
                context.rollbackClassification(), context.environment().environmentType(),
                physical.candidate().boundPlan().graph().runtimeFingerprint(),
                context.worldSnapshotFingerprint(), risks, approvals, policyViolations, "");
        return withHash(draft, sha256(DeploymentPreviewCodec.canonicalJson(draft, false)));
    }

    private static Map<ResourceId, Long> totals(List<ProcessResource> resources) {
        TreeMap<ResourceId, Long> sorted = new TreeMap<>(ID_ORDER);
        for (ProcessResource resource : resources) {
            sorted.merge(resource.resourceId(), resource.amount(), Math::addExact);
        }
        return new LinkedHashMap<>(sorted);
    }

    private static List<String> powerAssumptions(
            VerifiedPhysicalPlan physical, DeploymentPreviewContext context) {
        TreeSet<String> values = new TreeSet<>(context.powerSourceAssumptions());
        physical.unifiedGraph().nodes().values().stream()
                .filter(node -> node.roleId().toString().equals("layout:role_power_source"))
                .map(DeploymentPreviewService::powerAssumption)
                .forEach(values::add);
        return List.copyOf(values);
    }

    private static String powerAssumption(MachineNode node) {
        return "graph:" + node.implementationId() + "@"
                + node.relativePosition().x() + "," + node.relativePosition().y() + ","
                + node.relativePosition().z();
    }

    private static List<String> policyViolations(
            VerifiedPhysicalPlan physical,
            DeploymentPreviewContext context,
            DeploymentBoundingBox bounds,
            int affectedCount,
            Map<ResourceId, Long> materials,
            long stress,
            List<DeploymentRoutePreview> routes,
            List<DeploymentBlockObservation> protectedBlocks,
            List<DeploymentBlockObservation> blockEntities,
            List<DeploymentBlockReplacement> replacements) {
        DeploymentPolicy policy = context.policy();
        TreeSet<String> values = new TreeSet<>();
        if (!policy.allowsEnvironment(
                context.environment().environmentType(), context.environment().gameDirectoryIdentity())) {
            values.add("ENVIRONMENT_NOT_ALLOWED");
        }
        if (affectedCount > policy.maximumAffectedBlocks()) values.add("AFFECTED_BLOCK_LIMIT_EXCEEDED");
        if (affectedCount > policy.worldMutationBudget()) values.add("MUTATION_BUDGET_EXCEEDED");
        if (bounds.volume() > policy.maximumBoundingVolume()) values.add("BOUNDING_VOLUME_EXCEEDED");
        if (materials.values().stream().mapToLong(Long::longValue).sum() > policy.maximumMaterialCost()) {
            values.add("MATERIAL_COST_EXCEEDED");
        }
        if (stress > policy.maximumRotationalStressDemand()) values.add("ROTATIONAL_STRESS_EXCEEDED");
        if (routes.stream().anyMatch(route -> route.positions().size() > policy.maximumRouteLength())) {
            values.add("ROUTE_LENGTH_EXCEEDED");
        }
        if ((!routes.isEmpty() || !physical.placements().isEmpty())
                && context.routeAndPowerConstructionMaterials().isEmpty()) {
            values.add("ROUTE_OR_POWER_MATERIALS_UNDECLARED");
        }
        Set<ResourceId> implementations = physical.placements().stream()
                .map(PhysicalMachinePlacement::implementationId).collect(java.util.stream.Collectors.toSet());
        if (!policy.allowedImplementationIds().isEmpty()
                && !policy.allowedImplementationIds().containsAll(implementations)) {
            values.add("IMPLEMENTATION_NOT_ALLOWED");
        }
        Set<BoundMachineNode> nodes = new LinkedHashSet<>(
                physical.candidate().boundPlan().graph().boundProcessNodes().values());
        if (!policy.allowedAdapterIds().isEmpty() && nodes.stream()
                .map(BoundMachineNode::adapterId).anyMatch(id -> !policy.allowedAdapterIds().contains(id))) {
            values.add("ADAPTER_NOT_ALLOWED");
        }
        if (!policy.allowedRecipeTypes().isEmpty() && nodes.stream()
                .map(BoundMachineNode::recipeType).anyMatch(id -> !policy.allowedRecipeTypes().contains(id))) {
            values.add("RECIPE_TYPE_NOT_ALLOWED");
        }
        if (!protectedBlocks.isEmpty()
                && policy.playerBuiltBlockProtectionPolicy() == PlayerBuiltBlockProtectionPolicy.PROTECT) {
            values.add("PROTECTED_BLOCK_REPLACEMENT_FORBIDDEN");
        }
        if (!blockEntities.isEmpty()
                && policy.blockEntityProtectionPolicy() == BlockEntityProtectionPolicy.PROTECT) {
            values.add("BLOCK_ENTITY_REPLACEMENT_FORBIDDEN");
        }
        if (!replacements.isEmpty()
                && policy.unknownBlockReplacementPolicy() == UnknownBlockReplacementPolicy.FORBIDDEN) {
            values.add("UNKNOWN_BLOCK_REPLACEMENT_FORBIDDEN");
        }
        if (policy.rollbackRequired()
                && context.rollbackClassification() == RollbackClassification.UNSUPPORTED) {
            values.add("ROLLBACK_UNSUPPORTED");
        }
        return List.copyOf(values);
    }

    private static List<String> requiredApprovals(DeploymentPolicy policy) {
        TreeSet<String> values = new TreeSet<>();
        if (policy.humanApprovalRequired()) values.add("HUMAN_APPROVAL");
        if (policy.backupRequired()) values.add("VERIFIED_BACKUP");
        if (policy.rollbackRequired()) values.add("VERIFIED_ROLLBACK");
        if (policy.claimPermissionRequirement() == ClaimPermissionRequirement.REQUIRED) {
            values.add("CLAIM_PERMISSION");
        }
        values.add("REGION_AUTHORIZATION");
        values.add("FRESH_WORLD_SNAPSHOT");
        return List.copyOf(values);
    }

    private static List<String> riskFindings(
            WorldEnvironmentType environment,
            List<DeploymentBlockObservation> protectedBlocks,
            List<DeploymentBlockObservation> blockEntities,
            List<DeploymentBlockObservation> observations,
            List<DeploymentBlockReplacement> replacements,
            List<String> policyViolations) {
        TreeSet<String> values = new TreeSet<>();
        if (environment == WorldEnvironmentType.FORMAL_PLAYER_WORLD) values.add("FORMAL_WORLD_PREVIEW_ONLY");
        if (!protectedBlocks.isEmpty()) values.add("PROTECTED_BLOCKS_ENCOUNTERED");
        if (!blockEntities.isEmpty()) values.add("BLOCK_ENTITIES_ENCOUNTERED");
        if (observations.stream().anyMatch(DeploymentBlockObservation::containerHasContents)) {
            values.add("CONTAINER_CONTENTS_ENCOUNTERED");
        }
        if (!replacements.isEmpty()) values.add("BLOCK_REPLACEMENTS_PLANNED");
        policyViolations.forEach(value -> values.add("POLICY:" + value));
        return List.copyOf(values);
    }

    private static DeploymentPreview withHash(DeploymentPreview value, String hash) {
        return new DeploymentPreview(
                value.target(), value.quantity(), value.recipeIds(), value.implementationIds(),
                value.anchor(), value.orientations(), value.affectedBounds(), value.plannedPlacements(),
                value.plannedRemovals(), value.plannedReplacements(), value.protectedBlocksEncountered(),
                value.blockEntitiesEncountered(), value.itemRoutes(), value.rotationalPowerRoutes(),
                value.materialBillOfMaterials(), value.requiredInputResources(),
                value.machineConstructionMaterials(), value.expectedOutput(), value.estimatedTicks(),
                value.stressDemand(), value.powerSourceAssumptions(), value.journalEstimate(),
                value.rollbackClassification(), value.environmentClassification(), value.runtimeFingerprint(),
                value.worldSnapshotFingerprint(), value.riskFindings(), value.requiredApprovals(),
                value.policyViolations(), hash);
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte item : digest) hex.append(String.format("%02x", item));
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Required SHA-256 digest is unavailable", exception);
        }
    }
}
