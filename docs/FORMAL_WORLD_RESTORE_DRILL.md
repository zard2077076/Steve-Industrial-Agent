# Formal world restore-drill acceptance

Status: **FB-07 PASS** on 2026-07-20. This is recoverability evidence for a disposable repository-owned copy, not authorization to replace or modify the formal source.

## Accepted drill

- Source backup: `formal-backup:dff7fdad9d0884f46c078d84221209f0480d80207f1f561bd3d633dd9ac3deed`.
- Restore-drill identity: `restore-drill:63c0fdb898a76184ee089d3c78083a8fdc81a53e0e7baf29b30de34994afeb93`.
- Target: a new immutable child below ignored `work/formal-restore-drills/`, with its exact child recorded in the ignored acceptance report.
- Verification: the completed backup was independently reparsed and rehashed; every manifest path, byte count, mtime and SHA-256 matched before and after atomic publication.
- Identity readback: offline `level.dat` parsing returned the exact backup-bound `WorldIdentity`.
- Runtime isolation: no Minecraft, Forge or Java game process was launched, and the formal source was not used as the restore target.

Opaque player-linked files were copied only as manifest-bound bytes and were not parsed or summarized. The retained drill output is ignored and may contain private save bytes; it must not be committed or treated as an inventory source.

Evidence is in `work/formal-backup-acceptance/fb07-20260720-141225/acceptance.json` and `work/logs/formal-backup-acceptance-20260720-141225.log`. The accepted aggregate flags are `backupValid=true`, `restoreDrillPass=true`, `formalWorldWrite=false` and `externalMutation=false`.

Any future restore to the formal source requires a new, explicit destructive-restore authorization, a freshly verified backup, a new quiescence gate and a separately reviewed rollback procedure. None exists in this milestone.
