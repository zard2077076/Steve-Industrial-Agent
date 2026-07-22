package dev.stevecreate.agent.core.formalbackup;

import dev.stevecreate.agent.core.survey.FormalWorldIdentity;
import java.nio.file.Path;
import java.util.Objects;

public record FormalBackupVerificationRequest(
        Path formalPlatformRoot,
        Path repositoryRoot,
        Path backupRoot,
        Path backupTarget,
        FormalWorldIdentity expectedWorldIdentity,
        FormalBackupDestinationPlan expectedPlan) {
    public FormalBackupVerificationRequest {
        Objects.requireNonNull(formalPlatformRoot, "formalPlatformRoot");
        Objects.requireNonNull(repositoryRoot, "repositoryRoot");
        Objects.requireNonNull(backupRoot, "backupRoot");
        Objects.requireNonNull(backupTarget, "backupTarget");
        Objects.requireNonNull(expectedWorldIdentity, "expectedWorldIdentity");
        Objects.requireNonNull(expectedPlan, "expectedPlan");
        if (!expectedPlan.worldIdentity().equals(expectedWorldIdentity.value())) {
            throw new IllegalArgumentException("expected plan and world identity disagree");
        }
    }
}
