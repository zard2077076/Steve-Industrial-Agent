param(
    [string]$OutputDirectory,
    [switch]$FailOnInternalPathEvidence
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $root 'work\release-audit'
}
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null

function Invoke-GitLines([string[]]$Arguments) {
    $lines = & git -C $root @Arguments 2>&1
    if ($LASTEXITCODE -notin @(0, 1)) {
        throw "git $($Arguments -join ' ') failed"
    }
    return @($lines)
}

function Write-Report([string]$Name, [string[]]$Lines) {
    $path = Join-Path $OutputDirectory $Name
    @($Lines) | Set-Content -LiteralPath $path -Encoding UTF8
    return $path
}

$secretPatterns = @(
    'AKIA[0-9A-Z]{16}',
    'gh[pousr]_[A-Za-z0-9_]{20,}',
    'github_pat_[A-Za-z0-9_]{20,}',
    'sk-[A-Za-z0-9_-]{20,}',
    'BEGIN (RSA |OPENSSH |EC |DSA )?PRIVATE KEY'
)
$trackedSecretFiles = New-Object System.Collections.Generic.HashSet[string]
foreach ($pattern in $secretPatterns) {
    foreach ($line in (Invoke-GitLines @('grep', '-l', '-I', '-E', $pattern, '--', '.'))) {
        if ($line) { [void]$trackedSecretFiles.Add($line.Trim()) }
    }
}

$history = & git -C $root log --all -p --no-ext-diff --text
if ($LASTEXITCODE -ne 0) { throw 'Unable to scan Git history' }
$historyHits = New-Object System.Collections.Generic.HashSet[string]
$commit = ''
$file = ''
foreach ($line in $history) {
    if ($line -match '^commit ([0-9a-f]{40})$') { $commit = $Matches[1].Substring(0, 12); continue }
    if ($line -match '^diff --git a/(.+) b/(.+)$') { $file = $Matches[2]; continue }
    foreach ($pattern in $secretPatterns) {
        if ($line -match $pattern) {
            [void]$historyHits.Add("$commit $file pattern=$pattern")
            break
        }
    }
}

$tracked = @(Invoke-GitLines @('ls-files'))
$pathPatterns = 'C:\\Users\\[^\\\s]+|C:/Users/[^/\s]+|[A-Za-z]:\\PCL2|[A-Za-z]:/PCL2'
$pathHits = @()
foreach ($line in (Invoke-GitLines @('grep', '-n', '-I', '-E', $pathPatterns, '--', '.'))) {
    if ($line) { $pathHits += ($line -replace '^([^:]+:[0-9]+):.*$', '$1 [REDACTED_LOCAL_PATH_EVIDENCE]') }
}

$privateNamePattern = '(?i)(^|/)(saves?|worlds?|playerdata|region|backups?|crash-reports?|logs?)(/|$)|(?i)(^|/)(local\.properties|\.env|secrets\.properties)$'
$binaryPattern = '(?i)\.(jar|zip|7z|rar|nbt|mca|mcr|dat)$'
$privateFiles = @($tracked | Where-Object { $_ -match $privateNamePattern })
$binaryFiles = @($tracked | Where-Object { $_ -match $binaryPattern })
$largeFiles = @()
foreach ($relative in $tracked) {
    $path = Join-Path $root $relative
    if (Test-Path -LiteralPath $path -PathType Leaf) {
        $item = Get-Item -LiteralPath $path
        if ($item.Length -gt 5MB) { $largeFiles += "$($item.Length) $relative" }
    }
}

$secretReport = @(
    'Steve Industrial Agent release secrets scan',
    "trackedHighConfidenceHits=$($trackedSecretFiles.Count)",
    "historyHighConfidenceHits=$($historyHits.Count)",
    'Secret values are never emitted by this scanner.'
) + @($trackedSecretFiles | Sort-Object | ForEach-Object { "trackedFile=$_" }) + @($historyHits | Sort-Object)
$privacyReport = @(
    'Steve Industrial Agent privacy path scan',
    "trackedInternalPathEvidence=$($pathHits.Count)",
    'Internal historical evidence is not a release artifact and is recorded only by file and line.',
    'Public release artifacts must have zero path hits.'
) + @($pathHits | Sort-Object -Unique)
$binaryReport = @('Steve Industrial Agent binary artifact scan', "trackedBinaryFiles=$($binaryFiles.Count)") + $binaryFiles
$largeReport = @(
    'Steve Industrial Agent tracked large/private file scan',
    "trackedPrivateNameHits=$($privateFiles.Count)",
    "trackedFilesOver5MiB=$($largeFiles.Count)"
) + $privateFiles + $largeFiles
$historyReport = @(
    'Steve Industrial Agent Git history risk report',
    "highConfidenceSecretHits=$($historyHits.Count)",
    "internalPathEvidencePresent=$($pathHits.Count -gt 0)",
    'No public remote history rewrite was attempted. Internal path evidence is excluded from the release allowlist.'
)

$reports = @(
    (Write-Report 'secrets-scan.txt' $secretReport),
    (Write-Report 'privacy-paths.txt' $privacyReport),
    (Write-Report 'binary-artifacts.txt' $binaryReport),
    (Write-Report 'tracked-large-private.txt' $largeReport),
    (Write-Report 'git-history-risk.txt' $historyReport)
)

if ($trackedSecretFiles.Count -gt 0 -or $historyHits.Count -gt 0) {
    throw "HIGH_CONFIDENCE_SECRET_DETECTED: inspect sanitized reports under $OutputDirectory; rotate affected credentials before continuing"
}
if ($FailOnInternalPathEvidence -and $pathHits.Count -gt 0) {
    throw "INTERNAL_PATH_EVIDENCE_DETECTED: $($pathHits.Count) sanitized locations"
}

Write-Output "RELEASE_PRIVACY_SCAN PASS secrets=0 historySecrets=0 internalPathEvidence=$($pathHits.Count) trackedPrivateNames=$($privateFiles.Count) trackedLargeFiles=$($largeFiles.Count)"
$reports | ForEach-Object { Write-Output "REPORT $_" }
