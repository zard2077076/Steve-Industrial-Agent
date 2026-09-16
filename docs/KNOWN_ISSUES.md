# Known issues

## KI-105: C-04 empty-world recovery timeout (resolved 2026-09-05)

Vanilla `ServerLevel.tick()` skips entity and block-entity dispatch after 300 ticks with
neither players nor forced chunks. Loaded spawn chunks and `positionTicking=true` do not
prove that a block entity is being called. A temporary behaviour counter in the isolated
fixture recorded 299 calls at elapsed 300 and still 299 at elapsed 600, while game time
advanced (`c04-recovery-reload-read-20260905-080916.log`). This explains the previously
frozen press at runningTicks=234 despite positive speed and a matching registered instance.

The dev-only fixture now invokes vanilla `resetEmptyTime()` while active. The positive
counter run continued to 300 calls at elapsed 301 and produced one real iron sheet.
The final probe-free two-process gate passed at readTicks=383 with 30 exact unchanged BUILD
entries, 28 owned positions, one input/process journal entry each, one output and no
duplicates or crash reports (`work/logs/c04-recovery-reload-{write,read}-20260905-081413.log`).
The old completion assertions for 10 BUILD entries/12 total were replaced with equality
against the recovered BUILD journal and exactly two additional resource events. Both
wrappers require this evidence; the PowerShell wrapper is updated but not Windows-tested.

This changes only acceptance lifecycle and assertions. The production handler, 1201-tick
budget, recovery authority, natural Create ticks and resource-bearing refusal stay intact.
It does not add production chunk loading or guarantee operation in an empty sleeping world.

## KI-095: Multi-Bot client observation is ready, but completion evidence is still pending

The acceptance bridge already exposes bounded `world.inspect` entity rows. The harness now
provides `scenario plan multi-bot` and a read-only `scenario inspect-bots` command that samples
source, staging, site and return checkpoints, retaining each visible Bot UUID, role, tags and
position and flagging same-cell observations or duplicate UUIDs. It never spawns a worker,
invokes an order service or treats an empty observation as success. The 47/47 harness unit gate
proves the observation rules only. The dedicated-server physical gates now also prove real
BOTS material movement, settlement and cleanup for C-06--C-10 and Composite, but that does not
populate a live client observation: a real Mac BOTS order with screen/world evidence is still
required before Fleet-03/05 or any C-06--C-10/Composite client gate can be marked complete.

## KI-096: C-10 survival power is server-verified; real-client acceptance remains open

The acceptance harness exposes one reviewed representative plan for each of C-06 through
C-10. C-06 through C-10 now report verified water-wheel topologies with no
survival-power blocker. C-10 carries its exact item bill, media boundary, worker count and six
real kinetic roles; no creative motor remains in that row.
These plans are useful for selecting the next real-client target and diagnosing the stop, but
they do not reserve materials or count as a client acceptance. A visible Mac run through the
mandatory launcher remains required.

## KI-097: Offline C-06--C-10/Composite gates are green, while client and survival boundaries remain

The 2026-08-12 isolated dedicated-server run passes all 63 required C-06--C-10 checks,
Composite player orders in all three modes, Composite resume, and old-runtime reload/cancel.
The evidence is intentionally split across the four dated logs recorded in `docs/MASTER_PLAN.md`.
This closes a server-side physical regression (including stair-aware Bot navigation and
movement-aware BUILD budgets), not the player-visible acceptance layer. C-06 through C-08 are now
promoted rows; C-09 and C-10 are now promoted too. No client screenshot,
UI state or human usability claim may be inferred from these headless runs.

## KI-098: C-06 survival power is server-verified; real-client acceptance remains open

The isolated `scripts/test-c06-survival-power.sh` probe now proves a real Create 6.0.6
water-wheel -> gearbox -> vertical shaft -> encased-fan network, shared live kinetic network,
8 RPM role captures, live AirCurrent reach, and a balanced source-chest material reserve,
withdrawal and exact return. The candidate log is
`work/logs/c06-survival-power-gametest-20260812-125201.log`; all duplicate and unaccounted
counters are zero and the build area is restored.

The original candidate remains useful historical evidence, but production C-06 now uses a
larger 26-placement topology: mirrored water channel -> water wheel -> bottom gearbox ->
vertical shaft -> top gearbox -> fan-drive shaft -> north-facing encased fan. The isolated
production gates pass Direct/Bots/Hybrid (5/5), exact BUILD-prefix recovery and cancellation
(10/10), and the fault matrix (5/5). The mapping is now `VERIFIED_SURVIVAL` with evidence ID
`c06-water-wheel-fan`; its six kinetic roles are checked on one live, non-overstressed network,
and `create:creative_motor` is absent from the C-06 bill.

This promotes C-06 only to ordinary-player eligibility. No Mac client C-06 completion or human
UX claim is made until the mandatory acceptance launcher runs the visible workflow. C-04, C-05
and C-07--C-10 were subsequently promoted by their own independent production gates.

## KI-103: C-04 has a complete dual-input survival-power candidate, but is not player-eligible

`scripts/test-c04-survival-power.sh` proves two intentional Create 6.0.6 water-wheel/gearbox
branches: one powers all three belt segments and one powers the mechanical press. Ten role
captures run at 8 RPM across two network IDs. A real iron-ingot ItemEntity traverses the belt,
the press enters BELT mode, and live `create:pressing/iron_ingot` stores one iron sheet. The ledger
reports `planned=27 reserved=27 withdrawn=27 consumed=1 returned=26 duplicateWithdrawals=0
duplicateReturns=0 unaccountedItems=0 materialLedgerBalanced=true`; evidence is
`work/logs/c04-survival-power-gametest-20260812-191453.log`.

This is candidate evidence only. The published mapping remains `REVIEW_REQUIRED` and
`ordinaryPlayerEligible=false`; player unlock and Mac client acceptance require separate review.
C-05 still needs independent opposed-wheel survival evidence.

## KI-104: C-05 has a complete opposed-wheel survival candidate, but is not player-eligible

`scripts/test-c05-survival-power.sh` proves two mirrored-flow Create 6.0.6 water wheels directly
drive the two Z-axis crushing wheels at `-8/+8 RPM`. The runtime controller is
`VALID=true,FACING=DOWN`; a real gravel ItemEntity is consumed by `create:crushing/gravel` and at
least one sand reaches the output. The ledger reports `planned=17 reserved=17 withdrawn=17
consumed=1 returned=16 duplicateWithdrawals=0 duplicateReturns=0 unaccountedItems=0
materialLedgerBalanced=true`; evidence is
`work/logs/c05-survival-power-gametest-20260812-193508.log`.

This is candidate evidence only. The mapping remains `REVIEW_REQUIRED` and
`ordinaryPlayerEligible=false`; the destructive crushing fixture remains isolated, and player
unlock plus Mac client acceptance require separate review.

## KI-099: C-07 production survival power is server-verified; real-client acceptance remains open

The isolated `scripts/test-c07-survival-power.sh` probe now proves a real Create 6.0.6
water-wheel -> gearbox -> vertical shaft -> gearbox -> horizontal shaft -> upward mechanical saw
network. Six role captures share one live kinetic network at 8 RPM. The real runtime
`create:cutting/andesite_alloy` recipe consumes one `create:andesite_alloy` ItemEntity and stores
six `create:shaft` outputs; the candidate ledger reports
`planned=9 reserved=9 withdrawn=9 returned=9 duplicateWithdrawals=0 duplicateReturns=0
unaccountedItems=0 materialLedgerBalanced=true`, with rollback and baseline restoration. Evidence:
`work/logs/c07-survival-power-gametest-20260812-142318.log`.

The original probe is retained as candidate history. Production C-07 now uses a 23-placement,
fully billable topology: fourteen stone channel/lane cells, water wheel, two gearboxes, two
shafts, upward saw, depot, chest and one bucket-backed water source. Two bounded flowing cells
are journal-owned test/runtime propagation evidence, not extra materials. Direct/Bots/Hybrid
all complete real cutting with a complete item-form BOM, reload, settlement and baseline cleanup;
exact BUILD-prefix recovery and typed fault cleanup also pass. The mapping is now
`VERIFIED_SURVIVAL` with evidence ID `c07-water-wheel-mechanical-saw`, blocker null and six live
kinetic roles. This is ordinary-player eligibility only: no C-07 Mac client completion or human
UX claim is made until the mandatory acceptance launcher runs the visible workflow. Old oak
cutting IDs remain only in synthetic Composite fixtures and are not runtime recipe claims.

## KI-100: C-08 is server-eligible; Mac visible acceptance remains open

The isolated `scripts/test-c08-survival-power.sh` production probe proves a real Create 6.0.6
water-wheel -> gearbox -> shaft -> large/small/large cog ratio -> mechanical mixer network.
Seven live captures share one network at `8/8/8/8/16/16/32 RPM`; this matters because the
mechanical mixer requires Create's MEDIUM speed tier. Two real input ItemEntities enter the
Basin, the exact `create:mixing/andesite_alloy` recipe consumes one andesite and one iron nugget,
and the real mixer cycle stores one andesite alloy. The complete item-form bill explicitly
includes the construction-water bucket. The source ledger reports
`planned=26 reserved=26 withdrawn=26 consumed=2 returned=24 duplicateWithdrawals=0
duplicateReturns=0 unaccountedItems=0 materialLedgerBalanced=true`, with rollback and baseline
restoration. Evidence: `work/logs/c08-survival-power-gametest-20260813-101420.log`.

The published 24-placement production topology and generic graph use the same seven-role chain;
NONE and HEATED Direct/Bots/Hybrid, exact BUILD recovery and fault cleanup pass the official
63-test wrapper. The mapping is now `VERIFIED_SURVIVAL`, blocker null and
`ordinaryPlayerEligible=true`. What remains open is the separate real Mac client workflow and
human UX judgement; neither is inferred from server GameTests. C-09/C-10 cannot inherit C-08.

## KI-101: C-09 is server-eligible; Mac visible acceptance remains open

The isolated `scripts/test-c09-survival-power.sh` production probe proves a real Create 6.0.6
water-wheel -> gearbox -> vertical shaft -> gearbox -> horizontal shaft -> mechanical press
network. Six live captures share one kinetic network at 8 RPM. Three real input ItemEntities
enter the Basin, the exact `create:compacting/blaze_cake` recipe consumes one cinder flour,
one sugar and one egg, and the real press cycle stores one blaze cake base. The source ledger
includes the construction-water bucket and reports
`planned=25 reserved=25 withdrawn=25 consumed=3 returned=22 duplicateWithdrawals=0
duplicateReturns=0 unaccountedItems=0 materialLedgerBalanced=true`, with rollback and baseline
restoration. Evidence: `work/logs/c09-survival-power-gametest-20260813-103013.log`.

The published 22-placement topology and generic graph use the same six-role chain; standard and
100mB-lava Direct/Bots/Hybrid, exact recovery and fault cleanup pass the official 63-test wrapper.
The mapping is now `VERIFIED_SURVIVAL`, blocker null and `ordinaryPlayerEligible=true`. The
separate real Mac client workflow and human UX judgement remain open. C-10 cannot inherit C-09.

## KI-102: C-10 Deployer survival power is promoted, but client acceptance is still open

The isolated `scripts/test-c10-survival-power.sh` probe proves a real Create 6.0.6
water-wheel -> gearbox -> vertical shaft -> gearbox -> horizontal shaft -> downward Deployer
network. Six live captures share one kinetic network at 8 RPM. Two real input ItemEntities stage
one shaft as the Depot workpiece and one oak planks as the Deployer-held item; the exact
`create:deploying/cogwheel` recipe consumes both and stores one cogwheel. The source ledger reports
`planned=24 reserved=24 withdrawn=24 consumed=2 returned=22 duplicateWithdrawals=0
duplicateReturns=0 unaccountedItems=0 materialLedgerBalanced=true`, with rollback and baseline
restoration. Production evidence: `work/logs/c10-survival-power-gametest-20260813-104714.log`.

The published mapping is now `VERIFIED_SURVIVAL`, `ordinaryPlayerEligible=true`; the official
63-test integration gate and the owned-workpiece 10-test gate pass. The run also proves exact
cancellation when shaft is simultaneously structure escrow and process input. No C-10 Mac client
completion or human UX PASS is claimed until the real-client scenario is separately completed.

## KI-094: C-03 player fixture is ready but the real client run is still pending

The development-only `industrialagent acceptance create-fixture minecraft:gravel 3`
command now prepares a bounded disposable arena, derives the exact live C-03 bill from
the verified physical plan, seeds a real source chest and an empty salvage chest, and
refuses to overwrite an active player project, and never creates an order or invokes the
executor. `steve-agent-test scenario plan/run c03`
then drives the ordinary Engineer Terminal flow through semantic client actions and is
prepared to assert the completion report (`observedOutput=3`, `expectedOutput=3`, balanced
ledger and zero duplicate/private/unaccounted counters).

This is a fixture and an executable acceptance path, not physical evidence: no C-03
real-client PASS has been recorded in the current session. The C-03 run must use the
mandatory `scripts/new-player-acceptance-client.sh` launcher and the exact disposable
world. C-04, C-05 and C-08 through C-10 still refuse ordinary-player construction while
their survival-action mappings (notably the creative motor/power roles) remain unreviewed;
C-06 and C-07 are server-eligible but still lack separate real-client acceptance runs.

The real-client seeding step now also requires a fresh server marker on both generated
chests. If the command was refused because an active order survived, or if the client
only has an older disposable chest, the runner stops with `C03_FIXTURE_NOT_PREPARED`
instead of using that chest as if it were this run's bill.

The same fresh-marker rule applies to the pending Composite client fixture; it refuses
`COMPOSITE_FIXTURE_NOT_PREPARED` when either source chest does not carry the current
order type and one-run nonce.

## KI-093: Factory diagnosis is intentionally read-only; unavailable owned observers stay UNKNOWN

A-DIAG-01/02 provide six-category diagnosis for the player's current project and an exact
persisted warehouse order. Live probes now cover the player-bound delivery container, active
courier/task progress, exact `VerifiedPhysicalPlan` structure and Create rotational route,
the IE order's Metal Press/mold/FE topology and runtime-owned warehouse endpoints. Material
checks remain limited to player-bound sources and their reservation ledger; no probe discovers
nearby/private inventories or reads player slots.

An inactive/reloaded executor has no live route/session observer, and IE's output is a world
entity rather than a plan-owned container with a capacity contract. Those facts remain
`UNKNOWN`; neither proximity nor durable success is converted into invented live evidence.
The command supplies advice only and cannot authorize repair, rerouting, expansion, block
changes, item movement or order transitions. Physical zero-side-effect evidence:
`work/logs/factory-diagnostic-gametest-20260810-093814.log` (3/3). The dedicated IE runtime
retains its known client render/sound-class discovery diagnostics on a dedicated server; the
gate excludes only the three exact class names and still fails on every other ERROR/FATAL.

## KI-092: Search-visible derived targets were re-filtered by the reviewed display subset (resolved)

The terminal intentionally uses an empty query to show only eleven reviewed goals,
but `PlayerWorkflowService.createProject` reused that display subset as server
authority after already resolving a selected live-registry target. A player could
therefore search and see `minecraft:blue_concrete`, then receive
`TARGET_NOT_SUPPORTED` when pressing the order button. Creation now checks the
resolved entry itself for Create and recipe availability. The player-entry GameTest
proves blue concrete is search-visible, absent from the eleven reviewed rows and
accepted by `createProject`; the warehouse gate additionally produces one physical
blue concrete. Evidence: `work/logs/goal-driven-execution-gametest-20260809-202428.log`
and `work/logs/warehouse-unattended-20260809-204404.log`.

## KI-091: Vanilla item merging invalidated pending Bot salvage UUIDs (resolved)

Site clearing journals every real drop by entity UUID so the Bot can collect exactly
what its own block mutation produced. Vanilla may merge identical nearby ItemEntities,
remove the absorbed entity and invalidate that UUID while the combined items remain
visibly on the ground. Project-owned pending drops now use vanilla's unlimited-lifetime
no-merge sentinel before the first entity tick; they remain normally player-pickable if
the in-memory session is lost. The physical regression co-locates an identical control
drop for 60 ticks, proves both UUIDs survive, then proves exact Bot collection,
`groundDrops=0` and a balanced salvage ledger. Evidence:
`work/logs/site-preparation-gametest-20260809-203018.log` (12/12).

## KI-086: Player construction waits for survival action mappings

The server now derives a complete BOM from the freshly verified physical plan
and the item ledger can escrow process and item-form installation materials.
Current Create templates also contain semantic construction roles that are not
ordinary survival items: `create:creative_motor`, `create:belt`, water/lava/fire
media and some route roles. `PlayerVerifiedMaterialPlanResolver` returns
`SURVIVAL_MATERIAL_BINDING_REQUIRED` for these cases. This is intentional: the
terminal must not debit only the easy items while silently placing power,
belts or media for free. Required fix: reviewed server-authoritative physical
actions plus debit/rollback evidence for every such role.

The loader-neutral `CreateSurvivalPowerMappingV1` ledger freezes the exact
power roles behind this gate. C-03 through C-10 are reviewed survival mappings. C-06 binds
`water_wheel`, `bottom_gearbox`, `vertical_shaft`, `top_gearbox`, `fan_drive_shaft` and
`encased_fan`; C-07 binds its water-wheel/gearbox/shaft/upward-saw chain; C-08 binds its
water-wheel/two-ratio/mixer chain; C-09 binds its water-wheel/gearbox/shaft/press chain. The
Forge resolver derives its creative-only resource set from that ledger, and the current
unresolved set is empty. Any future review entry must still provide a versioned physical source,
topology, runtime kinetic observation and exact material/rollback evidence before ordinary-player use.
The Forge geometry regression now cross-checks every listed role against the published
v606 geometry and verifies that each unresolved role still names `create:creative_motor`,
while every verified role names its published survival block;
geometry drift therefore fails before any future survival-source implementation can be
reviewed.
`CreateSurvivalPowerEvidenceV1` adds the next fail-closed boundary: source identity,
topology fingerprint, exact role observations, positive live speed, stress safety,
material-plan binding, balanced ledger, rollback and baseline restoration are all
required. A complete candidate still cannot authorize ordinary-player orders while
its mapping entry remains `REVIEW_REQUIRED`.

The refusal detail is now preserved in `PlayerMaterialService.SelectionResult` and
the `MaterialSnapshotS2C` wire packet. The client displays the bounded detail after
the typed code (for example, which exact role is still unmapped), so this gate is
diagnosable without weakening it or guessing at a material source.

## KI-087: Exact IE Metal Press player slice implemented; broad IE authority remains closed

The exact IE 10.2.0-183 iron-plate order now has player material authority,
visible courier/builder Bots, real multiblock/mold/thermoelectric-LV-wire
actions, exact 2,400-FE observation, unique output, teardown, return, completion
report and five-window restart evidence. This does not flip the generic adapter
to `physicalExecutionImplemented=true`: other IE recipes/multiblocks, arbitrary
power layouts, central warehouse dispatch and complex fluids still refuse until
they receive their own reviewed lifecycle and physical gates.

## KI-088: Orders are durable, but no physical warehouse runtime is registered

`GlobalInventoryGraph`, `WarehouseReservationSystem` and
`UnattendedProductionOrderScheduler` are bounded loader-neutral authorities.
The Forge bridge projects only explicitly selected player containers and
existing persisted project reservations. `WarehouseOrderSavedData` now
round-trips the complete allowlist/in-flight identity, and the 20-tick service
persists dispatch/success/failure transitions. Runtime registrations are
deliberately ephemeral and none is player-exposed, so topology refresh,
physical inter-factory routing and unattended production remain unavailable.

## KI-089: Exact Forge endpoint transfer exists, but pipe/pump topology does not

Fluid identity, routes, network verification and exact-millibucket transaction
balance are implemented separately from the item ledger. The Forge bridge now
supports an exact simulate-first transaction between two explicitly authorized
`IFluidHandler` endpoints, including delivery-drift reclaim and exact return.
It does not scan nearby/private tanks, discover connected pipes, operate pumps
or resume an in-flight transfer after restart. Complex IE/Create fluid and
byproduct production remains a planning contract only.

## KI-090: Composite player orders run only inside the acceptance profile, and cannot resume

`industrial-player-order-v1` and its typed report/plan envelope are persisted
for ordinary C-03-C-10 player projects, and Metal Press projects project into
the same shape. The linear and branch/merge Composite graphs now also have a
genuine player path: `PlayerCompositeOrderService` reserves every stage and
route material from the player's own chest, runs the existing real wrappers and
settles output and salvage into one typed report. Composite-01 and Composite-03
both pass in all three modes
(`work/logs/composite-player-order-{20260806-162845,20260806-163634}.log`), and
an interrupted order fails closed across a restart
(`work/logs/composite-player-order-reload-20260806-164410.log`).

Three limits remain, and none may be rounded up to "Composite is
player-orderable":

- `CONCURRENT_LINES` (Composite-02) has no player entry, and should not get one
  as that wrapper stands. `CreateV606ConcurrentExecution` fails outright if line
  B completes without line A having been cancelled
  (`"Line B completed before line A cancellation evidence"`), and its completion
  record requires `sharedPreservedAfterLineACancel`. It is an apparatus for
  proving cancellation isolation, not a production executor — which matches
  Composite-02's own acceptance wording, "cancelling A preserves B". A player
  order over it would have to cancel half of what the player asked for in order
  to report success. `CompositeSiteLayout` refuses the shape explicitly.
  Concurrent *production* would need a two-line completion path added to that
  wrapper first; that is a new capability, not a missing player entry.
- The three-mode executor underneath both wrappers refuses to act unless
  `steve_industrial.test.goalDrivenExecutionGameTest` is set, so this path runs
  only inside the isolated acceptance profile. Opening that gate for ordinary
  worlds is a separate, deliberate decision and is not implied by IPO-02.
- **A restarted order cannot resume, but it can now be cancelled for a refund.**
  The reload gate proves the fail-closed half: a fresh JVM pauses the envelope at
  `RELOAD_RECONCILIATION_REQUIRED`, replays no adapter, writes no report and
  withdraws nothing further, with the player's unreserved surplus untouched.
  Continuing the run is still not possible.

  Until 2026-08-06 this entry claimed cancellation was available after a restart.
  It was not: `ACTIVE` is empty in a fresh process, so cancellation answered
  `NO_ACTIVE_COMPOSITE_ORDER` and the player had no path at all. A restarted
  order can now be cancelled, which clears the site and returns everything not
  already consumed — 20 of 25 withdrawn items in the gate. What is consumed by
  completed stages is genuinely spent and does not come back.

## KI-084: Phase IV-C construction handoff lacked player-owned material accounting (resolved)

The selected-source MaterialLedger, exact transaction recovery and
Direct/Bots/Hybrid transfer path are implemented. The follow-on blocker is
KI-086: complete survival construction-action mapping, not missing accounting.

## KI-085: Mid-clearing process restart is fail-closed, not resumable

Disconnect/reconnect within one server process pauses and resumes the existing
session. A full server restart deliberately discards in-memory execution
authority and marks the durable project `RECOVERY_REAPPROVAL_REQUIRED` (or
`CONSTRUCTION_RECOVERY_REPLAN_REQUIRED`). This prevents duplicate removal or
construction, but persistent carried-salvage reconciliation is not yet
implemented. Do not stop the server during active clearing; pause only after
the HUD reports no undelivered salvage. Automatic Agent-owned temporary
salvage containers remain unimplemented.

## KI-083: Forced chest removal could emit private contents (resolved)

The 2026-07-30 Windows handoff audit caught a nondeterministic defect in the
separately confirmed destructive grading path. Replacing a chest with air via
the normal block update can invoke the vanilla removal hook and drop its
inventory. The prior short-radius assertion could miss an item after it moved,
so an earlier green run was not sufficient evidence.

The implementation now removes only the already classified opaque BlockEntity
immediately before replacing the exact snapshot-bound forced cell. It does not
inspect, enumerate, transfer or salvage the contents. The default path still
refuses without mutation, and the force path still requires the second exact
confirmation and permanent-data-loss warning. The honest failing 9/10 run is
`work/logs/site-preparation-gametest-20260730-202524.log`; the fixed run passes
10/10 in `work/logs/site-preparation-gametest-20260730-203321.log`, with
`privateContainerContentsRead=0`.

## KI-082: Confirmed terrain-force removal is intentionally destructive

Explicit grading normally refuses containers, block entities, dangerous media
and unbreakable/protected blocks without changing the world. A player may now
confirm one exact, snapshot-bound warning hash and authorize their forced
removal. This necessarily permits permanent loss of container contents,
block-entity NBT and blocks that normal survival mechanics cannot break. The
implementation does not inspect, salvage or recreate those contents, and it
cannot undo the removal after the session advances. Dangerous media removal
may also change neighboring environmental behavior outside the selected cell.

The mitigation is deliberately narrow: the first scan is warning-only, the
confirmation expires and binds the player plus every exact risk cell/state,
the second full scan invalidates any drift, mutation stays inside the selected
grading volume, and evidence reports every destructive category with
`privateContainerContentsRead=0` and `playerInventoryAccess=0`. This is not a
claim that force removal is safe or recoverable. A stopped-world backup remains
mandatory before the final visible acceptance, and the normal path remains
fail closed.

## Phase IV integrated automation is green; visible acceptance remains WIP

- The previous candidate JAR was not launched because its player pilot surface
  still exposed only the Phase III gravel/iron-sheet targets. That gap is now
  closed in source: exact C-05-through-C-10 targets require a fresh prepared
  site and the existing executor receives its authorization. A dedicated
  `pilot site-anchor` read-only command makes the required deterministic anchor
  observable before selection. Prepared-site sessions intentionally do not
  resume execution after reload because their in-memory site authorization is
  not persisted; exact journal cleanup remains available, while automated
  handler recovery stays covered separately. The final JAR and real visible
  run remain pending, so this is not a completion claim.

- Composite-01/02/03 now have automated Direct/Bots/Hybrid physical evidence,
  and Composite-03 has a separately isolated 3/3 contamination, merge
  backpressure and branch-input-loss matrix. Composite-02 exact wrapper
  recovery now also passes in every mode. The integrated Site Preparation to
  existing C-07 execution fixture passes 8/8, and the concentrated automated
  matrix passes. These remain WIP rather than formal completion checkpoints
  only because the single backed-up, new-region player-visible acceptance and
  final checkpoint commits remain.
- Honest failed logs are retained. `20260727-112407` proves `item_application`
  casing is outside the current C-10 item-only recipe catalog, and
  `20260727-113147` proves an andesite-alloy Saw can choose a real
  `create:andesite_ladder` result that C-07 correctly refuses instead of
  relabeling as a shaft. Later `155505`, `155905`, `160431` and `160553` runs
  exposed cross-arena transient-entity leakage, wrong failed-branch
  attribution, thrown-session cleanup and a merge node that did not yet own
  physical routing; the final isolated success/fault suites fix those causes
  without weakening assertions.
- A vanilla Hopper can hold an item in flight after delivering the reserved
  item. Composite-01 therefore does not discard or directly rewrite that
  inventory: it powers the Hopper off, changes only its verified output
  direction, then physically drains all five excess planks to a dedicated
  salvage chest and checks exact conservation before C-10 starts.
- Earlier failed Composite GameTest attempts are retained as honest logs. The
  first was a missing Forge template-prefix annotation before execution; later
  attempts exposed C-10 single-cycle semantics and Hopper in-flight behavior.
  The final combined 9/9 passing run is
  `work/logs/phase-iv-composite-gametest-20260727-171847.log`; the final
  isolated 3/3 fault run is
  `work/logs/phase-iv-composite-fault-gametest-20260727-172021.log`.

## Phase IV 3-5 Bot kernel is integrated; visible acceptance remains

- The graph-neutral two-to-five worker kernel and construction typed adapter
  now pass focused JVM tests and the isolated 2/3/5 physical suite. They reuse
  the registered `ConstructionBotEntity` and do not add another scheduler.
- Honest failed logs `bot-fleet-gametest-20260727-163519.log` and
  `bot-fleet-gametest-20260727-163902.log` exposed an excessive test path bound
  and loss of same-worker continuity for physically carried reservations. The
  final `20260727-171108` run passes 5/5 after bounded paths and typed continuity
  were added. A later C-10 regression initially stopped after its logistics
  task because an explicit buffer delivery was incorrectly treated as carried
  inventory; `MATERIAL_DELIVERY` is now the exact typed hand-off boundary and
  C-10 passes 10/10 in `20260727-165222`.
- Site Preparation now has its own `TerrainPreparationTaskGraph` adapter and
  dispatcher behind this kernel. The 8/8 integrated fixture reaches the
  existing Construction Executor through `PreparedSiteExecutionGate` without
  constructing a terrain `ConstructionTask`, inventing `VerifiedPhysicalPlan`
  provenance, adding a round-robin scheduler or granting generic removal/use
  authority. Only the combined player-visible acceptance remains open.

## Phase III visible executor pilot resolved

- The dedicated `SteveAgent-Executor-Test` visible pass is complete for C-03
  and C-04 across Direct/Bots/Hybrid, including cancel, cleanup and safe
  BUILD-stage reload recovery. No formal instance was used.
- The first player-shaped dual-Bot follow-up exposed a shared completion cell.
  The fixed committed JAR now assigns distinct role-specific completion
  stations, automated C-03/C-04 acceptance proves `botOverlap=false`, and a
  second isolated iron-sheet replay was visibly confirmed with Steve and Alex
  separated. The presentation defect is resolved.
- Bot/Hybrid progress containing resource history remains cleanup-only after a
  restart. Automatic mid-task resume is intentionally disabled outside the
  separately proven initial BUILD boundary; this is a safety boundary, not a
  missing success flag.

## KI-071: RC1 is blocked on fresh-cache reproducibility evidence

The project has deterministic JAR ordering, timestamps and commit-derived build
metadata, but the two completely empty Gradle caches have not both completed.
One wrapper download timed out and the explicit Gradle 8.4 retry exceeded the
bounded dependency-resolution window. This is an environmental/bootstrap
blocker, not evidence of byte-identical reproducibility.

## KI-074: Public config is not yet the Forge pilot runtime authority

The external resolver proves precedence and path refusal, but the Forge pilot
commands still require the repository IWP launcher's JVM properties and accepted
backup evidence beneath repository work storage. A copied JAR plus example
config therefore cannot yet execute the writable pilot on a stranger's machine.
Installation and quick-start files are explicitly marked as pre-RC drafts until
this bootstrap is replaced and clean-room tested.

## KI-072: Final clean-room client visual acceptance is pending

The sole-JAR static install and packaged server pass, and the existing isolated
player pilot plus fresh GameTests prove gravel, iron sheet, cleanup, cancel and
recovery behavior. The exact final release JAR has not yet completed one new
human-visible client lifecycle in a separate clean-room instance. RC1 and its
tag remain blocked until that pass is explicitly confirmed.

## KI-073: Project networking is disabled; loader/mod networking is separate

Steve Industrial Agent implements no HTTP client, remote-code download,
automatic updater, telemetry or automatic diagnostic upload. Forge or other
user-installed mods may independently perform their own version checks; those
features are outside this project's authority and distribution.

## KI-001: Cold Forge dependency bootstrap was slow (mitigated)

Java/Gradle downloads from Forge Maven stalled on this host, while PowerShell HEAD/download succeeded. Top-level Forge 47.4.10 artifacts are stored in ignored `work/local-maven` and verified against official SHA-1. A portable SHA-verified JDK 17 and optional PCL library Maven source in ignored local configuration completed the cold bootstrap. Clean builds now pass, but a fresh host still needs normal repository access or equivalent bootstrap.

## KI-002: Codex project UI registration (resolved)

The user created `Steve Agent`, and live project/Git readback confirmed that it points at this repository. Keep the UI label for compatibility while using Steve Industrial Agent as the formal product name.

## KI-003: Registry scan and kinetic telemetry are separate capabilities

The C-01 command identifies real Create registry blocks and state without loading chunks; it is still not a kinetic diagnostic. C-02 separately reads one real kinetic anchor and its cached network stress totals through the strict 6.0.6 adapter. Neither capability observes ports, recipes, inventories, energy, fluids, chemicals, processing progress or output, so they must not be described as processing verification or full perception. C-03 is separate evidence for one fixed water-wheel/millstone plan and one live cobblestone milling recipe; C-04 is another separate proof for one fixed belt/press/funnel/chest path and one iron recipe.

## KI-004: Game module name still says Create

`forge-create-1.20.1` predates the Industrial rename. Renaming it before the first stable build would add churn and no runtime value. Plan a tested artifact/module migration separately; keep mod ID compatibility until a migration policy exists.

## KI-005: T-02 launch matrix is still partial

The repeatable Forge userdev framework passes neither-mod and Create-only profiles on both dedicated server and client, and the final packaged production JAR passes an official-Forge neither-mod dedicated-server launch. G-11 retains its minimal generic two-process `SavedData` fixture, while production C-03 and C-04 independently pass real-handler reload from the BUILD-complete, pre-resource boundary. Mekanism-only and dual-mod profiles remain blocked on an official dependency/version pin; none of those rows may be inferred from the passing userdev, packaged-neither or Create recovery evidence.

## KI-006: Kinetic direction is anchor-local and the overload fixture uses an explicit test capacity

Create networks can contain geared members with different speed magnitudes and signs. C-02 reports actual signed rotation for the requested anchor plus network-wide cached capacity/load; it does not assign that anchor direction to every network member. The deterministic overload acceptance lowers only the isolated fixture's creative motor capacity from the Create default to `1 SU/RPM`, then lets Create produce the real overload state. Those fixture totals must not be quoted as production defaults.

## KI-007: C-03 processing scope is intentionally narrow

C-03 directly feeds and reads the real Create 6.0.6 millstone inventories inside the versioned executor. It proves input consumption and output production, but not an external depot, chute, belt or hopper transport path. Its structure template is vanilla `minecraft:bastion/mobs/empty`; the typed executor builds every functional block during the test. G-10 proves cancellation after the first construction action and exact restoration of that still-safe block only. The production reload acceptance resumes only after BUILD and before input injection; any checkpoint containing injected or irreversibly processed resources remains a typed `RESOURCE_HISTORY_UNSAFE` refusal. Arbitrary milling recipes, automated repair, cancellation/recovery after input injection, complete rollback and throughput remain unverified. Belt/press behavior is covered only by the separate narrow C-04 evidence and must not be inferred from C-03.

## KI-008: C-04 output and topology are intentionally narrow

On Create 6.0.6 a plain chest at the horizontal belt ending blocks/ejects rather than acting as direct belt input. The verified C-04 plan uses an unpowered, non-extracting upward andesite funnel with the chest below; removing or reorienting that funnel is not covered. C-04 verifies one compact three-segment horizontal belt, creative-motor power, one press and the iron-ingot recipe in the vanilla empty GameTest footprint. Sloped/vertical belts, arbitrary lengths, depots, alternate extraction, arbitrary pressing recipes, throughput, persistence and repair remain unverified.

## KI-009: One retained crash report predates the fixed userdev classpath

`forge-create-1.20.1/run/mekanism-absent/crash-reports/crash-2026-07-13_11.32.56-fml.txt` is a historical failed probe that reported `NoClassDefFoundError`/`ClassNotFoundException` for `IndustrialModAdapter` before the Forge userdev run included `core` and `adapter-api` outputs. The classpath was fixed and later M-01, T-02 server and T-02A client acceptances produced no new crash report. The old file is retained as failure history and must not be described as a current crash.

## KI-010: Create 6.0.6 resolves an absent JourneyMap Mixin target in client userdev

With Create present and JourneyMap absent, upstream `create.mixins.json` names `journeymap.client.ui.fullscreen.Fullscreen`; Mixin 0.8.5 logs a `ClassNotFoundException` before the title screen. The strict T-02B driver rejected that initial run (`work/logs/runtime-create-only-client-20260714-130909.log`). A first experiment that actively loaded an empty target correctly failed with a Mixin crash; its full console log and SHA-256-preserved crash archive are `work/logs/runtime-create-only-client-20260714-131214.log` and `work/logs/failed-runtime-create-only-client-20260714-131214-crash.txt`. The final profile supplies the empty target only for startup lookup, never loads it, asserts the JourneyMap mod is absent, and excludes it from production artifacts. Final clean evidence is `work/logs/runtime-create-only-client-20260714-131634.log`; this workaround is userdev-test-only and is not a claim that an arbitrary packaged Create client has no upstream warning.

## KI-011: Generic resource categories are vocabulary, not implemented transports

G-01 defines six typed resource categories so later graphs do not hard-code Create semantics. It does not implement Forge Energy, fluids, Mekanism chemicals, heat transfer, ports, routing or cross-mod conversion. Current physical evidence remains limited to items and Create rotational power; documentation and adapters must not turn enum presence into a capability claim.

## KI-012: Unified machine graphs are not automatic planners

G-02 provides a strict immutable topology model. G-09 carries both the fixed C-03 six-node graph and fixed C-04 nine-node graph in their production generic plans, but each graph is still derived from an already chosen layout and recipe; neither selects machines, generates layouts or routes resources. G-14 separately checks only whether that selected final-role layout is basically placeable; it does not make the graph a planner. G-11 fingerprints the relative graphs and persists generic session state. C-03 and C-04 reconstruct their production v606 placement/build cursor only at one exact safe boundary after trusted-plan/world reconciliation. G-10 can journal and conservatively restore an unchanged early-construction block, but it does not plan a layout or provide complete rollback. G-12 proves that one test-source-only virtual adapter can execute success and typed failure through the generic graph/session/runner/rule contracts; it is synthetic JVM evidence, not a production adapter or physical third-party-mod result. No non-Create production adapter executes a graph yet.

## KI-013: Generic process specifications do not execute themselves

G-03 removes duplicate storage/validation of common milling and pressing process fields. Both C-03 and C-04 now use their process specifications to define the generic PROCESS step and exact rule through the shared runner, but the specifications are still data rather than recipe interpreters or world operations. Completion-evidence IDs remain requirements rather than observations or success flags; only each v606 handler's complete records plus its physical GameTest prove input consumption, processing and output. Optional byproducts and non-item resource quantities remain representable without production handlers.

## KI-014: Generic execution descriptors remain registry-controlled

G-04 step actions and conditions contain only handler/evaluator IDs and bounded opaque parameters; they cannot invoke code by themselves. `BoundedStepRunner` resolves those IDs only through explicit registries, and both C-03/C-04 v606 handlers reject unknown operation/step identities or non-empty parameters before world access. Other plans still need equally strict schemas. Optional rollback descriptors remain descriptive and non-executing; G-10 cancellation instead consults the session's concrete journal and exact current world state through the versioned adapter.

## KI-015: Production recovery is limited to explicit pre-resource boundaries

G-11 provides a versioned, canonical, 4 MiB-bounded checkpoint codec for plan/graph identities and fingerprints, complete session state, complete journal and modified positions. Its isolated two-process fixture stores one generic paused session through Minecraft `SavedData`, rediscovers it, requires a trusted runtime plan and exact authoritative block rescan, then either exposes a resumable session or returns typed stale-session. It intentionally refuses terminal sessions, injected resources and irreversible processing. C-03 and C-04 now have independent repository acceptance-only save hooks and persist their physical anchors, but capture only after BUILD at `POWER/READY`; after reload they reconstruct placement/build cursors only from a reconciled `Resumable`. Create 6.0.6 constructs kinetic block entities with a transient `NeedsSpeedUpdate` byte, serializes it before the first attach tick and never reads it back. Both production adapters therefore canonicalize only that byte on live v606 `KineticBlockEntity` snapshots after exact trusted-position validation; all other state remains exact and meaningful drift is still stale. Resource-bearing checkpoints remain refused, so no automatic blind resume, mid-process recovery or general recovery claim is valid.

## KI-016: The bounded runner cannot preempt a misbehaving handler

G-06 hard-caps runner dispatch to one action-handler invocation per tick and prevents same-tick retry loops or reinvocation after action success. Core cannot preempt a malicious handler that blocks internally or performs unbounded work inside one invocation, so every production handler still needs review and operation-specific bounds. G-09 physically validates both current v606 handlers: each call performs one block placement, C-04's one bounded belt connection, one bounded feed or one bounded poll, with no blocking primitive. Future handlers do not inherit this proof automatically.

## KI-017: Generic verification rules do not interpret adapter value schemas

`VerificationEvidence` always carries schema-tagged observed and expected values, so its pass flag is not a substitute for physical data. G-08 centralizes required/optional sets, provenance constraints, latest-observation selection, typed failures, timeout and custom-adapter permission, and its process factory rejects omission of any declared completion requirement. The schema ID and bounded value string remain adapter-defined: core still does not calculate numeric tolerances, inventory equality, block-state equality or custom structures by itself. Both C-03 and C-04 connect exact rules to the runner, but those rules trust records produced only after their v606 handlers perform concrete readbacks; the physical GameTests remain the authoritative proof.

## KI-018: G-10 rollback is deliberately incomplete

`WorldChangeJournal` is bounded to 4,096 entries, and one cancellation plans at most 64 reverse-order block restores. The v606 bridge never loads a chunk for rollback and restores a block only when its current registered block, complete properties and optional full block-entity SNBT exactly equal the recorded after-state; journal schema and SNBT are validated before any restore write. If any input was injected or real processing completed, the conservative planner performs no topology rollback and returns typed `INJECTED_RESOURCE_NOT_RECREATED` or `IRREVERSIBLE_PROCESSING_NOT_REVERSED` warnings. It never creates replacement items or claims full restoration. Current physical evidence covers cancellation after one construction action in each fixed C-03/C-04 plan, not mid-process rollback, multi-block conflict recovery or repair.

## KI-019: Plan transforms rotate fixed templates; they do not plan layouts

G-13 applies only whole Y rotations of 0/90/180/270 degrees to an already trusted anchor-relative plan. Pure JVM tests cover every turn and preserve graph identities/edges; physical Create evidence covers ZERO, CLOCKWISE_90 and CLOCKWISE_270 for each fixed C-03/C-04 template. CLOCKWISE_180 is not physically claimed. G-14 can now reject a selected rotation with a complete basic conflict report, but there is still no mirror transform, placement search, obstacle avoidance, existing-power connection or automatic machine/recipe selection.

## KI-020: Basic placement feasibility is not a claims system or layout solver

G-14 evaluates at most 256 explicit final-role targets and returns at most 1,024 typed conflicts. The Forge bridge recognizes protection only through the bounded position set supplied by its caller; it is not integrated with spawn protection, claims/permissions mods or arbitrary policy APIs. C-04's temporary pulley shafts are deliberate transitions into final belt roles and are intentionally excluded from internal final-role ownership conflicts. The existing 175/150-position transformed preflight volumes remain separate checks. A rejection never starts a session or changes a target, but it also never clears an obstacle, picks another anchor, mirrors, reroutes or repairs the plan.

## KI-021: Physical GameTest isolation depends on the repository flat-world fixture

C-03/C-04 use the vanilla one-block `minecraft:bastion/mobs/empty` structure template, so generated terrain outside that template is not isolated. A strict C-04 run retained at `work/logs/create-belt-press-gametest-20260715-120945.log` detected natural gravel entering the upward funnel even though the real iron sheet was also produced. Both repository drivers now write a valid classic-flat generator configuration before launch. This is test-environment isolation only, not a production world mutation or product capability; removing it requires a replacement structure or equally bounded isolation mechanism.

## KI-022: Logical machine graphs are not implementation or layout bindings

P-09 maps a selected coordinate-free candidate into exact typed logical resource flow and validates supplied capability declarations. It does not discover a real block, select an implementation, bind an Adapter port, choose coordinates/orientation/sides/capacity, route a connection or construct a physical `UnifiedMachineGraph`. Those facts require separate Adapter-owned implementation binding and complete physical layout materialization stages. The passing C-02/C-03/C-04 regressions prove that the frozen existing physical paths did not regress; they do not prove that a P-09 logical graph can yet build or run in a world.

## KI-023: A verified logical plan is not an executable or runtime-discovered plan

P-10 proves only that a candidate and its `LogicalMachineGraph` are internally consistent with the supplied deterministic `RecipeCatalog`, `MachineCapabilityCatalog` and `PlanningContext`. `VerifiedLogicalPlan` intentionally has no action handler, physical machine, layout, world reference or public bypass constructor. R-05 now drives matching runtime recipe/capability snapshots through that gate and can claim a runtime-backed verified logical plan. It still cannot claim that any concrete physical implementation has been selected or that the plan is executable.

## KI-024: Ingredient-resolved runtime recipes are not yet capability-linked plans

The exact v606 boundary enumerates, fingerprints and maps every live milling/pressing entry it can represent. The standard isolated profile exposes 51 target recipes: 32 map successfully and 19 return explicit `OUTPUT_UNSUPPORTED` because repeated independent rolls or probabilistic primary output cannot be represented without changing deterministic quantity semantics. R-03 resolves usable inputs into frozen `CatalogRecipe` values with original entry/tag/selection evidence and typed unresolved/stale limitations. R-04 publishes matching runtime-attributed capability IDs, but only `create:milling/cobblestone` and `create:pressing/iron_ingot` carry physical proof. R-05 can verify logical plans from those runtime facts, but it does not bind any logical node to the proven executor, so it must not be described as physical readiness.

## KI-025: The runtime planning command is intentionally read-only

`/industrialagent plan create` reports a runtime-backed `VerifiedLogicalPlan`; it does not reserve materials, place blocks, create a `GenericExecutionSession`, select a concrete implementation or start C-03/C-04. The command currently has no owned-inventory argument, so its production goal begins with no claimed owned resources even though the underlying typed service supports deterministic owned-resource selection. This is a presentation boundary, not a hidden construction shortcut.

## KI-026: DeceasedCraft runtime recipes are profile-specific

R-09C now verifies the disposable profile's actual RecipeManager, but those results remain profile-specific and must not be substituted back into either the standard Create-only profile or a player save. The DeceasedCraft profile has 18,286 total recipes and 103 milling/pressing entries versus the standard profile's 2,609 and 51; runtime fingerprints differ. The pack-source 235 discovered server mods, 236 JAR files and 288 loaded mod containers describe different layers and must not be conflated. No formal client save or server world was read or launched.

## KI-027: The unfiltered pack starts but emits retained dedicated-server diagnostics

R-09B loaded all 236 source JARs plus the Steve Industrial Agent production JAR as 288 Forge mod containers without filtering and completed a clean save/exit. R-09F's final repeat retained the same 42 matched diagnostic lines while completing RecipeManager export, seven planning scenarios, dimension saves and clean exit without a crash report (`work/logs/deceasedcraft-isolated-server-20260716-230411.log`). The diagnostics include optional integration `ClassNotFoundException` warnings, five `RuntimeDistCleaner` invalid-dedicated-server target errors encountered during Mixin lookup, and one uncaught `Thread-1` `ConcurrentModificationException` from ModernFix's NightConfig watcher. The final independent scan found zero pack-fatal markers, but these 42 lines are still not described as a clean log. No JAR is removed unless a later failure identifies that exact JAR as necessary to exclude; recipe-bearing behavior must not be traded away merely to suppress startup noise.

## KI-028: Physicalization is bounded planning authority, not construction authority

`VerifiedPhysicalPlan` contains concrete component positions, orientation-dependent ITEM ports, bounded routes and a strict `UnifiedMachineGraph`, but it cannot create a `GenericExecutionSession`, invoke a handler or mutate a world. The current module placement uses fixed canonical spacing and finite obstacle-search offsets; ITEM routing is bounded grid search, not an optimal conveyor designer. Only the two accepted Create implementations, three orientations, ITEM and ROTATIONAL_POWER are supported. Geometry component rules intentionally retain C-03/C-04 topology requirements without calling their fixed execution templates. Other Create recipe types, CLOCKWISE_180 physical evidence, mirrors, claims integration, existing-factory attachment, route cost optimization, repair and cross-mod transport remain unsupported.

## KI-029: DeceasedCraft unloads spawn chunks before layout capture

The pack enables ModernFix `remove_spawn_chunks`, so a loaded-chunk-only capture at `ServerStartedEvent` correctly returns `WORLD_SNAPSHOT_STALE`. The failed diagnostic run is retained at `work/logs/deceasedcraft-isolated-server-20260717-014634.log`. Accepted R-09 physicalization explicitly reads at most 49 chunks around the high isolated anchor, then classifies every actual block state; it does not assume air or change a block. This exception is restricted to the disposable ignored R-09 world and is not permission to load or inspect a formal player world. Final accepted evidence is `work/logs/deceasedcraft-isolated-server-20260717-015152.log`.

## KI-030: Goal-driven execution recovery and transport remain deliberately bounded

Execution currently supports only the exact Create 6.0.6 milling and pressing implementations. Verified ITEM-route cells are controlled, journaled physical connectors, while inter-process resources are staged through the real world-backed buffer; this is not a general conveyor-network or existing-factory attachment claim. Automatic reload recovery is limited to the proven single-process BUILD checkpoint before resource history. Multi-node root recovery and every resource-bearing or irreversible checkpoint fail closed. Sequenced assembly, other Create types, mirrors, arbitrary topology repair, survival collection, cross-mod transport, formal worlds and Mekanism remain unsupported.

## KI-031: The production-pack pilot explicitly drives Create block entities in an empty server

DeceasedCraft's dedicated-server profile has no player and includes empty-server pausing behavior. The normal server tick event still advances the bounded root controller, but Create water-wheel lazy refresh and machine block-entity dispatch do not reliably advance in that fixture. The R-09 execution JVM property therefore enables a narrowly scoped compatibility bridge only when a real world-backed resource buffer is present: it places and journals the two actual falling-water cells, asks Create's own `WaterWheelBlockEntity.determineAndApplyFlowScore()` to read the live fluid vectors, and calls each real millstone or belt/press-line `SmartBlockEntity.tick()` at most once per server tick. Recipe lookup, power propagation, inventory consumption, processing and output remain Create code and are read back physically; no speed or output is synthesized. Normal C-03/C-04 and goal-driven GameTests do not enable this bridge and pass independently.

The pilot also exposed and fixed a general physicalization bug: releasing an incident machine's clearance for ITEM routing must never release any actual component cell. Routes may begin/end at explicit ports but their interiors now always block every machine component, preventing an upstream connector from occupying a downstream belt start. This is still bounded fresh-layout routing, not permission to alter or repair an existing factory.

## KI-032: Environment classification is necessary but not deployment authority

PW-01 can prove that canonical paths and supplied fingerprints are consistent with an allowed isolated/development root or a forbidden/unknown environment. It does not validate a deployment budget, approval, backup, claim permission, snapshot freshness or mutation plan and cannot create an execution session. The explicit alias map is trusted only after an Adapter independently verifies the junction/symlink target; an unverified textual alias must never be added. Formal-player-world write prevention still requires PW-02/PW-03 gates on every execution, recovery, replay and handler path.

## KI-033: DeploymentPolicy is a limit set, not approval or readiness

PW-02 can make a request impossible under explicit bounds, but satisfying those bounds does not prove snapshot freshness, region authorization, claim permission, backup validity, human approval or handler coverage. The formal default intentionally permits a formal environment type only so a future explicitly approved read-only preview can be modeled; its forbidden-root check and zero mutation/execution authority still reject execution. PW-03 and later readiness gates must independently enforce every live entry path.

## KI-034: The current Forge guard proves repository-owned fixtures, not formal-world readiness

PW-03 strengthens every existing v606 mutation path, including post-session handler, route, resource and rollback operations. It still does not authorize formal inspection or execution: the marker is created only by repository scripts in isolated ignored run directories, and the configured forbidden root wins even if expected-gameDir is misconfigured. Future adapters must adopt the same per-write boundary; a new handler is unsupported until source/runtime tests prove it cannot write without current guard evidence.

## KI-035: PW-04 preview estimates are audit projections, not final gates

`DeploymentPreview` carries deterministic preliminary risk strings, policy violations and arithmetic estimates so its complete physical content can be reviewed and hash-bound. PW-05 now owns the typed INFO/LOW/MEDIUM/HIGH/CRITICAL assessment, and PW-06 owns the separate read-only resource/power budget; neither grants authority back to the preview. The preview still cannot prove snapshot freshness, claims, region scope, human approval, backup or restore viability. A preview with a valid hash remains non-executable.

The R-09 acceptance harness previously started its 180-second Forge-start clock before profile preparation and clean build. PW-04's mandatory pack repeat exposed this as a false timeout after a successful 125-second build. The clock now starts immediately after `Start-Process`; the server bound remains 180 seconds, and final-JAR acceptance passes with zero crash reports, residual processes or external mutation in `work/logs/deceasedcraft-isolated-server-20260717-202714.log`.

## KI-036: PW-05 evaluates supplied facts but does not authenticate them

The fixed risk rules prevent severity drift and make every critical finding blocking, but `DeploymentRiskContext` is still read-only input data. PW-06 calculates budgets without authenticating availability, PW-07/PW-08 bind supplied region/approval scope and PW-09 proves backup only for isolated fixtures. PW-10 normalizes attributed claim decisions but leaves detected third-party integration UNKNOWN; PW-11 now rejects stale or mismatched evidence. A non-critical assessment is still not approval or execution authority.

## KI-037: PW-06 accounts for declared resources but does not authenticate availability

`DeploymentBudgetContext` carries bounded read-only intermediate, power, logistics, capacity and storage facts supplied by a later Adapter boundary. Core deterministically calculates requirements and typed violations, but it does not inspect a container/network, prove ownership, reserve items or make availability current. PW-07/PW-08 bind scope/approval and PW-10 can carry verified Adapter permission without changing that limitation; PW-11 now rejects stale or mismatched preview, snapshot, runtime, budget and resource evidence before readiness can exist. A within-policy budget is not an approval or execution permit.

## KI-038: PW-07 compares supplied authorization evidence but does not authenticate the authorizer

The model prevents silent scope expansion and owner substitution, but the authorizer identity, optional owner and provenance are still supplied facts. Core does not contact a claim system, sign a token or prove a human identity. PW-08 verifies external exact-scope tokens; PW-10 carries attributed Adapter evidence but does not authenticate detected OPAC semantics; PW-11 joins current authorization, approval and permission without accepting any passing check as a capability. Formal scope records remain model-only and never allow a live read or write.

## KI-039: PW-08 consumes tokens in memory but does not persist or authenticate human identity

The gate atomically prevents replay within one live instance and binds reload generation so post-reload scope must differ, but it does not persist consumed hashes, cryptographically verify an authorizer or contact an identity provider. A later persistence boundary must preserve consumption evidence across process restart; PW-11 requires authoritative current reload/runtime evidence and refuses a caller-replayed old generation. TEST_ONLY approval remains isolated-only and is never evidence about a real human or formal world.

## KI-040: PW-09 is an isolated file backup foundation, not formal-world backup support

The service handles a quiesced directory snapshot with per-file hashes, atomic publication and exact restore verification. It does not coordinate a live Minecraft server save barrier, authenticate a formal backup operator, persist a backup catalog, encrypt backup contents, upload remotely or implement formal retention deletion. The current restore removes unexpected files only inside a verifier-approved isolated source. Formal or other non-isolated plans return before path access and require a future separately approved architecture stage.

## KI-041: OPAC presence is discovered but its permission API is not integrated

Disposable DeceasedCraft evidence identifies Open Parties and Claims 0.25.8, its default configs and successful isolated server initialization/claim loading. This does not prove stable API signatures, actor semantics, exact-region coverage, thread rules, generation/fingerprint behavior or permission freshness. PW-10A must review and version an Adapter entirely in disposable fixtures. Until then OPAC-backed queries return UNKNOWN through the generic boundary and block construction; no formal claim data may be queried.

## KI-042: PW-11 readiness is not durable execution permission

`DeploymentReadyPlan` is an immutable evidence aggregation and intentionally cannot start a session, reserve or withdraw resources, retain a world object, call a handler or mutate a block. Approval consumption is in-memory, backup evidence belongs only to repository-owned isolated fixtures, and claim evidence remains Adapter-attributed. PW-12 reports this state read-only, but any future executable transition must add a separately reviewed current-state gate and cannot reinterpret readiness as a cached capability or enable formal execution.

## KI-043: PW-12 reports conservative readiness but cannot authorize deployment

The command recalculates a verified physical preview from current loaded runtime evidence, but intentionally reports missing region authorization, claim permission, human approval and verified backup/restore. Its risk is therefore conservative and readiness remains BLOCKED. It does not inventory player resources, load unauthorized chunks, persist approval consumption or invoke a claim-mod API. A future executable player-world stage must supply independently authenticated fresh evidence and a new final gate; PW-12 output, preview hashes and command success codes are never capabilities.

## KI-044: FS-02 proves the read-only mechanism in fixtures, not a completed formal survey

The guard has typed path/operation/audit/fingerprint fixture coverage, but no `D:\PCL2` save has yet been opened through it. Real discovery remains blocked until the bounded offline NBT/Anvil parser and survey budget pass, and any multiple-world result must stop before region access. A passing guard is read authority only; it is never execution, inventory-content, backup or approval authority.

## KI-045: FS-03 survey records are evidence schemas, not parsed world truth

The models enforce provenance, privacy, confidence and non-execution invariants but do not themselves authenticate NBT, classify Create resources, infer topology or score a real candidate. Only the bounded FS-04 parser and later deterministic survey services may populate them. Callers cannot treat a well-formed record as runtime power, connectivity, permission or deployment readiness.

## KI-046: FS-04 is intentionally version- and data-surface-bounded

The offline parser currently accepts only Minecraft 1.20.1 DataVersion 3465 and its tested 4–12 bit non-crossing section palette layout. Unsupported versions, compression modes, external chunks and malformed structures remain typed partial evidence; the parser never guesses, repairs or upgrades a save. BlockEntity output deliberately omits inventories, item stacks and arbitrary payloads, so it proves only type/location presence. Actual budget accounting, later classification/topology logic and equal pre/post formal fingerprints are still required before any real survey result can be considered complete.

## KI-047: FS-05 budgets estimates and schedules work; it does not prove coverage

Region byte/chunk/retained-memory values are conservative caller-supplied metadata estimates until the guarded parser accounts for actual reads. Heat ordering is a deterministic sampling heuristic, not evidence that unscanned regions are empty or unimportant. Candidate seeds are supplied by later classification and scoring stages and never imply selection or approval. A `COMPLETE` bounded schedule only means its requested scheduling calls stayed within limits; only FS-12 can combine actual coverage, limitations and equal source fingerprints into Stage-A acceptance.

## KI-048: Guarded save discovery is metadata-only authority

The guard now owns direct `saves` enumeration and `level.dat` lookup, closing the pre-live filesystem-boundary gap. This authority remains limited to at most 256 non-recursive direct entries and two known metadata filenames per candidate. It cannot enumerate or read a region before unique world selection, and a unique identity still grants no claim, inventory, backup, approval, process-start or execution authority.

## KI-049: Offline storage classification proves presence, not capability or contents

Exact vanilla/Create storage blocks are observed presence. Other modded BlockEntities are called storage only from bounded name hints and remain DERIVED_LOW_CONFIDENCE with runtime confirmation required. Neither branch knows capacity, ownership, connectivity, items or fluids. Candidate-bound membership is geometry only, not permission to inspect contents or use resources; a later explicit user authorization is still mandatory for any content read.

## KI-050: Offline adjacency is not a runtime network graph

FS-07 joins only same-dimension unit-adjacent classified positions. Persisted axis compatibility can strengthen like-to-like evidence, but it does not prove Create kinetic network identity, belt direction, chute flow, pipe connection, pump direction, valve state, stress, RPM, capacity or reachability. Machine and storage attachments remain low-confidence, every edge fixes runtime connectivity to unknown, and partial scans must retain explicit unscanned boundaries. Candidate scoring and formal dry-runs must preserve these limitations and may not reinterpret the graph as permission, inventory access or execution readiness.

## KI-051: Candidate score ordering is not site selection or approval

FS-08 applies fixed heuristics to supplied bounded offline facts. A higher score does not authenticate terrain safety, ownership, claim permission, runtime connectivity, resource availability or physical-plan feasibility. UNKNOWN permission, incomplete coverage and protected/unknown findings stay explicit blockers or future authorizations, and even a high-confidence candidate remains `PENDING_USER_SELECTION`. Candidate IDs bind geometry for review but are not approval tokens and cannot create a deployment session.

## KI-052: Claim metadata detection is not permission evidence

FS-09 can record an allowlisted mod ID/version and possible relative config/data/API hints, but it does not load a mod class, query an owner, inspect claim chunks, authenticate an actor or evaluate an operation. Credential, remote and private-database surfaces are deliberately unavailable. OPAC or another detected mod therefore remains UNKNOWN and requires a separately reviewed versioned read-only Adapter; absence of a known descriptor also remains UNKNOWN because spawn/server/other protection may still apply.

## KI-053: FS-10 validates dry-run artifacts but does not create real formal results

The four-member contract proves exact scope, hash and non-authority invariants with synthetic formal fixtures. It does not claim the real survey has found anchors or that the pack custom recipes remain present in the current runtime. FS-12 must generate the four reports from the guarded snapshot; missing recipes or physical feasibility remain explicit unavailable results, never fabricated success.

## KI-054: FS-11 command plans do not perform survey I/O

The parser proves grammar, registry and authority boundaries only. Its relative report key is not a writable path or evidence that a report exists. FS-12 must resolve that key below the verified ignored repository output root and route every formal read through the guard while retaining pre/post fingerprints; no caller may turn a command plan into an arbitrary path or execution request.

## KI-055: Private formal files are metadata-fingerprinted but never content-hashed

To preserve the explicit privacy boundary, playerdata, stats and advancements contribute path, size and mtime to mutation detection but their bytes are never opened. All other ordinary formal files can be fully hashed. A same-size/same-mtime private-file change is therefore not cryptographically detectable; FS12 must still require the game to be quiescent and report this limitation rather than reading private contents.

## KI-056: The accepted real survey is bounded partial evidence, not complete-world coverage

The formal tree contains 59 region files. FS-12 scans 16 selected regions/9,733 chunks; 29 files smaller than an Anvil header are skipped as `REGION_HEADER_INVALID`, 40 dense chunks retain `CHUNK_CLASSIFICATION_BOUNDED`, and topology output is capped at 4,096 relevant nodes. Unscanned dimensions/regions and discarded over-bound findings remain unknown. None may be described as empty, safe or irrelevant, and the survey never repairs the malformed files.

## KI-057: Real candidates do not support formal previews or deployment approval

The eight real candidates are machine-seeded only after natural Create geology exclusion, but all have negative scores, UNKNOWN confidence/claim permission, unverified clear space and unresolved player-building boundaries. They remain `PENDING_USER_SELECTION`. The exact four formal dry-run rows are therefore `BLOCKED` with no preview hash and `FORMAL_WORLD_EXECUTION_FORBIDDEN`; FS-10's synthetic contract evidence must not be substituted for a real formal preview.

## KI-058: FB-01 plan identity and capacity do not prove a completed backup

FB-01 observes usable space once and computes a collision-free target without creating it. Space can change, and another writer could create the target after planning. The plan contains no source read, copy, manifest, completion marker or restore evidence. FB-02 must re-resolve roots, recheck capacity/collision, bind a fresh pre-copy source fingerprint and fail closed on any drift; callers cannot treat `backup-plan:*` as a completed `BackupIdentity`.

## KI-059: Opaque backup transport is not inventory authorization

FB-02 necessarily streams all restorable save bytes, including player-linked files, but its package-private guard exposes only bounded metadata and SHA-256 evidence. It does not decode NBT/JSON, reveal items/counts, summarize private paths in the completion marker or authorize later inventory reads. Hashes can still be sensitive evidence and must remain in ignored backup storage or bounded test fixtures. The production runner and reports must not publish per-player filenames or hashes. A completed copy also does not yet prove a disposable restore drill or create deployment approval; FB-03 remains mandatory.

## KI-060: A verified restore drill is recoverability evidence, not formal restore authority

FB-03 restores only from a completed external backup into a newly computed repository-work target. Exact path/hash and offline WorldIdentity equality prove that this backup can reconstruct its recorded save snapshot; they do not authorize overwriting the formal source, launching the restored world, reading private inventory contents, selecting a candidate zone or issuing approval. Disposable copies and audit logs remain ignored and may contain opaque private save bytes, so they must never be committed or summarized per player. Retention deletion remains unimplemented and requires fresh verification before any future cleanup.

## KI-061: Real candidate packages remain blocked without preview and physical evidence

The accepted FS-12 run produced eight ranked candidates but no valid formal preview hash or verified physical plan because clear space, claim permission and player-building boundaries remain unresolved. FB-04 can package these candidates only with explicit unavailable artifacts and missing authorizations. Their score order is not a site choice, their zero/absent budgets are not estimates, and no FS-10 synthetic hash may fill the gap. FB-05 cannot form an exact approval scope until the user selects a candidate and the required evidence exists.

## KI-062: Approval request availability is not an approval decision

FB-05 can model an exact one-time scope only for a future explicitly selected candidate with complete preview/physical/risk/budget evidence. The real candidates currently have neither selection nor that evidence, so their requests remain `AWAITING_USER_SELECTION` with no scope. Even a fixture `PENDING_USER_APPROVAL` request has decision `ABSENT`. TEST_ONLY belongs only to a disposable restore copy, and the formal hard stop remains unconditional until a separate future execution-pilot stage is explicitly authorized.

## KI-063: Management commands are routing plans, not completed operations

FB-06 command success proves only grammar, registry and authority boundaries. `backup create`, `verify` and `restore-drill` plans do not claim that the operation ran; FB-07 must execute the separately gated real acceptance and write ignored audit evidence. Inventory requests likewise do not authorize reads, and UNKNOWN claims remain blockers. No command accepts a path, starts a session, writes the source or issues approval.

## KI-064: The accepted backup is local recovery evidence, not retention or deployment authority

FB-07 proves one immutable local backup and disposable restore drill against the observed snapshot. It does not encrypt, upload, replicate, schedule or delete backups, and no retention cleanup is implemented. Ignored backup/drill trees contain opaque private save bytes and require the same privacy controls as the original even though this report exposes only aggregates.

The eight real candidates still lack verified previews, physical plans, claim/region evidence and player-building-boundary clearance. Their eight approval requests have no scope or decision. Backup success must not be interpreted as site selection, resource authorization, approval, permission to connect to an existing base or permission to construct in the formal world.

## KI-065: IWP live-world creation gate resolved in the isolated instance

The user created and closed exactly one `Steve Agent Test` world in the
independent `SteveAgent_DeceasedCraft_Test` instance. Offline discovery marked
that exact world, IWP-05 produced a verified backup and disposable restore, and
the later live run accepted region preview, readiness, real gravel/iron-sheet
processing and cleanup. This evidence applies only to the repository-owned
isolated instance. The standalone launcher still shares PCL2
libraries/assets/natives read-only; if those dependencies move, plan validation
fails rather than copying or repairing them.

## KI-066: Windows PowerShell 5.1 launcher incompatibility resolved

The initial launcher used the .NET Core `ProcessStartInfo.ArgumentList`
collection, which is null under Windows PowerShell 5.1. The attempt failed before
process creation and changed no world. The launcher now uses a tested Windows
argument encoder with `ProcessStartInfo.Arguments`; a dedicated 5.1 plan-only
regression verifies spaced paths, safety markers and zero process delta.

## KI-067: Forge inherited-client duplicate module resolved

The first launch that passed PowerShell process construction stopped inside
Forge module resolution because the child profile name was substituted into
`-DignoreList` while the standalone profile actually inherited `1.20.1.jar`.
SecureJarHandler therefore saw the client both as module `minecraft` and the
automatic module `_1._20._1`. The launcher now substitutes the base version for
the child JVM ignore-list entry, restores the child name for game arguments and
fails closed unless `1.20.1.jar` is present. The regression validates the exact
marker; no mod archive or formal PCL2 file is edited.

## KI-068: Test-world restore drills never overwrite the live test save

IWP-05 backup evidence is mandatory before the first pilot mutation, but a
restore test must not endanger the newly created user-visible test world. The
accepted drill therefore restores the verified backup into a fresh sibling
directory beneath `work/writable-world-backups`, verifies exact manifest
equality, and separately proves the live source manifest did not change. This
is recoverability evidence only; it does not authorize IWP-06 execution or a
restore over either the test world or the formal world.

## KI-069: Player-command recovery is intentionally stage-bounded

IWP-09 is complete, but recovery is not arbitrary replay. The player command
persists an exact bounded checkpoint in world `SavedData`. BUILD-mid,
BUILD-complete and the pre-resource CONNECT boundary can resume only after a
fresh exact world/region/journal rescan; reload time is rebased only after that
reconciliation. PROCESS refuses when consumption/output non-duplication is not
provable. VERIFY may perform only idempotent finalization. Any changed
world/region fingerprint, owner, authority, trusted definition or journal fails
typed. This limitation is the accepted safety contract, not an open IWP item.

## KI-070: Runtime Create changes require ownership-aware cleanup

The accepted live run exposed two Create behaviors that strict snapshot
equality alone cannot safely handle: connected belts can remove adjacent belt
blocks when one segment is cleared, and Create may change only a belt's runtime
managed `part` property. Cleanup now treats an already-air journal position as a
safe cascade result and permits only that one belt property transition. It still
refuses a changed block, facing, slope, axis, unrelated property, block entity,
out-of-region position or non-journal position. The original partial cleanup
left two journal-owned belt cells, which the user explicitly cleared with one
bounded `/fill`; no unknown block was removed.

## KI-073: The first public-alpha visual candidate is superseded

The A01 PCL2 run passed setup marking, stopped-game portable backup, restart
verification and safe region preview. It then found two candidate defects while
`worldMutation=false`: the gravel x3 two-node assembly was offset from the
region center, and readiness rejected an otherwise verified backup when the
instance and backup directories were disjoint siblings on the same drive.

The replacement centers one- and two-module layouts within the selected region
and models the public portable backup as an explicit external-disjoint root
relationship. Exact source/target containment, forbidden roots, overlap,
traversal and restore-target escape still fail closed. Pure tests, Forge layout
tests, 14 goal-driven GameTests, both two-process recovery fixtures and the
23-task clean build pass. The prior JAR must not be published or counted as a
visual PASS; the committed replacement still requires the complete PCL2
lifecycle.

## KI-074: Embedded license line endings are normalized

The first post-fix offline A/B produced two identical JARs, but the long-lived
main checkout produced a different hash. Per-entry comparison isolated the
difference to `META-INF/LICENSE-steve-industrial-agent` and
`META-INF/NOTICE-steve-industrial-agent`: a fresh Windows clone honored global
`core.autocrlf=true`, while the older checkout retained LF bytes. `.gitattributes`
now fixes both source files to LF. No class or runtime resource differed, but
the earlier hashes are not final release evidence.

## KI-075: C-06 through C-10 contracts are not physical capability acceptance

The Phase III-B milestone inventories and models four fan-processing modes,
item-only cutting, Mixing Phase I, Compacting Phase I and Deployer Phase I,
then maps supported recipes into the frozen executor contract and supplies
version-confined read-only observers. Its standard runtime fixture proves the
descriptor/DAG boundary and typed observer failures; it deliberately reuses an
already verified physical plan only as structural contract input.

The Phase IV implementation now supplies verified capability-specific physical
plans and has passed the concentrated C-06-through-C-10 real runtime gate:
Direct, two physical Bots and Hybrid obtain same-session live input-consumed and
output-observed evidence; exact BUILD-prefix recovery, three-mode cancellation
and the concentrated fault matrix also pass. This supersedes the earlier
Phase III semantic `BOTS=UNSUPPORTED` limitation only for the exact Phase IV
handlers and bounded test contracts.

Product-level C-06 through C-10 nevertheless remain in progress. Full
regression, Site Preparation/fleet integration and the one authorized
player-visible acceptance are still open.
The DeceasedCraft census remains profile-specific and grants no authority to
execute in a formal player world.

## KI-076: C-05 is a bounded crushing proof, not general crushing automation

C-05 now has real Direct/Bots/Hybrid execution, but its production handler is
deliberately limited to the trusted typed crushing process and fixed
opposed-wheel/hopper/chest topology. The standard physical test executes
`create:crushing/gravel`; the DeceasedCraft probe selects and describes a safe
raw-copper recipe from the live pack catalog but does not execute that pack
recipe in a player world at this checkpoint.

Optional byproducts are observed rather than guaranteed. Automatic raw-material
mining, arbitrary entity/container interaction, existing-factory attachment,
repair, throughput optimization and compensation after resource injection are
unsupported. Recovery is limited to exact pre-resource BUILD boundaries;
resource-bearing or irreversible checkpoints fail closed. The passing
disposable-profile evidence grants no authority for `D:\PCL2` or any formal
save. Visible Phase IV acceptance is owned by the later single integrated
Site-Preparation plus C-05-through-C-10 gate.

## KI-077: C-06 handler wiring is not yet physical fan acceptance

The C-06 runner, exact v606 handler, materialization, implementation geometry
and dangerous-medium routing policy pass their first-level gates. The shared
Forge gate now also proves the selected live washing recipe, real Depot
transformation, three-mode equivalence, cancellation, exact BUILD-prefix
recovery and bounded fault cleanup. Dangerous-medium Bot exclusion and Hybrid
fallback remain enforced by the immediate typed policy tests.

The handler is deliberately item-only and accepts exactly one guaranteed live
output. Probabilistic outputs, arbitrary existing factories, arbitrary
containers/entities, fluid routing, player inventory, world attachment and
resource-bearing recovery remain unsupported. C-06 will not run an independent
PCL2 visible acceptance; that gate is owned by the later integrated
Site-Preparation plus C-05-through-C-10 acceptance branch.

## KI-078: C-09 lava input is bounded, not general fluid compacting

The C-09 Basin/Press contract, exact v606 handler, implementation geometry,
verified-plan materialization and goal-driven mapping support counted ITEM
inputs plus exact optional FLUID inputs. A real Create 6.0.6 granite recipe now
withdraws one whole lava bucket from the material source, pours exactly 100mB
into the Basin before Create's authoritative recipe match, consumes all counted
ingredients through the real press cycle, journals the fluid and leaves the
final Basin empty. Direct, three-Bot and Hybrid runs share one verified plan and
produce the same unique result, material settlement, reload and cleanup.

The boundary is still deliberately narrow: `BasinHeatMode.NONE`, one execution,
reviewed ordinary buckets, deterministic ITEM output and no byproduct/residue or
unknown NBT. Chocolate and honey have no reviewed item-form container binding;
multi-cycle fluid orders, fluid outputs and HEATED/SUPERHEATED compacting remain
typed unsupported. If exact failure cleanup cannot remove an injected fluid,
the handler refuses to recreate its bucket. Resource-bearing reload and
post-feed compensation remain unsupported. Final player-visible macOS and later
PCL2 acceptance are not inferred from the automated physical gates.

## KI-079: C-08 HEATED and water input are bounded, not general fluid mixing

The C-08 counted Basin/Mixer contract, NONE/HEATED handler,
implementation geometry, verified-plan mapping and goal-driven session pass
their first-level gates. The macOS gate now also proves the real
`create:mixing/brass_ingot` HEATED recipe consumes copper and zinc, reserves
one exact ordinary coal before start, ignites a real captured Blaze Burner
through Create's insertion API, completes a real Mixer cycle and delivers the
same result through Direct, Bots and Hybrid.

HEATED uses an additive, fingerprint-bound metadata sidecar so the frozen
logical/bound/physical graph contracts remain unchanged. The sidecar is bound
to the exact root session and step; concurrent reservations subtract committed
fuel from the same plan-owned buffer, never read a player inventory or
arbitrary container, release on cancel, and release/reverify/reacquire only at
the exact pre-resource reload boundary. Consumed fuel is never compensated.

One additional slice accepts exact water input for one execution. A 250mB recipe is
quoted as one whole water bucket, the plan-owned buffer loses exactly that bucket, the
handler injects the declared water before Create's authoritative `BasinRecipe.match`,
and the journal retains the FLUID resource. The real `create:pulp` gate observes one
output and an empty final Basin. If failure cleanup cannot drain the exact poured fluid,
the bucket is not recreated; independent ITEM inputs remain returnable.

Both accepted slices are deliberately one exact Basin/Mixer execution. Multi-execution
fuel/fluid budgeting, SUPERHEATED/SEETHING, non-water input, fluid output, crafting
remainder, probabilistic or multiple output, unknown NBT, post-resource compensation
and resource-bearing recovery remain unsupported. Composite stages still have an
ITEM-only material contract: they prefer a non-fluid recipe or fall back to a
single-machine order, never omit a fluid requirement.
C-08 will not run an independent PCL2 visible acceptance and cannot be marked
complete before the integrated Site-Preparation plus C-05-through-C-10 gate.

## KI-080: C-10 bounded world-workpiece route is not yet goal-selected

The C-10 item-only Deployer contract, exact held-item policy, fixed downward
Deployer/owned-Depot topology, distinct two-input implementation, v606 handler,
verified-plan materialization and goal-driven session pass their first-level
gates. The shared Forge gate now proves a running Create 6.0.6 Deployer
completes the selected live `ItemApplicationRecipe` through Direct/Bots/Hybrid;
the concentrated fault, pre-feed cancellation, exact BUILD recovery and
ownership-aware cleanup gates preserve the bounded resources.

Phase I is deliberately limited to deterministic item application on the
plan-owned Depot. It never grants entity/combat/arbitrary block use, container
opening, player inventory, private storage, unknown NBT or unknown world-side
effect authority. Bots may build and supply the typed components only; they
cannot invoke a generic right-click. The handler observes live Depot output and
the Deployer held-item before/after delta and never constructs the declared
product.

Current bound-plan materialization selects the safe `CONSUMED` disposition.
Although the typed handler accepts `RETAINED`, common recipe metadata and
material reservations must transport the live keep-held-item flag before
general retained-tool goal execution. Resource-bearing reload remains typed
unsafe until the common recovery gate. C-10 will not run an independent PCL2
visible acceptance and cannot be marked complete before the integrated
Site-Preparation plus C-05-through-C-10 gate.

The user-authorized bounded expansion now has a distinct typed plan/policy and
real v606 handler for only
`create:item_application/andesite_casing_from_log`. Its 10/10 immediate Forge
gate proves the real block transition, no container/entity/NBT access,
pre-resource exact cancellation, completed-machine cleanup, and full shared
Direct/Bots/Hybrid three-task execution. Bots reuse the existing Steve/Alex
entity and fleet executor/coordinator; Hybrid reuses the existing router and
retains the exact Direct/Bots physical provenance. No second scheduler or
generic interaction primitive was added. It does not
grant generic block use: plan/session/region/coordinate, initial block, held
item and result block are immutable and allowlisted. The remaining issue is
goal selection/integration, not physical feasibility. The shared Executor Contract task graph
now carries the exact authority envelope and declares Direct/Bots/Hybrid. The
handler's exact pre-resource two-entry checkpoint/recovery also passes a real
no-duplication Forge cycle. The Direct physical backend is now bound and
reuses the handler; runtime recipe/goal selection and the existing Bot/Hybrid
physical workers must still adopt this new typed route before it can
contribute to the later complete checkpoint.

The reviewed route also binds its sole resource-buffer coordinate into the
typed plan and verified region. Handler begin/recovery derive that coordinate
from the plan and expose no alternate-container argument, preventing a caller
from redirecting the held-item read to unrelated storage.

## KI-081: Phase IV-S completion does not grant formal-world authority

The terrain adapter, physical isolated GameTests and automated hand-off to the
existing C-07 executor complete the Phase IV-S integration gate, but they do
not authorize deployment to
`D:\PCL2`, a formal save or Public Alpha. Site preparation still requires an
exact world/dimension/selection/snapshot/approval chain, refuses protected and
unknown blocks, and emits `PreparedConstructionSite` only after its mandatory
post-clearance rescan. The remaining player-visible acceptance is intentionally
owned by this Phase IV integration task and must combine site preparation with
the construction flow in one isolated client after backup and in a new region.

## KI-082: RESOLVED for player sources; warehouse dispatch remains IE-MP-02 scope

The player-selected half is now a transactional order rather than a low-level
adapter claim. It reserves the exact structure, hammer, live recipe inputs and
coal from only the named chest, carries them with visible Bots, persists every
stage, operates the real machine, delivers one order-tagged output exactly once,
returns leased materials and emits the common balanced report. Three two-process
recovery windows plus cancellation while the courier carries a withdrawn stack
prove duplicate withdrawal/return/output and unaccounted/private counters remain
zero; see `work/logs/ie-physical-acceptance-20260810-164418.log` and the six logs
emitted by `scripts/test-ie-alloy-recovery-reload.sh`.

The prohibitions remain: no nearby-container discovery, free fuel, direct player
inventory access or constructed output. IE-MP-02 is still in progress only because
the long-running warehouse dispatcher does not yet admit this reviewed order.
