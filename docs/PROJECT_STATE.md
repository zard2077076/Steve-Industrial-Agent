# Project state

Updated: 2026-09-16. This is the short current status; older evidence is available with
`git show cbac708:docs/PROJECT_STATE.md` (no duplicate archive in the working tree).
Priorities are maintained in [MASTER_PLAN](MASTER_PLAN.md), not copied into four ledgers.

## Verified baseline

- Branch: `feature/phase-iv-player-ux-site-workflow`.
- `79a2c1f`: derived-goal search validates session/query identity, rejects stale replies,
  coalesces typing, preserves search/quantity on resize, and localizes loading/empty states.
  The goal-driven physical suite passed 64 tests, including a real searched
  `minecraft:blue_concrete` player order. Evidence:
  `work/logs/goal-driven-execution-gametest-20260904-222644.log`.
- `cbac708`: C-04 two-process recovery passes. Native empty-world sleep, not missing power
  or an execution budget, stopped the recovered Press dispatch. Only the dev fixture now
  resets that timer. The recovered BUILD journal is compared exactly, replacing stale
  creative-topology counts. Final result: 30 unchanged entries/28 positions, one real iron
  sheet, one input/process event, no duplicates, no crash report. Evidence:
  `work/logs/c04-recovery-reload-{write,read}-20260905-081413.log`.
- `./gradlew test build --no-daemon --offline` passed 932 tests, zero failure/error:
  `work/logs/c04-recovery-full-build-20260905.log`. These are dated observations,
  not fixed future test counts. Windows execution of the matching wrapper is deferred.

## Real Mac client evidence already obtained

All runs used the designated external `Steve Agent Mac Acceptance` world, through the
mandatory launcher. These results do not claim all products or the latest UI labels passed.

- Terminal/search: real empty query showed the reviewed 11 rows; `concrete` showed 16
  derived targets including blue concrete. Terminal texture was visible, not purple/black.
  Captures: `~/SteveAgentPlayerAcceptance/screenshots/ux04-terminal-goal-picker.png` and
  `ux04-goal-search-concrete.png` in the same directory.
- Metal Press project `bcbe4dce-1be5-4e23-b5fc-32cac876f307`: planned/withdrawn 16,
  consumed 2, returned 14, 2400 FE, one plate, restored baseline, balanced ledger and
  zero duplicate/private/unaccounted counters.
- C-03 project `b5af59d9-359d-43f1-9855-d444b30ebab2`: gravel 3/3, planned/withdrawn/
  consumed 52, balanced ledger, zero duplicate/private/unaccounted counters.
  The dev-only pause-on-lost-focus correction closed the old PauseScreen failure.
- Composite/01 BOTS project `02a18200-4708-4071-bd75-c6fdc26a08b4`: cogwheel 1,
  salvage transfer 5, balanced ledger, restored baseline, zero integrity counters.
  Quoted fixture IDs and nonce-bound preparation fixed the previous empty-chest refusal.
  The responsive report screenshot verified scrolling without row/footer overlap.

## Current work

- User-authorized instruction audit is complete: current guidance is concise, five obsolete
  Markdown files and two redundant personal skills removed, eight retained/published skills
  validated, historical evidence preserved in Git. See INSTRUCTION_AUDIT for scope and recovery.
- Focused instruction follow-up completed using skill-creator: narrower complexity-skill
  triggers, shorter conditional CLI guidance and reuse of unchanged validation. Details
  and exact before/after counts are in INSTRUCTION_AUDIT.
- Shared C-03/C-06--C-10 runner and live-catalog fixture are implemented. Runner/installed
  CLI checks passed 88 cases (6 real-client-related cases deselected); these Python paths
  were unchanged during the subsequent Java fix, so their results were reused.
- The shared player-acceptance follow-up now waits for the teleported client position before
  inspecting or seeding the fixture, keeps the salvage chest outside the reviewed construction
  and clearance footprint, and clamps the demolition-approval content and button inside short
  windows. Focused Harness coverage passed 79 tests and
  `./gradlew test build --no-daemon --offline` completed successfully on 2026-09-16.
  This is server/runner/build evidence; it does not claim a new real-client C-06 pass.
- The first 2026-09-07 physical run passed 64/65 and exposed a shared production bug:
  the material snapshot included route endpoints while construction prices/builds only
  interior cells. `VerifiedPlanMaterialSnapshot.from` now uses the existing
  `PhysicalRoute.interiorPositions()` contract; the factory's missing-material refusal
  remains intact. Failed evidence: `work/logs/goal-driven-execution-gametest-20260907-173302.log`.
- After the fix, all 65 server tests passed, including exact source inventories and nonce
  markers for C03, C06--C10 and derived blue concrete; invalid quantity/nonce/target requests
  left the test sentinel unchanged. Wrapper output: `work/logs/create-fixture-fixed-suite-20260907.log`.
  Full offline test/build passed: 932 tests, zero failures/errors, evidence
  `work/logs/create-route-material-build-20260907.log`. No new real-client PASS is claimed.
- Next: validate these player workflows with Computer Use, especially the C06 material
  preview/reservation and exact completion report. The current 2026-09-07 Computer Use
  attempt reports the Mac is locked and automatic unlock failed; manual unlock is needed
  for UI work. Do not bypass the lock. Backend development can continue independently.
- Computer Use is the preferred real-UI path; the bridge supplements structured evidence.
  The new `cua` interface initializes successfully. At the last 2026-09-05 check there was
  no Java/Minecraft OS process and no corresponding application in the UI inventory;
  a separate display check returned zero active displays. Recheck when testing is due.
  An app-list miss alone is never proof that a user-visible Java window is absent.

## Outstanding validation and product gaps

- Complete C-01--C-10, Composite and multi-Bot real-player representative coverage;
  current C-06--C-10 server evidence is not client evidence.
- Remaining UI/world matrix: rotations/layouts, protected cells, missing/full salvage or
  return chests, multiple material sources, slot reordering, actual reservation drift,
  RETURN_PENDING, explicit salvage transfer and material-movement restart.
- Long-lived disposable worlds may contain old Bots/drops; identify current-order ownership
  before counting workers. Do not silently clear failed scenes or restart an existing game.
- Latest loading/empty UI labels remain visually unverified after GLFW's primary-monitor
  failure. Mac human UX judgement and Windows/PCL2 remain separate outstanding evidence.
- Mekanism has no admitted real runtime dependency/executor yet; nuclear/radiation automation
  remains disabled. Full MegaFactory/automatic expansion/moving-factory vision is not done.

See [KNOWN_ISSUES](../KNOWN_ISSUES.md) for current issue routing and
[INSTRUCTION_AUDIT](INSTRUCTION_AUDIT.md) for the instruction-cleanup findings.
