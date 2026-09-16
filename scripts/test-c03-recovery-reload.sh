#!/usr/bin/env bash
# macOS/Linux counterpart to Test-C03RecoveryReload.ps1. Two real server
# processes: the first checkpoints at a safe BUILD boundary, the second reloads
# the world, rescans it, refuses a stale session, and resumes without repeating
# any placement, input or output.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
run_root="${root}/forge-create-1.20.1/run"
run_directory="${run_root}/c03-recovery-reload-acceptance"
log_directory="${root}/work/logs"

case "${run_directory}" in
    "${run_root}/"*) ;;
    *) echo "Refusing to use an unsafe C03 recovery directory: ${run_directory}" >&2; exit 1 ;;
esac

if ps ax -o command= | grep -F -- "${run_directory}" | grep -Eq '[j]ava|[B]ootstrapLauncher'; then
    echo "C03 recovery directory is already in use: ${run_directory}" >&2
    exit 1
fi

rm -rf "${run_directory}"
mkdir -p "${run_directory}" "${log_directory}"
printf '%s\n' 'steve-industrial:isolated-execution/v1' \
    > "${run_directory}/.steve-industrial-execution-test"
printf '%s\n' '# Generated for the isolated Steve Industrial Agent C03 recovery acceptance.' \
    'eula=true' > "${run_directory}/eula.txt"
printf '%s\n' '# Generated for an isolated, non-public C03 save/reload acceptance server.' \
    'online-mode=false' 'server-port=0' 'level-name=world' \
    'level-seed=steve-industrial-c03-recovery-v1' 'generate-structures=false' \
    'view-distance=3' 'simulation-distance=3' > "${run_directory}/server.properties"

stamp="$(date +%Y%m%d-%H%M%S)"
write_log="${log_directory}/c03-recovery-reload-write-${stamp}.log"
read_log="${log_directory}/c03-recovery-reload-read-${stamp}.log"

assert_log() {
    if ! grep -qF "$2" "$1"; then
        echo "Missing $3 in $1. Expected: $2" >&2
        exit 1
    fi
}

"${root}/gradlew" -p "${root}" --offline -Pc03RecoveryReloadAcceptancePhase=write \
    :forge-create-1.20.1:runServer --console=plain 2>&1 | tee "${write_log}"

saved_data="${run_directory}/world/data/steve_industrial_c03_recovery_acceptance.dat"
if [[ ! -f "${saved_data}" ]]; then
    echo "C03 write phase did not create SavedData: ${saved_data}" >&2
    exit 1
fi

assert_log "${write_log}" 'java version 17.' 'Java 17 launch evidence'
assert_log "${write_log}" 'Forge mod loading, version 47.4.10, for MC 1.20.1' 'Forge/Minecraft evidence'
assert_log "${write_log}" 'C03_RECOVERY_RELOAD_WRITE PASS' 'safe BUILD-complete checkpoint'
assert_log "${write_log}" 'currentStep=steve_industrial:c03/step/power stepState=READY completedSteps=1 placements=17 blockChanges=17 resourceChanges=0' 'saved handler and step cursor'
assert_log "${write_log}" 'ThreadedAnvilChunkStorage: All dimensions are saved' 'write-phase clean save'
assert_log "${write_log}" 'BUILD SUCCESSFUL' 'write-phase Gradle success'

"${root}/gradlew" -p "${root}" --offline -Pc03RecoveryReloadAcceptancePhase=read \
    :forge-create-1.20.1:runServer --console=plain 2>&1 | tee "${read_log}"

# The second process must not trust the checkpoint: it rediscovers, rescans,
# refuses a stale session, and only then resumes.
assert_log "${read_log}" 'C03_RECOVERY_RELOAD_DISCOVERED PASS' 'post-reload discovery and exact rescan'
assert_log "${read_log}" 'verifiedPositions=17 rawTransientRejected=true normalizedKineticSnapshots=4 blindResume=false' 'bounded Create 6.0.6-normalized rescan-before-resume evidence'
assert_log "${read_log}" 'C03_RECOVERY_RELOAD_STALE PASS code=steve_industrial:recovery/stale_session reason=WORLD_STATE_CHANGED' 'typed stale-session result'
assert_log "${read_log}" 'C03_RECOVERY_RELOAD_RESUME_READY PASS' 'production handler resume evidence'
assert_log "${read_log}" 'placementCursor=17 journalEntries=17 repeatedPlacements=0 repeatedInputs=0' 'no duplicate work before resume'
assert_log "${read_log}" 'C03_RECOVERY_RELOAD_PROCESS PASS' 'real resumed Create recipe evidence'
assert_log "${read_log}" 'consumed=1 output=minecraft:gravel observed=1 blockChanges=17 inputChanges=1 processChanges=1' 'exact input/output/journal cardinality'
assert_log "${read_log}" 'C03_RECOVERY_RELOAD_ACCEPTANCE PASS' 'final C-03 recovery result'
assert_log "${read_log}" 'noDuplicatePlacement=true noDuplicateInput=true noDuplicateOutput=true' 'no-duplication result'
assert_log "${read_log}" 'ThreadedAnvilChunkStorage: All dimensions are saved' 'read-phase clean save'
assert_log "${read_log}" 'BUILD SUCCESSFUL' 'read-phase Gradle success'

if find "${run_directory}/crash-reports" -type f -print -quit 2>/dev/null | grep -q .; then
    echo "C-03 recovery created a crash report; logs: ${write_log} ${read_log}" >&2
    exit 1
fi
for log in "${write_log}" "${read_log}"; do
    if grep -Eqi '\[[^]]+/(ERROR|FATAL)\]|Exception in thread|C03_RECOVERY_RELOAD_ACCEPTANCE FAIL|Preparing crash report|BUILD FAILED' "${log}"; then
        echo "C-03 recovery log contains error evidence: ${log}" >&2
        exit 1
    fi
done
if ps ax -o command= | grep -F -- "${run_directory}" | grep -Eq '[j]ava|[B]ootstrapLauncher'; then
    echo "C-03 recovery left a Java process: ${run_directory}" >&2
    exit 1
fi

echo 'C03_RECOVERY_RELOAD_ACCEPTANCE_VERIFIED minecraft=1.20.1 forge=47.4.10 persistedCheckpoint=true processRestarts=2 oldSessionDiscovered=true rescanRequired=true staleRejected=true noBlindResume=true noDuplicatePlacement=true noDuplicateInput=true noDuplicateOutput=true crashReports=0'
echo "SavedData file: ${saved_data}"
echo "Isolated run directory: ${run_directory}"
echo "Write log: ${write_log}"
echo "Read log: ${read_log}"
