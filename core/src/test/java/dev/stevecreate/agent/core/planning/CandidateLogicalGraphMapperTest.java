package dev.stevecreate.agent.core.planning;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.core.verification.VerificationEvidenceKind;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CandidateLogicalGraphMapperTest {
    private final CandidateLogicalGraphMapper mapper = new CandidateLogicalGraphMapper();

    @Test
    void mapsRawProcessTargetAndExplicitPowerWithoutPhysicalBinding() {
        CandidatePlan candidate = generate(singleStepGraph());
        MachineCapability milling = capability(
                "industrial:milling", "fixture:milling", "fixture:adapter", 8);

        LogicalMachineGraph first = success(mapper.map(
                candidate, catalog(milling), context(Set.of("fixture:adapter"), supportedTypes())));
        LogicalMachineGraph repeated = success(mapper.map(
                candidate, catalog(milling), context(Set.of("fixture:adapter"), supportedTypes())));

        assertThat(first.id()).isEqualTo(id("planning:logical_" + candidate.candidateId().path()));
        assertThat(first.nodes().values()).extracting(LogicalGraphNode::kind)
                .containsExactly(
                        LogicalNodeKind.PROCESS,
                        LogicalNodeKind.RAW_RESOURCE_SOURCE,
                        LogicalNodeKind.TARGET_SINK);
        LogicalGraphNode process = first.node(candidate.unboundMachineNodes().get(0).nodeId());
        assertThat(process.stepId()).contains(id("planning:step_0001"));
        assertThat(process.requiredCapabilities()).containsExactly(id("industrial:milling"));
        assertThat(process.requiredResources()).containsExactly(
                new CapabilityResourceRequirement(GenericResourceType.ROTATIONAL_POWER, 8, true));
        assertThat(first.edges().values()).extracting(LogicalResourceEdge::kind)
                .containsExactly(LogicalEdgeKind.RAW_INPUT, LogicalEdgeKind.TARGET_OUTPUT);
        assertThat(first.edges().values()).extracting(LogicalResourceEdge::resourceId)
                .containsExactly(id("fixture:ore"), id("fixture:dust"));
        assertThat(first.nodes()).isEqualTo(repeated.nodes());
        assertThat(first.ports()).isEqualTo(repeated.ports());
        assertThat(first.edges()).isEqualTo(repeated.edges());
    }

    @Test
    void mapsRawOwnedIntermediateAndTargetFlowsAcrossTwoSteps() {
        CandidatePlan candidate = generate(multiStepGraph());
        MachineCapability crushing = capability(
                "industrial:crushing", "fixture:crushing", "fixture:adapter", 4);
        MachineCapability smelting = capability(
                "industrial:smelting", "fixture:smelting", "fixture:adapter", 12);

        LogicalMachineGraph graph = success(mapper.map(
                candidate,
                catalog(crushing, smelting),
                context(Set.of("fixture:adapter"), supportedTypes())));

        assertThat(graph.nodes().values()).extracting(LogicalGraphNode::kind)
                .containsExactly(
                        LogicalNodeKind.PROCESS,
                        LogicalNodeKind.PROCESS,
                        LogicalNodeKind.RAW_RESOURCE_SOURCE,
                        LogicalNodeKind.OWNED_RESOURCE_SOURCE,
                        LogicalNodeKind.TARGET_SINK);
        assertThat(graph.edges().values()).extracting(LogicalResourceEdge::kind)
                .containsExactly(
                        LogicalEdgeKind.INTERMEDIATE,
                        LogicalEdgeKind.RAW_INPUT,
                        LogicalEdgeKind.OWNED_INPUT,
                        LogicalEdgeKind.TARGET_OUTPUT);
        assertThat(graph.edges().values())
                .anySatisfy(edge -> {
                    assertThat(edge.kind()).isEqualTo(LogicalEdgeKind.INTERMEDIATE);
                    assertThat(edge.resourceId()).isEqualTo(id("fixture:dust"));
                    assertThat(edge.amount()).isEqualTo(2);
                });
    }

    @Test
    void mapsFullyOwnedTargetWithoutInventingAProcessOrImplementation() {
        CandidatePlan candidate = generate(new ProcessDependencyGraph(
                goal("fixture:alloy", 2), List.of(), List.of(), List.of(),
                List.of(item("fixture:alloy", 2)), List.of(), Optional.empty()));

        LogicalMachineGraph graph = success(mapper.map(
                candidate,
                catalog(capability(
                        "industrial:unused", "fixture:unused", "fixture:adapter", 1)),
                context(Set.of("fixture:adapter"), supportedTypes())));

        assertThat(graph.nodes().values()).extracting(LogicalGraphNode::kind)
                .containsExactly(
                        LogicalNodeKind.OWNED_RESOURCE_SOURCE,
                        LogicalNodeKind.TARGET_SINK);
        assertThat(graph.edges().values()).singleElement().satisfies(edge -> {
            assertThat(edge.kind()).isEqualTo(LogicalEdgeKind.TARGET_OUTPUT);
            assertThat(edge.resourceId()).isEqualTo(id("fixture:alloy"));
            assertThat(edge.amount()).isEqualTo(2);
        });
    }

    @Test
    void returnsTypedCapabilityAdapterAndResourceFailures() {
        CandidatePlan candidate = generate(singleStepGraph());
        PlanningContext ready = context(Set.of("fixture:adapter"), supportedTypes());

        assertFailure(
                mapper.map(
                        candidate,
                        catalog(capability(
                                "industrial:other", "fixture:milling", "fixture:adapter", 1)),
                        ready),
                LogicalGraphMappingFailureCode.MACHINE_CAPABILITY_MISSING);
        assertFailure(
                mapper.map(
                        candidate,
                        catalog(capability(
                                "industrial:milling", "fixture:other", "fixture:adapter", 1)),
                        ready),
                LogicalGraphMappingFailureCode.MACHINE_CAPABILITY_INCOMPATIBLE);
        assertFailure(
                mapper.map(
                        candidate,
                        catalog(capability(
                                "industrial:milling", "fixture:milling", "fixture:adapter", 1)),
                        context(Set.of(), supportedTypes())),
                LogicalGraphMappingFailureCode.CAPABILITY_ADAPTER_UNAVAILABLE);
        LogicalGraphMappingFailure unsupported = assertFailure(
                mapper.map(
                        candidate,
                        catalog(capability(
                                "industrial:milling", "fixture:milling", "fixture:adapter", 1)),
                        context(Set.of("fixture:adapter"), Set.of(GenericResourceType.ITEM))),
                LogicalGraphMappingFailureCode.RESOURCE_TYPE_UNSUPPORTED);
        assertThat(unsupported.candidateId()).isEqualTo(candidate.candidateId());
        assertThat(unsupported.stepId()).contains(id("planning:step_0001"));
        assertThat(unsupported.trace()).contains(candidate.candidateId(), id("planning:step_0001"));
    }

    @Test
    void rejectsCandidateInconsistencyAndUnresolvedInputAsTypedFailures() {
        CandidatePlan candidate = generate(singleStepGraph());
        CandidateQuantityConversion original = candidate.quantityConversions().get(0);
        CandidateQuantityConversion inconsistentConversion = new CandidateQuantityConversion(
                original.stepId(), id("fixture:not_the_selected_recipe"), original.executions(),
                original.inputs(), original.outputs(), original.byproducts());
        CandidatePlan inconsistent = copyCandidate(
                candidate,
                candidate.goal(),
                candidate.rawMaterials(),
                List.of(inconsistentConversion));
        CandidatePlan unresolved = copyCandidate(
                candidate,
                candidate.goal(),
                List.of(),
                candidate.quantityConversions());
        MachineCapability capability = capability(
                "industrial:milling", "fixture:milling", "fixture:adapter", 1);
        MachineCapabilityCatalog catalog = catalog(capability);
        PlanningContext context = context(Set.of("fixture:adapter"), supportedTypes());

        assertFailure(
                mapper.map(inconsistent, catalog, context),
                LogicalGraphMappingFailureCode.CANDIDATE_INCONSISTENT);
        assertFailure(
                mapper.map(unresolved, catalog, context),
                LogicalGraphMappingFailureCode.INPUT_ALLOCATION_UNRESOLVED);
    }

    @Test
    void returnsTypedMissingAndAmbiguousTargetFailures() {
        CandidatePlan candidate = generate(singleStepGraph());
        CandidatePlan missing = copyCandidate(
                candidate,
                goal("fixture:missing", 2),
                candidate.rawMaterials(),
                candidate.quantityConversions());
        MachineCapability milling = capability(
                "industrial:milling", "fixture:milling", "fixture:adapter", 1);

        assertFailure(
                mapper.map(
                        missing, catalog(milling),
                        context(Set.of("fixture:adapter"), supportedTypes())),
                LogicalGraphMappingFailureCode.TARGET_OUTPUT_MISSING);

        CandidatePlan ambiguous = generate(ambiguousTargetGraph());
        MachineCapability crushing = capability(
                "industrial:crushing", "fixture:crushing", "fixture:adapter", 1);
        assertFailure(
                mapper.map(
                        ambiguous, catalog(milling, crushing),
                        context(Set.of("fixture:adapter"), supportedTypes())),
                LogicalGraphMappingFailureCode.TARGET_OUTPUT_AMBIGUOUS);
    }

    @Test
    void returnsTypedQuantityOverflowWhenCapabilityRequirementsCannotBeAggregated() {
        CatalogRecipe recipe = recipe(
                "fixture:combined", "fixture:combined",
                Set.of(id("industrial:first"), id("industrial:second")),
                List.of(item("fixture:ore", 1)), List.of(item("fixture:dust", 1)));
        ProcessStepDependency step = step(
                "planning:step_0001", recipe,
                List.of(item("fixture:ore", 1)), List.of(item("fixture:dust", 1)));
        CandidatePlan candidate = generate(new ProcessDependencyGraph(
                goal("fixture:dust", 1), List.of(step), List.of(),
                List.of(item("fixture:ore", 1)), List.of(), List.of(),
                Optional.of(id("planning:step_0001"))));
        long requirement = 600_000_000_000L;

        assertFailure(
                mapper.map(
                        candidate,
                        catalog(
                                capability(
                                        "industrial:first", "fixture:combined",
                                        "fixture:adapter", requirement),
                                capability(
                                        "industrial:second", "fixture:combined",
                                        "fixture:adapter", requirement)),
                        context(Set.of("fixture:adapter"), supportedTypes())),
                LogicalGraphMappingFailureCode.QUANTITY_OVERFLOW);
    }

    private static ProcessDependencyGraph singleStepGraph() {
        CatalogRecipe recipe = recipe(
                "fixture:milling", "fixture:milling", Set.of(id("industrial:milling")),
                List.of(item("fixture:ore", 1)), List.of(item("fixture:dust", 1)));
        ProcessStepDependency step = step(
                "planning:step_0001", recipe,
                List.of(item("fixture:ore", 2)), List.of(item("fixture:dust", 2)));
        return new ProcessDependencyGraph(
                goal("fixture:dust", 2), List.of(step), List.of(),
                List.of(item("fixture:ore", 2)), List.of(), List.of(),
                Optional.of(id("planning:step_0001")));
    }

    private static ProcessDependencyGraph multiStepGraph() {
        CatalogRecipe crushing = recipe(
                "fixture:crushing", "fixture:crushing", Set.of(id("industrial:crushing")),
                List.of(item("fixture:ore", 1)), List.of(item("fixture:dust", 2)));
        CatalogRecipe smelting = recipe(
                "fixture:smelting", "fixture:smelting", Set.of(id("industrial:smelting")),
                List.of(item("fixture:coal", 1), item("fixture:dust", 1)),
                List.of(item("fixture:alloy", 1)));
        ProcessStepDependency first = step(
                "planning:step_0001", crushing,
                List.of(item("fixture:ore", 1)), List.of(item("fixture:dust", 2)));
        ProcessStepDependency second = new ProcessStepDependency(
                id("planning:step_0002"), smelting, 2, 1,
                List.of(item("fixture:coal", 2), item("fixture:dust", 2)),
                List.of(item("fixture:alloy", 2)), List.of());
        ProcessDependencyEdge dependency = new ProcessDependencyEdge(
                first.stepId(), second.stepId(), item("fixture:dust", 2));
        return new ProcessDependencyGraph(
                goal("fixture:alloy", 2), List.of(first, second), List.of(dependency),
                List.of(item("fixture:ore", 1)), List.of(item("fixture:coal", 2)),
                List.of(), Optional.of(second.stepId()));
    }

    private static ProcessDependencyGraph ambiguousTargetGraph() {
        CatalogRecipe milling = recipe(
                "fixture:milling", "fixture:milling", Set.of(id("industrial:milling")),
                List.of(item("fixture:ore", 1)), List.of(item("fixture:dust", 1)));
        CatalogRecipe crushing = recipe(
                "fixture:crushing", "fixture:crushing", Set.of(id("industrial:crushing")),
                List.of(item("fixture:stone", 1)), List.of(item("fixture:dust", 1)));
        ProcessStepDependency first = step(
                "planning:step_0001", milling,
                List.of(item("fixture:ore", 2)), List.of(item("fixture:dust", 2)));
        ProcessStepDependency second = step(
                "planning:step_0002", crushing,
                List.of(item("fixture:stone", 2)), List.of(item("fixture:dust", 2)));
        return new ProcessDependencyGraph(
                goal("fixture:dust", 2), List.of(first, second), List.of(),
                List.of(item("fixture:ore", 2), item("fixture:stone", 2)),
                List.of(), List.of(), Optional.of(second.stepId()));
    }

    private static CandidatePlan generate(ProcessDependencyGraph graph) {
        return new CandidatePlanGenerator().generate(new PlanningSuccess(List.of(graph))).get(0);
    }

    private static CandidatePlan copyCandidate(
            CandidatePlan source,
            ProductionGoal goal,
            List<ProcessResource> rawMaterials,
            List<CandidateQuantityConversion> conversions) {
        return new CandidatePlan(
                source.candidateId(), goal, source.selectedRecipes(),
                source.requiredMachineCapabilities(), rawMaterials,
                source.intermediateResources(), source.ownedResourcesUsed(),
                source.processingOrder(), conversions, source.dependencies(),
                source.unboundMachineNodes(), source.unboundSpatialLayout(),
                source.planningEvidence(), source.estimatedProcessingTicks());
    }

    private static ProcessStepDependency step(
            String stepId,
            CatalogRecipe recipe,
            List<ProcessResource> inputs,
            List<ProcessResource> outputs) {
        return new ProcessStepDependency(
                id(stepId), recipe, inputs.get(0).amount(), 1,
                inputs, outputs, List.of());
    }

    private static CatalogRecipe recipe(
            String recipeId,
            String recipeType,
            Set<ResourceId> capabilities,
            List<ProcessResource> inputs,
            List<ProcessResource> outputs) {
        return new CatalogRecipe(
                id(recipeId), id(recipeType), inputs, outputs, List.of(), capabilities,
                Set.of(GenericResourceType.ITEM, GenericResourceType.ROTATIONAL_POWER),
                OptionalLong.of(20),
                new RecipeSource(id("fixture:adapter"), "fixture", "fixture=1", true));
    }

    private static MachineCapability capability(
            String capabilityId,
            String recipeType,
            String adapterId,
            long rotationalPower) {
        return new MachineCapability(
                id(capabilityId), id(adapterId), Set.of(id(recipeType)),
                Set.of(GenericResourceType.ITEM), Set.of(GenericResourceType.ITEM),
                Set.of(new CapabilityResourceRequirement(
                        GenericResourceType.ROTATIONAL_POWER, rotationalPower, true)),
                Set.of(id("fixture:process")),
                Set.of(VerificationEvidenceKind.OUTPUT_PRODUCED),
                Set.of(id("fixture:failure")),
                new CapabilityVersionLimits(Optional.of("1"), Optional.empty(), Set.of()));
    }

    private static MachineCapabilityCatalog catalog(MachineCapability... capabilities) {
        return new ImmutableMachineCapabilityCatalog(List.of(capabilities));
    }

    private static PlanningContext context(
            Set<String> adapterIds,
            Set<GenericResourceType> types) {
        return new PlanningContext(
                adapterIds.stream().map(CandidateLogicalGraphMapperTest::id).collect(
                        java.util.stream.Collectors.toSet()),
                Set.of("fixture"),
                types);
    }

    private static Set<GenericResourceType> supportedTypes() {
        return Set.of(GenericResourceType.ITEM, GenericResourceType.ROTATIONAL_POWER);
    }

    private static ProductionGoal goal(String target, long amount) {
        return new ProductionGoal(
                id(target), GenericResourceType.ITEM, amount,
                Set.of(), Set.of(), Optional.empty(), MaterialConstraints.none(), List.of(), Map.of());
    }

    private static ProcessResource item(String value, long amount) {
        return new ProcessResource(id(value), GenericResourceType.ITEM, amount);
    }

    private static LogicalMachineGraph success(LogicalGraphMappingResult result) {
        assertThat(result).isInstanceOf(LogicalGraphMappingSuccess.class);
        return ((LogicalGraphMappingSuccess) result).graph();
    }

    private static LogicalGraphMappingFailure assertFailure(
            LogicalGraphMappingResult result,
            LogicalGraphMappingFailureCode expectedCode) {
        assertThat(result).isInstanceOf(LogicalGraphMappingFailureResult.class);
        LogicalGraphMappingFailure failure = ((LogicalGraphMappingFailureResult) result).failure();
        assertThat(failure.code()).isEqualTo(expectedCode);
        assertThat(failure.detail()).isNotBlank();
        return failure;
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
