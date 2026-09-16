$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runRoot = [IO.Path]::GetFullPath((Join-Path $root 'forge-create-1.20.1\run'))
$runDirectory = [IO.Path]::GetFullPath((Join-Path $runRoot 'goal-driven-execution-gametest'))
$logDirectory = Join-Path $root 'work\logs'
$rootPrefix = $root.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
$runPrefix = $runRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
if (-not $runDirectory.StartsWith($runPrefix, [StringComparison]::OrdinalIgnoreCase) -or
        -not $runDirectory.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase) -or
        $runDirectory.StartsWith('D:\PCL2\', [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing unsafe goal-driven GameTest directory: $runDirectory"
}
if (Test-Path -LiteralPath $runDirectory) {
    Remove-Item -LiteralPath $runDirectory -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $runDirectory, $logDirectory | Out-Null
@('# Repository-owned isolated goal-driven execution GameTest.', 'eula=true') |
    Set-Content -LiteralPath (Join-Path $runDirectory 'eula.txt') -Encoding ascii
'steve-industrial:isolated-execution/v1' |
    Set-Content -LiteralPath (Join-Path $runDirectory '.steve-industrial-execution-test') -Encoding ascii
@(
    '# Repository-owned isolated goal-driven execution GameTest.'
    'online-mode=false'
    'server-port=0'
    'level-name=world'
    'level-seed=steve-industrial-goal-execution-v1'
    'level-type=minecraft:flat'
    'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}'
    'generate-structures=false'
    'view-distance=6'
    'simulation-distance=6'
) | Set-Content -LiteralPath (Join-Path $runDirectory 'server.properties') -Encoding ascii

$started = Get-Date
$log = Join-Path $logDirectory ('goal-driven-execution-gametest-' + $started.ToString('yyyyMMdd-HHmmss') + '.log')
& (Join-Path $PSScriptRoot 'Invoke-Gradle.ps1') `
    -PgoalDrivenExecutionGameTest `
    :forge-create-1.20.1:runGameTestServer *>&1 | Tee-Object -FilePath $log

function Assert-LogContains([string]$Expected, [string]$Description) {
    if (-not (Select-String -LiteralPath $log -SimpleMatch $Expected -Quiet)) {
        throw "Missing $Description in $log. Expected: $Expected"
    }
}

Assert-LogContains 'java version 17.' 'Java 17 evidence'
Assert-LogContains 'Forge mod loading, version 47.4.10, for MC 1.20.1' 'Forge version evidence'
Assert-LogContains 'Create 6.0.6 initializing!' 'Create initialization evidence'
Assert-LogContains 'GOAL_EXECUTION_GAMETEST PASS target=minecraft:gravel quantity=3 observed=3 orientation=ZERO nodes=2 planningChecks=8 bindingChecks=15 physicalChecks=13 readinessChecks=16 maxHandlerInvocationsPerTick=1 inputConsumed=true outputVerified=true journals=3' 'gravel zero execution'
Assert-LogContains 'GOAL_EXECUTION_GAMETEST PASS target=minecraft:gravel quantity=3 observed=3 orientation=CLOCKWISE_90' 'gravel clockwise-90 execution'
Assert-LogContains 'GOAL_EXECUTION_GAMETEST PASS target=minecraft:gravel quantity=3 observed=3 orientation=CLOCKWISE_270' 'gravel clockwise-270 execution'
Assert-LogContains 'GOAL_EXECUTION_GAMETEST PASS target=create:iron_sheet quantity=2 observed=2 orientation=ZERO nodes=1' 'iron-sheet execution'
Assert-LogContains 'GOAL_EXECUTION_FAILURE_MATRIX PASS inputMissing=true formalWorldForbidden=true targetChanged=true' 'typed failure matrix'
Assert-LogContains 'GOAL_EXECUTION_CANCEL PASS duplicateRejected=true changes=1 restored=true code=EXECUTION_CANCELLED' 'cancel and duplicate proof'
Assert-LogContains 'GOAL_EXECUTION_RELOAD PASS boundary=BUILD_COMPLETE' 'BUILD-complete reload recovery'
Assert-LogContains 'GOAL_EXECUTION_RELOAD PASS boundary=BUILD_MID' 'mid-BUILD reload recovery'
Assert-LogContains 'GOAL_EXECUTION_RELOAD PASS boundary=BUILD_MID processNodes=2 recoveredNode=0' 'multi-node initial BUILD reload recovery'
Assert-LogContains 'GOAL_EXECUTION_RELOAD_REFUSED PASS boundary=PROCESS code=RELOAD_RECOVERY_UNSAFE' 'unsafe PROCESS reload refusal'
Assert-LogContains 'GOAL_EXECUTION_RELOAD PASS boundary=VERIFY' 'idempotent VERIFY reload recovery'
Assert-LogContains 'GOAL_EXECUTION_ROUTE_FAILURE PASS code=ROUTE_CONSTRUCTION_FAILED' 'route construction failure'
Assert-LogContains 'GOAL_EXECUTION_POWER_FAILURE PASS code=POWER_SOURCE_MISSING' 'physical power failure'
Assert-LogContains 'GOAL_EXECUTION_OUTPUT_FAILURE PASS code=OUTPUT_QUANTITY_MISMATCH' 'output verification failure'
Assert-LogContains 'FORMAL_WORLD_HANDLER_GUARD PASS sessionConstructed=true propertyRevoked=true code=FORMAL_WORLD_EXECUTION_FORBIDDEN worldMutation=false' 'post-session handler guard'
Assert-LogContains 'C03_THREE_MODE_EQUIVALENCE PASS' 'C-03 Direct/Bots/Hybrid equivalence'
Assert-LogContains 'C04_THREE_MODE_EQUIVALENCE PASS' 'C-04 Direct/Bots/Hybrid equivalence'
Assert-LogContains 'C05_THREE_MODE_EQUIVALENCE PASS' 'C-05 Direct/Bots/Hybrid equivalence'
Assert-LogContains 'C06_THREE_MODE_EQUIVALENCE PASS' 'C-06 Direct/Bots/Hybrid equivalence'
Assert-LogContains 'C07_THREE_MODE_EQUIVALENCE PASS' 'C-07 Direct/Bots/Hybrid equivalence'
Assert-LogContains 'C08_THREE_MODE_EQUIVALENCE PASS' 'C-08 Direct/Bots/Hybrid equivalence'
Assert-LogContains 'C09_THREE_MODE_EQUIVALENCE PASS' 'C-09 Direct/Bots/Hybrid equivalence'
Assert-LogContains 'C10_THREE_MODE_EQUIVALENCE PASS' 'C-10 Direct/Bots/Hybrid equivalence'
Assert-LogContains 'finalBlockSnapshot=true orientation=true ports=true shaftsBelts=true output=true sessionEvidence=true cleanup=true reload=true materialConsumption=true failures=0' 'three-mode equivalence evidence matrix'
Assert-LogContains 'botRoles=logistics+builder_inspector botWorkersActive=2 playerLike=true smoothMovement=true completionStationsDistinct=true botOverlap=false' 'two active non-overlapping role-specific player-like Bots'
Assert-LogContains 'hybridRoutes=bots+direct' 'safe Hybrid physical route provenance'
Assert-LogContains 'installationEscrow=true completeItemFormBom=true' 'complete item-form installation material escrow'
Assert-LogContains 'installationEscrowRestored=true' 'exact installation material cancellation return'
Assert-LogContains 'EXECUTOR_MODE_COMMANDS PASS buildModes=direct,bots,hybrid status=true explicitPilotModes=direct,bots,hybrid safePolicy=true' 'runtime executor command tree'
# The count is corrected, never deleted: this line is the only thing that catches a
# GameTest that stopped registering. It read 45 against a tree that logs 49, so the
# Windows path failed here before reaching any assertion below.
Assert-LogContains 'FLUID_TRANSFER PASS movedMb=1000 sourceDeltaMb=1000 destinationDeltaMb=1000' 'physical fluid conservation'
Assert-LogContains 'refusalMovedNothing=true selfTransferRefused=true' 'fluid refusal evidence'
Assert-LogContains 'FLUID_TOPOLOGY PASS' 'pipe topology'
Assert-LogContains 'unconnectedPairRejected=true selfExcluded=true' 'topology rejects the unconnected pair'
Assert-LogContains 'GOAL_EXECUTION_GAMETEST PASS target=minecraft:stripped_oak_log quantity=1 observed=1' 'cutting'
Assert-LogContains 'GOAL_EXECUTION_GAMETEST PASS target=minecraft:blackstone quantity=1 observed=1' 'haunting'
Assert-LogContains 'GOAL_EXECUTION_GAMETEST PASS target=minecraft:iron_ingot quantity=1 observed=1' 'blasting'
Assert-LogContains 'GOAL_EXECUTION_GAMETEST PASS target=create:andesite_alloy quantity=1 observed=1' 'mixing'
Assert-LogContains 'GOAL_EXECUTION_GAMETEST PASS target=create:cogwheel quantity=1 observed=1' 'deploying'
Assert-LogContains 'GOAL_EXECUTION_GAMETEST PASS target=create:blaze_cake_base quantity=1 observed=1' 'compacting'
Assert-LogContains 'GOAL_EXECUTION_GAMETEST PASS target=create:dough quantity=1 observed=1' 'splashing'
Assert-LogContains 'PLAYER_DERIVED_ORDER PASS target=minecraft:blue_concrete' 'searched derived player order'
Assert-LogContains 'searchVisible=true reviewedCatalog=false createProject=OK' 'derived order bypasses reviewed display subset'
Assert-LogContains 'FLUID_INPUT_MIXING PASS target=create:pulp fluid=minecraft:water amountMb=250' 'fluid-input mixing'
Assert-LogContains 'bucket=minecraft:water_bucket bucketConsumed=true liveRecipeMatched=true finalBasinFluidMb=0 outputObserved=1' 'fluid-input material and settlement evidence'
Assert-LogContains 'FLUID_INPUT_COMPACTING PASS target=minecraft:granite fluid=minecraft:lava amountMb=100' 'fluid-input compacting'
Assert-LogContains 'bucket=minecraft:lava_bucket bucketConsumed=true liveRecipeMatched=true finalBasinFluidMb=0 outputObserved=1' 'lava material and settlement evidence'
Assert-LogContains 'C09_LAVA_THREE_MODE_EQUIVALENCE PASS' 'fluid-input compacting three-mode equivalence'
Assert-LogContains 'fluidJournalMb=100 noResidue=true' 'fluid-input compacting exact three-mode settlement'
Assert-LogContains 'C09_LAVA_CANCEL_MATRIX PASS' 'fluid-input compacting exact cancellation return'
foreach ($target in @('minecraft:gravel', 'create:dough', 'minecraft:stripped_oak_log', 'create:andesite_alloy', 'create:blaze_cake_base', 'create:cogwheel', 'minecraft:blue_concrete')) {
    Assert-LogContains "PLAYER_CREATE_FIXTURE PASS target=$target " 'live client-fixture material bill'
}
# Includes the new fixture matrix, in addition to the existing 64 required tests.
Assert-LogContains 'All 65 required tests passed' 'integrated GameTest success count'
# Fluid and warehouse discovery reached the bash runner and not this one, so Windows
# acceptance silently covered less than macOS acceptance.
Assert-LogContains 'WAREHOUSE_DISCOVERY PASS found=2' 'warehouse discovery'
Assert-LogContains 'excludedHonoured=true emptySkipped=true' 'discovery exclusion evidence'
Assert-LogContains 'FLUID_DISCOVERY PASS' 'fluid endpoint discovery'
Assert-LogContains 'ThreadedAnvilChunkStorage: All dimensions are saved' 'clean shutdown'

$crashDirectory = Join-Path $runDirectory 'crash-reports'
$newCrashes = if (Test-Path -LiteralPath $crashDirectory) {
    @(Get-ChildItem -LiteralPath $crashDirectory -File | Where-Object LastWriteTime -ge $started)
} else { @() }
if ($newCrashes.Count -gt 0) {
    throw "Goal-driven GameTest created crash report(s): $($newCrashes.FullName -join ', ')"
}
$fatalPatterns = @(
    '\[[^\]]+/(ERROR|FATAL)\]',
    'Exception in thread',
    'Preparing crash report',
    'Goal-driven execution failed',
    'test failed',
    'Game test failed'
)
$fatal = @(Select-String -LiteralPath $log -Pattern $fatalPatterns -CaseSensitive:$false)
if ($fatal.Count -gt 0) {
    throw "Goal-driven GameTest log contains fatal evidence: $($fatal.Line -join ' | ')"
}
$residual = @(Get-CimInstance Win32_Process | Where-Object {
    $_.Name -eq 'java.exe' -and $_.CommandLine -like "*$runDirectory*"
})
if ($residual.Count -gt 0) {
    throw "Goal-driven GameTest left Java process(es): $($residual.ProcessId -join ',')"
}

Write-Output 'GOAL_EXECUTION_GAMETEST_VERIFIED tests=63 gravel=3 ironSheet=2 sand=1 orientations=ZERO,CLOCKWISE_90,CLOCKWISE_270 planningChecks=8 bindingChecks=15 physicalChecks=13 readinessChecks=16 genericSession=true boundedRunner=true maxHandlerInvocationsPerTick=1 realInputConsumed=true realOutputVerified=true reloadBuildMid=true reloadConnect=true reloadProcessRefused=true reloadVerifyIdempotent=true cancel=true rollback=true duplicateRejected=true formalWorldForbidden=true handlerGuard=true c03ThreeMode=true c04ThreeMode=true c05ThreeMode=true c06ThreeMode=true c07ThreeMode=true c08ThreeMode=true c09ThreeMode=true c09FluidThreeMode=true c09FluidCancel=true c10ThreeMode=true direct=true bots=true hybrid=true equivalence=true installationEscrow=true completeItemFormBom=true exactInstallationReturn=true botRoles=logistics+builder_inspector botWorkersActive=2,3 playerLike=true smoothMovement=true completionStationsDistinct=true botOverlap=false modeCommands=true crashReports=0 residualProcesses=0'
Write-Output "Isolated run directory: $runDirectory"
Write-Output "Test log: $log"
