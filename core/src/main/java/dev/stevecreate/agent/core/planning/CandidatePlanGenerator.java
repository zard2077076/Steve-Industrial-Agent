package dev.stevecreate.agent.core.planning;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;

/** Deterministically converts satisfiable dependency graphs into unbound candidates. */
public final class CandidatePlanGenerator {
    private static final Comparator<ResourceKey> RESOURCE_ORDER = Comparator
            .comparing((ResourceKey value) -> value.resourceId().toString())
            .thenComparing(value -> value.resourceType().ordinal());

    public List<CandidatePlan> generate(PlanningSuccess success) {
        List<CandidatePlan> candidates = new ArrayList<>(success.graphs().size());
        for (ProcessDependencyGraph graph : success.graphs()) {
            candidates.add(generate(graph));
        }
        return List.copyOf(candidates);
    }

    private CandidatePlan generate(ProcessDependencyGraph graph) {
        List<CatalogRecipe> recipes = graph.steps().stream()
                .map(ProcessStepDependency::recipe)
                .toList();
        TreeSet<ResourceId> capabilities = new TreeSet<>(Comparator.comparing(ResourceId::toString));
        graph.steps().forEach(step -> capabilities.addAll(
                step.recipe().requiredMachineCapabilities()));

        List<ResourceId> order = graph.steps().stream()
                .map(ProcessStepDependency::stepId)
                .toList();
        List<CandidateQuantityConversion> conversions = graph.steps().stream()
                .map(step -> new CandidateQuantityConversion(
                        step.stepId(),
                        step.recipe().recipeId(),
                        step.executions(),
                        step.scaledInputs(),
                        step.scaledOutputs(),
                        step.scaledByproducts()))
                .toList();
        List<UnboundMachineNode> nodes = graph.steps().stream()
                .map(step -> new UnboundMachineNode(
                        machineNodeId(step.stepId()),
                        step.stepId(),
                        step.recipe().requiredMachineCapabilities()))
                .toList();
        UnboundSpatialLayout layout = new UnboundSpatialLayout(
                nodes.stream().map(UnboundMachineNode::nodeId).toList());

        return new CandidatePlan(
                candidateId(graph),
                graph.goal(),
                recipes,
                Collections.unmodifiableSet(new LinkedHashSet<>(capabilities)),
                graph.rawMaterials(),
                intermediates(graph.edges()),
                graph.ownedResourcesUsed(),
                order,
                conversions,
                graph.edges(),
                nodes,
                layout,
                evidence(graph),
                estimatedTicks(graph.steps()));
    }

    private static List<ProcessResource> intermediates(List<ProcessDependencyEdge> edges) {
        Map<ResourceKey, Long> amounts = new LinkedHashMap<>();
        for (ProcessDependencyEdge edge : edges) {
            amounts.merge(ResourceKey.of(edge.resource()), edge.resource().amount(), Math::addExact);
        }
        return amounts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(RESOURCE_ORDER))
                .map(entry -> new ProcessResource(
                        entry.getKey().resourceId(),
                        entry.getKey().resourceType(),
                        entry.getValue()))
                .toList();
    }

    private static List<PlanningEvidence> evidence(ProcessDependencyGraph graph) {
        List<PlanningEvidence> evidence = new ArrayList<>();
        addEvidence(
                evidence,
                PlanningEvidenceKind.GOAL_SATISFIED,
                graph.goal().target(),
                "Dependency graph provides the requested target quantity " + graph.goal().quantity());
        for (ProcessStepDependency step : graph.steps()) {
            addEvidence(
                    evidence,
                    PlanningEvidenceKind.RECIPE_SELECTED,
                    step.recipe().recipeId(),
                    "Selected deterministic catalog recipe for step " + step.stepId());
            addEvidence(
                    evidence,
                    PlanningEvidenceKind.QUANTITY_SCALED,
                    step.stepId(),
                    "Scaled recipe executions=" + step.executions());
            for (ResourceId capability : step.recipe().requiredMachineCapabilities()) {
                addEvidence(
                        evidence,
                        PlanningEvidenceKind.CAPABILITY_REQUIRED,
                        capability,
                        "Required by recipe step " + step.stepId());
            }
        }
        for (ProcessDependencyEdge edge : graph.edges()) {
            addEvidence(
                    evidence,
                    PlanningEvidenceKind.DEPENDENCY_RESOLVED,
                    edge.resource().resourceId(),
                    "Resolved " + edge.producerStepId() + " -> " + edge.consumerStepId()
                            + " amount=" + edge.resource().amount());
        }
        for (ProcessResource owned : graph.ownedResourcesUsed()) {
            addEvidence(
                    evidence,
                    PlanningEvidenceKind.OWNED_RESOURCE_APPLIED,
                    owned.resourceId(),
                    "Applied owned amount=" + owned.amount());
        }
        return List.copyOf(evidence);
    }

    private static void addEvidence(
            List<PlanningEvidence> evidence,
            PlanningEvidenceKind kind,
            ResourceId subject,
            String detail) {
        evidence.add(new PlanningEvidence(
                ResourceId.parse("planning:evidence_" + String.format("%04d", evidence.size() + 1)),
                kind,
                subject,
                detail));
    }

    private static OptionalLong estimatedTicks(List<ProcessStepDependency> steps) {
        long total = 0;
        for (ProcessStepDependency step : steps) {
            if (step.recipe().processingTicks().isEmpty()) {
                return OptionalLong.empty();
            }
            try {
                total = Math.addExact(
                        total,
                        Math.multiplyExact(
                                step.recipe().processingTicks().getAsLong(),
                                step.executions()));
            } catch (ArithmeticException exception) {
                return OptionalLong.empty();
            }
        }
        return OptionalLong.of(total);
    }

    private static ResourceId machineNodeId(ResourceId stepId) {
        return ResourceId.parse("planning:machine_" + stepId.path());
    }

    private static ResourceId candidateId(ProcessDependencyGraph graph) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            put(digest, graph.goal().target().toString());
            put(digest, graph.goal().targetResourceType().serializedName());
            put(digest, graph.goal().quantity());
            for (ProcessStepDependency step : graph.steps()) {
                put(digest, step.stepId().toString());
                put(digest, step.recipe().recipeId().toString());
                put(digest, step.executions());
                putResources(digest, step.scaledInputs());
                putResources(digest, step.scaledOutputs());
                putResources(digest, step.scaledByproducts());
            }
            for (ProcessDependencyEdge edge : graph.edges()) {
                put(digest, edge.producerStepId().toString());
                put(digest, edge.consumerStepId().toString());
                putResource(digest, edge.resource());
            }
            putResources(digest, graph.rawMaterials());
            putResources(digest, graph.ownedResourcesUsed());
            putResources(digest, graph.byproducts());
            return ResourceId.parse("planning:candidate_" + HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Required SHA-256 digest is unavailable", exception);
        }
    }

    private static void putResources(MessageDigest digest, List<ProcessResource> values) {
        put(digest, values.size());
        values.forEach(value -> putResource(digest, value));
    }

    private static void putResource(MessageDigest digest, ProcessResource value) {
        put(digest, value.resourceId().toString());
        put(digest, value.resourceType().serializedName());
        put(digest, value.amount());
    }

    private static void put(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static void put(MessageDigest digest, long value) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
    }

    private record ResourceKey(ResourceId resourceId, GenericResourceType resourceType) {
        private static ResourceKey of(ProcessResource resource) {
            return new ResourceKey(resource.resourceId(), resource.resourceType());
        }
    }
}
