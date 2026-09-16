#!/usr/bin/env bash
# IPO-02 gate: one reviewed Composite order started the way a player starts it.
#
# Runs on a disposable repository-owned dedicated server. It never touches a
# launcher instance, mod JAR or save; the run directory below is recreated on
# every invocation and lives inside this repository.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
run_root="${root}/forge-create-1.20.1/run"
run_directory="${run_root}/composite-player-order-acceptance"
log_directory="${root}/work/logs"
modes="${1:-direct,bots,hybrid}"
# acacia_planks is not a reviewed graph: it is derived from the live recipe registry
# at order time, and it is the only gate covering the derived path, a two-stage chain,
# and a recipe that yields more than one per batch. Leaving it out of the default set
# is how that path gets broken without anyone noticing.
# Both derived shapes, because the 111 buildable products are one shape repeated: the
# survey reports {[cutting,cutting,cutting]=100, [cutting,cutting]=11}. acacia_planks is
# the two-stage shape and bamboo_mosaic the three-stage one, so between them every
# derivable composite in the live registry has a physically verified representative.
graphs="${2:-01,03,minecraft:acacia_planks,minecraft:bamboo_mosaic}"

case "${run_directory}" in
    "${run_root}/"*) ;;
    *) echo "Refusing unsafe composite run directory: ${run_directory}" >&2; exit 1 ;;
esac

rm -rf "${run_directory}"
mkdir -p "${run_directory}" "${log_directory}"

printf '%s\n' \
    '# Repository-owned isolated Composite player-order acceptance.' \
    'eula=true' > "${run_directory}/eula.txt"
printf '%s\n' 'steve-industrial:isolated-execution/v1' \
    > "${run_directory}/.steve-industrial-execution-test"
printf '%s\n' \
    '# Repository-owned isolated Composite player-order acceptance.' \
    'online-mode=false' \
    'server-port=0' \
    'level-name=world' \
    'level-seed=steve-industrial-composite-player-order-v1' \
    'level-type=minecraft:flat' \
    'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}' \
    'generate-structures=false' \
    'view-distance=8' \
    'simulation-distance=8' > "${run_directory}/server.properties"

log="${log_directory}/composite-player-order-$(date +%Y%m%d-%H%M%S).log"
set +e
# Dependencies are already materialized; going online here only exposes the run
# to a proxy outage that surfaces as hundreds of bogus "package does not exist"
# compile errors having nothing to do with the gate.
"${root}/gradlew" -p "${root}" --offline \
    -PcompositePlayerOrderAcceptance \
    "-PcompositePlayerOrderModes=${modes}" \
    "-PcompositePlayerOrderGraphs=${graphs}" \
    :forge-create-1.20.1:runServer --console=plain 2>&1 | tee "${log}"
status=${PIPESTATUS[0]}
set -e

assert_log() {
    if ! grep -qF "$1" "${log}"; then
        echo "Missing $2 in ${log}. Expected: $1" >&2
        exit 1
    fi
}

assert_log 'COMPOSITE_REFUSED_START PASS' 'refused-start material return evidence'
assert_log 'COMPOSITE_PLAYER_ORDER PASS' 'per-mode composite player order evidence'
assert_log 'COMPOSITE_PLAYER_ORDER_SUITE PASS' 'composite player order suite evidence'
assert_log 'freeBuild=false' 'no-free-build evidence'
assert_log 'reservedFromPlayerChest=true' 'player reservation evidence'
assert_log 'materialLedgerBalanced=true' 'balanced ledger evidence'

if grep -qF 'composite player-order acceptance failed' "${log}"; then
    echo "Composite player-order acceptance reported a failure in ${log}" >&2
    exit 1
fi

echo "Composite player-order acceptance passed for graphs ${graphs} in modes ${modes}"
echo "Log: ${log}"
exit "${status}"
