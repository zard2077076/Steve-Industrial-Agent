#!/usr/bin/env bash
# macOS/Linux counterpart to Test-BotFleetGameTest.ps1. The suite uses its own
# repository-owned world, so an acceptance client in another game directory may
# remain open while this gate runs.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
run_root="${root}/forge-create-1.20.1/run"
run_directory="${run_root}/bot-fleet-gametest"
log_directory="${root}/work/logs"

case "${run_directory}" in
    "${run_root}/"*) ;;
    *) echo "Refusing unsafe Bot Fleet GameTest directory: ${run_directory}" >&2; exit 1 ;;
esac

if ps ax -o command= | grep -F -- "${run_directory}" | grep -Eq '[j]ava|[B]ootstrapLauncher'; then
    echo "Bot Fleet GameTest directory is already in use: ${run_directory}" >&2
    exit 1
fi

rm -rf "${run_directory}"
mkdir -p "${run_directory}" "${log_directory}"
printf '%s\n' '# Repository-owned isolated Bot Fleet GameTest.' 'eula=true' \
    > "${run_directory}/eula.txt"
printf '%s\n' 'steve-industrial:isolated-bot/v1' \
    > "${run_directory}/.steve-industrial-bot-test"
printf '%s\n' '# Repository-owned isolated Bot Fleet GameTest.' \
    'online-mode=false' 'server-port=0' 'level-name=world' \
    'level-seed=steve-industrial-bot-fleet-v1' 'level-type=minecraft:flat' \
    'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}' \
    'generate-structures=false' 'view-distance=6' 'simulation-distance=6' \
    > "${run_directory}/server.properties"

log="${log_directory}/bot-fleet-gametest-$(date +%Y%m%d-%H%M%S).log"
set +e
"${root}/gradlew" -p "${root}" --offline -PbotFleetGameTest \
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
    echo "Bot Fleet GameTest failed with exit code ${status}; log: ${log}" >&2
    exit "${status}"
fi

assert_log 'java version 17.' 'Java 17 evidence'
assert_log 'Forge mod loading, version 47.4.10, for MC 1.20.1' 'Forge version evidence'
assert_log 'BOT_PHYSICAL_ACTION_CHAIN PASS spawned=true identityStable=true navigate=true fetch=2 carry=true transport=true place=true verify=true safeMachineFace=true removeSessionOwned=true returnLeftover=1 idleReload=true reloadDriftRefused=true duplicateWithdrawal=false playerInventoryReads=0 privateContainerReads=0 existingEntity=true smoothAdjacentMovement=true teleport=false evidence=7' 'physical Bot action chain'
assert_log 'BOT_POLICY_GAMETEST PASS adjacentMovement=true repath=true cancel=true idleAfterCancel=true outOfRegionRefused=true authorityRevoked=true worldMutationAfterRevoke=false attackActions=0 unknownRightClicks=0 teleportBypass=false' 'Bot policy failure matrix'
assert_log 'BOT_FLEET_PHYSICAL_DAG PASS workers=2 maxActive=2 parallelAssignments=true parallelMovement=true parallelFetch=true parallelTransport=true sequentialDependencies=true workReservations=2 placements=2 physicalSources=2 completedTasks=6 existingEntity=true smoothAdjacentMovement=true teleport=false cleanup=true terminalFailures=0' 'two-Bot physical DAG'
# 3 and 5 workers share one kernel; completedTasks scales at three per worker and
# leases must equal the worker count, which is what proves no second scheduler.
for workers in 3 5; do
    assert_log "BOT_FLEET_SCALED_${workers} PASS workers=${workers} maxActive=${workers} existingEntity=true sharedKernel=GraphNeutralFleetCoordinator parallelAssignments=true allMoved=true botOverlap=false physicalSources=${workers} placements=${workers} completedTasks=$((workers * 3)) leases=${workers} teleport=false playerInventoryReads=0 privateContainerReads=0 cleanup=true terminalFailures=0" "${workers}-Bot shared fleet physical DAG"
done
assert_log 'All 5 required tests passed' 'five Bot GameTest successes'
assert_log 'ThreadedAnvilChunkStorage: All dimensions are saved' 'clean shutdown'

if find "${run_directory}/crash-reports" -type f -print -quit 2>/dev/null | grep -q .; then
    echo "Bot Fleet GameTest created a crash report; log: ${log}" >&2
    exit 1
fi
if grep -Eqi '\[[^]]+/(ERROR|FATAL)\]|Exception in thread|Preparing crash report|Bot task failed|Game test failed|test failed' "${log}"; then
    echo "Bot Fleet GameTest log contains fatal evidence: ${log}" >&2
    exit 1
fi
if ps ax -o command= | grep -F -- "${run_directory}" | grep -Eq '[j]ava|[B]ootstrapLauncher'; then
    echo "Bot Fleet GameTest left a Java process: ${run_directory}" >&2
    exit 1
fi

echo 'BOT_FLEET_GAMETEST_VERIFIED tests=5 existingConstructionBotEntity=true workers2=true workers3=true workers5=true sharedKernel=true adjacentPath=true smoothMovement=true teleport=false repath=true fetch=true carry=true parallelTransport=true place=true verify=true botOverlap=false safeInteraction=true cleanup=true return=true idleReload=true cancel=true regionGuard=true authorityRevocation=true playerInventoryReads=0 privateContainerReads=0 crashReports=0 residualProcesses=0'
echo "Isolated run directory: ${run_directory}"
echo "Test log: ${log}"
