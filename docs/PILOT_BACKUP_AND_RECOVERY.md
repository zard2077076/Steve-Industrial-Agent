# Pilot backup and recovery

## Portable backup

1. In the marked test world run `/industrialagent backup prepare`.
2. Save and fully close Minecraft.
3. Run the bundled helper with the exact request path printed by the command:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\Complete-IndustrialAgentBackup.ps1 -RequestFile "<requestFile>"
```

4. Require `PORTABLE_BACKUP_COMPLETE`.
5. Reopen the same instance/world and run `/industrialagent backup verify`.

The request binds schema, test instance, world identity, marker generation,
backup root and important roots. The helper refuses a running game, locked world,
path escape/reparse, repository backup root, formal-root overlap, insufficient
space and overwrite. It copies to a unique staging record, hashes every eligible
file, performs a separate restore drill, proves equal source pre/post manifests,
atomically publishes and never uploads. The restarted JAR reparses and rehashes
both trees; stale marker/world evidence is rejected.

## Recovery

`pilot recovery-status` reports the persisted checkpoint. `pilot resume` first
rechecks config, marker, backup, world/dimension/owner/region/preview/runtime,
definition and journal, then rescans the real world. BUILD and pre-resource
CONNECT may resume without duplicate placement/input/output. Uncertain PROCESS
fails closed; VERIFY can only finalize idempotently. Never alter the selected
region between save and resume, and never restore over a live world.
