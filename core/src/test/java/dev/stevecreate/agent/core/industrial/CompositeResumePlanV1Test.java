package dev.stevecreate.agent.core.industrial;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Deciding where an interrupted run resumes, from the site instead of from memory.
 *
 * <p>Every observation here is built from the spec's own declared inputs rather than
 * from copied numbers, so a test cannot pass by agreeing with an assumption the
 * production code also makes.</p>
 */
class CompositeResumePlanV1Test {
    private static final CompositePlayerOrderSpecV1 SPEC =
            CompositePlayerOrderCatalogV1.COMPOSITE_01;

    @Test
    void resumesAtTheFirstStageOfASiteThatNeverStarted() {
        CompositeResumePlanV1 plan = CompositeResumePlanV1.from(SPEC, observation(0));

        assertThat(plan.decision()).isEqualTo(CompositeResumePlanV1.Decision.RESUME_AT_STAGE);
        assertThat(plan.stageIndex()).isZero();
    }

    /**
     * The case B1 exists for: the first stage ran, its output was routed downstream, and
     * the process died. The site alone says so — nothing recorded it.
     */
    @Test
    void resumesMidChainWhenMaterialHasMovedDownstream() {
        for (int stage = 1; stage < SPEC.stages().size(); stage++) {
            CompositeResumePlanV1 plan = CompositeResumePlanV1.from(SPEC, observation(stage));

            assertThat(plan.decision())
                    .as("stage " + stage)
                    .isEqualTo(CompositeResumePlanV1.Decision.RESUME_AT_STAGE);
            assertThat(plan.stageIndex()).isEqualTo(stage);
        }
    }

    /** Producing again after the output exists would run the last stage a second time. */
    @Test
    void settlesRatherThanProducingWhenTheOutputIsAlreadyThere() {
        Observed observed = new Observed();
        int last = SPEC.stages().size() - 1;
        observed.deliveries.set(last, Map.of(SPEC.stages().get(last).target(),
                SPEC.stages().get(last).targetQuantity()));

        CompositeResumePlanV1 plan = CompositeResumePlanV1.from(SPEC, observed.toObservation());

        assertThat(plan.decision()).isEqualTo(CompositeResumePlanV1.Decision.SETTLE_ONLY);
        assertThat(plan.stageIndex()).isEqualTo(-1);
    }

    /** A surplus is still a finished output; settlement already accepts at-least. */
    @Test
    void settlesOnASurplusOutputToo() {
        Observed observed = new Observed();
        int last = SPEC.stages().size() - 1;
        observed.deliveries.set(last, Map.of(SPEC.stages().get(last).target(),
                SPEC.stages().get(last).targetQuantity() + 5));

        assertThat(CompositeResumePlanV1.from(SPEC, observed.toObservation()).decision())
                .isEqualTo(CompositeResumePlanV1.Decision.SETTLE_ONLY);
    }

    @Test
    void refusesWhenMaterialIsStillInAHopper() {
        Observed observed = observedAt(0);
        observed.hoppers.set(0, Map.of(ResourceId.parse("minecraft:oak_log"), 1L));

        CompositeResumePlanV1 plan = CompositeResumePlanV1.from(SPEC, observed.toObservation());

        assertThat(plan.decision()).isEqualTo(CompositeResumePlanV1.Decision.REFUSE);
        assertThat(plan.reason()).startsWith("COMPOSITE_RESUME_MATERIAL_IN_TRANSIT");
    }

    /**
     * An empty site is the interruption that cannot be recovered: a machine took its
     * input and died before producing. Restarting from stage zero would be a guess that
     * costs the player the material twice.
     */
    @Test
    void refusesAnEmptySiteRatherThanRestartingFromTheBeginning() {
        CompositeResumePlanV1 plan =
                CompositeResumePlanV1.from(SPEC, new Observed().toObservation());

        assertThat(plan.decision()).isEqualTo(CompositeResumePlanV1.Decision.REFUSE);
        assertThat(plan.reason()).isEqualTo("COMPOSITE_RESUME_NO_STAGE_HAS_ITS_INPUT");
    }

    @Test
    void refusesWhenTwoStagesLookReady() {
        Observed observed = observedAt(0);
        int second = 1;
        observed.sources.set(second, SPEC.stages().get(second).processInputs());

        CompositeResumePlanV1 plan = CompositeResumePlanV1.from(SPEC, observed.toObservation());

        assertThat(plan.decision()).isEqualTo(CompositeResumePlanV1.Decision.REFUSE);
        assertThat(plan.reason()).startsWith("COMPOSITE_RESUME_AMBIGUOUS_PROGRESS");
    }

    /** Partial input is not a lesser form of ready; the stage would start and fail short. */
    @Test
    void refusesAStageHoldingOnlySomeOfItsInput() {
        Observed observed = new Observed();
        Map<ResourceId, Long> partial = new LinkedHashMap<>(SPEC.stages().get(0).processInputs());
        ResourceId first = partial.keySet().iterator().next();
        partial.put(first, partial.get(first) - 1);
        observed.sources.set(0, partial);

        assertThat(CompositeResumePlanV1.from(SPEC, observed.toObservation()).reason())
                .isEqualTo("COMPOSITE_RESUME_NO_STAGE_HAS_ITS_INPUT");
    }

    @Test
    void refusesAnObservationThatDoesNotDescribeThisSite() {
        CompositeResumePlanV1.Observation wrong = new CompositeResumePlanV1.Observation(
                List.of(Map.of()), List.of(Map.of()), List.of());

        assertThat(CompositeResumePlanV1.from(SPEC, wrong).reason())
                .isEqualTo("COMPOSITE_RESUME_OBSERVATION_SHAPE_MISMATCH");
    }

    private static CompositeResumePlanV1.Observation observation(int readyStage) {
        return observedAt(readyStage).toObservation();
    }

    private static Observed observedAt(int readyStage) {
        Observed observed = new Observed();
        observed.sources.set(readyStage, SPEC.stages().get(readyStage).processInputs());
        return observed;
    }

    /** An empty site, one slot per chest and hopper, that a test fills in as it needs. */
    private static final class Observed {
        private final List<Map<ResourceId, Long>> sources = new ArrayList<>();
        private final List<Map<ResourceId, Long>> deliveries = new ArrayList<>();
        private final List<Map<ResourceId, Long>> hoppers = new ArrayList<>();

        private Observed() {
            for (int index = 0; index < SPEC.stages().size(); index++) {
                sources.add(Map.of());
                deliveries.add(Map.of());
            }
            for (int index = 0; index < SPEC.stages().size() - 1; index++) {
                hoppers.add(Map.of());
            }
        }

        private CompositeResumePlanV1.Observation toObservation() {
            return new CompositeResumePlanV1.Observation(sources, deliveries, hoppers);
        }
    }
}
