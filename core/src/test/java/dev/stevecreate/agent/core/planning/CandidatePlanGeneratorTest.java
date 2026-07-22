package dev.stevecreate.agent.core.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CandidatePlanGeneratorTest {
    private final CandidatePlanGenerator generator = new CandidatePlanGenerator();

    @Test
    void generatesACompleteCoordinateFreeSingleStepCandidate() {
        CandidatePlan candidate = generator.generate(new PlanningSuccess(List.of(singleStepGraph())))
                .get(0);

        assertThat(candidate.selectedRecipes()).extracting(recipe -> recipe.recipeId())
                .containsExactly(id("fixture:milling"));
        assertThat(candidate.requiredMachineCapabilities())
                .containsExactly(id("industrial:milling"));
        assertThat(candidate.rawMaterials()).containsExactly(item("minecraft:cobblestone", 2));
        assertThat(candidate.processingOrder()).containsExactly(id("planning:step_0001"));
        assertThat(candidate.quantityConversions()).containsExactly(new CandidateQuantityConversion(
                id("planning:step_0001"), id("fixture:milling"), 2,
                List.of(item("minecraft:cobblestone", 2)),
                List.of(item("minecraft:gravel", 2)),
                List.of(item("minecraft:flint", 2))));
        assertThat(candidate.unboundMachineNodes()).containsExactly(new UnboundMachineNode(
                id("planning:machine_step_0001"), id("planning:step_0001"),
                Set.of(id("industrial:milling"))));
        assertThat(candidate.unboundSpatialLayout().nodeIds())
                .containsExactly(id("planning:machine_step_0001"));
        assertThat(candidate.unboundSpatialLayout().isBound()).isFalse();
        assertThat(candidate.planningEvidence()).extracting(PlanningEvidence::kind)
                .contains(
                        PlanningEvidenceKind.GOAL_SATISFIED,
                        PlanningEvidenceKind.RECIPE_SELECTED,
                        PlanningEvidenceKind.QUANTITY_SCALED,
                        PlanningEvidenceKind.CAPABILITY_REQUIRED);
        assertThat(candidate.estimatedProcessingTicks()).hasValue(40);
    }

    @Test
    void preservesMultiStepOrderIntermediateQuantitiesAndDependencies() {
        CandidatePlan candidate = generator.generate(new PlanningSuccess(List.of(multiStepGraph())))
                .get(0);

        assertThat(candidate.processingOrder()).containsExactly(
                id("planning:step_0001"), id("planning:step_0002"));
        assertThat(candidate.intermediateResources()).containsExactly(item("fixture:dust", 2));
        assertThat(candidate.dependencies()).containsExactly(new ProcessDependencyEdge(
                id("planning:step_0001"), id("planning:step_0002"), item("fixture:dust", 2)));
        assertThat(candidate.rawMaterials()).containsExactly(
                item("fixture:coal", 2), item("fixture:ore", 1));
        assertThat(candidate.estimatedProcessingTicks()).hasValue(60);
    }

    @Test
    void preservesOwnedMaterialUseWithoutInventingAnUpstreamNode() {
        ProcessDependencyGraph source = ownedIntermediateGraph();
        CandidatePlan candidate = generator.generate(new PlanningSuccess(List.of(source))).get(0);

        assertThat(candidate.ownedResourcesUsed()).containsExactly(item("fixture:dust", 2));
        assertThat(candidate.processingOrder()).containsExactly(id("planning:step_0001"));
        assertThat(candidate.dependencies()).isEmpty();
        assertThat(candidate.planningEvidence()).extracting(PlanningEvidence::kind)
                .contains(PlanningEvidenceKind.OWNED_RESOURCE_APPLIED);
    }

    @Test
    void generatesAlternativesInStableOrderWithRepeatableLayoutIndependentIds() {
        ProcessDependencyGraph first = singleStepGraph();
        ProcessDependencyGraph second = alternateSingleStepGraph();
        PlanningSuccess alternatives = new PlanningSuccess(List.of(first, second));

        List<CandidatePlan> one = generator.generate(alternatives);
        List<CandidatePlan> two = generator.generate(alternatives);

        assertThat(one).isEqualTo(two);
        assertThat(one).extracting(candidate -> candidate.selectedRecipes().get(0).recipeId())
                .containsExactly(id("fixture:milling"), id("fixture:crushing"));
        assertThat(one).extracting(CandidatePlan::candidateId).doesNotHaveDuplicates();
        assertThat(one).allSatisfy(candidate -> assertThat(candidate.candidateId().namespace())
                .isEqualTo("planning"));
    }

    @Test
    void candidateCollectionsAreDefensivelyImmutable() {
        CandidatePlan candidate = generator.generate(new PlanningSuccess(List.of(singleStepGraph())))
                .get(0);
        List<PlanningEvidence> evidence = new ArrayList<>(candidate.planningEvidence());

        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> candidate.processingOrder().clear());
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> candidate.unboundMachineNodes().clear());
        assertThat(evidence).isNotEmpty();
    }

    private static ProcessDependencyGraph singleStepGraph() {
        CatalogRecipe recipe = recipe(
                "fixture:milling", "fixture:milling", "industrial:milling",
                List.of(item("minecraft:cobblestone", 1)),
                List.of(item("minecraft:gravel", 1)),
                List.of(item("minecraft:flint", 1)), 20);
        ProcessStepDependency step = step(
                "planning:step_0001", recipe, 2, 1,
                List.of(item("minecraft:cobblestone", 2)),
                List.of(item("minecraft:gravel", 2)),
                List.of(item("minecraft:flint", 2)));
        return graph(
                goal("minecraft:gravel", 2), List.of(step), List.of(),
                List.of(item("minecraft:cobblestone", 2)), List.of(),
                List.of(item("minecraft:flint", 2)), "planning:step_0001");
    }

    private static ProcessDependencyGraph alternateSingleStepGraph() {
        CatalogRecipe recipe = recipe(
                "fixture:crushing", "fixture:crushing", "industrial:crushing",
                List.of(item("minecraft:stone", 1)),
                List.of(item("minecraft:gravel", 1)), List.of(), 30);
        ProcessStepDependency step = step(
                "planning:step_0001", recipe, 2, 1,
                List.of(item("minecraft:stone", 2)),
                List.of(item("minecraft:gravel", 2)), List.of());
        return graph(
                goal("minecraft:gravel", 2), List.of(step), List.of(),
                List.of(item("minecraft:stone", 2)), List.of(), List.of(),
                "planning:step_0001");
    }

    private static ProcessDependencyGraph multiStepGraph() {
        CatalogRecipe crushing = recipe(
                "fixture:crushing", "fixture:crushing", "industrial:crushing",
                List.of(item("fixture:ore", 1)), List.of(item("fixture:dust", 2)),
                List.of(), 20);
        CatalogRecipe smelting = recipe(
                "fixture:smelting", "fixture:smelting", "industrial:smelting",
                List.of(item("fixture:coal", 1), item("fixture:dust", 1)),
                List.of(item("fixture:alloy", 1)), List.of(), 20);
        ProcessStepDependency first = step(
                "planning:step_0001", crushing, 1, 2,
                List.of(item("fixture:ore", 1)), List.of(item("fixture:dust", 2)), List.of());
        ProcessStepDependency second = step(
                "planning:step_0002", smelting, 2, 1,
                List.of(item("fixture:coal", 2), item("fixture:dust", 2)),
                List.of(item("fixture:alloy", 2)), List.of());
        ProcessDependencyEdge edge = new ProcessDependencyEdge(
                id("planning:step_0001"), id("planning:step_0002"), item("fixture:dust", 2));
        return graph(
                goal("fixture:alloy", 2), List.of(first, second), List.of(edge),
                List.of(item("fixture:coal", 2), item("fixture:ore", 1)),
                List.of(), List.of(), "planning:step_0002");
    }

    private static ProcessDependencyGraph ownedIntermediateGraph() {
        CatalogRecipe smelting = recipe(
                "fixture:smelting", "fixture:smelting", "industrial:smelting",
                List.of(item("fixture:coal", 1), item("fixture:dust", 1)),
                List.of(item("fixture:alloy", 1)), List.of(), 20);
        ProcessStepDependency step = step(
                "planning:step_0001", smelting, 2, 1,
                List.of(item("fixture:coal", 2), item("fixture:dust", 2)),
                List.of(item("fixture:alloy", 2)), List.of());
        return graph(
                goal("fixture:alloy", 2), List.of(step), List.of(),
                List.of(item("fixture:coal", 2)), List.of(item("fixture:dust", 2)),
                List.of(), "planning:step_0001");
    }

    private static ProcessDependencyGraph graph(
            ProductionGoal goal,
            List<ProcessStepDependency> steps,
            List<ProcessDependencyEdge> edges,
            List<ProcessResource> raw,
            List<ProcessResource> owned,
            List<ProcessResource> byproducts,
            String targetProducer) {
        return new ProcessDependencyGraph(
                goal, steps, edges, raw, owned, byproducts,
                Optional.of(id(targetProducer)));
    }

    private static ProcessStepDependency step(
            String stepId,
            CatalogRecipe recipe,
            long executions,
            int depth,
            List<ProcessResource> inputs,
            List<ProcessResource> outputs,
            List<ProcessResource> byproducts) {
        return new ProcessStepDependency(
                id(stepId), recipe, executions, depth, inputs, outputs, byproducts);
    }

    private static CatalogRecipe recipe(
            String recipeId,
            String type,
            String capability,
            List<ProcessResource> inputs,
            List<ProcessResource> outputs,
            List<ProcessResource> byproducts,
            long ticks) {
        return new CatalogRecipe(
                id(recipeId), id(type), inputs, outputs, byproducts,
                Set.of(id(capability)),
                Set.of(GenericResourceType.ITEM, GenericResourceType.ROTATIONAL_POWER),
                OptionalLong.of(ticks),
                new RecipeSource(id("fixture:adapter"), "fixture", "fixture=1", true));
    }

    private static ProductionGoal goal(String target, long amount) {
        return new ProductionGoal(
                id(target), GenericResourceType.ITEM, amount,
                Set.of(), Set.of(), Optional.empty(), MaterialConstraints.none(), List.of(), Map.of());
    }

    private static ProcessResource item(String value, long amount) {
        return new ProcessResource(id(value), GenericResourceType.ITEM, amount);
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
