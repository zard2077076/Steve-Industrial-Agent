#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
run_root="${root}/forge-create-1.20.1/run"
run_directory="${run_root}/c09-survival-power-gametest"
log_directory="${root}/work/logs"
case "${run_directory}" in "${run_root}/"*) ;; *) exit 1 ;; esac
rm -rf "${run_directory}"
mkdir -p "${run_directory}" "${log_directory}"
printf '%s\n' '# Repository-owned isolated C-09 production probe.' 'eula=true' > "${run_directory}/eula.txt"
printf '%s\n' 'steve-industrial:isolated-execution/v1' > "${run_directory}/.steve-industrial-execution-test"
printf '%s\n' '# Repository-owned isolated C-09 production probe.' 'online-mode=false' \
  'server-port=0' 'level-name=world' 'level-seed=steve-industrial-c09-survival-power-v1' \
  'level-type=minecraft:flat' 'generate-structures=false' 'view-distance=8' \
  'simulation-distance=8' > "${run_directory}/server.properties"
log="${log_directory}/c09-survival-power-gametest-$(date +%Y%m%d-%H%M%S).log"
set +e
"${root}/gradlew" -p "${root}" --offline -Pc09SurvivalPowerGameTest \
  :forge-create-1.20.1:runGameTestServer --console=plain 2>&1 | tee "${log}"
rc=${PIPESTATUS[0]}
set -e
for marker in C09_SURVIVAL_POWER_TOPOLOGY C09_SURVIVAL_POWER_PROCESS \
  C09_SURVIVAL_POWER_MATERIAL C09_SURVIVAL_POWER_EVIDENCE C09_SURVIVAL_POWER_GAMETEST; do
  grep -qF "${marker} PASS" "${log}" || { echo "Missing ${marker} PASS: ${log}" >&2; exit 1; }
done
grep -qF 'duplicateWithdrawals=0 duplicateReturns=0 unaccountedItems=0 materialLedgerBalanced=true' "${log}"
grep -qF 'planned=25 reserved=25 withdrawn=25 consumed=3 returned=22' "${log}"
grep -qF 'evidenceComplete=true ordinaryPlayerEligible=true' "${log}"
grep -qF 'reviewStatus=VERIFIED_SURVIVAL' "${log}"
grep -qF 'All 1 required tests passed' "${log}"
if [ "${rc}" -ne 0 ]; then exit "${rc}"; fi
echo "C-09 survival-power production GameTest passed"
echo "Log: ${log}"
