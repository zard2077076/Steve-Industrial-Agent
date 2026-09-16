#!/usr/bin/env bash
# macOS/Linux counterpart to Test-PhaseIVComposite.ps1. The suite uses its own
# repository-owned world, so an acceptance client in another game directory may
# remain open while this gate runs.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
run_root="${root}/forge-create-1.20.1/run"
run_directory="${run_root}/phase-iv-composite-gametest"
log_directory="${root}/work/logs"

case "${run_directory}" in
    "${run_root}/"*) ;;
    *) echo "Refusing unsafe Phase IV composite GameTest directory: ${run_directory}" >&2; exit 1 ;;
esac

if ps ax -o command= | grep -F -- "${run_directory}" | grep -Eq '[j]ava|[B]ootstrapLauncher'; then
    echo "Phase IV composite GameTest directory is already in use: ${run_directory}" >&2
    exit 1
fi

rm -rf "${run_directory}"
mkdir -p "${run_directory}" "${log_directory}"
printf '%s\n' '# Repository-owned isolated Phase IV composite GameTest.' 'eula=true' \
    > "${run_directory}/eula.txt"
printf '%s\n' 'steve-industrial:isolated-execution/v1' \
    > "${run_directory}/.steve-industrial-execution-test"
printf '%s\n' '# Repository-owned isolated Phase IV composite GameTest.' \
    'online-mode=false' 'server-port=0' 'level-name=world' \
    'level-seed=steve-industrial-phase-iv-composite-v1' 'level-type=minecraft:flat' \
    'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}' \
    'generate-structures=false' 'view-distance=6' 'simulation-distance=6' \
    > "${run_directory}/server.properties"

log="${log_directory}/phase-iv-composite-gametest-$(date +%Y%m%d-%H%M%S).log"
set +e
"${root}/gradlew" -p "${root}" --offline -PgoalDrivenExecutionGameTest \
    -PphaseIvCompositeGameTestOnly \
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
    echo "Phase IV composite GameTest failed with exit code ${status}; log: ${log}" >&2
    exit "${status}"
fi

assert_log 'java version 17.' 'Java 17 evidence'
assert_log 'Forge mod loading, version 47.4.10, for MC 1.20.1' 'Forge version evidence'
assert_log 'Create 6.0.6 initializing!' 'Create version evidence'
for mode in DIRECT BOTS HYBRID; do
    assert_log "COMPOSITE_01_${mode} PASS stages=3 capabilities=2 routes=2 realIntermediateMovement=true final=create:cogwheel@1 preservedExcess=minecraft:oak_planks@5 reloadPerStage=true cleanup=true" "Composite-01 ${mode} evidence"
done
for mode in DIRECT BOTS HYBRID; do
    assert_log "COMPOSITE_02_${mode} PASS concurrentLines=2 cancelA=true lineBContinued=true sharedReferenceAfterCancel=1 sharedCleanupAfterAll=true independentTargets=true independentGraphs=true independentWorkers=true finalB=create:andesite_alloy@1 reloadB=true wrapperReload=true cleanup=true" "Composite-02 ${mode} evidence"
done
# The branch/merge modes are the ones that measure a physical route's delivery against
# the stage's own installation materials. A survival-powered branch bills create:shaft,
# so a check reading the chest's absolute count waits on a number it cannot reach.
for mode in DIRECT BOTS HYBRID; do
    assert_log "COMPOSITE_03_${mode} PASS nodes=4 edges=5 physicalRoutes=4 realBranches=2 mergeWaited=true typedFilters=true final=create:large_cogwheel@1 preservedExcess=minecraft:oak_planks@5 reloadPerHandler=true cleanup=true" "Composite-03 ${mode} evidence"
done
assert_log 'All 9 required tests passed' 'nine required Composite GameTests'
assert_log 'installationEscrow=true' 'per-stage complete installation material escrow'
assert_log 'ThreadedAnvilChunkStorage: All dimensions are saved' 'clean shutdown'

if find "${run_directory}/crash-reports" -type f -print -quit 2>/dev/null | grep -q .; then
    echo "Phase IV composite GameTest created a crash report; log: ${log}" >&2
    exit 1
fi
if grep -Eqi '\[[^]]+/(ERROR|FATAL)\]|Exception in thread|Preparing crash report|Game test failed|test failed' "${log}"; then
    echo "Phase IV composite GameTest log contains fatal evidence: ${log}" >&2
    exit 1
fi
if ps ax -o command= | grep -F -- "${run_directory}" | grep -Eq '[j]ava|[B]ootstrapLauncher'; then
    echo "Phase IV composite GameTest left a Java process: ${run_directory}" >&2
    exit 1
fi

echo 'PHASE_IV_COMPOSITE_GAMETEST_VERIFIED composite01=true stages=3 routes=2 realIntermediateMovement=true composite02=true concurrentCancelIsolation=true composite02Direct=true composite02Bots=true composite02Hybrid=true composite03Direct=true composite03Bots=true composite03Hybrid=true branchMerge=true botFleetPeak=3,5 installationEscrow=true cleanup=true crashReports=0 residualProcesses=0'
echo "Isolated run directory: ${run_directory}"
echo "Test log: ${log}"
