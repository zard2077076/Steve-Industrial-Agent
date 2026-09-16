#!/usr/bin/env bash
# Historical C-06 survival-power candidate probe.
#
# This is an isolated dedicated-server GameTest, not a player unlock and not a
# replacement for the production topology or real-client acceptance run. It proves a live Create
# water-wheel -> gearbox -> shaft -> fan path, role-level evidence, and exact
# material reservation/withdrawal/return conservation in a repository-owned run.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
run_root="${root}/forge-create-1.20.1/run"
run_directory="${run_root}/c06-survival-power-gametest"
log_directory="${root}/work/logs"

case "${run_directory}" in
    "${run_root}/"*) ;;
    *) echo "Refusing unsafe C-06 run directory: ${run_directory}" >&2; exit 1 ;;
esac

rm -rf "${run_directory}"
mkdir -p "${run_directory}" "${log_directory}"

printf '%s\n' '# Repository-owned isolated C-06 survival-power candidate GameTest.' 'eula=true' \
    > "${run_directory}/eula.txt"
printf '%s\n' 'steve-industrial:isolated-execution/v1' \
    > "${run_directory}/.steve-industrial-execution-test"
printf '%s\n' \
    '# Repository-owned isolated C-06 survival-power candidate GameTest.' \
    'online-mode=false' 'server-port=0' 'level-name=world' \
    'level-seed=steve-industrial-c06-survival-power-v1' \
    'level-type=minecraft:flat' 'generate-structures=false' \
    'view-distance=8' 'simulation-distance=8' \
    > "${run_directory}/server.properties"

log="${log_directory}/c06-survival-power-gametest-$(date +%Y%m%d-%H%M%S).log"
set +e
"${root}/gradlew" -p "${root}" --offline -Pc06SurvivalPowerGameTest \
    :forge-create-1.20.1:runGameTestServer --console=plain 2>&1 | tee "${log}"
status=${PIPESTATUS[0]}
set -e

assert_log() {
    if ! grep -qF "$1" "${log}"; then
        echo "Missing $2 in ${log}. Expected: $1" >&2
        exit 1
    fi
}

assert_log 'C06_SURVIVAL_POWER_TOPOLOGY PASS' 'live Create topology evidence'
assert_log 'C06_SURVIVAL_POWER_MATERIAL PASS' 'material ledger evidence'
assert_log 'duplicateWithdrawals=0 duplicateReturns=0 unaccountedItems=0 materialLedgerBalanced=true' \
    'balanced material ledger'
assert_log 'C06_SURVIVAL_POWER_EVIDENCE PASS evidenceComplete=true ordinaryPlayerEligible=false' \
    'candidate review gate'
assert_log 'reviewStatus=REVIEW_REQUIRED' 'review-required status'
assert_log 'C06_SURVIVAL_POWER_GAMETEST PASS' 'C-06 candidate suite evidence'
assert_log 'realWaterWheel=true realFan=true realAirCurrent=true realMaterialLedger=true candidateOnly=true' \
    'real runtime and candidate-only markers'
assert_log 'All 1 required tests passed' 'exactly one isolated C-06 test'

if grep -qE 'GameTest.*failed|crash-reports/crash-' "${log}"; then
    echo "C-06 survival-power candidate log contains a failure marker: ${log}" >&2
    exit 1
fi
if [ "${status}" -ne 0 ]; then
    echo "C-06 survival-power GameTest exited ${status}" >&2
    exit "${status}"
fi

echo "Historical C-06 survival-power candidate GameTest passed (production promotion is verified separately)"
echo "Log: ${log}"
