package dev.stevecreate.agent.core.industrial;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Where an interrupted composite run can pick up, decided from what is physically in the
 * site's chests rather than from anything the previous process remembered.
 *
 * <p>A restart loses the orchestrator's stage cursor, and serialising it was the obvious
 * fix. It is the wrong one: the cursor is a claim about the world, and a claim restored
 * from disk can disagree with the world it describes. The run itself never trusted a
 * remembered cursor either — a stage is judged complete by counting its delivery chest,
 * not by a flag — so the same evidence that advances a live run can restart a dead one.
 *
 * <p>Material in a linear site is always somewhere observable: in a stage's source chest,
 * in its delivery chest, or in the hopper between them. Exactly one stage having its full
 * input present is what a cleanly interrupted run looks like, because completing a stage
 * moves its output downstream and empties what it came from.
 *
 * <p>Every other reading is refused rather than guessed at. Material in a hopper is in
 * transit and belongs to neither side; no stage ready means the interruption caught a
 * machine mid-recipe with its input already consumed; two stages ready means the site is
 * not in a state this model describes. Refusing costs the player a cancellation and a
 * refund, which {@code cancelPausedOrder} already gives them. Guessing wrong reruns a
 * stage that already ran, consuming material twice — the failure the ledger exists to
 * make impossible.
 */
public record CompositeResumePlanV1(Decision decision, int stageIndex, String reason) {

    public enum Decision {
        /** Restart production from {@link #stageIndex}; everything before it is done. */
        RESUME_AT_STAGE,
        /** The final output is already in the delivery chest; only settlement remains. */
        SETTLE_ONLY,
        /** The site is not in a state resumption can be justified from. */
        REFUSE
    }

    public CompositeResumePlanV1 {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(reason, "reason");
        if (decision == Decision.RESUME_AT_STAGE && stageIndex < 0) {
            throw new IllegalArgumentException("a resume stage index must exist");
        }
        if (decision != Decision.RESUME_AT_STAGE && stageIndex != -1) {
            throw new IllegalArgumentException("only a resumed run has a stage index");
        }
    }

    /**
     * What the site physically holds, one entry per stage and one per route.
     *
     * @param stageSources what is in each stage's source chest
     * @param stageDeliveries what is in each stage's delivery chest
     * @param routeHoppers what is in each inter-stage hopper, in transit and owned by
     *     neither stage
     */
    public record Observation(
            List<Map<ResourceId, Long>> stageSources,
            List<Map<ResourceId, Long>> stageDeliveries,
            List<Map<ResourceId, Long>> routeHoppers) {
        public Observation {
            stageSources = List.copyOf(Objects.requireNonNull(stageSources, "stageSources"));
            stageDeliveries = List.copyOf(Objects.requireNonNull(stageDeliveries, "stageDeliveries"));
            routeHoppers = List.copyOf(Objects.requireNonNull(routeHoppers, "routeHoppers"));
        }
    }

    public static CompositeResumePlanV1 from(
            CompositePlayerOrderSpecV1 spec, Observation observation) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(observation, "observation");
        List<CompositePlayerOrderSpecV1.StageSpec> stages = spec.stages();
        if (observation.stageSources().size() != stages.size()
                || observation.stageDeliveries().size() != stages.size()
                || observation.routeHoppers().size() != stages.size() - 1) {
            return refuse("COMPOSITE_RESUME_OBSERVATION_SHAPE_MISMATCH");
        }

        // Settled output first. A run interrupted after its last stage finished but
        // before settlement has nothing left to produce, and resuming production there
        // would run the final stage a second time.
        CompositePlayerOrderSpecV1.StageSpec last = stages.get(stages.size() - 1);
        long delivered = observation.stageDeliveries().get(stages.size() - 1)
                .getOrDefault(last.target(), 0L);
        if (delivered >= last.targetQuantity()) {
            return new CompositeResumePlanV1(Decision.SETTLE_ONLY, -1,
                    "COMPOSITE_RESUME_OUTPUT_ALREADY_PRESENT");
        }

        // Material caught between two stages belongs to neither. Handing it to the
        // downstream stage would credit it with an input it has not received; leaving it
        // would strand it. Neither is a decision this function is entitled to make.
        for (int index = 0; index < observation.routeHoppers().size(); index++) {
            if (observation.routeHoppers().get(index).values().stream()
                    .anyMatch(quantity -> quantity > 0)) {
                return refuse("COMPOSITE_RESUME_MATERIAL_IN_TRANSIT:" + index);
            }
        }

        int ready = -1;
        int readyCount = 0;
        for (int index = 0; index < stages.size(); index++) {
            if (inputSatisfied(stages.get(index), observation.stageSources().get(index))) {
                readyCount++;
                ready = index;
            }
        }
        if (readyCount == 0) {
            // The likely cause is an interruption during processing: the machine had
            // taken its input and had not yet produced. That material is genuinely gone
            // and no reading of the site can bring it back, so the honest answer is that
            // this run cannot be resumed, not that it should restart from the beginning.
            return refuse("COMPOSITE_RESUME_NO_STAGE_HAS_ITS_INPUT");
        }
        if (readyCount > 1) {
            return refuse("COMPOSITE_RESUME_AMBIGUOUS_PROGRESS:" + readyCount);
        }
        return new CompositeResumePlanV1(Decision.RESUME_AT_STAGE, ready,
                "COMPOSITE_RESUME_FROM_STAGE:" + ready);
    }

    /**
     * A stage is ready when every input it declared is present in full. Partial input is
     * not a lesser form of ready: the stage would start, consume what is there and fail
     * short, which is a worse outcome than refusing to resume.
     */
    private static boolean inputSatisfied(
            CompositePlayerOrderSpecV1.StageSpec stage, Map<ResourceId, Long> source) {
        if (stage.processInputs().isEmpty()) return false;
        for (Map.Entry<ResourceId, Long> required : stage.processInputs().entrySet()) {
            if (source.getOrDefault(required.getKey(), 0L) < required.getValue()) return false;
        }
        return true;
    }

    private static CompositeResumePlanV1 refuse(String reason) {
        return new CompositeResumePlanV1(Decision.REFUSE, -1, reason);
    }
}
