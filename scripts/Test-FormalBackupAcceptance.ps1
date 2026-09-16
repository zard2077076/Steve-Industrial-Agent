param(
    [string]$FormalPlatformRoot = 'D:\PCL2',
    [string]$FormalInstanceRoot = 'D:\PCL2\.minecraft\versions\DeceasedCraft_Beta 5.10.16',
    [string]$FormalSaveRoot = 'D:\PCL2\.minecraft\versions\DeceasedCraft_Beta 5.10.16\saves\新的世界',
    [string]$WorldIdentity = 'world:ded38fd9f680d10751612d7b0d95e9a14c228918a8e8e4b5daf308a20762dcb3',
    [string]$ExpectedSourceFingerprint = '00571b934b91477a3bbc58b30933c121ea09a4848ade76c89ea4ad636a834a34',
    [string]$RuntimeFingerprint = 'facb53b47cdc61d3fed08f7faa01bee52e8766e53774e65bf060a9b2fa3c47d5',
    [switch]$PostCheckOnly,
    [string]$ExistingEvidenceRoot
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ((git -C $root status --porcelain | Measure-Object).Count -ne 0) {
    throw 'FB-07 requires a clean worktree before the real formal-source read.'
}
$gitHead = (git -C $root rev-parse HEAD).Trim()
$source = (Resolve-Path -LiteralPath $FormalSaveRoot).Path
$instance = (Resolve-Path -LiteralPath $FormalInstanceRoot).Path
$platform = (Resolve-Path -LiteralPath $FormalPlatformRoot).Path
if (-not $source.StartsWith($instance, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Formal source is outside the exact instance.'
}
$javaProcesses = @(Get-CimInstance Win32_Process | Where-Object {
    $_.Name -match '^(java|javaw|minecraft|forge).*\.exe$'
})
if ($javaProcesses.Count -ne 0) {
    throw ('Java/Minecraft/Forge processes are present: ' + (($javaProcesses.ProcessId) -join ','))
}

if (-not ('ExclusiveReadProbe' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
public static class ExclusiveReadProbe {
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    static extern IntPtr CreateFile(string name, uint access, uint share, IntPtr security,
        uint creation, uint flags, IntPtr template);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool CloseHandle(IntPtr handle);
    public static void Probe(string path) {
        IntPtr handle = CreateFile(path, 0x80000000, 0, IntPtr.Zero, 3, 0x80, IntPtr.Zero);
        if (handle == new IntPtr(-1)) throw new Win32Exception(Marshal.GetLastWin32Error());
        if (!CloseHandle(handle)) throw new Win32Exception(Marshal.GetLastWin32Error());
    }
}
'@
}
$sessionLock = Join-Path $source 'session.lock'
[ExclusiveReadProbe]::Probe($sessionLock)
$lockBefore = Get-Item -LiteralPath $sessionLock
$lockEvidence = '{0}|{1}|{2}' -f $lockBefore.Length,$lockBefore.LastWriteTimeUtc.Ticks,$lockBefore.Attributes

if ($PostCheckOnly) {
    if (-not $ExistingEvidenceRoot) { throw 'PostCheckOnly requires ExistingEvidenceRoot.' }
    $existing = (Resolve-Path -LiteralPath $ExistingEvidenceRoot).Path
    $report = Get-Content -LiteralPath (Join-Path $existing 'acceptance.json') -Raw | ConvertFrom-Json
    if ($report.worldIdentity -ne $WorldIdentity -or
        $report.sourcePreFingerprint -ne $ExpectedSourceFingerprint -or
        $report.sourcePostFingerprint -ne $ExpectedSourceFingerprint -or
        -not $report.backupValid -or -not $report.restoreDrillPass -or
        $report.candidatePackages -ne 8 -or $report.candidatePreviewUnavailable -ne 8 -or
        $report.approvalRequestsAwaitingSelection -ne 8 -or $report.formalAllowedDecisions -ne 0 -or
        $report.formalWorldWrite -or $report.inventoryContentsRead -or
        $report.minecraftOrForgeStarted -or $report.formalExecutionAllowed) {
        throw 'Existing FB-07 acceptance evidence failed post-check invariants.'
    }
    if (-not (Test-Path -LiteralPath $report.backupTarget -PathType Container) -or
        -not (Test-Path -LiteralPath $report.restoreTarget -PathType Container)) {
        throw 'Existing FB-07 backup or restore target is missing.'
    }
    $lockAfter = Get-Item -LiteralPath $sessionLock
    $lockAfterEvidence = '{0}|{1}|{2}' -f $lockAfter.Length,$lockAfter.LastWriteTimeUtc.Ticks,$lockAfter.Attributes
    if ($lockAfterEvidence -ne $lockEvidence) { throw 'session.lock changed during FB-07 post-check.' }
    [ExclusiveReadProbe]::Probe($sessionLock)
    $crashCount = @(Get-ChildItem -LiteralPath $existing -Recurse -Filter 'crash-*.txt' -ErrorAction SilentlyContinue).Count
    if ($crashCount -ne 0) { throw 'Existing FB-07 evidence contains a crash report.' }
    Write-Output "FB07_POST_PASS sessionLockUnchanged=true residualProcesses=0 crashReports=0 evidence=$existing"
    return
}

$backupRoot = Join-Path $root 'work\formal-backups'
$restoreRoot = Join-Path $root 'work\formal-restore-drills'
New-Item -ItemType Directory -Force -Path $backupRoot,$restoreRoot | Out-Null
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$evidenceRoot = Join-Path $root ('work\formal-backup-acceptance\fb07-' + $timestamp)
New-Item -ItemType Directory -Force -Path $evidenceRoot | Out-Null
$candidateTsv = Join-Path $root 'work\formal-survey\ded38fd9f680d10751612d7b0d95e9a14c228918a8e8e4b5daf308a20762dcb3\fs12-2026-07-20T03-50-59.119599200Z\candidate-zones.tsv'
$observedAt = [DateTimeOffset]::UtcNow.ToString('o')
$logDirectory = Join-Path $root 'work\logs'
New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
$log = Join-Path $logDirectory ('formal-backup-acceptance-' + $timestamp + '.log')

$gradleArgs = @(
    '-PskipGameModules=true', ':core:formalBackupAcceptance', '--console=plain',
    ('-PformalProjectRoot=' + $root), ('-PformalPlatformRoot=' + $platform),
    ('-PformalInstanceRoot=' + $instance), ('-PformalSaveRoot=' + $source),
    ('-PformalWorldIdentity=' + $WorldIdentity), ('-PformalRuntimeFingerprint=' + $RuntimeFingerprint),
    ('-PformalExpectedSourceFingerprint=' + $ExpectedSourceFingerprint), ('-PformalGitHead=' + $gitHead),
    ('-PformalQuiescenceObservedAt=' + $observedAt), ('-PformalEvidenceRoot=' + $evidenceRoot),
    ('-PformalCandidateTsv=' + $candidateTsv)
)
& (Join-Path $PSScriptRoot 'Invoke-Gradle.ps1') @gradleArgs *>&1 | Tee-Object -FilePath $log

$lockAfter = Get-Item -LiteralPath $sessionLock
$lockAfterEvidence = '{0}|{1}|{2}' -f $lockAfter.Length,$lockAfter.LastWriteTimeUtc.Ticks,$lockAfter.Attributes
if ($lockAfterEvidence -ne $lockEvidence) { throw 'session.lock changed during FB-07.' }
[ExclusiveReadProbe]::Probe($sessionLock)
$residual = @()
for ($attempt = 0; $attempt -lt 60; $attempt++) {
    $residual = @(Get-CimInstance Win32_Process | Where-Object {
        $_.Name -match '^(java|javaw|minecraft|forge).*\.exe$'
    })
    if ($residual.Count -eq 0) { break }
    Start-Sleep -Milliseconds 500
}
if ($residual.Count -ne 0) { throw 'FB-07 left residual Java/Minecraft/Forge processes.' }
$crashCount = @(Get-ChildItem -LiteralPath $evidenceRoot -Recurse -Filter 'crash-*.txt' -ErrorAction SilentlyContinue).Count
if ($crashCount -ne 0) { throw 'FB-07 produced a crash report.' }
Write-Output "FB07_POST_PASS sessionLockUnchanged=true residualProcesses=0 crashReports=0 evidence=$evidenceRoot"
Write-Output "Acceptance log: $log"
