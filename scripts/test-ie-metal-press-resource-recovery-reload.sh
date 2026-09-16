#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
run_root="${root}/forge-create-1.20.1/run"
log_directory="${root}/work/logs"

windows=(POWER_VERIFIED MAINTENANCE_APPROVED MAINTENANCE_PREPARED PARTIAL_ENERGY ENERGY_SETTLED OUTPUT_OBSERVED)
if [[ $# -gt 0 ]]; then
    windows=("$@")
fi

for window in "${windows[@]}"; do
    case "${window}" in
        POWER_VERIFIED|MAINTENANCE_APPROVED|MAINTENANCE_PREPARED|PARTIAL_ENERGY|ENERGY_SETTLED|OUTPUT_OBSERVED) ;;
        *) echo "Unsupported Metal Press resource reload window: ${window}" >&2; exit 2 ;;
    esac
    slug="$(printf '%s' "${window}" | tr '[:upper:]_' '[:lower:]-')"
    run_directory="${run_root}/ie-metal-press-resource-recovery-${slug}"
    case "${run_directory}" in
        "${run_root}/"*) ;;
        *) echo "Refusing unsafe Metal Press resource reload directory: ${run_directory}" >&2; exit 1 ;;
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
    write_log="${log_directory}/ie-metal-press-resource-${slug}-write-${stamp}.log"
    approve_log="${log_directory}/ie-metal-press-resource-${slug}-approve-${stamp}.log"
    read_log="${log_directory}/ie-metal-press-resource-${slug}-read-${stamp}.log"

    "${root}/gradlew" -p "${root}" --offline \
        -PmetalPressResourceRecoveryReloadPhase="write:${window}" \
        :forge-create-1.20.1:runServer --console=plain 2>&1 | tee "${write_log}"
    expected_stage="${window}"
    if [[ "${window}" == PARTIAL_ENERGY ]]; then expected_stage=PROCESSING; fi
    if [[ "${window}" == MAINTENANCE_APPROVED || "${window}" == MAINTENANCE_PREPARED ]]; then expected_stage=POWER_VERIFIED; fi
    grep -qF "IE_METAL_PRESS_RESOURCE_RELOAD_WRITE PASS window=${window} stage=${expected_stage}" "${write_log}"
    grep -qF 'resourceBindingSaved=true workers=2 assignments=13' "${write_log}"
    if [[ "${window}" == MAINTENANCE_APPROVED || "${window}" == MAINTENANCE_PREPARED ]]; then
        "${root}/gradlew" -p "${root}" --offline \
            -PmetalPressResourceRecoveryReloadPhase="approve:${window}" \
            :forge-create-1.20.1:runServer --console=plain 2>&1 | tee "${approve_log}"
        if [[ "${window}" == MAINTENANCE_PREPARED ]]; then
            grep -qF 'IE_METAL_PRESS_MAINTENANCE_RELOAD_APPROVE PASS window=MAINTENANCE_PREPARED oldRuntimeDestroyed=true proposalSaved=true approvalSaved=CONSUMED execution=PREPARED thermalSources=1 processWillExit=true' "${approve_log}"
        else
            grep -qF 'IE_METAL_PRESS_MAINTENANCE_RELOAD_APPROVE PASS window=MAINTENANCE_APPROVED oldRuntimeDestroyed=true proposalSaved=true approvalSaved=APPROVED execution=NOT_STARTED thermalSources=0 processWillExit=true' "${approve_log}"
        fi
    fi

    "${root}/gradlew" -p "${root}" --offline \
        -PmetalPressResourceRecoveryReloadPhase="read:${window}" \
        :forge-create-1.20.1:runServer --console=plain 2>&1 | tee "${read_log}"
    grep -qF "IE_METAL_PRESS_RESOURCE_RELOAD_ACCEPTANCE PASS window=${window} stage=COMPLETED" "${read_log}"
    grep -qF 'oldRuntimeDestroyed=true savedDataReloaded=true' "${read_log}"
    grep -qF 'warehouseReobserved=true botsReobserved=2 electricalReobserved=true resumed=true' "${read_log}"
    grep -qF 'duplicateWithdrawals=0 duplicateReturns=0 duplicateEnergySettlements=0 duplicateOutputs=0' "${read_log}"
    grep -qF 'unaccountedItems=0 privateItemsTouched=0 materialLedgerBalanced=true resourceBindingBalanced=true' "${read_log}"
    grep -qF 'energyConsumedFe=2400 output=1 baselineRestored=true report=true' "${read_log}"
    if [[ "${window}" == MAINTENANCE_APPROVED || "${window}" == MAINTENANCE_PREPARED ]]; then
        expected_mutations=2
        if [[ "${window}" == MAINTENANCE_PREPARED ]]; then expected_mutations=1; fi
        grep -qF "IE_METAL_PRESS_MAINTENANCE_RELOAD_READ PASS window=${window} proposalReloaded=true approvalReloaded=true approvalConsumed=true executionVerified=true worldMutations=${expected_mutations} replayPrevented=true orderCompleted=true" "${read_log}"
    fi
done

echo "IE Metal Press resource recovery/reload windows passed: ${log_directory}"
