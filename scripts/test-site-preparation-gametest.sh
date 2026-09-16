#!/usr/bin/env bash
# macOS/Linux counterpart to Test-SitePreparationGameTest.ps1. The suite uses
# its own repository-owned world, so an acceptance client in another game
# directory may remain open while this gate runs.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
run_root="${root}/forge-create-1.20.1/run"
run_directory="${run_root}/site-preparation-gametest"
log_directory="${root}/work/logs"

case "${run_directory}" in
    "${run_root}/"*) ;;
    *) echo "Refusing unsafe site-preparation GameTest directory: ${run_directory}" >&2; exit 1 ;;
esac

if ps ax -o command= | grep -F -- "${run_directory}" | grep -Eq '[j]ava|[B]ootstrapLauncher'; then
    echo "Site-preparation GameTest directory is already in use: ${run_directory}" >&2
    exit 1
fi

rm -rf "${run_directory}"
mkdir -p "${run_directory}" "${log_directory}"
printf '%s\n' '# Repository-owned isolated site-preparation GameTest.' 'eula=true' \
    > "${run_directory}/eula.txt"
printf '%s\n' 'steve-industrial:isolated-site-preparation/v1' \
    > "${run_directory}/.steve-industrial-site-preparation-test"
printf '%s\n' 'steve-industrial:isolated-execution/v1' \
    > "${run_directory}/.steve-industrial-execution-test"
printf '%s\n' '# Repository-owned isolated site-preparation GameTest.' \
    'online-mode=false' 'server-port=0' 'level-name=world' \
    'level-seed=steve-industrial-site-preparation-v1' 'level-type=minecraft:flat' \
    'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}' \
    'generate-structures=false' 'view-distance=6' 'simulation-distance=6' \
    > "${run_directory}/server.properties"

log="${log_directory}/site-preparation-gametest-$(date +%Y%m%d-%H%M%S).log"
set +e
"${root}/gradlew" -p "${root}" --offline -PsitePreparationGameTest \
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
    echo "Site-preparation GameTest failed with exit code ${status}; log: ${log}" >&2
    exit "${status}"
fi

assert_log 'java version 17.' 'Java 17 evidence'
assert_log 'Forge mod loading, version 47.4.10, for MC 1.20.1' 'Forge version evidence'
assert_log 'SITE_PREP_BOT_CLEARING PASS exactMutations=1 realDrop=true salvageDelivered=true unknownBlocksRemoved=0 protectedBlocksRemoved=0 containerOpened=0 playerInventoryAccess=0 regionOutsideMutations=0 formalWorldAccess=0 botOverlap=false teleportFallback=false' 'physical Bot clearing and salvage'
assert_log 'SITE_PREP_DROP_MERGE PASS pendingUuidStable=true mergeSuppressed=true collectedByBot=true groundDrops=0 ledgerBalanced=true' 'merge-safe exact Bot drop collection'
assert_log 'SITE_PREP_SALVAGE_PAUSE PASS fullChestPaused=true partialInsert=0 resumed=true salvageLedgerBalanced=true privateItemsDropped=0' 'full salvage chest pause and resume'
assert_log 'SITE_PREP_PROTECTION PASS protectedBlocksRemoved=0 containerOpened=0 environmentalMutation=0 worldMutation=false' 'protected/hazard refusal'
assert_log 'SITE_PREP_GRADING PASS rectangle=2x2' 'slope cut, hole fill and selected surface grading'
assert_log 'SITE_PREP_FORCED_GRADING PASS secondConfirmation=true snapshotBound=true protectedBlocksRemoved=3 containersDestroyed=1 blockEntitiesDestroyed=1 dangerousMediaRemoved=1 unbreakableBlocksRemoved=1 privateContainerContentsRead=0 playerInventoryAccess=0 permanentDataLossWarning=true cleanup=true' 'snapshot-bound destructive grading confirmation'
assert_log 'SITE_PREP_STALE_TOKEN PASS staleRefused=true actualMutations=0 unknownBlocksRemoved=0 protectedBlocksRemoved=0' 'stale token refusal'
assert_log 'SITE_PREP_TWO_BOT PASS workers=2 workersUsed=2 exactMutations=2 salvageDelivered=true botOverlap=false teleportFallback=false' 'two-Bot clearing'
assert_log 'SITE_PREP_FIVE_BOT PASS workers=5 workersUsed=5 exactMutations=5 sameFleetKernel=true salvageDelivered=true botOverlap=false teleportFallback=false' 'five-Bot shared-fleet clearing'
assert_log 'SITE_PREP_FLEET_RELOAD PASS reloadInterrupted=true exactRescan=true reassigned=true duplicateMutation=false exactMutations=1 salvageDelivered=true' 'typed fleet reload and reassignment'
assert_log 'SITE_PREP_FLEET_CANCEL PASS terminal=true actualMutations=0 workersRemoved=true noFurtherMutation=true' 'terminal fleet cancellation'
assert_log 'SITE_TO_PRODUCTION_C07 PASS placementAnchor=true siteSurvey=true demolitionPreview=true playerApproval=true terrainBots=true postClearanceRescan=true preparedSite=true verifiedPhysicalPlan=true constructionExecutor=existing liveWorldMismatchRefusal=true direct=true realSalvageInput=true output=minecraft:stripped_oak_log@1 cleanup=true' 'prepared-site to real production bridge'
assert_log 'All 12 required tests passed' 'twelve site-preparation GameTest successes'
assert_log 'ThreadedAnvilChunkStorage: All dimensions are saved' 'clean shutdown'

if find "${run_directory}/crash-reports" -type f -print -quit 2>/dev/null | grep -q .; then
    echo "Site-preparation GameTest created a crash report; log: ${log}" >&2
    exit 1
fi
if grep -Eqi '\[[^]]+/(ERROR|FATAL)\]|Exception in thread|Preparing crash report|Game test failed|test failed' "${log}"; then
    echo "Site-preparation GameTest log contains fatal evidence: ${log}" >&2
    exit 1
fi
if ps ax -o command= | grep -F -- "${run_directory}" | grep -Eq '[j]ava|[B]ootstrapLauncher'; then
    echo "Site-preparation GameTest left a Java process: ${run_directory}" >&2
    exit 1
fi

echo 'SITE_PREPARATION_GAMETEST_VERIFIED tests=12 singleTarget=true dropMergeSuppressed=true pendingUuidStable=true twoBots=true fiveBots=true sharedFleetKernel=true exactMutations=true realDrop=true salvageDelivered=true fullChestPause=true partialInsert=0 protectedRefusal=true environmentalRefusal=true grading=true forcedGrading=true secondConfirmation=true permanentDataLossWarning=true staleRefusal=true reload=true reassignment=true cancel=true preparedSite=true verifiedPhysicalPlan=true existingConstructionExecutor=true liveWorldMismatchRefusal=true realC07=true botOverlap=false teleportFallback=false playerInventoryReads=0 privateContainerContentsRead=0 crashReports=0 residualProcesses=0'
echo "Isolated run directory: ${run_directory}"
echo "Test log: ${log}"
