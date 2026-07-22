package dev.stevecreate.agent.core.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.plan.BeltPressPlan;
import dev.stevecreate.agent.core.plan.WaterWheelMillstonePlan;
import dev.stevecreate.agent.core.process.GenericProcessSpec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GenericVerificationRuleTest {
    private static final ResourceId STEP = id("test:step/process");
    private static final ResourceId SOURCE = id("test:adapter");
    private static final ResourceId TARGET = id("test:machine/output");

    @Test
    void passesWithCompletePhysicalEvidenceWhileOptionalEvidenceIsAbsent() {
        List<EvidenceRequirement> required = List.of(
                requirement("test:requirement/input", VerificationEvidenceKind.INPUT_CONSUMED),
                requirement("test:requirement/process", VerificationEvidenceKind.PROCESS_COMPLETED),
                requirement("test:requirement/output", VerificationEvidenceKind.OUTPUT_STORED));
        GenericVerificationRule rule = new GenericVerificationRule(
                required,
                List.of(requirement(
                        "test:requirement/no_crash", VerificationEvidenceKind.NO_NEW_CRASH)),
                20,
                false);

        GenericVerificationResult result = rule.evaluate(
                List.of(
                        evidence("input", required.get(0), 5, true, Optional.empty()),
                        evidence("process", required.get(1), 5, true, Optional.empty()),
                        evidence("output", required.get(2), 5, true, Optional.empty())),
                STEP,
                0,
                5);

        assertThat(result.status()).isEqualTo(GenericVerificationStatus.PASSED);
        assertThat(result.satisfiedRequiredEvidence()).containsExactlyInAnyOrder(
                id("test:requirement/input"),
                id("test:requirement/process"),
                id("test:requirement/output"));
        assertThat(result.missingRequiredEvidence()).isEmpty();
        assertThat(result.observedOptionalEvidence()).isEmpty();
        assertThat(result.failure()).isEmpty();
        assertThat(result.consideredEvidence())
                .allSatisfy(value -> {
                    assertThat(value.observedValue().value()).isEqualTo("observed");
                    assertThat(value.expectedValue().value()).isEqualTo("expected");
                });
    }

    @Test
    void usesOnlyTheLatestRequiredObservationWithoutLosingItsValues() {
        EvidenceRequirement requirement = requirement(
                "test:requirement/output", VerificationEvidenceKind.OUTPUT_PRODUCED);
        VerificationEvidence oldFailure = evidence(
                "old_failure",
                requirement,
                2,
                false,
                Optional.of(new EvidenceDiagnostic(
                        id("test:not_ready"), "Output was not present yet")));
        VerificationEvidence finalSuccess = evidence(
                "final_success", requirement, 4, true, Optional.empty());
        GenericVerificationRule rule = new GenericVerificationRule(
                List.of(requirement), List.of(), 20, false);

        GenericVerificationResult result = rule.evaluate(
                List.of(oldFailure, finalSuccess), STEP, 0, 4);

        assertThat(result.status()).isEqualTo(GenericVerificationStatus.PASSED);
        assertThat(result.consideredEvidence()).containsExactly(finalSuccess);
        assertThat(result.consideredEvidence().get(0).observedValue().value())
                .isEqualTo("observed");
        assertThat(result.consideredEvidence().get(0).expectedValue().value())
                .isEqualTo("expected");
    }

    @Test
    void returnsTypedFailureForRejectedPhysicalEvidence() {
        EvidenceRequirement requirement = requirement(
                "test:requirement/input", VerificationEvidenceKind.INPUT_CONSUMED);
        VerificationEvidence rejected = evidence(
                "rejected",
                requirement,
                3,
                false,
                Optional.of(new EvidenceDiagnostic(
                        id("test:input_remaining"), "One input item remained")));
        GenericVerificationRule rule = new GenericVerificationRule(
                List.of(requirement), List.of(), 20, false);

        GenericVerificationResult result = rule.evaluate(
                List.of(rejected), STEP, 0, 3);

        assertThat(result.status()).isEqualTo(GenericVerificationStatus.FAILED);
        assertThat(result.failure()).get()
                .extracting(VerificationRuleFailure::code)
                .isEqualTo(VerificationRuleFailureCode.EVIDENCE_REJECTED);
        assertThat(result.failure().orElseThrow().requirementId())
                .contains(requirement.requirementId());
        assertThat(result.failure().orElseThrow().detail())
                .isEqualTo("One input item remained");
        assertThat(result.consideredEvidence()).containsExactly(rejected);
    }

    @Test
    void waitsForMissingEvidenceThenReturnsATypedTimeoutAtTheBoundary() {
        EvidenceRequirement input = requirement(
                "test:requirement/input", VerificationEvidenceKind.INPUT_CONSUMED);
        EvidenceRequirement output = requirement(
                "test:requirement/output", VerificationEvidenceKind.OUTPUT_PRODUCED);
        GenericVerificationRule rule = new GenericVerificationRule(
                List.of(input, output), List.of(), 10, false);

        GenericVerificationResult pending = rule.evaluate(List.of(), STEP, 4, 13);
        GenericVerificationResult timedOut = rule.evaluate(List.of(), STEP, 4, 14);

        assertThat(pending.status()).isEqualTo(GenericVerificationStatus.PENDING);
        assertThat(pending.failure()).isEmpty();
        assertThat(pending.missingRequiredEvidence()).containsExactlyInAnyOrder(
                input.requirementId(), output.requirementId());
        assertThat(timedOut.status()).isEqualTo(GenericVerificationStatus.TIMED_OUT);
        assertThat(timedOut.failure()).get()
                .extracting(VerificationRuleFailure::code)
                .isEqualTo(VerificationRuleFailureCode.REQUIRED_EVIDENCE_TIMEOUT);
        assertThat(timedOut.failure().orElseThrow().detail())
                .contains("after 10 ticks", input.requirementId().toString(), output.requirementId().toString());
    }

    @Test
    void enforcesAdapterSpecificEvidencePermissionAtConstructionAndEvaluation() {
        EvidenceRequirement custom = requirement(
                "test:requirement/custom", VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new GenericVerificationRule(
                        List.of(custom), List.of(), 10, false))
                .withMessageContaining("adapterSpecificEvidenceAllowed=true");

        GenericVerificationRule allowed = new GenericVerificationRule(
                List.of(custom), List.of(), 10, true);
        assertThat(allowed.evaluate(
                        List.of(evidence("custom", custom, 1, true, Optional.empty())),
                        STEP,
                        0,
                        1)
                .status()).isEqualTo(GenericVerificationStatus.PASSED);

        EvidenceRequirement generic = requirement(
                "test:requirement/generic", VerificationEvidenceKind.OUTPUT_PRODUCED);
        GenericVerificationRule disallowed = new GenericVerificationRule(
                List.of(generic), List.of(), 10, false);
        VerificationEvidence unexpectedCustom = evidence(
                "unexpected_custom",
                new EvidenceRequirement(
                        generic.requirementId(),
                        VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE,
                        Optional.empty(),
                        Optional.empty()),
                1,
                true,
                Optional.empty());
        assertThat(disallowed.evaluate(List.of(unexpectedCustom), STEP, 0, 1)
                        .failure().orElseThrow().code())
                .isEqualTo(VerificationRuleFailureCode.CUSTOM_ADAPTER_EVIDENCE_NOT_ALLOWED);
    }

    @Test
    void returnsTypedFailuresForKindSourceAndTargetMismatches() {
        EvidenceRequirement constrained = EvidenceRequirement.fromSourceToTarget(
                id("test:requirement/output"),
                VerificationEvidenceKind.OUTPUT_STORED,
                SOURCE,
                TARGET);
        GenericVerificationRule rule = new GenericVerificationRule(
                List.of(constrained), List.of(), 10, false);

        VerificationEvidence wrongKind = evidence(
                "wrong_kind",
                new EvidenceRequirement(
                        constrained.requirementId(),
                        VerificationEvidenceKind.OUTPUT_PRODUCED,
                        Optional.of(SOURCE),
                        Optional.of(TARGET)),
                1,
                true,
                Optional.empty());
        VerificationEvidence wrongSource = evidenceFrom(
                "wrong_source", constrained, id("test:other_adapter"), TARGET, 1, true);
        VerificationEvidence wrongTarget = evidenceFrom(
                "wrong_target", constrained, SOURCE, id("test:other_target"), 1, true);

        assertThat(failureCode(rule, wrongKind))
                .isEqualTo(VerificationRuleFailureCode.EVIDENCE_KIND_MISMATCH);
        assertThat(failureCode(rule, wrongSource))
                .isEqualTo(VerificationRuleFailureCode.EVIDENCE_SOURCE_MISMATCH);
        assertThat(failureCode(rule, wrongTarget))
                .isEqualTo(VerificationRuleFailureCode.EVIDENCE_TARGET_MISMATCH);
    }

    @Test
    void processRulesCannotOmitAnyC03OrC04PhysicalRequirement() {
        GenericProcessSpec milling = WaterWheelMillstonePlan.at(
                new BlockPos3i(0, 0, 0)).process().genericSpec();
        GenericProcessSpec pressing = BeltPressPlan.at(
                new BlockPos3i(0, 0, 0)).process().genericSpec();

        List<EvidenceRequirement> incompleteMilling = requirementsFor(milling);
        incompleteMilling.removeIf(value -> value.requirementId().equals(
                id("create:evidence/millstone_inventory_output")));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> GenericVerificationRule.forProcess(
                        milling, incompleteMilling, List.of(), false))
                .withMessageContaining("create:evidence/millstone_inventory_output");

        List<EvidenceRequirement> incompletePressing = requirementsFor(pressing);
        incompletePressing.removeIf(value -> value.requirementId().equals(
                id("create:evidence/press_cycle_observed")));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> GenericVerificationRule.forProcess(
                        pressing, incompletePressing, List.of(), false))
                .withMessageContaining("create:evidence/press_cycle_observed");

        GenericVerificationRule millingRule = processRule(milling);
        GenericVerificationRule pressingRule = processRule(pressing);
        assertThat(millingRule.requiredEvidence())
                .extracting(EvidenceRequirement::requirementId)
                .containsExactlyInAnyOrderElementsOf(milling.requiredCompletionEvidence());
        assertThat(pressingRule.requiredEvidence())
                .extracting(EvidenceRequirement::requirementId)
                .containsExactlyInAnyOrderElementsOf(pressing.requiredCompletionEvidence());
        assertThat(millingRule.timeoutTicks()).isEqualTo(milling.maximumWaitTicks());
        assertThat(pressingRule.timeoutTicks()).isEqualTo(pressing.maximumWaitTicks());
        assertThat(evaluateComplete(millingRule).status())
                .isEqualTo(GenericVerificationStatus.PASSED);
        assertThat(evaluateComplete(pressingRule).status())
                .isEqualTo(GenericVerificationStatus.PASSED);
    }

    @Test
    void boundsAndDefensivelyCopiesRuleCollections() {
        List<EvidenceRequirement> required = new ArrayList<>(List.of(requirement(
                "test:requirement/output", VerificationEvidenceKind.OUTPUT_PRODUCED)));
        GenericVerificationRule rule = new GenericVerificationRule(
                required, List.of(), 10, false);
        required.clear();

        assertThat(rule.requiredEvidence()).hasSize(1);
        assertThatThrownBy(() -> rule.requiredEvidence().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new GenericVerificationRule(
                        List.of(), List.of(), 10, false))
                .withMessage("requiredEvidence count must be between 1 and 64");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new GenericVerificationRule(
                        rule.requiredEvidence(), rule.requiredEvidence(), 10, false))
                .withMessageContaining("overlaps required evidence");
    }

    private static GenericVerificationResult evaluateComplete(GenericVerificationRule rule) {
        List<VerificationEvidence> evidence = new ArrayList<>();
        int index = 0;
        for (EvidenceRequirement requirement : rule.requiredEvidence()) {
            evidence.add(evidence(
                    "complete_" + index++, requirement, 5, true, Optional.empty()));
        }
        return rule.evaluate(evidence, STEP, 0, 5);
    }

    private static GenericVerificationRule processRule(GenericProcessSpec processSpec) {
        return GenericVerificationRule.forProcess(
                processSpec, requirementsFor(processSpec), List.of(), false);
    }

    private static List<EvidenceRequirement> requirementsFor(GenericProcessSpec processSpec) {
        return processSpec.requiredCompletionEvidence().stream()
                .sorted(Comparator.comparing(ResourceId::toString))
                .map(value -> EvidenceRequirement.anySourceAndTarget(value, kindFor(value)))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private static VerificationEvidenceKind kindFor(ResourceId requirementId) {
        return switch (requirementId.path()) {
            case "evidence/input_consumed" -> VerificationEvidenceKind.INPUT_CONSUMED;
            case "evidence/process_completed", "evidence/press_cycle_observed" ->
                    VerificationEvidenceKind.PROCESS_COMPLETED;
            case "evidence/output_produced", "evidence/millstone_inventory_output" ->
                    VerificationEvidenceKind.OUTPUT_PRODUCED;
            case "evidence/belt_input_observed" -> VerificationEvidenceKind.PROCESS_STARTED;
            case "evidence/chest_output_observed" -> VerificationEvidenceKind.OUTPUT_STORED;
            default -> throw new IllegalArgumentException(
                    "No test evidence kind for " + requirementId);
        };
    }

    private static VerificationRuleFailureCode failureCode(
            GenericVerificationRule rule,
            VerificationEvidence evidence) {
        return rule.evaluate(List.of(evidence), STEP, 0, 1)
                .failure().orElseThrow().code();
    }

    private static EvidenceRequirement requirement(
            String requirementId,
            VerificationEvidenceKind kind) {
        return EvidenceRequirement.anySourceAndTarget(id(requirementId), kind);
    }

    private static VerificationEvidence evidence(
            String evidenceName,
            EvidenceRequirement requirement,
            long tick,
            boolean passed,
            Optional<EvidenceDiagnostic> diagnostic) {
        return evidenceFrom(evidenceName, requirement, SOURCE, TARGET, tick, passed, diagnostic);
    }

    private static VerificationEvidence evidenceFrom(
            String evidenceName,
            EvidenceRequirement requirement,
            ResourceId source,
            ResourceId target,
            long tick,
            boolean passed) {
        return evidenceFrom(
                evidenceName, requirement, source, target, tick, passed, Optional.empty());
    }

    private static VerificationEvidence evidenceFrom(
            String evidenceName,
            EvidenceRequirement requirement,
            ResourceId source,
            ResourceId target,
            long tick,
            boolean passed,
            Optional<EvidenceDiagnostic> diagnostic) {
        return new VerificationEvidence(
                id("test:evidence/" + evidenceName),
                requirement.kind(),
                requirement.requirementId(),
                STEP,
                source,
                target,
                new EvidenceValue(id("test:value/observation"), "observed"),
                new EvidenceValue(id("test:value/expectation"), "expected"),
                tick,
                passed,
                diagnostic);
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
