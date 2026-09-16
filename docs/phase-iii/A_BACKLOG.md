# Phase III-A backlog

Updated: 2026-07-23 (Asia/Shanghai)

This branch owns Construction Executors, Bot Fleet, Hybrid routing, C-03/C-04
three-mode acceptance, and final integration. Shared project-state documents are
left for the integration worktree as required by the Phase III command.

| Item | Priority | State | Objective acceptance |
|---|---:|---|---|
| A-00 independent worktree and baseline | P0 | DONE | `feature/construction-executors-bot-fleet` starts at `7d8cfa6cd5a13199b2fc8017b14dc0a1b36e3ea8`; `Build.ps1` passes 23 tasks and 418 XML tests with zero failures/errors/skips |
| A-01 Executor Contract v1 | P0 | DONE | loader-neutral API and 18 focused tests cover DAG, physical-plan provenance, all three mode declarations, ownership, reservations, retry/cancel/recovery, and evidence-backed success; 436-test full build passes and the coherent commit is sent to B |
| A-02 DirectWorldExecutor migration | P0 | DONE | exact v606 root sessions run through loader-neutral `DirectWorldExecutor`; 6 focused Direct tests, 442-test full build and all 14 goal-driven Create GameTests pass with real output, typed cancel/reload/failure evidence and zero residual process |
| A-03 Bot worker actions | P0 | DONE | loader-neutral worker contracts plus the repository-owned test-only ArmorStand bridge prove stable identity, adjacent bounded navigation/repath, dedicated-chest fetch/carry/return, allowlisted place/remove/lever interaction, exact BlockState readback, cancellation, authority/region refusal and idle reload in isolated GameTest |
| A-04 material ledger | P0 | DONE | bounded test/dedicated-source ledger atomically covers concurrent shortage, capacity, delivery, return, cancel, reload and non-duplication; the physical adapter reconciles every chest mutation and visible held item against the ledger and refuses reload drift |
| A-05 Bot Fleet scheduler | P0 | DONE | 2-5-worker core tests cover DAG/work-position/path reservations, recovery, deadlock, reload and global cancel; an isolated two-worker GameTest proves parallel assignment/movement/fetch/transport with sequential dependent placement and two distinct physical sources |
| A-06 HybridExecutor | P0 | DONE | loader-neutral router applies immutable class policy, checks Hybrid and physical-backend declarations, permanently refuses high risk, binds evidence back to one Hybrid assignment, prevents cross-backend continuation and permits Direct fallback only when Bot absence is proven before any Bot call |
| A-07 C-03/C-04 equivalence | P0 | DONE | 18/18 GoalDriven GameTests prove the same VerifiedPhysicalPlan and graph produce equal final snapshot, orientation, ports, shafts/belts, output, session evidence, cleanup, idle reload and exact material consumption for Direct, Bots and Hybrid; two-process initial BUILD recovery and runtime command-tree acceptance also pass |
| A-08 visible isolated acceptance | P0 | DONE | C-03/C-04 all modes, Bot motion/materials, cancel and cleanup passed only in `SteveAgent-Executor-Test`. After the first re-entry exposed a multi-process recovery refusal, the fixed committed JAR repeated initial BUILD save/exit/re-entry and resumed with exact rescan, gravel 3/3, no duplicate placement/consumption/output, cleanup removed 50 owned blocks, workers/unknown removals/crashes/residual Java 0. Formal instance/save unchanged. |
| A-09 integration | P0 | DONE | Independent `feature/phase-iii-integration` started at A checkpoint `a48efcf`, cherry-picked B's three owned commits in order while skipping equivalent `d368158`, retained both sides of two documentation conflicts, and passed 476/476 XML tests, 23/23 build tasks, 18/18 A GameTests plus B's 40-task/56-dependency runtime contract and read-only observer acceptance with zero crashes/residual processes |
| A-10 player-shaped role Bots and completion spacing | P0 | DONE | Steve logistics and Alex builder/inspector both perform non-zero work with smooth movement; automated C-03/C-04 Bots runs end at two distinct safe stations and report `botOverlap=false`. The final committed-JAR isolated iron-sheet replay ended at `-846,149,3001` and `-845,149,3002`, was visibly confirmed by the user, cleaned to baseline with unknown removals 0, saved all dimensions and left crashes/residual Java 0. |

The public `v0.1.0-alpha.1` tag/release is frozen. No release, tag movement,
force-push, formal PCL2 write, or formal save access is part of this branch.
