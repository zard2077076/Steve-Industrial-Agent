#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
run_root="${root}/forge-create-1.20.1/run"
run_directory="${run_root}/ipo03-resource-restart"
log_directory="${root}/work/logs"

case "${run_directory}" in
    "${run_root}/"*) ;;
    *) echo "Refusing unsafe IPO-03 directory: ${run_directory}" >&2; exit 1 ;;
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
write_log="${log_directory}/ipo03-resource-restart-write-${stamp}.log"
read_log="${log_directory}/ipo03-resource-restart-read-${stamp}.log"

"${root}/gradlew" -p "${root}" --offline -Pipo03ResourceRestartPhase=write \
    :forge-create-1.20.1:runServer --console=plain 2>&1 | tee "${write_log}"
grep -qF 'IPO03_RESOURCE_RESTART_WRITE PASS' "${write_log}"
grep -qF 'warehouseWithdrawn=8 bots=2 feExtracted=2400 fluidDelivered=250' "${write_log}"

"${root}/gradlew" -p "${root}" --offline -Pipo03ResourceRestartPhase=read \
    :forge-create-1.20.1:runServer --console=plain 2>&1 | tee "${read_log}"
grep -qF 'IPO03_RESOURCE_RESTART_READ PASS' "${read_log}"
grep -qF 'oldRuntimeDestroyed=true bindingReloaded=true liveEndpointsReregistered=true' "${read_log}"
grep -qF 'warehouse=8 bots=2 fe=2400 fluid=250' "${read_log}"
grep -qF 'duplicateWithdrawals=0 duplicateReturns=0 duplicateEnergy=0' "${read_log}"
grep -qF 'duplicateFluidWithdrawals=0 duplicateFluidReturns=0' "${read_log}"
grep -qF 'unaccounted=0 privateItemsTouched=0 materialLedgerBalanced=true' "${read_log}"
grep -qF 'baselineRestored=true report=true' "${read_log}"

echo "IPO-03 resource restart acceptance passed: ${read_log}"
