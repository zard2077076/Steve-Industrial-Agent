$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runRoot = [IO.Path]::GetFullPath((Join-Path $root 'forge-create-1.20.1\run'))
$runDirectory = [IO.Path]::GetFullPath((Join-Path $runRoot 'create-belt-press-gametest'))
$configDirectory = Join-Path $runDirectory 'config'
$logDirectory = Join-Path $root 'work\logs'

$rootPrefix = $root.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
$runPrefix = $runRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
if (-not $runDirectory.StartsWith($runPrefix, [StringComparison]::OrdinalIgnoreCase) -or
    -not $runDirectory.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase) -or
    $runDirectory -eq $runRoot) {
    throw "Refusing to use an unsafe C-04 GameTest directory: $runDirectory"
}

if (Test-Path -LiteralPath $runDirectory) {
    Remove-Item -LiteralPath $runDirectory -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $runDirectory, $configDirectory, $logDirectory | Out-Null
'steve-industrial:isolated-execution/v1' | Set-Content -LiteralPath `
    (Join-Path $runDirectory '.steve-industrial-execution-test') -Encoding ascii
@(
    '# Generated for the isolated Steve Industrial Agent C-04 GameTest.'
    'eula=true'
) | Set-Content -LiteralPath (Join-Path $runDirectory 'eula.txt') -Encoding ascii
@(
    '# Generated for an isolated, non-public C-04 GameTest server.'
    'online-mode=false'
    'server-port=0'
    'level-name=world'
    'level-seed=steve-industrial-c04-v1'
    'level-type=minecraft:flat'
    'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}'
    'generate-structures=false'
    'view-distance=3'
    'simulation-distance=3'
) | Set-Content -LiteralPath (Join-Path $runDirectory 'server.properties') -Encoding ascii
@(
    '# Generated for the isolated Steve Industrial Agent C-04 GameTest.'
    'earlyWindowHeight = 480'
    'versionCheck = false'
    'earlyWindowControl = true'
    'earlyWindowFBScale = 1'
    'earlyWindowProvider = "fmlearlywindow"'
    'earlyWindowWidth = 854'
    'earlyWindowMaximized = false'
    'defaultConfigPath = "defaultconfigs"'
    'disableOptimizedDFU = true'
    'earlyWindowSkipGLVersions = []'
    'earlyWindowLogHelpMessage = false'
    'maxThreads = -1'
    'earlyWindowSquir = false'
    'earlyWindowShowCPU = false'
) | Set-Content -LiteralPath (Join-Path $configDirectory 'fml.toml') -Encoding ascii

$started = Get-Date
$log = Join-Path $logDirectory ('create-belt-press-gametest-' + $started.ToString('yyyyMMdd-HHmmss') + '.log')
& (Join-Path $PSScriptRoot 'Invoke-Gradle.ps1') `
    -PcreateBeltPressGameTest `
    :forge-create-1.20.1:runGameTestServer *>&1 | Tee-Object -FilePath $log

function Assert-LogContains([string]$Expected, [string]$Description) {
    if (-not (Select-String -LiteralPath $log -SimpleMatch $Expected -Quiet)) {
        throw "Missing $Description in $log. Expected: $Expected"
    }
}

Assert-LogContains 'java version 17.' 'Java 17 launch evidence'
Assert-LogContains 'Forge mod loading, version 47.4.10, for MC 1.20.1' 'Forge/Minecraft version evidence'
Assert-LogContains 'Create 6.0.6 initializing!' 'Create 6.0.6 initialization evidence'
Assert-LogContains 'CREATE_BELT_PRESS_PLAN origin=' 'typed plan and coordinate evidence'
Assert-LogContains 'CREATE_BELT_PRESS_GENERIC_EXECUTION plan=steve_industrial:c04/belt_press_plan graph=steve_industrial:c04/belt_press_graph nodes=27 steps=4 runner=BoundedStepRunner verifier=GenericVerificationRule handler=create:v606/belt_press' 'generic execution path evidence'
Assert-LogContains 'buildSteps=26 finalPlacements=26 preflightPositions=336' 'bounded C-04 plan evidence'
Assert-LogContains 'CREATE_BELT_PRESS_FEASIBILITY PASS conflicts=4 positions=3 kinds=NOT_REPLACEABLE,PROTECTED noMutation=true' 'complete C-04 placement-conflict and fail-before-mutation evidence'
Assert-LogContains 'CREATE_BELT_PRESS_CANCELLATION PASS changes=1 modifiedPositions=1 restoredPositions=1 warnings=0 stopped=true noResourceCompensation=true' 'bounded C-04 cancellation and conservative rollback evidence'
Assert-LogContains 'CREATE_BELT_PRESS_JOURNAL PASS entries=32 blockChanges=30 injectedInputs=1 irreversibleProcessing=1 modifiedPositions=28' 'complete C-04 world-change journal evidence'
Assert-LogContains 'CREATE_BELT_PRESS_PLACEMENTS PASS count=26 order=' 'real final-placement readback evidence'
Assert-LogContains 'CREATE_BELT_PRESS_PLAN_TRANSFORM PASS rotation=CLOCKWISE_90 beltAxis=X beltFacing=NORTH beltDriveAxis=X pressDriveAxis=X mechanicalPressFacing=EAST funnelFacing=UP' 'C-04 clockwise-90 physical block-state rotation evidence'
Assert-LogContains 'CREATE_BELT_PRESS_PLAN_TRANSFORM PASS rotation=CLOCKWISE_270 beltAxis=X beltFacing=SOUTH beltDriveAxis=X pressDriveAxis=X mechanicalPressFacing=WEST funnelFacing=UP' 'C-04 clockwise-270 physical block-state rotation evidence'
Assert-LogContains 'CREATE_BELT_PRESS_POWER PASS beltDriveRpm=' 'real belt/press power evidence'
Assert-LogContains 'CREATE_BELT_PRESS_RECIPE PASS id=create:pressing/iron_ingot type=create:pressing input=minecraft:iron_ingot consumed=1 output=create:iron_sheet observed=1' 'real pressing recipe input/output evidence'
Assert-LogContains 'pressCycleTicks=240 inputObservedOnBelt=true pressCycleObserved=true outputObservedInChest=true' 'real belt, press-cycle and chest evidence'
Assert-LogContains 'CREATE_BELT_PRESS_WRONG_THREAD code=WRONG_THREAD' 'wrong-thread rejection evidence'
Assert-LogContains 'CREATE_BELT_PRESS_UNLOADED_CHUNK chunk=' 'unloaded-chunk probe evidence'
Assert-LogContains 'before=false code=CHUNK_NOT_LOADED conflicts=26 positions=26 after=false' 'complete unloaded-target report and no chunk-load evidence'
Assert-LogContains 'CREATE_BELT_PRESS_GAMETEST PASS minecraft=1.20.1 forge=47.4.10 create=6.0.6-150 template=minecraft:bastion/mobs/empty buildSteps=26 finalPlacements=26 preflightPositions=336 inputObservedOnBelt=true pressCycleObserved=true outputObservedInChest=true' 'C-04 PASS marker'
Assert-LogContains 'All 3 required tests passed' 'three-orientation GameTest framework success evidence'
Assert-LogContains 'ThreadedAnvilChunkStorage: All dimensions are saved' 'clean server shutdown evidence'

$newCrashReports = @()
$crashDirectory = Join-Path $runDirectory 'crash-reports'
if (Test-Path -LiteralPath $crashDirectory) {
    $newCrashReports = @(Get-ChildItem -LiteralPath $crashDirectory -File |
        Where-Object LastWriteTime -ge $started)
}
if ($newCrashReports.Count -gt 0) {
    throw "C-04 GameTest created crash report(s): $($newCrashReports.FullName -join ', ')"
}

$fatalPatterns = @(
    '\[[^\]]+/(ERROR|FATAL)\]',
    'Exception in thread',
    'Preparing crash report',
    'C-04 executor failed',
    'test failed',
    'Game test failed'
)
$fatalMatches = @(Select-String -LiteralPath $log -Pattern $fatalPatterns -CaseSensitive:$false)
if ($fatalMatches.Count -gt 0) {
    throw "C-04 GameTest log contains fatal/error evidence: $($fatalMatches.Line -join ' | ')"
}

Write-Output 'CREATE_BELT_PRESS_GAMETEST_VERIFIED minecraft=1.20.1 forge=47.4.10 create=6.0.6-150 buildSteps=26 finalPlacements=26 preflightPositions=336 rotations=ZERO,CLOCKWISE_90,CLOCKWISE_270 genericRunner=true genericVerifier=true journalEntries=32 cancellationStopped=true safeRollback=true noResourceCompensation=true feasibilityConflicts=4 feasibilityPositions=3 noMutationOnFeasibilityFailure=true realRecipe=true realBeltInput=true realPressCycle=true realChestOutput=true wrongThreadRejected=true unloadedChunkPreserved=true crashReports=0'
Write-Output "Isolated run directory: $runDirectory"
Write-Output "Test log: $log"
