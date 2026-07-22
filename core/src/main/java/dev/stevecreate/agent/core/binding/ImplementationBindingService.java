package dev.stevecreate.agent.core.binding;

import dev.stevecreate.agent.core.graph.PortMode;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.CandidatePlan;
import dev.stevecreate.agent.core.planning.CandidateQuantityConversion;
import dev.stevecreate.agent.core.planning.CatalogRecipe;
import dev.stevecreate.agent.core.planning.LogicalGraphNode;
import dev.stevecreate.agent.core.planning.LogicalNodeKind;
import dev.stevecreate.agent.core.planning.LogicalPortRequirement;
import dev.stevecreate.agent.core.planning.LogicalPortRole;
import dev.stevecreate.agent.core.planning.RecipeIngredientKind;
import dev.stevecreate.agent.core.planning.VerifiedLogicalPlan;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Binds every verified logical process node and delegates authority to the independent verifier. */
public final class ImplementationBindingService {
    private final CandidateImplementationGenerator generator;
    private final BindingVerifier verifier;

    public ImplementationBindingService() {
        this(new CandidateImplementationGenerator(), new BindingVerifier());
    }

    ImplementationBindingService(
            CandidateImplementationGenerator generator,
            BindingVerifier verifier) {
        this.generator = Objects.requireNonNull(generator, "generator");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
    }

    public BindingResult bind(
            VerifiedLogicalPlan logicalPlan,
            MachineImplementationCatalog catalog,
            BindingConstraints constraints) {
        if (logicalPlan == null) {
            return failure(BindingFailureCode.LOGICAL_NODE_UNBOUND, BindingStage.GRAPH_CONSTRUCTION,
                    Optional.empty(), Optional.empty(), Optional.empty(), List.of(), constraints,
                    "verified_logical_plan=present", "Provide a verified logical plan");
        }
        return bind(logicalPlan, catalog, constraints, defaultInputs(logicalPlan));
    }

    public BindingResult bind(
            VerifiedLogicalPlan logicalPlan,
            MachineImplementationCatalog catalog,
            BindingConstraints constraints,
            Map<ResourceId, List<BoundRecipeInput>> recipeInputs) {
        if (logicalPlan == null || catalog == null || constraints == null || recipeInputs == null) {
            return failure(BindingFailureCode.IMPLEMENTATION_CATALOG_MISSING,
                    BindingStage.GRAPH_CONSTRUCTION, Optional.empty(), Optional.empty(),
                    Optional.empty(), List.of(), constraints, "binding_inputs=present",
                    "Supply the verified plan, implementation snapshot, constraints and Ingredients");
        }
        if (!catalog.runtimeFingerprint().equals(constraints.runtimeFingerprint())) {
            return failure(BindingFailureCode.IMPLEMENTATION_RUNTIME_MISMATCH,
                    BindingStage.CATALOG_VALIDATION, Optional.empty(), Optional.empty(),
                    Optional.empty(), catalog.implementations().stream()
                            .map(MachineImplementationDescriptor::implementationId).toList(),
                    constraints, "catalog_fingerprint=current_runtime",
                    "Rebuild binding inputs from the current reload generation");
        }

        CandidatePlan candidate = logicalPlan.candidate();
        Map<ResourceId, StepData> steps = indexSteps(candidate);
        List<BoundMachineNode> bindings = new ArrayList<>();
        List<String> trace = new ArrayList<>();
        trace.add("logicalPlan=" + logicalPlan.id());
        trace.add("catalogGeneration=" + catalog.reloadGeneration());
        for (LogicalGraphNode node : logicalPlan.logicalGraph().nodes().values()) {
            if (node.kind() != LogicalNodeKind.PROCESS) {
                continue;
            }
            StepData step = steps.get(node.stepId().orElse(null));
            if (step == null) {
                return failure(BindingFailureCode.BINDING_GRAPH_INVALID,
                        BindingStage.GRAPH_CONSTRUCTION, Optional.of(node.id()),
                        node.requiredCapabilities().stream().findFirst(), Optional.empty(), List.of(),
                        constraints, "logical_step=candidate_step",
                        "Rebuild the logical plan before implementation binding");
            }
            ImplementationCandidateGenerationResult generated = generator.generate(
                    node, step.recipe(), logicalPlan.logicalGraph(), catalog, constraints);
            if (generated instanceof ImplementationCandidateGenerationResult.Failure failed) {
                return new BindingResult.Failure(failed.failure());
            }
            List<ImplementationSelectionCandidate> candidates =
                    ((ImplementationCandidateGenerationResult.Success) generated).candidates();
            ImplementationSelectionCandidate selected = candidates.get(0);
            Map<ResourceId, ResourceId> portBindings = bindPorts(
                    node, logicalPlan, selected.descriptor());
            if (portBindings == null) {
                return failure(BindingFailureCode.IMPLEMENTATION_PORT_CONTRACT_INVALID,
                        BindingStage.GRAPH_CONSTRUCTION, Optional.of(node.id()),
                        node.requiredCapabilities().stream().findFirst(),
                        Optional.of(step.recipe().recipeId()),
                        candidates.stream().map(value -> value.descriptor().implementationId()).toList(),
                        constraints, "logical_port_mapping=complete",
                        "Repair the implementation port contract before binding");
            }
            List<BoundRecipeInput> inputs = recipeInputs.get(step.recipe().recipeId());
            if (inputs == null) {
                return failure(BindingFailureCode.BINDING_GRAPH_INVALID,
                        BindingStage.GRAPH_CONSTRUCTION, Optional.of(node.id()),
                        node.requiredCapabilities().stream().findFirst(),
                        Optional.of(step.recipe().recipeId()), List.of(), constraints,
                        "recipe_ingredient_provenance=present",
                        "Preserve every resolved runtime Ingredient selection into binding");
            }
            MachineImplementationDescriptor descriptor = selected.descriptor();
            bindings.add(new BoundMachineNode(
                    node.id(),
                    step.conversion().stepId(),
                    step.recipe().recipeId(),
                    step.recipe().recipeType(),
                    descriptor.implementationId(),
                    descriptor.adapterId(),
                    descriptor.implementationFamily(),
                    descriptor.processingMode(),
                    portBindings,
                    inputs,
                    step.conversion(),
                    selected.score(),
                    candidates.stream().map(value -> value.descriptor().implementationId()).toList(),
                    selected.reasons(),
                    descriptor.limitations(),
                    descriptor.runtimeFingerprint()));
            trace.add("node=" + node.id() + ":implementation=" + descriptor.implementationId()
                    + ":score=" + selected.score().total());
        }

        ImplementationBoundMachineGraph graph = new ImplementationBoundMachineGraph(
                ResourceId.parse("binding:graph_" + logicalPlan.id().path()),
                logicalPlan,
                bindings,
                catalog.runtimeFingerprint(),
                catalog.reloadGeneration(),
                trace);
        BindingVerificationResult verified = verifier.verify(graph, catalog, constraints);
        if (verified instanceof BindingVerificationResult.Failure failed) {
            return new BindingResult.Failure(failed.failure());
        }
        return new BindingResult.Success(
                ((BindingVerificationResult.Success) verified).plan());
    }

    private static Map<ResourceId, List<BoundRecipeInput>> defaultInputs(
            VerifiedLogicalPlan logicalPlan) {
        Map<ResourceId, List<BoundRecipeInput>> result = new LinkedHashMap<>();
        for (CatalogRecipe recipe : logicalPlan.candidate().selectedRecipes()) {
            List<BoundRecipeInput> inputs = new ArrayList<>();
            for (int index = 0; index < recipe.inputs().size(); index++) {
                var input = recipe.inputs().get(index);
                inputs.add(new BoundRecipeInput(
                        recipe.recipeId(), index, RecipeIngredientKind.EXACT_RESOURCE,
                        "exact:" + input.resourceId() + "@" + input.amount(),
                        input.resourceId(), input.amount(), "EXACT_RESOURCE"));
            }
            result.put(recipe.recipeId(), List.copyOf(inputs));
        }
        return Map.copyOf(result);
    }

    private static Map<ResourceId, StepData> indexSteps(CandidatePlan candidate) {
        Map<ResourceId, StepData> steps = new LinkedHashMap<>();
        for (int index = 0; index < candidate.processingOrder().size(); index++) {
            steps.put(candidate.processingOrder().get(index), new StepData(
                    candidate.selectedRecipes().get(index),
                    candidate.quantityConversions().get(index)));
        }
        return steps;
    }

    private static Map<ResourceId, ResourceId> bindPorts(
            LogicalGraphNode node,
            VerifiedLogicalPlan logicalPlan,
            MachineImplementationDescriptor descriptor) {
        List<LogicalPortRequirement> logicalPorts = logicalPlan.logicalGraph().ports().values()
                .stream().filter(value -> value.nodeId().equals(node.id()))
                .filter(value -> value.role() == LogicalPortRole.PROCESS_INPUT
                        || value.role() == LogicalPortRole.PROCESS_OUTPUT)
                .sorted(Comparator.comparing(value -> value.id().toString())).toList();
        Map<ResourceId, ResourceId> result = new LinkedHashMap<>();
        Map<ResourceId, Integer> useCount = new LinkedHashMap<>();
        for (LogicalPortRequirement logical : logicalPorts) {
            List<ImplementationPortContract> compatible = descriptor.ports().stream()
                    .filter(port -> port.resourceType().orElse(null) == logical.resourceType())
                    .filter(port -> logical.mode() == PortMode.INPUT
                            ? port.mode().acceptsInput() : port.mode().providesOutput())
                    .sorted(Comparator.comparing(value -> value.portId().toString()))
                    .toList();
            ImplementationPortContract selected = compatible.stream()
                    .filter(port -> port.multiplexable()
                            || useCount.getOrDefault(port.portId(), 0) == 0)
                    .findFirst().orElse(null);
            if (selected == null) {
                return null;
            }
            result.put(logical.id(), selected.portId());
            useCount.merge(selected.portId(), 1, Integer::sum);
        }
        return result;
    }

    private static BindingResult.Failure failure(
            BindingFailureCode code,
            BindingStage stage,
            Optional<ResourceId> nodeId,
            Optional<ResourceId> capabilityId,
            Optional<ResourceId> recipeId,
            List<ResourceId> candidates,
            BindingConstraints constraints,
            String constraint,
            String nextStep) {
        return new BindingResult.Failure(new BindingFailure(
                code, stage, nodeId, capabilityId, recipeId, candidates,
                constraints == null ? Optional.empty() : constraints.preferredAdapterId(),
                constraints == null ? "runtime:unavailable" : constraints.runtimeFingerprint(),
                constraint,
                List.of("implementation_binding:" + code.name().toLowerCase()),
                "Implementation binding could not produce a verified bound plan",
                code == BindingFailureCode.IMPLEMENTATION_FORBIDDEN
                        || code == BindingFailureCode.IMPLEMENTATION_MOD_UNAVAILABLE,
                nextStep));
    }

    private record StepData(CatalogRecipe recipe, CandidateQuantityConversion conversion) {}
}
