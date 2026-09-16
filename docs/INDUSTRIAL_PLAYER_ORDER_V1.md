# `industrial-player-order-v1`

This is the additive order boundary used by ordinary player work. It does not
replace the frozen `phase-iv-player-workflow-v3` or the exact IE Metal Press
state machine.

The core envelope carries owner, world, dimension, target, recipe, anchor,
plan/runtime/baseline hashes, monotonic lifecycle checkpoints, durable effect
IDs and an optional `IndustrialCompletionReportV1`. The report keeps separate
maps for item material, FE, fluids and outputs; it rejects any planned versus
withdrawn or withdrawn versus consumed/returned identity drift and records
duplicate, unaccounted and private-item counters.

`IndustrialPlayerOrderPlanV1` binds one verified material plan to an optional
physical plan, Composite DAG, FE graph and fluid graph. IPO-03 adds an optional
`IndustrialResourceBindingV1` that freezes the exact authorized warehouse,
Bot session/assignments, FE topology, fluid topology and planned FE/fluid
amounts into the same plan fingerprint. It also binds the
selected Direct/Bots/Hybrid mode and bounded Bot fleet. This is intentionally a
plan envelope: graph presence never grants a Forge mutation path.

The Forge bridge persists the envelope in
`steve_industrial_player_orders`. Ordinary C-03-C-10 projects create it after
verified material resolution, checkpoint reservations/construction, and write a
common report projection at terminal settlement. On a fresh server JVM,
non-terminal envelopes pause at `RELOAD_RECONCILIATION_REQUIRED`; physical
adapters must still perform their own exact rescan before resuming.

`IndustrialResourceCheckpointSavedData` persists immutable endpoint selectors,
expected topology and transaction snapshots. It deliberately does not serialize
live capability handles, entity references or a runtime as reusable authority.
A fresh runtime must re-observe the selected containers, Bots, wires, tanks,
pumps and pipes, pass exact topology checks and explain every mutable
item/FE/fluid delta through the append-only ledgers. A malformed or duplicate
journal is dropped at decode; endpoint drift pauses with a typed code and is
never repaired or replaced by a nearby endpoint.

Current gates:

- implemented: core contract/fingerprint/balance tests, Forge order and IPO-03
  binding/checkpoint codec round-trips, cross-project reservation contention,
  exact return/reload journals, ordinary-player checkpoint projection, Composite
  player orders and Metal Press additive projection;
- implemented physical recovery gate: an isolated two-JVM dedicated server
  re-observes a real chest, two persistent Bot identities, real IE power endpoints
  and real Create tanks/pipes, then settles one report with every
  duplicate/private/unaccounted counter at zero and restores the tracked baseline;
- implemented first production registration: the IE 10.2.0-183 adapter declares the exact reviewed
  iron-plate Metal Press order/recipe and its ITEM/ELECTRICAL_ENERGY resource classes without
  mutation authority. The real Metal Press service requires that registration and binds its
  cancellation and balanced report into this common envelope;
- implemented production binding in the same-runtime physical gate: the real Metal Press order
  persists the selected chest, two exact Bot identities, thirteen assignments, real IE LV topology
  and stable internal press FE storage. Independent acceptance restores its checkpoint and proves a
  balanced 16-item/2,400-FE/no-fluid settlement without using the generic layer to mutate the world;
- implemented first production fresh-runtime recovery: a two-JVM gate stops the real order at
  `POWER_VERIFIED`, then re-observes the same chest, two persisted Bots and IE LV topology before
  resuming. Since the real IE Metal Press buffer resets from 2,424 FE to zero across reload, the
  specialized adapter safely recharges through the already-owned thermoelectric structure; it does
  not write FE directly or weaken exact content comparison. Evidence:
  `work/logs/ie-metal-press-resource-reload-read-20260813-230110.log`;
- implemented the remaining production recovery windows: the same two-JVM fixture now destroys the
  old runtime at `PARTIAL_ENERGY`, `ENERGY_SETTLED`, and `OUTPUT_OBSERVED` in addition to
  `POWER_VERIFIED`. Partial-energy recovery requires the one persisted IE process queue and resumes
  it through the already-owned thermoelectric network without re-admitting iron. Energy settlement
  and output claiming are separate durable stages; the latter trusts persisted exact-energy evidence
  after reload because IE does not persist its internal FE buffer, while still requiring one real
  output entity. All four windows finish with zero duplicate/private/unaccounted counters and a
  balanced common resource binding. The generic contract still has no repair, scanning or execution
  authority.
