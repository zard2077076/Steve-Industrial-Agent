# Phase III-B Create C-05 through C-10 backlog

Start: `7d8cfa6cd5a13199b2fc8017b14dc0a1b36e3ea8`

Branch: `feature/create-c05-c10-capabilities`

This file is the B-side working backlog. Shared release/project documents remain owned by
ROLE=A during integration.

| Item | State | Objective acceptance |
|---|---|---|
| B-00 isolated worktree and baseline | DONE | B worktree is separate; core and 23-task clean build pass; no PCL2 path is configured |
| B-02 standard runtime census | DONE | authoritative RecipeManager accounts for 421 targets: 318 supported, 23 semantics-only and 80 unsupported |
| B-02 DeceasedCraft runtime census | DONE | isolated fresh-world RecipeManager accounts for 995 targets: 827 supported, 57 semantics-only and 111 unsupported |
| B-03 shared semantics | DONE | loader-neutral heat, airflow, medium, tool, catalyst, basin, held-item, multi-output, probability, speed/direction and observer contracts pass JVM tests |
| B-04 C-05 Crushing | DONE | dual-wheel direction/power plus ordered multi-output/probability semantics and observer requirements |
| B-05 C-06 Fan Processing | DONE | washing/smoking/haunting/blasting medium, direction, reach, obstruction, dwell and output semantics |
| B-06 C-07 Cutting | DONE | item-only cutting supported; complex ingredients/outputs remain typed limitations |
| B-07 C-08 Mixing Phase I | DONE | counted item inputs, NONE/HEATED and deterministic item output; fluids/SUPERHEATED/complex residue rejected typed |
| B-08 C-09 Compacting Phase I | DONE | separate Basin+Press semantics with heat and deterministic item output; never aliases C-04 pressing |
| B-09 C-10 Deployer Phase I | DONE | exact held item, consumed/retained state and deterministic item output; arbitrary block/entity/container/player-inventory use rejected |
| B-10 binding metadata | DONE | capability ID, orientation, footprint/clearance, ports/zones/slots, speed/stress and reload/cleanup requirements are loader-neutral |
| B-11 runtime observers | DONE | live recipe/component/power/environment/input/output evidence with typed failures; fixed sleep is structurally forbidden |
| CHECKPOINT-2 Contract v1 | DONE | exact A commit `051b701edb5a2b19fd5d5f440a0395995eba5ce3` cherry-picked; capabilities map to frozen descriptors/task graphs without an executor |
| B close-out | DONE | 437 pure tests and 449 full-build XML tests pass; artifact isolation, static boundaries, crash/residual checks and documentation pass |

Safety boundary: B writes only its worktree and repository-owned isolated run/evidence paths. The
formal launcher instance and every player save remain read-only and non-executable. No executor,
world placement path, arbitrary coordinates, LLM authority, network or telemetry is introduced.

Detailed census, contract mapping and A-side migration notes are in
`docs/phase-iii/B_CREATE_CAPABILITIES_V1.md`.
