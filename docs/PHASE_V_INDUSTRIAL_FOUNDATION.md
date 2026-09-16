# Phase V industrial compatibility foundation

Updated: 2026-08-01 (Asia/Shanghai)

## Scope decision

The player interaction and item-material protocol remains frozen. Phase V is
implemented as additive loader-neutral contracts and exact-version adapters.
It does not add another terminal workflow, another construction executor or a
magic central-storage block.

The capability vocabulary covers item processing, fluids, heat, rotational
power, FE electrical power, multiblocks and logistics. A capability profile
must separately state whether discovery is read-only and whether physical
execution is implemented. Planning success never upgrades a read-only adapter
into mutation authority.

## Logical warehouse and long-running orders

`GlobalInventoryGraph` represents explicitly authorized endpoints and routes.
`WarehouseReservationSystem` performs atomic priority-ordered allocations and
subtracts every active cross-project commitment before admitting a batch. Its
states cover reserved, withdrawn, delivered, consumed, return pending,
returned, released and expired; the balance report fails on unaccounted stock.

`PlayerWarehouseProjection` maps only already selected containers and the
durable `PlayerMaterialSavedData` reservations into this graph. It does not scan
nearby private storage. `UnattendedProductionOrderScheduler` is a pure bounded
single-in-flight maintain-stock scheduler with a fixed recipe/adapter/site
allowlist, retry budget and backoff. `WarehouseOrderSavedData` and
`WarehouseOrderService` now persist those orders and evaluate them every 20
server ticks. Ordinary single-stage player projects additionally persist the
shared `industrial-player-order-v1` envelope. Dispatch remains fail-closed:
after every restart the exact warehouse runtime must register again, and no
physical inter-factory route is inferred from the logical graph alone.

## Electrical and fluid graphs

`ElectricalNetworkGraph` models generator, connector, relay, wire,
transformer, consumer and storage nodes with voltage tier, direction, distance,
capacity, load and server-observed topology. Verification rejects disconnected
endpoints, tier mismatch, collision/clearance failures, insufficient capacity
and generation/storage imbalance.

Fluid state has a separate `FluidIdentity`, network graph/verifier and
`ProjectFluidLedger`. Transactions conserve exact millibuckets and cannot be
silently folded into the item ledger. `ForgeFluidTransactionExecutor` now
performs simulate-first exact drain/fill between two explicitly authorized
Forge fluid capabilities and reclaims/returns an exact withdrawal if delivery
drifts. It does not scan nearby tanks or claim a real pipe/pump route; live
topology discovery and complex fluid multiblock execution remain open.

## IE 1.20.1-10.2.0-183 adapter

The first low-risk target is the Metal Press. The versioned adapter:

- reads the real runtime Metal Press recipe catalog;
- resolves deterministic inputs, outputs, retained mold and FE cost;
- reads the official runtime multiblock template into exact component roles;
- exposes formation, connection, insertion, start and observation lifecycle
  actions;
- scans real `GlobalWireNetwork` LV/MV/HV topology without changing it;
- produces a complete `IndustrialProductionPlan` with site components,
  materials, tools, energy, optional fluids, lifecycle steps and 2-5 Bot
  bounds.

The isolated Mac read-only acceptance loads IE 1.20.1-10.2.0-183, observes 24 Metal Press
recipes, verifies iron plate at 2,400 FE with `mold_plate`, resolves 7 Metal
Press components, captures one temporary real LV wire edge and plans two
batches at 4,800 FE with 3 Bots. The reviewed exact Metal Press service now
adds the missing production lifecycle without broadening the generic adapter:
player-source reservation, visible courier and builder Bots, real seven-block
formation and mold interaction, a real thermoelectric generator with two LV
connectors and IE copper wire, live FE receipt, one iron input, exactly 2,400 FE
consumption, one iron plate, exact return, teardown and baseline restoration.
Every durable effect is saved and reconciled across the five tested restart
windows. Generic `execute()` remains conservative for any other machine or
recipe.

## Acceptance boundary

Automated success now proves contracts, material conservation, deterministic
planning and one player-shaped, dedicated-server Metal Press vertical slice.
The dedicated client UI is implemented, but the remaining human-operated Mac
click-through is still tracked separately. This does not prove other IE
machines, central warehouse dispatch, mixed-mod factories or complex fluid
production. Windows/PCL2 remains explicitly deferred and is not inferred from
Mac evidence.
