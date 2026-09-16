# Player material ledger

Updated: 2026-08-01 (Asia/Shanghai)

## Scope and status

This milestone closes item accounting between a clean approved site and the
existing Direct/Bots/Hybrid construction executors. A later complete-BOM gate
now also refuses semantic construction roles that lack safe survival actions;
therefore item accounting is implemented but the current ordinary Create goals
are not yet player-buildable. It does not relax formal-world protection, touch
a PCL2 instance, or claim the still-pending Mac player-operated click-through.

The ordinary player flow is now:

1. finish and confirm clearing;
2. right-click one or more allowed material chests/barrels with the Engineer
   Terminal;
3. review exact required, available and missing quantities;
4. confirm sources so the server creates exclusive reservations;
5. regenerate the complete physical-plan BOM and start only if every
   construction role has an exact survival action mapping;
6. observe Direct transfer or a Bot walking to the selected source, carrying
   and delivering its exact task material;
7. receive a completion report only after real construction output and ledger
   balance both verify.

## Server authority

The client submits only intent. `PlayerMaterialService` revalidates world and
dimension, container position and face, BlockEntity type, distance, project
region, build permission, container validity and a fresh inventory snapshot.
The current fail-closed allowlist is the vanilla chest and barrel in the
isolated authorized execution world; unsupported/private mod storage is
rejected rather than inspected. Player inventory and nearby unselected
containers are never fallback sources.

Each `MaterialSourceBinding` records the project/owner, source identity,
position/face/type, block and inventory hashes, priority, per-source maximum,
expiry, and every exact slot's item identity, quantity and component/NBT hash.
Confirmation rescans rather than trusting the selection snapshot.

## Reservation and transaction model

`MaterialRequirementPlan` is matched to exact slots by
`ProjectMaterialLedger`. Reservations are exclusive across projects even if
the same physical inventory is addressed through another face. Matching
preserves exact item and component/NBT identity; incompatible NBT is refused.
Reservations expire and can be released without item movement.

Every physical movement is journaled through one of these paths:

```text
PREPARED -> WITHDRAWN -> DELIVERED -> CONSUMED
PREPARED -> RELEASED
WITHDRAWN -> RETURN_PENDING -> RETURNED
DELIVERED -> RETURN_PENDING -> RETURNED
```

The journal and current transaction rows are persisted together with staging,
delivery and courier identity. On a server restart, incomplete work pauses.
Prepared reservations can be released; withdrawn or delivered material must be
reconciled and returned exactly. The implementation deliberately does not
pretend that the prior executor session can resume automatically.

If a source changes, disappears, loses authorization or no longer contains the
reserved exact slot identity/quantity, construction pauses with a material
source diagnostic. Cancellation returns exact carried/staged material. A full
or missing return destination remains paused in `RETURN_PENDING`; the ledger
does not discard the obligation or silently use another box.

## Salvage separation

Clearing output remains owned by `SalvageLedger`. It is excluded by default.
Only the explicit "allow this project's salvage" control permits a
resource/quantity-bounded transfer into the project material calculation, and
the transfer is recorded as project-salvage evidence. Clearing drops are never
silently consumed as construction stock.

## Construction and completion

Before material movement, `PlayerConstructionService` replans, regenerates the
complete BOM and reauthorizes the `PreparedSiteExecutionGate`, then persists
its staging/delivery locations. Process inputs move to delivery; registered
item-form machine components remain in project staging escrow until the real
handler completes and are then consumed, or returned exactly on failure/cancel.
Direct mode transfers exact reserved slots transactionally. Bots mode uses a
visible `ConstructionBotEntity` courier with bounded pathing to each selected
source, exact slot withdrawal, a visible carried stack and delivery to staging.
Hybrid uses the same material ledger and records the transaction executor as
Hybrid; the construction graph remains unchanged in all modes.

The completion screen is derived only after the real executor finishes and the
ledger verifies planned, reserved, withdrawn, consumed and returned totals.
Its hard checks include:

```text
duplicateWithdrawals=0
duplicateReturns=0
unaccountedItems=0
privateItemsTouched=0
materialLedgerBalanced=true
```

An imbalance pauses the project instead of producing a successful report.

## Automated evidence and remaining gate

- Core macOS formal-world fixtures now assert the intended alias/symlink and
  canonical-path refusal; the production formal-world guard was not weakened.
- The aggregate JVM suite passes 640/640, including exact reservation conflict,
  face-bypass refusal, expiry, NBT refusal, cancellation return, journal reload,
  SavedData migration and packet bounds.
- A fresh isolated integrated GameTest server passes all 22 existing physical
  Direct/Bots/Hybrid equivalence tests, saves all dimensions and produces no
  crash report. Evidence:
  `work/logs/phase-iv-integrated-visible-gametest-20260801-131732.log`.

These automated gates establish the item ledger implementation and preserve
the existing physical executors. They do not prove the remaining
`SURVIVAL_MATERIAL_BINDING_REQUIRED` roles or replace the still-required
player-operated Mac acceptance for the source-selection/courier/report screens.
Windows/PCL2 validation remains explicitly deferred to its later handoff gate.
