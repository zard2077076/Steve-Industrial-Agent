# DirectWorldExecutor migration

Status: COMPLETE on Phase III-A, 2026-07-22 (Asia/Shanghai).

## Result

The accepted C-03/C-04 goal-driven v606 path now crosses the frozen construction
backend boundary on every root tick and typed cancellation:

```text
ExecutionReadyPlan
  -> ConstructionTaskGraph + Direct TaskAssignment/TaskOwnership
  -> DirectWorldExecutor
  -> one existing bounded v606 root action
  -> TaskExecutionResult + assignment-bound output/cancellation evidence
  -> existing CreateV606GoalDrivenExecution compatibility result
```

The core `DirectWorldExecutor` is loader-neutral. Its backend may capture a
trusted server world, but no Minecraft, Forge or Create object crosses the core
API. Exact executor-capability declarations, graph/task/assignment identity,
ownership, cancellation, recovery policy and result tick are checked before or
after every bounded backend call.

## Preserved v0.1 safety

- `ExecutionReadyPlan` remains the only accepted physical authority. Preview,
  region authorization, readiness evidence and live target validation still run
  before a root session exists.
- `CreateExecutionWorldGuard` remains inside the exact v606 handlers and route
  builder. Revoking the isolated-gameDir property after session construction
  still returns `FORMAL_WORLD_FORBIDDEN` before a world mutation.
- Existing C-03/C-04 handlers, `BoundedStepRunner`, physical evidence rules,
  journals, rollback reports, cleanup and recovery codecs remain the physical
  source of truth. They were adapted, not replaced.
- One Direct executor call invokes at most one existing bounded handler action.
  `TaskExecutionResult.worldMutationCount` counts that bounded action, while the
  journal retains every exact block change it caused. In particular, one
  Create-native belt connection is one handler action and three retained journal
  entries; none of those entries is discarded or collapsed.
- Buffer snapshots before and after each call provide material-mutation
  accounting. Completion requires real resource-buffer output readback and
  assignment-bound `OUTPUT_VERIFIED` evidence.
- Exact cancellation continues after ownership expiry so an active session
  cannot become impossible to clean up. The old typed journal rollback path is
  still used and its full journals remain in the compatibility result.
- Existing BUILD-mid, BUILD-complete and VERIFY recovery paths construct a new
  exact Direct assignment only after their existing authoritative reconciliation.
  Unsafe resource-bearing PROCESS reload remains refused.

## Verification

- Focused core command:
  `scripts/Invoke-Gradle.ps1 -PskipGameModules=true :core:test --tests
  dev.stevecreate.agent.core.execution.construction.DirectWorldExecutorTest
  --tests
  dev.stevecreate.agent.core.execution.construction.ConstructionExecutorContractTest`
  — 9 tests passed (6 Direct implementation tests plus 3 contract tests).
- `scripts/Test-Core.ps1` — 9/9 tasks passed; core 395 plus adapter-api 38
  XML tests, zero failures, errors or skips. Log:
  `work/logs/core-test-20260722-234038.log`.
- `scripts/Invoke-Gradle.ps1 :forge-create-1.20.1:compileJava` — passed.
- The first isolated migration run correctly exposed an overly strict adapter
  accounting assertion: a single native belt-connect handler action retained
  three journal entries and was incorrectly rejected. Evidence is retained in
  `work/logs/goal-driven-execution-gametest-20260722-234510.log`. The adapter now
  counts the one bounded handler action while preserving all three entries.
- Final `scripts/Test-GoalDrivenExecution.ps1` — all 14 required GameTests
  passed. Real gravel x3 and iron sheet x2 outputs, three orientations, typed
  failures, cancellation/rollback, duplicate rejection, BUILD-mid,
  BUILD-complete and VERIFY recovery, unsafe PROCESS refusal, and the
  post-construction formal-world hard stop all passed. Crash reports: 0;
  residual Java processes: 0. Log:
  `work/logs/goal-driven-execution-gametest-20260722-234959.log`.
- `scripts/Build.ps1` — 23/23 tasks passed; core 395, adapter-api 38 and
  Forge 9 XML tests (442 total), zero failures, errors or skips; artifact
  isolation passed. Log: `work/logs/build-20260722-235321.log`.

Only the repository-owned
`forge-create-1.20.1/run/goal-driven-execution-gametest` directory was writable
for the GameTest. The formal `D:\PCL2` instance and formal saves had zero access
and zero modification.
