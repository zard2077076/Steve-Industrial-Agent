# Formal world backup acceptance

Status: **FB-07 PASS** on 2026-07-20. This record authorizes and describes only the completed external backup. It grants no restore-to-source, candidate-selection, approval or construction authority.

## Accepted backup

- World identity: `world:ded38fd9f680d10751612d7b0d95e9a14c228918a8e8e4b5daf308a20762dcb3`.
- Source aggregate before copy: 216 files and 214,122,809 bytes.
- Source fingerprint before and after: `00571b934b91477a3bbc58b30933c121ea09a4848ade76c89ea4ad636a834a34` (equal).
- Backup identity: `formal-backup:dff7fdad9d0884f46c078d84221209f0480d80207f1f561bd3d633dd9ac3deed`.
- Published backup: ignored `work/formal-backups/` storage outside the formal platform root; the immutable child is recorded in the ignored acceptance report.
- Manifest SHA-256: `0ded5c6914ac9cd30d47c28bd3189241f6c3fbc616544ebb1fa6fc472dc7b46c`.
- Copied aggregate: 215 files and 214,122,806 bytes. One three-byte root `session.lock` was excluded by policy.
- Capacity gate: 750,993,721 bytes required, including a 536,870,912-byte safety margin; 317,303,128,064 bytes usable at preflight.

The transaction used a fresh quiescence observation, a unique reparse-safe staging sibling, per-file size/mtime/SHA-256 verification and same-filesystem atomic publication. Player-linked files were transported as opaque bytes; their names, hashes and semantic contents are not published here. The backup is local, ignored by Git, not uploaded, not overwritten and not a cloud-retention claim.

## Evidence and boundaries

- Main log: `work/logs/formal-backup-acceptance-20260720-141225.log`.
- Aggregate report: `work/formal-backup-acceptance/fb07-20260720-141225/acceptance.json`.
- Accepted markers: `FB07_PREFLIGHT_PASS`, `FB07_ACCEPTANCE_PASS` and the subsequent `FB07_POST_PASS` process/session-lock check.
- Result flags: `backupValid=true`, `formalWorldWrite=false`, `sessionLockCreatedOrModified=false`, `inventoryContentsRead=false`, `minecraftOrForgeStarted=false`, `formalExecutionAllowed=false`.

Verification must always start from the immutable completed marker plus canonical manifest and rehash every published file. A plan ID, directory presence or stale prior verification is never sufficient.
