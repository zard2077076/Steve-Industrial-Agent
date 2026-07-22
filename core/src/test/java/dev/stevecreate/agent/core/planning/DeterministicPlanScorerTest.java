package dev.stevecreate.agent.core.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DeterministicPlanScorerTest {
    private final DeterministicPlanScorer scorer = new DeterministicPlanScorer();

    @Test
    void explicitStepMachineAndRawKindWeightsProduceAnAuditableBreakdown() {
        PlanScoringWeights weights = weights(100, 10, 1, 1_000, 0, 0, 0, 0, 1);
        CandidatePlan candidate = candidate(
                "planning:candidate_a", 2,
                Set.of(id("fixture:mill"), id("fixture:press")),
                List.of(item("fixture:ore", 2), item("fixture:coal", 1)),
                List.of(), "create", OptionalLong.of(50), List.of());

        PlanScore score = scorer.score(candidate, weights, context(
                Set.of(id("fixture:mill"), id("fixture:press")), List.of()));

        assertThat(score.stepCount()).isEqualTo(2);
        assertThat(score.machineKindCount()).isEqualTo(2);
        assertThat(score.rawMaterialKindCount()).isEqualTo(2);
        assertThat(score.total()).isEqualTo(BigInteger.valueOf(222));
    }

    @Test
    void unavailableCapabilitiesReceiveTheirExplicitPenalty() {
        CandidatePlan available = candidate(
                "planning:candidate_available", 2, Set.of(id("fixture:mill")),
                List.of(item("fixture:ore", 1)), List.of(), "create",
                OptionalLong.of(10), List.of());
        CandidatePlan unavailable = candidate(
                "planning:candidate_unavailable", 1, Set.of(id("fixture:press")),
                List.of(item("fixture:ore", 1)), List.of(), "create",
                OptionalLong.of(10), List.of());
        PlanScoringWeights weights = weights(1, 0, 0, 1_000, 0, 0, 0, 0, 1);

        List<ScoredCandidate> ranked = scorer.rank(
                List.of(unavailable, available), weights,
                context(Set.of(id("fixture:mill")), List.of()));

        assertThat(ranked).extracting(value -> value.candidate().candidateId())
                .containsExactly(id("planning:candidate_available"),
                        id("planning:candidate_unavailable"));
        assertThat(ranked.get(1).score().unavailableCapabilityCount()).isEqualTo(1);
    }

    @Test
    void orderedModPreferenceIsExplicitAndDeterministic() {
        CandidatePlan create = candidate(
                "planning:candidate_create", 1, Set.of(id("fixture:machine")),
                List.of(item("fixture:ore", 1)), List.of(), "create",
                OptionalLong.of(10), List.of());
        CandidatePlan other = candidate(
                "planning:candidate_other", 1, Set.of(id("fixture:machine")),
                List.of(item("fixture:ore", 1)), List.of(), "other",
                OptionalLong.of(10), List.of());

        List<ScoredCandidate> ranked = scorer.rank(
                List.of(other, create),
                weights(0, 0, 0, 0, 100, 0, 0, 0, 1),
                context(Set.of(id("fixture:machine")), List.of("create", "other")));

        assertThat(ranked).extracting(value -> value.candidate().candidateId())
                .containsExactly(id("planning:candidate_create"), id("planning:candidate_other"));
        assertThat(ranked.get(0).score().modPreferencePenaltyUnits()).isZero();
        assertThat(ranked.get(1).score().modPreferencePenaltyUnits()).isEqualTo(1);
    }

    @Test
    void ownedUtilizationAndEstimatedTimeRespectGoalStrategyMultiplier() {
        CandidatePlan ownedFast = candidate(
                "planning:candidate_owned", 1, Set.of(id("fixture:machine")),
                List.of(item("fixture:ore", 1)), List.of(item("fixture:ore", 3)),
                "create", OptionalLong.of(10),
                List.of(PlanningStrategyPreference.PREFER_OWNED_RESOURCES,
                        PlanningStrategyPreference.MINIMIZE_PROCESSING_TIME));
        CandidatePlan rawSlow = candidate(
                "planning:candidate_raw", 1, Set.of(id("fixture:machine")),
                List.of(item("fixture:ore", 4)), List.of(), "create",
                OptionalLong.of(100),
                List.of(PlanningStrategyPreference.PREFER_OWNED_RESOURCES,
                        PlanningStrategyPreference.MINIMIZE_PROCESSING_TIME));

        List<ScoredCandidate> ranked = scorer.rank(
                List.of(rawSlow, ownedFast),
                weights(0, 0, 0, 0, 0, 1, 1, 10_000, 3),
                context(Set.of(id("fixture:machine")), List.of()));

        assertThat(ranked.get(0).candidate()).isEqualTo(ownedFast);
        assertThat(ranked.get(0).score().ownedUtilizationBasisPoints()).isEqualTo(7_500);
        assertThat(ranked.get(1).score().ownedUtilizationBasisPoints()).isZero();
    }

    @Test
    void equalTotalsUseCandidateIdAsStableTieBreakAcrossRepeatedRuns() {
        CandidatePlan z = candidate(
                "planning:candidate_z", 1, Set.of(id("fixture:machine")),
                List.of(item("fixture:ore", 1)), List.of(), "create",
                OptionalLong.empty(), List.of());
        CandidatePlan a = candidate(
                "planning:candidate_a", 1, Set.of(id("fixture:machine")),
                List.of(item("fixture:ore", 1)), List.of(), "create",
                OptionalLong.empty(), List.of());
        PlanScoringWeights weights = weights(1, 0, 0, 0, 0, 0, 0, 5, 1);
        PlanScoringContext context = context(Set.of(id("fixture:machine")), List.of());

        List<ScoredCandidate> one = scorer.rank(List.of(z, a), weights, context);
        List<ScoredCandidate> two = scorer.rank(List.of(z, a), weights, context);

        assertThat(one).isEqualTo(two);
        assertThat(one).extracting(value -> value.candidate().candidateId())
                .containsExactly(id("planning:candidate_a"), id("planning:candidate_z"));
        assertThat(scorer.select(List.of(z, a), weights, context)).isEqualTo(a);
    }

    @Test
    void validatesWeightsAndUsesUnboundedIntegerArithmeticForLargeScores() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                weights(-1, 0, 0, 0, 0, 0, 0, 0, 1));
        assertThatIllegalArgumentException().isThrownBy(() ->
                weights(0, 0, 0, 0, 0, 0, 0, 0, 1));

        CandidatePlan candidate = candidate(
                "planning:candidate_large", 2, Set.of(id("fixture:machine")),
                List.of(item("fixture:ore", 1)), List.of(), "create",
                OptionalLong.of(Long.MAX_VALUE), List.of());
        PlanScore score = scorer.score(
                candidate,
                weights(1_000_000_000L, 0, 0, 0, 0, 0, 1_000_000_000L, 0, 1),
                context(Set.of(id("fixture:machine")), List.of()));

        assertThat(score.total()).isGreaterThan(BigInteger.valueOf(Long.MAX_VALUE));
    }

    private static CandidatePlan candidate(
            String candidateId,
            int stepCount,
            Set<ResourceId> capabilities,
            List<ProcessResource> raw,
            List<ProcessResource> owned,
            String modId,
            OptionalLong ticks,
            List<PlanningStrategyPreference> preferences) {
        ProductionGoal goal = new ProductionGoal(
                id("fixture:target"), GenericResourceType.ITEM, 1,
                Set.of(), Set.of(), Optional.empty(), MaterialConstraints.none(), preferences, Map.of());
        List<CatalogRecipe> recipes = new ArrayList<>();
        List<ResourceId> order = new ArrayList<>();
        List<CandidateQuantityConversion> conversions = new ArrayList<>();
        List<UnboundMachineNode> nodes = new ArrayList<>();
        List<ResourceId> capabilityList = capabilities.stream()
                .sorted(java.util.Comparator.comparing(ResourceId::toString)).toList();
        for (int index = 0; index < stepCount; index++) {
            ResourceId stepId = id("planning:step_" + String.format("%04d", index + 1));
            ResourceId recipeId = id("fixture:recipe_" + String.format("%04d", index + 1));
            ResourceId capability = capabilityList.get(index % capabilityList.size());
            CatalogRecipe recipe = new CatalogRecipe(
                    recipeId, id("fixture:type"),
                    List.of(item("fixture:input_" + index, 1)),
                    List.of(item("fixture:output_" + index, 1)), List.of(),
                    Set.of(capability), Set.of(GenericResourceType.ITEM),
                    OptionalLong.of(1),
                    new RecipeSource(id("fixture:adapter"), modId, modId + "=1", true));
            recipes.add(recipe);
            order.add(stepId);
            conversions.add(new CandidateQuantityConversion(
                    stepId, recipeId, 1, recipe.inputs(), recipe.outputs(), List.of()));
            nodes.add(new UnboundMachineNode(
                    id("planning:machine_step_" + String.format("%04d", index + 1)),
                    stepId, Set.of(capability)));
        }
        return new CandidatePlan(
                id(candidateId), goal, recipes, capabilities, raw, List.of(), owned,
                order, conversions, List.of(), nodes,
                new UnboundSpatialLayout(nodes.stream().map(UnboundMachineNode::nodeId).toList()),
                List.of(new PlanningEvidence(
                        id("planning:evidence_0001"), PlanningEvidenceKind.GOAL_SATISFIED,
                        goal.target(), "fixture evidence")), ticks);
    }

    private static PlanScoringWeights weights(
            long steps,
            long machines,
            long raw,
            long unavailable,
            long mods,
            long owned,
            long time,
            long unknownTime,
            long preferenceMultiplier) {
        return new PlanScoringWeights(
                steps, machines, raw, unavailable, mods, owned, time, unknownTime,
                preferenceMultiplier);
    }

    private static PlanScoringContext context(
            Set<ResourceId> capabilities,
            List<String> preferredMods) {
        return new PlanScoringContext(capabilities, preferredMods);
    }

    private static ProcessResource item(String value, long amount) {
        return new ProcessResource(id(value), GenericResourceType.ITEM, amount);
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
