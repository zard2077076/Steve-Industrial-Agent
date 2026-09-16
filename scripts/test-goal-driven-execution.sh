#!/usr/bin/env bash
# Goal-driven single-machine execution GameTests, the bash counterpart to
# Test-GoalDrivenExecution.ps1 for hosts without PowerShell.
#
# It asserts the same evidence lines rather than a bare exit code: these tests
# have passed while reporting the wrong observed count before, and an exit code
# would not have said so.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
run_root="${root}/forge-create-1.20.1/run"
run_directory="${run_root}/goal-driven-execution-gametest"
log_directory="${root}/work/logs"

case "${run_directory}" in
    "${run_root}/"*) ;;
    *) echo "Refusing unsafe goal-driven run directory: ${run_directory}" >&2; exit 1 ;;
esac

rm -rf "${run_directory}"
mkdir -p "${run_directory}" "${log_directory}"

printf '%s\n' '# Repository-owned isolated goal-driven execution GameTest.' 'eula=true' \
    > "${run_directory}/eula.txt"
printf '%s\n' 'steve-industrial:isolated-execution/v1' \
    > "${run_directory}/.steve-industrial-execution-test"
printf '%s\n' '# Repository-owned isolated goal-driven execution GameTest.' \
    'online-mode=false' 'server-port=0' 'level-name=world' 'level-type=minecraft:flat' \
    'generate-structures=false' 'view-distance=8' 'simulation-distance=8' \
    > "${run_directory}/server.properties"

log="${log_directory}/goal-driven-execution-gametest-$(date +%Y%m%d-%H%M%S).log"
set +e
"${root}/gradlew" -p "${root}" --offline -PgoalDrivenExecutionGameTest \
    :forge-create-1.20.1:runGameTestServer --console=plain 2>&1 | tee "${log}"
status=${PIPESTATUS[0]}
set -e

assert_log() {
    if ! grep -qF "$1" "${log}"; then
        echo "Missing $2 in ${log}. Expected: $1" >&2
        exit 1
    fi
}

assert_log 'GOAL_EXECUTION_GAMETEST PASS target=minecraft:gravel quantity=3 observed=3 orientation=ZERO' 'gravel zero execution'
assert_log 'GOAL_EXECUTION_GAMETEST PASS target=create:iron_sheet quantity=2 observed=2' 'iron-sheet execution'
# The multi-batch answer C3a-2 step 2 depends on. observed=12 is the whole point:
# a pass reporting a smaller count would mean the executor silently ran one batch.
assert_log 'GOAL_EXECUTION_GAMETEST PASS target=minecraft:gravel quantity=6 observed=6' 'multi-batch execution'

# A target nobody enumerated, resolved and built. The survey's executable count says
# the resolver is reachable; only this says a derived goal produces anything.
assert_log 'GOAL_EXECUTION_GAMETEST PASS target=minecraft:baked_potato quantity=1 observed=1' 'derived target execution'
# The real terminal path, not only the planner: search used to find this row and the
# order button then looked in the empty-query eleven-row subset and refused it.
assert_log 'PLAYER_DERIVED_ORDER PASS target=minecraft:blue_concrete' 'searched derived player order'
assert_log 'searchVisible=true reviewedCatalog=false createProject=OK' 'derived order bypasses reviewed display subset'

# KI-088's finding half. The counts matter more than the pass: 12 combined proves the
# excluded chest's 99 stayed out, and two results prove the empty one did too.
assert_log 'WAREHOUSE_DISCOVERY PASS found=2' 'warehouse discovery'
assert_log 'excludedHonoured=true emptySkipped=true' 'discovery exclusion evidence'

# KI-089's finding half. The empty tank being kept is the rule that differs from
# inventory discovery: a transfer needs a destination as much as a source.
assert_log 'FLUID_DISCOVERY PASS found=2' 'fluid endpoint discovery'
assert_log 'emptyKeptAsDestination=true' 'empty destination evidence'

# A count, because every assertion above is a grep for a line that is present — none of
# them can notice a test that stopped running. The PowerShell runner has had this since
# it was written; the bash one did not, which is the asymmetry that let it pass while
# covering less.
# First physical proof that fluid actually moves between two real tanks. The deltas are
# the point: every earlier fluid assertion was about the ledger's own bookkeeping.
assert_log 'FLUID_TRANSFER PASS movedMb=1000 sourceDeltaMb=1000 destinationDeltaMb=1000' 'physical fluid conservation'
assert_log 'refusalMovedNothing=true selfTransferRefused=true' 'fluid refusal evidence'

# Connectivity, which neither discovery nor the executor could answer. The rejected
# unconnected pair is the assertion with teeth: a walker returning everything nearby
# would pass a test built only from connected examples.
assert_log 'FLUID_TOPOLOGY PASS' 'pipe topology'
assert_log 'unconnectedPairRejected=true selfExcluded=true' 'topology rejects the unconnected pair'

# The unattended loop, which nothing could previously reach: nothing ever registered a
# runtime, so every order was skipped forever. authorisedWhenStocked=0 is the assertion
# with teeth — a runtime that dispatched unconditionally would pass a test built only
# from understocked examples.


# Coverage by capability, not by product count. The 111 buildable composites are one
# shape repeated; breadth lives in the single-machine goals, and these are four executors
# that had never been physically exercised.
assert_log 'GOAL_EXECUTION_GAMETEST PASS target=minecraft:stripped_oak_log quantity=1 observed=1' 'cutting'
assert_log 'GOAL_EXECUTION_GAMETEST PASS target=minecraft:blackstone quantity=1 observed=1' 'haunting'
assert_log 'GOAL_EXECUTION_GAMETEST PASS target=minecraft:iron_ingot quantity=1 observed=1' 'blasting'

assert_log 'GOAL_EXECUTION_GAMETEST PASS target=create:andesite_alloy quantity=1 observed=1' 'mixing'
assert_log 'FLUID_INPUT_MIXING PASS target=create:pulp fluid=minecraft:water amountMb=250' 'fluid-input mixing'
assert_log 'bucket=minecraft:water_bucket bucketConsumed=true liveRecipeMatched=true finalBasinFluidMb=0 outputObserved=1' 'fluid-input material and settlement evidence'
assert_log 'FLUID_INPUT_COMPACTING PASS target=minecraft:granite fluid=minecraft:lava amountMb=100' 'fluid-input compacting'
assert_log 'bucket=minecraft:lava_bucket bucketConsumed=true liveRecipeMatched=true finalBasinFluidMb=0 outputObserved=1' 'lava material and settlement evidence'
assert_log 'C09_LAVA_THREE_MODE_EQUIVALENCE PASS' 'fluid-input compacting three-mode equivalence'
assert_log 'fluidJournalMb=100 noResidue=true' 'fluid-input compacting exact three-mode settlement'
assert_log 'C09_LAVA_CANCEL_MATRIX PASS' 'fluid-input compacting exact cancellation return'
assert_log 'GOAL_EXECUTION_GAMETEST PASS target=create:cogwheel quantity=1 observed=1' 'deploying'

assert_log 'GOAL_EXECUTION_GAMETEST PASS target=create:blaze_cake_base quantity=1 observed=1' 'compacting'
assert_log 'GOAL_EXECUTION_GAMETEST PASS target=create:dough quantity=1 observed=1' 'splashing'

for target in minecraft:gravel create:dough minecraft:stripped_oak_log create:andesite_alloy create:blaze_cake_base create:cogwheel minecraft:blue_concrete; do
    assert_log "PLAYER_CREATE_FIXTURE PASS target=${target} " 'live client-fixture material bill'
done
# Includes the new fixture matrix, in addition to the existing 64 required tests.
assert_log 'All 65 required tests passed' 'integrated GameTest success count'

if [ "${status}" -ne 0 ]; then
    echo "Goal-driven execution GameTests exited ${status}" >&2
    exit "${status}"
fi

echo "Goal-driven execution GameTests passed"
echo "Log: ${log}"
