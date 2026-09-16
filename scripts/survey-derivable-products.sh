#!/usr/bin/env bash
# Read-only survey: how much of the live recipe registry can this architecture derive
# and physically build? Mutates nothing — it only reads the recipe manager.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
run_directory="${root}/forge-create-1.20.1/run/derivable-product-survey"
log_directory="${root}/work/logs"

rm -rf "${run_directory}"
mkdir -p "${run_directory}" "${log_directory}"
printf '%s\n' '# Repository-owned read-only registry survey.' 'eula=true' \
    > "${run_directory}/eula.txt"
# Planning runs the isolated-execution path proof even though nothing is mutated.
printf '%s\n' 'steve-industrial:isolated-execution/v1' \
    > "${run_directory}/.steve-industrial-execution-test"
# The survey plans every derivable product in one tick. That is a diagnostic doing in
# one go what a player order does once, and with two-stage chains admitted it now plans
# 113 of them — enough to trip the 60s watchdog and kill the server mid-report. Disabled
# here only; nothing else writes this file, and no player-facing path plans in bulk.
printf '%s\n' '# Repository-owned read-only registry survey.' 'online-mode=false' \
    'server-port=0' 'level-name=world' 'level-type=minecraft:flat' \
    'generate-structures=false' 'view-distance=4' 'simulation-distance=4' \
    'max-tick-time=-1' \
    > "${run_directory}/server.properties"

log="${log_directory}/derivable-product-survey-$(date +%Y%m%d-%H%M%S).log"
set +e
"${root}/gradlew" -p "${root}" --offline -PderivableProductSurvey \
    :forge-create-1.20.1:runServer --console=plain 2>&1 | tee "${log}"
status=${PIPESTATUS[0]}
set -e

if ! grep -qF 'DERIVABLE_PRODUCT_SURVEY PASS' "${log}"; then
    echo "Survey did not complete; see ${log}" >&2
    exit 1
fi

echo
echo "=== survey result"
grep -oE 'DERIVABLE_PRODUCT_SURVEY .*' "${log}" | head -5
echo "Log: ${log}"
exit "${status}"
