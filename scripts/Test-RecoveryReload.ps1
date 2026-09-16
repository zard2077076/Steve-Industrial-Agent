$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runRoot = [IO.Path]::GetFullPath((Join-Path $root 'forge-create-1.20.1\run'))
$runDirectory = [IO.Path]::GetFullPath((Join-Path $runRoot 'recovery-reload-acceptance'))
$logDirectory = Join-Path $root 'work\logs'

$rootPrefix = $root.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
$runPrefix = $runRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
if (-not $runDirectory.StartsWith($runPrefix, [StringComparison]::OrdinalIgnoreCase) -or
    -not $runDirectory.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase) -or
    $runDirectory -eq $runRoot) {
    throw "Refusing to use an unsafe recovery reload acceptance directory: $runDirectory"
}

if (Test-Path -LiteralPath $runDirectory) {
    Remove-Item -LiteralPath $runDirectory -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $runDirectory, $logDirectory | Out-Null
@(
    '# Generated for the isolated Steve Industrial Agent G-11 acceptance.'
    'eula=true'
) | Set-Content -LiteralPath (Join-Path $runDirectory 'eula.txt') -Encoding ascii
@(
    '# Generated for an isolated, non-public G-11 save/reload acceptance server.'
    'online-mode=false'
    'server-port=0'
    'level-name=world'
    'level-seed=steve-industrial-g11-v1'
    'generate-structures=false'
    'view-distance=3'
    'simulation-distance=3'
) | Set-Content -LiteralPath (Join-Path $runDirectory 'server.properties') -Encoding ascii

$started = Get-Date
$log = Join-Path $logDirectory ('recovery-reload-acceptance-' + $started.ToString('yyyyMMdd-HHmmss') + '.log')

& (Join-Path $PSScriptRoot 'Invoke-Gradle.ps1') `
    '-PrecoveryReloadAcceptancePhase=write' `
    :forge-create-1.20.1:runServer *>&1 | Tee-Object -FilePath $log

$savedDataFile = Join-Path $runDirectory 'world\data\steve_industrial_recovery_acceptance.dat'
if (-not (Test-Path -LiteralPath $savedDataFile -PathType Leaf)) {
    throw "G-11 write phase did not create the expected SavedData file: $savedDataFile"
}

& (Join-Path $PSScriptRoot 'Invoke-Gradle.ps1') `
    '-PrecoveryReloadAcceptancePhase=read' `
    :forge-create-1.20.1:runServer *>&1 | Tee-Object -FilePath $log -Append

function Assert-LogContains([string]$Expected, [string]$Description) {
    if (-not (Select-String -LiteralPath $log -SimpleMatch $Expected -Quiet)) {
        throw "Missing $Description in $log. Expected: $Expected"
    }
}

Assert-LogContains 'java version 17.' 'Java 17 launch evidence'
Assert-LogContains 'Forge mod loading, version 47.4.10, for MC 1.20.1' 'Forge/Minecraft version evidence'
Assert-LogContains 'RECOVERY_RELOAD_WRITE PASS session=steve_industrial:acceptance/recovery_reload_session' 'persisted write-phase checkpoint'
Assert-LogContains 'currentStep=steve_industrial:acceptance/recovery_resume completedSteps=1 modifiedPositions=1' 'saved session progress and position evidence'
Assert-LogContains 'RECOVERY_RELOAD_DISCOVERED PASS session=steve_industrial:acceptance/recovery_reload_session' 'old-session discovery after process reload'
Assert-LogContains 'completedSteps=1 modifiedPositions=1 blindResume=false' 'rescan-before-resume evidence'
Assert-LogContains 'RECOVERY_RELOAD_RESUME PASS session=steve_industrial:acceptance/recovery_reload_session' 'consistent-state resume evidence'
Assert-LogContains 'verifiedPositions=1 actionInvocations=1 worldMutation=false' 'bounded resumed execution evidence'
Assert-LogContains 'RECOVERY_RELOAD_STALE PASS code=steve_industrial:recovery/stale_session reason=WORLD_STATE_CHANGED' 'typed stale-session rejection evidence'
Assert-LogContains 'noMutation=true' 'stale reconciliation no-mutation evidence'
Assert-LogContains 'RECOVERY_RELOAD_ACCEPTANCE PASS session=steve_industrial:acceptance/recovery_reload_session savedData=true worldReloaded=true rescanRequired=true consistentResume=true staleRejected=true noBlindResume=true' 'final G-11 acceptance marker'

$saveMarkers = @(Select-String -LiteralPath $log -SimpleMatch 'ThreadedAnvilChunkStorage: All dimensions are saved')
if ($saveMarkers.Count -lt 2) {
    throw "Expected both G-11 server processes to save every dimension; observed $($saveMarkers.Count) markers"
}
$buildMarkers = @(Select-String -LiteralPath $log -SimpleMatch 'BUILD SUCCESSFUL')
if ($buildMarkers.Count -lt 2) {
    throw "Expected both G-11 Gradle server runs to succeed; observed $($buildMarkers.Count) markers"
}

$newCrashReports = @()
$crashDirectory = Join-Path $runDirectory 'crash-reports'
if (Test-Path -LiteralPath $crashDirectory) {
    $newCrashReports = @(Get-ChildItem -LiteralPath $crashDirectory -File |
        Where-Object LastWriteTime -ge $started)
}
if ($newCrashReports.Count -gt 0) {
    throw "G-11 acceptance created crash report(s): $($newCrashReports.FullName -join ', ')"
}

$fatalPatterns = @(
    '\[[^\]]+/(ERROR|FATAL)\]'
    'Exception in thread'
    'RECOVERY_RELOAD_ACCEPTANCE FAIL'
    'Preparing crash report'
    'BUILD FAILED'
)
$fatalMatches = @(Select-String -LiteralPath $log -Pattern $fatalPatterns -CaseSensitive:$false)
if ($fatalMatches.Count -gt 0) {
    throw "G-11 acceptance log contains fatal/error evidence: $($fatalMatches.Line -join ' | ')"
}

Write-Output 'RECOVERY_RELOAD_ACCEPTANCE_VERIFIED minecraft=1.20.1 forge=47.4.10 persistedCheckpoint=true processRestarts=2 oldSessionDiscovered=true rescanRequired=true consistentResume=true staleRejected=true noBlindResume=true noMutationOnStale=true crashReports=0'
Write-Output "SavedData file: $savedDataFile"
Write-Output "Isolated run directory: $runDirectory"
Write-Output "Test log: $log"
