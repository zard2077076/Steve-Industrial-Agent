package dev.stevecreate.agent.core.planning;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Bounded deterministic expansion of a typed goal into coordinate-free recipe dependency graphs. */
public final class ProcessDependencyGraphBuilder {
    private static final Comparator<ResourceKey> RESOURCE_ORDER = Comparator
            .comparing((ResourceKey value) -> value.resourceId().toString())
            .thenComparing(value -> value.resourceType().ordinal());

    public PlanningResult plan(
            ProductionGoal goal,
            RecipeCatalog recipes,
            MachineCapabilityCatalog capabilities,
            PlanningContext context) {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(recipes, "recipes");
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(context, "context");
        if (!context.supportedResourceTypes().contains(goal.targetResourceType())) {
            return failure(failure(
                    PlanningFailureCode.RESOURCE_TYPE_UNSUPPORTED,
                    0,
                    goal.target(),
                    null,
                    List.of(goal.target()),
                    "The requested target resource type is not supported by this planning context",
                    List.of()));
        }

        BranchState initial = new BranchState(goal.ownedResources());
        Expansion expansion;
        try {
            expansion = resolve(
                    goal.target(),
                    goal.targetResourceType(),
                    goal.quantity(),
                    0,
                    true,
                    List.of(),
                    initial,
                    goal,
                    recipes,
                    capabilities,
                    context);
        } catch (ArithmeticException exception) {
            return failure(failure(
                    PlanningFailureCode.INVALID_QUANTITY,
                    0,
                    goal.target(),
                    null,
                    List.of(goal.target()),
                    "Quantity scaling overflowed the bounded planner",
                    List.of()));
        }

        if (expansion.resolutions().isEmpty()) {
            return failure(firstFailure(expansion, goal.target()));
        }
        if (expansion.resolutions().size() > PlanningSuccess.MAX_GRAPHS) {
            return failure(failure(
                    PlanningFailureCode.AMBIGUOUS_OUTPUT,
                    0,
                    goal.target(),
                    null,
                    List.of(goal.target()),
                    "Recipe alternatives exceed the bounded candidate count",
                    List.of()));
        }

        LinkedHashSet<ProcessDependencyGraph> unique = new LinkedHashSet<>();
        for (Resolution resolution : expansion.resolutions()) {
            unique.add(resolution.state().toGraph(
                    goal,
                    resolution.producer().map(ProducerRef::stepId)));
        }
        if (unique.size() > PlanningSuccess.MAX_GRAPHS) {
            return failure(failure(
                    PlanningFailureCode.AMBIGUOUS_OUTPUT,
                    0,
                    goal.target(),
                    null,
                    List.of(goal.target()),
                    "Unique recipe alternatives exceed the bounded candidate count",
                    List.of()));
        }
        return new PlanningSuccess(List.copyOf(unique));
    }

    private Expansion resolve(
            ResourceId resourceId,
            GenericResourceType resourceType,
            long requested,
            int parentDepth,
            boolean target,
            List<ResourceId> trace,
            BranchState incoming,
            ProductionGoal goal,
            RecipeCatalog recipes,
            MachineCapabilityCatalog capabilities,
            PlanningContext context) {
        if (requested <= 0 || requested > ProcessResource.MAX_AMOUNT) {
            return Expansion.failed(failure(
                    PlanningFailureCode.INVALID_QUANTITY,
                    parentDepth,
                    resourceId,
                    null,
                    append(trace, resourceId),
                    "A dependency quantity is not positive and bounded",
                    List.of()));
        }
        if (!context.supportedResourceTypes().contains(resourceType)) {
            return Expansion.failed(failure(
                    PlanningFailureCode.RESOURCE_TYPE_UNSUPPORTED,
                    parentDepth,
                    resourceId,
                    null,
                    append(trace, resourceId),
                    "A required dependency resource type is not supported",
                    List.of()));
        }

        BranchState afterOwned = incoming.copy();
        long remaining = requested;
        if (resourceType == GenericResourceType.ITEM) {
            long available = afterOwned.ownedRemaining.getOrDefault(resourceId, 0L);
            long used = Math.min(available, requested);
            if (used > 0) {
                afterOwned.ownedRemaining.put(resourceId, available - used);
                afterOwned.add(afterOwned.ownedUsed, new ResourceKey(resourceId, resourceType), used);
                remaining -= used;
            }
        }
        if (remaining == 0) {
            return Expansion.succeeded(new Resolution(afterOwned, Optional.empty()));
        }

        if (trace.contains(resourceId)) {
            return Expansion.failed(failure(
                    PlanningFailureCode.DEPENDENCY_CYCLE,
                    parentDepth,
                    resourceId,
                    null,
                    append(trace, resourceId),
                    "Recipe dependencies contain a cycle",
                    List.of()));
        }
        List<ResourceId> nextTrace = append(trace, resourceId);
        List<CatalogRecipe> candidates = recipes.recipesProducing(resourceId, resourceType).stream()
                .filter(recipe -> requiredOutputAmount(recipe, resourceId, resourceType).isPresent())
                .toList();
        if (candidates.isEmpty()) {
            if (target) {
                return Expansion.failed(failure(
                        PlanningFailureCode.RECIPE_NOT_FOUND,
                        parentDepth,
                        resourceId,
                        null,
                        nextTrace,
                        "No deterministic catalog recipe produces the requested target",
                        List.of()));
            }
            Optional<PlanningFailure> materialFailure = validateRawMaterial(
                    resourceId, resourceType, remaining, parentDepth, nextTrace, afterOwned, goal);
            if (materialFailure.isPresent()) {
                return Expansion.failed(materialFailure.get());
            }
            BranchState raw = afterOwned.copy();
            raw.add(raw.rawMaterials, new ResourceKey(resourceId, resourceType), remaining);
            return Expansion.succeeded(new Resolution(raw, Optional.empty()));
        }

        int stepDepth = parentDepth + 1;
        int maximumDepth = goal.maximumProcessingDepth()
                .orElse(ProductionGoal.MAX_PROCESSING_DEPTH);
        if (stepDepth > maximumDepth) {
            return Expansion.failed(failure(
                    PlanningFailureCode.DEPTH_LIMIT_EXCEEDED,
                    stepDepth,
                    resourceId,
                    null,
                    nextTrace,
                    "The dependency expansion exceeds the requested maximum processing depth",
                    candidateIds(candidates)));
        }

        List<Resolution> successes = new ArrayList<>();
        List<PlanningFailure> failures = new ArrayList<>();
        for (CatalogRecipe candidate : candidates) {
            Optional<PlanningFailure> incompatible = validateRecipe(
                    candidate,
                    resourceId,
                    stepDepth,
                    nextTrace,
                    goal,
                    capabilities,
                    context,
                    candidates);
            if (incompatible.isPresent()) {
                failures.add(incompatible.get());
                continue;
            }

            long outputPerExecution = requiredOutputAmount(
                    candidate, resourceId, resourceType).orElseThrow();
            long executions = ceilDiv(remaining, outputPerExecution);
            List<InputWork> partials = List.of(new InputWork(afterOwned.copy(), List.of()));
            boolean stopped = false;
            for (ProcessResource input : candidate.inputs()) {
                long scaledInput = Math.multiplyExact(input.amount(), executions);
                List<InputWork> expanded = new ArrayList<>();
                for (InputWork partial : partials) {
                    Expansion inputExpansion = resolve(
                            input.resourceId(),
                            input.resourceType(),
                            scaledInput,
                            stepDepth,
                            false,
                            nextTrace,
                            partial.state(),
                            goal,
                            recipes,
                            capabilities,
                            context);
                    failures.addAll(inputExpansion.failures());
                    for (Resolution resolution : inputExpansion.resolutions()) {
                        List<ProducerRef> producers = new ArrayList<>(partial.inputProducers());
                        resolution.producer().ifPresent(producers::add);
                        expanded.add(new InputWork(resolution.state(), List.copyOf(producers)));
                        if (expanded.size() > PlanningSuccess.MAX_GRAPHS) {
                            stopped = true;
                            break;
                        }
                    }
                    if (stopped) {
                        break;
                    }
                }
                partials = expanded;
                if (partials.isEmpty() || stopped) {
                    break;
                }
            }
            if (stopped) {
                failures.add(failure(
                        PlanningFailureCode.AMBIGUOUS_OUTPUT,
                        stepDepth,
                        resourceId,
                        candidate.recipeId(),
                        nextTrace,
                        "Dependency alternatives exceed the bounded candidate count",
                        candidateIds(candidates)));
                continue;
            }

            for (InputWork partial : partials) {
                BranchState completed = partial.state().copy();
                if (completed.steps.size() >= ProcessDependencyGraph.MAX_STEPS) {
                    failures.add(failure(
                            PlanningFailureCode.DEPTH_LIMIT_EXCEEDED,
                            stepDepth,
                            resourceId,
                            candidate.recipeId(),
                            nextTrace,
                            "Dependency steps exceed the bounded graph size",
                            candidateIds(candidates)));
                    continue;
                }
                ResourceId stepId = stepId(completed.steps.size() + 1);
                ProcessStepDependency step = new ProcessStepDependency(
                        stepId,
                        candidate,
                        executions,
                        stepDepth,
                        scale(candidate.inputs(), executions),
                        scale(candidate.outputs(), executions),
                        scale(candidate.optionalByproducts(), executions));
                completed.steps.add(step);
                for (ProducerRef producer : partial.inputProducers()) {
                    completed.edges.add(new ProcessDependencyEdge(
                            producer.stepId(),
                            stepId,
                            boundedResource(
                                    producer.resourceId(),
                                    producer.resourceType(),
                                    producer.quantity())));
                }
                for (ProcessResource byproduct : step.scaledByproducts()) {
                    completed.add(
                            completed.byproducts,
                            ResourceKey.of(byproduct),
                            byproduct.amount());
                }
                successes.add(new Resolution(
                        completed,
                        Optional.of(new ProducerRef(
                                stepId, resourceId, resourceType, remaining))));
                if (successes.size() > PlanningSuccess.MAX_GRAPHS) {
                    break;
                }
            }
        }
        return new Expansion(List.copyOf(successes), List.copyOf(failures));
    }

    private Optional<PlanningFailure> validateRecipe(
            CatalogRecipe recipe,
            ResourceId requestedResource,
            int depth,
            List<ResourceId> trace,
            ProductionGoal goal,
            MachineCapabilityCatalog capabilities,
            PlanningContext context,
            List<CatalogRecipe> candidates) {
        String modId = recipe.source().sourceModId();
        if (goal.forbiddenModIds().contains(modId)
                || (!goal.allowedModIds().isEmpty() && !goal.allowedModIds().contains(modId))) {
            return Optional.of(failure(
                    PlanningFailureCode.CONSTRAINT_CONFLICT,
                    depth,
                    requestedResource,
                    recipe.recipeId(),
                    trace,
                    "The recipe source mod conflicts with the production-goal constraints",
                    candidateIds(candidates)));
        }
        if (!context.availableModIds().contains(modId)
                || !context.availableAdapterIds().contains(recipe.source().adapterId())) {
            return Optional.of(failure(
                    PlanningFailureCode.REQUIRED_MOD_UNAVAILABLE,
                    depth,
                    requestedResource,
                    recipe.recipeId(),
                    trace,
                    "The recipe source mod or Adapter is not available",
                    candidateIds(candidates)));
        }
        if (!context.supportedResourceTypes().containsAll(recipe.requiredResourceTypes())) {
            return Optional.of(failure(
                    PlanningFailureCode.RESOURCE_TYPE_UNSUPPORTED,
                    depth,
                    requestedResource,
                    recipe.recipeId(),
                    trace,
                    "The recipe requires a resource type outside the planning context",
                    candidateIds(candidates)));
        }
        for (ResourceId capabilityId : recipe.requiredMachineCapabilities()) {
            Optional<MachineCapability> capability = capabilities.find(capabilityId);
            if (capability.isEmpty() || !capability.get().isCompatibleWith(recipe)) {
                return Optional.of(failure(
                        PlanningFailureCode.MACHINE_CAPABILITY_MISSING,
                        depth,
                        requestedResource,
                        recipe.recipeId(),
                        trace,
                        "A required machine capability is absent or incompatible: " + capabilityId,
                        candidateIds(candidates)));
            }
            if (!context.availableAdapterIds().contains(capability.get().adapterId())) {
                return Optional.of(failure(
                        PlanningFailureCode.REQUIRED_MOD_UNAVAILABLE,
                        depth,
                        requestedResource,
                        recipe.recipeId(),
                        trace,
                        "The Adapter declaring a required machine capability is unavailable",
                        candidateIds(candidates)));
            }
        }
        return Optional.empty();
    }

    private Optional<PlanningFailure> validateRawMaterial(
            ResourceId resourceId,
            GenericResourceType resourceType,
            long amount,
            int depth,
            List<ResourceId> trace,
            BranchState state,
            ProductionGoal goal) {
        if (goal.materialConstraints().forbiddenResources().contains(resourceId)) {
            return Optional.of(failure(
                    PlanningFailureCode.CONSTRAINT_CONFLICT,
                    depth,
                    resourceId,
                    null,
                    trace,
                    "A required raw material is forbidden by the production goal",
                    List.of()));
        }
        Optional<Long> maximum = Optional.ofNullable(
                goal.materialConstraints().maximumConsumption().get(resourceId));
        if (maximum.isPresent()) {
            long already = state.rawMaterials.getOrDefault(
                    new ResourceKey(resourceId, resourceType), 0L);
            if (Math.addExact(already, amount) > maximum.get()) {
                return Optional.of(failure(
                        PlanningFailureCode.UNSATISFIABLE_INPUT,
                        depth,
                        resourceId,
                        null,
                        trace,
                        "A required raw material exceeds its maximum consumption constraint",
                        List.of()));
            }
        }
        return Optional.empty();
    }

    private static Optional<Long> requiredOutputAmount(
            CatalogRecipe recipe,
            ResourceId resourceId,
            GenericResourceType resourceType) {
        return recipe.outputs().stream()
                .filter(value -> value.resourceId().equals(resourceId)
                        && value.resourceType() == resourceType)
                .map(ProcessResource::amount)
                .findFirst();
    }

    private static List<ProcessResource> scale(List<ProcessResource> values, long multiplier) {
        List<ProcessResource> scaled = new ArrayList<>(values.size());
        for (ProcessResource value : values) {
            scaled.add(boundedResource(
                    value.resourceId(),
                    value.resourceType(),
                    Math.multiplyExact(value.amount(), multiplier)));
        }
        return List.copyOf(scaled);
    }

    private static long ceilDiv(long dividend, long divisor) {
        return Math.addExact(dividend, divisor - 1) / divisor;
    }

    private static ProcessResource boundedResource(
            ResourceId resourceId,
            GenericResourceType resourceType,
            long amount) {
        if (amount <= 0 || amount > ProcessResource.MAX_AMOUNT) {
            throw new ArithmeticException("Scaled resource quantity exceeds ProcessResource bounds");
        }
        return new ProcessResource(resourceId, resourceType, amount);
    }

    private static ResourceId stepId(int ordinal) {
        return ResourceId.parse("planning:step_" + String.format("%04d", ordinal));
    }

    private static List<ResourceId> append(List<ResourceId> values, ResourceId value) {
        List<ResourceId> copy = new ArrayList<>(values);
        copy.add(value);
        return List.copyOf(copy);
    }

    private static List<ResourceId> candidateIds(List<CatalogRecipe> candidates) {
        return candidates.stream().map(CatalogRecipe::recipeId).distinct().toList();
    }

    private static PlanningFailure firstFailure(Expansion expansion, ResourceId target) {
        return expansion.failures().stream().findFirst().orElseGet(() -> failure(
                PlanningFailureCode.UNSATISFIABLE_INPUT,
                0,
                target,
                null,
                List.of(target),
                "No dependency expansion produced a satisfiable graph",
                List.of()));
    }

    private static PlanningFailureResult failure(PlanningFailure failure) {
        return new PlanningFailureResult(failure);
    }

    private static PlanningFailure failure(
            PlanningFailureCode code,
            int depth,
            ResourceId resourceId,
            ResourceId recipeId,
            List<ResourceId> trace,
            String reason,
            List<ResourceId> alternatives) {
        return new PlanningFailure(
                code,
                new PlanningFailureLocation(
                        depth,
                        Optional.ofNullable(resourceId),
                        Optional.ofNullable(recipeId)),
                trace,
                reason,
                alternatives);
    }

    private record ResourceKey(ResourceId resourceId, GenericResourceType resourceType) {
        private static ResourceKey of(ProcessResource resource) {
            return new ResourceKey(resource.resourceId(), resource.resourceType());
        }
    }

    private record ProducerRef(
            ResourceId stepId,
            ResourceId resourceId,
            GenericResourceType resourceType,
            long quantity) {}

    private record Resolution(BranchState state, Optional<ProducerRef> producer) {}

    private record InputWork(BranchState state, List<ProducerRef> inputProducers) {}

    private record Expansion(
            List<Resolution> resolutions,
            List<PlanningFailure> failures) {
        private static Expansion succeeded(Resolution resolution) {
            return new Expansion(List.of(resolution), List.of());
        }

        private static Expansion failed(PlanningFailure failure) {
            return new Expansion(List.of(), List.of(failure));
        }
    }

    private static final class BranchState {
        private final List<ProcessStepDependency> steps;
        private final List<ProcessDependencyEdge> edges;
        private final Map<ResourceKey, Long> rawMaterials;
        private final Map<ResourceKey, Long> ownedUsed;
        private final Map<ResourceKey, Long> byproducts;
        private final Map<ResourceId, Long> ownedRemaining;

        private BranchState(Map<ResourceId, Long> ownedResources) {
            this(
                    new ArrayList<>(),
                    new ArrayList<>(),
                    new LinkedHashMap<>(),
                    new LinkedHashMap<>(),
                    new LinkedHashMap<>(),
                    new LinkedHashMap<>(ownedResources));
        }

        private BranchState(
                List<ProcessStepDependency> steps,
                List<ProcessDependencyEdge> edges,
                Map<ResourceKey, Long> rawMaterials,
                Map<ResourceKey, Long> ownedUsed,
                Map<ResourceKey, Long> byproducts,
                Map<ResourceId, Long> ownedRemaining) {
            this.steps = steps;
            this.edges = edges;
            this.rawMaterials = rawMaterials;
            this.ownedUsed = ownedUsed;
            this.byproducts = byproducts;
            this.ownedRemaining = ownedRemaining;
        }

        private BranchState copy() {
            return new BranchState(
                    new ArrayList<>(steps),
                    new ArrayList<>(edges),
                    new LinkedHashMap<>(rawMaterials),
                    new LinkedHashMap<>(ownedUsed),
                    new LinkedHashMap<>(byproducts),
                    new LinkedHashMap<>(ownedRemaining));
        }

        private void add(Map<ResourceKey, Long> target, ResourceKey key, long amount) {
            target.merge(key, amount, Math::addExact);
        }

        private ProcessDependencyGraph toGraph(
                ProductionGoal goal,
                Optional<ResourceId> targetProducer) {
            return new ProcessDependencyGraph(
                    goal,
                    List.copyOf(steps),
                    List.copyOf(edges),
                    resources(rawMaterials),
                    resources(ownedUsed),
                    resources(byproducts),
                    targetProducer);
        }

        private static List<ProcessResource> resources(Map<ResourceKey, Long> values) {
            return values.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(RESOURCE_ORDER))
                    .map(entry -> boundedResource(
                            entry.getKey().resourceId(),
                            entry.getKey().resourceType(),
                            entry.getValue()))
                    .toList();
        }
    }
}
