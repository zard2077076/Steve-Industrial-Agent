# ROLE=B Create capabilities v1

Date: 2026-07-23 (Asia/Shanghai)

Scope: C-05 crushing, C-06 fan washing/smoking/haunting/blasting, C-07
item-only cutting, C-08 Mixing Phase I, C-09 Compacting Phase I and C-10
Deployer Phase I. This milestone provides runtime semantics, census, binding
metadata, frozen-contract descriptors/task graphs and read-only observers. It
does not provide a `ConstructionExecutor`, a second Direct path, Bot execution,
world placement or a physical success claim.

## Contract baseline

- Frozen API source: `docs/phase-iii/EXECUTOR_CONTRACT_V1.md`.
- Exact A checkpoint: `051b701edb5a2b19fd5d5f440a0395995eba5ce3`.
- Frozen package: `core/src/main/java/dev/stevecreate/agent/core/execution/construction/`.
- B did not change frozen public method, record component, enum name or
  serialized meaning.

Every `SUPPORTED_PHASE_I` recipe publishes all three modes:

| Mode | Declaration | Required boundary |
|---|---|---|
| DIRECT | `CONSTRAINED` | A-owned `direct_world_executor_v1` plus exact v606 observer |
| BOTS | `UNSUPPORTED` | limitation includes `construction:bot_execution_capability_unsupported` |
| HYBRID | `CONSTRAINED` | validated Direct fallback only; no Bot fallback |

`SEMANTICS_ONLY` and `UNSUPPORTED` recipes publish `UNSUPPORTED` for all modes
and therefore cannot become construction tasks.

## Authoritative runtime census

The rows below come from live server `RecipeManager` exports. Static scripts
were not substituted for runtime truth.

### Standard Create 6.0.6 profile

421 target recipes = 318 supported + 23 semantics-only + 80 unsupported.

| Capability | Discovered | Supported | Semantics-only | Unsupported |
|---|---:|---:|---:|---:|
| C-05 Crushing | 55 | 42 | 13 | 0 |
| C-06 Fan Haunting | 20 | 19 | 1 | 0 |
| C-06 Fan Washing | 33 | 29 | 4 | 0 |
| C-06 Fan Blasting | 30 | 30 | 0 | 0 |
| C-06 Fan Smoking | 10 | 10 | 0 | 0 |
| C-07 Cutting | 143 | 143 | 0 | 0 |
| C-08 Mixing Phase I | 11 | 3 | 2 | 6 |
| C-09 Compacting Phase I | 7 | 2 | 3 | 2 |
| C-10 Deployer Phase I | 112 | 40 | 0 | 72 |

Typed limitation occurrences: `HELD_ITEM_NOT_EXACT=72`,
`FLUID_INPUT_PHASE_I_UNSUPPORTED=18`, `PRIMARY_OUTPUT_PROBABILISTIC=18`,
`FLUID_OUTPUT_PHASE_I_UNSUPPORTED=5`, `OUTPUT_MISSING=5`,
`ITEM_INGREDIENT_UNSUPPORTED=3`, `SUPERHEATED_PHASE_I_UNSUPPORTED=1`.

### DeceasedCraft isolated profile

18,286 total recipes; 995 target recipes = 827 supported + 57 semantics-only +
111 unsupported.

| Capability | Discovered | Supported | Semantics-only | Unsupported |
|---|---:|---:|---:|---:|
| C-05 Crushing | 103 | 88 | 10 | 5 |
| C-06 Fan Haunting | 23 | 22 | 1 | 0 |
| C-06 Fan Washing | 39 | 35 | 4 | 0 |
| C-06 Fan Blasting | 95 | 92 | 0 | 3 |
| C-06 Fan Smoking | 41 | 41 | 0 | 0 |
| C-07 Cutting | 488 | 484 | 0 | 4 |
| C-08 Mixing Phase I | 67 | 13 | 35 | 19 |
| C-09 Compacting Phase I | 26 | 11 | 7 | 8 |
| C-10 Deployer Phase I | 113 | 41 | 0 | 72 |

Typed limitation occurrences: `FLUID_INPUT_PHASE_I_UNSUPPORTED=85`,
`HELD_ITEM_NOT_EXACT=72`, `FLUID_OUTPUT_PHASE_I_UNSUPPORTED=32`,
`PRIMARY_OUTPUT_PROBABILISTIC=31`, `ITEM_INGREDIENT_UNSUPPORTED=20`,
`OUTPUT_MISSING=15`, `OUTPUT_NBT_UNSUPPORTED=9`,
`SUPERHEATED_PHASE_I_UNSUPPORTED=5`.

## Execution descriptor and task-graph boundary

`CreateCapabilityExecutionDescriptors` converts one runtime recipe plus its
exact adapter binding metadata into a frozen `CapabilityExecutionDescriptor`.
Adapter-owned bounded parameters carry recipe/phase, heat, airflow/medium,
direction, basin, tool, held item and observer requirements. They explicitly
declare `placement_authority=false`, `executor_provided_by_adapter=false`,
`player_inventory_forbidden=true` and `fixed_sleep_forbidden=true`; no parameter
contains an absolute coordinate.

`CreateCapabilityConstructionTaskGraphFactory` accepts only
`SUPPORTED_PHASE_I`, requires the requested logical node and exact metadata
implementation in one `VerifiedPhysicalPlan`, and requires exact reservation
key coverage for its verified components and incident routes. All task sources
are a verified placement, route or port. The DAG is:

1. fetch and place each verified component;
2. connect each verified incident route;
3. verify the assembled machine;
4. deliver reserved process input;
5. invoke the safe machine-interaction task;
6. verify observed output.

Input delivery is not ready until machine verification succeeds. Every
dependency requires the predecessor's declared postcondition evidence.

## Runtime-observer boundary

The public v606 observer registry routes all nine capability IDs to six
version-confined read-only observers. A request binds exact session, graph,
task, assignment, verified physical plan, source element, capability, recipe,
runtime fingerprint, world snapshot, component positions, baseline item counts,
expected consumed/produced deltas and a bounded tick window.

Success requires the live recipe, machine state, environment, input consumption
and output production to all match. The success record repeats the same
assignment/plan/runtime/world identities and request window with a fresh server
tick, so stale or cross-assignment evidence cannot be represented as success.
All mismatches return a stable typed failure. Observation performs no world
mutation, network operation, fixed sleep or unbounded scan.

## A-side integration and migration

1. Use the authoritative runtime census recipe and exact v606 binding metadata;
   do not reconstruct semantics from free text.
2. Materialize and verify a physical plan whose implementation ID, components,
   ports and routes match that metadata. B metadata is not placement authority.
3. Allocate exact component/route/material/interaction reservations, then call
   `CreateCapabilityConstructionTaskGraphFactory.create(...)`.
4. Before assignment, let the A-owned executor validate descriptor schema,
   execution mode, required executor capabilities, graph/plan/runtime/world
   identities and reservations. Reject `UNSUPPORTED`; never route BOTS.
5. Capture baseline item counts for the exact assignment before interaction.
   Poll the selected `CreateV606RuntimeObserver` on server ticks within the
   declared bound; do not sleep or block the server thread.
6. Accept success only from the latest observation carrying the same assignment,
   task, graph, plan, runtime/world snapshot and observed-vs-expected deltas.
   Convert it to frozen `ConstructionTaskEvidence` without weakening any gate.
7. A physical capability is not accepted until a separate isolated test observes
   a real recipe input being consumed and its real output appearing. The current
   structural fixture intentionally does not make that claim.

## Retained evidence

- Standard structural runtime:
  `work/logs/create-runtime-recipe-catalog-20260723-001729.log`.
- DeceasedCraft runtime census/planning:
  `work/logs/deceasedcraft-isolated-server-20260723-000611.log`.
- B-owned profile build:
  `work/logs/deceasedcraft-profile-builder-20260722-235817.log`.
- Final pure-module regression (437 tests):
  `work/logs/core-test-20260723-002248.log`.
- Final clean build (23 tasks; 449 XML tests; zero
  failures/errors/skips; artifact isolation passed):
  `work/logs/build-20260723-002418.log`.

The DeceasedCraft run loaded 288 mod containers, recorded 42 retained nonfatal
pack diagnostics, cleanly saved/exited, left zero crash reports and zero
residual processes, and verified the formal client/server source fingerprints
unchanged with `externalMutation=false` and `savesOrFormalWorldRead=false`.
Static close-out found zero pure-module game/mod imports, zero B-owned executor
declarations, zero blocking/network primitives in the B production boundary,
zero frozen-package working-tree changes and zero target crash reports.
