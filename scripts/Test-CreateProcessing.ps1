$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runRoot = [IO.Path]::GetFullPath((Join-Path $root 'forge-create-1.20.1\run'))
$runDirectory = [IO.Path]::GetFullPath((Join-Path $runRoot 'create-processing-gametest'))
$logDirectory = Join-Path $root 'work\logs'

$rootPrefix = $root.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
$runPrefix = $runRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
if (-not $runDirectory.StartsWith($runPrefix, [StringComparison]::OrdinalIgnoreCase) -or
    -not $runDirectory.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase) -or
    $runDirectory -eq $runRoot) {
    throw "Refusing to use an unsafe C-03 GameTest directory: $runDirectory"
}

if (Test-Path -LiteralPath $runDirectory) {
    Remove-Item -LiteralPath $runDirectory -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $runDirectory, $logDirectory | Out-Null
'steve-industrial:isolated-execution/v1' | Set-Content -LiteralPath `
    (Join-Path $runDirectory '.steve-industrial-execution-test') -Encoding ascii
@(
    '# Generated for the isolated Steve Industrial Agent C-03 GameTest.'
    'eula=true'
) | Set-Content -LiteralPath (Join-Path $runDirectory 'eula.txt') -Encoding ascii
@(
    '# Generated for an isolated, non-public C-03 GameTest server.'
    'online-mode=false'
    'server-port=0'
    'level-name=world'
    'level-seed=steve-industrial-c03-v1'
    'level-type=minecraft:flat'
    'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}'
    'generate-structures=false'
    'view-distance=3'
    'simulation-distance=3'
) | Set-Content -LiteralPath (Join-Path $runDirectory 'server.properties') -Encoding ascii

$started = Get-Date
$log = Join-Path $logDirectory ('create-processing-gametest-' + $started.ToString('yyyyMMdd-HHmmss') + '.log')
& (Join-Path $PSScriptRoot 'Invoke-Gradle.ps1') `
    -PcreateProcessingGameTest `
    :forge-create-1.20.1:runGameTestServer *>&1 | Tee-Object -FilePath $log

function Assert-LogContains([string]$Expected, [string]$Description) {
    if (-not (Select-String -LiteralPath $log -SimpleMatch $Expected -Quiet)) {
        throw "Missing $Description in $log. Expected: $Expected"
    }
}

Assert-LogContains 'java version 17.' 'Java 17 launch evidence'
Assert-LogContains 'Forge mod loading, version 47.4.10, for MC 1.20.1' 'Forge/Minecraft version evidence'
Assert-LogContains 'Create 6.0.6 initializing!' 'Create 6.0.6 initialization evidence'
Assert-LogContains 'CREATE_PROCESSING_GENERIC_EXECUTION plan=steve_industrial:c03/water_wheel_millstone_plan graph=steve_industrial:c03/water_wheel_millstone_graph nodes=6 steps=4 runner=BoundedStepRunner verifier=GenericVerificationRule handler=create:v606/water_wheel_millstone' 'shared graph/session/runner/verifier production-path evidence'
Assert-LogContains 'CREATE_PROCESSING_PLAN origin=' 'typed plan and coordinate evidence'
Assert-LogContains 'placements=17 preflightPositions=175' 'bounded plan evidence'
Assert-LogContains 'CREATE_PROCESSING_FEASIBILITY PASS conflicts=4 positions=3 kinds=NOT_REPLACEABLE,PROTECTED noMutation=true' 'complete placement-conflict and fail-before-mutation evidence'
Assert-LogContains 'CREATE_PROCESSING_CANCELLATION PASS changes=1 modifiedPositions=1 restoredPositions=1 warnings=0 stopped=true noResourceCompensation=true' 'bounded cancellation and conservative rollback evidence'
Assert-LogContains 'CREATE_PROCESSING_JOURNAL PASS entries=19 blockChanges=17 injectedInputs=1 irreversibleProcessing=1 modifiedPositions=17' 'complete C-03 world-change journal evidence'
Assert-LogContains 'CREATE_PROCESSING_PLACEMENTS PASS count=17 order=' 'real placement readback evidence'
Assert-LogContains 'CREATE_PROCESSING_PLAN_TRANSFORM PASS rotation=CLOCKWISE_90 waterWheelAxis=Z gearboxAxis=X shaftAxis=Y' 'C-03 clockwise-90 physical state rotation evidence'
Assert-LogContains 'CREATE_PROCESSING_PLAN_TRANSFORM PASS rotation=CLOCKWISE_270 waterWheelAxis=Z gearboxAxis=X shaftAxis=Y' 'C-03 clockwise-270 physical state rotation evidence'
Assert-LogContains 'CREATE_PROCESSING_POWER PASS waterWheelRpm=' 'real power propagation evidence'
Assert-LogContains 'CREATE_PROCESSING_WATER_CONTAINMENT PASS waterPositions=' 'bounded water containment evidence'
Assert-LogContains 'CREATE_PROCESSING_RECIPE PASS id=create:milling/cobblestone type=create:milling duration=250 input=minecraft:cobblestone consumed=1 output=minecraft:gravel observed=1' 'real recipe input/output evidence'
Assert-LogContains 'CREATE_PROCESSING_WRONG_THREAD code=WRONG_THREAD' 'wrong-thread rejection evidence'
Assert-LogContains 'CREATE_PROCESSING_UNLOADED_CHUNK chunk=' 'unloaded-chunk probe evidence'
Assert-LogContains 'before=false code=CHUNK_NOT_LOADED conflicts=17 positions=17 after=false' 'complete unloaded-target report and no chunk-load evidence'
Assert-LogContains 'CREATE_PROCESSING_GAMETEST PASS minecraft=1.20.1 forge=47.4.10 create=6.0.6-150 template=minecraft:bastion/mobs/empty placements=17 preflightPositions=175 inputConsumed=true outputVerified=true' 'C-03 PASS marker'
Assert-LogContains 'All 3 required tests passed' 'three-orientation GameTest framework success evidence'
Assert-LogContains 'ThreadedAnvilChunkStorage: All dimensions are saved' 'clean server shutdown evidence'

$newCrashReports = @()
$crashDirectory = Join-Path $runDirectory 'crash-reports'
if (Test-Path -LiteralPath $crashDirectory) {
    $newCrashReports = @(Get-ChildItem -LiteralPath $crashDirectory -File |
        Where-Object LastWriteTime -ge $started)
}
if ($newCrashReports.Count -gt 0) {
    throw "C-03 GameTest created crash report(s): $($newCrashReports.FullName -join ', ')"
}

$fatalPatterns = @(
    '\[[^\]]+/(ERROR|FATAL)\]',
    'Exception in thread',
    'Preparing crash report',
    'C-03 executor failed',
    'test failed',
    'Game test failed'
)
$fatalMatches = @(Select-String -LiteralPath $log -Pattern $fatalPatterns -CaseSensitive:$false)
if ($fatalMatches.Count -gt 0) {
    throw "C-03 GameTest log contains fatal/error evidence: $($fatalMatches.Line -join ' | ')"
}

Write-Output 'CREATE_PROCESSING_GAMETEST_VERIFIED minecraft=1.20.1 forge=47.4.10 create=6.0.6-150 genericRunner=true genericVerifier=true journalEntries=19 cancellationStopped=true safeRollback=true noResourceCompensation=true placements=17 preflightPositions=175 rotations=ZERO,CLOCKWISE_90,CLOCKWISE_270 feasibilityConflicts=4 feasibilityPositions=3 noMutationOnFeasibilityFailure=true realRecipe=true inputConsumed=true outputVerified=true wrongThreadRejected=true unloadedChunkPreserved=true crashReports=0'
Write-Output "Isolated run directory: $runDirectory"
Write-Output "Test log: $log"
