$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runRoot = [IO.Path]::GetFullPath((Join-Path $root 'forge-create-1.20.1\run'))
$runDirectory = [IO.Path]::GetFullPath((Join-Path $runRoot 'create-scan-acceptance'))
$logDirectory = Join-Path $root 'work\logs'

$rootPrefix = $root.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
$runPrefix = $runRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
if (-not $runDirectory.StartsWith($runPrefix, [StringComparison]::OrdinalIgnoreCase) -or
    -not $runDirectory.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase) -or
    $runDirectory -eq $runRoot) {
    throw "Refusing to use an unsafe Create scan acceptance directory: $runDirectory"
}

# A fresh ignored world prevents prior fixtures or loaded chunks from satisfying C-01.
if (Test-Path -LiteralPath $runDirectory) {
    Remove-Item -LiteralPath $runDirectory -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $runDirectory, $logDirectory | Out-Null
@(
    '# Generated for the isolated Steve Industrial Agent C-01 acceptance.'
    'eula=true'
) | Set-Content -LiteralPath (Join-Path $runDirectory 'eula.txt') -Encoding ascii
@(
    '# Generated for an isolated, non-public C-01 acceptance server.'
    'online-mode=false'
    'server-port=0'
    'level-name=world'
    'generate-structures=false'
    'view-distance=3'
    'simulation-distance=3'
) | Set-Content -LiteralPath (Join-Path $runDirectory 'server.properties') -Encoding ascii

$started = Get-Date
$log = Join-Path $logDirectory ('create-scan-acceptance-' + $started.ToString('yyyyMMdd-HHmmss') + '.log')
& (Join-Path $PSScriptRoot 'Invoke-Gradle.ps1') `
    -PcreateScanAcceptance `
    :forge-create-1.20.1:runServer *>&1 | Tee-Object -FilePath $log

function Assert-LogContains([string]$Expected, [string]$Description) {
    if (-not (Select-String -LiteralPath $log -SimpleMatch $Expected -Quiet)) {
        throw "Missing $Description in $log. Expected: $Expected"
    }
}

Assert-LogContains 'java version 17.' 'Java 17 launch evidence'
Assert-LogContains 'Forge mod loading, version 47.4.10, for MC 1.20.1' 'Forge/Minecraft version evidence'
Assert-LogContains 'Create 6.0.6 initializing!' 'Create 6.0.6 initialization evidence'
Assert-LogContains 'CREATE_SCAN_FIXTURE_RESULT block=create:shaft namespace=create position=' 'real Create fixture observation'
Assert-LogContains 'state.axis=x radius=1 components=1' 'state and bounded-scan evidence'
Assert-LogContains 'CREATE_SCAN_COMMAND_RESULT command="/industrialagent scan create 1" return=1 message="create: 1 components; runtime=' 'registered command evidence'
Assert-LogContains 'CREATE_SCAN_UNLOADED_CHUNK_RESULT chunk=' 'unloaded-chunk probe evidence'
Assert-LogContains 'before=false code=CHUNK_NOT_LOADED after=false' 'no chunk-load evidence'
Assert-LogContains 'CREATE_SCAN_ACCEPTANCE PASS minecraft=1.20.1 forge=47.4.10 create=6.0.6-150 boundedRadius=1 commandReturn=1 unloadedChunkPreserved=true' 'C-01 PASS marker'
Assert-LogContains 'ThreadedAnvilChunkStorage: All dimensions are saved' 'clean server shutdown evidence'

$newCrashReports = @()
$crashDirectory = Join-Path $runDirectory 'crash-reports'
if (Test-Path -LiteralPath $crashDirectory) {
    $newCrashReports = @(Get-ChildItem -LiteralPath $crashDirectory -File |
        Where-Object LastWriteTime -ge $started)
}
if ($newCrashReports.Count -gt 0) {
    throw "C-01 acceptance created crash report(s): $($newCrashReports.FullName -join ', ')"
}

$fatalPatterns = @(
    '\[[^\]]+/(ERROR|FATAL)\]',
    'Exception in thread',
    'CREATE_SCAN_ACCEPTANCE FAIL',
    'Preparing crash report'
)
$fatalMatches = @(Select-String -LiteralPath $log -Pattern $fatalPatterns -CaseSensitive:$false)
if ($fatalMatches.Count -gt 0) {
    throw "C-01 acceptance log contains fatal/error evidence: $($fatalMatches.Line -join ' | ')"
}

Write-Output 'CREATE_SCAN_ACCEPTANCE_VERIFIED minecraft=1.20.1 forge=47.4.10 create=6.0.6-150 components=1 commandReturn=1 unloadedChunkPreserved=true crashReports=0'
Write-Output "Isolated run directory: $runDirectory"
Write-Output "Test log: $log"
