$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runRoot = [IO.Path]::GetFullPath((Join-Path $root 'forge-create-1.20.1\run'))
$runDirectory = [IO.Path]::GetFullPath((Join-Path $runRoot 'c05-crushing-gametest'))
$logDirectory = Join-Path $root 'work\logs'
$rootPrefix = $root.TrimEnd([IO.Path]::DirectorySeparatorChar) +
    [IO.Path]::DirectorySeparatorChar
$runPrefix = $runRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) +
    [IO.Path]::DirectorySeparatorChar
if (-not $runDirectory.StartsWith($runPrefix, [StringComparison]::OrdinalIgnoreCase) -or
        -not $runDirectory.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase) -or
        $runDirectory.StartsWith('D:\PCL2\', [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing unsafe C-05 GameTest directory: $runDirectory"
}
if (Test-Path -LiteralPath $runDirectory) {
    Remove-Item -LiteralPath $runDirectory -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $runDirectory, $logDirectory | Out-Null
@('# Repository-owned isolated C-05 GameTest.', 'eula=true') |
    Set-Content -LiteralPath (Join-Path $runDirectory 'eula.txt') -Encoding ascii
'steve-industrial:isolated-execution/v1' |
    Set-Content -LiteralPath (
        Join-Path $runDirectory '.steve-industrial-execution-test') -Encoding ascii
@(
    '# Repository-owned isolated C-05 GameTest.'
    'online-mode=false'
    'server-port=0'
    'level-name=world'
    'level-seed=steve-industrial-c05-crushing-v1'
    'level-type=minecraft:flat'
    'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}'
    'generate-structures=false'
    'view-distance=6'
    'simulation-distance=6'
) | Set-Content -LiteralPath (
    Join-Path $runDirectory 'server.properties') -Encoding ascii

$started = Get-Date
$log = Join-Path $logDirectory (
    'c05-crushing-gametest-' + $started.ToString('yyyyMMdd-HHmmss') + '.log')
& (Join-Path $PSScriptRoot 'Invoke-Gradle.ps1') `
    -PgoalDrivenExecutionGameTest `
    -Pc05CrushingGameTestOnly `
    :forge-create-1.20.1:runGameTestServer *>&1 | Tee-Object -FilePath $log

function Assert-LogContains([string]$Expected, [string]$Description) {
    if (-not (Select-String -LiteralPath $log -SimpleMatch $Expected -Quiet)) {
        throw "Missing $Description in $log. Expected: $Expected"
    }
}

Assert-LogContains 'java version 17.' 'Java 17 evidence'
Assert-LogContains 'Forge mod loading, version 47.4.10, for MC 1.20.1' 'Forge evidence'
Assert-LogContains 'Create 6.0.6 initializing!' 'Create evidence'
Assert-LogContains 'C05_THREE_MODE_EQUIVALENCE PASS' 'C-05 equivalence'
Assert-LogContains 'finalBlockSnapshot=true orientation=true ports=true shaftsBelts=true output=true sessionEvidence=true cleanup=true reload=true materialConsumption=true failures=0' 'C-05 evidence matrix'
Assert-LogContains 'C05_EXACT_RELOAD PASS boundary=BUILD_MID exactRescan=true' 'C-05 exact mid-build recovery'
Assert-LogContains 'repeatedPlacements=0 repeatedInputs=0 repeatedOutput=0' 'C-05 duplicate prevention'
Assert-LogContains 'C05_FAILURE_BOUNDARY PASS materialMissing=INPUT_RESOURCE_MISSING formalWorld=FORMAL_WORLD_FORBIDDEN obstruction=BLOCK_PLACEMENT_BLOCKED cancellation=EXECUTION_CANCELLED' 'C-05 typed boundary failures'
Assert-LogContains 'C05_RUNTIME_FAULT_MATRIX PASS wrongOrientation=PROCESSING_FAILED unknownSideEffect=PROCESSING_FAILED insufficientPower=KINETIC_COMPONENT_NOT_FOUND inputConsumed=false outputProduced=false cleanup=true' 'C-05 typed runtime failures'
Assert-LogContains 'All 4 required tests passed' 'four required C-05 GameTests'
Assert-LogContains 'ThreadedAnvilChunkStorage: All dimensions are saved' 'clean shutdown'

$crashDirectory = Join-Path $runDirectory 'crash-reports'
$newCrashes = if (Test-Path -LiteralPath $crashDirectory) {
    @(Get-ChildItem -LiteralPath $crashDirectory -File |
        Where-Object LastWriteTime -ge $started)
} else { @() }
if ($newCrashes.Count -gt 0) {
    throw "C-05 GameTest created crash report(s): $($newCrashes.FullName -join ', ')"
}
$fatalPatterns = @(
    '\[[^\]]+/(ERROR|FATAL)\]',
    'Exception in thread',
    'Preparing crash report',
    'Three-mode execution failed',
    'test failed',
    'Game test failed'
)
$fatal = @(Select-String -LiteralPath $log -Pattern $fatalPatterns -CaseSensitive:$false)
if ($fatal.Count -gt 0) {
    throw "C-05 GameTest log contains fatal evidence: $($fatal.Line -join ' | ')"
}
$residual = @(Get-CimInstance Win32_Process | Where-Object {
    $_.Name -eq 'java.exe' -and $_.CommandLine -like "*$runDirectory*"
})
if ($residual.Count -gt 0) {
    throw "C-05 GameTest left Java process(es): $($residual.ProcessId -join ',')"
}

Write-Output 'C05_CRUSHING_GAMETEST_VERIFIED direct=true bots=true hybrid=true realInput=true realOutput=true probabilisticEvidence=true cleanup=true reload=true exactRecovery=true noDuplicates=true typedFailures=true crashReports=0 residualProcesses=0'
Write-Output "Isolated run directory: $runDirectory"
Write-Output "Test log: $log"
