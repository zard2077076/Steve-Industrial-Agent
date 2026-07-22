package dev.stevecreate.agent.core.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BoundedExecutionStepTest {
    @Test
    void constructsAnImmutableTypedStepWithoutExecutableCallbacks() {
        List<StepCondition> preconditions = new ArrayList<>(List.of(condition("loaded")));
        List<StepCondition> successConditions = new ArrayList<>(List.of(condition("placed")));
        List<StepCondition> failureConditions = new ArrayList<>(List.of(condition("blocked")));
        Set<ResourceId> evidence = new LinkedHashSet<>(Set.of(id("test:block_present")));
        StepActionDescriptor action = action("place_block");
        StepActionDescriptor rollback = action("restore_block");

        GenericExecutionStep step = new BoundedExecutionStep(
                id("test:step/place"),
                GenericExecutionPhase.BUILD,
                preconditions,
                action,
                successConditions,
                failureConditions,
                40,
                new RetryPolicy(2, 1, Set.of(id("test:dirty_state"))),
                true,
                Optional.of(rollback),
                evidence);

        preconditions.clear();
        successConditions.clear();
        failureConditions.clear();
        evidence.clear();

        assertThat(step.stepId()).isEqualTo(id("test:step/place"));
        assertThat(step.phase()).isEqualTo(GenericExecutionPhase.BUILD);
        assertThat(step.preconditions()).hasSize(1);
        assertThat(step.action()).isSameAs(action);
        assertThat(step.rollbackAction()).contains(rollback);
        assertThat(step.retryPolicy().allowsRetry()).isTrue();
        assertThat(step.requiredEvidence()).containsExactly(id("test:block_present"));
        assertThatThrownBy(() -> step.preconditions().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> step.requiredEvidence().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsMissingOutcomeConditionsEvidenceAndDuplicateConditionIds() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> step(List.of(), List.of(condition("failed")), Set.of(id("test:evidence"))))
                .withMessage("successConditions count must be between 1 and 32");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> step(List.of(condition("done")), List.of(), Set.of(id("test:evidence"))))
                .withMessage("failureConditions count must be between 1 and 32");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> step(List.of(condition("done")), List.of(condition("failed")), Set.of()))
                .withMessage("requiredEvidence count must be between 1 and 64");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new BoundedExecutionStep(
                        id("test:duplicate"),
                        GenericExecutionPhase.VERIFY,
                        List.of(condition("same")),
                        action("verify"),
                        List.of(condition("same")),
                        List.of(condition("failed")),
                        20,
                        RetryPolicy.NO_RETRY,
                        false,
                        Optional.empty(),
                        Set.of(id("test:evidence"))))
                .withMessage("Duplicate condition id in execution step: test:same");
    }

    @Test
    void boundsConditionCountsAndTimeouts() {
        List<StepCondition> tooMany = new ArrayList<>();
        for (int index = 0; index <= BoundedExecutionStep.MAX_CONDITIONS_PER_KIND; index++) {
            tooMany.add(condition("condition_" + index));
        }
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new BoundedExecutionStep(
                        id("test:too_many"),
                        GenericExecutionPhase.PREPARE,
                        tooMany,
                        action("prepare"),
                        List.of(condition("done")),
                        List.of(condition("failed")),
                        20,
                        RetryPolicy.NO_RETRY,
                        true,
                        Optional.empty(),
                        Set.of(id("test:evidence"))))
                .withMessage("preconditions count must be between 0 and 32");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new BoundedExecutionStep(
                        id("test:timeout"),
                        GenericExecutionPhase.PROCESS,
                        List.of(),
                        action("process"),
                        List.of(condition("done")),
                        List.of(condition("failed")),
                        0,
                        RetryPolicy.NO_RETRY,
                        true,
                        Optional.empty(),
                        Set.of(id("test:evidence"))))
                .withMessage("timeoutTicks must be between 1 and 2400");
    }

    @Test
    void validatesRetryBoundsAndTypedFailureAllowlist() {
        assertThat(RetryPolicy.NO_RETRY.allowsRetry()).isFalse();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RetryPolicy(0, 0, Set.of()))
                .withMessage("maximumAttempts must be between 1 and 16");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RetryPolicy(2, 0, Set.of()))
                .withMessage("retryableFailures count must be between 1 and 64");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RetryPolicy(1, 1, Set.of()))
                .withMessage("A no-retry policy cannot have backoff ticks");
    }

    @Test
    void defensivelyCopiesAndBoundsActionAndConditionParameters() {
        Map<ResourceId, String> actionParameters = new LinkedHashMap<>(Map.of(
                id("test:target"), "node/press"));
        StepActionDescriptor action = new StepActionDescriptor(
                id("test:handler"), id("test:operate"), actionParameters);
        actionParameters.clear();
        assertThat(action.parameters()).containsEntry(id("test:target"), "node/press");
        assertThatThrownBy(() -> action.parameters().clear())
                .isInstanceOf(UnsupportedOperationException.class);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StepCondition(
                        id("test:condition"),
                        id("test:evaluator"),
                        id("test:type"),
                        Map.of(id("test:value"), " ")))
                .withMessageContaining("must contain 1 to 256 characters");
    }

    @Test
    void executionPhaseNamesAreStableAndRejectUnknownInput() {
        assertThat(GenericExecutionPhase.values())
                .allSatisfy(phase -> assertThat(
                        GenericExecutionPhase.fromSerializedName(phase.serializedName()))
                        .isSameAs(phase));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> GenericExecutionPhase.fromSerializedName("arbitrary_code"))
                .withMessage("Unknown generic execution phase: arbitrary_code");
    }

    private static BoundedExecutionStep step(
            List<StepCondition> successConditions,
            List<StepCondition> failureConditions,
            Set<ResourceId> evidence) {
        return new BoundedExecutionStep(
                id("test:step"),
                GenericExecutionPhase.VERIFY,
                List.of(),
                action("verify"),
                successConditions,
                failureConditions,
                20,
                RetryPolicy.NO_RETRY,
                false,
                Optional.empty(),
                evidence);
    }

    private static StepCondition condition(String path) {
        return new StepCondition(
                id("test:" + path),
                id("test:evaluator"),
                id("test:condition_type"),
                Map.of());
    }

    private static StepActionDescriptor action(String path) {
        return new StepActionDescriptor(
                id("test:handler"), id("test:" + path), Map.of());
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
