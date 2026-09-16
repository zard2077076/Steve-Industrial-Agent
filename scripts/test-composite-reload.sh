#!/usr/bin/env bash
# IPO-02 reload gate: a Composite player order that is interrupted mid-run must fail
# closed in a fresh JVM, not resume and not double-charge the player.
#
# Two phases, two separate server processes, one shared saved world. The run
# directory below is recreated only for the write phase; the read phase must load
# exactly what the write phase left behind.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
run_root="${root}/forge-create-1.20.1/run"
run_directory="${run_root}/composite-player-order-reload"
log_directory="${root}/work/logs"

case "${run_directory}" in
    "${run_root}/"*) ;;
    *) echo "Refusing unsafe composite reload directory: ${run_directory}" >&2; exit 1 ;;
esac

rm -rf "${run_directory}"
mkdir -p "${run_directory}" "${log_directory}"

printf '%s\n' \
    '# Repository-owned isolated Composite reload acceptance.' \
    'eula=true' > "${run_directory}/eula.txt"
printf '%s\n' 'steve-industrial:isolated-execution/v1' \
    > "${run_directory}/.steve-industrial-execution-test"
printf '%s\n' \
    '# Repository-owned isolated Composite reload acceptance.' \
    'online-mode=false' \
    'server-port=0' \
    'level-name=world' \
    'level-seed=steve-industrial-composite-reload-v1' \
    'level-type=minecraft:flat' \
    'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}' \
    'generate-structures=false' \
    'view-distance=8' \
    'simulation-distance=8' > "${run_directory}/server.properties"

timestamp="$(date +%Y%m%d-%H%M%S)"
log="${log_directory}/composite-player-order-reload-${timestamp}.log"

run_phase() {
    echo "=== composite reload phase: $1" | tee -a "${log}"
    set +e
    "${root}/gradlew" -p "${root}" --offline \
        "-PcompositePlayerOrderReloadPhase=$1" \
        :forge-create-1.20.1:runServer --console=plain 2>&1 | tee -a "${log}"
    local status=${PIPESTATUS[0]}
    set -e
    if [ "${status}" -ne 0 ]; then
        echo "Composite reload phase $1 exited ${status}" >&2
        exit "${status}"
    fi
}

assert_log() {
    if ! grep -qF "$1" "${log}"; then
        echo "Missing $2 in ${log}. Expected: $1" >&2
        exit 1
    fi
}

run_phase write
assert_log 'COMPOSITE_RELOAD_WRITE PASS' 'mid-run write-phase evidence'

run_phase read
assert_log 'COMPOSITE_RELOAD_READ PASS' 'fresh-JVM read-phase evidence'
assert_log 'resumed=false' 'no-resume evidence'
assert_log 'duplicateWithdrawal=false' 'no duplicate withdrawal evidence'
assert_log 'chestUnchanged=true' 'unchanged player chest evidence'
assert_log 'COMPOSITE_RELOAD_CANCEL PASS' 'restart-cancel material recovery evidence'

if grep -qF 'COMPOSITE_RELOAD_ACCEPTANCE FAIL' "${log}"; then
    echo "Composite reload acceptance reported a failure in ${log}" >&2
    exit 1
fi

echo "Composite reload acceptance passed (write + read)"
echo "Log: ${log}"
