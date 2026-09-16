#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
run_root="${root}/forge-create-1.20.1/run"
log_directory="${root}/work/logs"
windows=(
    WITHDRAWN_NOT_DELIVERED
    BATCH_ADMITTED_NOT_OUTPUT
    OUTPUT_CLAIMED_NOT_REPORTED
)

mkdir -p "${log_directory}"

for window in "${windows[@]}"; do
    slug="$(printf '%s' "${window}" | tr '[:upper:]_' '[:lower:]-')"
    run_directory="${run_root}/ie-alloy-smelter-recovery-${slug}"
    case "${run_directory}" in
        "${run_root}/"*) ;;
        *) echo "Refusing unsafe Alloy reload directory: ${run_directory}" >&2; exit 1 ;;
    esac
    rm -rf "${run_directory}"
    mkdir -p "${run_directory}"
    printf '%s\n' 'eula=true' > "${run_directory}/eula.txt"
    printf '%s\n' 'steve-industrial:isolated-execution/v1' \
        > "${run_directory}/.steve-industrial-execution-test"
    printf '%s\n' 'online-mode=false' 'server-port=0' 'level-name=world' \
        'level-type=minecraft:flat' 'generate-structures=false' 'view-distance=8' \
        'simulation-distance=8' > "${run_directory}/server.properties"

    stamp="$(date +%Y%m%d-%H%M%S)"
    write_log="${log_directory}/ie-alloy-reload-${slug}-write-${stamp}.log"
    read_log="${log_directory}/ie-alloy-reload-${slug}-read-${stamp}.log"

    "${root}/gradlew" -p "${root}" --offline \
        -PalloySmelterRecoveryReloadAcceptancePhase="write:${window}" \
        :forge-create-1.20.1:runServer --console=plain 2>&1 | tee "${write_log}"
    grep -qF "IE_ALLOY_RECOVERY_RELOAD_WRITE PASS window=${window}" "${write_log}"

    "${root}/gradlew" -p "${root}" --offline \
        -PalloySmelterRecoveryReloadAcceptancePhase="read:${window}" \
        :forge-create-1.20.1:runServer --console=plain 2>&1 | tee "${read_log}"
    grep -qF "IE_ALLOY_RECOVERY_RELOAD_ACCEPTANCE PASS window=${window}" "${read_log}"
    grep -qF 'oldRuntimeDestroyed=true savedDataReloaded=true resumed=true' "${read_log}"
    grep -qF 'duplicateWithdrawals=0 duplicateReturns=0 duplicateOutputs=0' "${read_log}"
    grep -qF 'unaccountedItems=0 materialLedgerBalanced=true' "${read_log}"
    grep -qF 'baselineRestored=true report=true output=2' "${read_log}"
done

echo 'IE Alloy Smelter recovery/reload acceptance passed (3/3 windows)'
