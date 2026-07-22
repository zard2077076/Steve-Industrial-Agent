package dev.stevecreate.agent.core.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.core.verification.VerificationEvidenceKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlanningFailureTest {
    @Test
    void definesExactlyTheElevenRequiredStableCodes() {
        assertThat(PlanningFailureCode.values()).containsExactly(
                PlanningFailureCode.TARGET_NOT_FOUND,
                PlanningFailureCode.RECIPE_NOT_FOUND,
                PlanningFailureCode.MACHINE_CAPABILITY_MISSING,
                PlanningFailureCode.RESOURCE_TYPE_UNSUPPORTED,
                PlanningFailureCode.DEPENDENCY_CYCLE,
                PlanningFailureCode.DEPTH_LIMIT_EXCEEDED,
                PlanningFailureCode.CONSTRAINT_CONFLICT,
                PlanningFailureCode.REQUIRED_MOD_UNAVAILABLE,
                PlanningFailureCode.AMBIGUOUS_OUTPUT,
                PlanningFailureCode.UNSATISFIABLE_INPUT,
                PlanningFailureCode.INVALID_QUANTITY);
    }

    @Test
    void everyCodeCarriesImmutableLocationTraceReasonAndSortedAlternatives() {
        for (PlanningFailureCode code : PlanningFailureCode.values()) {
            List<ResourceId> trace = new ArrayList<>(List.of(id("fixture:target")));
            List<ResourceId> alternatives = new ArrayList<>(List.of(
                    id("fixture:z_recipe"), id("fixture:a_recipe")));
            PlanningFailure failure = new PlanningFailure(
                    code,
                    new PlanningFailureLocation(
                            2,
                            Optional.of(id("fixture:target")),
                            Optional.of(id("fixture:recipe"))),
                    trace,
                    "A bounded user-readable explanation",
                    alternatives);
            trace.clear();
            alternatives.clear();

            assertThat(failure.tracePath()).containsExactly(id("fixture:target"));
            assertThat(failure.alternatives()).containsExactly(
                    id("fixture:a_recipe"), id("fixture:z_recipe"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> failure.tracePath().clear());
        }
    }

    @Test
    void rejectsMissingOrUnboundedFailureEvidenceAndEmptySuccessConventions() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PlanningFailureLocation(
                0, Optional.empty(), Optional.empty()));
        assertThatIllegalArgumentException().isThrownBy(() -> failure(List.of(), "reason", List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> failure(
                List.of(id("fixture:target")), " ", List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> failure(
                List.of(id("fixture:target")), "reason",
                List.of(id("fixture:same"), id("fixture:same"))));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new PlanningSuccess(List.of()));
    }

    @Test
    void plannerPropagatesUnavailableAdapterUnsupportedTypeAndQuantityAsTypedFailures() {
        ProcessDependencyGraphBuilder builder = new ProcessDependencyGraphBuilder();
        CatalogRecipe recipe = recipe();
        RecipeCatalog recipes = new ImmutableRecipeCatalog(List.of(recipe));
        MachineCapabilityCatalog capabilities = new ImmutableMachineCapabilityCatalog(
                List.of(capability()));

        assertThat(code(builder.plan(
                goal(1), recipes, capabilities,
                new PlanningContext(
                        Set.of(), Set.of(), Set.of(GenericResourceType.ITEM,
                        GenericResourceType.ROTATIONAL_POWER)))))
                .isEqualTo(PlanningFailureCode.REQUIRED_MOD_UNAVAILABLE);
        assertThat(code(builder.plan(
                goal(1), recipes, capabilities,
                new PlanningContext(
                        Set.of(id("fixture:adapter")), Set.of("fixture"),
                        Set.of(GenericResourceType.ROTATIONAL_POWER)))))
                .isEqualTo(PlanningFailureCode.RESOURCE_TYPE_UNSUPPORTED);
        assertThat(code(builder.plan(
                goal(ProcessResource.MAX_AMOUNT + 1L), recipes, capabilities,
                new PlanningContext(
                        Set.of(id("fixture:adapter")), Set.of("fixture"),
                        Set.of(GenericResourceType.ITEM, GenericResourceType.ROTATIONAL_POWER)))))
                .isEqualTo(PlanningFailureCode.INVALID_QUANTITY);
    }

    @Test
    void failureResultCannotHideOrReplaceItsTypedFailure() {
        PlanningFailure failure = failure(
                List.of(id("fixture:target")), "No route exists", List.of());
        PlanningResult result = new PlanningFailureResult(failure);

        assertThat(result).isInstanceOf(PlanningFailureResult.class);
        assertThat(((PlanningFailureResult) result).failure()).isSameAs(failure);
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> new PlanningFailureResult(null));
    }

    private static PlanningFailure failure(
            List<ResourceId> trace,
            String reason,
            List<ResourceId> alternatives) {
        return new PlanningFailure(
                PlanningFailureCode.UNSATISFIABLE_INPUT,
                new PlanningFailureLocation(
                        1, Optional.of(id("fixture:target")), Optional.empty()),
                trace,
                reason,
                alternatives);
    }

    private static PlanningFailureCode code(PlanningResult result) {
        assertThat(result).isInstanceOf(PlanningFailureResult.class);
        return ((PlanningFailureResult) result).failure().code();
    }

    private static ProductionGoal goal(long amount) {
        return new ProductionGoal(
                id("fixture:output"), GenericResourceType.ITEM, amount,
                Set.of(), Set.of(), Optional.empty(), MaterialConstraints.none(), List.of(),
                java.util.Map.of());
    }

    private static CatalogRecipe recipe() {
        return new CatalogRecipe(
                id("fixture:recipe"), id("fixture:type"),
                List.of(item("fixture:input")), List.of(item("fixture:output")), List.of(),
                Set.of(id("fixture:capability")),
                Set.of(GenericResourceType.ITEM, GenericResourceType.ROTATIONAL_POWER),
                OptionalLong.of(20),
                new RecipeSource(id("fixture:adapter"), "fixture", "fixture=1", true));
    }

    private static MachineCapability capability() {
        return new MachineCapability(
                id("fixture:capability"), id("fixture:adapter"), Set.of(id("fixture:type")),
                Set.of(GenericResourceType.ITEM), Set.of(GenericResourceType.ITEM),
                Set.of(new CapabilityResourceRequirement(
                        GenericResourceType.ROTATIONAL_POWER, 1, true)),
                Set.of(id("fixture:process")),
                Set.of(VerificationEvidenceKind.OUTPUT_PRODUCED), Set.of(),
                new CapabilityVersionLimits(Optional.of("1"), Optional.empty(), Set.of()));
    }

    private static ProcessResource item(String value) {
        return new ProcessResource(id(value), GenericResourceType.ITEM, 1);
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
