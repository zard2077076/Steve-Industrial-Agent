# Hybrid executor

Date: 2026-07-23 (Asia/Shanghai)

`HybridExecutor` is a loader-neutral router over the existing Direct and Bot
`ConstructionExecutor` boundaries. It does not create a fourth planning path:
the caller supplies the same `ConstructionTaskGraph`, verified physical-plan
identity, task, ownership lease and bounded execution context used by the
single-mode executors.

## Default policy

| Task class | Default route |
|---|---|
| ordinary block | Bots, with declared Direct fallback |
| Create machine | Direct |
| orientation-sensitive component | Direct |
| material transport | Bots, with declared Direct fallback |
| obstruction clearance | Direct |
| system verification | Direct, non-overridable |
| test-only resource | Bots, with declared Direct fallback |
| high-risk interaction | refuse, non-overridable |
| shared infrastructure | Direct |

`HybridRoutingPolicy` accepts player/configuration preferences for the
remaining classes, but construction safety remains authoritative. A policy
cannot route high-risk interaction or move system verification away from the
trusted Direct backend. A fallback class must be Bot-preferred and non-high
risk.

## Assignment and fallback invariants

The caller-visible assignment always remains `HYBRID`. For one bounded backend
call, the router derives a Direct or Bot assignment from the same session,
graph, task, assignment ID, attempt, ownership generation/token and lease.
Direct strips a worker identity; Bot requires the exact worker already bound by
Hybrid ownership. Backend evidence is rebound to the original Hybrid identity
with `hybrid-route=<direct|bots>` provenance.

Fallback occurs only when Bot support can be disproved before calling a Bot,
for example when a capability declares `BOTS=UNSUPPORTED`, the task does not
permit Bots, or the Hybrid assignment has no worker. Direct must independently
be permitted by the task, descriptor and executor, and the policy must allow
the class fallback. Once a backend is invoked, its PENDING, success, failure,
recovery or unsupported result is never switched to the other backend. Prior
route provenance is checked before continuation, preventing cross-backend
resume after a mutation or reload.

This rule directly supports the B capability mapping: a C-05 through C-10
descriptor may declare Bot execution unsupported and Hybrid as a Direct-only
fallback without fabricating Bot success.

## Verification

Focused command:

```powershell
.\scripts\Invoke-Gradle.ps1 :core:test --tests dev.stevecreate.agent.core.execution.construction.HybridExecutorTest
```

Result: 10/10 tests passed. The suite covers default classification, permanent
high-risk refusal, immutable policy constraints, pre-call Direct fallback,
honest runtime Bot refusal, no fallback after a pending mutation, stable prior
evidence routing, exact Hybrid evidence identity, recovery refusal, cancellation
result enforcement and delegate identity/mode confusion.

The implementation imports no Minecraft, Forge, Create or Mekanism classes and
contains no world, network, sleep, wait or blocking primitive.
