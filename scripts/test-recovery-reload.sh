#!/usr/bin/env bash
# macOS/Linux counterpart to Test-RecoveryReload.ps1 (G-11). Two real server
# processes: the first persists a checkpoint, the second reloads the world and
# must rediscover, rescan and resume it without ever resuming blind.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
run_root="${root}/forge-create-1.20.1/run"
run_directory="${run_root}/recovery-reload-acceptance"
log_directory="${root}/work/logs"

case "${run_directory}" in
    "${run_root}/"*) ;;
    *) echo "Refusing unsafe recovery reload directory: ${run_directory}" >&2; exit 1 ;;
esac

if ps ax -o command= | grep -F -- "${run_directory}" | grep -Eq '[j]ava|[B]ootstrapLauncher'; then
    echo "Recovery reload directory is already in use: ${run_directory}" >&2
    exit 1
fi

rm -rf "${run_directory}"
mkdir -p "${run_directory}" "${log_directory}"
printf '%s\n' '# Generated for the isolated Steve Industrial Agent G-11 acceptance.' \
    'eula=true' > "${run_directory}/eula.txt"
printf '%s\n' '# Generated for an isolated, non-public G-11 save/reload acceptance server.' \
    'online-mode=false' 'server-port=0' 'level-name=world' \
    'level-seed=steve-industrial-g11-v1' 'generate-structures=false' \
    'view-distance=3' 'simulation-distance=3' > "${run_directory}/server.properties"

stamp="$(date +%Y%m%d-%H%M%S)"
write_log="${log_directory}/recovery-reload-acceptance-write-${stamp}.log"
read_log="${log_directory}/recovery-reload-acceptance-read-${stamp}.log"

assert_log() {
    if ! grep -qF "$2" "$1"; then
        echo "Missing $3 in $1. Expected: $2" >&2
        exit 1
    fi
}

"${root}/gradlew" -p "${root}" --offline -PrecoveryReloadAcceptancePhase=write \
    :forge-create-1.20.1:runServer --console=plain 2>&1 | tee "${write_log}"

saved_data="${run_directory}/world/data/steve_industrial_recovery_acceptance.dat"
if [[ ! -f "${saved_data}" ]]; then
    echo "G-11 write phase did not create the expected SavedData file: ${saved_data}" >&2
    exit 1
fi

assert_log "${write_log}" 'java version 17.' 'Java 17 launch evidence'
assert_log "${write_log}" 'Forge mod loading, version 47.4.10, for MC 1.20.1' 'Forge version evidence'
assert_log "${write_log}" 'RECOVERY_RELOAD_WRITE PASS session=steve_industrial:acceptance/recovery_reload_session' 'persisted write-phase checkpoint'
assert_log "${write_log}" 'currentStep=steve_industrial:acceptance/recovery_resume completedSteps=1 modifiedPositions=1' 'saved session progress and position evidence'
assert_log "${write_log}" 'ThreadedAnvilChunkStorage: All dimensions are saved' 'write-phase clean save'
assert_log "${write_log}" 'BUILD SUCCESSFUL' 'write-phase Gradle success'

"${root}/gradlew" -p "${root}" --offline -PrecoveryReloadAcceptancePhase=read \
    :forge-create-1.20.1:runServer --console=plain 2>&1 | tee "${read_log}"

# The point of this gate is that the second process does not trust the checkpoint:
# it rediscovers the session, rescans the world, and refuses a stale one.
assert_log "${read_log}" 'RECOVERY_RELOAD_DISCOVERED PASS session=steve_industrial:acceptance/recovery_reload_session' 'old-session discovery after process reload'
assert_log "${read_log}" 'completedSteps=1 modifiedPositions=1 blindResume=false' 'rescan-before-resume evidence'
assert_log "${read_log}" 'RECOVERY_RELOAD_RESUME PASS session=steve_industrial:acceptance/recovery_reload_session' 'consistent-state resume evidence'
assert_log "${read_log}" 'verifiedPositions=1 actionInvocations=1 worldMutation=false' 'bounded resumed execution evidence'
assert_log "${read_log}" 'RECOVERY_RELOAD_STALE PASS code=steve_industrial:recovery/stale_session reason=WORLD_STATE_CHANGED' 'typed stale-session rejection evidence'
assert_log "${read_log}" 'noMutation=true' 'stale reconciliation no-mutation evidence'
assert_log "${read_log}" 'RECOVERY_RELOAD_ACCEPTANCE PASS session=steve_industrial:acceptance/recovery_reload_session savedData=true worldReloaded=true rescanRequired=true consistentResume=true staleRejected=true noBlindResume=true' 'final G-11 acceptance marker'
assert_log "${read_log}" 'ThreadedAnvilChunkStorage: All dimensions are saved' 'read-phase clean save'
assert_log "${read_log}" 'BUILD SUCCESSFUL' 'read-phase Gradle success'

if find "${run_directory}/crash-reports" -type f -print -quit 2>/dev/null | grep -q .; then
    echo "G-11 acceptance created a crash report; logs: ${write_log} ${read_log}" >&2
    exit 1
fi
for log in "${write_log}" "${read_log}"; do
    if grep -Eqi '\[[^]]+/(ERROR|FATAL)\]|Exception in thread|RECOVERY_RELOAD_ACCEPTANCE FAIL|Preparing crash report|BUILD FAILED' "${log}"; then
        echo "G-11 acceptance log contains fatal evidence: ${log}" >&2
        exit 1
    fi
done
if ps ax -o command= | grep -F -- "${run_directory}" | grep -Eq '[j]ava|[B]ootstrapLauncher'; then
    echo "G-11 acceptance left a Java process: ${run_directory}" >&2
    exit 1
fi

echo 'RECOVERY_RELOAD_ACCEPTANCE_VERIFIED minecraft=1.20.1 forge=47.4.10 persistedCheckpoint=true processRestarts=2 oldSessionDiscovered=true rescanRequired=true consistentResume=true staleRejected=true noBlindResume=true noMutationOnStale=true crashReports=0'
echo "SavedData file: ${saved_data}"
echo "Isolated run directory: ${run_directory}"
echo "Write log: ${write_log}"
echo "Read log: ${read_log}"
