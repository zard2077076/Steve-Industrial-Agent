#!/usr/bin/env bash
# C4: the unattended production loop, driven by real world stock.
#
# Its own dedicated server rather than a GameTest, and that is not a style choice: as a
# GameTest it waited ninety-odd ticks for the twenty-tick service and reproducibly broke
# an unrelated neighbour (C07 failed on a stray dirt item in its lane; disabling this one
# test restored 51/51). A test that lives across many ticks in a shared arena has
# neighbours.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
run_root="${root}/forge-create-1.20.1/run"
run_directory="${run_root}/warehouse-unattended"
log_directory="${root}/work/logs"

case "${run_directory}" in
    "${run_root}/"*) ;;
    *) echo "Refusing unsafe warehouse run directory: ${run_directory}" >&2; exit 1 ;;
esac

rm -rf "${run_directory}"
mkdir -p "${run_directory}" "${log_directory}"

printf '%s\n' '# Repository-owned isolated unattended-production acceptance.' 'eula=true' \
    > "${run_directory}/eula.txt"
printf '%s\n' 'steve-industrial:isolated-execution/v1' \
    > "${run_directory}/.steve-industrial-execution-test"
printf '%s\n' '# Repository-owned isolated unattended-production acceptance.' \
    'online-mode=false' 'server-port=0' 'level-name=world' 'level-type=minecraft:flat' \
    'generate-structures=false' 'view-distance=8' 'simulation-distance=8' \
    > "${run_directory}/server.properties"

log="${log_directory}/warehouse-unattended-$(date +%Y%m%d-%H%M%S).log"
set +e
"${root}/gradlew" -p "${root}" --offline -PwarehouseUnattended \
    :forge-create-1.20.1:runServer --console=plain 2>&1 | tee "${log}"
status=${PIPESTATUS[0]}
set -e

assert_log() {
    if ! grep -qF "$1" "${log}"; then
        echo "Missing $2 in ${log}. Expected: $1" >&2
        exit 1
    fi
}

# Reachability, which the inventory graph never modelled: it was always built with an
# empty edge list while reachable() walks edges, so nothing reached anything.
# blockedEdges=0 is the assertion with teeth — a builder claiming an edge for every pair
# would pass every other check here.
assert_log 'WAREHOUSE_TOPOLOGY PASS' 'warehouse reachability'
assert_log 'blockedEdges=0' 'an obstructed run claims no edge'
assert_log 'refreshAdvancedGeneration=true' 'a refresh is distinguishable from a stale graph'

assert_log 'WAREHOUSE_UNATTENDED PASS' 'the unattended loop reached a decision'
# The assertion with teeth. A runtime that dispatched unconditionally would pass a gate
# built only from understocked examples, and that failure produces goods nobody asked for.
assert_log 'authorisedWhenStocked=0' 'a warehouse above its target authorises nothing'
assert_log 'stockedObserved=32' 'stock was read from a real chest'
assert_log 'authorisedBatches=2' 'the batch count matches the deficit'
# Said plainly in the evidence itself: this proves a decision, not production.
assert_log 'producedNothing=true' 'the gate does not claim to have produced anything'

# The step that turns a planning agent into an operating one: an authorisation becomes a
# real order through the same service a player's own order uses. viaPlayerOrderService is
# the part that matters — an acceptance fixture could have staged one in a single call,
# and that would prove nothing about the production path.
# Both typed refusals, exercised. An unexercised refusal is a refusal nobody knows
# works, and these guard against ordering on ground nobody is near.
assert_log 'WAREHOUSE_DISPATCH_REFUSALS PASS siteTooFar=refused sourcesOutOfReach=refused' 'dispatch refuses out of reach'

assert_log 'WAREHOUSE_DISPATCH PASS' 'an authorisation became a real order'
assert_log 'viaPlayerOrderService=true mode=BOTS' 'the production path, run by the bot fleet'
# The loop closing: the warehouse ends up holding the thing it was short of, and the
# scheduler is told so. producedInWarehouse is read back off the chest, not from the
# order's own report.
assert_log 'batchSettled=true failuresReset=true' 'the batch settled and the loop closed'

# C5-A: material physically carried from the warehouse to the site before the order
# exists. distributeUntouched is the design claim — the carry sits outside the order, so
# nothing the order relies on has to survive it.
assert_log 'WAREHOUSE_CARRY PASS' 'a bot carried the bill to the site'
assert_log 'distributeUntouched=true orderCompleted=true' 'the carried order ran to completion'

# C5-B residency: the line stays up after the order that built it completes, and the
# ledger settles anyway because the site was kept on purpose rather than abandoned.
assert_log 'WAREHOUSE_RESIDENT PASS' 'a production line stayed standing'
# Against a control, not against zero: phase five ran the same graph without retaining,
# so "more than the cleared site" is the claim rather than "more than nothing".
assert_log 'clearedBlocks=0' 'the non-retained site really was cleared'
assert_log 'reportGenerated=true siteRetained=true' 'a retained site still settles'

# A blocked site is a detour, not a stoppage. The refusal with only one site is the half
# that gives the acceptance meaning: without it, a dispatch that ignored the preferred
# site entirely would pass.
assert_log 'WAREHOUSE_FAILOVER PASS' 'production moved to a spare site'
assert_log 'sitesTried=2' 'the preferred site was tried first'

# Inter-factory routing: stock moving between two warehouses under its own power.
# conserved=true is the assertion with teeth — a transfer that duplicated material would
# satisfy any check that only looked at the receiving end.
assert_log 'WAREHOUSE_TRANSFER PASS' 'stock moved between warehouses'
assert_log 'conserved=true' 'the source was drawn down by exactly what arrived'

# A product that is one machine rather than a chain. Everything above runs
# composite/01, and until now every maintainable product was a chain — 111 of them and
# all 111 wood cut on a saw. routes=0 is the part with teeth: a one-stage order has
# nothing to carry between machines, so anything still assuming an edge fails here.
assert_log 'WAREHOUSE_SINGLE_MACHINE PASS' 'a warehouse maintained a single-machine product'
assert_log 'product=create:shaft stages=1 routes=0' 'one stage, no routes, a real Create part'

# Blocks with no item of their own, bought as the item a player actually hands over.
# The bill assertion is inside the fixture: a phase whose product turned out not to be
# fan-washed would otherwise pass while proving nothing about buckets.
assert_log 'product=minecraft:blue_concrete stages=1 routes=0 boughtByItem=minecraft:water_bucket' \
    'a site whose escrow held a bucket built and settled'
assert_log 'product=create:haunted_bell stages=1 routes=0 boughtByItem=minecraft:flint_and_steel' \
    'a site whose escrow held a tool built and settled'

# Tidying your own chest must not lose the work. A courier rather than a composite order:
# composite reserves and distributes in the same tick, so there is no window there — the
# first version of this phase failed its own no-op guard for exactly that reason. The
# fixture refuses to pass if the rearrangement did not actually move anything, which is
# what would make this vacuous, and conserved=true is what stops a transfer that quietly
# duplicated material from passing.
# The negative half lives in PlayerMaterialSnapshotTest and is verified there against the
# old implementation: taking anything out is still refused.
assert_log 'WAREHOUSE_TIDIED_CHEST PASS' 'a rearranged chest did not lose the work'
assert_log 'orderSurvived=true' 'the courier finished after the chest was reorganised'

if grep -qF 'WAREHOUSE_UNATTENDED_ACCEPTANCE FAIL' "${log}"; then
    echo "Unattended acceptance reported a failure in ${log}" >&2
    exit 1
fi

if [ "${status}" -ne 0 ]; then
    echo "Unattended acceptance exited ${status}" >&2
    exit "${status}"
fi

echo "Unattended production acceptance passed"
echo "Log: ${log}"
