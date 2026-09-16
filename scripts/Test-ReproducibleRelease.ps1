param(
    [string]$JavaHome = $env:JAVA_HOME,
    [Parameter(Mandatory = $true)][string]$GradleExecutable,
    [Parameter(Mandatory = $true)][string]$MirrorRoot,
    [Parameter(Mandatory = $true)][string]$RootDirectory
)

$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$work = (Resolve-Path (Join-Path $repository 'work')).Path
$evidenceDirectory = Join-Path $work 'release-evidence'
$root = [IO.Path]::GetFullPath($RootDirectory)
$mirror = [IO.Path]::GetFullPath($MirrorRoot)

function Assert-ChildOfWork([string]$Path, [string]$Code) {
    if (-not $Path.StartsWith($work + '\', [StringComparison]::OrdinalIgnoreCase)) { throw "${Code}: $Path" }
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
function Assert-Mirror([object]$Manifest, [string]$Snapshot) {
    $rows = [Collections.Generic.List[string]]::new()
    $actual = @(Get-ChildItem -LiteralPath $Snapshot -Recurse -File -Force)
    if ($actual.Count -ne $Manifest.fileCount) { throw 'OFFLINE_MIRROR_FILE_COUNT_MISMATCH' }
    foreach ($entry in $Manifest.files) {
        $path = Join-Path $Snapshot ([string]$entry.path).Replace('/', '\')
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "OFFLINE_MIRROR_FILE_MISSING: $($entry.path)" }
        $file = Get-Item -LiteralPath $path
        $hash = Get-Sha256 $path
        if ($file.Length -ne $entry.bytes -or $hash -ne $entry.sha256) {
            throw "OFFLINE_MIRROR_FILE_CHANGED: $($entry.path)"
        }
        if (-not $file.IsReadOnly) { throw "OFFLINE_MIRROR_FILE_NOT_READ_ONLY: $($entry.path)" }
        $rows.Add("$($entry.path)`t$($entry.bytes)`t$($entry.sha256)")
    }
    if ((Get-TextSha256 $rows) -ne $Manifest.aggregateSha256) { throw 'OFFLINE_MIRROR_AGGREGATE_MISMATCH' }
}
function Materialize-Mirror([object]$Manifest, [string]$Snapshot, [string]$Destination) {
    New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    & robocopy.exe $Snapshot $Destination /E /COPY:DAT /DCOPY:DAT /R:2 /W:1 /NFL /NDL /NJH /NJS /NP | Out-Null
    if ($LASTEXITCODE -gt 7) { throw "OFFLINE_MIRROR_MATERIALIZE_FAILED: robocopy=$LASTEXITCODE" }
    foreach ($entry in $Manifest.files) {
        $path = Join-Path $Destination ([string]$entry.path).Replace('/', '\')
        if (-not (Test-Path -LiteralPath $path -PathType Leaf) -or (Get-Sha256 $path) -ne $entry.sha256) {
            throw "OFFLINE_MIRROR_MATERIALIZE_HASH_MISMATCH: $($entry.path)"
        }
        (Get-Item -LiteralPath $path -Force).IsReadOnly = $false
    }
}

function Assert-DependencyInputsUnchanged([string]$FromCommit, [string]$ToCommit) {
    $dependencyInputs = @(
        'settings.gradle',
        'build.gradle',
        'gradle.properties',
        'adapter-api/build.gradle',
        'core/build.gradle',
        'forge-create-1.20.1/build.gradle',
        'gradle/wrapper/gradle-wrapper.jar',
        'gradle/wrapper/gradle-wrapper.properties',
        'gradlew',
        'gradlew.bat'
    )
    & git -C $repository diff --quiet "$FromCommit..$ToCommit" -- @dependencyInputs
    if ($LASTEXITCODE -ne 0) {
        throw "OFFLINE_MIRROR_DEPENDENCY_INPUTS_CHANGED: source=$FromCommit current=$ToCommit"
    }
}

Assert-ChildOfWork $root 'OFFLINE_AB_ROOT_OUTSIDE_WORK'
Assert-ChildOfWork $mirror 'OFFLINE_MIRROR_ROOT_OUTSIDE_WORK'
if (Test-Path -LiteralPath $root) { throw "OFFLINE_AB_ROOT_MUST_BE_NEW: $root" }
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) { throw 'OFFLINE_JAVA_17_REQUIRED' }
if (-not (Test-Path -LiteralPath $GradleExecutable -PathType Leaf)) { throw 'OFFLINE_GRADLE_NOT_FOUND' }
if (((& (Join-Path $JavaHome 'bin\java.exe') -version 2>&1) -join "`n") -notmatch 'version "17\.') { throw 'OFFLINE_JAVA_VERSION_MISMATCH' }
if ((& git -C $repository status --porcelain --untracked-files=normal) -ne $null) { throw 'OFFLINE_REPOSITORY_NOT_CLEAN' }

$manifestPath = Join-Path $mirror 'mirror-manifest.json'
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) { throw 'OFFLINE_MIRROR_MANIFEST_MISSING' }
$mirrorManifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$head = (& git -C $repository rev-parse HEAD).Trim()
if ($mirrorManifest.status -ne 'PASS' -or -not $mirrorManifest.immutable) {
    throw 'OFFLINE_MIRROR_STALE_OR_UNVERIFIED'
}
$mirrorSourceCommit = [string]$mirrorManifest.gitCommit
if (-not $mirrorSourceCommit) {
    throw 'OFFLINE_MIRROR_SOURCE_COMMIT_UNAVAILABLE'
}
& git -C $repository cat-file -e "$mirrorSourceCommit^{commit}" 2>$null
if ($LASTEXITCODE -ne 0) {
    throw 'OFFLINE_MIRROR_SOURCE_COMMIT_UNAVAILABLE'
}
Assert-DependencyInputsUnchanged $mirrorSourceCommit $head
$snapshot = [string]$mirrorManifest.snapshot
Assert-Mirror $mirrorManifest $snapshot
New-Item -ItemType Directory -Force -Path $root | Out-Null

$hashes = @()
$buildEvidence = @()
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Inspect-ReleaseJar([string]$Jar, [string]$Label, [string]$Source, [string]$GradleHome) {
    $archive = [IO.Compression.ZipFile]::OpenRead($Jar)
    try {
        $rows = [Collections.Generic.List[string]]::new()
        $names = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        $fileTimestamps = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        $directoryTimestamps = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        $manifest = $null
        $position = 0
        foreach ($entry in $archive.Entries) {
            if (-not $names.Add($entry.FullName)) { throw "DUPLICATE_JAR_ENTRY: $($entry.FullName)" }
            $time = $entry.LastWriteTime.UtcDateTime.ToString('o')
            if ($entry.FullName.EndsWith('/', [StringComparison]::Ordinal)) { [void]$directoryTimestamps.Add($time) }
            else { [void]$fileTimestamps.Add($time) }
            $sha = [Security.Cryptography.SHA256]::Create()
            $stream = $entry.Open()
            try { $entryHash = ([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-','').ToLowerInvariant() }
            finally { $stream.Dispose(); $sha.Dispose() }
            $rows.Add("$position`t$($entry.FullName)`t$($entry.Length)`t$($entry.CompressedLength)`t$time`t$entryHash")
            if ($entry.FullName -eq 'META-INF/MANIFEST.MF') {
                $reader = [IO.StreamReader]::new($entry.Open())
                try { $manifest = $reader.ReadToEnd() } finally { $reader.Dispose() }
            }
            $position++
        }
        if ($null -eq $manifest -or $manifest -notmatch '(?m)^Dirty-Worktree: false\r?$') { throw "RELEASE_MANIFEST_DIRTY_OR_MISSING: build=$Label" }
        if ($fileTimestamps.Count -ne 1 -or $directoryTimestamps.Count -gt 1) { throw "NONDETERMINISTIC_ENTRY_TIMESTAMPS: build=$Label" }
        $entryList = Join-Path $root "build-$Label-entry-list.tsv"
        @("index`tname`tlength`tcompressedLength`tutcTimestamp`tsha256") + $rows | Set-Content -LiteralPath $entryList -Encoding utf8
        return [ordered]@{
            label=$Label; source=$Source; gradleUserHome=$GradleHome; offline=$true
            jar=$Jar; jarSha256=Get-Sha256 $Jar; jarBytes=(Get-Item -LiteralPath $Jar).Length
            entryCount=$rows.Count; fileEntryTimestamp=@($fileTimestamps)[0]
            directoryEntryTimestamp=if ($directoryTimestamps.Count -eq 1) { @($directoryTimestamps)[0] } else { $null }
            entryList=$entryList; entryListSha256=Get-Sha256 $entryList; manifest=$manifest
        }
    } finally { $archive.Dispose() }
}

foreach ($label in @('A', 'B')) {
    $source = Join-Path $root "source-$label"
    $gradleHome = Join-Path $root "gradle-user-home-$label"
    $buildLog = Join-Path $root "offline-build-$label.log"
    & git clone --quiet --no-hardlinks --local $repository $source
    if ($LASTEXITCODE -ne 0) { throw "OFFLINE_CLEAN_CLONE_FAILED: $label" }
    if (Test-Path -LiteralPath (Join-Path $source 'local.properties')) { throw "OFFLINE_LOCAL_PROPERTIES_LEAK: $label" }
    if (Test-Path -LiteralPath (Join-Path $source 'work\local-maven')) { throw "OFFLINE_DEVELOPER_MAVEN_LEAK: $label" }
    Materialize-Mirror $mirrorManifest $snapshot $gradleHome
    $oldJava = $env:JAVA_HOME
    $oldGradle = $env:GRADLE_USER_HOME
    try {
        $env:JAVA_HOME = $JavaHome
        $env:GRADLE_USER_HOME = $gradleHome
        Push-Location $source
        try {
            & $GradleExecutable --offline --no-daemon --console=plain clean build *> $buildLog
            $buildExit = $LASTEXITCODE
        } finally { Pop-Location }
    } finally {
        $env:JAVA_HOME = $oldJava
        $env:GRADLE_USER_HOME = $oldGradle
    }
    if ($buildExit -ne 0) {
        Get-Content -LiteralPath $buildLog -Tail 120
        throw "OFFLINE_BUILD_FAILED: $label exit=$buildExit"
    }
    $jar = Join-Path $source 'forge-create-1.20.1\build\libs\steve-industrial-agent-0.1.0-alpha.1.jar'
    if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw "OFFLINE_RELEASE_JAR_MISSING: $label" }
    $evidence = Inspect-ReleaseJar $jar $label $source $gradleHome
    $hashes += $evidence.jarSha256
    $buildEvidence += $evidence
}

Assert-Mirror $mirrorManifest $snapshot
if ($hashes[0] -ne $hashes[1]) {
    Compare-Object (Get-Content -LiteralPath $buildEvidence[0].entryList) (Get-Content -LiteralPath $buildEvidence[1].entryList) |
            Out-String | Set-Content -LiteralPath (Join-Path $root 'entry-diff.txt') -Encoding utf8
    throw "REPRODUCIBLE_BUILD_MISMATCH: buildA=$($hashes[0]) buildB=$($hashes[1])"
}
$foundation = (& git -C $repository rev-parse 'generic-foundation-v1^{}').Trim()
$result = [ordered]@{
    schema='steve-industrial:reproducible-release/v3'; status='PASS'; gitCommit=$head
    genericFoundation=$foundation; javaMajor=17; gradle='8.4'
    mirrorSourceCommit=$mirrorSourceCommit; dependencyInputsUnchanged=$true
    onlineColdBuildEvidence=$mirrorManifest.sourceOnlineEvidence
    onlineColdBuildSha256=Get-Sha256 $mirrorManifest.sourceOnlineEvidence
    dependencyMirror=$mirror; dependencyMirrorManifest=$manifestPath
    dependencyMirrorManifestSha256=Get-Sha256 $manifestPath
    dependencyMirrorAggregateSha256=$mirrorManifest.aggregateSha256
    mirrorUnchangedAfterBuilds=$true; networkFallbackAllowed=$false
    cleanCheckouts=2; isolatedGradleHomes=2; offlineBuilds=2
    mirrorMaterializedIndependently=$true; cacheAReusedByB=$false; byteIdentical=$true
    jarSha256=$hashes[0]; onlineDependencyResolutionValidated=$true; builds=$buildEvidence
    completedAtUtc=(Get-Date).ToUniversalTime().ToString('o')
}
$result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $root 'reproducible-build.json') -Encoding utf8
New-Item -ItemType Directory -Force -Path $evidenceDirectory | Out-Null
$result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $evidenceDirectory 'reproducible-build-current.json') -Encoding utf8
Write-Output "REPRODUCIBLE_RELEASE PASS commit=$head mirrorSourceCommit=$mirrorSourceCommit dependencyInputsUnchanged=true sha256=$($hashes[0]) onlineColdBuilds=1 offlineBuilds=2 byteIdentical=true cleanCheckouts=2 isolatedGradleHomes=2 mirrorUnchanged=true networkFallbackAllowed=false entryCount=$($buildEvidence[0].entryCount)"
