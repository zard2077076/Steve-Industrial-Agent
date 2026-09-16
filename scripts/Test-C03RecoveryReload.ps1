param(
    [ValidateRange(120, 900)]
    [int]$TimeoutSecondsPerPhase = 420
)

$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runRoot = [IO.Path]::GetFullPath((Join-Path $root 'forge-create-1.20.1\run'))
$runDirectory = [IO.Path]::GetFullPath((Join-Path $runRoot 'c03-recovery-reload-acceptance'))
$logDirectory = Join-Path $root 'work\logs'
$rootPrefix = $root.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
$runPrefix = $runRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
if (-not $runDirectory.StartsWith($runPrefix, [StringComparison]::OrdinalIgnoreCase) -or
    -not $runDirectory.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase) -or
    $runDirectory -eq $runRoot) {
    throw "Refusing to use an unsafe C-03 recovery directory: $runDirectory"
}

if (Test-Path -LiteralPath $runDirectory) {
    Remove-Item -LiteralPath $runDirectory -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $runDirectory, $logDirectory | Out-Null
'steve-industrial:isolated-execution/v1' | Set-Content -LiteralPath `
    (Join-Path $runDirectory '.steve-industrial-execution-test') -Encoding ascii
@(
    '# Generated for the isolated Steve Industrial Agent C-03 recovery acceptance.'
    'eula=true'
) | Set-Content -LiteralPath (Join-Path $runDirectory 'eula.txt') -Encoding ascii
@(
    '# Generated for an isolated, non-public C-03 save/reload acceptance server.'
    'online-mode=false'
    'server-port=0'
    'level-name=world'
    'level-seed=steve-industrial-c03-recovery-v1'
    'generate-structures=false'
    'view-distance=3'
    'simulation-distance=3'
) | Set-Content -LiteralPath (Join-Path $runDirectory 'server.properties') -Encoding ascii

$started = Get-Date
$timestamp = $started.ToString('yyyyMMdd-HHmmss')
$log = Join-Path $logDirectory ("c03-recovery-reload-acceptance-$timestamp.log")
$invokeGradle = Join-Path $PSScriptRoot 'Invoke-Gradle.ps1'
$powerShell = (Get-Process -Id $PID).Path

function Invoke-BoundedPhase([ValidateSet('write', 'read')][string]$Phase) {
    $stdout = Join-Path $logDirectory ("c03-recovery-$Phase-$timestamp.stdout.tmp")
    $stderr = Join-Path $logDirectory ("c03-recovery-$Phase-$timestamp.stderr.tmp")
    $arguments = @(
        '-NoProfile'
        '-ExecutionPolicy'
        'Bypass'
        '-File'
        ('"' + $invokeGradle + '"')
        "-Pc03RecoveryReloadAcceptancePhase=$Phase"
        ':forge-create-1.20.1:runServer'
    )
    $process = Start-Process -FilePath $powerShell `
        -ArgumentList $arguments `
        -WorkingDirectory $root `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr `
        -WindowStyle Hidden `
        -PassThru
    $completed = $process.WaitForExit($TimeoutSecondsPerPhase * 1000)
    if (-not $completed) {
        & taskkill.exe /PID $process.Id /T /F | Out-Null
        $process.WaitForExit()
    }
    $combined = [Collections.Generic.List[string]]::new()
    $combined.Add("===== C-03 RECOVERY PHASE $($Phase.ToUpperInvariant()) =====")
    if (Test-Path -LiteralPath $stdout) {
        $combined.AddRange([string[]](Get-Content -LiteralPath $stdout))
    }
    if (Test-Path -LiteralPath $stderr) {
        $stderrLines = [string[]](Get-Content -LiteralPath $stderr)
        if ($stderrLines.Count -gt 0) {
            $combined.Add('===== STDERR =====')
            $combined.AddRange($stderrLines)
        }
    }
    $combined | Add-Content -LiteralPath $log -Encoding utf8
    Remove-Item -LiteralPath $stdout, $stderr -Force -ErrorAction SilentlyContinue
    if (-not $completed) {
        throw "C-03 $Phase phase exceeded $TimeoutSecondsPerPhase seconds and was terminated. Log: $log"
    }
    if ($process.ExitCode -ne 0) {
        throw "C-03 $Phase phase failed with exit code $($process.ExitCode). Log: $log"
    }
}

Invoke-BoundedPhase 'write'
$savedDataFile = Join-Path $runDirectory 'world\data\steve_industrial_c03_recovery_acceptance.dat'
if (-not (Test-Path -LiteralPath $savedDataFile -PathType Leaf)) {
    throw "C-03 write phase did not create SavedData: $savedDataFile"
}
Invoke-BoundedPhase 'read'

function Assert-LogContains([string]$Expected, [string]$Description) {
    if (-not (Select-String -LiteralPath $log -SimpleMatch $Expected -Quiet)) {
        throw "Missing $Description in $log. Expected: $Expected"
    }
}

Assert-LogContains 'java version 17.' 'Java 17 launch evidence'
Assert-LogContains 'Forge mod loading, version 47.4.10, for MC 1.20.1' 'Forge/Minecraft evidence'
Assert-LogContains 'C03_RECOVERY_RELOAD_WRITE PASS' 'safe BUILD-complete checkpoint'
Assert-LogContains 'currentStep=steve_industrial:c03/step/power stepState=READY completedSteps=1 placements=17 blockChanges=17 resourceChanges=0' 'saved handler and step cursor'
Assert-LogContains 'C03_RECOVERY_RELOAD_DISCOVERED PASS' 'post-reload discovery and exact rescan'
Assert-LogContains 'verifiedPositions=17 rawTransientRejected=true normalizedKineticSnapshots=4 blindResume=false' 'bounded Create 6.0.6-normalized rescan-before-resume evidence'
Assert-LogContains 'C03_RECOVERY_RELOAD_STALE PASS code=steve_industrial:recovery/stale_session reason=WORLD_STATE_CHANGED' 'typed stale-session result'
Assert-LogContains 'C03_RECOVERY_RELOAD_RESUME_READY PASS' 'production handler resume evidence'
Assert-LogContains 'placementCursor=17 journalEntries=17 repeatedPlacements=0 repeatedInputs=0' 'no duplicate work before resume'
Assert-LogContains 'C03_RECOVERY_RELOAD_PROCESS PASS' 'real resumed Create recipe evidence'
Assert-LogContains 'consumed=1 output=minecraft:gravel observed=1 blockChanges=17 inputChanges=1 processChanges=1' 'exact input/output/journal cardinality'
Assert-LogContains 'C03_RECOVERY_RELOAD_ACCEPTANCE PASS' 'final C-03 recovery result'
Assert-LogContains 'noDuplicatePlacement=true noDuplicateInput=true noDuplicateOutput=true' 'no-duplication result'

$saveMarkers = @(Select-String -LiteralPath $log -SimpleMatch 'ThreadedAnvilChunkStorage: All dimensions are saved')
if ($saveMarkers.Count -lt 2) {
    throw "Expected both C-03 processes to save every dimension; observed $($saveMarkers.Count)"
}
$buildMarkers = @(Select-String -LiteralPath $log -SimpleMatch 'BUILD SUCCESSFUL')
if ($buildMarkers.Count -lt 2) {
    throw "Expected both C-03 Gradle server runs to succeed; observed $($buildMarkers.Count)"
}

$newCrashReports = @()
$crashDirectory = Join-Path $runDirectory 'crash-reports'
if (Test-Path -LiteralPath $crashDirectory) {
    $newCrashReports = @(Get-ChildItem -LiteralPath $crashDirectory -File |
        Where-Object LastWriteTime -ge $started)
}
if ($newCrashReports.Count -gt 0) {
    throw "C-03 recovery created crash reports: $($newCrashReports.FullName -join ', ')"
}

$fatalPatterns = @(
    '\[[^\]]+/(ERROR|FATAL)\]'
    'Exception in thread'
    'C03_RECOVERY_RELOAD_ACCEPTANCE FAIL'
    'Preparing crash report'
    'Mixin apply failed'
    'NoClassDefFoundError'
    'ClassNotFoundException'
    'BUILD FAILED'
)
$fatalMatches = @(Select-String -LiteralPath $log -Pattern $fatalPatterns -CaseSensitive:$false)
if ($fatalMatches.Count -gt 0) {
    throw "C-03 recovery log contains error evidence: $($fatalMatches.Line -join ' | ')"
}

Write-Output 'C03_RECOVERY_RELOAD_ACCEPTANCE_VERIFIED minecraft=1.20.1 forge=47.4.10 create=6.0.6 processRestarts=2 exactRescan=true staleRejected=true handlerCursorRecovered=true stepCursorRecovered=true realRecipe=true inputConsumed=1 outputObserved=1 noDuplicatePlacement=true noDuplicateInput=true noDuplicateOutput=true crashReports=0'
Write-Output "SavedData file: $savedDataFile"
Write-Output "Isolated run directory: $runDirectory"
Write-Output "Test log: $log"
