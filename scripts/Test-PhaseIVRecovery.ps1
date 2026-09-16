$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runRoot = [IO.Path]::GetFullPath((Join-Path $root 'forge-create-1.20.1\run'))
$runDirectory = [IO.Path]::GetFullPath(
    (Join-Path $runRoot 'phase-iv-recovery-gametest'))
$logDirectory = Join-Path $root 'work\logs'
$rootPrefix = $root.TrimEnd([IO.Path]::DirectorySeparatorChar) +
    [IO.Path]::DirectorySeparatorChar
$runPrefix = $runRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) +
    [IO.Path]::DirectorySeparatorChar
if (-not $runDirectory.StartsWith(
        $runPrefix, [StringComparison]::OrdinalIgnoreCase) -or
        -not $runDirectory.StartsWith(
            $rootPrefix, [StringComparison]::OrdinalIgnoreCase) -or
        $runDirectory.StartsWith(
            'D:\PCL2\', [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing unsafe Phase IV recovery GameTest directory: $runDirectory"
}
if (Test-Path -LiteralPath $runDirectory) {
    Remove-Item -LiteralPath $runDirectory -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $runDirectory, $logDirectory |
    Out-Null
@('# Repository-owned isolated Phase IV recovery GameTest.', 'eula=true') |
    Set-Content -LiteralPath (Join-Path $runDirectory 'eula.txt') -Encoding ascii
'steve-industrial:isolated-execution/v1' |
    Set-Content -LiteralPath (
        Join-Path $runDirectory '.steve-industrial-execution-test') -Encoding ascii
@(
    '# Repository-owned isolated Phase IV recovery GameTest.'
    'online-mode=false'
    'server-port=0'
    'level-name=world'
    'level-seed=steve-industrial-phase-iv-recovery-v1'
    'level-type=minecraft:flat'
    'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}'
    'generate-structures=false'
    'view-distance=6'
    'simulation-distance=6'
) | Set-Content -LiteralPath (
    Join-Path $runDirectory 'server.properties') -Encoding ascii

$started = Get-Date
$log = Join-Path $logDirectory (
    'phase-iv-recovery-gametest-' +
    $started.ToString('yyyyMMdd-HHmmss') + '.log')
& (Join-Path $PSScriptRoot 'Invoke-Gradle.ps1') `
    -PgoalDrivenExecutionGameTest `
    -PphaseIvRecoveryGameTestOnly `
    :forge-create-1.20.1:runGameTestServer *>&1 |
    Tee-Object -FilePath $log

function Assert-LogContains([string]$Expected, [string]$Description) {
    if (-not (Select-String -LiteralPath $log -SimpleMatch $Expected -Quiet)) {
        throw "Missing $Description in $log. Expected: $Expected"
    }
}

Assert-LogContains 'java version 17.' 'Java 17 evidence'
Assert-LogContains 'Forge mod loading, version 47.4.10, for MC 1.20.1' 'Forge evidence'
Assert-LogContains 'Create 6.0.6 initializing!' 'Create evidence'
foreach ($capability in @('C07', 'C06', 'C09', 'C08', 'C10')) {
    Assert-LogContains (
        $capability + '_EXACT_RECOVERY PASS boundary=BUILD_MID ' +
        'exactRescan=true checkpointCodec=true repeatedPlacements=0 ' +
        'repeatedInputs=0 repeatedOutput=0') (
        $capability + ' exact recovery')
    Assert-LogContains (
        $capability + '_CANCEL_MATRIX PASS direct=EXECUTION_CANCELLED ' +
        'bots=EXECUTION_CANCELLED hybrid=EXECUTION_CANCELLED ' +
        'exactInputReturn=true installationEscrowRestored=true ' +
        'deliveryEmpty=true workersRemoved=true ' +
        'repeatedTickRefused=true privateContainerReads=0 ' +
        'playerInventoryReads=0') (
        $capability + ' three-mode cancellation')
}
Assert-LogContains 'All 10 required tests passed' 'ten required resilience GameTests'
Assert-LogContains 'ThreadedAnvilChunkStorage: All dimensions are saved' 'clean shutdown'

$crashDirectory = Join-Path $runDirectory 'crash-reports'
$newCrashes = if (Test-Path -LiteralPath $crashDirectory) {
    @(Get-ChildItem -LiteralPath $crashDirectory -File |
        Where-Object LastWriteTime -ge $started)
} else { @() }
if ($newCrashes.Count -gt 0) {
    throw "Recovery GameTest created crash report(s): $($newCrashes.FullName -join ', ')"
}
$fatalPatterns = @(
    '\[[^\]]+/(ERROR|FATAL)\]',
    'Exception in thread',
    'Preparing crash report',
    'recovered execution failed',
    'test failed',
    'Game test failed'
)
$fatal = @(Select-String -LiteralPath $log -Pattern $fatalPatterns -CaseSensitive:$false)
if ($fatal.Count -gt 0) {
    throw "Recovery GameTest log contains fatal evidence: $($fatal.Line -join ' | ')"
}
$residual = @(Get-CimInstance Win32_Process | Where-Object {
    $_.Name -eq 'java.exe' -and $_.CommandLine -like "*$runDirectory*"
})
if ($residual.Count -gt 0) {
    throw "Recovery GameTest left Java process(es): $($residual.ProcessId -join ',')"
}

Write-Output (
    'PHASE_IV_RECOVERY_GAMETEST_VERIFIED capabilities=C07,C06,C09,C08,C10 ' +
    'boundary=BUILD_MID exactRescan=true checkpointCodec=true ' +
    'repeatedPlacements=0 repeatedInputs=0 repeatedOutput=0 ' +
    'directCancel=true botsCancel=true hybridCancel=true exactInputReturn=true ' +
    'crashReports=0 residualProcesses=0')
Write-Output "Isolated run directory: $runDirectory"
Write-Output "Test log: $log"
