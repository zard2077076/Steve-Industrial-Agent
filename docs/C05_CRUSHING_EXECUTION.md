# C-05 Crushing real execution

Status: automated checkpoint complete on 2026-07-23. Player-visible Phase IV
acceptance remains a later gate.

## Accepted scope

C-05 is one bounded ITEM-to-ITEM Create crushing-wheel capability. The standard
acceptance recipe is `create:crushing/gravel`: one `minecraft:gravel` is fed as
a real item entity, one guaranteed `minecraft:sand` is collected from the real
output chest, and optional flint/clay rolls are reported from the actual
runtime result. The disposable DeceasedCraft Beta 5.10.16 profile additionally
selects the runtime-verified
`deceasedcraft:crushing_wheel/crushing/raw_materials/copper` recipe: the
`forge:raw_materials/copper` input resolves to `minecraft:raw_copper`, the
guaranteed output is `create:crushed_raw_copper`, and the optional byproduct is
`immersiveengineering:nugget_copper`.

This checkpoint does not add a general arbitrary crushing interpreter. The
physical executor accepts only the exact typed recipe carried by its trusted
plan and revalidates that recipe against the live `RecipeManager`.

## One shared physical plan

Direct, Bots and Hybrid consume the same `VerifiedPhysicalPlan`. C-05 owns:

- one chest at relative `(1,0,0)` and one down-facing hopper at `(1,1,0)`;
- independently powered left/right crushing wheels at `(0,3,0)` and `(2,3,0)`;
- creative motors at `(0,3,1)` facing north and `(2,3,-1)` facing south;
- the one-block runtime controller gap at `(1,3,0)`;
- the bounded real input entity boundary at `(1,4,0)`.

The loader-neutral geometry exposes the honest continuous left-drive/left-wheel
rotational route and a capacity of 64. It does not fabricate a kinetic edge
through Create's controller gap. The exact v606 handler separately proves both
independent powered wheels have equal-magnitude opposed rotation, the runtime
controller is valid and down-facing, and observed stress does not exceed
capacity.

## Execution and evidence

The four generic steps are `BUILD`, `POWER`, `FEED_INPUT` and `PROCESS`.
`BoundedStepRunner` invokes at most one registered C-05 action per server tick.
The action handler:

1. places and journals one owned block per build invocation;
2. records the final wheel and Create-owned controller as one recovery-safe
   atomic transition;
3. reads four real kinetic components and the live controller state;
4. confirms the controller, hopper and chest are empty before spawning input;
5. validates the exact Create crushing recipe and real input entity;
6. waits for actual input disappearance and chest output;
7. records guaranteed output, actual optional byproducts, recipe duration,
   controller state, wheel speeds/stress and crash-free evidence.

The goal resource buffer collects only real chest contents and applies the
withdrawal transaction once. No sleep, network request, LLM text, unchecked
coordinate or in-memory success flag can complete processing.

Direct uses the exact server-thread handler. Bots uses the shared player-shaped
Steve logistics and Alex builder/inspector executor with distinct stations,
smooth bounded movement, reservations and no overlap. Hybrid routes logistics
to Bots and the sensitive build/verification work to Direct. The three modes
are compared for the same plan/graph, final block snapshot, ports, orientation,
output, journal, cleanup, recovery and material accounting.

## Failure and recovery boundary

The C-05-only GameTest suite proves:

- missing gravel is `INPUT_RESOURCE_MISSING`;
- formal-world planning is `FORMAL_WORLD_FORBIDDEN`;
- an occupied construction cell is `BLOCK_PLACEMENT_BLOCKED`;
- one-tick cancellation is `EXECUTION_CANCELLED` and restores exactly 1/1
  journal-owned position with zero rollback warning;
- a controller changed to the wrong direction is `PROCESSING_FAILED`;
- a foreign item inserted into the output path is `PROCESSING_FAILED`;
- a removed drive is `KINETIC_COMPONENT_NOT_FOUND`;
- all three runtime faults occur before resource injection or output;
- a persisted one-placement BUILD prefix is encoded, decoded, exactly rescanned
  and resumed after a 40-tick gap with no repeated placement/input/output.

Shared Bot-fleet regressions supply the unreachable-path, worker-blocked,
repath/deadlock, cancellation and exact-reconciliation cases used by the C-05
Bots/Hybrid backends. The C-05 three-mode GameTest proves that this backend
executes the actual C-05 graph with two active roles and distinct completion
stations. Resource-bearing or irreversible checkpoints remain unsafe and are
never automatically replayed.

## Automated evidence

- `scripts/Test-C05Crushing.ps1`: 4/4 C-05 GameTests, Direct/Bots/Hybrid real
  processing, exact BUILD recovery and typed failure matrices;
  `work/logs/c05-crushing-gametest-20260723-202007.log`; final review rerun
  `work/logs/c05-crushing-gametest-20260724-084508.log`.
- `scripts/Test-Core.ps1`: final post-review loader-neutral regression;
  `work/logs/core-test-20260724-084358.log`.
- `scripts/Test-GoalDrivenExecution.ps1`: 22/22 aggregate GameTests including
  C-03/C-04/C-05, recovery and formal-world guards;
  `work/logs/goal-driven-execution-gametest-20260723-203921.log`.
- `scripts/Test-CreateRuntimeRecipeCatalog.ps1`: standard runtime 2,609 total,
  55 crushing discovered, 12 safely mapped, three runtime capabilities and
  three implementations, no world mutation/session;
  `work/logs/create-runtime-recipe-catalog-20260723-201435.log`.
- `scripts/Test-DeceasedCraftIsolatedServer.ps1 -SkipBuild`: 18,286 total,
  103 crushing recipes and the safe C-05 copper selection, with
  `externalMutation=false`, `savesOrFormalWorldRead=false`, clean exit, zero
  crash report and zero residual process;
  `work/logs/deceasedcraft-isolated-server-20260723-203557.log`.

## Safety and limitations

Only repository-owned disposable profiles may execute C-05. `D:\PCL2` remains
an external read-only compatibility source and receives no launch, config, JAR
or save change. C-05 does not mine raw materials, open arbitrary containers,
interact with players/entities, use fluids, radiation or Mekanism, attach to an
existing factory, repair a line, compensate consumed resources, or authorize a
formal world. Player-visible Phase IV acceptance and all later Phase IV
capabilities/composites are separate checkpoints.
