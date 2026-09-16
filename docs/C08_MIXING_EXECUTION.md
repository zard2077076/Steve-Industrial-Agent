# C-08 Mixing execution

Status: WIP safe-NONE automated runtime gate passed; final integration pending.

## Supported Phase I shape

- exact `create:mixing` recipe;
- one through nine counted ITEM inputs;
- concrete items must match every live tag/any-of `Ingredient`;
- one guaranteed deterministic ITEM output with exact count;
- `BasinHeatMode.NONE` or ordinary-fuel `HEATED`;
- no fluid ingredient/result, byproduct, residue or unknown NBT;
- fixed motor, mechanical mixer, Basin, Blaze Burner and output chest;
- exact live speed, stress, heat, Basin, mixer-cycle and output evidence.

The safe NONE reference is `create:mixing/andesite_alloy`, using andesite plus
the resolved iron-nugget tag member. The HEATED reference is
`create:mixing/brass_ingot`, using resolved copper and zinc ingots. Runtime
recipe and Ingredient alternatives remain authoritative.

## Heat and handler boundary

NONE requires the owned Burner to remain at `NONE`. HEATED extracts one real
`minecraft:coal` from the shared resource buffer, inserts it through Create's
`BlazeBurnerBlock.tryInsert` behavior and requires exact `KINDLED`.
`SEETHING`/SUPERHEATED cannot pass. The handler never sets Burner heat state or
unknown NBT directly.

Inputs are staged in the real Basin inventory and matched with
`BasinRecipe.match`. Completion observes the mechanical mixer's live
running/processing ticks and transfers only actual Basin output inventory to the
planned chest. No recipe apply/process method, fixed sleep, teleport, player
inventory or synthetic output path exists.

## Current automated evidence

Frozen verified-plan nodes need explicit heat metadata and fuel reservation
before general HEATED goal materialization; the current safe materializer uses
NONE. That route now passes shared multi-stack Direct/Bots/Hybrid equivalence,
the real `create:mixing/andesite_alloy` cycle, exact BUILD-prefix recovery,
pre-feed cancellation and the concentrated fault/cleanup matrix. General
HEATED goals, resource-bearing recovery, Composite/fleet integration and the
integrated Site Preparation acceptance remain. No independent PCL2 visible run
is permitted.
