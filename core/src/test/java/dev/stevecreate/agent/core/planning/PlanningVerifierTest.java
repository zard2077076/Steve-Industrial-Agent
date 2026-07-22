package dev.stevecreate.agent.core.planning;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.graph.PortMode;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.core.verification.VerificationEvidenceKind;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlanningVerifierTest {
    private final PlanningVerifier verifier = new PlanningVerifier();

    @Test
    void verifiesMillingPressingAndFullyOwnedTargetsThroughTheCompletePipeline() {
        Pipeline milling = pipeline(
                goal("minecraft:gravel", 2, Map.of()),
                List.of(recipe(
                        "fixture:milling", "fixture:milling", "industrial:milling",
                        List.of(item("minecraft:cobblestone", 1)),
                        List.of(item("minecraft:gravel", 1)))),
                List.of(capability("industrial:milling", "fixture:milling")),
                readyContext());
        Pipeline pressing = pipeline(
                goal("create:iron_sheet", 1, Map.of()),
                List.of(recipe(
                        "fixture:pressing", "fixture:pressing", "industrial:pressing",
                        List.of(item("minecraft:iron_ingot", 1)),
                        List.of(item("create:iron_sheet", 1)))),
                List.of(capability("industrial:pressing", "fixture:pressing")),
                readyContext());
        Pipeline owned = pipeline(
                goal("fixture:alloy", 2, Map.of(id("fixture:alloy"), 2L)),
                List.of(recipe(
                        "fixture:unused", "fixture:unused", "industrial:unused",
                        List.of(item("fixture:ore", 1)),
                        List.of(item("fixture:alloy", 1)))),
                List.of(capability("industrial:unused", "fixture:unused")),
                readyContext());

        VerifiedLogicalPlan millingPlan = success(verifier.verify(
                milling.candidate(), milling.graph(), milling.capabilities(), milling.context()));
        VerifiedLogicalPlan pressingPlan = success(verifier.verify(
                pressing.candidate(), pressing.graph(), pressing.capabilities(), pressing.context()));
        VerifiedLogicalPlan ownedPlan = success(verifier.verify(
                owned.candidate(), owned.graph(), owned.capabilities(), owned.context()));

        assertThat(millingPlan.candidate().goal().target()).isEqualTo(id("minecraft:gravel"));
        assertThat(pressingPlan.candidate().goal().target()).isEqualTo(id("create:iron_sheet"));
        assertThat(ownedPlan.logicalGraph().nodes().values())
                .extracting(LogicalGraphNode::kind)
                .containsExactly(
                        LogicalNodeKind.OWNED_RESOURCE_SOURCE,
                        LogicalNodeKind.TARGET_SINK);
        assertThat(millingPlan.evidence()).containsOnlyKeys(PlanningVerificationCheck.values());
    }

    @Test
    void verifiesTwoStepRawOwnedIntermediateFlowAndIsDeterministic() {
        Pipeline pipeline = multiStepPipeline();

        VerifiedLogicalPlan first = success(verifier.verify(
                pipeline.candidate(), pipeline.graph(),
                pipeline.capabilities(), pipeline.context()));
        VerifiedLogicalPlan repeated = success(verifier.verify(
                pipeline.candidate(), pipeline.graph(),
                pipeline.capabilities(), pipeline.context()));

        assertThat(first.evidence()).isEqualTo(repeated.evidence());
        assertThat(first.id()).isEqualTo(repeated.id());
        assertThat(first.evidence().values())
                .extracting(PlanningVerificationEvidence::check)
                .containsExactly(PlanningVerificationCheck.values());
        assertThat(first.logicalGraph().edges().values())
                .extracting(LogicalResourceEdge::kind)
                .containsExactly(
                        LogicalEdgeKind.INTERMEDIATE,
                        LogicalEdgeKind.RAW_INPUT,
                        LogicalEdgeKind.OWNED_INPUT,
                        LogicalEdgeKind.TARGET_OUTPUT);
    }

    @Test
    void verifiedPlanIsReadOnlyNonExecutableAndNotPubliclyConstructible() {
        Pipeline pipeline = multiStepPipeline();
        VerifiedLogicalPlan plan = success(verifier.verify(
                pipeline.candidate(), pipeline.graph(),
                pipeline.capabilities(), pipeline.context()));

        assertThat(VerifiedLogicalPlan.class.getConstructors()).isEmpty();
        assertThat(Arrays.stream(VerifiedLogicalPlan.class.getDeclaredFields())
                .map(field -> field.getType().getName()))
                .noneMatch(name -> name.contains("execution.GenericExecutionPlan")
                        || name.contains("graph.UnifiedMachineGraph")
                        || name.startsWith("net.minecraft")
                        || name.startsWith("net.minecraftforge")
                        || name.startsWith("com.simibubi.create"));
        assertThat(Arrays.stream(PlanningVerifier.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(method -> method.getName()))
                .containsExactly("verify");
        assertThat(plan.evidence()).isUnmodifiable();
    }

    @Test
    void rejectsUntraceableDependenciesAndMissingProductionSources() {
        Pipeline multi = multiStepPipeline();
        LogicalMachineGraph withoutDependency = withoutFirstEdgeKind(
                multi.graph(), LogicalEdgeKind.INTERMEDIATE);
        assertFailure(
                verifier.verify(
                        multi.candidate(), withoutDependency,
                        multi.capabilities(), multi.context()),
                PlanningVerificationFailureCode.DEPENDENCY_UNTRACEABLE);

        Pipeline single = singleStepPipeline();
        LogicalMachineGraph withoutRaw = withoutFirstEdgeKind(
                single.graph(), LogicalEdgeKind.RAW_INPUT);
        assertFailure(
                verifier.verify(
                        single.candidate(), withoutRaw,
                        single.capabilities(), single.context()),
                PlanningVerificationFailureCode.PRODUCTION_SOURCE_MISSING);
    }

    @Test
    void rejectsQuantityAndTargetMutations() {
        Pipeline single = singleStepPipeline();
        LogicalMachineGraph rawQuantityChanged = replaceEdgeAmount(
                single.graph(), LogicalEdgeKind.RAW_INPUT, 1);
        assertFailure(
                verifier.verify(
                        single.candidate(), rawQuantityChanged,
                        single.capabilities(), single.context()),
                PlanningVerificationFailureCode.QUANTITY_INCONSISTENT);

        LogicalMachineGraph targetQuantityChanged = replaceEdgeAmount(
                single.graph(), LogicalEdgeKind.TARGET_OUTPUT, 1);
        assertFailure(
                verifier.verify(
                        single.candidate(), targetQuantityChanged,
                        single.capabilities(), single.context()),
                PlanningVerificationFailureCode.TARGET_UNSATISFIED);
    }

    @Test
    void rejectsCapabilityAndEdgeResourceMutations() {
        Pipeline single = singleStepPipeline();
        LogicalMachineGraph capabilityChanged = replaceProcessCapability(
                single.graph(), id("industrial:invented"));
        assertFailure(
                verifier.verify(
                        single.candidate(), capabilityChanged,
                        single.capabilities(), single.context()),
                PlanningVerificationFailureCode.MACHINE_CAPABILITY_UNDECLARED);

        LogicalMachineGraph fluidRawEdge = replaceRawEdgeType(
                single.graph(), GenericResourceType.FLUID);
        PlanningContext fluidContext = new PlanningContext(
                single.context().availableAdapterIds(),
                single.context().availableModIds(),
                Set.of(
                        GenericResourceType.ITEM,
                        GenericResourceType.FLUID,
                        GenericResourceType.ROTATIONAL_POWER));
        assertFailure(
                verifier.verify(
                        single.candidate(), fluidRawEdge,
                        single.capabilities(), fluidContext),
                PlanningVerificationFailureCode.EDGE_RESOURCE_MISMATCH);
    }

    @Test
    void rejectsAProcessCycleBeforeAcceptingExtraDependencyStructure() {
        Pipeline pipeline = multiStepPipeline();
        LogicalMachineGraph cyclic = addReverseCycleEdge(pipeline.graph());

        PlanningVerificationFailure failure = assertFailure(
                verifier.verify(
                        pipeline.candidate(), cyclic,
                        pipeline.capabilities(), pipeline.context()),
                PlanningVerificationFailureCode.DEPENDENCY_CYCLE);

        assertThat(failure.trace()).contains(pipeline.candidate().candidateId());
        assertThat(failure.check()).isEqualTo(PlanningVerificationCheck.ACYCLIC);
    }

    @Test
    void translatesUnavailableAdapterAndUnsupportedResourceToTypedFailures() {
        Pipeline single = singleStepPipeline();
        PlanningContext noAdapter = new PlanningContext(
                Set.of(), Set.of("fixture"), supportedTypes());
        PlanningContext itemOnly = new PlanningContext(
                single.context().availableAdapterIds(), Set.of("fixture"),
                Set.of(GenericResourceType.ITEM));

        assertFailure(
                verifier.verify(
                        single.candidate(), single.graph(),
                        single.capabilities(), noAdapter),
                PlanningVerificationFailureCode.ADAPTER_UNAVAILABLE);
        assertFailure(
                verifier.verify(
                        single.candidate(), single.graph(),
                        single.capabilities(), itemOnly),
                PlanningVerificationFailureCode.RESOURCE_TYPE_UNSUPPORTED);
    }

    @Test
    void rejectsGraphIdentityMismatchWithoutReturningATypedPlan() {
        Pipeline single = singleStepPipeline();
        LogicalMachineGraph wrongIdentity = rebuild(
                single.graph(), id("planning:not_the_candidate_graph"),
                new ArrayList<>(single.graph().nodes().values()),
                new ArrayList<>(single.graph().ports().values()),
                new ArrayList<>(single.graph().edges().values()));

        PlanningVerificationResult result = verifier.verify(
                single.candidate(), wrongIdentity,
                single.capabilities(), single.context());
        PlanningVerificationFailure failure = assertFailure(
                result, PlanningVerificationFailureCode.CANDIDATE_GRAPH_MISMATCH);

        assertThat(result).isNotInstanceOf(PlanningVerificationSuccess.class);
        assertThat(failure.graphId()).isEqualTo(wrongIdentity.id());
    }

    private static Pipeline singleStepPipeline() {
        return pipeline(
                goal("fixture:dust", 2, Map.of()),
                List.of(recipe(
                        "fixture:milling", "fixture:milling", "industrial:milling",
                        List.of(item("fixture:ore", 1)),
                        List.of(item("fixture:dust", 1)))),
                List.of(capability("industrial:milling", "fixture:milling")),
                readyContext());
    }

    private static Pipeline multiStepPipeline() {
        return pipeline(
                goal("fixture:alloy", 2, Map.of(id("fixture:coal"), 2L)),
                List.of(
                        recipe(
                                "fixture:crushing", "fixture:crushing", "industrial:crushing",
                                List.of(item("fixture:ore", 1)),
                                List.of(item("fixture:dust", 2))),
                        recipe(
                                "fixture:smelting", "fixture:smelting", "industrial:smelting",
                                List.of(item("fixture:coal", 1), item("fixture:dust", 1)),
                                List.of(item("fixture:alloy", 1)))),
                List.of(
                        capability("industrial:crushing", "fixture:crushing"),
                        capability("industrial:smelting", "fixture:smelting")),
                readyContext());
    }

    private static Pipeline pipeline(
            ProductionGoal goal,
            List<CatalogRecipe> recipes,
            List<MachineCapability> capabilities,
            PlanningContext context) {
        RecipeCatalog recipeCatalog = new ImmutableRecipeCatalog(recipes);
        MachineCapabilityCatalog capabilityCatalog = new ImmutableMachineCapabilityCatalog(
                capabilities);
        PlanningResult planning = new ProcessDependencyGraphBuilder().plan(
                goal, recipeCatalog, capabilityCatalog, context);
        assertThat(planning).isInstanceOf(PlanningSuccess.class);
        CandidatePlan candidate = new CandidatePlanGenerator()
                .generate((PlanningSuccess) planning)
                .get(0);
        LogicalGraphMappingResult mapped = new CandidateLogicalGraphMapper().map(
                candidate, capabilityCatalog, context);
        assertThat(mapped).isInstanceOf(LogicalGraphMappingSuccess.class);
        return new Pipeline(
                candidate,
                ((LogicalGraphMappingSuccess) mapped).graph(),
                capabilityCatalog,
                context);
    }

    private static LogicalMachineGraph withoutFirstEdgeKind(
            LogicalMachineGraph graph,
            LogicalEdgeKind kind) {
        List<LogicalResourceEdge> edges = new ArrayList<>(graph.edges().values());
        int index = firstEdgeIndex(edges, kind);
        edges.remove(index);
        return rebuild(
                graph, graph.id(), new ArrayList<>(graph.nodes().values()),
                new ArrayList<>(graph.ports().values()), edges);
    }

    private static LogicalMachineGraph replaceEdgeAmount(
            LogicalMachineGraph graph,
            LogicalEdgeKind kind,
            long amount) {
        List<LogicalResourceEdge> edges = new ArrayList<>(graph.edges().values());
        int index = firstEdgeIndex(edges, kind);
        LogicalResourceEdge original = edges.get(index);
        edges.set(index, new LogicalResourceEdge(
                original.id(), original.sourcePortId(), original.targetPortId(),
                original.resourceId(), original.resourceType(), amount, original.kind()));
        return rebuild(
                graph, graph.id(), new ArrayList<>(graph.nodes().values()),
                new ArrayList<>(graph.ports().values()), edges);
    }

    private static LogicalMachineGraph replaceProcessCapability(
            LogicalMachineGraph graph,
            ResourceId capabilityId) {
        List<LogicalGraphNode> nodes = new ArrayList<>(graph.nodes().values());
        for (int index = 0; index < nodes.size(); index++) {
            LogicalGraphNode node = nodes.get(index);
            if (node.kind() == LogicalNodeKind.PROCESS) {
                nodes.set(index, new LogicalGraphNode(
                        node.id(), node.kind(), node.stepId(),
                        Set.of(capabilityId), node.requiredResources()));
                break;
            }
        }
        return rebuild(
                graph, graph.id(), nodes,
                new ArrayList<>(graph.ports().values()),
                new ArrayList<>(graph.edges().values()));
    }

    private static LogicalMachineGraph replaceRawEdgeType(
            LogicalMachineGraph graph,
            GenericResourceType resourceType) {
        List<LogicalPortRequirement> ports = new ArrayList<>(graph.ports().values());
        List<LogicalResourceEdge> edges = new ArrayList<>(graph.edges().values());
        int edgeIndex = firstEdgeIndex(edges, LogicalEdgeKind.RAW_INPUT);
        LogicalResourceEdge edge = edges.get(edgeIndex);
        replacePortType(ports, edge.sourcePortId(), resourceType);
        replacePortType(ports, edge.targetPortId(), resourceType);
        edges.set(edgeIndex, new LogicalResourceEdge(
                edge.id(), edge.sourcePortId(), edge.targetPortId(), edge.resourceId(),
                resourceType, edge.amount(), edge.kind()));
        return rebuild(
                graph, graph.id(), new ArrayList<>(graph.nodes().values()), ports, edges);
    }

    private static void replacePortType(
            List<LogicalPortRequirement> ports,
            ResourceId portId,
            GenericResourceType resourceType) {
        for (int index = 0; index < ports.size(); index++) {
            LogicalPortRequirement port = ports.get(index);
            if (port.id().equals(portId)) {
                ports.set(index, new LogicalPortRequirement(
                        port.id(), port.nodeId(), port.resourceId(), resourceType,
                        port.mode(), port.role(), port.amount()));
                return;
            }
        }
        throw new IllegalArgumentException("Unknown test port " + portId);
    }

    private static LogicalMachineGraph addReverseCycleEdge(LogicalMachineGraph graph) {
        List<LogicalGraphNode> processNodes = graph.nodes().values().stream()
                .filter(node -> node.kind() == LogicalNodeKind.PROCESS)
                .toList();
        ResourceId resource = id("fixture:cycle_token");
        List<LogicalPortRequirement> ports = new ArrayList<>(graph.ports().values());
        LogicalPortRequirement secondOutput = new LogicalPortRequirement(
                id("planning:cycle_output"), processNodes.get(1).id(), resource,
                GenericResourceType.ITEM, PortMode.OUTPUT,
                LogicalPortRole.PROCESS_OUTPUT, 1);
        LogicalPortRequirement firstInput = new LogicalPortRequirement(
                id("planning:cycle_input"), processNodes.get(0).id(), resource,
                GenericResourceType.ITEM, PortMode.INPUT,
                LogicalPortRole.PROCESS_INPUT, 1);
        ports.add(secondOutput);
        ports.add(firstInput);
        List<LogicalResourceEdge> edges = new ArrayList<>(graph.edges().values());
        edges.add(new LogicalResourceEdge(
                id("planning:cycle_edge"), secondOutput.id(), firstInput.id(), resource,
                GenericResourceType.ITEM, 1, LogicalEdgeKind.INTERMEDIATE));
        return rebuild(
                graph, graph.id(), new ArrayList<>(graph.nodes().values()), ports, edges);
    }

    private static int firstEdgeIndex(
            List<LogicalResourceEdge> edges,
            LogicalEdgeKind kind) {
        for (int index = 0; index < edges.size(); index++) {
            if (edges.get(index).kind() == kind) {
                return index;
            }
        }
        throw new IllegalArgumentException("Missing test edge " + kind);
    }

    private static LogicalMachineGraph rebuild(
            LogicalMachineGraph original,
            ResourceId graphId,
            List<LogicalGraphNode> nodes,
            List<LogicalPortRequirement> ports,
            List<LogicalResourceEdge> edges) {
        assertThat(original).isNotNull();
        return new LogicalMachineGraph(graphId, nodes, ports, edges);
    }

    private static CatalogRecipe recipe(
            String recipeId,
            String recipeType,
            String capability,
            List<ProcessResource> inputs,
            List<ProcessResource> outputs) {
        return new CatalogRecipe(
                id(recipeId), id(recipeType), inputs, outputs, List.of(),
                Set.of(id(capability)),
                Set.of(GenericResourceType.ITEM, GenericResourceType.ROTATIONAL_POWER),
                OptionalLong.of(20),
                new RecipeSource(id("fixture:adapter"), "fixture", "fixture=1", true));
    }

    private static MachineCapability capability(String capabilityId, String recipeType) {
        return new MachineCapability(
                id(capabilityId), id("fixture:adapter"), Set.of(id(recipeType)),
                Set.of(GenericResourceType.ITEM), Set.of(GenericResourceType.ITEM),
                Set.of(new CapabilityResourceRequirement(
                        GenericResourceType.ROTATIONAL_POWER, 8, true)),
                Set.of(id("fixture:process")),
                Set.of(VerificationEvidenceKind.OUTPUT_PRODUCED),
                Set.of(id("fixture:failure")),
                new CapabilityVersionLimits(Optional.of("1"), Optional.empty(), Set.of()));
    }

    private static ProductionGoal goal(
            String target,
            long amount,
            Map<ResourceId, Long> owned) {
        return new ProductionGoal(
                id(target), GenericResourceType.ITEM, amount,
                Set.of(), Set.of(), Optional.empty(), MaterialConstraints.none(),
                List.of(), owned);
    }

    private static PlanningContext readyContext() {
        return new PlanningContext(
                Set.of(id("fixture:adapter")), Set.of("fixture"), supportedTypes());
    }

    private static Set<GenericResourceType> supportedTypes() {
        return Set.of(GenericResourceType.ITEM, GenericResourceType.ROTATIONAL_POWER);
    }

    private static ProcessResource item(String value, long amount) {
        return new ProcessResource(id(value), GenericResourceType.ITEM, amount);
    }

    private static VerifiedLogicalPlan success(PlanningVerificationResult result) {
        assertThat(result).isInstanceOf(PlanningVerificationSuccess.class);
        return ((PlanningVerificationSuccess) result).plan();
    }

    private static PlanningVerificationFailure assertFailure(
            PlanningVerificationResult result,
            PlanningVerificationFailureCode expectedCode) {
        assertThat(result).isInstanceOf(PlanningVerificationFailureResult.class);
        PlanningVerificationFailure failure = ((PlanningVerificationFailureResult) result).failure();
        assertThat(failure.code()).isEqualTo(expectedCode);
        assertThat(failure.detail()).isNotBlank();
        assertThat(failure.trace()).isNotEmpty();
        return failure;
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }

    private record Pipeline(
            CandidatePlan candidate,
            LogicalMachineGraph graph,
            MachineCapabilityCatalog capabilities,
            PlanningContext context) {}
}
