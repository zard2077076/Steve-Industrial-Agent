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

class ProcessDependencyGraphBuilderTest {
    private static final ResourceId CREATE_ADAPTER = id("fixture:create_adapter");
    private static final ResourceId ALT_ADAPTER = id("fixture:alternate_adapter");

    private final ProcessDependencyGraphBuilder builder = new ProcessDependencyGraphBuilder();

    @Test
    void buildsScaledSingleStepMillingWithRawLeafAndByproduct() {
        PlanningSuccess success = success(plan(
                goal("minecraft:gravel", 3),
                List.of(milling()),
                capabilities("industrial:milling"),
                context(CREATE_ADAPTER)));

        ProcessDependencyGraph graph = success.graphs().get(0);
        assertThat(graph.steps()).hasSize(1);
        assertThat(graph.steps().get(0).recipe().recipeId()).isEqualTo(id("create:milling/cobblestone"));
        assertThat(graph.steps().get(0).executions()).isEqualTo(3);
        assertThat(graph.rawMaterials()).containsExactly(item("minecraft:cobblestone", 3));
        assertThat(graph.byproducts()).containsExactly(item("minecraft:flint", 3));
        assertThat(graph.targetProducerStepId()).contains(id("planning:step_0001"));
    }

    @Test
    void buildsTheIndependentIronPressingScenario() {
        ProcessDependencyGraph graph = success(plan(
                goal("create:iron_sheet", 2),
                List.of(pressing()),
                capabilities("industrial:pressing"),
                context(CREATE_ADAPTER))).graphs().get(0);

        assertThat(graph.steps()).extracting(step -> step.recipe().recipeId())
                .containsExactly(id("create:pressing/iron_ingot"));
        assertThat(graph.rawMaterials()).containsExactly(item("minecraft:iron_ingot", 2));
        assertThat(graph.steps().get(0).scaledOutputs()).containsExactly(item("create:iron_sheet", 2));
    }

    @Test
    void buildsTwoStepMultiInputChainWithQuantityConversionAndDependencyEdge() {
        ProcessDependencyGraph graph = success(plan(
                goal("fixture:alloy_ingot", 2),
                List.of(smelting(), crushing()),
                capabilities("industrial:smelting", "industrial:crushing"),
                context(CREATE_ADAPTER))).graphs().get(0);

        assertThat(graph.steps()).extracting(step -> step.recipe().recipeId().toString())
                .containsExactly("fixture:crushing/ore", "fixture:smelting/alloy");
        assertThat(graph.steps()).extracting(ProcessStepDependency::executions)
                .containsExactly(1L, 2L);
        assertThat(graph.rawMaterials())
                .containsExactly(item("fixture:coal", 2), item("fixture:ore", 1));
        assertThat(graph.edges()).containsExactly(new ProcessDependencyEdge(
                id("planning:step_0001"), id("planning:step_0002"), item("fixture:dust", 2)));
    }

    @Test
    void returnsStableAlternativeGraphsAndIdenticalRepeatedResults() {
        CatalogRecipe alternate = recipe(
                "other:gravel", "other:crushing", "industrial:alternate",
                List.of(item("minecraft:stone", 1)),
                List.of(item("minecraft:gravel", 1)), List.of(), ALT_ADAPTER, "other");
        MachineCapabilityCatalog capabilityCatalog = capabilities(
                capability("industrial:milling", CREATE_ADAPTER, "create:milling"),
                capability("industrial:alternate", ALT_ADAPTER, "other:crushing"));
        RecipeCatalog recipes = new ImmutableRecipeCatalog(List.of(alternate, milling()));
        PlanningContext context = context(CREATE_ADAPTER, ALT_ADAPTER);
        ProductionGoal goal = goal("minecraft:gravel", 1);

        PlanningResult first = builder.plan(goal, recipes, capabilityCatalog, context);
        PlanningResult second = builder.plan(goal, recipes, capabilityCatalog, context);

        assertThat(first).isEqualTo(second);
        assertThat(success(first).graphs()).extracting(graph ->
                graph.steps().get(graph.steps().size() - 1).recipe().recipeId().toString())
                .containsExactly("create:milling/cobblestone", "other:gravel");
    }

    @Test
    void ownedIntermediateSkipsItsUpstreamRecipe() {
        ProductionGoal goal = goal(
                "fixture:alloy_ingot", 2, Set.of(), Optional.empty(),
                Map.of(id("fixture:dust"), 2L));
        ProcessDependencyGraph graph = success(plan(
                goal,
                List.of(smelting(), crushing()),
                capabilities("industrial:smelting", "industrial:crushing"),
                context(CREATE_ADAPTER))).graphs().get(0);

        assertThat(graph.steps()).extracting(step -> step.recipe().recipeId())
                .containsExactly(id("fixture:smelting/alloy"));
        assertThat(graph.ownedResourcesUsed()).containsExactly(item("fixture:dust", 2));
        assertThat(graph.rawMaterials()).containsExactly(item("fixture:coal", 2));
        assertThat(graph.edges()).isEmpty();
    }

    @Test
    void returnsTypedFailureWhenRequiredMachineCapabilityIsMissing() {
        PlanningFailure failure = failure(plan(
                goal("minecraft:gravel", 1),
                List.of(milling()),
                capabilities("industrial:pressing"),
                context(CREATE_ADAPTER)));

        assertThat(failure.code()).isEqualTo(PlanningFailureCode.MACHINE_CAPABILITY_MISSING);
        assertThat(failure.location().recipeId()).contains(id("create:milling/cobblestone"));
    }

    @Test
    void distinguishesMissingTargetRecipeFromRawInputLeaves() {
        PlanningFailure failure = failure(plan(
                goal("fixture:missing", 1),
                List.of(milling()),
                capabilities("industrial:milling"),
                context(CREATE_ADAPTER)));

        assertThat(failure.code()).isEqualTo(PlanningFailureCode.RECIPE_NOT_FOUND);
        assertThat(failure.location().resourceId()).contains(id("fixture:missing"));
    }

    @Test
    void detectsRecipeCyclesWithAStableTrace() {
        CatalogRecipe first = recipe(
                "fixture:a_from_b", "fixture:cycle", "industrial:cycle",
                List.of(item("fixture:b", 1)), List.of(item("fixture:a", 1)),
                List.of(), CREATE_ADAPTER, "create");
        CatalogRecipe second = recipe(
                "fixture:b_from_a", "fixture:cycle", "industrial:cycle",
                List.of(item("fixture:a", 1)), List.of(item("fixture:b", 1)),
                List.of(), CREATE_ADAPTER, "create");

        PlanningFailure failure = failure(plan(
                goal("fixture:a", 1), List.of(second, first),
                capabilities(capability("industrial:cycle", CREATE_ADAPTER, "fixture:cycle")),
                context(CREATE_ADAPTER)));

        assertThat(failure.code()).isEqualTo(PlanningFailureCode.DEPENDENCY_CYCLE);
        assertThat(failure.tracePath()).containsExactly(
                id("fixture:a"), id("fixture:b"), id("fixture:a"));
    }

    @Test
    void enforcesMaximumDepthAndForbiddenModConstraints() {
        PlanningFailure depth = failure(plan(
                goal("fixture:alloy_ingot", 1, Set.of(), Optional.of(1), Map.of()),
                List.of(smelting(), crushing()),
                capabilities("industrial:smelting", "industrial:crushing"),
                context(CREATE_ADAPTER)));
        PlanningFailure forbidden = failure(plan(
                goal("minecraft:gravel", 1, Set.of("create"), Optional.empty(), Map.of()),
                List.of(milling()),
                capabilities("industrial:milling"),
                context(CREATE_ADAPTER)));

        assertThat(depth.code()).isEqualTo(PlanningFailureCode.DEPTH_LIMIT_EXCEEDED);
        assertThat(forbidden.code()).isEqualTo(PlanningFailureCode.CONSTRAINT_CONFLICT);
    }

    @Test
    void rejectsRawMaterialThatViolatesTheDeclaredMaximum() {
        ProductionGoal goal = new ProductionGoal(
                id("minecraft:gravel"), GenericResourceType.ITEM, 3,
                Set.of(), Set.of(), Optional.empty(),
                new MaterialConstraints(Set.of(), Map.of(id("minecraft:cobblestone"), 2L)),
                List.of(), Map.of());

        PlanningFailure failure = failure(plan(
                goal, List.of(milling()), capabilities("industrial:milling"),
                context(CREATE_ADAPTER)));
        assertThat(failure.code()).isEqualTo(PlanningFailureCode.UNSATISFIABLE_INPUT);
    }

    private PlanningResult plan(
            ProductionGoal goal,
            List<CatalogRecipe> recipes,
            MachineCapabilityCatalog capabilities,
            PlanningContext context) {
        return builder.plan(goal, new ImmutableRecipeCatalog(recipes), capabilities, context);
    }

    private static PlanningSuccess success(PlanningResult result) {
        assertThat(result).isInstanceOf(PlanningSuccess.class);
        return (PlanningSuccess) result;
    }

    private static PlanningFailure failure(PlanningResult result) {
        assertThat(result).isInstanceOf(PlanningFailureResult.class);
        return ((PlanningFailureResult) result).failure();
    }

    private static ProductionGoal goal(String target, long quantity) {
        return goal(target, quantity, Set.of(), Optional.empty(), Map.of());
    }

    private static ProductionGoal goal(
            String target,
            long quantity,
            Set<String> forbiddenMods,
            Optional<Integer> depth,
            Map<ResourceId, Long> owned) {
        return new ProductionGoal(
                id(target), GenericResourceType.ITEM, quantity,
                Set.of(), forbiddenMods, depth, MaterialConstraints.none(), List.of(), owned);
    }

    private static CatalogRecipe milling() {
        return recipe(
                "create:milling/cobblestone", "create:milling", "industrial:milling",
                List.of(item("minecraft:cobblestone", 1)),
                List.of(item("minecraft:gravel", 1)),
                List.of(item("minecraft:flint", 1)), CREATE_ADAPTER, "create");
    }

    private static CatalogRecipe pressing() {
        return recipe(
                "create:pressing/iron_ingot", "create:pressing", "industrial:pressing",
                List.of(item("minecraft:iron_ingot", 1)),
                List.of(item("create:iron_sheet", 1)), List.of(), CREATE_ADAPTER, "create");
    }

    private static CatalogRecipe crushing() {
        return recipe(
                "fixture:crushing/ore", "fixture:crushing", "industrial:crushing",
                List.of(item("fixture:ore", 1)), List.of(item("fixture:dust", 2)),
                List.of(), CREATE_ADAPTER, "create");
    }

    private static CatalogRecipe smelting() {
        return recipe(
                "fixture:smelting/alloy", "fixture:smelting", "industrial:smelting",
                List.of(item("fixture:dust", 1), item("fixture:coal", 1)),
                List.of(item("fixture:alloy_ingot", 1)), List.of(), CREATE_ADAPTER, "create");
    }

    private static CatalogRecipe recipe(
            String recipeId,
            String recipeType,
            String capabilityId,
            List<ProcessResource> inputs,
            List<ProcessResource> outputs,
            List<ProcessResource> byproducts,
            ResourceId adapterId,
            String modId) {
        return new CatalogRecipe(
                id(recipeId), id(recipeType), inputs, outputs, byproducts,
                Set.of(id(capabilityId)),
                Set.of(GenericResourceType.ITEM, GenericResourceType.ROTATIONAL_POWER),
                OptionalLong.of(20),
                new RecipeSource(adapterId, modId, modId + "=fixture", true));
    }

    private static MachineCapabilityCatalog capabilities(String... capabilityIds) {
        return new ImmutableMachineCapabilityCatalog(java.util.Arrays.stream(capabilityIds)
                .map(value -> capability(value, CREATE_ADAPTER, recipeTypeFor(value)))
                .toList());
    }

    private static MachineCapabilityCatalog capabilities(MachineCapability... capabilities) {
        return new ImmutableMachineCapabilityCatalog(List.of(capabilities));
    }

    private static MachineCapability capability(
            String capabilityId,
            ResourceId adapterId,
            String recipeType) {
        return new MachineCapability(
                id(capabilityId), adapterId, Set.of(id(recipeType)),
                Set.of(GenericResourceType.ITEM), Set.of(GenericResourceType.ITEM),
                Set.of(new CapabilityResourceRequirement(
                        GenericResourceType.ROTATIONAL_POWER, 1, true)),
                Set.of(id("fixture:process")),
                Set.of(VerificationEvidenceKind.OUTPUT_PRODUCED), Set.of(),
                new CapabilityVersionLimits(Optional.of("1"), Optional.empty(), Set.of()));
    }

    private static String recipeTypeFor(String capabilityId) {
        return switch (capabilityId) {
            case "industrial:milling" -> "create:milling";
            case "industrial:pressing" -> "create:pressing";
            case "industrial:crushing" -> "fixture:crushing";
            case "industrial:smelting" -> "fixture:smelting";
            default -> "fixture:unrelated";
        };
    }

    private static PlanningContext context(ResourceId... adapters) {
        return new PlanningContext(
                Set.of(adapters), Set.of("create", "other"),
                Set.of(GenericResourceType.ITEM, GenericResourceType.ROTATIONAL_POWER));
    }

    private static ProcessResource item(String value, long amount) {
        return new ProcessResource(id(value), GenericResourceType.ITEM, amount);
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
