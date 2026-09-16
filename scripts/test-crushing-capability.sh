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
run_directory="${run_root}/crushing-capability"
log_directory="${root}/work/logs"

case "${run_directory}" in
    "${run_root}/"*) ;;
    *) echo "Refusing unsafe goal-driven run directory: ${run_directory}" >&2; exit 1 ;;
esac

rm -rf "${run_directory}"
mkdir -p "${run_directory}" "${log_directory}"

printf '%s\n' '# Repository-owned isolated crushing capability GameTest.' 'eula=true' \
    > "${run_directory}/eula.txt"
printf '%s\n' 'steve-industrial:isolated-execution/v1' \
    > "${run_directory}/.steve-industrial-execution-test"
printf '%s\n' '# Repository-owned isolated crushing capability GameTest.' \
    'online-mode=false' 'server-port=0' 'level-name=world' 'level-type=minecraft:flat' \
    'generate-structures=false' 'view-distance=8' 'simulation-distance=8' \
    > "${run_directory}/server.properties"

log="${log_directory}/crushing-capability-$(date +%Y%m%d-%H%M%S).log"
set +e
"${root}/gradlew" -p "${root}" --offline -PgoalDrivenExecutionGameTest -PcrushingCapabilityOnly \
    :forge-create-1.20.1:runGameTestServer --console=plain 2>&1 | tee "${log}"
status=${PIPESTATUS[0]}
set -e

assert_log() {
    if ! grep -qF "$1" "${log}"; then
        echo "Missing $2 in ${log}. Expected: $1" >&2
        exit 1
    fi
}

# Crushing alone, in its own world. The machine grinds the arena floor and the debris
# lands in whatever runs next door, so this cannot share a suite — see trap 24.
assert_log 'GOAL_EXECUTION_GAMETEST PASS target=minecraft:sand quantity=1 observed=1' 'crushing'
assert_log 'All 1 required tests passed' 'exactly one test, alone in its world'

if [ "${status}" -ne 0 ]; then
    echo "Goal-driven execution GameTests exited ${status}" >&2
    exit "${status}"
fi

echo "Goal-driven execution GameTests passed"
echo "Log: ${log}"
