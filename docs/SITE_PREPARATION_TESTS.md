# Site preparation tests

Automated coverage is split by boundary:

- Pure JVM: anchors, corners, four facings, projection, cross-dimension and
  out-of-region refusal, classification, exact token binding, stale/revoked/
  expired/consumed tokens, budget, graph bounds and rescan identity.
- Forge unit/integration: command grammar, loaded-chunk-only survey, state
  fingerprinting, particle-only preview, container privacy, actual drops,
  salvage delivery, conservative leveling and reload reconciliation.
- GameTest: one/two Bots, path conflict, no teleport, tool/destination failure,
  cancellation, reassignment, safe clearing, protected obstacle refusal and
  new obstacle after clearing.
- Visible isolated test: natural-obstacle success and protected-container
  refusal.

Every acceptance reports:

`unknownBlocksRemoved=0`, `protectedBlocksRemoved=0`, `containerOpened=0`,
`playerInventoryAccess=0`, `regionOutsideMutations=0`, `formalWorldAccess=0`,
`botOverlap=false`, `teleportFallback=false`.

## SITE_PREP_CONTRACT_V1 verification

Run from the repository root on 2026-07-24:

```powershell
.\scripts\Invoke-Gradle.ps1 -PskipGameModules=true :core:compileJava
.\scripts\Invoke-Gradle.ps1 -PskipGameModules=true :core:test --tests dev.stevecreate.agent.core.siteprep.SitePreparationContractTest
.\scripts\Invoke-Gradle.ps1 -PskipGameModules=true :core:test
```

Results: PASS. The contract suite passed 7/7 methods; the complete core suite
completed successfully. No Forge, Minecraft or Create imports exist under the
loader-neutral `core.siteprep` package.

## Phase IV-S shared fleet completion

Run from the repository root on 2026-07-27:

```powershell
.\scripts\Test-Core.ps1
.\scripts\Build.ps1
.\scripts\Test-SitePreparationGameTest.ps1
.\scripts\Test-BotFleetGameTest.ps1
```

Results: PASS. The full build reports 500 aggregate JVM/Forge tests with zero
failure, error or skip. Seven isolated terrain GameTests prove real drops,
exact salvage, 2- and 5-Bot shared-kernel work, reload/rescan/reassignment,
terminal cancellation, protected/hazard refusal and exact
`DEMOLITION_APPROVAL_STALE` refusal with zero mutation. The unchanged shared
construction fleet then passed its five physical 2/3/5-Bot tests. Evidence:
`work/logs/core-test-20260727-175156.log`,
`work/logs/build-20260727-180011.log`,
`work/logs/site-preparation-gametest-20260727-180134.log` and
`work/logs/bot-fleet-gametest-20260727-175426.log`.

The final player-visible site-preparation scenarios are intentionally not run
in this branch. They remain assigned to the main Phase IV integration task so
that terrain and construction are accepted together in one isolated client.

## Integrated explicit grading WIP

The integrated branch adds this bounded player flow:

```text
/industrialagent site grade corner1 <x> <z>
/industrialagent site grade corner2 <x> <z>
/industrialagent site grade height <surface-y>
# Look directly at one dedicated ordinary supply/salvage chest:
/industrialagent site grade supply
/industrialagent site grade start <namespace:block>
```

`start` scans incrementally and begins shared-fleet Bot grading only when the
snapshot is ordinarily approvable. If it contains a container, block entity,
dangerous medium or unbreakable/protected cell, no mutation occurs. The command
prints the exact risk list and one warning hash. After reviewing the permanent
data-loss warning, the player may run:

```text
/industrialagent site grade force-confirm <warning-hash>
```

This is not a wildcard confirmation. A second full scan must match the player,
expiry, rectangle, height, fill state, every relevant block fingerprint and
the complete risk set. Any drift requires a fresh `start` and warning review.
The confirmed path replaces only those exact risk cells with air without
reading or transferring container/block-entity data. `status` and `cancel`
remain available throughout.

Automated evidence on 2026-07-28 is 10/10 Site Preparation GameTests in
`work/logs/site-preparation-gametest-20260728-160309.log`, including physical
slope cutting, hole filling, exact material consumption, final surface rescan,
default high-risk refusal, and second-confirmed chest/TNT/bedrock removal with
nonzero destructive counters. Full loader-neutral regression is 550/550,
Forge JVM is 33/33, the clean build is 583/583 aggregate XML tests, and the
integrated visible-equivalent production matrix remains 22/22. The backed-up
new-region player-visible run remains pending; no PCL2 instance was launched
for this automated gate.
