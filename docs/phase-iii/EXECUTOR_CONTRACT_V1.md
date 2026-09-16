# Executor Contract v1 freeze

Status: FROZEN on Phase III-A; exact commit hash is reported as
`EXECUTOR_CONTRACT_V1_COMMIT` after commit creation.

## Boundary

The contract is loader-neutral Java 17 under
`dev.stevecreate.agent.core.execution.construction`. It adds a construction
backend boundary after `VerifiedPhysicalPlan`; it does not modify or replace
`GenericExecutionSession`, `ExecutionReadyPlan`, the v606 handlers, the world
write guard, the journal, or recovery codecs.

The frozen chain is:

```text
VerifiedPhysicalPlan
  -> ConstructionTaskGraph
  -> TaskAssignment + TaskOwnership + reservations
  -> ConstructionExecutor (one bounded call)
  -> TaskExecutionResult + objective ExecutionEvidence
```

World/Forge/Create objects never cross the contract. A concrete executor owns
its trusted server-authoritative services internally and may report at most one
world mutation and one material mutation per call.

## Frozen API surface

- `ConstructionExecutor`, `ExecutionMode`, `ConstructionExecutionCommand`,
  `ConstructionExecutionContext`
- `ConstructionTask`, `ConstructionTaskGraph`, `TaskKind`,
  `ConstructionTaskClass`, `TaskDependency`, `TaskDependencyKind`
- `TaskPrecondition`, `TaskPostcondition`, `TaskConditionKind`
- `CapabilityExecutionDescriptor`, `ModeCapabilityDeclaration`,
  `CapabilitySupport`
- `VerifiedPlanTaskSource`, `TaskSourceKind`
- `TaskAssignment`, `TaskOwnership`
- `PlacementReservation`, `MaterialReservation`,
  `SharedInfrastructureReservation`, `ReservationStatus`,
  `MaterialSourceScope`
- `RetryPolicy` (existing shared execution type), `RecoveryPolicy`,
  `RecoveryStrategy`, `CleanupPolicy`, `CleanupScope`
- `TaskExecutionResult`, `TaskExecutionOutcome`, `TaskFailure`,
  `TaskFailureCategory`, `ConstructionFailureCode`
- `ExecutionEvidence`, `ExecutionEvidenceKind`

Until integration, record components, public method signatures, enum names and
their serialized meanings are frozen. An incompatible need from B must be sent
as `CONTRACT_CHANGE_REQUEST`; A will review it before either branch changes the
surface. Additive Adapter-owned parameter keys and namespaced failure codes are
allowed within the existing bounded maps.

## Required invariants

- Every graph binds exactly one verified physical-plan ID, runtime fingerprint
  and world-snapshot fingerprint.
- A world-mutating task must cite a verified placement, route or port element;
  a system check or free coordinate cannot become mutation authority.
- Every world mutation has a placement reservation; every material operation
  has a material reservation. Phase-I material sources are only bounded
  TEST_ONLY sources or explicitly authorized dedicated containers.
- Every capability declares Direct, Bots and Hybrid separately. `UNSUPPORTED`
  is not executable and cannot claim executor capabilities.
- Dependencies form a bounded DAG and cite real predecessor postconditions.
  Readiness accepts only successful `TaskExecutionResult` values from the same
  graph, not caller-supplied completion booleans.
- Bot ownership and placement reservations name an exact worker. Direct leases
  cannot claim a Bot worker. Ownership is generation-, time- and token-bound.
- A material reservation enforces
  `delivered + returned <= withdrawn <= reserved <= requested`.
- Recovery never blindly replays resource consumption. Resumable/reassignable
  recovery requires exact reconciliation evidence.
- Success is constructible only when the latest evidence for every
  postcondition belongs to the exact assignment, passes, contains the expected
  observed values and is within the current ownership lease.
- Shared infrastructure is reference-counted by exact session identities;
  released/expired infrastructure has no owners.
- High-risk/private/player-inventory behavior receives no authority from this
  contract. Formal-world hard stops remain in the existing independent guards.

The common failure ID for honest Bot refusal is
`construction:bot_execution_capability_unsupported`
(`BOT_EXECUTION_CAPABILITY_UNSUPPORTED`). Hybrid may later route that task to
Direct only when the descriptor declares safe Direct support; it may not turn
the refusal into fabricated Bot success.

## B integration instructions

After receiving `EXECUTOR_CONTRACT_V1_COMMIT`:

1. Cherry-pick that exact commit onto the B branch and rerun the baseline.
2. For each C-05 through C-10 implementation, create one
   `CapabilityExecutionDescriptor` with all three mode declarations. Do not
   claim Bot support unless its task requirements are actually expressible.
3. Derive task sources from the current `VerifiedPhysicalPlan` identity and its
   placement/route/port/resource element identities. Do not add coordinates to
   capability semantics or reuse a fixed Direct layout.
4. Emit capability-owned task requirements and DAG construction only. Do not
   implement `ConstructionExecutor`, world placement, Bot movement or a second
   Direct path on B.
5. Use Adapter-owned bounded descriptor parameters for heat, airflow, medium,
   tool, basin, held-item and runtime-observer requirements. Parameters remain
   untrusted until an A/integration executor schema-checks them.
6. If the surface is insufficient, send a concrete `CONTRACT_CHANGE_REQUEST`
   containing the affected type, required invariant and failing test before
   changing shared contracts.

No migration is required for current C-03/C-04 code at checkpoint 1. A will
adapt those existing v606 paths behind `DirectWorldExecutor` after this freeze;
the old accepted handlers and session/recovery types remain the source of
physical truth during that migration.

## Freeze verification

- Focused contract suite: 18 tests PASS.
- `scripts/Test-Core.ps1`: PASS; log
  `work/logs/core-test-20260722-225008.log`.
- `scripts/Build.ps1`: 23/23 tasks PASS; 436 XML tests, zero failures, errors
  or skips; artifact isolation PASS; log
  `work/logs/build-20260722-225123.log`.
- Source scan: zero Minecraft, Forge, Create or Mekanism imports; zero
  sleep/wait/join/network primitives in the new contract package.
- Formal PCL2 instance and formal saves: zero access and zero modification.
