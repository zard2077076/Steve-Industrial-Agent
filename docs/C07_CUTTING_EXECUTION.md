# C-07 Cutting execution

Status: WIP automated runtime gate passed; final integrated acceptance pending.

## Supported Phase I shape

- exact item-only `create:cutting` recipe;
- fixed owned Depot, upward mechanical saw, power and output chest;
- `world_cutting=forbidden`;
- live recipe, saw orientation/kinetic state, item-entity cycle, consumed input
  and real chest-output evidence.

The safe reference processes an oak log into a stripped oak log. The upward saw
and Depot-bound item path cannot fell a tree or cut an arbitrary world block.
The handler never synthesizes the product, teleports a Bot, uses a fixed sleep
or reads player inventory.

## Current automated evidence

First-level plan, contract, geometry, observer and static gates pass. The shared
suite proves Direct/Bots/Hybrid equivalence, exact BUILD-prefix recovery,
pre-feed cancellation and the fault/cleanup matrix. Resource-bearing recovery,
Composite/fleet integration, full final regression and the integrated
Site Preparation player-visible acceptance remain. No independent PCL2 run is
permitted.
