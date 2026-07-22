package dev.stevecreate.agent.adapter.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.CapabilityResourceRequirement;
import dev.stevecreate.agent.core.planning.CapabilityVersionLimits;
import dev.stevecreate.agent.core.planning.ImmutableMachineCapabilityCatalog;
import dev.stevecreate.agent.core.planning.ImmutableRuntimeRecipeCatalog;
import dev.stevecreate.agent.core.planning.MachineCapability;
import dev.stevecreate.agent.core.planning.MaterialConstraints;
import dev.stevecreate.agent.core.planning.PlanningStrategyPreference;
import dev.stevecreate.agent.core.planning.ProductionGoal;
import dev.stevecreate.agent.core.planning.RecipeIngredient;
import dev.stevecreate.agent.core.planning.RecipeSource;
import dev.stevecreate.agent.core.planning.RuntimeRecipeCatalogEntry;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.core.verification.VerificationEvidenceKind;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RuntimeKnowledgePlanningServiceTest {
    private static final String FINGERPRINT = "sha256:r05-runtime";
    private final RuntimeKnowledgePlanningService service = new RuntimeKnowledgePlanningService();

    @Test
    void drivesResolvedRuntimeMillingThroughScoringAndVerificationWithRoundedQuantity() {
        RuntimeRecipeCatalogSnapshot recipes = recipeSnapshot(List.of(
                recipe(
                        "create:milling/cobblestone",
                        "create:milling",
                        new RecipeIngredient.ExactResource(id("minecraft:cobblestone"), 1),
                        "minecraft:gravel",
                        2)));
        RuntimeMachineCapabilityCatalogSnapshot capabilities = capabilitySnapshot(List.of(
                declaration("create:milling", "create:milling/cobblestone", FINGERPRINT)));

        RuntimePlanningResult result = service.plan(
                recipes, capabilities, goal("minecraft:gravel", 3, Map.of()));

        assertThat(result).isInstanceOf(RuntimePlanningResult.Success.class);
        RuntimeVerifiedPlanningResult planned =
                ((RuntimePlanningResult.Success) result).result();
        assertThat(planned.runtimeFingerprint()).isEqualTo(FINGERPRINT);
        assertThat(planned.rankedCandidates()).hasSize(1);
        assertThat(planned.verifiedPlan().candidate().selectedRecipes())
                .extracting(value -> value.recipeId())
                .containsExactly(id("create:milling/cobblestone"));
        assertThat(planned.verifiedPlan().candidate().quantityConversions())
                .singleElement()
                .satisfies(conversion -> {
                    assertThat(conversion.executions()).isEqualTo(2);
                    assertThat(conversion.inputs()).containsExactly(
                            item("minecraft:cobblestone", 2));
                    assertThat(conversion.outputs()).containsExactly(
                            item("minecraft:gravel", 4));
                });
        assertThat(planned.verifiedPlan().candidate().rawMaterials())
                .containsExactly(item("minecraft:cobblestone", 2));
        assertThat(planned.resolvedRecipes().resolutions().get(0).selections().get(0)
                        .ingredientIdentity())
                .isEqualTo("exact:minecraft:cobblestone@1");
        assertThat(Arrays.stream(RuntimeVerifiedPlanningResult.class.getDeclaredFields())
                        .map(field -> field.getType().getName()))
                .noneMatch(name -> name.contains("BlockPos")
                        || name.contains("MachineOrientation")
                        || name.contains("UnifiedMachineGraph")
                        || name.contains("GenericExecutionSession"));
        RuntimePlanningCommandReport report =
                new RuntimePlanningCommandFormatter().format(result);
        assertThat(report.success()).isTrue();
        assertThat(report.commandReturn()).isEqualTo(1);
        assertThat(String.join(" ", report.userLines()))
                .contains(
                        "target=minecraft:gravel",
                        "quantity=3",
                        "candidates=1",
                        "recipe=create:milling/cobblestone",
                        "raw=[minecraft:cobblestone@2]",
                        "capabilities=[create:milling]",
                        "ingredient=EXACT_RESOURCE:minecraft:cobblestone",
                        "verification=PASS",
                        FINGERPRINT);
        assertThat(report.structuredLog())
                .contains("runtimePlanning={", "trace=[minecraft:gravel,create:milling/cobblestone]");
    }

    @Test
    void ownedTagChoiceAndEqualScoreAlternativesRemainDeterministic() {
        RuntimeRecipeCatalogSnapshot recipes = recipeSnapshot(List.of(
                recipe(
                        "create:pressing/iron_ingot",
                        "create:pressing",
                        new RecipeIngredient.TagReference(
                                id("forge:ingots/iron"),
                                List.of(id("example:iron_ingot"), id("minecraft:iron_ingot")),
                                FINGERPRINT,
                                1),
                        "create:iron_sheet",
                        1),
                recipe(
                        "create:pressing/iron_ingot_alternative",
                        "create:pressing",
                        new RecipeIngredient.ExactResource(id("minecraft:iron_ingot"), 1),
                        "create:iron_sheet",
                        1)));
        RuntimeMachineCapabilityCatalogSnapshot capabilities = capabilitySnapshot(List.of(
                declaration("create:pressing", "create:pressing/iron_ingot", FINGERPRINT)));
        ProductionGoal goal = goal(
                "create:iron_sheet", 2, Map.of(id("minecraft:iron_ingot"), 2L));

        RuntimeVerifiedPlanningResult first = success(service.plan(recipes, capabilities, goal));
        RuntimeVerifiedPlanningResult repeated = success(service.plan(recipes, capabilities, goal));

        assertThat(first.rankedCandidates()).isEqualTo(repeated.rankedCandidates());
        assertThat(first.rankedCandidates()).hasSize(2);
        assertThat(first.verifiedPlan().id()).isEqualTo(repeated.verifiedPlan().id());
        assertThat(first.resolvedRecipes().resolutions())
                .filteredOn(value -> value.runtimeEntry().recipeId()
                        .equals(id("create:pressing/iron_ingot")))
                .singleElement()
                .satisfies(value -> {
                    assertThat(value.selections().get(0).selectedResource())
                            .isEqualTo(id("minecraft:iron_ingot"));
                    assertThat(value.selections().get(0).ingredientKind().name())
                            .isEqualTo("TAG_REFERENCE");
                });
    }

    @Test
    void missingRecipeAndCapabilityAreCompleteTypedFailures() {
        RuntimeRecipeCatalogSnapshot recipes = recipeSnapshot(List.of(
                recipe(
                        "create:milling/cobblestone",
                        "create:milling",
                        new RecipeIngredient.ExactResource(id("minecraft:cobblestone"), 1),
                        "minecraft:gravel",
                        1)));
        RuntimeMachineCapabilityCatalogSnapshot milling = capabilitySnapshot(List.of(
                declaration("create:milling", "create:milling/cobblestone", FINGERPRINT)));
        RuntimeMachineCapabilityCatalogSnapshot pressingOnly = capabilitySnapshot(List.of(
                declaration("create:pressing", "create:pressing/iron_ingot", FINGERPRINT)));

        assertFailure(
                service.plan(recipes, milling, goal("minecraft:diamond", 1, Map.of())),
                RuntimeKnowledgeFailureCode.RECIPE_NOT_FOUND);
        RuntimeKnowledgeFailure missingCapability = assertFailure(
                service.plan(recipes, pressingOnly, goal("minecraft:gravel", 1, Map.of())),
                RuntimeKnowledgeFailureCode.MACHINE_CAPABILITY_MISSING);
        assertThat(missingCapability.targetResource()).contains(id("minecraft:gravel"));
        assertThat(missingCapability.recipeId()).contains(id("create:milling/cobblestone"));
        assertThat(missingCapability.trace()).isNotEmpty();
        RuntimePlanningCommandReport report = new RuntimePlanningCommandFormatter().format(
                new RuntimePlanningResult.Failure(missingCapability));
        assertThat(report.success()).isFalse();
        assertThat(report.commandReturn()).isZero();
        assertThat(report.userLines()).singleElement().asString()
                .contains("MACHINE_CAPABILITY_MISSING", "minecraft:gravel", FINGERPRINT);
        assertThat(report.structuredLog()).contains(
                "stage=PLANNING", "recipe=create:milling/cobblestone", "trace=");
    }

    @Test
    void refusesRecipeAndCapabilitySnapshotsFromDifferentRuntimeFingerprints() {
        RuntimeRecipeCatalogSnapshot recipes = recipeSnapshot(List.of(
                recipe(
                        "create:milling/cobblestone",
                        "create:milling",
                        new RecipeIngredient.ExactResource(id("minecraft:cobblestone"), 1),
                        "minecraft:gravel",
                        1)));
        RuntimeMachineCapabilityCatalogSnapshot capabilities = capabilitySnapshot(List.of(
                declaration("create:milling", "create:milling/cobblestone", "sha256:other")),
                "sha256:other");

        assertFailure(
                service.plan(recipes, capabilities, goal("minecraft:gravel", 1, Map.of())),
                RuntimeKnowledgeFailureCode.RUNTIME_FINGERPRINT_MISMATCH);
    }

    private static RuntimeVerifiedPlanningResult success(RuntimePlanningResult result) {
        assertThat(result).isInstanceOf(RuntimePlanningResult.Success.class);
        return ((RuntimePlanningResult.Success) result).result();
    }

    private static RuntimeKnowledgeFailure assertFailure(
            RuntimePlanningResult result,
            RuntimeKnowledgeFailureCode code) {
        assertThat(result).isInstanceOf(RuntimePlanningResult.Failure.class);
        RuntimeKnowledgeFailure failure = ((RuntimePlanningResult.Failure) result).failure();
        assertThat(failure.code()).isEqualTo(code);
        assertThat(failure.stage()).isEqualTo(RuntimeKnowledgeStage.PLANNING);
        assertThat(failure.detail()).isNotBlank();
        return failure;
    }

    private static RuntimeRecipeCatalogSnapshot recipeSnapshot(
            List<RuntimeRecipeCatalogEntry> recipes) {
        return new RuntimeRecipeCatalogSnapshot(
                new ImmutableRuntimeRecipeCatalog(recipes),
                runtime(),
                FINGERPRINT,
                "minecraft:overworld",
                0,
                recipes.size(),
                recipes.size(),
                List.of(),
                List.of());
    }

    private static RuntimeRecipeCatalogEntry recipe(
            String recipeId,
            String recipeType,
            RecipeIngredient ingredient,
            String output,
            long outputAmount) {
        return new RuntimeRecipeCatalogEntry(
                id(recipeId),
                id(recipeType),
                List.of(ingredient),
                List.of(item(output, outputAmount)),
                List.of(),
                Set.of(id(recipeType)),
                Set.of(GenericResourceType.ITEM, GenericResourceType.ROTATIONAL_POWER),
                OptionalLong.of(100),
                new RecipeSource(id("steve_industrial:create_runtime_1_20_1_6_0_6"),
                        "create", FINGERPRINT, true));
    }

    private static RuntimeMachineCapabilityCatalogSnapshot capabilitySnapshot(
            List<RuntimeMachineCapabilityDeclaration> declarations) {
        return capabilitySnapshot(declarations, FINGERPRINT);
    }

    private static RuntimeMachineCapabilityCatalogSnapshot capabilitySnapshot(
            List<RuntimeMachineCapabilityDeclaration> declarations,
            String fingerprint) {
        return new RuntimeMachineCapabilityCatalogSnapshot(
                new ImmutableMachineCapabilityCatalog(declarations.stream()
                        .map(RuntimeMachineCapabilityDeclaration::capability)
                        .toList()),
                declarations,
                runtime(),
                fingerprint);
    }

    private static RuntimeMachineCapabilityDeclaration declaration(
            String capabilityId,
            String provenRecipeId,
            String fingerprint) {
        MachineCapability capability = new MachineCapability(
                id(capabilityId),
                id("steve_industrial:create_runtime_1_20_1_6_0_6"),
                Set.of(id(capabilityId)),
                Set.of(GenericResourceType.ITEM),
                Set.of(GenericResourceType.ITEM),
                Set.of(new CapabilityResourceRequirement(
                        GenericResourceType.ROTATIONAL_POWER, 1, true)),
                Set.of(id("create:v606/process")),
                Set.of(
                        VerificationEvidenceKind.POWER_PRESENT,
                        VerificationEvidenceKind.INPUT_CONSUMED,
                        VerificationEvidenceKind.PROCESS_COMPLETED,
                        VerificationEvidenceKind.OUTPUT_PRODUCED),
                Set.of(id("steve_industrial:no_power")),
                new CapabilityVersionLimits(
                        Optional.of("6.0.6"),
                        Optional.of("6.0.7"),
                        Set.of(fingerprint)));
        return new RuntimeMachineCapabilityDeclaration(
                capability,
                true,
                Set.of(id(provenRecipeId)),
                runtime(),
                fingerprint,
                RuntimeMachineCapabilitySource.VERSIONED_ADAPTER);
    }

    private static ProductionGoal goal(
            String target,
            long quantity,
            Map<ResourceId, Long> owned) {
        return new ProductionGoal(
                id(target),
                GenericResourceType.ITEM,
                quantity,
                Set.of(),
                Set.of(),
                Optional.of(8),
                MaterialConstraints.none(),
                List.of(
                        PlanningStrategyPreference.MINIMIZE_STEPS,
                        PlanningStrategyPreference.PREFER_OWNED_RESOURCES),
                owned);
    }

    private static RuntimeFingerprint runtime() {
        return new RuntimeFingerprint(
                "1.20.1",
                "forge",
                "47.4.10",
                Map.of("create", "6.0.6-150"),
                "steve_industrial:create_runtime_1_20_1_6_0_6",
                1);
    }

    private static ProcessResource item(String value, long amount) {
        return new ProcessResource(id(value), GenericResourceType.ITEM, amount);
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
