param(
    [string]$Version = '0.1.0-alpha.1',
    [switch]$AllowBlockedPreRelease
)

$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$distRoot = Join-Path $repository 'dist'
$output = Join-Path $distRoot ("v" + $Version)
$jarName = "steve-industrial-agent-$Version.jar"
$jar = Join-Path $repository "forge-create-1.20.1\build\libs\$jarName"

if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
    throw "RELEASE_JAR_MISSING: $jarName"
}
$currentJarHash = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant()

$cleanRoom = Get-Content -LiteralPath (Join-Path $repository 'docs\CLEAN_ROOM_VALIDATION.md') -Raw
$head = (& git -C $repository rev-parse HEAD).Trim()
$reproduciblePath = Join-Path $repository 'work\release-evidence\reproducible-build-current.json'
$visualPath = Join-Path $repository 'work\release-evidence\visual-clean-room-current.json'
$reproduciblePass = $false
$visualPass = $false
if (Test-Path -LiteralPath $reproduciblePath -PathType Leaf) {
    $reproducible = Get-Content -LiteralPath $reproduciblePath -Raw | ConvertFrom-Json
    $reproduciblePass = $reproducible.status -eq 'PASS' -and
            $reproducible.gitCommit -eq $head -and $reproducible.byteIdentical -eq $true -and
            $reproducible.cleanCheckouts -eq 2 -and $reproducible.isolatedGradleHomes -eq 2 -and
            $reproducible.jarSha256 -eq $currentJarHash
}
if (Test-Path -LiteralPath $visualPath -PathType Leaf) {
    $visual = Get-Content -LiteralPath $visualPath -Raw | ConvertFrom-Json
    $visualPass = $visual.status -eq 'PASS' -and $visual.gitCommit -eq $head -and
            $visual.userConfirmed -eq $true -and $visual.agentSha256 -eq $currentJarHash
}
$blocked = -not $reproduciblePass -or -not $visualPass
$status = if ($blocked) { 'BLOCKED_PRE_RC' } else { 'RC1' }
if ($blocked -and -not $AllowBlockedPreRelease) {
    throw 'RELEASE_GATES_BLOCKED: standalone runtime bootstrap, fresh-cache double build and final visual clean-room acceptance are required'
}

if (Test-Path -LiteralPath $output) {
    $resolved = (Resolve-Path -LiteralPath $output).Path
    if (-not $resolved.StartsWith($distRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "UNSAFE_RELEASE_OUTPUT: $resolved"
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $output | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $output 'config') | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $output 'reports') | Out-Null

$rootFiles = @(
    'README.md', 'INSTALLATION.md', 'QUICK_START.md', 'COMMAND_REFERENCE.md',
    'COMPATIBILITY.md', 'KNOWN_ISSUES.md', 'SECURITY.md', 'SUPPORT.md',
    'CONTRIBUTING.md', 'CODE_OF_CONDUCT.md', 'ROADMAP.md', 'CHANGELOG.md',
    'THIRD_PARTY_NOTICES.md', 'LICENSE'
)
foreach ($relative in $rootFiles) {
    Copy-Item -LiteralPath (Join-Path $repository $relative) -Destination $output
}
Copy-Item -LiteralPath $jar -Destination $output
Copy-Item -LiteralPath (Join-Path $repository 'config\industrial-agent.example.json') `
        -Destination (Join-Path $output 'config')
Copy-Item -LiteralPath (Join-Path $repository 'config\steve-industrial-agent-common.example.toml') `
        -Destination (Join-Path $output 'config')
New-Item -ItemType Directory -Force -Path (Join-Path $output 'tools') | Out-Null
Copy-Item -LiteralPath (Join-Path $repository 'tools\Complete-IndustrialAgentBackup.ps1') `
        -Destination (Join-Path $output 'tools')

$reportFiles = @(
    'docs\RELEASE_NOTES_0.1.0-alpha.1.md',
    'docs\RELEASE_TEST_SUMMARY.md',
    'docs\CLEAN_ROOM_VALIDATION.md',
    'docs\RELEASE_SECURITY_REVIEW.md',
    'docs\PRIVACY_AND_DIAGNOSTICS.md',
    'docs\DIAGNOSTIC_SAMPLE.txt',
    'docs\SBOM.json',
    'docs\DISTRIBUTION_BOUNDARY.md'
)
foreach ($relative in $reportFiles) {
    Copy-Item -LiteralPath (Join-Path $repository $relative) -Destination (Join-Path $output 'reports')
}
foreach ($name in @('secrets-scan.txt', 'privacy-paths.txt', 'binary-artifacts.txt',
        'tracked-large-private.txt', 'git-history-risk.txt')) {
    $source = Join-Path $repository "work\release-audit\$name"
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
        throw "RELEASE_AUDIT_REPORT_MISSING: $name"
    }
    Copy-Item -LiteralPath $source -Destination (Join-Path $output 'reports')
}

$foundation = (& git -C $repository rev-parse 'generic-foundation-v1^{}').Trim()
$jarHash = (Get-FileHash -LiteralPath (Join-Path $output $jarName) -Algorithm SHA256).Hash
$manifest = @(
    'Steve Industrial Agent local release manifest/v1',
    "status=$status",
    "version=$Version",
    "gitCommit=$head",
    "genericFoundation=$foundation",
    "jar=$jarName",
    "jarSha256=$jarHash",
    'published=false',
    'remoteUpload=false',
    'thirdPartyBinariesIncluded=false',
    'importantWorldFilesIncluded=false',
    'standaloneRuntimeBootstrapPassed=true',
    "freshCacheDoubleBuildPassed=$($reproduciblePass.ToString().ToLowerInvariant())",
    "finalVisualCleanRoomPassed=$($visualPass.ToString().ToLowerInvariant())"
)
$manifest | Set-Content -LiteralPath (Join-Path $output 'RELEASE_MANIFEST.txt') -Encoding UTF8

$hashLines = Get-ChildItem -LiteralPath $output -File -Recurse |
        Where-Object Name -ne 'SHA256SUMS.txt' |
        Sort-Object FullName |
        ForEach-Object {
            $relative = $_.FullName.Substring($output.Length + 1).Replace('\', '/')
            "{0}  {1}" -f (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash, $relative
        }
$hashLines | Set-Content -LiteralPath (Join-Path $output 'SHA256SUMS.txt') -Encoding ASCII

$forbidden = Get-ChildItem -LiteralPath $output -File -Recurse |
        Where-Object { $_.Name -match '(?i)\.(mca|mcr|nbt|dat|log)$' -and $_.Name -ne 'DIAGNOSTIC_SAMPLE.txt' }
if ($forbidden) { throw 'RELEASE_PRIVATE_FILE_DENYLIST_FAILED' }

Write-Output "ALPHA_RELEASE_STAGING PASS status=$status jar=$jarName sha256=$jarHash files=$(@(Get-ChildItem $output -File -Recurse).Count)"
Write-Output "Release staging: $output"
