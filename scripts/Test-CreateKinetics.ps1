$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runRoot = [IO.Path]::GetFullPath((Join-Path $root 'forge-create-1.20.1\run'))
$runDirectory = [IO.Path]::GetFullPath((Join-Path $runRoot 'create-kinetics-acceptance'))
$logDirectory = Join-Path $root 'work\logs'

$rootPrefix = $root.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
$runPrefix = $runRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
if (-not $runDirectory.StartsWith($runPrefix, [StringComparison]::OrdinalIgnoreCase) -or
    -not $runDirectory.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase) -or
    $runDirectory -eq $runRoot) {
    throw "Refusing to use an unsafe Create kinetics acceptance directory: $runDirectory"
}

# Fresh repository-owned world and explicit fixture-only stress values make all states reproducible.
if (Test-Path -LiteralPath $runDirectory) {
    Remove-Item -LiteralPath $runDirectory -Recurse -Force
}
$serverConfigDirectory = Join-Path $runDirectory 'world\serverconfig'
New-Item -ItemType Directory -Force -Path $runDirectory, $serverConfigDirectory, $logDirectory | Out-Null
@(
    '# Generated for the isolated Steve Industrial Agent C-02 acceptance.'
    'eula=true'
) | Set-Content -LiteralPath (Join-Path $runDirectory 'eula.txt') -Encoding ascii
@(
    '# Generated for an isolated, non-public C-02 acceptance server.'
    'online-mode=false'
    'server-port=0'
    'level-name=world'
    'level-seed=steve-industrial-c02-v1'
    'generate-structures=false'
    'view-distance=3'
    'simulation-distance=3'
) | Set-Content -LiteralPath (Join-Path $runDirectory 'server.properties') -Encoding ascii
@(
    '# Fixture-only Create 6.0.6 stress values. Production defaults are not changed.'
    '[kinetics]'
    'disableStress = false'
    '[kinetics.stressValues]'
    '[kinetics.stressValues.v2]'
    '[kinetics.stressValues.v2.impact]'
    'encased_fan = 2.0'
    '[kinetics.stressValues.v2.capacity]'
    'creative_motor = 1.0'
) | Set-Content -LiteralPath (Join-Path $serverConfigDirectory 'create-server.toml') -Encoding ascii

$started = Get-Date
$log = Join-Path $logDirectory ('create-kinetics-acceptance-' + $started.ToString('yyyyMMdd-HHmmss') + '.log')
& (Join-Path $PSScriptRoot 'Invoke-Gradle.ps1') `
    -PcreateKineticsAcceptance `
    :forge-create-1.20.1:runServer *>&1 | Tee-Object -FilePath $log

function Assert-LogContains([string]$Expected, [string]$Description) {
    if (-not (Select-String -LiteralPath $log -SimpleMatch $Expected -Quiet)) {
        throw "Missing $Description in $log. Expected: $Expected"
    }
}

Assert-LogContains 'java version 17.' 'Java 17 launch evidence'
Assert-LogContains 'Forge mod loading, version 47.4.10, for MC 1.20.1' 'Forge/Minecraft version evidence'
Assert-LogContains 'Create 6.0.6 initializing!' 'Create 6.0.6 initialization evidence'
Assert-LogContains 'CREATE_KINETICS_STATE state=STOPPED block=create:shaft' 'real stopped state'
Assert-LogContains 'speedRpm=0.0 direction=STATIONARY capacity=0.0 load=0.0 overstressed=false' 'stopped values'
Assert-LogContains 'CREATE_KINETICS_STATE state=POWERED_POSITIVE block=create:creative_motor' 'real positive powered state'
Assert-LogContains 'speedRpm=16.0 direction=POSITIVE capacity=16.0 load=0.0 overstressed=false' 'positive values'
Assert-LogContains 'CREATE_KINETICS_STATE state=POWERED_NEGATIVE block=create:creative_motor' 'real negative powered state'
Assert-LogContains 'speedRpm=16.0 direction=NEGATIVE capacity=16.0 load=0.0 overstressed=false' 'negative values'
Assert-LogContains 'CREATE_KINETICS_STATE state=OVERSTRESSED block=create:creative_motor' 'real overstressed state'
Assert-LogContains 'speedRpm=0.0 direction=STATIONARY capacity=16.0 load=32.0 overstressed=true' 'overstressed values'
Assert-LogContains 'CREATE_KINETICS_WRONG_THREAD code=WRONG_THREAD' 'non-server-thread rejection'
Assert-LogContains 'CREATE_KINETICS_UNLOADED_CHUNK chunk=' 'unloaded-chunk probe'
Assert-LogContains 'before=false code=CHUNK_NOT_LOADED after=false' 'no chunk-load evidence'
Assert-LogContains 'CREATE_KINETICS_REPLAY PASS snapshots=4 canonicalRoundTrips=4' 'deterministic replay evidence'
Assert-LogContains 'CREATE_KINETICS_ACCEPTANCE PASS minecraft=1.20.1 forge=47.4.10 create=6.0.6-150' 'C-02 PASS marker'
Assert-LogContains 'ThreadedAnvilChunkStorage: All dimensions are saved' 'clean server shutdown evidence'

$newCrashReports = @()
$crashDirectory = Join-Path $runDirectory 'crash-reports'
if (Test-Path -LiteralPath $crashDirectory) {
    $newCrashReports = @(Get-ChildItem -LiteralPath $crashDirectory -File |
        Where-Object LastWriteTime -ge $started)
}
if ($newCrashReports.Count -gt 0) {
    throw "C-02 acceptance created crash report(s): $($newCrashReports.FullName -join ', ')"
}

$fatalPatterns = @(
    '\[[^\]]+/(ERROR|FATAL)\]',
    'Exception in thread',
    'CREATE_KINETICS_ACCEPTANCE FAIL',
    'Preparing crash report'
)
$fatalMatches = @(Select-String -LiteralPath $log -Pattern $fatalPatterns -CaseSensitive:$false)
if ($fatalMatches.Count -gt 0) {
    throw "C-02 acceptance log contains fatal/error evidence: $($fatalMatches.Line -join ' | ')"
}

Write-Output 'CREATE_KINETICS_ACCEPTANCE_VERIFIED minecraft=1.20.1 forge=47.4.10 create=6.0.6-150 states=4 wrongThreadRejected=true unloadedChunkPreserved=true replayDeterministic=true crashReports=0'
Write-Output "Isolated run directory: $runDirectory"
Write-Output "Test log: $log"
