#!/usr/bin/env bash
# C-08 production survival-power probe.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
run_root="${root}/forge-create-1.20.1/run"
run_directory="${run_root}/c08-survival-power-gametest"
log_directory="${root}/work/logs"

case "${run_directory}" in
    "${run_root}/"*) ;;
    *) echo "Refusing unsafe C-08 run directory: ${run_directory}" >&2; exit 1 ;;
esac

rm -rf "${run_directory}"
mkdir -p "${run_directory}" "${log_directory}"
printf '%s\n' '# Repository-owned isolated C-08 survival-power production GameTest.' \
    'eula=true' > "${run_directory}/eula.txt"
printf '%s\n' 'steve-industrial:isolated-execution/v1' \
    > "${run_directory}/.steve-industrial-execution-test"
printf '%s\n' \
    '# Repository-owned isolated C-08 survival-power production GameTest.' \
    'online-mode=false' 'server-port=0' 'level-name=world' \
    'level-seed=steve-industrial-c08-survival-power-v1' \
    'level-type=minecraft:flat' 'generate-structures=false' \
    'view-distance=8' 'simulation-distance=8' > "${run_directory}/server.properties"

log="${log_directory}/c08-survival-power-gametest-$(date +%Y%m%d-%H%M%S).log"
set +e
"${root}/gradlew" -p "${root}" --offline -Pc08SurvivalPowerGameTest \
    :forge-create-1.20.1:runGameTestServer --console=plain 2>&1 | tee "${log}"
rc=${PIPESTATUS[0]}
set -e

assert_log() {
    if ! grep -qF "$1" "${log}"; then
        echo "Missing $2 in ${log}. Expected: $1" >&2
        exit 1
    fi
}

assert_log 'C08_SURVIVAL_POWER_TOPOLOGY PASS' 'live Create topology evidence'
assert_log 'C08_SURVIVAL_POWER_PROCESS PASS' 'real mixing cycle evidence'
assert_log 'C08_SURVIVAL_POWER_MATERIAL PASS' 'material ledger evidence'
assert_log 'duplicateWithdrawals=0 duplicateReturns=0 unaccountedItems=0 materialLedgerBalanced=true' \
    'balanced material ledger'
assert_log 'planned=26 reserved=26 withdrawn=26 consumed=2 returned=24' \
    'complete item-form material bill including water bucket'
assert_log 'C08_SURVIVAL_POWER_EVIDENCE PASS evidenceComplete=true ordinaryPlayerEligible=true' \
    'production eligibility gate'
assert_log 'reviewStatus=VERIFIED_SURVIVAL' 'verified-survival status'
assert_log 'C08_SURVIVAL_POWER_GAMETEST PASS' 'C-08 production suite evidence'
assert_log 'realWaterWheel=true realGearing=true realMixer=true realMixingRecipe=true realInputEntities=true realOutput=true realMaterialLedger=true productionEligible=true' \
    'real runtime and production-eligibility markers'
assert_log 'All 1 required tests passed' 'exactly one isolated C-08 test'

if grep -qE 'GameTest.*failed|crash-reports/crash-' "${log}"; then
    echo "C-08 survival-power candidate log contains a failure marker: ${log}" >&2
    exit 1
fi
if [ "${rc}" -ne 0 ]; then
    echo "C-08 survival-power GameTest exited ${rc}" >&2
    exit "${rc}"
fi

echo "C-08 survival-power production GameTest passed"
echo "Log: ${log}"
