# Demolition approval

`DemolitionPreview` reports exact findings, per-class counts, expected ticks,
bounded mutation budget, plan/snapshot identity, expiry and approval hash.

`DemolitionApprovalToken` binds exact writable world, dimension, player, plan
hash, site snapshot hash, approval hash, obstacle IDs, positions, BlockState
fingerprints, approved classes, expiry and maximum mutations. It is revocable
and one-time. Any world, dimension, player, plan, snapshot, position or state
drift fails closed; protected, hazard and unknown findings can never enter a
token. There is no approve-all form.

Commands:

- `/industrialagent site survey`
- `/industrialagent site obstacles`
- `/industrialagent site demolition preview`
- `/industrialagent site demolition approve-safe`
- `/industrialagent site demolition approve <id>`
- `/industrialagent site demolition revoke|status`
