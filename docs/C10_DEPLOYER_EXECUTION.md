# C-10 Deployer execution

Status: WIP consumed-held-item runtime gate passed; final integration pending.

## Supported Phase I shape

- exact deterministic `ItemApplicationRecipe`;
- one processed item plus one exact held item;
- fixed owned Depot, downward Deployer, power and output chest;
- exact consumed or retained held-item policy at the typed handler boundary;
- live Deployer hand, Depot before/after, cycle and chest-output evidence.

The current goal materializer executes the safe consumed-held-item
`create:deploying/cogwheel` route. The handler supplies real shaft and plank
inputs through the Deployer/Depot item capabilities and collects only the real
Depot result.

## Immediate authority boundary

There is no arbitrary block use, private-container access, entity interaction,
combat, player-inventory access or unknown NBT mutation. Bots may build and
supply the typed topology; they receive no generic right-click authority.
Unknown side effects or interaction targets refuse typed before execution.

## Current automated evidence

First-level safety, loader-neutral, contract, geometry and observer tests pass.
The shared suite proves Direct/Bots/Hybrid equivalence, exact BUILD-prefix
recovery, pre-feed cancellation and the fault/cleanup matrix for the consumed
route. General retained-tool metadata/reservations, resource-bearing recovery,
Composite/fleet integration and the integrated Site Preparation acceptance
remain. No independent PCL2 run is permitted.
