# Backup safety foundation

PW-09 supports actual backup and restore only for an explicitly approved isolated test world. `BackupPathPolicy` binds canonical isolated, backup and forbidden roots. The backup root itself must stay under the isolated root; source must stay outside the backup subtree; targets must stay under the backup root; symbolic links in a manifest are rejected.

`BackupPlanner` inventories every file, records relative path, byte size and SHA-256, computes the canonical aggregate manifest hash, estimates size plus headroom and reads target filesystem capacity. `BackupPlan` also fixes staging/atomic rename, quiesced consistency, restore fingerprint checks, retention, cleanup-on-failure, explicit TEST_ONLY approval and journal backup identity. `BackupVerifier` returns typed failures for incomplete/corrupt manifest, capacity, path, strategy, approval or journal problems. A non-isolated environment returns before any source/target path lookup.

`IsolatedBackupService` rechecks current source manifest and live free space, copies to a verified staging directory, rehashes staging and publishes only through atomic rename. Restore first rehashes the backup, removes unexpected isolated-fixture files without following symbolic links, restores the manifest files and rehashes the source. Unknown staging paths are deliberately not deleted.

Acceptance uses a real temporary directory containing `level.dat` and a region file: create backup, modify the source, restore, then compare the full manifest and journal backup identity. Separate cases prove incomplete interrupted backup, insufficient space, corrupt hash, source/target escape and formal environment cannot be accepted. No formal external world was enumerated or backed up. Formal backup remains a future separately approved stage.

PW-11 accepts this evidence only when both the exact backup plan/manifest and restore drill match the current isolated request. Backup success is one of twenty-five independent checks and cannot compensate for a stale preview, denied permission, missing approval, failed write guard or non-isolated environment.

PW-12 never creates or verifies a backup. Every read-only command view carries missing-backup evidence and BLOCKED readiness, and the command grammar contains no backup, restore or approval argument. This keeps the actual PW-09 isolated backup service behind its existing explicit path and approval boundary.
