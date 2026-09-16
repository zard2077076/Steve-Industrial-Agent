# Isolated Writable Player World Pilot

The pilot is restricted to the repository-owned ignored instance at
`work/isolated-player/SteveAgent_DeceasedCraft_Test/run`. `D:\PCL2` remains an
explicit forbidden root and a read-only source for pack JARs plus shared launch
libraries/assets/natives. Formal saves, account/session files, server lists,
options, logs, screenshots, chat and inventory data are neither copied nor read.

The writable world must be the only direct save in that game directory, be named
`Steve Agent Test`, and contain `.steve-industrial-writable-test-world` with the
exact value `ISOLATED_WRITABLE_TEST_WORLD`. The game directory also requires its
separate instance marker. Both markers, exact canonical paths, the formal root,
the formal WorldIdentity, runtime fingerprint, world fingerprint and occupancy
are rechecked before a pilot command proceeds.

The completed surface includes region selection/preview/confirmation, typed
readiness/dry-run, visible start/status/cancel, journal cleanup and durable
stage-bounded recovery commands. See `PILOT_COMMANDS.md` for the complete list.

The first writable acceptance used:

```text
/industrialagent pilot region here 64 64 32
/industrialagent pilot region preview
/industrialagent pilot region confirm
/industrialagent pilot readiness minecraft:gravel 3
/industrialagent pilot dry-run minecraft:gravel 3
/industrialagent pilot start minecraft:gravel 3
/industrialagent pilot cleanup preview
/industrialagent pilot cleanup
/industrialagent pilot readiness create:iron_sheet 2
/industrialagent pilot dry-run create:iron_sheet 2
/industrialagent pilot start create:iron_sheet 2
```

Preview displays particles only and scans at most 4096 cells per server tick. It
places no blocks and reports non-air, danger/fluid, BlockEntity, container and
unloaded-cell counts with a complete region fingerprint. Confirmation is refused
until that scan completes and becomes stale if world, dimension, player, session,
expiry or the region fingerprint differs.

The user-visible run on 2026-07-20 observed gravel `required=3 observed=3` through
two real millstone nodes and iron sheets `required=2 observed=2` through the real
belt/press path. The run exposed water escape and belt cleanup issues. The water
plan now has typed/journaled containment (`outside=0` in all three orientations),
and cleanup accepts only the belt's runtime-managed `part` transition plus
already-air cascade results.

The 2026-07-22 final visible acceptance held iron sheet x2 at BUILD, saved to
title, re-entered, inspected `PERSISTED stage=BUILD`, resumed after exact rescan
and completed every visible phase with 2/2 output and no duplicate placement,
consumption or output. A gravel x3 BUILD hold was cancelled and then previewed/
cleaned with one owned removal and zero unknown removals. Fourteen goal
GameTests separately cover BUILD-mid, BUILD-complete/CONNECT, PROCESS refusal
and VERIFY idempotence. IWP-01 through IWP-11 are COMPLETE. Formal `D:\PCL2`
launches and writes remained 0/0.
