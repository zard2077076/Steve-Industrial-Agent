#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
run_root="${root}/forge-create-1.20.1/run"
run_directory="${run_root}/ie-alloy-warehouse-restart"
log_directory="${root}/work/logs"

case "${run_directory}" in
    "${run_root}/"*) ;;
    *) echo "Refusing unsafe Alloy warehouse directory: ${run_directory}" >&2; exit 1 ;;
esac

rm -rf "${run_directory}"
mkdir -p "${run_directory}" "${log_directory}"
printf '%s\n' 'eula=true' > "${run_directory}/eula.txt"
printf '%s\n' 'steve-industrial:isolated-execution/v1' \
    > "${run_directory}/.steve-industrial-execution-test"
printf '%s\n' 'online-mode=false' 'server-port=0' 'level-name=world' \
    'level-type=minecraft:flat' 'generate-structures=false' 'view-distance=8' \
    'simulation-distance=8' > "${run_directory}/server.properties"

stamp="$(date +%Y%m%d-%H%M%S)"
write_log="${log_directory}/ie-alloy-warehouse-restart-write-${stamp}.log"
read_log="${log_directory}/ie-alloy-warehouse-restart-read-${stamp}.log"

"${root}/gradlew" -p "${root}" --offline \
    -PalloySmelterWarehouseRestartPhase=write \
    :forge-create-1.20.1:runServer --console=plain 2>&1 | tee "${write_log}"
grep -qF 'IE_ALLOY_WAREHOUSE_RESTART_WRITE PASS' "${write_log}"
grep -qF 'stage=PROCESSING registrationPersisted=true outputInWarehouse=0' "${write_log}"

"${root}/gradlew" -p "${root}" --offline \
    -PalloySmelterWarehouseRestartPhase=read \
    :forge-create-1.20.1:runServer --console=plain 2>&1 | tee "${read_log}"
grep -qF 'IE_ALLOY_WAREHOUSE_RESTART_READ PASS' "${read_log}"
grep -qF 'oldRuntimeDestroyed=true savedRuntimeRestored=true physicalOrderResumed=true' "${read_log}"
grep -qF 'batchAutoSettled=true targetSatisfied=true outputInWarehouse=2 looseOutputs=0' "${read_log}"
grep -qF 'duplicateWithdrawals=0 duplicateReturns=0 duplicateOutputs=0' "${read_log}"
grep -qF 'unaccountedItems=0 materialLedgerBalanced=true baselineRestored=true report=true' "${read_log}"

echo "IE Alloy Smelter warehouse restart acceptance passed: ${read_log}"
