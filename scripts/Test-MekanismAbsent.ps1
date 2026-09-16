$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runDirectory = Join-Path $root 'forge-create-1.20.1\run\mekanism-absent'
$logDirectory = Join-Path $root 'work\logs'
$expectedMarker = 'MEKANISM_ABSENT_SMOKE PASS code=UNSUPPORTED_RUNTIME detail="Optional mod is not loaded: mekanism"'

if (-not ([IO.Path]::GetFullPath($runDirectory).StartsWith($root + [IO.Path]::DirectorySeparatorChar))) {
    throw "Refusing to use a run directory outside the repository: $runDirectory"
}

New-Item -ItemType Directory -Force -Path $runDirectory, $logDirectory | Out-Null
@(
    '# Generated for the isolated Steve Industrial Agent development server.'
    'eula=true'
) | Set-Content -LiteralPath (Join-Path $runDirectory 'eula.txt') -Encoding ascii

$started = Get-Date
$log = Join-Path $logDirectory ('mekanism-absent-' + $started.ToString('yyyyMMdd-HHmmss') + '.log')
& (Join-Path $PSScriptRoot 'Invoke-Gradle.ps1') `
    -PmekanismAbsentSmoke=true `
    :forge-create-1.20.1:runServer *>&1 | Tee-Object -FilePath $log

$newCrashReports = @()
$crashDirectory = Join-Path $runDirectory 'crash-reports'
if (Test-Path -LiteralPath $crashDirectory) {
    $newCrashReports = @(Get-ChildItem -LiteralPath $crashDirectory -File |
        Where-Object LastWriteTime -ge $started)
}
if ($newCrashReports.Count -gt 0) {
    throw "Mekanism-absent launch created crash report(s): $($newCrashReports.FullName -join ', ')"
}
if (-not (Select-String -LiteralPath $log -SimpleMatch $expectedMarker -Quiet)) {
    throw "Missing typed Mekanism-absent PASS marker in $log"
}

Write-Output $expectedMarker
Write-Output "No crash reports created under: $crashDirectory"
Write-Output "Test log: $log"
