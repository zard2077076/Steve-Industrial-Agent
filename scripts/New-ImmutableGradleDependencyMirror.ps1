param(
    [Parameter(Mandatory = $true)][string]$OnlineRoot,
    [Parameter(Mandatory = $true)][string]$MirrorRoot
)

$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$work = (Resolve-Path (Join-Path $repository 'work')).Path
$online = [IO.Path]::GetFullPath($OnlineRoot)
$mirror = [IO.Path]::GetFullPath($MirrorRoot)

function Assert-ChildOfWork([string]$Path, [string]$Code) {
    if (-not $Path.StartsWith($work + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "${Code}: $Path"
    }
}
function Get-Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}
function Get-TextSha256([string[]]$Rows) {
    $bytes = [Text.Encoding]::UTF8.GetBytes(($Rows -join "`n") + "`n")
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant() }
    finally { $sha.Dispose() }
}

Assert-ChildOfWork $online 'MIRROR_ONLINE_ROOT_OUTSIDE_WORK'
Assert-ChildOfWork $mirror 'MIRROR_ROOT_OUTSIDE_WORK'
if (Test-Path -LiteralPath $mirror) { throw "MIRROR_ROOT_MUST_BE_NEW: $mirror" }
$onlineEvidencePath = Join-Path $online 'online-dependency-resolution.json'
if (-not (Test-Path -LiteralPath $onlineEvidencePath -PathType Leaf)) { throw 'MIRROR_ONLINE_EVIDENCE_MISSING' }
$onlineEvidence = Get-Content -LiteralPath $onlineEvidencePath -Raw | ConvertFrom-Json
$head = (& git -C $repository rev-parse HEAD).Trim()
if ($onlineEvidence.status -ne 'PASS' -or $onlineEvidence.gitCommit -ne $head -or -not $onlineEvidence.onlineColdCleanBuild) {
    throw 'MIRROR_ONLINE_EVIDENCE_STALE_OR_FAILED'
}
if ((Get-Sha256 $onlineEvidence.dependencyManifest) -ne $onlineEvidence.dependencyManifestSha256) {
    throw 'MIRROR_DEPENDENCY_MANIFEST_HASH_MISMATCH'
}
$sourceHome = [string]$onlineEvidence.gradleUserHome
if (-not (Test-Path -LiteralPath $sourceHome -PathType Container)) { throw 'MIRROR_ONLINE_GRADLE_HOME_MISSING' }

New-Item -ItemType Directory -Force -Path $mirror | Out-Null
$snapshot = Join-Path $mirror 'gradle-user-home-snapshot'
New-Item -ItemType Directory -Force -Path $snapshot | Out-Null
& robocopy.exe $sourceHome $snapshot /E /COPY:DAT /DCOPY:DAT /R:2 /W:1 /NFL /NDL /NJH /NJS /NP | Out-Null
if ($LASTEXITCODE -gt 7) { throw "MIRROR_COPY_FAILED: robocopy=$LASTEXITCODE" }

foreach ($volatileDirectory in @('daemon', 'workers', 'notifications')) {
    $candidate = Join-Path $snapshot $volatileDirectory
    if (Test-Path -LiteralPath $candidate) { Remove-Item -LiteralPath $candidate -Recurse -Force }
}
Get-ChildItem -LiteralPath $snapshot -Recurse -File -Force |
        Where-Object { $_.Name -like '*.lock' -or $_.Name -eq 'gc.properties' } |
        Remove-Item -Force

$entries = [Collections.Generic.List[object]]::new()
$rows = [Collections.Generic.List[string]]::new()
foreach ($file in Get-ChildItem -LiteralPath $snapshot -Recurse -File | Sort-Object FullName) {
    $relative = $file.FullName.Substring($snapshot.Length + 1).Replace('\', '/')
    $hash = Get-Sha256 $file.FullName
    $entries.Add([ordered]@{ path=$relative; bytes=$file.Length; sha256=$hash })
    $rows.Add("$relative`t$($file.Length)`t$hash")
}
if ($entries.Count -eq 0) { throw 'MIRROR_EMPTY' }
$aggregate = Get-TextSha256 $rows
Copy-Item -LiteralPath $onlineEvidence.dependencyManifest -Destination (Join-Path $mirror 'online-dependency-manifest.json')
$manifest = [ordered]@{
    schema='steve-industrial:immutable-gradle-mirror/v1'; status='PASS'; gitCommit=$head
    sourceOnlineEvidence=$onlineEvidencePath
    sourceOnlineEvidenceSha256=Get-Sha256 $onlineEvidencePath
    sourceDependencyManifestSha256=$onlineEvidence.dependencyManifestSha256
    snapshot=$snapshot; immutable=$true; networkFallbackAllowed=$false
    fileCount=$entries.Count; totalBytes=($entries | Measure-Object bytes -Sum).Sum
    aggregateSha256=$aggregate; files=$entries
    createdAtUtc=(Get-Date).ToUniversalTime().ToString('o')
}
$manifestPath = Join-Path $mirror 'mirror-manifest.json'
$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $manifestPath -Encoding utf8

foreach ($file in Get-ChildItem -LiteralPath $snapshot -Recurse -File -Force) {
    $file.IsReadOnly = $true
}
$readOnlyCount = @(Get-ChildItem -LiteralPath $snapshot -Recurse -File -Force | Where-Object IsReadOnly).Count
if ($readOnlyCount -ne $entries.Count) { throw 'MIRROR_READ_ONLY_ATTRIBUTE_INCOMPLETE' }
Write-Output "IMMUTABLE_DEPENDENCY_MIRROR PASS commit=$head files=$($entries.Count) bytes=$($manifest.totalBytes) aggregateSha256=$aggregate readOnlyFiles=$readOnlyCount networkFallbackAllowed=false"
