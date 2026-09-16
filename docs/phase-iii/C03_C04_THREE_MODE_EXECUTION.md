# C-03/C-04 three-mode execution

Date: 2026-07-23 (Asia/Shanghai)

The C-03 milling and C-04 pressing acceptance now executes one immutable
`VerifiedPhysicalPlan` and one construction task graph in `DIRECT`, `BOTS`, and
`HYBRID` modes. The Forge bridge does not introduce a second Create recipe or
machine implementation: Direct and Bot construction both drive the existing
bounded Create v606 goal-execution session, and Hybrid applies the frozen safe
routing policy over those two backends.

## Physical routes

- Direct transports the exact dedicated input stack between repository-owned
  source and delivery chests, then invokes the v606 handler.
- Bots spawn two visible, identity-tagged ArmorStand workers. The assigned
  worker walks a bounded path, fetches and visibly carries the exact stack,
  delivers it, reaches a plan-derived safe work cell, and then drives the same
  v606 handler one server tick at a time.
- Hybrid routes material transport to Bots and Create construction plus system
  verification to Direct. Evidence retains the original Hybrid assignment and
  records `hybrid-route=bots` or `hybrid-route=direct` provenance.

All modes use the same three tasks: material transport, Create construction and
authoritative output verification. An idle boundary reconstructs the executor
and Bot wrappers after transport and reconciles the same live worker identity,
empty carried inventory and exact delivery chest contents.

## Comparison gate

The C-03 and C-04 GameTests compare:

- exact final block snapshot;
- orientation-sensitive components;
- component and material-route endpoint ports;
- shafts, belts and wheels;
- authoritative output and exact material consumption;
- assignment-bound session evidence;
- exact-plan cleanup;
- idle reload reconciliation;
- elapsed server ticks, fleet size and zero failures.

Latest acceptance command:

```powershell
.\scripts\Test-GoalDrivenExecution.ps1
```

Result: 18/18 required GameTests passed. C-03 completed in 1424 Direct,
1483 Bots and 1435 Hybrid ticks; C-04 completed in 356 Direct, 411 Bots and 367
Hybrid ticks. The run also executed all four build-mode commands and parsed all
three explicit pilot-mode forms. The additional test persists C-03 during the
first process BUILD phase, waits 40 ticks, exactly reconciles trusted `node_0`,
then completes both processes with zero repeated placement, input or output.
Crash reports and residual Java processes were both zero. Evidence:
`work/logs/goal-driven-execution-gametest-20260723-164019.log`.

The final full build passes 23/23 tasks and 463/463 XML tests (core 416,
adapter 38, Forge 9), with zero failures/errors/skips and artifact isolation
PASS: `work/logs/build-20260723-164339.log`.

## Commands and safety

The registered commands are:

```text
/industrialagent build mode direct
/industrialagent build mode bots
/industrialagent build mode hybrid
/industrialagent build mode status
/industrialagent pilot start <target> <qty> --mode <direct|bots|hybrid>
```

Mode selection is per operator, defaults to Direct, and an explicit pilot mode
overrides it. Existing region confirmation, preview, backup, mutation budget,
formal-world guard and dedicated-material rules remain mandatory. Bots and
Hybrid use a deterministic adjacent delivery chest and two bounded walkable
worker starts inside the authorized region. Cleanup removes only exact
session-owned plan state, the two dedicated buffers and tagged fleet workers.

Visible player acceptance in `SteveAgent-Executor-Test` has passed both targets,
all modes, Bot motion/materials, cancel and cleanup. The first actual
save/re-entry drill safely exposed the former single-process-only recovery guard
and cleaned its exact session state. The fixed committed JAR then repeated the
same C-03 initial BUILD checkpoint across two independent client processes:
`PERSISTED/BUILD/SAFE_CHECKPOINT`, exact-rescan resume, zero duplicate
placement/consumption/output, gravel 3/3 and cleanup of 50 journal-owned blocks
with workers/unknown removals 0. Both exits saved every dimension and left zero
crash reports or residual Java processes. Evidence:
`work/logs/iwp-visible-recovery-pre-reload-20260723-165924.log` and
`work/logs/iwp-visible-recovery-post-reload-20260723-170450.log`. No formal
launcher instance or save was entered or modified by this milestone.
