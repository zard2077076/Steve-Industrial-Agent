#!/usr/bin/env bash
# macOS/Linux counterpart to Test-PhaseIVRecovery.ps1. The suite uses its own
# repository-owned world, so an acceptance client in another game directory may
# remain open while this gate runs.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
run_root="${root}/forge-create-1.20.1/run"
run_directory="${run_root}/phase-iv-recovery-gametest"
log_directory="${root}/work/logs"

case "${run_directory}" in
    "${run_root}/"*) ;;
    *) echo "Refusing unsafe Phase IV recovery GameTest directory: ${run_directory}" >&2; exit 1 ;;
esac

if ps ax -o command= | grep -F -- "${run_directory}" | grep -Eq '[j]ava|[B]ootstrapLauncher'; then
    echo "Phase IV recovery GameTest directory is already in use: ${run_directory}" >&2
    exit 1
fi

rm -rf "${run_directory}"
mkdir -p "${run_directory}" "${log_directory}"
printf '%s\n' '# Repository-owned isolated Phase IV recovery GameTest.' 'eula=true' \
    > "${run_directory}/eula.txt"
printf '%s\n' 'steve-industrial:isolated-execution/v1' \
    > "${run_directory}/.steve-industrial-execution-test"
printf '%s\n' '# Repository-owned isolated Phase IV recovery GameTest.' \
    'online-mode=false' 'server-port=0' 'level-name=world' \
    'level-seed=steve-industrial-phase-iv-recovery-v1' 'level-type=minecraft:flat' \
    'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}' \
    'generate-structures=false' 'view-distance=6' 'simulation-distance=6' \
    > "${run_directory}/server.properties"

log="${log_directory}/phase-iv-recovery-gametest-$(date +%Y%m%d-%H%M%S).log"
set +e
"${root}/gradlew" -p "${root}" --offline -PgoalDrivenExecutionGameTest \
    -PphaseIvRecoveryGameTestOnly \
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
    echo "Phase IV recovery GameTest failed with exit code ${status}; log: ${log}" >&2
    exit "${status}"
fi

assert_log 'java version 17.' 'Java 17 evidence'
assert_log 'Forge mod loading, version 47.4.10, for MC 1.20.1' 'Forge version evidence'
assert_log 'Create 6.0.6 initializing!' 'Create version evidence'
# Two assertions per capability. The zero counters are the point of the first:
# a resume that replayed a placement, an input or an output would still finish,
# and only these three counters tell that apart from an exact one.
for capability in C07 C06 C09 C08 C10; do
    assert_log "${capability}_EXACT_RECOVERY PASS boundary=BUILD_MID exactRescan=true checkpointCodec=true repeatedPlacements=0 repeatedInputs=0 repeatedOutput=0" "${capability} exact recovery"
    assert_log "${capability}_CANCEL_MATRIX PASS direct=EXECUTION_CANCELLED bots=EXECUTION_CANCELLED hybrid=EXECUTION_CANCELLED exactInputReturn=true installationEscrowRestored=true deliveryEmpty=true workersRemoved=true repeatedTickRefused=true privateContainerReads=0 playerInventoryReads=0" "${capability} three-mode cancellation"
done
assert_log 'All 10 required tests passed' 'ten required resilience GameTests'
assert_log 'ThreadedAnvilChunkStorage: All dimensions are saved' 'clean shutdown'

if find "${run_directory}/crash-reports" -type f -print -quit 2>/dev/null | grep -q .; then
    echo "Phase IV recovery GameTest created a crash report; log: ${log}" >&2
    exit 1
fi
if grep -Eqi '\[[^]]+/(ERROR|FATAL)\]|Exception in thread|Preparing crash report|Game test failed|test failed' "${log}"; then
    echo "Phase IV recovery GameTest log contains fatal evidence: ${log}" >&2
    exit 1
fi
if ps ax -o command= | grep -F -- "${run_directory}" | grep -Eq '[j]ava|[B]ootstrapLauncher'; then
    echo "Phase IV recovery GameTest left a Java process: ${run_directory}" >&2
    exit 1
fi

echo 'PHASE_IV_RECOVERY_GAMETEST_VERIFIED capabilities=C07,C06,C09,C08,C10 boundary=BUILD_MID exactRescan=true checkpointCodec=true repeatedPlacements=0 repeatedInputs=0 repeatedOutput=0 directCancel=true botsCancel=true hybridCancel=true exactInputReturn=true crashReports=0 residualProcesses=0'
echo "Isolated run directory: ${run_directory}"
echo "Test log: ${log}"
