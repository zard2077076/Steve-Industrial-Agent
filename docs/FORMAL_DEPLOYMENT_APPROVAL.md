# Formal deployment approval status

Status: **no approval exists**.

FB-07 generated eight approval-request records, one per candidate. All eight are `AWAITING_USER_SELECTION`; every request has no `ApprovalScope`, decision `ABSENT`, no one-time token and no execution authority. Formal allowed decisions: zero.

An approval request is not an approval decision. A future request can become `PENDING_USER_APPROVAL` only after an explicit candidate selection and only when a complete non-critical package binds the exact world, candidate, zone, preview, physical plan, source/world/runtime snapshots, completed backup, target/quantity, mutation limit, material/power budgets, allowed operations and expiry. Even then, it remains pending until a separate explicit human decision.

`TEST_ONLY_ALLOWED` is restricted to a disposable restore-drill copy and cannot be transferred to the formal source. The decision vocabulary has no formal `ALLOWED` state, and `FormalWorldExecutionHardStop` continues to return `FORMAL_WORLD_EXECUTION_FORBIDDEN` unconditionally.

Current aggregate evidence is `approvalRequestsAwaitingSelection=8`, `formalAllowedDecisions=0`, `claimPermission=UNKNOWN`, `inventoryContentsRead=false` and `formalExecutionAllowed=false` in `work/formal-backup-acceptance/fb07-20260720-141225/acceptance.json`.

Therefore this two-stage task ends without candidate selection, inventory inspection, approval, construction, connection to the existing base or formal-world mutation.
