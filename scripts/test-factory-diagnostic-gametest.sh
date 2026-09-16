#!/usr/bin/env bash
# Isolated physical proof for the read-only factory diagnostic boundary.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
run_root="${root}/forge-create-1.20.1/run"
run_directory="${run_root}/factory-diagnostic-gametest"
log_directory="${root}/work/logs"

case "${run_directory}" in
    "${run_root}/"*) ;;
    *) echo "Refusing unsafe factory-diagnostic GameTest directory: ${run_directory}" >&2; exit 1 ;;
esac

if ps ax -o command= | grep -F -- "${run_directory}" | grep -Eq '[j]ava|[B]ootstrapLauncher'; then
    echo "Factory-diagnostic GameTest directory is already in use: ${run_directory}" >&2
    exit 1
fi

rm -rf "${run_directory}"
mkdir -p "${run_directory}" "${log_directory}"
printf '%s\n' '# Repository-owned isolated factory-diagnostic GameTest.' 'eula=true' \
    > "${run_directory}/eula.txt"
printf '%s\n' '# Repository-owned isolated factory-diagnostic GameTest.' \
    'online-mode=false' 'server-port=0' 'level-name=world' \
    'level-seed=steve-industrial-factory-diagnostic-v1' 'level-type=minecraft:flat' \
    'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}' \
    'generate-structures=false' 'view-distance=6' 'simulation-distance=6' \
    > "${run_directory}/server.properties"

log="${log_directory}/factory-diagnostic-gametest-$(date +%Y%m%d-%H%M%S).log"
set +e
"${root}/gradlew" -p "${root}" --offline -PfactoryDiagnosticGameTest \
    -PincludeImmersiveEngineeringRuntime=true \
    :forge-create-1.20.1:runGameTestServer --console=plain 2>&1 | tee "${log}"
status=${PIPESTATUS[0]}
set -e

assert_log() {
    if ! grep -qF "$1" "${log}"; then
        echo "Missing $2 in ${log}. Expected: $1" >&2
        exit 1
    fi
}

if [[ ${status} -ne 0 ]]; then
    echo "Factory-diagnostic GameTest failed with exit code ${status}; log: ${log}" >&2
    exit "${status}"
fi

assert_log 'java version 17.' 'Java 17 evidence'
assert_log 'Forge mod loading, version 47.4.10, for MC 1.20.1' 'Forge version evidence'
assert_log 'FACTORY_DIAGNOSTIC_PROJECT PASS commandResult=1 state=FAULTED fault=OUTPUT_CAPACITY_EXHAUSTED available=0 material=HEALTHY reservation=HEALTHY unknown=3 worldMutations=0 containerMutations=0 ledgerMutations=0 automaticRepairAuthorized=false' 'project output-capacity and read-only evidence'
assert_log 'FACTORY_DIAGNOSTIC_IE_LIVE PASS commandResult=1 structure=HEALTHY structureEvidence=PLAN_STRUCTURE_AND_ORIENTATION_MATCH energy=HEALTHY energyEvidence=PLAN_FE_CHARGING_PROGRESS connections=1 outputCapacity=UNKNOWN worldMutations=0 orderTransitions=0 automaticRepairAuthorized=false' 'IE live structure and FE read-only evidence'
assert_log 'FACTORY_MAINTENANCE_IE_POWER PASS proposal=approved-once transport=player-command-three-step freshDiagnosis=true action=RESTORE_POWER exactSourceCells=2 postDiagnosis=HEALTHY approval=CONSUMED replay=REFUSED siteDrift=REFUSED_WITHOUT_CONSUMPTION duplicateMutations=0 privateItemsTouched=0 nearbyScan=false rewiring=false' 'approved one-use IE power maintenance evidence'
assert_log 'FACTORY_DIAGNOSTIC_WAREHOUSE PASS commandResult=1 state=FAULTED fault=ENERGY_INSUFFICIENT evidence=MOLD_INSTALLED_NOT_POWERED worldMutations=0 orderTransitions=0 runtimeMutations=0 automaticRepairAuthorized=false' 'warehouse command read-only evidence'
assert_log 'All 4 required tests passed' 'four diagnostic and maintenance GameTest successes'
assert_log 'ThreadedAnvilChunkStorage: All dimensions are saved' 'clean shutdown'

if find "${run_directory}/crash-reports" -type f -print -quit 2>/dev/null | grep -q .; then
    echo "Factory-diagnostic GameTest created a crash report; log: ${log}" >&2
    exit 1
fi
# The Create+IE runtime probes client-only Minecraft rendering/runtime types during
# dedicated-server discovery. The specific type is class-load-order dependent. Exclude only
# RuntimeDistCleaner's explicit rejection of Minecraft/Blaze3D client namespaces; any other
# ERROR/FATAL, namespace, exception or test failure still fails this gate.
fatal_evidence="$(grep -Ei '\[[^]]+/(ERROR|FATAL)\]|Exception in thread|Preparing crash report|Game test failed|test failed' "${log}" \
    | grep -Ev 'RuntimeDistCleaner/DISTXFORM.*Attempted to load class (net/minecraft/client/|com/mojang/blaze3d/)[A-Za-z0-9_/$]+ for invalid dist DEDICATED_SERVER$' \
    || true)"
if [[ -n "${fatal_evidence}" ]]; then
    printf '%s\n' "${fatal_evidence}" >&2
    echo "Factory-diagnostic GameTest log contains fatal evidence: ${log}" >&2
    exit 1
fi
if ps ax -o command= | grep -F -- "${run_directory}" | grep -Eq '[j]ava|[B]ootstrapLauncher'; then
    echo "Factory-diagnostic GameTest left a Java process: ${run_directory}" >&2
    exit 1
fi

echo 'FACTORY_DIAGNOSTIC_GAMETEST_VERIFIED tests=4 commandResults=3 projectReadOnly=true ieLiveReadOnly=true warehouseReadOnly=true approvedIePowerRestore=true approvalOneUse=true freshRediagnosis=true duplicateMutations=0 privateItemsTouched=0 materialHealthy=true reservationHealthy=true outputCapacityFaultTyped=true structureHealthy=true liveFeHealthy=true unknownPreserved=true energyFaultTyped=true diagnosticWorldMutations=0 containerMutations=0 ledgerMutations=0 orderTransitions=0 runtimeMutations=0 automaticRepair=false crashReports=0 residualProcesses=0'
echo "Isolated run directory: ${run_directory}"
echo "Test log: ${log}"
