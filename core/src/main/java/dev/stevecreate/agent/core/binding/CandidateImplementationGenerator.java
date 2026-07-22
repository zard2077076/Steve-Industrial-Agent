package dev.stevecreate.agent.core.binding;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.CapabilityResourceRequirement;
import dev.stevecreate.agent.core.planning.CatalogRecipe;
import dev.stevecreate.agent.core.planning.LogicalGraphNode;
import dev.stevecreate.agent.core.planning.LogicalMachineGraph;
import dev.stevecreate.agent.core.planning.LogicalPortRequirement;
import dev.stevecreate.agent.core.planning.LogicalPortRole;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/** Generic filter-and-score pipeline with no Create-specific implementation branches. */
public final class CandidateImplementationGenerator {
    private static final int NON_PREFERRED_ADAPTER_PENALTY = 10_000;

    public ImplementationCandidateGenerationResult generate(
            LogicalGraphNode node,
            CatalogRecipe recipe,
            LogicalMachineGraph graph,
            MachineImplementationCatalog catalog,
            BindingConstraints constraints) {
        if (node == null || recipe == null || graph == null || catalog == null
                || constraints == null) {
            return failure(
                    BindingFailureCode.IMPLEMENTATION_CATALOG_MISSING,
                    node,
                    recipe,
                    List.of(),
                    constraints,
                    "binding_inputs=present",
                    "Supply a verified logical node, recipe, graph, catalog and constraints");
        }
        if (!catalog.runtimeFingerprint().equals(constraints.runtimeFingerprint())) {
            return failure(
                    BindingFailureCode.IMPLEMENTATION_RUNTIME_MISMATCH,
                    node,
                    recipe,
                    catalog.implementations(),
                    constraints,
                    "catalog_fingerprint=constraint_fingerprint",
                    "Rebuild implementation bindings from the current runtime snapshot");
        }

        List<MachineImplementationDescriptor> current = catalog.implementations();
        if (current.isEmpty()) {
            return failure(BindingFailureCode.IMPLEMENTATION_NOT_FOUND, node, recipe, current,
                    constraints, "implementation_count>0", "Publish a matching implementation");
        }
        FilterResult filtered = filter(current,
                value -> value.capabilityIds().containsAll(node.requiredCapabilities()),
                BindingFailureCode.IMPLEMENTATION_CAPABILITY_MISMATCH);
        if (filtered.empty()) {
            return failure(filtered.code(), node, recipe, current, constraints,
                    "implementation_capabilities_cover_node=true",
                    "Publish an implementation covering every logical capability");
        }
        current = filtered.values();
        filtered = filter(current, value -> value.supportsRecipeType(recipe.recipeType()),
                BindingFailureCode.IMPLEMENTATION_RECIPE_TYPE_MISMATCH);
        if (filtered.empty()) {
            return failure(filtered.code(), node, recipe, current, constraints,
                    "implementation_recipe_type=" + recipe.recipeType(),
                    "Use an implementation verified for the selected recipe type");
        }
        current = filtered.values();
        filtered = filter(current, value -> constraints.allowedAdapterIds().isEmpty()
                        || constraints.allowedAdapterIds().contains(value.adapterId()),
                BindingFailureCode.IMPLEMENTATION_ADAPTER_UNAVAILABLE);
        if (filtered.empty()) {
            return failure(filtered.code(), node, recipe, current, constraints,
                    "adapter_allowed=true", "Load and allow the owning runtime Adapter");
        }
        current = filtered.values();
        filtered = filter(current, value -> value.runtimeFingerprint()
                        .equals(constraints.runtimeFingerprint()),
                BindingFailureCode.IMPLEMENTATION_RUNTIME_MISMATCH);
        if (filtered.empty()) {
            return failure(filtered.code(), node, recipe, current, constraints,
                    "implementation_fingerprint=current_runtime",
                    "Rebuild the implementation catalog after runtime reload");
        }
        current = filtered.values();
        filtered = filter(current, value -> value.modVersion().equals(
                        constraints.availableModVersions().get(value.modId())),
                BindingFailureCode.IMPLEMENTATION_MOD_UNAVAILABLE);
        if (filtered.empty()) {
            return failure(filtered.code(), node, recipe, current, constraints,
                    "implementation_mod_version=runtime_mod_version",
                    "Load the exact mod version required by the implementation Adapter");
        }
        current = filtered.values();
        filtered = filter(current, value -> resourcePortsMatch(value, node, graph),
                BindingFailureCode.IMPLEMENTATION_PORT_CONTRACT_INVALID);
        if (filtered.empty()) {
            return failure(filtered.code(), node, recipe, current, constraints,
                    "logical_ports_covered=true",
                    "Repair the loader-neutral implementation port contracts");
        }
        current = filtered.values();
        filtered = filter(current, value -> powerContractsMatch(value, node),
                BindingFailureCode.IMPLEMENTATION_POWER_CONTRACT_INVALID);
        if (filtered.empty()) {
            return failure(filtered.code(), node, recipe, current, constraints,
                    "power_contracts_cover_requirements=true",
                    "Publish compatible continuous power contracts before binding");
        }
        current = filtered.values();
        filtered = filter(current, value -> value.verificationEvidence()
                        .containsAll(constraints.requiredEvidence())
                        && (!constraints.requirePhysicallyVerifiedExecution()
                        || value.executionSupport()
                        == ImplementationExecutionSupport.PHYSICALLY_VERIFIED),
                BindingFailureCode.IMPLEMENTATION_EXECUTION_UNVERIFIED);
        if (filtered.empty()) {
            return failure(filtered.code(), node, recipe, current, constraints,
                    "required_execution_evidence=present",
                    "Complete physical acceptance before enabling this implementation");
        }
        current = filtered.values();
        filtered = filter(current, value -> (!constraints.requireBindingAllowed()
                        || value.bindingAllowed())
                        && !constraints.forbiddenImplementationIds()
                        .contains(value.implementationId()),
                BindingFailureCode.IMPLEMENTATION_FORBIDDEN);
        if (filtered.empty()) {
            return failure(filtered.code(), node, recipe, current, constraints,
                    "implementation_policy=allowed",
                    "Select an implementation allowed by the current safety policy");
        }

        List<ImplementationSelectionCandidate> candidates = filtered.values().stream()
                .map(value -> score(value, constraints))
                .sorted(Comparator
                        .comparing(ImplementationSelectionCandidate::score)
                        .thenComparing(value -> value.descriptor().implementationId().toString()))
                .toList();
        if (!constraints.allowCanonicalTieBreak() && candidates.size() > 1
                && candidates.get(0).score().equals(candidates.get(1).score())) {
            return failure(
                    BindingFailureCode.MULTIPLE_IMPLEMENTATIONS_AMBIGUOUS,
                    node,
                    recipe,
                    candidates.stream().map(ImplementationSelectionCandidate::descriptor).toList(),
                    constraints,
                    "unique_minimum_score=true",
                    "Provide an explicit priority or permit the canonical ID tie-break");
        }
        return new ImplementationCandidateGenerationResult.Success(candidates);
    }

    static ImplementationSelectionScore scoreOf(
            MachineImplementationDescriptor descriptor,
            BindingConstraints constraints) {
        int adapterPenalty = constraints.preferredAdapterId().isPresent()
                && !constraints.preferredAdapterId().get().equals(descriptor.adapterId())
                ? NON_PREFERRED_ADAPTER_PENALTY : 0;
        int limitationPenalty = descriptor.limitations().size();
        int total = Math.addExact(descriptor.deterministicPriority(),
                Math.addExact(adapterPenalty, limitationPenalty));
        return new ImplementationSelectionScore(
                descriptor.deterministicPriority(), adapterPenalty, limitationPenalty, total);
    }

    private static ImplementationSelectionCandidate score(
            MachineImplementationDescriptor descriptor,
            BindingConstraints constraints) {
        ImplementationSelectionScore score = scoreOf(descriptor, constraints);
        return new ImplementationSelectionCandidate(
                descriptor,
                score,
                List.of(
                        "priority=" + score.descriptorPriority(),
                        "adapterPenalty=" + score.adapterPreferencePenalty(),
                        "limitationPenalty=" + score.limitationPenalty(),
                        "canonicalTieBreak=" + descriptor.implementationId()));
    }

    static boolean resourcePortsMatch(
            MachineImplementationDescriptor descriptor,
            LogicalGraphNode node,
            LogicalMachineGraph graph) {
        Map<PortKey, Integer> required = new LinkedHashMap<>();
        graph.ports().values().stream().filter(port -> port.nodeId().equals(node.id()))
                .filter(port -> port.role() == LogicalPortRole.PROCESS_INPUT
                        || port.role() == LogicalPortRole.PROCESS_OUTPUT)
                .forEach(port -> required.merge(
                        new PortKey(port.resourceType(), port.mode().acceptsInput()), 1, Integer::sum));
        for (Map.Entry<PortKey, Integer> entry : required.entrySet()) {
            List<ImplementationPortContract> matching = descriptor.ports().stream()
                    .filter(port -> port.resourceType().orElse(null) == entry.getKey().type())
                    .filter(port -> entry.getKey().input()
                            ? port.mode().acceptsInput() : port.mode().providesOutput())
                    .toList();
            if (matching.isEmpty()) {
                return false;
            }
            if (entry.getValue() > matching.size()
                    && matching.stream().noneMatch(ImplementationPortContract::multiplexable)) {
                return false;
            }
        }
        return true;
    }

    static boolean powerContractsMatch(
            MachineImplementationDescriptor descriptor,
            LogicalGraphNode node) {
        Map<GenericResourceType, CapabilityResourceRequirement> contracts = new EnumMap<>(
                GenericResourceType.class);
        descriptor.powerInputContracts().forEach(value -> contracts.put(
                value.resourceType(), value));
        for (CapabilityResourceRequirement required : node.requiredResources()) {
            CapabilityResourceRequirement actual = contracts.get(required.resourceType());
            if (actual == null || actual.minimumAmount() < required.minimumAmount()
                    || (required.continuous() && !actual.continuous())) {
                return false;
            }
        }
        return true;
    }

    private static FilterResult filter(
            List<MachineImplementationDescriptor> values,
            Predicate<MachineImplementationDescriptor> predicate,
            BindingFailureCode code) {
        return new FilterResult(values.stream().filter(predicate).toList(), code);
    }

    private static ImplementationCandidateGenerationResult.Failure failure(
            BindingFailureCode code,
            LogicalGraphNode node,
            CatalogRecipe recipe,
            List<MachineImplementationDescriptor> candidates,
            BindingConstraints constraints,
            String constraint,
            String nextStep) {
        List<ResourceId> ids = candidates.stream().map(
                MachineImplementationDescriptor::implementationId).toList();
        Optional<ResourceId> adapter = constraints == null
                ? Optional.empty() : constraints.preferredAdapterId();
        String fingerprint = constraints == null
                ? "runtime:unavailable" : constraints.runtimeFingerprint();
        Optional<ResourceId> nodeId = node == null ? Optional.empty() : Optional.of(node.id());
        Optional<ResourceId> capability = node == null
                ? Optional.empty() : node.requiredCapabilities().stream().findFirst();
        Optional<ResourceId> recipeId = recipe == null
                ? Optional.empty() : Optional.of(recipe.recipeId());
        return new ImplementationCandidateGenerationResult.Failure(new BindingFailure(
                code,
                BindingStage.CANDIDATE_GENERATION,
                nodeId,
                capability,
                recipeId,
                ids,
                adapter,
                fingerprint,
                constraint,
                List.of("candidate_generation:" + code.name().toLowerCase()),
                "No eligible implementation remained after deterministic filtering",
                code == BindingFailureCode.IMPLEMENTATION_MOD_UNAVAILABLE
                        || code == BindingFailureCode.IMPLEMENTATION_FORBIDDEN,
                nextStep));
    }

    private record FilterResult(
            List<MachineImplementationDescriptor> values,
            BindingFailureCode code) {
        private boolean empty() {
            return values.isEmpty();
        }
    }

    private record PortKey(GenericResourceType type, boolean input) {}
}
