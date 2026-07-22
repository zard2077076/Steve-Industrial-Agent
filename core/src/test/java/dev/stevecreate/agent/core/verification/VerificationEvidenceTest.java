package dev.stevecreate.agent.core.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class VerificationEvidenceTest {
    @Test
    void definesExactlyTheElevenRequiredStableEvidenceKinds() {
        assertThat(VerificationEvidenceKind.values()).extracting(Enum::name).containsExactly(
                "BLOCK_PRESENT",
                "BLOCK_STATE_MATCH",
                "NETWORK_CONNECTED",
                "POWER_PRESENT",
                "INPUT_CONSUMED",
                "PROCESS_STARTED",
                "PROCESS_COMPLETED",
                "OUTPUT_PRODUCED",
                "OUTPUT_STORED",
                "NO_NEW_CRASH",
                "CUSTOM_ADAPTER_EVIDENCE");
        assertThat(VerificationEvidenceKind.values()).allSatisfy(kind -> assertThat(
                VerificationEvidenceKind.fromSerializedName(kind.serializedName()))
                .isSameAs(kind));
    }

    @Test
    void carriesSourceTargetObservedExpectedPassAndDiagnosticWithoutGameTypes() {
        EvidenceValue observed = new EvidenceValue(id("test:value/rpm"), "16.0");
        EvidenceValue expected = new EvidenceValue(id("test:value/rpm"), ">0.0");
        EvidenceDiagnostic diagnostic = new EvidenceDiagnostic(
                id("test:within_expected_range"), "Observed positive kinetic speed");
        VerificationEvidence evidence = new VerificationEvidence(
                id("test:evidence/power"),
                VerificationEvidenceKind.POWER_PRESENT,
                id("test:requirement/power"),
                id("test:step/power"),
                id("test:fake_adapter"),
                id("test:node/motor"),
                observed,
                expected,
                42,
                true,
                Optional.of(diagnostic));

        assertThat(evidence.sourceId()).isEqualTo(id("test:fake_adapter"));
        assertThat(evidence.targetId()).isEqualTo(id("test:node/motor"));
        assertThat(evidence.observedValue()).isEqualTo(observed);
        assertThat(evidence.expectedValue()).isEqualTo(expected);
        assertThat(evidence.observedTick()).isEqualTo(42);
        assertThat(evidence.passed()).isTrue();
        assertThat(evidence.diagnostic()).contains(diagnostic);
    }

    @Test
    void rejectsUnknownKindsAndUnboundedOrBlankValues() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> VerificationEvidenceKind.fromSerializedName("invented"))
                .withMessage("Unknown verification evidence kind: invented");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EvidenceValue(id("test:value/text"), " "))
                .withMessage("value must contain 1 to 1024 characters");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EvidenceDiagnostic(id("test:diagnostic"), " "))
                .withMessage("detail must contain 1 to 512 characters");
    }

    @Test
    void rejectsNegativeObservationTimeEvenWhenThePassFlagIsTrue() {
        EvidenceValue value = new EvidenceValue(id("test:value/integer"), "1");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new VerificationEvidence(
                        id("test:evidence"),
                        VerificationEvidenceKind.OUTPUT_PRODUCED,
                        id("test:requirement"),
                        id("test:step"),
                        id("test:source"),
                        id("test:target"),
                        value,
                        value,
                        -1,
                        true,
                        Optional.empty()))
                .withMessage("observedTick must not be negative");
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
