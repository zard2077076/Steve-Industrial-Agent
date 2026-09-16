# Pilot commands

All commands are player-only and available only in the exact marked repository
instance/world. They never accept a path or free-text construction request.

```text
/industrialagent pilot region here <x> <z> <y>
/industrialagent pilot region pos1
/industrialagent pilot region pos2
/industrialagent pilot region preview
/industrialagent pilot region confirm
/industrialagent pilot region clear
/industrialagent pilot region status
/industrialagent pilot readiness <resource> <quantity> [orientation]
/industrialagent pilot dry-run <resource> <quantity> [orientation]
/industrialagent pilot start <resource> <quantity>
/industrialagent pilot status
/industrialagent pilot cancel
/industrialagent pilot cleanup preview
/industrialagent pilot cleanup
/industrialagent pilot recovery-status
/industrialagent pilot resume
/industrialagent pilot hold build
/industrialagent pilot hold connect
/industrialagent pilot hold process
/industrialagent pilot hold verify
/industrialagent pilot continue
```

The enabled production targets are exactly `minecraft:gravel 3` and
`create:iron_sheet 2`. Readiness binds the current confirmation, 25 deployment
checks, 16 execution checks, backup manifest, permission, budget, test-only
materials/power and the formal-world hard stop. Dry-run creates no session and
does not consume or mutate anything. Start requires the exact dry-run and uses a
three-second cancellation window.

Status, cancel and cleanup bind to the active/history session's original world,
dimension, region, preview, journal and backup rather than to a post-mutation
world fingerprint. Cleanup preview is mandatory. It removes only journal-owned
positions whose current state is still the recorded state; the sole normalized
exception is Create's runtime-managed belt `part` property. Unknown drift,
direction changes and non-session blocks remain hard refusals.

`hold <phase>` is an acceptance/debug control that pauses once the requested
visible phase is reached; `continue` releases an in-memory hold. Save-to-title
or shutdown persists the checkpoint. After re-entry, `recovery-status` reports
the actual stored stage and safety mode. `resume` freshly checks instance,
world, dimension, owner, region, preview, backup, runtime, trusted plan and
journal, then performs an exact bounded rescan. BUILD and the pre-resource
CONNECT boundary can resume without repeating placements or consumption.
Uncertain PROCESS state refuses; VERIFY finalization is idempotent. Cleanup
remains available for owned journals even when resume safely refuses.
