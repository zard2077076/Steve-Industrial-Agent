package dev.stevecreate.agent.core.deployment;

/** Declares whether a backup root is nested under or disjoint from the isolated source root. */
public enum BackupRootRelationship {
    WITHIN_ISOLATED_ROOT,
    EXTERNAL_DISJOINT
}
