#!/usr/bin/env bash
# B1 resume gate: a Composite player order interrupted mid-run must be finishable in a
# fresh JVM, from where the site says it stopped, without withdrawing material again.
#
# This is the counterpart to the reload gate, which proves the same interruption fails
# closed and can be refunded. Refunding leaves the player whole but throws away what the
# run consumed; this proves the other outcome, that the run can be carried to a product.
#
# Two phases, two separate server processes, one shared saved world. The run directory
# below is recreated only for the write phase; the read phase must load exactly what the
# write phase left behind.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
run_root="${root}/forge-create-1.20.1/run"
run_directory="${run_root}/composite-player-order-resume"
log_directory="${root}/work/logs"

case "${run_directory}" in
    "${run_root}/"*) ;;
    *) echo "Refusing unsafe composite resume directory: ${run_directory}" >&2; exit 1 ;;
esac

rm -rf "${run_directory}"
mkdir -p "${run_directory}" "${log_directory}"

printf '%s\n' \
    '# Repository-owned isolated Composite resume acceptance.' \
    'eula=true' > "${run_directory}/eula.txt"
printf '%s\n' 'steve-industrial:isolated-execution/v1' \
    > "${run_directory}/.steve-industrial-execution-test"
printf '%s\n' \
    '# Repository-owned isolated Composite resume acceptance.' \
    'online-mode=false' \
    'server-port=0' \
    'level-name=world' \
    'level-seed=steve-industrial-composite-resume-v1' \
    'level-type=minecraft:flat' \
    'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}' \
    'generate-structures=false' \
    'view-distance=8' \
    'simulation-distance=8' > "${run_directory}/server.properties"

timestamp="$(date +%Y%m%d-%H%M%S)"
log="${log_directory}/composite-player-order-resume-${timestamp}.log"

run_phase() {
    echo "=== composite resume phase: $1" | tee -a "${log}"
    set +e
    "${root}/gradlew" -p "${root}" --offline \
        "-PcompositePlayerOrderResumePhase=$1" \
        :forge-create-1.20.1:runServer --console=plain 2>&1 | tee -a "${log}"
    local status=${PIPESTATUS[0]}
    set -e
    if [ "${status}" -ne 0 ]; then
        echo "Composite resume phase $1 exited ${status}" >&2
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
assert_log 'COMPOSITE_RESUME_WRITE PASS' 'mid-run write-phase evidence'

run_phase read
assert_log 'COMPOSITE_RESUME_READ PASS' 'fresh-JVM resume evidence'
assert_log 'withdrawnUnchanged=true' 'no second withdrawal evidence'

# The whole point is that it resumed part-way. Finishing from stage zero would produce
# the same item off the same log line while having charged the player for the first
# stage twice, so the gate asserts the stage it resumed from is not the first.
if grep -qF 'resumedFromStage=0' "${log}"; then
    echo "The resumed run restarted from the first stage; it did not resume" >&2
    exit 1
fi

if grep -qF 'COMPOSITE_RESUME_ACCEPTANCE FAIL' "${log}"; then
    echo "Composite resume acceptance reported a failure in ${log}" >&2
    exit 1
fi

echo "Composite resume acceptance passed (write + resume + finish)"
echo "Log: ${log}"
