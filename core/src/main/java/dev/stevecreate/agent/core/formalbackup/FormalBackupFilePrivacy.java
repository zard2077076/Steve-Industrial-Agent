package dev.stevecreate.agent.core.formalbackup;

/** Whether a backup file was transported without making its content available to callers. */
public enum FormalBackupFilePrivacy {
    ORDINARY,
    OPAQUE_PRIVATE
}
