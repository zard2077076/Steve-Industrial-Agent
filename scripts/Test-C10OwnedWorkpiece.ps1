$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runRoot = [IO.Path]::GetFullPath((Join-Path $root 'forge-create-1.20.1\run'))
$runDirectory = [IO.Path]::GetFullPath(
    (Join-Path $runRoot 'c10-owned-workpiece-gametest'))
$logDirectory = Join-Path $root 'work\logs'
$rootPrefix = $root.TrimEnd([IO.Path]::DirectorySeparatorChar) +
    [IO.Path]::DirectorySeparatorChar
$runPrefix = $runRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) +
    [IO.Path]::DirectorySeparatorChar
if (-not $runDirectory.StartsWith($runPrefix, [StringComparison]::OrdinalIgnoreCase) -or
        -not $runDirectory.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase) -or
        $runDirectory.StartsWith('D:\PCL2\', [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing unsafe C-10 owned-workpiece GameTest directory: $runDirectory"
}
if (Test-Path -LiteralPath $runDirectory) {
    Remove-Item -LiteralPath $runDirectory -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $runDirectory, $logDirectory | Out-Null
@('# Repository-owned isolated C-10 owned-workpiece GameTest.', 'eula=true') |
    Set-Content -LiteralPath (Join-Path $runDirectory 'eula.txt') -Encoding ascii
'steve-industrial:isolated-execution/v1' |
    Set-Content -LiteralPath (
        Join-Path $runDirectory '.steve-industrial-execution-test') -Encoding ascii
@(
    '# Repository-owned isolated C-10 owned-workpiece GameTest.'
    'online-mode=false'
    'server-port=0'
    'level-name=world'
    'level-seed=steve-industrial-c10-owned-workpiece-v1'
    'level-type=minecraft:flat'
    'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}'
    'generate-structures=false'
    'view-distance=6'
    'simulation-distance=6'
) | Set-Content -LiteralPath (
    Join-Path $runDirectory 'server.properties') -Encoding ascii

$started = Get-Date
$log = Join-Path $logDirectory (
    'c10-owned-workpiece-gametest-' +
    $started.ToString('yyyyMMdd-HHmmss') + '.log')
& (Join-Path $PSScriptRoot 'Invoke-Gradle.ps1') `
    -PgoalDrivenExecutionGameTest `
    -Pc10OwnedWorkpieceGameTestOnly `
    :forge-create-1.20.1:runGameTestServer *>&1 | Tee-Object -FilePath $log

function Assert-LogContains([string]$Expected, [string]$Description) {
    if (-not (Select-String -LiteralPath $log -SimpleMatch $Expected -Quiet)) {
        throw "Missing $Description in $log. Expected: $Expected"
    }
}

Assert-LogContains 'java version 17.' 'Java 17 evidence'
Assert-LogContains 'Forge mod loading, version 47.4.10, for MC 1.20.1' 'Forge evidence'
Assert-LogContains 'Create 6.0.6 initializing!' 'Create evidence'
Assert-LogContains 'C10_OWNED_WORKPIECE_DIRECT_GRAPH PASS' 'Direct executor three-task graph'
Assert-LogContains 'C10_OWNED_WORKPIECE_DIRECT_CANCEL PASS' 'Direct executor exact cancellation'
Assert-LogContains 'C10_OWNED_WORKPIECE_BOT_GRAPH PASS' 'existing Steve/Alex Bot fleet graph'
Assert-LogContains 'C10_OWNED_WORKPIECE_HYBRID_GRAPH PASS' 'Hybrid shared Direct/Bot routing graph'
Assert-LogContains 'C10_OWNED_WORKPIECE_REAL PASS' 'real Deployer workpiece transition'
Assert-LogContains 'C10_OWNED_WORKPIECE_RELOAD PASS' 'exact pre-resource reload recovery'
Assert-LogContains 'C10_OWNED_WORKPIECE_CONTAINER_REFUSAL PASS' 'private container refusal'
Assert-LogContains 'C10_OWNED_WORKPIECE_ENTITY_REFUSAL PASS' 'entity refusal'
Assert-LogContains 'C10_OWNED_WORKPIECE_NBT_REFUSAL PASS' 'unknown NBT refusal'
Assert-LogContains 'C10_OWNED_WORKPIECE_CANCEL PASS' 'exact cancellation restoration'
Assert-LogContains 'All 10 required tests passed' 'ten required owned-workpiece GameTests'
Assert-LogContains 'ThreadedAnvilChunkStorage: All dimensions are saved' 'clean shutdown'

$crashDirectory = Join-Path $runDirectory 'crash-reports'
$newCrashes = if (Test-Path -LiteralPath $crashDirectory) {
    @(Get-ChildItem -LiteralPath $crashDirectory -File |
        Where-Object LastWriteTime -ge $started)
} else { @() }
if ($newCrashes.Count -gt 0) {
    throw "C-10 owned-workpiece GameTest created crash report(s): $($newCrashes.FullName -join ', ')"
}
$fatalPatterns = @(
    '\[[^\]]+/(ERROR|FATAL)\]'
    'Exception in thread'
    'Preparing crash report'
    'test failed'
    'Game test failed'
)
$fatal = @(Select-String -LiteralPath $log -Pattern $fatalPatterns -CaseSensitive:$false)
if ($fatal.Count -gt 0) {
    throw "C-10 owned-workpiece log contains fatal evidence: $($fatal.Line -join ' | ')"
}
$residual = @(Get-CimInstance Win32_Process | Where-Object {
    $_.Name -eq 'java.exe' -and $_.CommandLine -like "*$runDirectory*"
})
if ($residual.Count -gt 0) {
    throw "C-10 owned-workpiece left Java process(es): $($residual.ProcessId -join ',')"
}

Write-Output 'C10_OWNED_WORKPIECE_GAMETEST_VERIFIED directGraph=true directCancel=true botFleet=true existingSteveAlex=true hybridRoutes=true provenance=true realDeployer=true allowlist=true arbitraryPosition=false privateContainer=false entity=false playerInventory=false unknownNbt=false cancel=true reload=true exactRecovery=true cleanup=true crashReports=0 residualProcesses=0'
Write-Output "Isolated run directory: $runDirectory"
Write-Output "Test log: $log"
