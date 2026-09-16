# Bot, material and fleet core milestone

Date: 2026-07-23 (Asia/Shanghai)

## Scope

This milestone supplies the loader-neutral core for A-04 through A-06. It does
not claim a Minecraft entity, a real navigation result, a container mutation or
a visible Bot acceptance. Those game-owned bridges remain the next A-03 task.

`BotWorker` is the only controlled-worker boundary. A complete immutable
`BotWorkerSnapshot` carries the stable worker identity, IDLE/BUSY/OFFLINE/DEAD
status, bounded Bot-only inventory, exact assignment identity, authorized
region identity, health/load state and explicit safe-action capabilities. It
does not expose a game entity, player inventory, world object or arbitrary
interaction callback. `BotFleetExecutor` dispatches only an exact BOTS
assignment to a registered healthy worker with every task-specific capability;
HIGH_RISK_INTERACTION is refused before the worker is called.

`ReservationLedger` accepts only `TEST_ONLY_BOUNDED_SOURCE` or
`AUTHORIZED_DEDICATED_CONTAINER` material sources already represented by the
frozen contract. Its synchronized transitions reserve, withdraw, deliver,
return and release exact quantities. Bot capacity, source/session authority,
loaded state, delivery arithmetic, carried inventory, row identity,
generation, snapshot time and collection counts all fail closed. Cancellation
cannot discard carried material, and reload cannot replay a withdrawal or
restore an overcommitted/tampered state.

`BotFleetCoordinator` deterministically schedules two to five workers across
the verified task DAG. It provides bounded retry/reassignment, exclusive work
positions, capability/health/load gates, path collision and head-on crossing
rejection, wait-for deadlock detection, sequential dependency release, global
cancel and reload reconciliation. Active work with an observed result becomes
reconciliation-required after reload; it is never blindly replayed. A failed
worker is not reassigned until exact `RECOVERY_RECONCILED` evidence belonging
to the prior assignment satisfies the task recovery policy.

## Automated acceptance

The focused suite contains 14 passing methods:

- six Bot executor/fleet methods: capability/high-risk refusal, two-worker
  parallel transport followed by sequential installation, exact failure
  reconciliation/reassignment, pending reload without replay, global cancel,
  and collision/deadlock/no-teleport checks;
- five material-ledger methods: exact reserve/withdraw/deliver/return/cancel,
  concurrent over-reservation refusal, capacity/source/carrying-cancel refusal,
  reload continuation, and tamper rejection;
- three frozen-contract methods including loader neutrality for the new Bot,
  fleet and material surfaces.

The post-review focused command passed:

```powershell
.\scripts\Invoke-Gradle.ps1 -PskipGameModules=true :core:test --tests dev.stevecreate.agent.core.execution.construction.BotFleetExecutorTest --tests dev.stevecreate.agent.core.execution.construction.ReservationLedgerTest --tests dev.stevecreate.agent.core.execution.construction.ConstructionExecutorContractTest
```

Final regression evidence:

- `work/logs/core-test-20260723-002317.log`: Test-Core PASS, 406 core plus
  38 Adapter API methods, zero failures/errors/skips;
- `work/logs/build-20260723-002436.log`: clean build PASS, 23/23 tasks,
  453 aggregate XML methods (406 core, 38 Adapter API, 9 Forge), zero
  failures/errors/skips, artifact isolation verified;
- final log scan found zero fatal exception/thread/process signatures, and the
  host readback found zero residual Minecraft/GameTest/server Java processes.

## Deliberate limitations

- No third-party Bot mod is assumed or required.
- No worker implementation may read a player inventory or arbitrary/private
  storage; the future game bridge must use a repository-owned test-only source
  or an explicitly authorized dedicated container.
- Core does not compute a Minecraft path, load a chunk, grant a region, teleport
  around a failed route, open a block, attack an entity or execute an unknown
  right-click.
- Material ledger transitions model the exact authoritative transaction but do
  not themselves mutate a container. The versioned game adapter must perform
  physical readback and commit the matching transition on the server thread.
- JVM fixture evidence proves scheduler and contract semantics only. A-03 and
  A-08 still require isolated real block/entity/container evidence and visible
  acceptance in `SteveAgent-Executor-Test`.
