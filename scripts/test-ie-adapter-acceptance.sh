#!/usr/bin/env bash
# Immersive Engineering physical acceptance: real multiblock formation, a real
# thermoelectric generator feeding real LV connectors, and a real Metal Press run.
#
# This had no runner at all — the switches existed in build.gradle and could only be
# reached by typing gradle by hand, which is how a gate stops being run without anyone
# deciding to stop running it.
#
# IE is compileOnly and only joins the runtime classpath when this property is set, so
# this is the one environment where Forge Energy blocks actually exist.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
run_root="${root}/forge-create-1.20.1/run"
run_directory="${run_root}/ie-v1020-adapter-acceptance"
log_directory="${root}/work/logs"

case "${run_directory}" in
    "${run_root}/"*) ;;
    *) echo "Refusing unsafe IE adapter run directory: ${run_directory}" >&2; exit 1 ;;
esac

rm -rf "${run_directory}"
mkdir -p "${run_directory}" "${log_directory}"

printf '%s\n' '# Repository-owned isolated IE adapter acceptance.' 'eula=true' \
    > "${run_directory}/eula.txt"
printf '%s\n' 'steve-industrial:isolated-execution/v1' \
    > "${run_directory}/.steve-industrial-execution-test"
printf '%s\n' '# Repository-owned isolated IE adapter acceptance.' \
    'online-mode=false' 'server-port=0' 'level-name=world' 'level-type=minecraft:flat' \
    'generate-structures=false' 'view-distance=8' 'simulation-distance=8' \
    > "${run_directory}/server.properties"

log="${log_directory}/ie-physical-acceptance-$(date +%Y%m%d-%H%M%S).log"
set +e
"${root}/gradlew" -p "${root}" --offline -PieAdapterAcceptance \
    :forge-create-1.20.1:runServer --console=plain 2>&1 | tee "${log}"
status=${PIPESTATUS[0]}
set -e

assert_log() {
    if ! grep -qF "$1" "${log}"; then
        echo "Missing $2 in ${log}. Expected: $1" >&2
        exit 1
    fi
}

# The world-to-graph-to-verifier path for FE. It has existed all along and had no
# runner, so nothing ever ran it: a real LV wire is built, captured as an
# ElectricalNetworkGraph, and put through the deterministic verifier.
assert_log 'IE_V1020_ADAPTER_ACCEPTANCE PASS' 'IE adapter acceptance'
assert_log 'adapterWorldMutations=0' 'the adapter mutated nothing'
assert_log 'fixtureSetupRestored=true' 'the fixture restored its own setup'

if [ "${status}" -ne 0 ]; then
    echo "IE adapter acceptance exited ${status}" >&2
    exit "${status}"
fi

echo "IE adapter acceptance passed"
echo "Log: ${log}"
