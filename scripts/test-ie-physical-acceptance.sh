#!/usr/bin/env bash
# Immersive Engineering physical acceptance: real multiblock formation, a real
# thermoelectric generator feeding real LV connectors, and a real Metal Press run.
#
# This had no runner at all — the switches existed in build.gradle and could only be
# reached by typing gradle by hand, which is how a gate stops being run without anyone
# deciding to stop running it.
#
# IE is compileOnly and only joins the runtime classpath when this property is set, so
# this is the one environment where Forge Energy blocks actually exist.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
run_root="${root}/forge-create-1.20.1/run"
run_directory="${run_root}/ie-v1020-physical-acceptance"
log_directory="${root}/work/logs"

case "${run_directory}" in
    "${run_root}/"*) ;;
    *) echo "Refusing unsafe IE run directory: ${run_directory}" >&2; exit 1 ;;
esac

rm -rf "${run_directory}"
mkdir -p "${run_directory}" "${log_directory}"

printf '%s\n' '# Repository-owned isolated IE physical acceptance.' 'eula=true' \
    > "${run_directory}/eula.txt"
printf '%s\n' 'steve-industrial:isolated-execution/v1' \
    > "${run_directory}/.steve-industrial-execution-test"
printf '%s\n' '# Repository-owned isolated IE physical acceptance.' \
    'online-mode=false' 'server-port=0' 'level-name=world' 'level-type=minecraft:flat' \
    'generate-structures=false' 'view-distance=8' 'simulation-distance=8' \
    > "${run_directory}/server.properties"

log="${log_directory}/ie-physical-acceptance-$(date +%Y%m%d-%H%M%S).log"
set +e
"${root}/gradlew" -p "${root}" --offline -PiePhysicalAcceptance \
    :forge-create-1.20.1:runServer --console=plain 2>&1 | tee "${log}"
status=${PIPESTATUS[0]}
set -e

assert_log() {
    if ! grep -qF "$1" "${log}"; then
        echo "Missing $2 in ${log}. Expected: $1" >&2
        exit 1
    fi
}

assert_log 'IE_V1020_REAL_POWER_SUBGATE PASS' 'real power sub-gate'
assert_log 'IE_V1020_PHYSICAL_ACCEPTANCE PASS' 'IE physical acceptance'
assert_log 'commonOrderBound=true commonReportAccepted=true' \
    'Metal Press adapter registration reaches the common industrial order and report'
assert_log 'resourceBindingBalanced=true resourceWorkers=2 resourceAssignments=13' \
    'Metal Press real item/Bot/FE binding reaches exact balanced settlement'
assert_log 'directEnergyWrites=0' 'no direct energy writes'

# The only place the energy scan can meet real Forge Energy blocks. canMoveEnergy=true
# is the claim that matters: it needs two different endpoints facing opposite ways, so a
# scan that found only the generator would fail here rather than log a cheerful count.
assert_log 'IE_V1020_ENERGY_SCAN PASS' 'energy endpoint scan against real blocks'
assert_log 'canMoveEnergy=true' 'a real generator and consumer pair'

# The discrimination the flags cannot provide. Every IE endpoint allows both directions,
# so GENERATOR/CONSUMER never appear from canExtract/canReceive; energy appearing over
# time is the only evidence that something is actually generating.
assert_log 'IE_V1020_ENERGY_BEHAVIOUR PASS' 'generation observed rather than declared'

# C7's actual question: is a second IE machine a parameter or four hundred fresh lines?
# Forming one that is not the press, through the same code, is the only answer that counts.
assert_log 'IE_V1020_SECOND_MULTIBLOCK PASS machine=ALLOY_SMELTER' 'a second IE multiblock forms'
assert_log 'formed=true masterPresent=true clearedAfter=true' 'second machine formed and cleaned up'

# Formed is not the same as operable: an inventory that rejects its own recipe's inputs,
# or a slot layout read wrongly, would both survive a formation check.
assert_log 'IE_V1020_SECOND_MACHINE_OPERABLE PASS' 'the second machine takes a real recipe'
assert_log 'inputsAccepted=true recipeRecognised=true' 'inputs accepted and recipe matched'

# The claim C7 was actually after: a machine that is not the press runs a real batch.
# burnedFuel=true matters — the alloy smelter burns rather than drawing FE, and without
# fuel every other check still passes while nothing is ever produced.
assert_log 'IE_V1020_SECOND_MACHINE_BATCH PASS machine=ALLOY_SMELTER' 'a second machine produced'
assert_log 'burnedFuel=true' 'the batch was fuelled'
assert_log 'fuelInserted=1 fuelConsumed=true residualItems=0 adapterOwned=true' \
    'one exact fuel item was consumed with no hidden residue through the production adapter'

# IE-MP-02 is not complete at the low-level adapter boundary. The reviewed fuel
# machine must traverse the same player-selected source, material journal, visible
# courier/build Bot, exact return, common report and baseline restoration contract.
assert_log 'IE_V1020_ALLOY_PLAYER_ORDER PASS' 'the reviewed Alloy Smelter player order'
assert_log 'selectedPlayerSource=true exactReservation=true visibleMaterialCourierBot=true' \
    'the real player material protocol and visible carry path'
assert_log 'itemFuelLedger=true inventedFe=0 realRecipeOutput=2 uniqueOutput=true' \
    'fuel accounted as material and one unique real brass output'
assert_log 'duplicateWithdrawals=0 duplicateReturns=0 duplicateOutputs=0' \
    'idempotent withdrawal return and output settlement'
assert_log 'unaccountedItems=0 privateItemsTouched=0 materialLedgerBalanced=true' \
    'balanced private-safe material settlement'
assert_log 'completionReport=true baselineRestored=true' \
    'common report and exact site restoration'

# A pre-commit cancellation is a different terminal result, not a fake completion.
# Stop while the courier physically carries the first reservation so both the
# WITHDRAWN -> RETURNED and PREPARED -> RELEASED paths are exercised together.
assert_log 'IE_V1020_ALLOY_PLAYER_CANCEL PASS cancelledWhileBotCarrying=true' \
    'cancellation during an actual in-flight material withdrawal'
assert_log 'exactWithdrawnReturn=true preparedReservationsReleased=true' \
    'withdrawn material returned and untouched reservations released'
assert_log 'completionReport=false baselineRestored=true botsDiscarded=true' \
    'cancellation did not fabricate completion and left no order Bot or structure'

if [ "${status}" -ne 0 ]; then
    echo "IE physical acceptance exited ${status}" >&2
    exit "${status}"
fi

echo "IE physical acceptance passed"
echo "Log: ${log}"
