$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runRoot = [IO.Path]::GetFullPath((Join-Path $root 'forge-create-1.20.1\run'))
$runDirectory = [IO.Path]::GetFullPath((Join-Path $runRoot 'phase-iv-integrated-visible-gametest'))
$logDirectory = Join-Path $root 'work\logs'
$rootPrefix = $root.TrimEnd([IO.Path]::DirectorySeparatorChar) +
    [IO.Path]::DirectorySeparatorChar
$runPrefix = $runRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) +
    [IO.Path]::DirectorySeparatorChar
if (-not $runDirectory.StartsWith($runPrefix, [StringComparison]::OrdinalIgnoreCase) -or
        -not $runDirectory.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase) -or
        $runDirectory.StartsWith('D:\PCL2\', [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing unsafe integrated visible GameTest directory: $runDirectory"
}

$preexisting = @(Get-CimInstance Win32_Process | Where-Object {
    $_.Name -eq 'java.exe' -and
    $_.CommandLine -match '(Minecraft|forge|runGameTestServer|server\.jar)'
})
if ($preexisting.Count -gt 0) {
    throw "Integrated GameTest slot is not free; Java process(es): $($preexisting.ProcessId -join ',')"
}

if (Test-Path -LiteralPath $runDirectory) {
    Remove-Item -LiteralPath $runDirectory -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $runDirectory, $logDirectory | Out-Null
@('# Repository-owned isolated integrated visible GameTest.', 'eula=true') |
    Set-Content -LiteralPath (Join-Path $runDirectory 'eula.txt') -Encoding ascii
'steve-industrial:isolated-execution/v1' |
    Set-Content -LiteralPath (
        Join-Path $runDirectory '.steve-industrial-execution-test') -Encoding ascii
'steve-industrial:isolated-bot/v1' |
    Set-Content -LiteralPath (
        Join-Path $runDirectory '.steve-industrial-bot-test') -Encoding ascii
'steve-industrial:isolated-site-preparation/v1' |
    Set-Content -LiteralPath (
        Join-Path $runDirectory '.steve-industrial-site-preparation-test') -Encoding ascii
@(
    '# Repository-owned isolated integrated visible GameTest.'
    'online-mode=false'
    'server-port=0'
    'level-name=world'
    'level-seed=steve-industrial-phase-iv-integrated-visible-v1'
    'level-type=minecraft:flat'
    'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}'
    'generate-structures=false'
    'view-distance=6'
    'simulation-distance=6'
) | Set-Content -LiteralPath (
    Join-Path $runDirectory 'server.properties') -Encoding ascii

$started = Get-Date
$log = Join-Path $logDirectory (
    'phase-iv-integrated-visible-gametest-' +
    $started.ToString('yyyyMMdd-HHmmss') + '.log')
& (Join-Path $PSScriptRoot 'Invoke-Gradle.ps1') `
    -PphaseIvIntegratedVisibleGameTest `
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
Assert-LogContains 'SITE_TO_PRODUCTION_C07 PASS' 'prepared-site production bridge'
Assert-LogContains 'BOT_FLEET_SCALED_3 PASS' 'three-Bot physical fleet'
Assert-LogContains 'BOT_FLEET_SCALED_5 PASS' 'five-Bot physical fleet'
foreach ($capability in @('C05', 'C07', 'C06', 'C09', 'C08', 'C10')) {
    Assert-LogContains ($capability + '_THREE_MODE_EQUIVALENCE PASS') (
        $capability + ' Direct/Bots/Hybrid equivalence')
}
foreach ($composite in @('COMPOSITE_01_DIRECT PASS', 'COMPOSITE_01_BOTS PASS',
        'COMPOSITE_01_HYBRID PASS', 'COMPOSITE_02_DIRECT PASS',
        'COMPOSITE_02_BOTS PASS', 'COMPOSITE_02_HYBRID PASS',
        'COMPOSITE_03_DIRECT PASS', 'COMPOSITE_03_BOTS PASS',
        'COMPOSITE_03_HYBRID PASS')) {
    Assert-LogContains $composite 'Composite physical success evidence'
}
Assert-LogContains 'C10_OWNED_WORKPIECE_DIRECT_GRAPH PASS' 'bounded C-10 Direct expansion'
Assert-LogContains 'C10_OWNED_WORKPIECE_BOT_GRAPH PASS' 'bounded C-10 Bots expansion'
Assert-LogContains 'C10_OWNED_WORKPIECE_HYBRID_GRAPH PASS' 'bounded C-10 Hybrid expansion'
Assert-LogContains 'C10_OWNED_WORKPIECE_REAL PASS' 'real bounded C-10 workpiece mutation'
Assert-LogContains 'All 22 required tests passed' '22 integrated success tests'
Assert-LogContains 'ThreadedAnvilChunkStorage: All dimensions are saved' 'clean shutdown'

$crashDirectory = Join-Path $runDirectory 'crash-reports'
$newCrashes = if (Test-Path -LiteralPath $crashDirectory) {
    @(Get-ChildItem -LiteralPath $crashDirectory -File |
        Where-Object LastWriteTime -ge $started)
} else { @() }
if ($newCrashes.Count -gt 0) {
    throw "Integrated GameTest created crash report(s): $($newCrashes.FullName -join ', ')"
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
    throw "Integrated GameTest log contains fatal evidence: $($fatal.Line -join ' | ')"
}
$residual = @(Get-CimInstance Win32_Process | Where-Object {
    $_.Name -eq 'java.exe' -and $_.CommandLine -like "*$runDirectory*"
})
if ($residual.Count -gt 0) {
    throw "Integrated GameTest left Java process(es): $($residual.ProcessId -join ',')"
}

Write-Output (
    'PHASE_IV_INTEGRATED_VISIBLE_GAMETEST_VERIFIED tests=22 ' +
    'siteToProduction=true capabilities=C05-C10 modes=direct,bots,hybrid ' +
    'botFleet=3,5 composites=01,02,03 c10BoundedExpansion=true ' +
    'cleanup=true crashReports=0 residualProcesses=0')
Write-Output "Isolated run directory: $runDirectory"
Write-Output "Test log: $log"
