package dev.stevecreate.agent.core.formalbackup;

import dev.stevecreate.agent.core.survey.FormalFingerprintPolicy;
import dev.stevecreate.agent.core.survey.FormalWorldReadOnlyGuard;
import java.util.Objects;

public record FormalBackupCopyRequest(
        FormalBackupDestinationPlan plan,
        FormalWorldReadOnlyGuard fingerprintGuard,
        FormalFingerprintPolicy fingerprintPolicy,
        FormalBackupQuiescenceEvidence quiescenceEvidence,
        int maxSourceFiles,
        long maxSourceBytes) {
    public FormalBackupCopyRequest {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(fingerprintGuard, "fingerprintGuard");
        Objects.requireNonNull(fingerprintPolicy, "fingerprintPolicy");
        Objects.requireNonNull(quiescenceEvidence, "quiescenceEvidence");
        if (maxSourceFiles < 1 || maxSourceFiles > 1_000_000 || maxSourceBytes < 1) {
            throw new IllegalArgumentException("source copy bounds are invalid");
        }
    }
}
