param(
    [string]$InstanceRoot,
    [string]$FormalRoot = 'D:\PCL2'
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ((git -C $repoRoot status --porcelain | Measure-Object).Count -ne 0) {
    throw 'IWP-05 real backup requires a clean worktree.'
}
if ([string]::IsNullOrWhiteSpace($InstanceRoot)) {
    $InstanceRoot = Join-Path $repoRoot 'work\isolated-player\SteveAgent_DeceasedCraft_Test'
}
$instance = (Resolve-Path -LiteralPath $InstanceRoot).Path
$formal = (Resolve-Path -LiteralPath $FormalRoot).Path
$run = (Resolve-Path -LiteralPath (Join-Path $instance 'run')).Path
$saves = (Resolve-Path -LiteralPath (Join-Path $run 'saves')).Path
$allowed = [IO.Path]::GetFullPath((Join-Path $repoRoot 'work\isolated-player')).TrimEnd('\') + '\'
if (-not $instance.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase) -or
        $instance.StartsWith($formal.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'TEST_WORLD_IDENTITY_MISMATCH: instance is outside the isolated repository root.'
}
$minecraft = @(Get-CimInstance Win32_Process | Where-Object {
    $_.Name -match '^javaw?\.exe$' -and
        ($_.CommandLine -like "*$run*" -or $_.CommandLine -match 'net\.minecraft|forge')
})
if ($minecraft.Count -gt 0) {
    throw "TEST_WORLD_IN_USE_DURING_BACKUP: Minecraft/Forge PID $($minecraft.ProcessId -join ',') is still running."
}
$worlds = @(Get-ChildItem -LiteralPath $saves -Directory | Where-Object {
    Test-Path -LiteralPath (Join-Path $_.FullName 'level.dat') -PathType Leaf
})
if ($worlds.Count -eq 0) { throw 'TEST_WORLD_NOT_FOUND: no generated test world exists.' }
if ($worlds.Count -gt 1) { throw "MULTIPLE_TEST_WORLDS_AMBIGUOUS: $($worlds.Name -join ', ')" }
if ($worlds[0].Name -ne 'Steve Agent Test') {
    throw "TEST_WORLD_IDENTITY_MISMATCH: expected Steve Agent Test, found $($worlds[0].Name)."
}
$world = $worlds[0].FullName
$marker = Join-Path $world '.steve-industrial-writable-test-world'
if (-not (Test-Path -LiteralPath $marker -PathType Leaf) -or
        (Get-Content -LiteralPath $marker -Raw).Trim() -ne 'ISOLATED_WRITABLE_TEST_WORLD') {
    throw 'TEST_WORLD_IDENTITY_MISMATCH: isolated writable marker is absent.'
}

$levelHash = (Get-FileHash -LiteralPath (Join-Path $world 'level.dat') -Algorithm SHA256).Hash.ToLowerInvariant()
$worldIdentity = 'world:' + $levelHash
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backupIdentity = 'iwp05-' + $timestamp + '-' + $levelHash.Substring(0, 12)
$backupRoot = Join-Path $repoRoot 'work\writable-world-backups'
$worldBackupRoot = Join-Path $backupRoot $levelHash
$acceptanceRoot = Join-Path $worldBackupRoot $timestamp
if (Test-Path -LiteralPath $acceptanceRoot) { throw 'IWP-05 refuses to overwrite an existing backup run.' }
New-Item -ItemType Directory -Path $backupRoot -Force | Out-Null
New-Item -ItemType Directory -Path $worldBackupRoot -Force | Out-Null
New-Item -ItemType Directory -Path $acceptanceRoot | Out-Null

$logRoot = Join-Path $repoRoot 'work\logs'
New-Item -ItemType Directory -Path $logRoot -Force | Out-Null
$log = Join-Path $logRoot ('iwp-world-backup-' + $timestamp + '.log')
$arguments = @(
    '-PskipGameModules=true', ':core:writableTestWorldBackupAcceptance', '--console=plain',
    ('-PiwpProjectRoot=' + $repoRoot), ('-PiwpInstanceRoot=' + $instance),
    ('-PiwpWorldRoot=' + $world), ('-PiwpFormalRoot=' + $formal),
    ('-PiwpBackupRoot=' + $backupRoot), ('-PiwpRunRoot=' + $acceptanceRoot),
    ('-PiwpWorldIdentity=' + $worldIdentity), ('-PiwpBackupIdentity=' + $backupIdentity)
)
try {
    & (Join-Path $PSScriptRoot 'Invoke-Gradle.ps1') @arguments *>&1 | Tee-Object -FilePath $log
    if ($LASTEXITCODE -ne 0) { throw "IWP-05 Gradle acceptance failed with exit code $LASTEXITCODE." }
    $report = Get-Content -LiteralPath (Join-Path $acceptanceRoot 'acceptance.json') -Raw | ConvertFrom-Json
    if (-not $report.backupValid -or -not $report.restoreDrillPass -or
            -not $report.sourceUnchanged -or $report.formalWorldTouched -or
            $report.minecraftStarted -or
            $report.sourcePreManifestHash -ne $report.sourcePostManifestHash) {
        throw 'IWP-05 acceptance readback failed.'
    }
    $pointerPath = Join-Path $instance 'evidence\accepted-world-backup.properties'
    @(
        'schema=steve-industrial:iwp-accepted-backup/v1'
        "evidenceRoot=$acceptanceRoot"
        "backupRoot=$backupRoot"
        "backupTarget=$($report.backupTarget)"
        "backupIdentity=$($report.backupIdentity)"
        "manifestHash=$($report.sourcePostManifestHash)"
        "worldIdentity=$($report.worldIdentity)"
    ) | Set-Content -LiteralPath $pointerPath -Encoding utf8
    $residual = @(Get-CimInstance Win32_Process | Where-Object {
        $_.Name -match '^javaw?\.exe$' -and $_.CommandLine -like "*$run*"
    })
    if ($residual.Count -gt 0) { throw 'IWP-05 left a Minecraft process running.' }
    Write-Output "IWP05_POST_PASS backupIdentity=$($report.backupIdentity) manifest=$($report.sourcePostManifestHash) files=$($report.fileCount) bytes=$($report.totalBytes) restoreDrill=true sourceUnchanged=true residualMinecraft=0 formalWorldTouched=false evidence=$acceptanceRoot pointer=$pointerPath log=$log"
} catch {
    throw
}
