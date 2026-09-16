# Repository-owned Bot worker game acceptance

Date: 2026-07-23 (Asia/Shanghai)

This milestone closes the game-owned portion of Phase III-A A-03 through A-05.
It intentionally implements the Phase I bounded source as an isolated,
repository-owned GameTest adapter. It is not enabled in a normal game, does not
claim a third-party Bot mod, and does not authorize a formal player world.

## Safety boundary

The adapter fails closed unless all of the following are true:

- `steve_industrial.test.botFleetGameTest=true`;
- the current game directory is the exact Gradle-provided repository fixture;
- `.steve-industrial-bot-test` contains
  `steve-industrial:isolated-bot/v1`;
- the loaded world is below that exact fixture directory;
- the call runs on the authoritative server thread;
- the target is loaded and inside the bounded test region.

The worker does not read player inventory, open private containers, attack an
entity, or right-click an unknown block. Material access is limited to an exact
dedicated test chest. Placement is limited to the fixture allowlist, removal is
limited to an unchanged block owned by the same session, and the only machine
interaction fixture is an exact lever state transition. Movement is adjacent
bounded path traversal; there is no teleport fallback.

## Physical and recovery evidence

`TestOnlyServerBotWorker` binds loader-neutral task/assignment identities to a
visible ArmorStand identity and exact game positions. Every chest removal,
delivery, leftover return and visible held item is reconciled with the
`ReservationLedger`. Reload is accepted only at an idle boundary with the same
entity UUID, exact material snapshot, physical chest counts, visible held item,
owned block states, session, region and monotonic tick. Drift fails closed.

The three GameTests prove:

1. one visible Bot performs navigate, fetch two items, carry, transport, place,
   verify, safe lever interaction, remove the session-owned block, return one
   leftover item and reload without duplicate withdrawal;
2. blocked movement repaths, cancellation returns the worker to idle, and both
   out-of-region work and revoked authority are refused without mutation;
3. two physical Bots execute two material branches concurrently, retain two
   work-position reservations and perform dependent placement only after their
   own fetch/transport predecessors complete.

## Verification

Command:

```powershell
.\scripts\Test-BotFleetGameTest.ps1
```

Result: `All 3 required tests passed`; Gradle `BUILD SUCCESSFUL` in 54 seconds.
The script also asserted two visible entities, adjacent path/repath evidence,
the physical source and placement counts, no player/private inventory reads,
no crash report and no residual server process.

Log:

`work/logs/bot-fleet-gametest-20260723-005325.log`

No formal save or launcher instance was opened or modified. The configured
`D:/PCL2` identity is used only as the fail-closed forbidden-root check.

## Integrated player-shaped Bot follow-up

The integration branch now uses a repository-owned `ConstructionBotEntity`
rendered with the normal wide player model. Steve represents the logistics
role; Alex represents the builder/inspector role. In Bots mode, logistics owns
material delivery while builder/inspector owns construction and final
verification, so both workers perform non-zero visible work.

The first isolated visible iron-sheet run proved both role assignments and real
output, but also exposed that final verification reused the logistics delivery
cell. The follow-up selects a second safe verifier station while explicitly
excluding the logistics station. Completion construction rejects duplicate Bot
final positions, and the player command reports both role positions plus
`botOverlap=false`.

Automated post-fix evidence:

- `work/logs/goal-driven-execution-gametest-20260723-184939.log`: 18/18,
  C-03 and C-04 each record two active role workers and distinct completion
  coordinates;
- `work/logs/core-test-20260723-185238.log`: core 416 plus Adapter API 48;
- `work/logs/build-20260723-185357.log`: 23/23 tasks and 476/476 XML tests,
  zero failures/errors/skips, artifact isolation PASS.

The committed post-fix JAR at
`1effc7d4f12ae0126277a3c914f82a556fe3b03e` completed the final visible
isolated run. It produced iron sheet 2/2, reported Steve logistics at
`-846,149,3001` after one assignment and Alex builder/inspector at
`-845,149,3002` after two assignments, and emitted `botOverlap=false`. The user
visibly confirmed the workers were separated. Journal-only cleanup removed no
unknown block, a final region scan returned to the 256-block platform baseline
with zero BlockEntities or containers, all dimensions saved, and the run left
zero crash reports or residual Java processes. Evidence:
`work/logs/iwp-visible-bot-spacing-20260723-191220.log`.
