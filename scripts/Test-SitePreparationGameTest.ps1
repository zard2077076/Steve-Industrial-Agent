$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runRoot = [IO.Path]::GetFullPath((Join-Path $root 'forge-create-1.20.1\run'))
$runDirectory = [IO.Path]::GetFullPath((Join-Path $runRoot 'site-preparation-gametest'))
$logDirectory = Join-Path $root 'work\logs'
$rootPrefix = $root.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
$runPrefix = $runRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
if (-not $runDirectory.StartsWith($runPrefix, [StringComparison]::OrdinalIgnoreCase) -or
        -not $runDirectory.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase) -or
        $runDirectory.StartsWith('D:\PCL2\', [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing unsafe site-preparation GameTest directory: $runDirectory"
}

$preexisting = @(Get-CimInstance Win32_Process | Where-Object {
    $_.Name -eq 'java.exe' -and $_.CommandLine -match '(Minecraft|forge|runGameTestServer|server\.jar)'
})
if ($preexisting.Count -gt 0) {
    throw "Site-preparation GameTest slot is not free; Java process(es): $($preexisting.ProcessId -join ',')"
}

if (Test-Path -LiteralPath $runDirectory) {
    Remove-Item -LiteralPath $runDirectory -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $runDirectory, $logDirectory | Out-Null
@('# Repository-owned isolated site-preparation GameTest.', 'eula=true') |
    Set-Content -LiteralPath (Join-Path $runDirectory 'eula.txt') -Encoding ascii
'steve-industrial:isolated-site-preparation/v1' |
    Set-Content -LiteralPath (Join-Path $runDirectory '.steve-industrial-site-preparation-test') -Encoding ascii
'steve-industrial:isolated-execution/v1' |
    Set-Content -LiteralPath (Join-Path $runDirectory '.steve-industrial-execution-test') -Encoding ascii
@(
    '# Repository-owned isolated site-preparation GameTest.'
    'online-mode=false'
    'server-port=0'
    'level-name=world'
    'level-seed=steve-industrial-site-preparation-v1'
    'level-type=minecraft:flat'
    'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}'
    'generate-structures=false'
    'view-distance=6'
    'simulation-distance=6'
) | Set-Content -LiteralPath (Join-Path $runDirectory 'server.properties') -Encoding ascii

$started = Get-Date
$log = Join-Path $logDirectory ('site-preparation-gametest-' +
    $started.ToString('yyyyMMdd-HHmmss') + '.log')
& (Join-Path $PSScriptRoot 'Invoke-Gradle.ps1') `
    -PsitePreparationGameTest `
    :forge-create-1.20.1:runGameTestServer *>&1 | Tee-Object -FilePath $log

function Assert-LogContains([string]$Expected, [string]$Description) {
    if (-not (Select-String -LiteralPath $log -SimpleMatch $Expected -Quiet)) {
        throw "Missing $Description in $log. Expected: $Expected"
    }
}

Assert-LogContains 'java version 17.' 'Java 17 evidence'
Assert-LogContains 'Forge mod loading, version 47.4.10, for MC 1.20.1' 'Forge version evidence'
Assert-LogContains 'SITE_PREP_BOT_CLEARING PASS exactMutations=1 realDrop=true salvageDelivered=true unknownBlocksRemoved=0 protectedBlocksRemoved=0 containerOpened=0 playerInventoryAccess=0 regionOutsideMutations=0 formalWorldAccess=0 botOverlap=false teleportFallback=false' 'physical Bot clearing and salvage'
Assert-LogContains 'SITE_PREP_DROP_MERGE PASS pendingUuidStable=true mergeSuppressed=true collectedByBot=true groundDrops=0 ledgerBalanced=true' 'merge-safe exact Bot drop collection'
Assert-LogContains 'SITE_PREP_SALVAGE_PAUSE PASS fullChestPaused=true partialInsert=0 resumed=true salvageLedgerBalanced=true privateItemsDropped=0' 'full salvage chest pause and resume'
Assert-LogContains 'SITE_PREP_PROTECTION PASS protectedBlocksRemoved=0 containerOpened=0 environmentalMutation=0 worldMutation=false' 'protected/hazard refusal'
Assert-LogContains 'SITE_PREP_GRADING PASS rectangle=2x2' 'real slope cut, hole fill, and selected surface grading'
Assert-LogContains 'SITE_PREP_FORCED_GRADING PASS secondConfirmation=true snapshotBound=true protectedBlocksRemoved=3 containersDestroyed=1 blockEntitiesDestroyed=1 dangerousMediaRemoved=1 unbreakableBlocksRemoved=1 privateContainerContentsRead=0 playerInventoryAccess=0 permanentDataLossWarning=true cleanup=true' 'snapshot-bound destructive grading confirmation'
Assert-LogContains 'SITE_PREP_STALE_TOKEN PASS staleRefused=true actualMutations=0 unknownBlocksRemoved=0 protectedBlocksRemoved=0' 'stale token refusal'
Assert-LogContains 'SITE_PREP_TWO_BOT PASS workers=2 workersUsed=2 exactMutations=2 salvageDelivered=true botOverlap=false teleportFallback=false' 'two-Bot clearing'
Assert-LogContains 'SITE_PREP_FIVE_BOT PASS workers=5 workersUsed=5 exactMutations=5 sameFleetKernel=true salvageDelivered=true botOverlap=false teleportFallback=false' 'five-Bot shared-fleet clearing'
Assert-LogContains 'SITE_PREP_FLEET_RELOAD PASS reloadInterrupted=true exactRescan=true reassigned=true duplicateMutation=false exactMutations=1 salvageDelivered=true' 'typed fleet reload and reassignment'
Assert-LogContains 'SITE_PREP_FLEET_CANCEL PASS terminal=true actualMutations=0 workersRemoved=true noFurtherMutation=true' 'terminal fleet cancellation'
Assert-LogContains 'SITE_TO_PRODUCTION_C07 PASS placementAnchor=true siteSurvey=true demolitionPreview=true playerApproval=true terrainBots=true postClearanceRescan=true preparedSite=true verifiedPhysicalPlan=true constructionExecutor=existing liveWorldMismatchRefusal=true direct=true realSalvageInput=true output=minecraft:stripped_oak_log@1 cleanup=true' 'prepared-site to real production bridge'
Assert-LogContains 'All 12 required tests passed' 'twelve site-preparation GameTest successes'
Assert-LogContains 'ThreadedAnvilChunkStorage: All dimensions are saved' 'clean shutdown'

$crashDirectory = Join-Path $runDirectory 'crash-reports'
$newCrashes = if (Test-Path -LiteralPath $crashDirectory) {
    @(Get-ChildItem -LiteralPath $crashDirectory -File |
        Where-Object LastWriteTime -ge $started)
} else { @() }
if ($newCrashes.Count -gt 0) {
    throw "Site-preparation GameTest created crash report(s): $($newCrashes.FullName -join ', ')"
}
$fatalPatterns = @(
    '\[[^\]]+/(ERROR|FATAL)\]'
    'Exception in thread'
    'Preparing crash report'
    'Game test failed'
    'test failed'
)
$fatal = @(Select-String -LiteralPath $log -Pattern $fatalPatterns -CaseSensitive:$false)
if ($fatal.Count -gt 0) {
    throw "Site-preparation GameTest log contains fatal evidence: $($fatal.Line -join ' | ')"
}
$residual = @(Get-CimInstance Win32_Process | Where-Object {
    $_.Name -eq 'java.exe' -and $_.CommandLine -like "*$runDirectory*"
})
if ($residual.Count -gt 0) {
    throw "Site-preparation GameTest left Java process(es): $($residual.ProcessId -join ',')"
}

Write-Output 'SITE_PREPARATION_GAMETEST_VERIFIED tests=12 singleTarget=true dropMergeSuppressed=true pendingUuidStable=true twoBots=true fiveBots=true sharedFleetKernel=true exactMutations=true realDrop=true salvageDelivered=true fullChestPause=true partialInsert=0 protectedRefusal=true environmentalRefusal=true grading=true forcedGrading=true secondConfirmation=true permanentDataLossWarning=true staleRefusal=true reload=true reassignment=true cancel=true preparedSite=true verifiedPhysicalPlan=true existingConstructionExecutor=true liveWorldMismatchRefusal=true realC07=true botOverlap=false teleportFallback=false playerInventoryReads=0 privateContainerContentsRead=0 crashReports=0 residualProcesses=0'
Write-Output "Isolated run directory: $runDirectory"
Write-Output "Test log: $log"
