# Phase IV production composites

Status: automated isolated-world checkpoint complete for Composite-01/02/03 in
Direct/Bots/Hybrid. This is not player-visible Mac or Windows/PCL2 acceptance.

## Loader-neutral contract

`CompositeProductionGraph` admits one of three bounded DAG shapes: a linear
three-or-more-stage chain with at least two capabilities, independent
concurrent lines that share verified infrastructure, or a truly non-linear
branch/merge graph. `CompositeProductionCoordinator` owns only deterministic
node readiness and material-buffer evidence. It cannot access a world or run a
handler.

Every edge has one exact item identity, reservation quantity and capacity.
Starting a node reserves exact incoming quantities; completion requires
authoritative outputs and refuses missing output, contamination or
backpressure. Snapshots bind graph ID and SHA-256 topology fingerprint so a
changed graph cannot resume. Cancelling a running line returns its reserved
intermediate quantities in the loader-neutral ledger. Independent failed
branches do not authorize their merge node.

`SharedInfrastructureLeaseRegistry` updates the existing
`SharedInfrastructureReservation` owner set. Releasing line A cannot authorize
cleanup while line B remains an active owner.

## Composite-01 physical chain

The accepted WIP fixture uses:

1. C-07 real mechanical saw: one oak log -> one stripped oak log.
2. C-07 real mechanical saw: one stripped log -> six oak planks.
3. C-10 real downward Deployer: one reserved plank plus one shaft -> one
   cogwheel.

The two intermediate edges are preverified vanilla Hoppers held by physical
redstone locks. The coordinator releases a lock only after the producer's
authoritative delivery readback. It never inserts an intermediate. The second
recipe creates six planks while the next deterministic C-10 cycle needs one;
after one plank reaches the next source, the Hopper is locked and redirected
to a dedicated salvage chest. It then physically moves the five remaining and
in-flight planks to salvage. Execution continues only after readback proves:

`source decrease = reserved destination quantity + salvage quantity`.

Direct, Bots and Hybrid share the same graph fingerprint and verified stage
plans. Each stage retains its existing per-stage exact idle reload evidence.
Each stage also escrows its own complete registered item-form installation
manifest through the same `CreateV606ThreeModeExecution` material boundary;
there is no Composite-only free construction path.
Cleanup removes only exact-match session-owned machine and route blocks;
delivered output and salvage remain player-owned boundary resources.

Automated evidence:

- `work/logs/core-test-20260727-102635.log`
- `work/logs/phase-iv-composite-gametest-20260727-105143.log`

## Composite-02 physical cancellation isolation

The real concurrent fixture runs two independently identified lines at the
same time in Direct, Bots and Hybrid: C-07 log stripping and C-08
andesite-alloy mixing.
Each line has its own verified physical plan, graph, source, delivery, final
output, session and worker identities. Their delivery Hoppers share one
physical redstone interlock whose reservation is hard-bound to line A's
verified plan and starts with line A as its sole owner; line B acquires a
second reference before either real handler starts.

After both lines have real runtime progress, the fixture cancels line A through
the existing detailed cancellation path. Its source is restored and delivery
is empty, while the shared block and line B's sole remaining reference are
still present. Line B then completes its real Create recipe and only its final
release authorizes removal of the shared lock, after which the existing Hopper
moves the real andesite-alloy output into B's final chest. Cleanup removes the
two exact shared Hoppers and B's session-owned machine; A's cancelled session
has already performed its exact rollback.

All three modes pass, line B's exact handler reload reconciliation is retained,
and wrapper-level concurrent snapshot/recovery is covered by the isolated
checkpoint. Player-visible acceptance remains open.

Automated evidence:

- `work/logs/phase-iv-composite-gametest-20260727-111430.log` (Composite-01
  and Composite-02 Direct/Bots/Hybrid, 6/6, clean shutdown)

## Composite-03 physical branch and merge

The first passing WIP Direct fixture is a true four-node/five-edge DAG. A
locked, direction-switched physical Hopper splits one stripped oak log to the
wood branch and one shaft plus one oak plank to the second branch. C-07 cuts
the stripped log into six planks while C-10 independently deploys the other
shaft/plank pair into one cogwheel. The merge remains waiting until both real
handler outputs exist.

Two separately locked Hoppers then reserve one produced plank and the cogwheel
into the merge source. The five unreserved planks are redirected through the
same physical Hopper into a dedicated salvage chest. C-10 finally consumes the
one plank and cogwheel to produce one real large cogwheel. Every boundary
inventory and Hopper rejects unexpected identity or NBT and enforces capacity;
the coordinator never inserts an intermediate. All three handlers retain exact
idle reload reconciliation and cleanup removes only session-owned machines and
route blocks.

The retained failed runs are material evidence: an initial casing design was
typed `RECIPE_NOT_FOUND` because Create `item_application` is outside the C-10
Depot-item contract, and an andesite-alloy Saw attempt produced the real but
unrequested `create:andesite_ladder`; C-07 correctly refused that ambiguous
runtime result. Neither was treated as success.

Automated evidence:

- `work/logs/phase-iv-composite-gametest-20260727-113608.log` (Composite-01/02
  plus Composite-03 Direct, 7/7, clean shutdown)

## Remaining work

- Map the remaining non-item/semantic construction roles to reviewed survival
  actions so the ordinary player can fund the same manifests.
- Persist/recover an active Composite wrapper across a full process restart;
  handler reload and bounded wrapper snapshot checks do not recreate mutation
  authority after restart.
- Run the final player-visible Mac acceptance after survival mappings, then the
  separately deferred Windows/PCL2 acceptance.
