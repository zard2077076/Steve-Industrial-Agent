package dev.stevecreate.agent.core.formalbackup;

public enum FormalBackupStage {
    DESTINATION_POLICY,
    SOURCE_PREFLIGHT,
    COPY,
    MANIFEST,
    VERIFICATION,
    PUBLICATION,
    RESTORE_DRILL,
    CANDIDATE_PACKAGE,
    APPROVAL_REQUEST,
    REPORTING
}
