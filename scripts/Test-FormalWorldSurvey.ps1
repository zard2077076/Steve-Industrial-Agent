param(
    [Parameter(Mandatory = $true)][string]$Pcl2Root,
    [Parameter(Mandatory = $true)][string]$InstanceName,
    [Parameter(Mandatory = $true)][string]$SaveDirectoryName,
    [Parameter(Mandatory = $true)][string]$WorldIdentity,
    [Parameter(Mandatory = $true)][string]$LevelName,
    [Parameter(Mandatory = $true)][int]$DataVersion,
    [Parameter(Mandatory = $true)][string]$VersionName,
    [Parameter(Mandatory = $true)][string[]]$Dimensions
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$pcl2 = (Resolve-Path -LiteralPath $Pcl2Root).Path
$instance = (Resolve-Path -LiteralPath (Join-Path $pcl2 ".minecraft\versions\$InstanceName")).Path
$save = (Resolve-Path -LiteralPath (Join-Path $instance "saves\$SaveDirectoryName")).Path
$expectedInstanceParent = (Resolve-Path -LiteralPath (Join-Path $pcl2 '.minecraft\versions')).Path
$expectedSaveParent = (Resolve-Path -LiteralPath (Join-Path $instance 'saves')).Path
if ((Split-Path -Parent $instance) -ne $expectedInstanceParent -or
    (Split-Path -Parent $save) -ne $expectedSaveParent) {
    throw 'Formal instance/save identity is not an exact direct child of the approved PCL2 structure'
}
if ($WorldIdentity -notmatch '^world:[0-9a-f]{64}$' -or $Dimensions.Count -lt 1) {
    throw 'Formal world identity or dimension set is invalid'
}
if ((git -C $projectRoot rev-parse --show-toplevel).Trim().Replace('\', '/') -ne $projectRoot.Replace('\', '/')) {
    throw 'Project root is not the active Git repository root'
}

function Assert-NoJavaProcess([string]$Stage) {
    $java = @(Get-Process java, javaw -ErrorAction SilentlyContinue)
    if ($java.Count -ne 0) {
        throw "$Stage found Java/Javaw process IDs: $($java.Id -join ',')"
    }
}

function Get-InstanceMetadataSnapshot([string]$InstanceRoot, [string]$ExcludedSaveRoot) {
    $root = [IO.Path]::GetFullPath($InstanceRoot).TrimEnd('\')
    $excluded = [IO.Path]::GetFullPath($ExcludedSaveRoot).TrimEnd('\')
    $stack = [Collections.Generic.Stack[string]]::new()
    $stack.Push($root)
    $lines = [Collections.Generic.List[string]]::new()
    $fileCount = 0L
    $totalBytes = 0L
    $crashReports = 0L
    while ($stack.Count -gt 0) {
        $directory = $stack.Pop()
        foreach ($entry in [IO.Directory]::EnumerateFileSystemEntries($directory)) {
            $full = [IO.Path]::GetFullPath($entry)
            $attributes = [IO.File]::GetAttributes($full)
            if (($attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "Formal instance metadata snapshot found a reparse point: $full"
            }
            if (($attributes -band [IO.FileAttributes]::Directory) -ne 0) {
                if ($full.TrimEnd('\') -eq $excluded) { continue }
                $stack.Push($full)
                continue
            }
            $item = [IO.FileInfo]::new($full)
            $relative = [IO.Path]::GetRelativePath($root, $full).Replace('\', '/')
            $lines.Add("$relative|$($item.Length)|$($item.LastWriteTimeUtc.Ticks)")
            $fileCount++
            $totalBytes += $item.Length
            if ($relative -like 'crash-reports/*') { $crashReports++ }
        }
    }
    $lines.Sort([StringComparer]::Ordinal)
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes(($lines -join "`n"))
        $hash = [Convert]::ToHexString($sha.ComputeHash($bytes)).ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
    [pscustomobject]@{
        fingerprint = $hash
        fileCount = $fileCount
        totalBytes = $totalBytes
        crashReports = $crashReports
        privateSaveTreeEnumerated = $false
        contentOpened = $false
    }
}

Assert-NoJavaProcess 'FS12 preflight'
$outsideBefore = Get-InstanceMetadataSnapshot $instance $save
$logDirectory = Join-Path $projectRoot 'work\logs'
New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
$log = Join-Path $logDirectory ('formal-world-survey-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.log')
$budget = 'regions=16,chunks=16384,readBytes=8589934592,durationMillis=1800000,retainedBytes=8589934592,candidateZones=8,deepScanRadius=1,parallelism=1'
Write-Output "FS12_OUTER_PREFLIGHT canonicalSaveRoot=$save policy=formal-survey-v1 output=$projectRoot\work\formal-survey budget=$budget javaProcesses=0 externalMetadataFingerprint=$($outsideBefore.fingerprint)"

$gradleArguments = @(
    '-PskipGameModules=true'
    ':core:formalSurveyAcceptance'
    "-PformalProjectRoot=$projectRoot"
    "-PformalInstanceRoot=$instance"
    "-PformalSaveRoot=$save"
    "-PformalWorldIdentity=$WorldIdentity"
    "-PformalLevelName=$LevelName"
    "-PformalDataVersion=$DataVersion"
    "-PformalVersionName=$VersionName"
    "-PformalDimensions=$($Dimensions -join ',')"
)
& (Join-Path $PSScriptRoot 'Invoke-Gradle.ps1') @gradleArguments *>&1 | Tee-Object -FilePath $log
if ($LASTEXITCODE -ne 0) { throw "FS12 guarded survey failed; see $log" }

$outsideAfter = Get-InstanceMetadataSnapshot $instance $save
if ($outsideBefore.fingerprint -ne $outsideAfter.fingerprint -or
    $outsideBefore.fileCount -ne $outsideAfter.fileCount -or
    $outsideBefore.totalBytes -ne $outsideAfter.totalBytes) {
    throw 'EXTERNAL_MUTATION_DETECTED: formal instance metadata outside the save changed during FS12'
}
if ($outsideAfter.crashReports -ne $outsideBefore.crashReports) {
    throw 'FS12 created a crash report in the formal instance'
}
Assert-NoJavaProcess 'FS12 close-out'

$passLine = Select-String -LiteralPath $log -Pattern '^FS12_FORMAL_SURVEY_PASS .* evidence=(.+)$' |
    Select-Object -Last 1
if ($null -eq $passLine) { throw 'FS12 PASS marker is missing' }
$runRoot = $passLine.Matches[0].Groups[1].Value.Trim()
$summaryPath = Join-Path $runRoot 'summary.json'
$summary = Get-Content -LiteralPath $summaryPath -Raw | ConvertFrom-Json
if ($summary.preFingerprint -ne $summary.postFingerprint -or
    $summary.externalMutation -ne $false -or $summary.savesOrFormalWorldRead -ne $true -or
    $summary.formalWorldWrite -ne $false -or $summary.sessionLockCreatedOrModified -ne $false -or
    $summary.inventoryContentsRead -ne $false -or $summary.processStarted -ne $false -or
    $summary.executionAllowed -ne $false) {
    throw 'FS12 structured report violates the formal read-only acceptance flags'
}
if ([int]$summary.candidateZones -lt 1) {
    throw 'CANDIDATE_ZONE_NOT_FOUND: Stage A cannot close without at least one pending candidate zone'
}
if ([int]$summary.dryRunCandidatesCompleted -ne 0 -or [int]$summary.dryRunCandidatesBlocked -ne 4 -or
    $summary.dryRunBlockReason -notmatch 'FORMAL_WORLD_EXECUTION_FORBIDDEN') {
    throw 'FS12 did not retain the exact four-member formal dry-run blocked result'
}
$dryRuns = @(Import-Csv -LiteralPath (Join-Path $runRoot 'dry-run-results.tsv') -Delimiter "`t")
if ($dryRuns.Count -ne 4 -or @($dryRuns | Where-Object { $_.status -ne 'BLOCKED' }).Count -ne 0 -or
    @($dryRuns | Where-Object { $_.preview_hash -ne 'not-generated' }).Count -ne 0) {
    throw 'FS12 formal dry-run report is incomplete or falsely claims a preview'
}
$auditPath = Join-Path $runRoot 'audit\formal-read-audit.jsonl'
$audit = @(Get-Content -LiteralPath $auditPath | ForEach-Object { $_ | ConvertFrom-Json })
$privateOpen = @($audit | Where-Object {
    $_.event -eq 'READ_OPEN' -and $_.path -match '[/\\](playerdata|stats|advancements)[/\\]'
})
if ($privateOpen.Count -ne 0) { throw 'FORMAL_INVENTORY_CONTENTS_FORBIDDEN: private formal content was opened' }

Write-Output "FS12_OUTER_ACCEPTANCE PASS worldIdentity=$WorldIdentity preFingerprint=$($summary.preFingerprint) postFingerprint=$($summary.postFingerprint) externalInstanceFingerprint=$($outsideAfter.fingerprint) externalMutation=false savesOrFormalWorldRead=true formalWorldWrite=false sessionLockChanged=false crashReports=0 residualProcesses=0 candidates=$($summary.candidateZones) evidence=$runRoot log=$log"
